package com.ccoder.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把输入区画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测能钉住"边框是圆角的""条没被压扁"，
 * 钉不住"整体看起来对不对"。它存在的理由是补上这一层：
 *
 * > 输入区曾经看起来完全正常，实际上右边的任务条塌成了一个空的小圆点。
 * > 单测全绿，因为所有测试都在看**属性**，没有一条在**看**它。
 *
 * 产物在 `build/composer-probe.png`。改了输入区的观感就跑一下这个看一眼。
 */
class ComposerRenderProbe {

    @Test
    fun `把输入区画成图片`() {
        SwingUtilities.invokeAndWait {
            val usage = buildUsageLabel().apply {
                text = "上下文  12.3k / 200k · 6%"
                isVisible = true
            }
            val strip = RunStripView {}.apply {
                setStrip(RunStrip(todoProgress = "3/7", currentTask = "修复 extractor 指纹", runningCount = 2))
            }
            val contextRow = buildContextRow(usage, strip)

            val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
                lineWrap = true
                styleComposerInput(this)
                text = "输入消息，Enter 发送"
            }
            val inputScroll = JBScrollPane(input).apply {
                border = com.intellij.util.ui.JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }

            val model = buildModelLabel().apply { text = "Sonnet 4.5 ▾" }
            val send = RoundSendButton().apply {
                setState(mainButtonState(ready = true, busy = false, disconnected = false))
            }
            val toolbar = buildComposerToolbar(model, send)

            val card = buildComposerCard(contextRow, inputScroll, toolbar)

            val outer = JPanel(BorderLayout()).apply {
                border = com.intellij.util.ui.JBUI.Borders.empty(6, 8, 8, 8)
                background = com.intellij.util.ui.UIUtil.getPanelBackground()
                add(card, BorderLayout.CENTER)
            }

            val w = 430
            val h = 150
            outer.setSize(w, h)
            outer.doLayout()
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File("build/composer-probe.png"))
        }
    }

    private fun layoutAll(c: java.awt.Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is java.awt.Container) layoutAll(child)
        }
    }
}
