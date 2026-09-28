package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "该不该把同步跑起来"与"改了配置要不要重建"（2026-09-24）。
 *
 * 这两条判定是服务层唯一被测的逻辑，而它们要回答的都是**必须讲清理由**的问题：
 *
 * - 不启动时，要说得出是**哪一项没填**、还是**被哪个窗口占着**（用户看到的只有状态卡上的
 *   一句话，说不出理由就等于没工作）
 * - 改配置时，要知道**哪些改动可以当场生效**：设置页是改动即写的，重启一次看盘在映射网络盘上
 *   是 3.4 秒（探针实测），不能改什么都重建
 */
class SyncStartTest {

    private val configured = SyncConfig(enabled = true, src = "C:\\src", dst = "Z:\\dst")

    private fun occupiedBy(project: String) =
        SyncClaim.Result.Occupied(SyncClaim.Owner(pid = 4242, project = project, sinceMs = 1L))

    // ---------------------------------------------------------------- 该不该启动

    @Test
    fun `没配好不启动 —— 缺哪一项要说得出来`() {
        assertEquals(
            SyncStart.Cannot(ConfigProblem.DISABLED),
            syncStartDecision(SyncConfig(), SyncClaim.Result.Acquired),
        )
        assertEquals(
            SyncStart.Cannot(ConfigProblem.NO_SRC),
            syncStartDecision(SyncConfig(enabled = true), SyncClaim.Result.Acquired),
        )
        assertEquals(
            SyncStart.Cannot(ConfigProblem.NO_DST),
            syncStartDecision(SyncConfig(enabled = true, src = "C:\\src"), SyncClaim.Result.Acquired),
        )
    }

    @Test
    fun `被别的活窗口占着就不启动 —— 并说得出是哪个项目`() {
        val d = syncStartDecision(configured, occupiedBy("Z:/m71/server"))

        assertEquals(SyncStart.Occupied("Z:/m71/server"), d, "状态卡要拿这个讲「被谁占着」")
    }

    @Test
    fun `配好了、也没人占着就启动`() {
        assertEquals(SyncStart.Start, syncStartDecision(configured, SyncClaim.Result.Acquired))
    }

    @Test
    fun `配置不齐时不谈占用 —— 先把缺的填上才有意义`() {
        // 两个理由同时成立时报哪个：报"没配好"更贴近用户当下要做的事
        val d = syncStartDecision(SyncConfig(), occupiedBy("其它窗口"))

        assertEquals(SyncStart.Cannot(ConfigProblem.DISABLED), d)
    }

    // ---------------------------------------------------------------- 要不要重建

    @Test
    fun `时长改动不重建 —— 改一个数字就是一次通知`() {
        assertFalse(needsRestart(configured, configured.copy(settleMs = 2_500)), "静默期当场生效")
        assertFalse(needsRestart(configured, configured.copy(sweepMs = 90_000)), "巡检周期当场生效")
    }

    @Test
    fun `镜像删除也不重建 —— 引擎每轮现读`() {
        assertFalse(needsRestart(configured, configured.copy(deleteMissing = false)))
    }

    @Test
    fun `换了目录必须重建 —— 连基线与认领都换了另一份`() {
        assertTrue(needsRestart(configured, configured.copy(src = "D:\\其它")))
        assertTrue(needsRestart(configured, configured.copy(dst = "Y:\\其它")))
    }

    @Test
    fun `范围与排除项必须重建 —— 看盘的注册只在启动时做一次`() {
        assertTrue(needsRestart(configured, configured.copy(exclude = listOf("temp"))))
        assertTrue(needsRestart(configured, configured.copy(syncRoots = listOf("trunk"))))
        assertTrue(
            needsRestart(configured, configured.copy(junk = JunkRules(dirNames = listOf("build")))),
            "垃圾规则决定哪些目录**不下探**，注册时就用上了",
        )
    }

    @Test
    fun `开关本身算改动`() {
        assertTrue(needsRestart(configured, configured.copy(enabled = false)))
    }

    @Test
    fun `没填的项不影响重建判定 —— 归一之后比`() {
        val a = normalizeConfig(SyncConfig(enabled = true, src = " C:\\src ", dst = "Z:\\dst"))
        val b = normalizeConfig(SyncConfig(enabled = true, src = "C:\\src", dst = "Z:\\dst"))

        assertFalse(needsRestart(a, b), "只多打了个空格不该重建")
    }

    @Test
    fun `第一次（没有旧配置）要重建`() {
        assertTrue(needsRestart(null, configured))
    }
}
