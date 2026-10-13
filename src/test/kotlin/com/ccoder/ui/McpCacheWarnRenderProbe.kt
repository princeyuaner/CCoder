package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.PermissionModeSetting
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：「MCP 还没连完」那条带子的五种形态，出图给人眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"什么时候该开口""该报哪一档"，钉不住
 * "琥珀色在这套主题下看不看得清""最长那句在 420px 栏宽里要不要换行"。
 *
 * **要盯的两件事**：
 * 1. **不显示时一分高度不占**（它是 BoxLayout 里的一个子项，本仓的既有规矩）——
 *    所以每次同时打出"带子可见/隐藏"时那一栏的首选高，数字在日志里；
 * 2. 五种形态的观感与**颜色**：pending 琥珀、落定绿、失败红、确认态那三颗
 *    控件挤不挤。
 *
 * 深浅两套各跑一遍（`-PtestLang=zh` / `en` 也会换文案）——
 * 2026-10-10 的教训：深色下整框黑掉，靠浅色探针一路过关。
 * 产物在 `build/mcp-cache-warn-*.png`。
 */
class McpCacheWarnRenderProbe {

    /** 探针也要跟着语言走（`-PtestLang=en` 那遍）—— 分隔符从词表取，不写字面量。 */
    private val twoNames = listOf("codegraph", "code-review-graph")
        .joinToString(com.ccoder.text.CcoderText.text("common.listSeparator"))

    @Test
    fun `隐藏态 —— 不占高度`() = render("build/mcp-cache-warn-hidden.png") { }

    @Test
    fun `连接中 —— 琥珀`() = render("build/mcp-cache-warn-pending.png") {
        it.setState(CacheWarnState.Pending(twoNames), worthwhile = true)
    }

    @Test
    fun `落定 —— 绿，自己会走`() = render("build/mcp-cache-warn-settled.png") {
        // 绿的收尾只在"先前显示过"时才出现：先给一份 pending 把它叫醒
        it.setState(CacheWarnState.Pending("codegraph"), worthwhile = true)
        it.setState(CacheWarnState.Settled, worthwhile = false)
    }

    @Test
    fun `失败 —— 红，整条可点`() = render("build/mcp-cache-warn-failed.png") {
        it.setState(CacheWarnState.Failed("game-mcp"), worthwhile = true)
    }

    @Test
    fun `确认态 —— 照发 等它 本会话不再问`() = render("build/mcp-cache-warn-confirm.png") {
        it.setState(CacheWarnState.Pending(twoNames), worthwhile = true)
        it.showConfirm()
    }

    private fun render(path: String, drive: (McpCacheWarnBar) -> Unit) {
        IdeLaf.withRealLaf {
            SwingUtilities.invokeAndWait {
                val bar = McpCacheWarnBar(
                    onOpenSettings = {},
                    onSendAnyway = { _ -> },
                    onWait = {},
                )
                drive(bar)

                // 输入卡照抄 ClaudePanel 的装配（同 AttachmentStripRenderProbe 的做法）：
                // 带子长在输入卡上沿，图里就得有那张卡，不然"离得多近"看不见。
                // **不垫 strut**：与卡片之间那口气画在带子自己身上（BOTTOM_GAP，
                // 2026-10-13 用户报"粘在一起"改的）—— 这里再垫一层就是两倍缝
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
                val toolbar = buildComposerToolbar(
                    AttachButton(),
                    ModelLabel {}.apply { setProfile(ModelProfile(name = "Sonnet 4.5")) },
                    ModeLabel {}.apply { setMode(PermissionModeSetting.DEFAULT) },
                    EffortLabel {}.apply { setEffort(EffortSetting.HIGH) },
                    RoundSendButton().apply {
                        setState(mainButtonState(ready = true, busy = false, disconnected = false))
                    },
                )
                val card = buildComposerCard(inputScroll, toolbar)

                val column = JPanel().apply {
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                    isOpaque = true
                    background = UIUtil.getPanelBackground()
                    add(bar.apply { alignmentX = LEFT_ALIGNMENT })
                    add(card.apply { alignmentX = LEFT_ALIGNMENT })
                }

                val w = 430
                val h = 220
                column.setSize(w, h)
                // 两遍：FlowLayout 的"换行后要多高"要看容器**已经**多宽才答得准，
                // 一遍出来的图会是首帧的样子（本仓 2026-09-14 那次"卡片内容被裁剪"
                // 的假阳性就是量在第 0 帧）
                repeat(2) { layoutAll(column) }

                // 数字版：带子藏起来时，整列的首选高应当与"它在上面但不可见"一致 ——
                // BoxLayout 跳过不可见子项是本仓的既有规矩，这里顺手量一眼
                println(
                    "[mcp-warn-probe] ${File(path).name} 带子可见=${bar.isVisible} " +
                        "带子首选高=${bar.preferredSize.height} 整列首选高=${column.preferredSize.height}",
                )
                // 确认态那一格：换行后的高度是 FlowLayout 与 BoxLayout 互相问出来的，
                // 出图看不清"勾被裁了"还是"勾没加进来"时，量一遍最直接
                // （同 AttachmentStripRenderProbe 的 dump 手法；只打这一格 ——
                // 四张都打就成了噪音）
                if (bar.isConfirming()) dump(bar, 0)

                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                column.paint(g)
                g.dispose()
                ImageIO.write(img, "png", File(path))

                // 绿的收尾/确认态会留一个单发 Timer 在跑 —— 出完图就撤掉，
                // 别让探针 JVM 结尾时还有定时器攥着组件
                bar.dismiss()
            }
        }
    }

    /** 组件树、位置、可见性 —— 图上看不出"没加进来"还是"被裁掉"的时候用它。 */
    private fun dump(c: Container, depth: Int) {
        for (child in c.components) {
            val pad = "  ".repeat(depth)
            println(
                "[mcp-warn-tree] $pad${child.javaClass.simpleName} " +
                    "bounds=${child.bounds.x},${child.bounds.y},${child.width}x${child.height} " +
                    "visible=${child.isVisible}",
            )
            if (child is Container) dump(child, depth + 1)
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
