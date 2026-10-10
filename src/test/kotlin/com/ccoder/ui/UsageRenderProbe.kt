package com.ccoder.ui

import com.ccoder.sidecar.UsageDay
import com.ccoder.sidecar.UsageModelTokens
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 渲染探针：把统计框与常驻条画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"桶数对、顺序对"，钉不住"这张框摆在 620px 里
 * 整体读不读得下去"。选型稿里挑的正是**排版**（V1 + E1），这种事只靠信念不行。
 *
 * **深浅两个主题各画一张**（2026-10-10 的教训：只有浅色探针时，"深色下对话框
 * 整个黑掉"这种问题一路过关 —— 用户实机才现形）。
 *
 * 产物在 `build/usage-*-probe*.png`。改了版式就跑一下看一眼。
 */
class UsageRenderProbe {

    private val today = LocalDate.of(2026, 10, 10)

    @Test
    fun `统计框（仪表盘）画成图片`() = renderDialog("build/usage-dialog-probe.png", fixture())

    /** 深色版：用户的工作主题。页底用选型台的深色页（#161719），看"近黑画布上的灰卡"。 */
    @Test
    fun `统计框（深色）画成图片`() = renderDialog("build/usage-dialog-probe-dark.png", fixture(), dark = true)

    /** 一条数据都没有那一版：只有一句实话，不画全零的图。 */
    @Test
    fun `统计框的空态画成图片`() = renderDialog("build/usage-dialog-probe-empty.png", emptyList())

    @Test
    fun `常驻条画成图片`() {
        SwingUtilities.invokeAndWait {
            val strip = UsageStrip(onOpen = {})
            strip.submit(UsageStripData(12_400_000, 14.9, spark()))
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(6, 8)
                add(strip, BorderLayout.NORTH)
            }
            val w = JBUI.scale(420)
            val h = strip.preferredSize.height + JBUI.scale(12)
            outer.setSize(w, h)
            layoutAll(outer)
            paint(outer, w, h, "build/usage-strip-probe.png")
        }
    }

    // ------------------------------------------------------------------

    private fun renderDialog(path: String, rows: List<UsageDay>, dark: Boolean = false) {
        SwingUtilities.invokeAndWait {
            val restore = pushDarkTheme(dark)
            try {
                val panel = buildUsagePanel(
                    rows = rows,
                    scope = "project",
                    gran = UsageGran.Day,
                    today = today,
                    onPickGran = {},
                    onPickScope = {},
                )
                val outer = JPanel(BorderLayout()).apply {
                    isOpaque = true
                    // 页底 = 选型台那两行 pageBg 常量（深色 #161719 与用户 IDE 的画布
                    // 实测同一个量级）—— 探针要复现的是"框摆在画布上"的关系
                    background = if (dark) Color(0x16, 0x17, 0x19) else Color(0xf4, 0xf5, 0xf7)
                    add(panel, BorderLayout.CENTER)
                }
                val w = panel.preferredSize.width
                val h = panel.preferredSize.height
                outer.setSize(w, h)
                layoutAll(outer)
                paint(outer, w, h, path)
            } finally {
                restore()
            }
        }
    }

    /**
     * 把平台色换成深色版（只按统计框真正读的那几个键）。
     *
     * 为什么不是 `JBColor.setDark`：单测环境的 UIManager 里躺的是**已解析的**颜色，
     * 翻 JBColor 的旗子对 `UIUtil.getPanelBackground()` 这类取值没有影响（试过，
     * 深色探针画出来和浅色一模一样）。所以直接按住这几个键，画完还原。
     * 面板色用 #191A1C —— 用户实机那块（Islands 系主题实测值）。
     */
    private fun pushDarkTheme(dark: Boolean): () -> Unit {
        if (!dark) return {}
        val keys = linkedMapOf(
            "Panel.background" to Color(0x19, 0x1a, 0x1c),
            "Label.foreground" to Color(0xdf, 0xe1, 0xe5),
            "Component.infoForeground" to Color(0x9d, 0xa0, 0xa8),
            "Label.disabledForeground" to Color(0x9d, 0xa0, 0xa8),
            "Tree.selectionBackground" to Color(0x2f, 0x65, 0xca),
            "TextField.background" to Color(0x1e, 0x1f, 0x22),
            "List.selectionBackground" to Color(0x2e, 0x43, 0x6e),
        )
        val prev = keys.keys.associateWith { UIManager.getColor(it) }
        keys.forEach { (k, v) -> UIManager.put(k, v) }
        // 顺手把那对老 JBColor 常量也翻过去（图表基线画的是 JBColor.border()）
        JBColor.setDark(true)
        return {
            prev.forEach { (k, v) -> UIManager.put(k, v) }
            // 还原成浅色：这个套件的基准就是浅色（其余探针都按浅色画）
            JBColor.setDark(false)
        }
    }

    private fun paint(c: Container, w: Int, h: Int, path: String) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        c.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(path))
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        c.components.forEach { if (it is Container) layoutAll(it) }
    }

    // ---- 示意数据（30 天、三个模型；形状与真实一致：缓存读占大头）----

    private fun modelShare(tokens: Long) = UsageModelTokens(
        inp = (tokens * 0.15).toLong(),
        out = (tokens * 0.02).toLong(),
        cacheRead = (tokens * 0.78).toLong(),
        cacheWrite = (tokens * 0.05).toLong(),
        costUsd = tokens / 1e6 * 1.9,
    )

    private fun fixture(): List<UsageDay> = (29 downTo 0).map { back ->
        val d = today.minusDays(back.toLong())
        val weekend = d.dayOfWeek.value >= 6
        val grown = 6_000_000L + ((29 - back) * 297_000L) % 5_000_000L
        val base = if (weekend) grown * 35 / 100 else grown
        UsageDay(
            d,
            mapOf(
                "deepseek-v4-flash" to modelShare(base * 60 / 100),
                "mimo-v2.6-pro" to modelShare(base * 25 / 100),
                "claude-sonnet-5-5" to modelShare(base * 15 / 100),
            ),
        )
    }

    private fun spark(): List<Long> = (13 downTo 0).map { 2_000_000L + (it * 1_100_000L) % 9_000_000L }
}
