package com.ccoder.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.Shape
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.Highlighter
import javax.swing.text.JTextComponent

/**
 * 渲染探针：右键加进来的**记号底色到底铺了多宽**。
 *
 * 没有断言，也不该有 —— 断言在 [ComposerInputTest]（量"刷了哪一段"），
 * 这里回答另一个问题：**那一段在屏幕上被画成多大一块**。两者会分叉：
 * `addHighlight(p0, p1, painter)` 里 p0/p1 是对的，可交给 painter 的 `bounds`
 * 是 Swing 自己算的 —— 纯文本组件常常给**整行**（换行视图里甚至给整条组件宽）。
 * 那正是用户报的「背景充满了整个聊天框」。
 *
 * 顺带把两个宽度打进日志：Swing 给的 vs 字形自己的。
 *
 * 产物在 `build/ref-highlight-probe*.png`（3 倍画，字形小，原尺寸看不准）。
 */
class RefHighlightRenderProbe {

    /** 采样用：只记下 Swing 给的 bounds 与"按字形量出来"的那块，自己不画。 */
    private class Sampler : Highlighter.HighlightPainter {
        var given: Rectangle? = null
        var tight: Rectangle? = null

        override fun paint(g: Graphics, p0: Int, p1: Int, bounds: Shape, c: JTextComponent) {
            given = bounds.bounds
            val a = c.modelToView2D(p0)
            val b = c.modelToView2D(p1)
            if (a != null && b != null && a.y == b.y) {
                tight = Rectangle(a.bounds.x, a.bounds.y, maxOf(1, b.bounds.x - a.bounds.x), a.bounds.height)
            }
        }
    }

    @Test
    fun `画出来看看：记号底色铺了多宽`() {
        render("build/ref-highlight-probe.png", text = TOKEN + " 这段能不能合并？")
    }

    @Test
    fun `只有一行记号时（用户报的那种）`() {
        render("build/ref-highlight-probe-only.png", text = TOKEN)
    }

    @Test
    fun `不换行时同样量一遍`() {
        render("build/ref-highlight-probe-nowrap.png", text = TOKEN + " 这段能不能合并？", wrap = false)
    }

    private fun render(
        path: String,
        text: String,
        wrap: Boolean = true,
        rows: Int = COMPOSER_MIN_ROWS,
    ) {
        val input = ComposerTextArea(rows, 40).apply {
            lineWrap = wrap
            wrapStyleWord = true
            styleComposerInput(this)
            this.text = text
        }
        val card = buildComposerCard(
            JBScrollPane(input).apply {
                border = BorderFactory.createEmptyBorder()
                isOpaque = false
                viewport.isOpaque = false
            },
            JPanel().apply { isOpaque = false; preferredSize = Dimension(10, 22) },
        )
        val outer = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            add(card, BorderLayout.NORTH)
        }

        val width = 420
        val height = outer.preferredSize.height
        outer.setSize(width, height)
        layoutAll(outer)

        // 真界面走的就是这一条
        applyRefHighlights(input)
        // 再挂一个只采样不画的，看 Swing 给我们的是哪一块
        val sampler = Sampler()
        for (r in refRanges(input.text)) {
            input.highlighter.addHighlight(r.first, r.last + 1, sampler)
        }
        layoutAll(outer)

        val scale = 3.0
        val img = BufferedImage((width * scale).toInt(), (height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.scale(scale, scale)
        outer.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(path))

        println("[ref-probe] ${File(path).name}")
        println("[ref-probe]   Swing 给的 bounds = ${sampler.given}")
        println("[ref-probe]   字形自己的      = ${sampler.tight}")
        println("[ref-probe]   输入框宽        = ${input.width}")
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutAll(child)
    }

    private companion object {
        val TOKEN = refToken("sidecar/session.js", 24..27)
    }
}
