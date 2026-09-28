package com.ccoder.ui

import com.ccoder.sync.ConfigProblem
import com.ccoder.sync.Failed
import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 状态栏那两句话（2026-09-24）。
 *
 * 钉的是**显示口径**，不是措辞 —— 具体每个字由词表决定（中英各一份），这里只管：
 *
 * 1. **没配置就不占地方**：一个永远写着"同步：未配置"的组件只是噪音
 * 2. **几个状态不能混成同一句**：状态栏就那几个字，混了等于没显示
 * 3. **该带的数据要带上**：被占用时那**是哪个项目**、跑完之后**几个数** ——
 *    不写出来用户只会看到"不工作"
 */
class SyncStatusBarTest {

    @Test
    fun `没配置或已停时不占状态栏`() {
        assertEquals("", syncStatusBarText(SyncSnapshot()), "新建的项目不该在状态栏上多出一段")
        assertEquals("", syncStatusBarText(SyncSnapshot(run = SyncRun.STOPPED)))
    }

    @Test
    fun `三种在跑的状态各有各的说法`() {
        val texts = listOf(SyncRun.OCCUPIED, SyncRun.RUNNING, SyncRun.FAILED)
            .map { syncStatusBarText(SyncSnapshot(run = it)) }

        assertTrue(texts.all { it.isNotEmpty() }, "在跑的状态不能是空白")
        assertEquals(texts.size, texts.toSet().size, "状态栏就那么几个字，混成同一句就等于没显示")
    }

    @Test
    fun `tooltip：占用时带上是哪个项目`() {
        val tip = syncStatusBarTooltip(SyncSnapshot(run = SyncRun.OCCUPIED, occupiedBy = "Z:/m71/server"))

        assertTrue(tip.contains("Z:/m71/server"), "不写出来用户只会看到「不工作」，实际：$tip")
    }

    @Test
    fun `tooltip：缺本地与缺目标端是两句话`() {
        val noSrc = syncStatusBarTooltip(SyncSnapshot(run = SyncRun.STOPPED, problem = ConfigProblem.NO_SRC))
        val noDst = syncStatusBarTooltip(SyncSnapshot(run = SyncRun.STOPPED, problem = ConfigProblem.NO_DST))

        assertTrue(noSrc.isNotEmpty())
        assertNotEquals(noSrc, noDst, "两种缺法是用户要做的两件不同的事")
    }

    @Test
    fun `tooltip：还没跑完一轮时说明情况`() {
        val tip = syncStatusBarTooltip(SyncSnapshot(run = SyncRun.RUNNING, lastRoundAtMs = null))

        assertTrue(tip.isNotEmpty(), "刚打开项目时最长见的就是这一屏，不能是空的")
    }

    @Test
    fun `tooltip：跑过之后带上三个计数`() {
        val tip = syncStatusBarTooltip(
            SyncSnapshot(run = SyncRun.RUNNING, lastRoundAtMs = 1_700_000_000_000L, copied = 7, deleted = 3),
        )

        assertTrue(tip.contains("7"), "复制数要在，实际：$tip")
        assertTrue(tip.contains("3"), "删除数要在，实际：$tip")
    }

    @Test
    fun `tooltip：有失败项时多一句「去看清单」`() {
        val clean = syncStatusBarTooltip(
            SyncSnapshot(run = SyncRun.RUNNING, lastRoundAtMs = 1L, copied = 1),
        )
        val failed = syncStatusBarTooltip(
            SyncSnapshot(
                run = SyncRun.FAILED,
                lastRoundAtMs = 1L,
                failed = listOf(Failed("trunk/a.kt", "被占用")),
            ),
        )

        assertNotEquals(clean, failed, "「同步出错」与「同步中」不该长得一样")
    }
}
