package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Shape
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent

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

/**
 * 记号的底色。
 *
 * 画**背景**而不是前景：`JTextArea` 是纯文本组件，改字符颜色要换成
 * StyledDocument（连带影响输入法、补全、高度那几处），而 Highlighter 只是
 * 在文字底下刷一层 —— 代价小一个数量级，而"它是个东西而不是乱码"这个目的
 * 已经达到了。
 */
private val REF_HIGHLIGHT = object : Highlighter.HighlightPainter {
    override fun paint(
        g: Graphics,
        p1: Int,
        p2: Int,
        bounds: Shape,
        c: JTextComponent,
    ) {
        val r = bounds.bounds
        g.color = refBackground
        g.fillRect(r.x, r.y, r.width, r.height)
    }
}

/** 记号底色。取平台色，取不到时退回一对淡紫（浅色/深色各一）。 */
private val refBackground: Color = JBColor(Color(0xEF, 0xE6, 0xFF), Color(0x2C, 0x25, 0x40))

/**
 * 把输入框里所有记号刷上底色。
 *
 * 每次文本变化都重刷一遍（见 ClaudePanel 的文档监听）—— 记号会被粘贴、
 * 被删、被拆开，逐段维护那几处高亮的生命周期比整批重刷更容易出错。
 * 一次全文扫描是几十微秒的事，而这里每次按键只做一次。
 */
internal fun applyRefHighlights(area: JTextComponent) {
    val highlighter = area.highlighter ?: return
    highlighter.removeAllHighlights()
    for (range in refRanges(area.text)) {
        // 区间越界不该炸掉整个界面 —— 文本正在变的时候（文档事件里）理论上有窗口
        runCatching { highlighter.addHighlight(range.first, range.last + 1, REF_HIGHLIGHT) }
    }
}
