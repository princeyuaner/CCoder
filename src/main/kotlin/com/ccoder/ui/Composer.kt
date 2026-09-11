package com.ccoder.ui

import com.ccoder.settings.SendShortcut
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel

// ---- 输入框高度 ----

/**
 * 输入框的最小行数 —— 它同时也是"拖到最小能有多矮"的地板。
 *
 * **不做自动长高**（2026-09-11 用户明确要求）：高度由用户拖分隔条决定，
 * 输入框只负责在拖出来的空间里撑满。这样"我写多长"和"它占多高"两件事
 * 解耦，不会因为粘一段长文本就把面板顶开。
 *
 * 取 1 而不是 3：它经 preferredSize 变成布局的最小高度，而这个最小高度会
 * 反过来顶住分隔条的默认比例 —— 设为 3 时最小值约 97px，把默认比例往下调
 * 也压不下去，表现为"改了默认高度却没变"。
 */
internal const val COMPOSER_MIN_ROWS = 1

// ---- 输入框本体 ----

/**
 * 竖向撑满视口的输入框。
 *
 * `JTextArea` 默认不随视口长高（`getScrollableTracksViewportHeight()`
 * 返回 false），把它放进滚动面板后，拖高输入区只会多出一片空白 ——
 * 输入框本身纹丝不动，看起来像"拖了没用"。
 */
internal class ComposerTextArea(rows: Int, cols: Int) : JBTextArea(rows, cols) {
    override fun getScrollableTracksViewportHeight(): Boolean = true
}

// ---- 发送快捷键 ----

/**
 * 这个按键该不该触发发送。
 *
 * 只判这几个键：**Shift+Enter 在两种约定下都留给换行** —— 用户在
 * Ctrl+Enter 模式下想换行时，多半会先试 Shift+Enter。
 */
internal fun isSendKey(
    keyCode: Int,
    shiftDown: Boolean,
    ctrlDown: Boolean,
    shortcut: SendShortcut,
): Boolean {
    if (keyCode != KeyEvent.VK_ENTER) return false
    return when (shortcut) {
        SendShortcut.ENTER -> !shiftDown
        SendShortcut.CTRL_ENTER -> ctrlDown
    }
}

// ---- 输入区布局 ----

/**
 * 输入区，自上而下三段：
 *
 *   NORTH  上下文长度条（无数据时整条隐藏）
 *   CENTER 输入框（撑满可用高度）
 *   SOUTH  控件工具栏（发送/停止在右，左侧留给以后的模型切换等）
 *
 * 工具栏放 SOUTH 而不是把按钮摆在输入框右边（原来那样）—— 右边放不下
 * 以后的模型切换、权限模式、用量读数；摆下面则加控件只是往工具栏左侧添，
 * 不必再动结构。
 */
internal fun buildComposerArea(
    contextBar: JComponent,
    inputScroll: JComponent,
    toolbar: JComponent,
): JPanel = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(4, 8)
    add(contextBar, BorderLayout.NORTH)
    add(inputScroll, BorderLayout.CENTER)
    add(toolbar, BorderLayout.SOUTH)
}
