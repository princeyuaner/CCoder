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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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

    /** 带账单那版（方案 A 的对账条）：深浅各一张 —— 虚线框用的是强调色，两套纸下都要看一眼。 */
    @Test
    fun `统计框（带账单）画成图片`() =
        renderDialog("build/usage-dialog-probe-bill.png", fixture(), bill = billFixture())

    @Test
    fun `统计框（带账单·深色）画成图片`() =
        renderDialog("build/usage-dialog-probe-bill-dark.png", fixture(), dark = true, bill = billFixture())

    /** 带额度那版（opencode 三档进度条）：深浅各一张 —— 三支颜色压在两张纸上都要看一眼。 */
    @Test
    fun `统计框（带额度）画成图片`() =
        renderDialog("build/usage-dialog-probe-quota.png", fixture(), quota = quotaFixture())

    @Test
    fun `统计框（带额度·深色）画成图片`() =
        renderDialog("build/usage-dialog-probe-quota-dark.png", fixture(), dark = true, quota = quotaFixture())

    /** 13 个模型那版（2026-10-13 用户实况）：图例折行后，框宽回到设计值、条目都能读。 */
    @Test
    fun `统计框（13 个模型）画成图片`() = renderDialog("build/usage-dialog-probe-many.png", manyModelFixture())

    /** 带余额那版（DeepSeek）：深浅各一张 —— 数字下划线与琥珀「已不可用」都要看一眼。 */
    @Test
    fun `统计框（带余额）画成图片`() =
        renderDialog("build/usage-dialog-probe-balance.png", fixture(), balance = balanceFixture())

    @Test
    fun `统计框（带余额·深色）画成图片`() =
        renderDialog("build/usage-dialog-probe-balance-dark.png", fixture(), dark = true, balance = balanceFixture())

    /** 常驻条（带余额）：尾巴一段「余额 ¥110.00」。 */
    @Test
    fun `常驻条（带余额）画成图片`() = renderStrip(
        "build/usage-strip-probe-balance.png",
        UsageStripData(12_400_000, 14.9, spark(), deepseekBalance = balanceFixture()),
    )

    @Test
    fun `常驻条画成图片`() = renderStrip("build/usage-strip-probe.png", UsageStripData(12_400_000, 14.9, spark()))

    /** 带实扣那版（方案 A）：条尾多一段「实扣¥8.30」。 */
    @Test
    fun `常驻条（带实扣）画成图片`() = renderStrip(
        "build/usage-strip-probe-bill.png",
        UsageStripData(12_400_000, 14.9, spark(), todayBillYuan = 8.3),
    )

    /**
     * 带额度 · **窄窗**（420）：三根轨道放不下，退成「5h 12% · 周 82% · 月 100%」文字
     * —— 降级阶梯的实景（2026-10-14 丙 版）。宽窗那版见下面那一对。
     */
    @Test
    fun `常驻条（带额度）画成图片`() = renderStrip(
        "build/usage-strip-probe-quota.png",
        UsageStripData(12_400_000, 14.9, spark(), opencodeQuota = quotaFixture()),
    )

    /**
     * 带额度 · **宽窗**（窗 756 → 条 740，与用户实机量到的那条 739 同宽）：
     * 丙 版的主场景 —— 三根可伸的轨道。周 82% 琥珀、月 100% 红，颜色门槛一并看。
     */
    @Test
    fun `常驻条（带额度·宽窗）画成图片`() = renderStrip(
        "build/usage-strip-probe-quota-wide.png",
        UsageStripData(12_400_000, 14.9, spark(), opencodeQuota = quotaFixture()),
        width = 756,
    )

    @Test
    fun `常驻条（带额度·宽窗·深色）画成图片`() = renderStrip(
        "build/usage-strip-probe-quota-wide-dark.png",
        UsageStripData(12_400_000, 14.9, spark(), opencodeQuota = quotaFixture()),
        width = 756,
        dark = true,
    )

    /** 深色下那根基线（线色 65% 透明）在近黑底上还在不在 —— 浅色看得见不代表深色看得见。 */
    @Test
    fun `常驻条（带实扣·深色）画成图片`() = renderStrip(
        "build/usage-strip-probe-bill-dark.png",
        UsageStripData(12_400_000, 14.9, spark(), todayBillYuan = 8.3),
        width = 756,
        dark = true,
    )

    private fun renderStrip(path: String, data: UsageStripData, width: Int = 420, dark: Boolean = false) {
        SwingUtilities.invokeAndWait {
            val restore = pushDarkTheme(dark)
            try {
                val strip = UsageStrip(onOpen = {})
                strip.submit(data)
                val outer = JPanel(BorderLayout()).apply {
                    isOpaque = true
                    background = UIUtil.getPanelBackground()
                    border = JBUI.Borders.empty(6, 8)
                    add(strip, BorderLayout.NORTH)
                }
                val w = JBUI.scale(width)
                val h = strip.preferredSize.height + JBUI.scale(12)
                outer.setSize(w, h)
                layoutAll(outer)
                paint(outer, w, h, path)
            } finally {
                restore()
            }
        }
    }

    // ------------------------------------------------------------------

    private fun renderDialog(
        path: String,
        rows: List<UsageDay>,
        dark: Boolean = false,
        bill: UsageBill? = null,
        quota: OpencodeQuota? = null,
        balance: DeepseekBalance? = null,
    ) {
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
                    bill = bill,
                    quota = quota,
                    balance = balance,
                    onRefreshBill = {},
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

    /** 账单示意（方案 A 的对账条）：数字形状照站点 —— 实扣与余额都是「两位小数」的元。 */
    private fun billFixture(): UsageBill = UsageBill(
        provider = DMX_PROVIDER_NAME,
        yuanToday = 8.5,
        promptTokens = 12_340_000,
        completionTokens = 380_000,
        balanceYuan = 123.4,
        fetchedAt = LocalTime.of(14, 32),
    )

    /**
     * 额度示意（opencode 三档进度条）：**三支颜色都要看得见** —— 12%（强调）、
     * 82%（琥珀）、100%（红 + 限流）。重置时间是固定 GMT 点，画出来的钟点跟着本机时区走。
     */
    private fun quotaFixture(): OpencodeQuota = OpencodeQuota(
        windows = listOf(
            QuotaWindow(QuotaWindowKind.Rolling, 12.0, false, Instant.parse("2026-10-13T12:46:37Z")),
            QuotaWindow(QuotaWindowKind.Weekly, 82.0, false, Instant.parse("2026-10-19T00:00:00Z")),
            QuotaWindow(QuotaWindowKind.Monthly, 100.0, true, Instant.parse("2026-11-13T06:58:17Z")),
        ),
        fetchedAt = LocalTime.of(20, 3),
    )

    /** 余额示意（DeepSeek）：金额形状照站点 —— 字符串两位小数、CNY。 */
    private fun balanceFixture(): DeepseekBalance = DeepseekBalance(
        currency = "CNY",
        total = 110.0,
        granted = 10.0,
        toppedUp = 100.0,
        available = true,
        fetchedAt = LocalTime.of(20, 52),
    )

    /** 13 个模型（形状照用户实况：名字长、含 [1m] 与斜杠）—— 图例/表格宽度的压力测试。 */
    private fun manyModelFixture(): List<UsageDay> {
        val names = listOf(
            "deepseek-v4-flash", "deepseek-flash", "deepseek-v4.1-flash[1m]", "deepseek-v4.1-flash",
            "deepseek-flash-guan[1m]", "deepseek-flash[1m]", "deepseek-v4-flash[1m]",
            "deepseek/deepseek-flash", "deepseek-v4-flash-vision-exp",
            "mimo-v2.6-pro", "mimo-v2.6-flash", "claude-sonnet-5-5-cc[1m]", "claude-opus-5-5-cc[1m]",
        )
        return (29 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val grown = 4_000_000L + ((29 - back) * 297_000L) % 6_000_000L
            UsageDay(d, names.mapIndexed { i, n -> n to modelShare(grown / (i + 2)) }.toMap())
        }
    }
}
