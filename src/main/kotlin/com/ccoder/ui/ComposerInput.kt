package com.ccoder.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI

/** 输入框内边距（垂直, 水平），未缩放 px。 */
private const val PADDING_V = 5
private const val PADDING_H = 7

/**
 * 输入框只留内边距，**不画线也不填底**。
 *
 * 边框与底色都归外层卡片（[ComposerCard]）：卡片包住的是整个输入区——
 * 上下文行、输入框、工具栏，边界比只框住文字那一块更明确。
 *
 * 这里若再画一层，就退回了"框里套框"——那正是方案 A 要付的代价，
 * 不能连代价带来的好处也一起丢掉。
 *
 * 抽成独立函数是为了可测 —— ClaudePanel 依赖 Project，起不了单测。
 */
internal fun styleComposerInput(area: JBTextArea) {
    // 不透明会把卡片的底色盖掉，看起来又像嵌了一层
    area.isOpaque = false
    area.border = JBUI.Borders.empty(PADDING_V, PADDING_H)
}

/**
 * 往输入框追加一段片段（右键"添加到 CCoder 聊天框"用）。
 *
 * 追加而不是覆盖：这个动作的用途是**攒上下文** —— 连着扔几段代码再一起问，
 * 覆盖会把上一段吞掉。
 */
internal fun appendSnippet(area: JBTextArea, snippet: String) {
    // 末尾的空白先收干净：不然连着扔两段就会攒出三四行空行
    val existing = area.text.trimEnd()
    area.text = if (existing.isEmpty()) snippet else "$existing\n\n$snippet"
    // 光标停到末尾：追加完接着就能打字
    area.caretPosition = area.text.length
}
