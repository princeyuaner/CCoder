package com.ccoder.ui

import com.intellij.util.ui.UIUtil
import javax.swing.JButton

/**
 * 顶部那一行的「＋」。
 *
 * 位置：**齿轮左边**（见 [buildTopRow]）。2026-09-14 之前它在最右角、
 * 齿轮在左，用户要求对调过 —— 这一句写在这里免得下次又照着旧记忆改。
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
 * 造型与齿轮共用一份（[asTopRowIconButton]）—— 同一行里两个按钮得长得一样。
 */
internal class SessionNewButton(private val onClick: () -> Unit) : JButton("＋") {

    init {
        asTopRowIconButton()
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
