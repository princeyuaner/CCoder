package com.ccoder.ui

import com.ccoder.settings.PermissionModeSetting
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

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

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIUtil.getInactiveTextColor()
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpen()
                }
            }
        )
    }

    fun setMode(mode: PermissionModeSetting) {
        text = mode.label + EXPAND_CARET
        foreground = modeColor(mode)
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
        foreground = warningColor()
        repaint()
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
 * 光有"仅规划""不询问"这种名字，用户不知道选下去意味着什么 —— 而这几个
 * 字改变的是 Claude 被允许做什么，值得说清楚。
 */
internal fun modeDescription(mode: PermissionModeSetting): String = when (mode) {
    PermissionModeSetting.DEFAULT -> "危险操作会先询问"
    PermissionModeSetting.ACCEPT_EDITS -> "文件改动自动接受，其余仍询问"
    PermissionModeSetting.PLAN -> "只读：只做计划，不执行工具"
    PermissionModeSetting.DONT_ASK -> "不询问；未预先允许的一律拒绝"
    PermissionModeSetting.BYPASS_PERMISSIONS -> "所有操作都不再询问"
}

/**
 * 模式列表。当前项打勾。
 *
 * 全部列出、包括绕过 —— 用户明确要了"5 个都列，直接生效"。取走掉
 * "绕过权限"的做法会让这个下拉在半数场景下答非所问（"我要切回绕过"）。
 */
internal fun buildModeList(
    current: PermissionModeSetting,
    onPick: (PermissionModeSetting) -> Unit,
): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(4, 4)
    PermissionModeSetting.entries.forEach { add(modeRow(it, it == current, onPick)) }
}

private fun modeRow(
    mode: PermissionModeSetting,
    selected: Boolean,
    onPick: (PermissionModeSetting) -> Unit,
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
    val name = JLabel((if (selected) MARK else " ") + " " + mode.label).apply {
        foreground = modeColor(mode)
        font = if (selected) base.deriveFont(Font.BOLD) else base
    }

    val desc = JLabel(modeDescription(mode)).apply {
        foreground = UIUtil.getInactiveTextColor()
        font = base.deriveFont(base.size2D - 1f)
    }

    row.add(name, BorderLayout.NORTH)
    row.add(desc, BorderLayout.SOUTH)

    // 整行可点，不只是文字那一小块 —— 一行里拆成了两个标签，
    // 点到描述上没反应会显得很钝
    val handler = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            onPick(mode)
        }
    }
    listOf(row, name, desc).forEach { it.addMouseListener(handler) }

    return row
}
