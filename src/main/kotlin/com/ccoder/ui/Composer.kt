package com.ccoder.ui

import com.ccoder.settings.SendShortcut
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel

// ---- 输入框高度 ----

/** 内容再少也留两行，让输入框看起来是个能写字的地方。 */
internal const val COMPOSER_MIN_ROWS = 2

/**
 * 行数上限，超过转为输入框内部滚动。
 *
 * 不设上限的话，粘一大段代码进来输入框会把整个面板吃掉。
 */
internal const val COMPOSER_MAX_ROWS = 12

/**
 * 输入框应显示的**视觉行数**（含自动折行产生的行）。
 *
 * @param measuredLines 按当前宽度量出来的视觉行数
 */
internal fun composerRows(measuredLines: Int): Int =
    measuredLines.coerceIn(COMPOSER_MIN_ROWS, COMPOSER_MAX_ROWS)

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
