package com.ccoder.ui

import com.ccoder.sidecar.UsageDay
import com.ccoder.sidecar.UsageModelTokens
import com.ccoder.text.CcoderText
import com.ccoder.text.TextCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.time.LocalDate
import java.util.Locale

/**
 * 使用量统计的显示层模型（UsagePeriods）—— 纯函数，直接打。
 *
 * 「今日」钉成 2026-10-10（周六）。周口径按**周一起**（与分桶、三块砖同一处，
 * 两处差一天的话砖与柱会对不上）。
 */
class UsagePeriodsTest {

    private val today = LocalDate.of(2026, 10, 10)

    private fun t(i: Long, o: Long = 0, r: Long = 0, c: Long = 0, cost: Double = 0.0) =
        UsageModelTokens(i, o, r, c, cost)

    private fun day(y: Int, m: Int, d: Int, vararg models: Pair<String, UsageModelTokens>) =
        UsageDay(LocalDate.of(y, m, d), models.toMap())

    // ------------------------------------------------------------------ 分桶

    @Test
    fun `按日：近 30 天、空洞补零、最右是今天`() {
        val rows = listOf(
            day(2026, 10, 10, "m1" to t(10)),
            day(2026, 10, 8, "m1" to t(5)), // 10/9 缺 —— 中间那格必须是 0，不是少一根
        )
        val buckets = usageBuckets(rows, UsageGran.Day, today)
        assertEquals(30, buckets.size)
        assertEquals(LocalDate.of(2026, 9, 11), buckets.first().start)
        assertEquals(today, buckets.last().start)
        assertEquals(10L, buckets.last().tokens)
        assertEquals(0L, buckets[28].tokens, "10/9 没有账 —— 那一格是 0")
        assertEquals(5L, buckets[27].tokens)
    }

    @Test
    fun `按周：近 12 周、周一起、跨周合并`() {
        val rows = listOf(
            day(2026, 10, 5, "m1" to t(1)), // 本周一
            day(2026, 10, 10, "m2" to t(2)), // 本周六（今天）
            day(2026, 10, 4, "m1" to t(4)), // 上周日
        )
        val buckets = usageBuckets(rows, UsageGran.Week, today)
        assertEquals(12, buckets.size)
        assertEquals(LocalDate.of(2026, 10, 5), buckets.last().start, "最后一格从本周一开始")
        assertEquals(3L, buckets.last().tokens, "周一与周六合成一格")
        assertEquals(4L, buckets[10].tokens)
        assertEquals(LocalDate.of(2026, 7, 20), buckets.first().start)
    }

    @Test
    fun `按月：近 12 个月、自然月`() {
        val rows = listOf(
            day(2026, 10, 1, "m1" to t(1)),
            day(2026, 10, 10, "m1" to t(2)),
            day(2025, 11, 20, "m1" to t(9)),
        )
        val buckets = usageBuckets(rows, UsageGran.Month, today)
        assertEquals(12, buckets.size)
        assertEquals(LocalDate.of(2025, 11, 1), buckets.first().start)
        assertEquals(LocalDate.of(2026, 10, 1), buckets.last().start)
        assertEquals(3L, buckets.last().tokens)
        assertEquals(9L, buckets.first().tokens)
    }

    @Test
    fun `桶内模型按总量降序 —— 顺序即颜色顺序`() {
        val rows = listOf(day(2026, 10, 10, "small" to t(1), "big" to t(100), "mid" to t(10)))
        val bucket = usageBuckets(rows, UsageGran.Day, today).last()
        assertEquals(listOf("big", "mid", "small"), bucket.models.map { it.first })
    }

    // ------------------------------------------------------------------ 三块砖

    @Test
    fun `三块砖：今日两天前那笔不算；本周从周一起；变化率对齐上周`() {
        val rows = listOf(
            day(2026, 10, 3, "m1" to t(50)), // 上周六 —— 注意它同时也在**本月**里
            day(2026, 10, 5, "m1" to t(10)), // 本周一
            day(2026, 10, 10, "m1" to t(5)), // 今天
            day(2026, 9, 25, "m1" to t(7)), // 上个月（且在更早的那一周）
        )
        val s = usageSummary(rows, today)
        assertEquals(5L, s.today.tokens)
        assertEquals(15L, s.week.tokens)
        assertEquals(65L, s.month.tokens, "本月 = 10/3 + 10/5 + 10/10（10/3 既是上周六、也是本月的）")
        // 上周 = 9/28-10/4，只有 10/3 那笔 50
        assertEquals(-0.7, s.weekDelta!!, 0.0001, "(15-50)/50")
    }

    @Test
    fun `上周没有数据时不造百分比`() {
        val s = usageSummary(listOf(day(2026, 10, 10, "m1" to t(5))), today)
        assertNull(s.weekDelta)
    }

    // ------------------------------------------------------------------ 常驻条 / 顺序 / 合计

    @Test
    fun `常驻条数据：今天的量加成本、走势 14 格`() {
        val rows = listOf(
            day(2026, 9, 27, "m1" to t(7)), // 14 格窗口（9/27-10/10）的最左一格
            day(2026, 10, 10, "m1" to t(3, cost = 0.5), "m2" to t(4, cost = 0.25)),
        )
        val d = stripDataOf(rows, today)
        assertEquals(7L, d.todayTokens)
        assertEquals(0.75, d.todayCost!!, 0.0001)
        assertEquals(14, d.spark.size)
        assertEquals(7L, d.spark.first())
        assertEquals(7L, d.spark.last())
        assertEquals(0L, d.spark[5])
    }

    @Test
    fun `没有任何数据时今天给 0、成本给 null（界面省掉那半截）`() {
        val d = stripDataOf(emptyList(), today)
        assertEquals(0L, d.todayTokens)
        assertNull(d.todayCost)
    }

    @Test
    fun `全局模型顺序按总量降序（跨天合并）`() {
        val rows = listOf(
            day(2026, 10, 9, "a" to t(1), "b" to t(10)),
            day(2026, 10, 10, "a" to t(30), "b" to t(1)),
        )
        assertEquals(listOf("a", "b"), usageModelsOrdered(rows))
    }

    @Test
    fun `合计按模型合并`() {
        val buckets = listOf(
            usageBucketOf(today, "", listOf(day(2026, 10, 10, "a" to t(1, 2, 3, 4, 0.1)))),
            usageBucketOf(today, "", listOf(day(2026, 10, 9, "a" to t(10, 0, 0, 0, 0.2)))),
        )
        val totals = usageTotalsByModel(buckets)
        assertEquals(t(11, 2, 3, 4, 0.1 + 0.2), totals["a"])
    }

    // ------------------------------------------------------------------ 文案件

    @Test
    fun `变化率文案：正负号与取整`() {
        assertNull(usageDeltaText(null))
        assertEquals("+21%", usageDeltaText(0.214))
        assertEquals("-6%", usageDeltaText(-0.055))
    }

    @Test
    fun `成本格式：千以内一位小数、再大取整（ROOT locale，不跟着系统走）`() {
        assertEquals("12.4", formatUsageCost(12.44))
        assertEquals("0.5", formatUsageCost(0.54))
        assertEquals("1235", formatUsageCost(1234.6))
    }

    @Test
    fun `常驻条那串字：有成本带成本、没成本只给今日`() {
        val prev = CcoderText.locale()
        CcoderText.setOverride(Locale.SIMPLIFIED_CHINESE)
        try {
            assertEquals(
                "今日 12.4M · ≈$1.2",
                usageStripText(UsageStripData(12_400_000, 1.23, emptyList())),
            )
            assertEquals(
                "今日 0",
                usageStripText(UsageStripData(0, null, emptyList())),
            )
        } finally {
            CcoderText.setOverride(prev)
        }
    }

    @Test
    fun `CSV：表头走词表、日期升序、模型降序、逗号会被包起来`() {
        val prev = CcoderText.locale()
        CcoderText.setOverride(Locale.ENGLISH)
        try {
            val rows = listOf(
                day(2026, 10, 10, "a,b" to t(1, 2, 3, 4, 0.5), "z" to t(9)),
                day(2026, 10, 9, "z" to t(7)),
            )
            val lines = usageCsvOf(rows).trim().split("\n")
            assertEquals("Date,Model,Input,Output,Cache read,Cache write,≈Cost", lines[0])
            assertEquals("2026-10-09,z,7,0,0,0,0.0000", lines[1])
            assertEquals("2026-10-10,\"a,b\",1,2,3,4,0.5000", lines[2])
            assertEquals("2026-10-10,z,9,0,0,0,0.0000", lines[3])
        } finally {
            CcoderText.setOverride(prev)
        }
    }

    // ------------------------------------------------------------------ 词表对账

    @Test
    fun `间接引用的键（表里、when 里那些）两份词表都得有`() {
        // TextKeysTest 的"代码→词表"方向只认字面调用点；这几个键从表里引用，
        // 没有这一条，打错了会静默显示成键名（同 CONTEXT_ROW_KEYS 那条）
        val en = TextCatalog.keysOf(Locale.ENGLISH)
        val zh = TextCatalog.keysOf(Locale.SIMPLIFIED_CHINESE)
        for (key in USAGE_INDIRECT_KEYS) {
            assertTrue(key in en, "英文词表缺 $key")
            assertTrue(key in zh, "中文词表缺 $key")
        }
    }

    @Test
    fun `模型 token 的加法与合计`() {
        assertEquals(10L, t(1, 2, 3, 4).total())
        assertEquals(t(1, 2, 3, 4, 0.5), t(1, 0, 3, 0, 0.2) + t(0, 2, 0, 4, 0.3))
    }

    // ------------------------------------------------------------------ 纸

    @Test
    fun `统计框的纸是选型台常量 —— 不跟宿主面板色走`() {
        // 2026-10-10 用户实测：Islands 系主题的面板是 #191A1C，照主题上色整个框会黑掉，
        // 与选型台（近黑画布上的灰卡）不是一个东西。修复 = 对话框自带这两套纸。
        val dark = usagePaletteFor(Color(0x19, 0x1a, 0x1c))
        assertTrue(dark.dark)
        assertEquals(Color(0x2b, 0x2d, 0x30), dark.bg)
        assertEquals(Color(0x1e, 0x1f, 0x22), dark.surface)
        assertEquals(Color(0x39, 0x3b, 0x40), dark.border)

        val light = usagePaletteFor(Color(0xf2, 0xf2, 0xf2))
        assertTrue(!light.dark)
        assertEquals(Color(0xf7, 0xf8, 0xfa), light.bg)
        assertEquals(Color(0xff, 0xff, 0xff), light.surface)
        assertEquals(Color(0xd1, 0xd1, 0xd1), light.border)
    }

    @Test
    fun `项目键比对：反斜杠与正斜杠是同一个项目 —— tick 不能被丢`() {
        // 2026-10-10 实机：会话启动参数里的 cwd 是 Windows 反斜杠形
        // （absolutePathString），面板手里的 basePath 是正斜杠形 —— 直接 ==
        // 会把每一条 usageTick 都丢掉（账在记、条不动，重启 IDE 才更新）。
        assertTrue(usageSameProject("C:\\Users\\CY\\Desktop\\zhixin", "C:/Users/CY/Desktop/zhixin"))
        assertTrue(usageSameProject("C:/proj/", "C:/proj"))
        assertTrue(!usageSameProject("C:/proj", "C:/other"))
        assertTrue(!usageSameProject("", "C:/proj"))
        assertTrue(!usageSameProject(null, "C:/proj"))
    }
}
