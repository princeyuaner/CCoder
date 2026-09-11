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
 * 输入框的默认行数。
 *
 * **不做自动长高**（2026-09-11 用户明确要求）：高度由用户拖分隔条决定，
 * 输入框只负责在拖出来的空间里撑满。这样"我写多长"和"它占多高"两件事
 * 解耦，不会因为粘一段长文本就把面板顶开。
 */
internal const val COMPOSER_ROWS = 3

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
 * 输入区：输入框在上，控件工具栏在下。
 *
 * 工具栏放 SOUTH 而不是把按钮摆在输入框右边（原来那样）—— 右边放不下
 * 以后的模型切换、权限模式、用量读数；摆下面则加控件只是往工具栏左侧添，
 * 不必再动结构。
 */
internal fun buildComposerArea(
    inputScroll: JComponent,
    toolbar: JComponent,
): JPanel = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(4, 8)
    add(inputScroll, BorderLayout.CENTER)
    add(toolbar, BorderLayout.SOUTH)
}
