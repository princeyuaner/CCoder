package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.BorderFactory

/** 输入框内边距（垂直, 水平），未缩放 px。 */
private const val PADDING_V = 5
private const val PADDING_H = 7

/**
 * 把多行输入框做成"看得出是输入框"的样子。
 *
 * 原来只有 `JBUI.Borders.empty(6)` —— 那是内边距，**完全不画线**，
 * 于是输入区与转写区糊成一片分不清（用户反馈）。
 *
 * 边框颜色用 `Component.borderColor`，即 IDE 给输入类控件用的那条线；
 * 取不到时回退到 `JBColor.border()`。
 *
 * 线边框与内边距用 CompoundBorder 组合：只画线不留内边距的话，文字会贴着
 * 线，看起来像"被框住的段落"而不是输入框。
 *
 * 抽成独立函数是为了可测 —— ClaudePanel 依赖 Project，起不了单测。
 */
internal fun styleComposerInput(area: JBTextArea) {
    area.background = UIUtil.getTextFieldBackground()
    area.border = BorderFactory.createCompoundBorder(
        JBUI.Borders.customLine(JBColor.namedColor("Component.borderColor", JBColor.border()), 1),
        JBUI.Borders.empty(PADDING_V, PADDING_H),
    )
}
