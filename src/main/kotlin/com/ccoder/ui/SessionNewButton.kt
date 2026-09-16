package com.ccoder.ui

import com.intellij.util.ui.UIUtil

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
 * ## 为什么到上限才置灰
 *
 * 会话标签走的是"点了才说"（弹出后才给说明），因为它有内容可看。
 * 而「＋」是一个单一动作按钮 —— 点了没反应更像坏了。所以它直接置灰，
 * 并把"先做什么"写进 tooltip。
 *
 * **2026-09-16**：多标签之后它不再停掉当前会话，于是"忙"不再是置灰的理由
 * （见 [newTabEnabled]），唯一的闸变成标签数到上限。
 *
 * 只负责显示与点击；到没到上限由 [newTabEnabled] 判定。
 * 造型与齿轮共用一份（[TopRowIconButton]）—— 同一行里两个按钮得长得一样。
 */
internal class SessionNewButton(private val onClick: () -> Unit) :
    TopRowIconButton(TopRowGlyph.Plus) {

    init {
        addActionListener { onClick() }
        setTabState(1)
    }

    /**
     * 按**当前标签数**刷新。**判定与画分开** —— ClaudePanel 起不了单测，
     * 所以 [newTabEnabled] 是纯函数，这里只把结果画出来。
     */
    fun setTabState(tabCount: Int) {
        isEnabled = newTabEnabled(tabCount)
        toolTipText = newTabTooltip(tabCount)
        foreground = if (isEnabled) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
    }
}
