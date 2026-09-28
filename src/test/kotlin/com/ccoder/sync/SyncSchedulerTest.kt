package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 触发状态机（2026-09-24）。**决策全部喂时刻**，不起线程、不睡觉。
 *
 * 这一层的错都是"安静地不对"，而且都不是读代码能看出来的 —— 三条各有一组用例：
 *
 * | 写错了会怎样 | 用例 |
 * |---|---|
 * | **巡检被事件饿死** → 目标端被改回去永远发现不了 | [巡检的硬期限：事件再频繁也不会把它饿死] |
 * | **退避形同虚设** → 网络恢复了也干等事件 | [退避期内来了事件也不提前跑] |
 * | **静默期无限延后** → 事件洪流下一轮都跑不起来 | [静默期上限 20 秒] |
 *
 * 只有最后两条真起线程的用例（`start` 的自动首轮、`poke` 能叫醒 worker）走真时钟 ——
 * 那两条验的是"线程确实被叫醒了"，用注入的时刻反而验不到。
 */
class SyncSchedulerTest {

    /**
     * 时刻驱动器：`now` 由用例推，`step` / `afterRound` 直接调。
     *
     * 起始时刻刻意不取 0（虽然实现已经不用 0 当哨兵了，见 `firstEventAtMs`）。
     */
    private class Driver(sweepMs: Long = 60_000, settleMs: Long = 1_500) {
        var now = 1_000_000L
        val sched = SyncScheduler(
            config = { SyncConfig(settleMs = settleMs, sweepMs = sweepMs) },
            runRound = { true },
            clock = { now },
        )

        fun step(): SyncScheduler.Step = sched.step(now)
        fun poke(rel: String = "trunk/a.kt") = sched.poke(rel)
        fun afterRun(trigger: SyncScheduler.Trigger, ok: Boolean = true) = sched.afterRound(trigger, ok)

        /** 走完启动那一轮基线（第一轮永远是它）。 */
        fun baseline() {
            val t = run()
            assertEquals(SyncScheduler.Reason.BASELINE, t.reason)
            afterRun(t, ok = true)
        }

        fun run(): SyncScheduler.Trigger = (step() as SyncScheduler.Step.Run).trigger

        fun wait(): Long {
            val s = step()
            assertTrue(s is SyncScheduler.Step.Wait, "本该是「等」，实际：$s")
            return (s as SyncScheduler.Step.Wait).timeoutMs ?: -1
        }

        fun event(): SyncScheduler.Trigger = SyncScheduler.Trigger(SyncScheduler.Reason.EVENT, full = false)
    }

    // ---------------------------------------------------------------- 首轮与静默期

    @Test
    fun `第一轮是基线轮，强制走完整轮`() {
        assertEquals(
            SyncScheduler.Trigger(SyncScheduler.Reason.BASELINE, full = true),
            Driver().run(),
        )
    }

    @Test
    fun `事件之后要等到安静 settle 毫秒才开始`() {
        val d = Driver(settleMs = 1_500, sweepMs = 0)
        d.baseline()

        d.now = 1_100_000
        d.poke("trunk/a.kt")
        assertEquals(1_500, d.wait(), "刚被叫醒时，要等满整个静默期")

        d.now += 700
        d.poke("trunk/b.kt")
        assertEquals(1_500, d.wait(), "事件还在来 → 重新计时，不是累计")

        d.now += 1_300
        assertEquals(200, d.wait(), "距最后一个事件才 1300ms，差 200ms")

        d.now += 201
        assertEquals(SyncScheduler.Reason.EVENT, d.run().reason, "安静够了就开跑")
    }

    @Test
    fun `worker 还在跑上一轮时来的事件不会丢 —— 那个提示记在状态里`() {
        // 这是一条**回归用例**，钉的是实现里踩过的一个真 bug：第一版拿 `cond.await()` 的
        // 返回值当"被事件叫醒"，于是 poke 若发生在 worker 还没进入等待时（它正在跑上一轮）
        // 那个 signal 就丢了 —— 时间戳记下了却没人据此进静默期，**那一轮事件永远不跑**。
        // 参考实现的 `threading.Event` 是粘性的，所以它没有这个问题。
        //
        // 形状就是那条路径：poke 之后**没人 await**，直接问下一步。
        val d = Driver(sweepMs = 0, settleMs = 100)
        d.baseline()

        d.poke("trunk/a.kt")
        d.step()                       // 这一步在旧实现里会因为"没被叫醒"而跳过静默期

        d.now += 200
        assertEquals(
            SyncScheduler.Reason.EVENT,
            d.run().reason,
            "事件必须还在 —— 丢了的话这个文件永远等不到下一轮（只能等巡检，默认 60 秒）",
        )
    }

    @Test
    fun `静默期上限 20 秒 —— 事件洪流下也必须开跑`() {
        val d = Driver(settleMs = 1_500, sweepMs = 0)
        d.baseline()

        d.now = 1_100_000
        d.poke("e0")
        for (i in 1..3) {
            d.now += 5_000
            d.poke("e$i")
            assertTrue(
                d.step() is SyncScheduler.Step.Wait,
                "距第一个事件才 ${i * 5} 秒，该继续等",
            )
        }
        d.now += 5_000
        d.poke("e4")
        assertEquals(
            SyncScheduler.Reason.EVENT,
            d.run().reason,
            "距第一个事件正好 20 秒 → 上限到，必须开跑（一次 revert -R . 就是这种洪流）",
        )
    }

    // ---------------------------------------------------------------- 巡检

    @Test
    fun `巡检的硬期限：事件再频繁也不会把它饿死`() {
        // 这条是参考实现专门纠正过一次的地方：把"距上次完整轮"写成"已经安静多久"，
        // 每次事件都会重置计时，于是事件一频繁巡检就被无限期推迟 ——
        // 而"目标端那份被人改过"只有巡检轮才会发现。
        val d = Driver(sweepMs = 60_000, settleMs = 1_500)
        d.baseline()

        var sawSweep = false
        var nextPoke = d.now + 2_000
        while (d.now < 1_300_000) {
            d.now += 500
            if (d.now >= nextPoke) {
                d.poke("e.kt")
                nextPoke = d.now + 2_000
            }
            when (val s = d.step()) {
                is SyncScheduler.Step.Run -> {
                    if (s.trigger.full) {
                        sawSweep = true
                        break
                    }
                    d.afterRun(s.trigger, ok = true)      // 事件轮：lastFull 不动
                }

                is SyncScheduler.Step.Wait -> Unit
            }
        }

        assertTrue(sawSweep, "事件一直不断时，巡检仍必须在硬期限内发生")
    }

    @Test
    fun `静默期结束时若巡检已到期，这一轮就顺手走完整轮`() {
        val d = Driver(sweepMs = 10_000, settleMs = 1_500)
        d.baseline()                     // lastFull = 1_000_000 → 巡检 1_010_000 到期

        d.now = 1_020_000                // 已经过了一天（就巡检的计时而言）
        d.poke("a.kt")
        d.step()              // 进静默期
        d.now += 2_000                   // 安静够了
        val t = d.run()

        assertEquals(SyncScheduler.Reason.SWEEP, t.reason, "不另开一轮，这一轮顺手做掉")
        assertTrue(t.full, "到期就必须走完整轮")
    }

    @Test
    fun `兜底关了就一直等事件，不空转`() {
        val d = Driver(sweepMs = 0)
        d.baseline()

        assertNull((d.step() as SyncScheduler.Step.Wait).timeoutMs, "sweepMs = 0 → 无限等")
    }

    // ---------------------------------------------------------------- 退避

    @Test
    fun `退避序列 5、10、20、30、60 秒，到顶不再加`() {
        val d = Driver()
        d.baseline()
        val expect = listOf(5_000L, 10_000L, 20_000L, 30_000L, 60_000L, 60_000L)

        expect.forEachIndexed { i, want ->
            d.afterRun(d.event(), ok = false)
            assertEquals(want, d.wait(), "第 ${i + 1} 次失败该退避 $want 毫秒")
            d.now += want
            assertEquals(SyncScheduler.Reason.RETRY, d.run().reason, "退避结束要**自动**重试")
        }
    }

    @Test
    fun `退避期内来了事件也不提前跑 —— 否则退避形同虚设`() {
        val d = Driver()
        d.baseline()
        d.afterRun(d.event(), ok = false)        // 失败 → 退避 5 秒

        d.now += 1_000
        d.poke("a.kt")
        assertEquals(4_000, d.wait(), "等事件的话，网络恢复了也要等下一个文件被碰才动")

        d.now += 4_000
        assertEquals(
            SyncScheduler.Reason.RETRY,
            d.run().reason,
            "退避优先于事件。那个被推迟的事件**不会丢**：重试轮跑完之后它会带出一轮事件轮" +
                "（提示记在状态里，不是记在「被叫醒了吗」上）",
        )
    }

    @Test
    fun `成功一轮把退避计数清零`() {
        val d = Driver()
        d.baseline()
        val ev = d.event()

        d.afterRun(ev, ok = false)
        assertEquals(5_000, d.wait())
        d.now += 5_000
        d.afterRun(d.run(), ok = true)           // 重试成功 → 清零
        d.afterRun(ev, ok = false)               // 又失败
        assertEquals(5_000, d.wait(), "又从 5 秒起，不是接着 10 秒")
    }

    @Test
    fun `重试轮不遍历目标端 —— 它只是把失败的那些补上`() {
        val d = Driver()
        d.baseline()
        d.afterRun(d.event(), ok = false)
        d.now += 5_000

        val t = d.run()

        assertEquals(SyncScheduler.Reason.RETRY, t.reason)
        assertTrue(!t.full, "退避重试走事件轮那条路（本地改动照样会被发现，见 contentPlan）")
    }

    // ---------------------------------------------------------------- 真线程（只这两条）

    @Test
    @Timeout(30)
    fun `start 之后自动跑一轮基线；stop 之后不再有新一轮`() {
        val rounds = CopyOnWriteArrayList<Boolean>()
        val firstRound = CountDownLatch(1)
        val s = SyncScheduler(
            config = { SyncConfig(sweepMs = 0) },
            runRound = { full ->
                rounds += full
                firstRound.countDown()
                true
            },
        )

        s.start()
        assertTrue(firstRound.await(10, TimeUnit.SECONDS), "start 之后该自动跑一轮（基线轮）")
        assertTrue(s.stopAndJoin(10_000), "stopAndJoin 该把线程收干净")

        val seen = rounds.size
        Thread.sleep(300)
        assertEquals(seen, rounds.size, "stop 之后不该再有新一轮")
    }

    @Test
    @Timeout(30)
    fun `poke 能叫醒 worker —— 不必等巡检`() {
        val rounds = CountDownLatch(2)          // 基线一轮 + 事件一轮
        val s = SyncScheduler(
            config = { SyncConfig(sweepMs = 0, settleMs = 100) },
            runRound = {
                rounds.countDown()
                true
            },
        )

        s.start()
        s.poke("trunk/a.kt")

        assertTrue(rounds.await(10, TimeUnit.SECONDS), "poke 之后该在静默期结束时跑第二轮")
        s.stopAndJoin(10_000)
    }
}
