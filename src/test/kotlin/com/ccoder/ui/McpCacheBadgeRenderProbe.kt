package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.PermissionModeSetting
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：「MCP 缓存徽章」（2026-10-14 选型台乙案，用户选定）与「发送前的
 * 确认框」出图给人眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"什么时候露头、该报哪一档、退不退成点"，
 * 钉不住"琥珀色在这套主题下看不看得清""徽章挤在时钟键左边体量对不对""窄窗
 * 退成点之后那一格还认不认得出来"。
 *
 * **要盯的三件事**：
 * 1. 徽章坐在工具栏右端（时钟键左边）的体量与位置 —— 三态各一张；
 * 2. 窄窗（420）**退成点**的样子；
 * 3. 确认框的内容卡（等它 / 照发 / 本会话不再问）在真机 LAF 下的观感。
 *
 * 跑在真机 LAF（New UI 深色）下 —— 测试 JVM 渲染不了浅色（见 `IdeLaf` 的说明），
 * 浅色那套由选型台的浏览器页（`web/tools/mcp-corner-view.mjs`）覆盖过。
 * 产物在 `build/probe/mcp-badge-*.png`。
 *
 * 场景故意取最坏：模型名用真名 `deepseek-v4.1-flash[1m]`（长标签最挤的那种）。
 */
class McpCacheBadgeRenderProbe {

    /** 探针也要跟着语言走（`-PtestLang=en` 那遍）—— 分隔符从词表取，不写字面量。 */
    private val twoNames = listOf("codegraph", "code-review-graph")
        .joinToString(com.ccoder.text.CcoderText.text("common.listSeparator"))

    @Test
    fun `徽章三态`() {
        renderBadge("build/probe/mcp-badge-pending.png") {
            it.setState(CacheWarnState.Pending(twoNames), worthwhile = true)
        }
        renderBadge("build/probe/mcp-badge-failed.png") {
            it.setState(CacheWarnState.Failed("game-mcp"), worthwhile = true)
        }
        renderBadge("build/probe/mcp-badge-settled.png") {
            // 绿的收尾只在"先前显示过"时才出现：先给一份 pending 把它叫醒
            it.setState(CacheWarnState.Pending("codegraph"), worthwhile = true)
            it.setState(CacheWarnState.Settled, worthwhile = false)
        }
    }

    @Test
    fun `窄窗退成点`() {
        renderBadge("build/probe/mcp-badge-compact.png", width = 420) {
            it.setState(CacheWarnState.Pending(twoNames), worthwhile = true)
        }
    }

    @Test
    fun `确认框的内容卡`() {
        renderConfirmCard("build/probe/mcp-cache-confirm-card.png", names = twoNames)
        renderConfirmCard("build/probe/mcp-cache-confirm-card-nonames.png", names = "")
    }

    /**
     * 一套完整的输入卡切片：真机几何的工具栏（附件 + 三个标签 + 时钟 + 发送），
     * 徽章住在时钟键左边 —— 与 `ClaudePanel` 的装配同一份（`buildComposerToolbar`）。
     */
    private fun renderBadge(path: String, width: Int = 560, drive: (McpCacheBadge) -> Unit) {
        IdeLaf.withRealLaf {
            SwingUtilities.invokeAndWait {
                val badge = McpCacheBadge(onOpenSettings = {})
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
                    ModelLabel {}.apply { setProfile(ModelProfile(name = "deepseek-v4.1-flash[1m]")) },
                    ModeLabel {}.apply { setMode(PermissionModeSetting.DEFAULT) },
                    EffortLabel {}.apply { setEffort(EffortSetting.HIGH) },
                    RoundSendButton().apply {
                        setState(mainButtonState(ready = true, busy = false, disconnected = false))
                    },
                    ScheduleButton {},
                    badge,
                )
                val card = buildComposerCard(inputScroll, toolbar)

                drive(badge)

                val column = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = true
                    background = UIUtil.getPanelBackground()
                    add(card.apply { alignmentX = LEFT_ALIGNMENT })
                }

                // 两遍：FlowLayout 那类"放不下就换行/量宽"要看容器**已经**多宽才答得准
                // （本仓 2026-09-14 那次"卡片内容被裁剪"的假阳性就是量在第 0 帧）
                column.setSize(width, 200)
                repeat(2) { layoutAll(column) }

                // 数字版：徽章可见/退成点/字是什么，不用看图就能对账
                println(
                    "[mcp-badge-probe] ${File(path).name} 行宽=$width 徽章可见=${badge.isVisible} " +
                        "退成点=${badge.isCompact()} 徽章首选宽=${badge.preferredSize.width} " +
                        "文字=\"${badge.badgeText()}\"",
                )

                val h = column.preferredSize.height
                column.setSize(width, h)
                repeat(2) { layoutAll(column) }

                val img = BufferedImage(width, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                column.paint(g)
                g.dispose()
                ImageIO.write(img, "png", File(path))

                // 绿的收尾/确认那条会留一个单发 Timer 在跑 —— 出完图就撤掉，
                // 别让探针 JVM 结尾时还有定时器攥着组件
                badge.dismiss()
            }
        }
    }

    /** 确认框内容卡：垫一层面板底当"对话框表面"，尺寸按卡片首选给。 */
    private fun renderConfirmCard(path: String, names: String) {
        IdeLaf.withRealLaf {
            SwingUtilities.invokeAndWait {
                val card = McpCacheConfirmCard(names) {}
                val pad = JBUI.scale(10)
                val outer = JPanel(BorderLayout()).apply {
                    isOpaque = true
                    background = UIUtil.getPanelBackground()
                    border = JBUI.Borders.empty(pad)
                    add(card, BorderLayout.CENTER)
                }
                val w = card.preferredSize.width + pad * 2
                val h = card.preferredSize.height + pad * 2
                println("[mcp-badge-probe] ${File(path).name} 卡片首选=${card.preferredSize.width}x${card.preferredSize.height}")
                outer.setSize(w, h)
                repeat(2) { layoutAll(outer) }

                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                outer.paint(g)
                g.dispose()
                ImageIO.write(img, "png", File(path))
            }
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
