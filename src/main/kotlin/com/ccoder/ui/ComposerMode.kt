package com.ccoder.ui

import com.ccoder.settings.PermissionModeSetting
import com.ccoder.text.CcoderText
import com.google.gson.JsonObject
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel

/** 当前项的标记。实现与测试共用，免得两边各写一个字符然后漂移。 */
internal const val MARK = "✓"

/**
 * 权限模式。可点，点了弹列表切换。
 *
 * ## 为什么是标签不是 JComboBox
 *
 * 下拉框自带 LookAndFeel 的方框与箭头，放进这张圆角卡片里会立刻显得是
 * "嵌进来的另一个控件"—— 发送键当初也是为这个才自绘的。这里只要一行
 * 次要文字。
 *
 * ## 危险模式必须一直看得见
 *
 * 用户选了"进下拉框、直接生效、不拦"，那么"现在开着绕过权限"这件事就
 * 必须**常驻**在界面上。靠人记得自己点过什么是不可靠的，而这里的代价是
 * "所有操作不再询问"。所以绕过模式用警示色，且不做成"过一会儿淡回去"。
 *
 * 注意它只负责**显示**当前模式。切过去之后什么时候改这个标签，由
 * [ClaudePanel] 决定 —— 那边等 sidecar 的回执，不等的话切换失败时
 * 标签就会显示一个没生效的模式。
 */
internal class ModeLabel(private val onOpen: () -> Unit) : JLabel() {

    private var hovered = false

    /**
     * 不悬停时该显示的颜色（模式色 / 警示色）。
     *
     * 悬停只是临时顶掉它，移开要原样放回来 —— 绕过的警示色不能因为鼠标
     * 划过一趟就丢了。
     */
    private var resting: Color = UIUtil.getInactiveTextColor()

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIUtil.getInactiveTextColor()
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpen()
                }

                // 可点就该有反馈：这行看着就是普通灰字，不给反馈没人知道它能点。
                // 与 [ModelLabel] 同一套写法 —— 两个标签并排站着，手感得一样
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

    fun setMode(mode: PermissionModeSetting) {
        text = mode.label + EXPAND_CARET
        resting = modeColor(mode)
        applyForeground()
        repaint()
    }

    /**
     * 显示"本会话不再询问"。
     *
     * 这**不是** SDK 的模式之一，是插件侧本会话的状态，所以它不是
     * [PermissionModeSetting] 的一个枚举项。但它必须显示出来：开着自动放行
     * 却还显示「标准」，用户会以为 Claude 还会问 —— 而这个标签的职责
     * 恰恰是"Claude 现在被允许做什么"。
     *
     * 与 [setMode] 二选一，由 [ClaudePanel] 按当前状态决定调哪个。
     */
    fun setAutoAllow() {
        text = AUTO_ALLOW_LABEL + EXPAND_CARET
        resting = warningColor()
        applyForeground()
        repaint()
    }

    private fun applyForeground() {
        foreground = if (hovered) UIUtil.getLabelForeground() else resting
    }
}

/**
 * 模式对应的文字颜色。
 *
 * 只有绕过往外跳 —— 其余模式都是"现状"，安静地待着就好。要是每个模式
 * 都上一个颜色，等到真的是绕过时就没人会注意到了。
 */
internal fun modeColor(mode: PermissionModeSetting): Color =
    if (mode.requiresDangerousOptIn) warningColor() else UIUtil.getInactiveTextColor()

/**
 * 模式的一句话说明。
 *
 * 2026-09-15 起这句话住在枚举上（[PermissionModeSetting.description]）——
 * 设置对话框的「权限」页也要用它，而 `settings` 包不许反过来依赖 `ui`。
 * 留这个名字是因为它在这儿读起来更顺，内容是**同一份**，不是抄一份。
 */
internal fun modeDescription(mode: PermissionModeSetting): String = mode.description

/**
 * 从 CLI 的 `system/status` 事件里读出**此刻真正在跑**的权限模式；不是那条事件就给 null。
 *
 * 为什么要读它（2026-09-16 实测，`sidecar/tools/probe-auto-mode.mjs`）：切完模式
 * CLI 会吐一条 status，里面带着 `permissionMode` —— 这是**唯一一条能读回
 * 生效模式**的路。从前"切成功"的依据只是控制请求没报错，而"会话以什么模式起来"
 * 连问都问不到：闸门（`permissions.disableAutoMode`、订阅档、断路器）在 CLI 侧，
 * 它在启动时换了档我们看不见，标签就会一直显示用户选的那个。
 *
 * 认不出的值一律给 null，**不猜** —— 与旁边那个回执分支同一条规矩：
 * 显示一个我们自己都不认识的模式，不如保持原样。
 */
internal fun permissionModeOfStatus(event: JsonObject): PermissionModeSetting? {
    if (event.str("type") != "system" || event.str("subtype") != "status") return null
    val wire = event.str("permissionMode") ?: return null
    return PermissionModeSetting.entries.firstOrNull { it.wireValue == wire }
}

// 与 ui 包里其它文件同一写法：每个文件自带一份，别为这个把包结构改了
private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

/**
 * 模式列表。当前项打勾。
 *
 * 全部列出、包括绕过 —— 用户明确要了"全都列出来，直接生效"。取走掉
 * "绕过权限"的做法会让这个下拉在半数场景下答非所问（"我要切回绕过"）。
 *
 * **分两张卡**（2026-09-21 改，设计稿 `docs/design/mode-effort-picker.html` 的 C 案）：
 * 上面是还会按规则问你的常规几个，下面是"不再问你"的那两个 —— 后者描一圈琥珀边。
 * 刀口落在枚举本来就有的那道缝上（[PermissionModeSetting.PLAN] 与
 * [PermissionModeSetting.DONT_ASK] 之间），**一项都不重排**。
 *
 * 为什么值得分：绕过的后果是"所有操作都不再询问"，而它原先只是六个灰名字里的
 * 一个琥珀色名字。分完组，这件事由**结构**说出来了，不靠用户去读那一行说明。
 *
 * 卡与行本身住在 [PickerList] —— 与模型、思考那两个弹层是同一套东西。
 */
internal fun buildModeList(
    current: PermissionModeSetting,
    onPick: (PermissionModeSetting) -> Unit,
): JComponent = cardColumn().apply {
    ModeGroup.entries.forEach { group ->
        val modes = PermissionModeSetting.entries.filter { modeGroup(it) == group }
        if (modes.isEmpty()) return@forEach
        addCard(optionGroup(group.title, group.warn, modes.map { modeRow(it, it == current, onPick) }))
    }
}

/**
 * 这个模式归哪一组。
 *
 * 穷尽式 `when`（同 [effortDescription]）：将来 SDK 加了模式，这里会是**编译错误**，
 * 而不是一条悄悄漏在两张卡外面的模式。
 */
internal fun modeGroup(mode: PermissionModeSetting): ModeGroup = when (mode) {
    PermissionModeSetting.DEFAULT,
    PermissionModeSetting.ACCEPT_EDITS,
    PermissionModeSetting.AUTO,
    PermissionModeSetting.PLAN,
    -> ModeGroup.REGULAR

    PermissionModeSetting.DONT_ASK,
    PermissionModeSetting.BYPASS_PERMISSIONS,
    -> ModeGroup.SILENT
}

/**
 * 权限模式的两组。
 *
 * 组名**故意不写"会先问你"**：「仅规划」是只读、根本不执行工具，说它会问是反的
 * （2026-09-21 定的词）。「常规」不含这个断言，第二组的「不再问你」也与那两档的
 * 说明对得上 —— 两个名字都不撒谎。
 */
internal enum class ModeGroup(private val titleKey: String, val warn: Boolean) {
    REGULAR("composer.mode.group.regular", warn = false),
    SILENT("composer.mode.group.silent", warn = true),
    ;

    /** 界面上的组名。取一次读一次词表 —— 语言变了下次取就是新语言。 */
    val title: String get() = CcoderText.text(titleKey)
}

private fun modeRow(
    mode: PermissionModeSetting,
    selected: Boolean,
    onPick: (PermissionModeSetting) -> Unit,
): JComponent = optionRow(
    name = mode.label,
    // 只有绕过是警示色。与标签那颗（[modeColor]）**不同**，那是有意的：
    // 标签是状态显示，那里的颜色是"现在开着绕过"的常驻提醒；这里是列表，
    // 若每个模式都上色，等到真是绕过时就没人会注意到了
    nameColor = if (mode.requiresDangerousOptIn) warningColor() else UIUtil.getLabelForeground(),
    description = modeDescription(mode),
    selected = selected,
    onClick = { onPick(mode) },
)
