package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：上下文卡那层**水位**（2026-09-17 用户从七个方案里挑的 B）。
 *
 * 没有断言，也不该有 —— 单测钉得住"34% 的水面在第 37 像素""左下角被圆角裁掉"，
 * 钉不住"这点水看着像水位还是像脏了一块""波纹是不是太晃"。后者只有**看**才知道。
 *
 * 三样东西一张图：
 * 1. **相位的四个瞬间**（0 / ¼ / ½ / ¾）并排 —— 动画在静图里只剩这一种看法，
 *    四个相位摆在一起才知道那条波到底长什么样、振幅是不是合适；
 * 2. 同一张卡**放大三倍**：1:1 下 1.6px 的波看不真切；
 * 3. 三档色调（34 蓝 / 76 琥珀 / 93 红）与"压缩中"（那层水一直动）。
 *
 * 在真机 LAF（New UI 深色）底下画 —— 水色是 `mix(面板底, 强调色, 0.16)`，
 * 底色一变换算出来就不是那个数了（[IdeLaf] 的注释里写着同一条教训）。
 * 产物在 `build/probe/context-water.png`。
 */
class ContextWaterRenderProbe {

    @Test
    fun `把水位画成图片`() = render("build/probe/context-water.png")

    private fun render(path: String) {
        IdeLaf.withRealLaf {
            SwingUtilities.invokeAndWait {
                val column = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = true
                    background = UIUtil.getPanelBackground()
                    border = JBUI.Borders.empty(10)
                }

                fun caption(text: String) = column.add(
                    JLabel(text).apply {
                        foreground = UIUtil.getInactiveTextColor()
                        alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        border = JBUI.Borders.empty(8, 0, 4, 0)
                    },
                )

                fun card(percent: Int, phase: Double = 0.0, compacting: Boolean = false): StatusCardView {
                    val usage = ContextUsage(usedTokens = percent * 2_000L, windowTokens = 200_000L)
                    return StatusCardView(icon = CardIcon.Context).apply {
                        setModel(contextCardOf(usage, compacting = compacting))
                        settleWater(percent / 100.0, phase)
                    }
                }

                caption("① 相位 0 / ¼ / ½ / ¾：那条波在静图里是四个瞬间（真实尺寸）")
                column.add(strip(listOf(card(34, 0.0), card(34, 0.25), card(34, 0.5), card(34, 0.75))))

                caption("② 同一张卡放大三倍：1.6px 的波在 1:1 下看不真切")
                column.add(zoomed(card(34)))

                caption("③ 三档色调 + 压缩中：70% 起琥珀、90% 起红；压缩中那层水一直动")
                column.add(strip(listOf(card(34), card(76), card(93), card(60, compacting = true))))

                caption("④ 一排四张（真实上下文：404px 宽，间距 5）")
                column.add(realRow())

                column.add(Box.createVerticalGlue())
                val w = STATUS_ROW_W + JBUI.scale(20)
                column.setSize(w, column.preferredSize.height)
                layoutAll(column)
                val h = column.preferredSize.height

                // 探针的数字版：水色、水面高度、波幅 —— 图看着不对时先看这几个数
                val panel = UIUtil.getPanelBackground()
                println(
                    "[水位探针] 面板底=$panel 水色=${mix(panel, focusColor(), 0.16)} " +
                        "34% 的水面在 y=${waterTopY(0.34, JBUI.scale(56))}（卡高 ${JBUI.scale(56)}） " +
                        "波幅=${JBUI.scale(16) / 10.0}px 波长=${JBUI.scale(22)}px",
                )

                val img = BufferedImage(w, h + JBUI.scale(10), BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                column.paint(g)
                g.dispose()
                File(path).parentFile?.mkdirs()
                ImageIO.write(img, "png", File(path))
            }
        }
    }

    /** 一排等宽的卡（间距照真机的 5px）。 */
    private fun strip(cards: List<StatusCardView>): JPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        cards.forEach { card ->
            card.setSize(CARD_W, CARD_H)
            add(card)
            add(Box.createHorizontalStrut(JBUI.scale(5)))
        }
    }.also { layoutAll(it) }

    /**
     * 放大三倍画一张（用 `g.scale` 而不是把位图拉伸：线条才不糊）。
     *
     * **null 布局**：用 BorderLayout 的话卡片会被拉满整个宿主，缩放 3 倍之后
     * 就只剩左上角一个角露在图里（第一次就是这么画出一张空框的）。
     */
    private fun zoomed(card: StatusCardView): JPanel = object : JPanel(null) {
        init {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            preferredSize = java.awt.Dimension(CARD_W * 3, CARD_H * 3)
            maximumSize = preferredSize
            card.setBounds(0, 0, CARD_W, CARD_H)
            add(card)
        }

        override fun paintChildren(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            g2.scale(3.0, 3.0)
            super.paintChildren(g2)
            g2.dispose()
        }
    }.also { host ->
        host.setSize(CARD_W * 3, CARD_H * 3)
        layoutAll(host)
    }

    /** 真机那一排：404px 里四张等宽。 */
    private fun realRow(): JPanel {
        val row = StatusCardsRow(
            onClear = {}, onCompact = {}, onOpenContext = {}, onOpenTodos = {}, onOpenRunning = {},
        ).apply {
            connection.setModel(connectionCardOf("已连接"))
            context.setModel(contextCardOf(ContextUsage(usedTokens = 68_000, windowTokens = 200_000)))
            todos.setModel(
                todoCardOf(
                    TaskList(listOf(TodoItem("定位调用点", TodoState.Completed), TodoItem("跑测试", TodoState.Pending))),
                ),
            )
            running.setModel(runningCardOf(listOf(RunningTask("t1", null, null, null, 0, 0))))
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            add(row, BorderLayout.CENTER)
            preferredSize = java.awt.Dimension(STATUS_ROW_W, CARD_H)
            maximumSize = preferredSize
        }.also { host ->
            host.setSize(STATUS_ROW_W, CARD_H + 4)
            layoutAll(host)
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    private companion object {
        /** 真机上单张卡的尺寸：一行 404、四张、间距 5 → 97.25 → 取整 97。 */
        val CARD_W = 97
        val CARD_H = 56
        val STATUS_ROW_W = 404
    }
}
