package com.ccoder.sync

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 把一串文件事件合并成**一轮一轮串行**的同步。
 *
 * 逐条移植参考实现 `watch_remote.py` 的 `SyncScheduler`。两条设计不许动：
 *
 * 1. **事件只当提示，不当任务。** 收到事件时**不记录"要同步哪些文件"**，只记"该去看一眼了"。
 *    于是事件丢了、合并了、重复了都无害（下一轮扫描照样发现全部改动），也不需要维护
 *    待同步队列、重启不丢状态。这是整个调度器敢做去抖的前提。
 * 2. **只有一个消费者。** 只有 [start] 起的那条线程会调 `runRound`，串行化靠这一点，
 *    不靠锁。加上引擎里"复制串行"，两层保证同一时刻只有一个写者。
 *
 * ## 为什么要能测，以及怎么测
 *
 * 这一层的错都是"安静地不对"，而且都不是靠读代码能看出来的：
 *
 * - **巡检被事件饿死**：远端被改回去就永远发现不了（参考实现专门纠正过一次）
 * - **退避形同虚设**：失败后干等事件，网络恢复了也不动
 * - **静默期无限延后**：事件洪流下一轮都跑不起来
 *
 * 所以决策抽成 [step]（不动时钟、不睡觉、不碰盘，只改自己的状态），线程只负责
 * "问下一步等多久 → 等 → 再问"。单测直接喂时刻，一条用例一个错误。
 */
internal class SyncScheduler(
    /** 配置每次读 —— 设置页可能中途改（改了时长不必重启调度器）。 */
    private val config: () -> SyncConfig,
    /** 跑一轮。返回 true = 这一轮没有失败项。 */
    private val runRound: (reconcileTarget: Boolean) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {

    /** 这一轮为什么跑。报告与状态卡按它讲人话。 */
    enum class Reason {
        /** 启动时的第一轮：把当前全部差异推过去。 */
        BASELINE,

        /** 退避结束后的自动重试。 */
        RETRY,

        /** 有文件被触碰（内容有没有真变由对账决定）。 */
        EVENT,

        /** 巡检：距上次完整轮到期了，防事件遗漏。 */
        SWEEP,
    }

    data class Trigger(val reason: Reason, val full: Boolean)

    /** 下一步该干什么。 */
    internal sealed interface Step {
        /** 可以跑一轮了。 */
        data class Run(val trigger: Trigger) : Step

        /** 还不到时候：等这么久再看（`null` = 无限等，等事件或停止）。 */
        data class Wait(val timeoutMs: Long?) : Step
    }

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()

    private var thread: Thread? = null

    @Volatile
    private var stopped = false

    // ---- 状态：只在 [step]（可能在读取线程之外的调用线程上）或 [poke] 的锁里改 ----
    private var lastEventAtMs = 0L
    /** 第一件事件的时刻。**用可空而不是 0 当哨兵** —— 单测的时钟就从 0 开始。 */
    private var firstEventAtMs: Long? = null
    private var lastFullAtMs = 0L
    private var retryAtMs: Long? = null
    private var streak = 0
    private var baselinePending = true
    private var settleActive = false
    private var hintShown = false
    private var pendingCount = 0
    private var lastPoked: String? = null

    // ---------------------------------------------------------------- 对外

    /** 起那条唯一的 worker 线程。只调一次。 */
    fun start() {
        if (thread != null) return
        val t = Thread({ loop() }, "ccoder-sync-worker")
        t.isDaemon = true
        thread = t
        log("[启动] 先跑一轮基线同步，把当前差异推过去")
        t.start()
    }

    /**
     * 有文件被触碰。
     *
     * 只记"该去看一眼了"（外加最近那条路径，纯为日志）——**不记录待同步清单**，见类头注。
     */
    fun poke(rel: String? = null) {
        if (stopped) return
        lock.withLock {
            pendingCount++
            lastEventAtMs = clock()
            if (firstEventAtMs == null) firstEventAtMs = lastEventAtMs
            if (rel != null) lastPoked = rel
            // 必须在持锁时 signal —— Condition 绑在锁上，锁外调用抛 IllegalMonitorStateException
            cond.signalAll()
        }
    }

    /**
     * 停，并**等在飞的那一轮跑完**。
     *
     * 不打断正在跑的一轮是刻意的：复制到一半被掐掉会在目标端留个半截文件，而它
     * **不在基线里**（基线只记复制成功的），于是下一轮会重新发现并覆盖它 —— 但那要等
     * 到下次同步。宁可多等一会儿。
     *
     * @return true = 线程确实结束了；false = 超时了（调用方只能记一笔日志）
     */
    fun stopAndJoin(timeoutMs: Long): Boolean {
        stopped = true
        lock.withLock { cond.signalAll() }   // 同上：signal 必须在持锁时
        val t = thread ?: return true
        t.join(timeoutMs)
        return !t.isAlive
    }

    // ---------------------------------------------------------------- 主循环

    private fun loop() {
        while (!stopped) {
            val step = lock.withLock { step(clock()) }
            when (step) {
                is Step.Run -> {
                    val ok = runOnce(step.trigger)
                    lock.withLock { afterRound(step.trigger, ok) }
                }

                is Step.Wait -> if (!stopped) awaitSignal(step.timeoutMs)
            }
        }
    }

    /** 跑一轮，把异常折成"这一轮不 ok" —— 单轮异常绝不拖垮监听。 */
    private fun runOnce(trigger: Trigger): Boolean = try {
        runRound(trigger.full)
    } catch (e: SyncError) {
        // 目标端不可访问（盘掉了 / 网络断了）：退避后自动重试，进程绝不退出
        log("[错误] ${e.message}")
        log("[重试] 前置检查未通过；监听继续，稍后自动重试")
        false
    } catch (e: Exception) {
        log("[错误] 本轮同步异常：$e")
        false
    }

    /**
     * 一轮结束后的状态推进（`lastFullAt` / 退避序列）。
     *
     * **内部可见只为可测**：它是主循环的另一半，而退避那几条性质（不能被事件提前打断、
     * 序列 5→10→20→30→60、成功后清零）只有把它和 [step] 配起来才测得动 ——
     * 靠真起线程 + 真睡觉去测这几条，会既慢又不稳。
     */
    internal fun afterRound(trigger: Trigger, ok: Boolean) {
        if (trigger.full) lastFullAtMs = clock()
        if (ok) {
            streak = 0
            return
        }
        val backoff = BACKOFF_MS[minOf(streak, BACKOFF_MS.size - 1)]
        streak++
        // 退避期内**不等事件**：等事件的话，网络恢复了也要等下一个文件被碰才行
        retryAtMs = clock() + backoff
        log("[退避] ${backoff / 1000} 秒后重试")
    }

    /** 等，直到被叫醒或超时。**返回什么不重要**：醒来之后一律重新 [step]，见它头注那段。 */
    private fun awaitSignal(timeoutMs: Long?) = try {
        lock.withLock {
            if (timeoutMs == null) cond.await() else cond.await(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        }
    } catch (_: InterruptedException) {
        // 停止时会 signal，正常路径不会走到这
    }

    // ---------------------------------------------------------------- 纯决策

    /**
     * 决定下一步。**不动时钟、不睡觉、不碰盘** —— 这就是它能被单测喂时刻的原因。
     *
     * 顺序**不可调换**（与参考实现逐条对应）：
     *
     * 1. 启动基线轮，强制完整轮
     * 2. 退避期**先于事件判断** —— 否则退避形同虚设（网络还没恢复就被事件催着跑）
     * 3. 有未处理的事件 → 进静默期，等到安静为止（上限 [SyncConfig.MAX_SETTLE_MS]）
     * 4. 静默期结束 → 跑一轮；**巡检已到期就顺手走完整轮**（事件再频繁也不能饿死巡检）
     * 5. 没事件 → 等巡检到期；兜底关了就一直等事件
     *
     * ## "有没有未处理的事件"是从状态读的，不是从 `await` 的返回值拿的
     *
     * 判据是 `firstEventAtMs != null`（由 [poke] 置上、由第 4 步清掉）。**这一点是踩出来的**：
     * 第一版用 `cond.await()` 的返回值当"被事件叫醒"，于是有一条真实的丢唤醒 ——
     * `poke` 若发生在 worker 还没进入等待时（比如它正在跑上一轮），那个 signal 就丢了，
     * 时间戳记下了却没人据此进静默期，**那一轮事件永远不跑**。
     * 参考实现的 `threading.Event` 是粘性的（`wait()` 会立刻返回），所以它没这个问题。
     * 把提示放进状态里，竞态从根上消失，[step] 也因此不必再要一个"被叫醒了吗"的参数。
     */
    internal fun step(now: Long): Step {
        val cfg = config()

        if (baselinePending) {
            baselinePending = false
            return Step.Run(Trigger(Reason.BASELINE, full = true))
        }

        retryAtMs?.let { at ->
            if (now < at) return Step.Wait(at - now)
            retryAtMs = null
            return Step.Run(Trigger(Reason.RETRY, full = false))
        }

        // 只在**刚进**静默期时记日志：静默期里还会来一串事件，每次都记就成了刷屏
        if (firstEventAtMs != null && !settleActive) {
            settleActive = true
            if (!hintShown) {
                hintShown = true
                log(
                    "[提示] 文件被触碰 ≠ 内容改动：读文件、IDE 索引、构建产物都会产生事件。" +
                        "是否真要同步由内容对账决定 —— 清单为空就代表本轮无事可做。",
                )
            }
            val sample = lastPoked?.let { "（最近：$it）" } ?: ""
            log("[事件] $pendingCount 个路径被触碰$sample → 开始检查")
            pendingCount = 0
        }

        if (settleActive) {
            val idle = now - lastEventAtMs
            val span = now - (firstEventAtMs ?: lastEventAtMs)
            if (idle < cfg.settleMs && span < SyncConfig.MAX_SETTLE_MS) {
                // 两个上限都要取：事件洪流（一次 revert -R . 之类）下不能把一轮无限延后
                val wait = minOf(cfg.settleMs - idle, SyncConfig.MAX_SETTLE_MS - span)
                return Step.Wait(wait.coerceAtLeast(1))
            }
            settleActive = false
            firstEventAtMs = null
            lastEventAtMs = 0L
            // 静默期里巡检到期了 → 这一轮顺手走完整轮，不另开一轮
            return if (sweepOverdue(now, cfg)) {
                Step.Run(Trigger(Reason.SWEEP, full = true))
            } else {
                Step.Run(Trigger(Reason.EVENT, full = false))
            }
        }

        val dueIn = sweepDueInMs(now, cfg) ?: return Step.Wait(null)
        if (dueIn <= 0) {
            log("[巡检] 定时兜底扫描（防事件遗漏）→ 开始检查")
            return Step.Run(Trigger(Reason.SWEEP, full = true))
        }
        return Step.Wait(dueIn)
    }

    /**
     * 距下次**必须**做的完整轮还有多少毫秒；`null` = 兜底已关闭。
     *
     * 注意这是"距上次完整轮"的**硬期限**，不是"已经安静多久" —— 参考实现的注释专门记了
     * 这一条：按后者写，每次事件都会把计时重置，事件一频繁巡检就被无限期饿死，
     * 于是"目标端那份被人改过"永远发现不了。
     */
    private fun sweepDueInMs(now: Long, cfg: SyncConfig): Long? =
        if (cfg.sweepMs <= 0) null else lastFullAtMs + cfg.sweepMs - now

    private fun sweepOverdue(now: Long, cfg: SyncConfig): Boolean =
        sweepDueInMs(now, cfg)?.let { it <= 0 } ?: false

    private companion object {
        /** 失败后的退避序列（毫秒）。与参考实现一致：5 → 10 → 20 → 30 → 60，到顶不再加。 */
        val BACKOFF_MS = longArrayOf(5_000, 10_000, 20_000, 30_000, 60_000)
    }
}
