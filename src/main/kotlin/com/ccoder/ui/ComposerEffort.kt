package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

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
internal fun effortLabelText(setting: EffortSetting): String = "思考·" + setting.label

/**
 * 切换弹层的内容。当前项打勾。
 *
 * 第二行的说明**必须把「哪些档分模型」讲在明面上**：xhigh 与 max 在不支持的
 * 模型上会被 CLI 静默降级，而界面上没有任何别的办法能看出来。与其让用户
 * 以为自己选了「最大」就真的最大，不如一开始就说清楚前提。
 */
internal fun buildEffortList(
    current: EffortSetting,
    onPick: (EffortSetting) -> Unit,
): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(4, 4)
    EffortSetting.entries.forEach { add(effortRow(it, it == current, onPick)) }
}

/**
 * 一句话说明这一档是什么。
 *
 * 穷尽式 `when`（同 [modeDescription]）：将来 SDK 加了新档位，这里会是
 * 编译错误，而不是一条空着的说明。
 */
internal fun effortDescription(setting: EffortSetting): String = when (setting) {
    EffortSetting.DEFAULT -> "不干预，按模型自己的默认档执行"
    EffortSetting.LOW -> "最少思考，最快回复"
    EffortSetting.MEDIUM -> "适度思考"
    EffortSetting.HIGH -> "深度推理（CLI 的默认档）"
    EffortSetting.XHIGH -> "比「高」更深；仅部分模型认，其余降级为「高」"
    EffortSetting.MAX -> "最高档；仅少数模型认，其余会降级"
}

private fun effortRow(
    setting: EffortSetting,
    selected: Boolean,
    onPick: (EffortSetting) -> Unit,
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，
    // deriveFont 会直接 NPE（RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(3, 6)
    }

    // 未选中的行也缩进同样的宽度，勾出现或消失时文字不会左右跳
    val name = JLabel((if (selected) MARK else " ") + " " + setting.label).apply {
        // 与 [modelRow] 一样用常规文字色：档位名是这一行的主体，
        // 说明在下面一档更淡。模式列表那边用模式色，是因为那里
        // 颜色本身在传达安全性，这里没有那个负担
        foreground = UIUtil.getLabelForeground()
        font = if (selected) base.deriveFont(Font.BOLD) else base
    }

    val desc = JLabel(effortDescription(setting)).apply {
        foreground = UIUtil.getInactiveTextColor()
        font = base.deriveFont(base.size2D - 1f)
    }

    row.add(name, BorderLayout.NORTH)
    row.add(desc, BorderLayout.SOUTH)

    // 整行可点，不只是文字那一小块 —— 一行里拆成了两个标签，
    // 点到说明上没反应会显得很钝
    val handler = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            onPick(setting)
        }
    }
    listOf(row, name, desc).forEach { it.addMouseListener(handler) }

    return row
}
