package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 跨窗口认领（2026-09-24）。
 *
 * 这一层的存在理由是**两个窗口同步同一对目录会互相删对方刚写的文件**，而且不报错 ——
 * 只是文件时有时无。所以它的错法比它的功能更需要被钉住：
 *
 * 1. **陈旧认领必须能接管**：上一轮 IDE 崩掉留下的认领不该把用户永久挡在外面
 * 2. **释放只删自己那一份**：否则被挡下的那个窗口一退出，占用者的认领就没了，
 *    第三个窗口立刻能挤进来 —— "只许一个"这条规矩自己就破了
 *
 * 判活是注入的："造一个死进程"既慢又难写对，而这条逻辑恰恰是必须确定性验证的。
 * 真实的 `ProcessHandle` 判活单独有一条用例。
 */
class SyncClaimTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var claim: Path

    @BeforeEach
    fun setUp() {
        claim = tmp.resolve("ccoder/sync/abc123.claim")
    }

    @Test
    fun `第一次认领成功，并写下自己的 pid 与项目`() {
        val r = SyncClaim.tryAcquire(claim, "C:/proj/a", myPid = 100, isAlive = { false })

        assertEquals(SyncClaim.Result.Acquired, r)
        val owner = SyncClaim.readOwner(claim)!!
        assertEquals(100L, owner.pid)
        assertEquals("C:/proj/a", owner.project)
    }

    @Test
    fun `活着的人占着就不让认领 —— 而且说得出是谁`() {
        SyncClaim.tryAcquire(claim, "C:/proj/other", myPid = 999, isAlive = { false })

        val r = SyncClaim.tryAcquire(claim, "C:/proj/mine", myPid = 100, isAlive = { it == 999L })

        assertTrue(r is SyncClaim.Result.Occupied, "另一个窗口还活着，不该放行")
        val owner = (r as SyncClaim.Result.Occupied).owner
        assertEquals(999L, owner.pid)
        assertEquals("C:/proj/other", owner.project, "状态卡要拿这个讲「被谁占着」")
    }

    @Test
    fun `进程死了的陈旧认领可以接管 —— 上一轮崩掉不该把我永久挡在外面`() {
        SyncClaim.tryAcquire(claim, "C:/proj/dead", myPid = 999, isAlive = { false })

        val r = SyncClaim.tryAcquire(claim, "C:/proj/mine", myPid = 100, isAlive = { false })

        assertEquals(SyncClaim.Result.Acquired, r)
        assertEquals(100L, SyncClaim.readOwner(claim)!!.pid, "认领该换成我们的")
    }

    @Test
    fun `自己的认领可以重复拿 —— 服务重启过还是我`() {
        SyncClaim.tryAcquire(claim, "C:/proj/mine", myPid = 100, isAlive = { true })

        val r = SyncClaim.tryAcquire(claim, "C:/proj/mine", myPid = 100, isAlive = { true })

        assertEquals(SyncClaim.Result.Acquired, r, "同一个进程重新进来不该把自己挡住")
    }

    @Test
    fun `release 只删自己的那一份 —— 不会把占用者的认领抹掉`() {
        SyncClaim.tryAcquire(claim, "C:/proj/other", myPid = 999, isAlive = { false })

        SyncClaim.release(claim, myPid = 100)      // 被挡下的那个窗口退出时会这么调

        assertTrue(Files.exists(claim), "占用者的认领被抹掉的话，第三个窗口立刻就能挤进来")
        assertEquals(999L, SyncClaim.readOwner(claim)!!.pid)
    }

    @Test
    fun `release 会删掉自己的认领`() {
        SyncClaim.tryAcquire(claim, "C:/proj/mine", myPid = 100)

        SyncClaim.release(claim, myPid = 100)

        assertFalse(Files.exists(claim))
        assertNull(SyncClaim.readOwner(claim))
    }

    @Test
    fun `认领文件读坏了当作没有认领，可以接管`() {
        Files.createDirectories(claim.parent)
        Files.writeString(claim, "这不是 JSON")

        val r = SyncClaim.tryAcquire(claim, "C:/proj/mine", myPid = 100, isAlive = { true })

        assertEquals(SyncClaim.Result.Acquired, r, "读不动的认领挡不住人 —— 挡住的代价是「同步再也起不来」")
    }

    @Test
    fun `写认领不留临时文件 —— 半个认领文件等于没有认领`() {
        SyncClaim.tryAcquire(claim, "C:/proj/mine", myPid = 100)

        val leftovers = Files.list(claim.parent).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf(claim.fileName.toString()), leftovers)
    }

    @Test
    fun `真实判活：自己的 pid 活着，一个荒谬的 pid 不是`() {
        assertTrue(SyncClaim.defaultIsAlive(ProcessHandle.current().pid()))
        assertFalse(SyncClaim.defaultIsAlive(Long.MAX_VALUE), "不存在的 pid 必须判成「不在了」，否则陈旧认领永远接管不了")
    }
}
