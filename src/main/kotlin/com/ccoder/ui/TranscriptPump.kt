package com.ccoder.ui

import com.ccoder.sidecar.TranscriptOp
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
            buffer.toList().also { buffer.clear() }
        }

        // 失败只吞掉本次：桥可能临时不可用（页面重载中），
        // 不该让节流器永久失效
        runCatching { exec(TranscriptOpCodec.encodeBatch(batch)) }
    }

    fun dispose() {
        disposed = true
        timer.cancel()
        synchronized(lock) { buffer.clear() }
    }

    companion object {
        const val DEFAULT_THROTTLE_MS = 16L
    }
}
