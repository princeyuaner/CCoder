package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.text.CcoderText
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * 思考深度。可点，点了弹列表切换。
 *
 * 与 [ModelLabel]、[ModeLabel] 并排站在输入框左下角，所以手感必须一致：
 * 同样的悬停反馈、同样的展开箭头、同样的"安静次要文字"配色 —— 三个标签
 * 挨着站，其中任何一个反应不一样都会立刻看出来。
 *
 * 它**没有警示档**。[warningColor] 是权限绕过专用的：那个颜色之所以有用，
 * 全靠它是唯一的。借过来给思考深度用，等真的出现绕过时就没人会注意到了。
 *
 * 只负责显示与转发点击 —— 什么时候改这个标签由 [ClaudePanel] 决定，
 * 那边等 sidecar 的回执（同 [ModeLabel]）。
 */
internal class EffortLabel(private val onOpen: () -> Unit) : JLabel() {

    private var hovered = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIUtil.getInactiveTextColor()
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpen()
                }

                // 可点就该有反馈：这行看着就是普通灰字，不给反馈没人知道它能点。
                // 与 [ModelLabel]、[ModeLabel] 同一套写法
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    applyForeground()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    applyForeground()
                }
            }
        )
    }

    fun setEffort(setting: EffortSetting) {
        text = effortLabelText(setting) + EXPAND_CARET
        applyForeground()
        repaint()
    }

    /** 悬停时亮成常规文字色 —— 这是"能点"的唯一提示。 */
    private fun applyForeground() {
        foreground =
            if (hovered) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
    }
}

/**
 * 标签上写什么。
 *
 * 带「思考·」前缀而不是光一个档位名 —— 它旁边就是权限模式的「标准」「仅规划」，
 * 一个孤零零的「高」会被连着读成模式的一部分。分隔符用间隔号，
 * 与 [modelDetail] 里那个保持同一种写法。
 */
internal fun effortLabelText(setting: EffortSetting): String =
    CcoderText.text("composer.effort.label", setting.label)

/**
 * 切换弹层的内容。当前项打勾。
 *
 * 第二行的说明**必须把「哪些档分模型」讲在明面上**：xhigh 与 max 在不支持的
 * 模型上会被 CLI 静默降级，而界面上没有任何别的办法能看出来。与其让用户
 * 以为自己选了「最大」就真的最大，不如一开始就说清楚前提。
 *
 * **分两张卡**（2026-09-21 改，设计稿 `docs/design/mode-effort-picker.html` 的 C 案）：
 * 「通用档」与「仅部分模型认」—— 后者描一圈琥珀边。刀口落在枚举本来就有的
 * 那道缝上（[EffortSetting.HIGH] 与 [EffortSetting.XHIGH] 之间），**一档都不重排**。
 *
 * 为什么值得分：原来那句"仅部分模型认"是**说明里的小字**，而它是选这一档
 * 之前唯一该知道的事。分完组，它成了整张卡的标题。
 *
 * 卡与行本身住在 [PickerList] —— 与模型、权限那两个弹层是同一套东西。
 */
internal fun buildEffortList(
    current: EffortSetting,
    onPick: (EffortSetting) -> Unit,
): JComponent = cardColumn().apply {
    EffortGroup.entries.forEach { group ->
        val levels = EffortSetting.entries.filter { effortGroup(it) == group }
        if (levels.isEmpty()) return@forEach
        addCard(optionGroup(group.title, group.warn, levels.map { effortRow(it, it == current, onPick) }))
    }
}

/**
 * 这一档归哪一组。
 *
 * 穷尽式 `when`（同 [effortDescription]）：将来 SDK 加了档位，这里会是**编译错误**，
 * 而不是一档悄悄漏在两张卡外面的档位。
 */
internal fun effortGroup(setting: EffortSetting): EffortGroup = when (setting) {
    EffortSetting.DEFAULT,
    EffortSetting.LOW,
    EffortSetting.MEDIUM,
    EffortSetting.HIGH,
    -> EffortGroup.GENERAL

    EffortSetting.XHIGH,
    EffortSetting.MAX,
    -> EffortGroup.PARTIAL
}

/**
 * 思考深度的两组。
 *
 * 第二组叫「仅部分模型认」而不是「高级档」：那两档真正的区别不是"更高"，
 * 而是**在不认它的模型上会被静默降级**（[effortDescription] 里写着的就是这句）。
 * 组名说这件事，用户才知道挑它之前要看一眼自己用的是什么模型。
 */
internal enum class EffortGroup(private val titleKey: String, val warn: Boolean) {
    GENERAL("composer.effort.group.common", warn = false),
    PARTIAL("composer.effort.group.partial", warn = true),
    ;

    /** 界面上的组名。取一次读一次词表 —— 语言变了下次取就是新语言。 */
    val title: String get() = CcoderText.text(titleKey)
}

/**
 * 一句话说明这一档是什么。
 *
 * 穷尽式 `when`（同 [modeDescription]）：将来 SDK 加了新档位，这里会是
 * 编译错误，而不是一条空着的说明。
 */
internal fun effortDescription(setting: EffortSetting): String = when (setting) {
    EffortSetting.DEFAULT -> CcoderText.text("composer.effort.default.desc")
    EffortSetting.LOW -> CcoderText.text("composer.effort.low.desc")
    EffortSetting.MEDIUM -> CcoderText.text("composer.effort.medium.desc")
    EffortSetting.HIGH -> CcoderText.text("composer.effort.high.desc")
    EffortSetting.XHIGH -> CcoderText.text("composer.effort.xhigh.desc")
    EffortSetting.MAX -> CcoderText.text("composer.effort.max.desc")
}

private fun effortRow(
    setting: EffortSetting,
    selected: Boolean,
    onPick: (EffortSetting) -> Unit,
): JComponent = optionRow(
    name = setting.label,
    // 用常规文字色：档位名是这一行的主体，说明在下面一档更淡。
    // 权限模式那边用警示色是因为那里的颜色在传达安全性，这里没有那个负担
    nameColor = UIUtil.getLabelForeground(),
    description = effortDescription(setting),
    selected = selected,
    onClick = { onPick(setting) },
)
