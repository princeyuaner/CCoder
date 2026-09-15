package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.PermissionModeSetting
import com.intellij.ui.components.JBScrollPane
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
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：排队条与输入区放在一起，出两张图。
 *
 * 没有断言，也不该有 —— 单测能钉住"超过三行折成还有 N 条"、能钉住截断，
 * 钉不住"空队列时那块到底有没有凭空多出一截空白"。而后者正是这次改动的
 * 理由之一（spec §6），不该只靠信念。
 *
 * 结构照抄 `ClaudePanel` 的 header：状态卡 → strut → 排队条 → 输入卡。
 * 这组图第一版就抓到两处单测看不见的错：**「排队 N」被居中**、
 * 整条带子被摆到 x=113（见 QueueStrip 里那两处 alignmentX 的说明）。
 *
 * 产物在 `build/queue-probe-*.png`。改了排队条或输入区就跑一下看一眼。
 */
class QueueStripRenderProbe {

    @Test
    fun `空队列——不该多出任何空白`() = render("build/queue-probe-empty.png", emptyList())

    @Test
    fun `一条——内容直接写在那一行上`() =
        render("build/queue-probe-1.png", listOf("等一下，先别动 README" to "等一下，先别动 README"))

    @Test
    fun `三条收着——只剩一行`() =
        render("build/queue-probe-3.png", threeQueued())

    @Test
    fun `三条展开——列表压在输入卡上方`() =
        render("build/queue-probe-3-open.png", threeQueued(), expanded = true)

    /** 第二条故意长且多行：看截断与折行。 */
    private fun threeQueued() = listOf(
        "等一下，先别动 README" to "等一下，先别动 README",
        "跑完把这几个测试补一下，顺便看看 web/src/components/ToolCallBlock.tsx 那边" +
            "有没有跟着改" to "原始的多行文本\n第二行\n第三行",
        "最后再跑一遍 gradlew test" to "最后再跑一遍 gradlew test",
    )

    /** @param queued 每条的 (text, typed)。 */
    private fun render(
        path: String,
        queued: List<Pair<String, String>>,
        expanded: Boolean = false,
    ) {
        SwingUtilities.invokeAndWait {
            val queue = SendQueue().apply {
                for ((text, typed) in queued) enqueue(text, typed)
            }
            val strip = QueueStrip {}.apply {
                setModel(queueStripModel(queue))
                if (expanded) setExpanded(true)
            }

            val cards = StatusCardsRow(onOpenContext = {}, onOpenTodos = {}, onOpenRunning = {})
                .apply {
                    connection.setModel(connectionCardOf("已连接"))
                    context.setModel(
                        contextCardOf(ContextUsage(usedTokens = 12300, windowTokens = 200000))
                    )
                }

            val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
                lineWrap = true
                styleComposerInput(this)
                text = ""
            }
            val inputScroll = JBScrollPane(input).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }
            // 忙闲跟着队列走：有条排着就说明正忙 —— 按钮那时该写「停止」
            val toolbar = buildComposerToolbar(
                ModelLabel {}.apply { setProfile(ModelProfile(name = "Sonnet 4.5")) },
                ModeLabel {}.apply { setMode(PermissionModeSetting.DEFAULT) },
                EffortLabel {}.apply { setEffort(EffortSetting.HIGH) },
                RoundSendButton().apply {
                    setState(
                        mainButtonState(
                            ready = true,
                            busy = !queue.isEmpty,
                            disconnected = false,
                            queued = queue.size,
                        )
                    )
                },
            )
            val card = buildComposerCard(inputScroll, toolbar)

            // 与 ClaudePanel 的 header 逐字同构（含那三处对齐）——
            // 这组图要看的就是这几层摆得对不对
            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                cards.alignmentX = java.awt.Component.LEFT_ALIGNMENT
                add(cards)
                // strut 的宽度是 0，在对齐的加权平均里权重也是 0 —— 不必管它
                add(Box.createVerticalStrut(JBUI.scale(7)))
                add(strip)
            }

            val outer = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(6, 8, 8, 8)
                background = UIUtil.getPanelBackground()
                add(header, BorderLayout.NORTH)
                add(card, BorderLayout.CENTER)
            }

            val w = 430
            val h = 260
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
