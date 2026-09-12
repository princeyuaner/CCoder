package com.ccoder.ui

import com.ccoder.sidecar.TranscriptOp
import com.intellij.openapi.diagnostic.Logger
import java.util.Timer
import java.util.TimerTask
import javax.swing.SwingUtilities

/**
 * 把转写操作按固定频率批量推送到 JCEF。
 *
 * 为什么必须节流：`includePartialMessages: true` 会让逐 token 增量以极高频率
 * 到达，而每一次 executeJavaScript 都要跨 CEF 进程边界。每个增量推一次会明显
 * 卡顿 —— 这是"流畅"目标的前提条件，不是可选优化。
 *
 * 16ms 约合 60fps，与显示器刷新率对齐。
 *
 * 与 JCEF 解耦（[exec] 注入），因此可单测。
 */
class TranscriptPump(
    private val exec: (String) -> Unit,
    throttleMs: Long = DEFAULT_THROTTLE_MS,
    private val maxBatch: Int = DEFAULT_MAX_BATCH,
) {
    private val lock = Any()
    private val buffer = mutableListOf<TranscriptOp>()

    @Volatile
    private var disposed = false

    private val timer = Timer("ccoder-transcript-pump", true).apply {
        scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    // exec 会触碰 JCEF/Swing，必须回到 EDT
                    SwingUtilities.invokeLater { runCatching { flushNow() } }
                }
            },
            throttleMs,
            throttleMs,
        )
    }

    fun enqueue(op: TranscriptOp) {
        if (disposed) return
        synchronized(lock) { buffer.add(op) }
    }

    /** 立即推送。测试与"关掉节流"场景用。 */
    fun flushNow() {
        if (disposed) return

        val batch = synchronized(lock) {
            if (buffer.isEmpty()) return
            // 单批上限。回放会把几百条一次塞进来（实测最大会话 804 条），
            // 全发出去就是一次几 MB 的 executeJavaScript，跨 CEF 进程边界
            // 会明显卡顿。留一部分给下一拍，代价只是多几帧。
            val n = minOf(buffer.size, maxBatch)
            val head = buffer.take(n)
            buffer.subList(0, n).clear()
            head
        }

        // 失败只影响本次批次（桥可能临时不可用，页面重载中），
        // 不该让节流器永久失效 —— 但必须留下痕迹。
        //
        // 这里曾经是 runCatching{} 静默吞掉：推送失败的表现是"界面不更新"，
        // 与"本来就没有消息"在屏幕上完全一样，排查时无从下手。
        try {
            exec(TranscriptOpCodec.encodeBatch(batch))
        } catch (e: Exception) {
            LOG.warn("CCoder 转写视图：推送 ${batch.size} 条操作失败", e)
        }
    }

    fun dispose() {
        disposed = true
        timer.cancel()
        synchronized(lock) { buffer.clear() }
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 16L

        /**
         * 单批操作数上限。
         *
         * 200 是估的：正常流式推送一拍只有几条到几十条，这个值不影响它；
         * 而 804 条的回放会被切成 5 拍（约 80ms）推完，用户看不出来。
         */
        const val DEFAULT_MAX_BATCH = 200

        private val LOG = Logger.getInstance(TranscriptPump::class.java)
    }
}
