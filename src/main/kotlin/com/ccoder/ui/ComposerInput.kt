package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.Shape
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent
import javax.swing.text.Utilities

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
 * 往输入框追加一段（右键那三个动作的落点：加选区 / 加文件 / 项目树加文件）。
 *
 * 追加而不是覆盖：这个动作的用途是**攒上下文** —— 连着扔几样再一起问，
 * 覆盖会把上一样吞掉。
 *
 * **接在后面，不另起一行。** 2026-09-15 用户原话：「添加了代码到聊天窗口后，
 * 再添加文件会出现换行，我希望跟文字一样跟随在后面」。
 *
 * 从前这里固定接 `\n\n` —— 那是**记号之前**的写法：那时扔进来的是一整段代码，
 * 两段之间得空一行才分得开。现在两样东西都只剩一行（`⟦路径 24-27 · 4 行⟧`
 * 与 `@路径 `），再接空行就成了输入框里凭空的空行。
 *
 * 分隔看**前一个字符**：已经以空白收尾就直接接（连着加两个文件不会攒出两个
 * 空格），用户自己敲的回车也留着（他想换行就换行）。
 */
internal fun appendSnippet(area: JBTextArea, snippet: String) {
    val existing = area.text
    val separator = if (existing.isEmpty() || existing.last().isWhitespace()) "" else " "
    area.text = existing + separator + snippet
    // 光标停到末尾：追加完接着就能打字
    area.caretPosition = area.text.length
}

/**
 * 一段文本在屏幕上真正占的那几块（记号被折行时不止一块）。
 *
 * **按字形量，不信 painter 收到的 `bounds`。** 那个 `bounds` 对纯文本组件来说是
 * **整行**：实测一行 179px 宽的记号，交给 painter 的却是 392px（整条输入框减掉
 * 内边距）—— 照它涂就是用户报的「背景充满了整个聊天框」。
 *
 * 越界、或者组件还没排版（`modelToView2D` 返回 null）时，返回**已经量到的**那几块 ——
 * 正在变的文本不该让绘制炸掉。
 */
internal fun refRects(c: JTextComponent, start: Int, end: Int): List<Rectangle> {
    val out = mutableListOf<Rectangle>()
    if (end <= start) return out

    var p = start
    while (p < end) {
        val a = c.modelToView2D(p)?.bounds ?: return out
        val rowLast = Utilities.getRowEnd(c, p)
        // rowLast < p 说明这一行还没排出来（拿不到行尾），直接收到 end —— 不然死循环
        val next = if (rowLast < p || rowLast + 1 >= end) end else rowLast + 1
        val b = c.modelToView2D(next)?.bounds ?: return out
        out += Rectangle(a.x, a.y, maxOf(1, b.x - a.x), a.height)
        p = next
    }
    return out
}

/**
 * 记号的底色。
 *
 * 画**背景**而不是前景：`JTextArea` 是纯文本组件，改字符颜色要换成
 * StyledDocument（连带影响输入法、补全、高度那几处），而 Highlighter 只是
 * 在文字底下刷一层 —— 代价小一个数量级，而"它是个东西而不是乱码"这个目的
 * 已经达到了。
 *
 * 那块底色由 [refRects] 自己量 —— painter 拿到的 `bounds` 是整行，见那里的说明。
 */
private val REF_HIGHLIGHT = object : Highlighter.HighlightPainter {
    override fun paint(
        g: Graphics,
        p0: Int,
        p1: Int,
        bounds: Shape,
        c: JTextComponent,
    ) {
        g.color = refBackground
        for (r in refRects(c, p0, p1)) {
            g.fillRect(r.x, r.y, r.width, r.height)
        }
    }
}

/**
 * 记号底色。取平台色，取不到时退回一对淡紫（浅色/深色各一）。
 *
 * `internal` 是为了让测试能**按像素**找它 —— "底色铺了多宽"这件事只有数像素
 * 才算数（见 [ComposerInputTest] 里那条用户报过的"铺满整行"）。
 */
internal val refBackground: Color = JBColor(Color(0xEF, 0xE6, 0xFF), Color(0x2C, 0x25, 0x40))

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
