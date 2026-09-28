package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 配置的规整与"配好了没有"（2026-09-24）。
 *
 * 规矩与参考实现的 `apply_config()` 一致：**缺项 / 类型不对一律回落默认值**，
 * 于是"没有配置"和"配置只写了一半"的行为都是确定的。多做的两件事（模式归一、
 * 时长夹区间）在这里各有一条用例。
 */
class SyncConfigTest {

    @Test
    fun `路径只去空白，不动盘符与分隔符`() {
        val cfg = normalizeConfig(SyncConfig(src = "  C:\\M71\\server  ", dst = " Z:\\m71\\server "))

        assertEquals("C:\\M71\\server", cfg.src, "src/dst 是要拿去做 IO 的真路径，不能顺手改成正斜杠")
        assertEquals("Z:\\m71\\server", cfg.dst)
    }

    @Test
    fun `模式一律归一 —— 配置里写大写或反斜杠也匹配得上`() {
        val cfg = normalizeConfig(
            SyncConfig(syncRoots = listOf("\\Trunk\\"), exclude = listOf(" Temp ", "", "BUILD")),
        )

        assertEquals(listOf("trunk"), cfg.syncRoots)
        assertEquals(listOf("temp", "build"), cfg.exclude, "归一之外还去空白项、按出现顺序去重")
    }

    @Test
    fun `静默期夹在合理区间 —— 填 0 会把 CPU 烧起来`() {
        assertEquals(SyncConfig.MAX_SETTLE_MS, normalizeConfig(SyncConfig(settleMs = 999_999)).settleMs)
        assertEquals(SyncConfig.MIN_SETTLE_MS, normalizeConfig(SyncConfig(settleMs = -5)).settleMs)
        assertEquals(SyncConfig.DEFAULT_SETTLE_MS, normalizeConfig(SyncConfig()).settleMs)
    }

    @Test
    fun `巡检周期的 0 是有效取值 —— 那是「关闭兜底巡检」`() {
        assertEquals(0L, normalizeConfig(SyncConfig(sweepMs = 0)).sweepMs, "夹下限会把它变成「每 100 毫秒巡检一次」")
        assertEquals(
            SyncConfig.MIN_SETTLE_MS,
            normalizeConfig(SyncConfig(sweepMs = 1)).sweepMs,
            "非 0 的小值才夹下限",
        )
    }

    @Test
    fun `junk 四组一律去空白、转小写、去重`() {
        val cfg = normalizeConfig(
            SyncConfig(
                junk = JunkRules(
                    dirNames = listOf(" BUILD ", "build", ""),
                    fileNames = listOf("Thumbs.DB"),
                    suffixes = listOf(".LOG"),
                    prefixes = listOf("  GsLog"),
                ),
            ),
        )

        assertEquals(listOf("build"), cfg.junk.dirNames)
        assertEquals(listOf("thumbs.db"), cfg.junk.fileNames)
        assertEquals(listOf(".log"), cfg.junk.suffixes)
        assertEquals(listOf("gslog"), cfg.junk.prefixes)
    }

    @Test
    fun `isConfigured 三缺一就不算配好`() {
        assertFalse(SyncConfig().isConfigured, "默认是关的")
        assertFalse(SyncConfig(enabled = true).isConfigured, "开了但没填路径")
        assertFalse(SyncConfig(enabled = true, src = "C:/a").isConfigured)
        assertTrue(SyncConfig(enabled = true, src = "C:/a", dst = "Z:/b").isConfigured)
    }

    @Test
    fun `缺哪一项要说得出是哪一项 —— 状态卡靠它讲人话`() {
        assertEquals(ConfigProblem.DISABLED, configProblem(SyncConfig()))
        assertEquals(ConfigProblem.NO_SRC, configProblem(SyncConfig(enabled = true)))
        assertEquals(ConfigProblem.NO_DST, configProblem(SyncConfig(enabled = true, src = "C:/a")))
        assertNull(configProblem(SyncConfig(enabled = true, src = "C:/a", dst = "Z:/b")))
    }

    @Test
    fun `只填了空白也不算填了`() {
        assertEquals(ConfigProblem.NO_SRC, configProblem(SyncConfig(enabled = true, src = "   ")))
    }
}
