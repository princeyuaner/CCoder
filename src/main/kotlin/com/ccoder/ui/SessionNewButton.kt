package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import javax.swing.JButton

/**
 * 状态栏最右的「＋」。
 *
 * ## 为什么是图标不是文字
 *
 * 这一行本来就有状态文字和会话名，420px 下面板真的挤。图标最省地方，
 * 语义靠 tooltip 补（设计稿 §一 A）。
 *
 * ## 为什么忙时是置灰，不是"点了才说"
 *
 * 会话标签走的是"点了才说"（弹出后才给说明），因为它有内容可看。
 * 而「＋」是一个单一动作按钮 —— 点了没反应更像坏了。所以它直接置灰，
 * 并把"先做什么"写进 tooltip。
 *
 * 只负责显示与点击；现在是不是忙、能不能点，由 [newSessionEnabled] 判定。
 */
internal class SessionNewButton(private val onClick: () -> Unit) : JButton("＋") {

    init {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(15f)
        addActionListener { onClick() }
        setBlock(SwitchBlock.None)
    }

    /**
     * 按忙闲刷新。**判定与画分开** —— ClaudePanel 起不了单测，
     * 所以 [newSessionEnabled] 是纯函数，这里只把结果画出来。
     */
    fun setBlock(block: SwitchBlock) {
        isEnabled = newSessionEnabled(block)
        toolTipText = newSessionTooltip(block)
        foreground = if (isEnabled) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
    }
}
