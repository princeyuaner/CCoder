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
        synchronized(lock) {
            // 同 target 的连续增量**就地合并**成一条。
            //
            // 逐 token 流式下，一拍里躺着几十条 AppendDelta，而它们在 applyOps
            // 里本来就是顺序做字符串拼接 —— 先拼好再发，语义完全等价（并发交错
            // 时也一样：applyOps 认的就是到达顺序），但 op 数与 JSON 体积降一个
            // 数量级，前端也从几十次拼接变成一次。
            //
            // 合并规则只有"看 buffer 末尾"这一条，且天然安全：中间夹了任何别的
            // op，末尾就不再是同类增量，合并自动断开。这挡住的是真正会出错的两处
            // —— ClearDelta / FinalizeDelta 会清空 live 缓冲，跨过它们把两侧文本
            // 拼一起就是无中生有（见 TranscriptPumpTest 的三条边界用例）。
            val last = buffer.lastOrNull()
            if (op is TranscriptOp.AppendDelta &&
                last is TranscriptOp.AppendDelta &&
                last.target == op.target
            ) {
                buffer[buffer.size - 1] = last.copy(text = last.text + op.text)
                return
            }
            buffer.add(op)
        }
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
        //
        // **注意这条日志盖不住最要紧的那种断法**：CEF 通道真的断了的时候，
        // `executeJavaScript` 走到的 `RpcExecutor.exec` 是自己 `return` 掉的
        // （不抛错），所以"卡死 + 日志干净"是可能的，别据此认为通道没事
        // —— 见 `ClaudeTranscriptView` 顶上"第二副面孔"那段。
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
