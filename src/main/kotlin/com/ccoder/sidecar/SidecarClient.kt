package com.ccoder.sidecar

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.ccoder.text.CcoderText

/**
 * NDJSON 分帧器（插件侧）。
 *
 * 与 sidecar/ndjson.js 的 NdjsonDecoder 对称 —— 两侧都必须容忍消息被切开。
 */
class NdjsonFramer {
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val idx = buffer.indexOf("\n")
            if (idx < 0) break
            val line = buffer.substring(0, idx)
            buffer.delete(0, idx + 1)
            out.add(line.removeSuffix("\r"))
        }
        return out
    }

    fun flush(): List<String> {
        if (buffer.isEmpty()) return emptyList()
        val rest = buffer.toString().removeSuffix("\r")
        buffer.setLength(0)
        return listOf(rest)
    }
}

interface SidecarListener {
    fun onMessage(msg: SidecarMessage)
}

/** 一次请求的结果。 */
sealed interface RequestOutcome {
    data class Answered(val message: SidecarMessage) : RequestOutcome

    /** 超时、通道关闭或 sidecar 退出。三种都意味着"不会再有答案了"。 */
    data class Failed(val reason: String) : RequestOutcome
}

/**
 * sidecar 的 stdin/stdout 通道。
 *
 * 只负责收发与分帧；消息含义由调用方解释。
 */
class SidecarClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: SidecarListener,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) {
    private val closed = AtomicBoolean(false)
    private var readerThread: Thread? = null

    private class Pending(val timer: TimerTask, val callback: (RequestOutcome) -> Unit)

    /**
     * 已发出但未配对的请求。
     *
     * 与 sidecar 侧的权限待决表（session.js 的 `pending`）对称 —— 两侧都
     * 有一个"发出去了、还没回来"的窗口，都必须保证它最终被关闭。
     */
    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * 超时定时器。**懒创建**。
     *
     * 它每个实例占一个线程，而大多数 client 一辈子没发过请求（只 sendLine 的
     * 那些），为它们开一个线程既浪费，又会被平台测试框架的线程泄漏检测逮住
     * —— 实测过：不懒创建时，`sendLine 原样写出` 那条用例会因为
     * `Thread leaked: ccoder-sidecar-request-timeout` 而失败。
     */
    private var timer: Timer? = null

    @Synchronized
    private fun timerOrCreate(): Timer =
        timer ?: Timer("ccoder-sidecar-request-timeout", true).also { timer = it }

    fun start() {
        val thread = Thread({
            val framer = NdjsonFramer()
            try {
                // InputStreamReader 在字节层做 UTF-8 解码，
                // 多字节字符不会因为读取块边界而损坏
                input.reader(StandardCharsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    while (!closed.get()) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        dispatch(framer.feed(String(buf, 0, n)))
                    }
                }
                dispatch(framer.flush())

                // 读线程走到这里 = 流结束 = sidecar 不会再回话了。
                // 挂着的请求必须立刻失败，不能等超时
                failAllPending(CcoderText.text("chat.error.sidecarExited"))
            } catch (_: Exception) {
                // 流被关闭（进程退出或主动 close）是预期路径，不向上传播
            }
        }, "ccoder-sidecar-reader")
        thread.isDaemon = true
        thread.start()
        readerThread = thread
    }

    private fun dispatch(lines: List<String>) {
        for (line in lines) {
            // parse 返回 null 表示非 JSON、空行或畸形消息 —— 跳过即可
            val msg = Protocol.parse(line) ?: continue

            // 已被配对的响应不再往下走，否则它会被处理两遍。
            // id 对不上的仍然送给 listener —— 静默丢弃会让"请求超时"
            // 和"消息丢了"两种故障长得一模一样
            val rid = Protocol.responseIdOf(msg)
            if (rid != null && resolvePending(rid, msg)) continue

            listener.onMessage(msg)
        }
    }

    /** 写出单行 JSON。已关闭或写入失败时静默忽略 —— sidecar 可能已退出。 */
    @Synchronized
    fun sendLine(json: String) {
        if (closed.get()) return
        try {
            output.write(json.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } catch (_: Exception) {
            // sidecar 已退出，写入失败是预期情况
        }
    }

    /**
     * 发一条请求，在收到**带同一 id 的响应**时回调。
     *
     * 回调保证恰好发生一次，三条路径都会走到：响应到达、超时、通道关闭
     * 或 sidecar 退出。这与 spec §6.2 规则① 同源 —— 挂起的回调不能泄漏，
     * 否则界面会永远停在"载入中"。
     *
     * 回调在**读取线程**上执行。调用方要碰 Swing 得自己 invokeLater。
     */
    fun request(id: String, json: String, callback: (RequestOutcome) -> Unit) {
        if (closed.get()) {
            callback(RequestOutcome.Failed(CcoderText.text("chat.error.channelClosed")))
            return
        }
        registerAndSchedule(id, callback)
        sendLine(json)
    }

    /**
     * 登记与排期**必须在同一把锁里**。
     *
     * 拆开成两步的话有一条真实的竞态：读线程恰好在两步之间因为流结束
     * （sidecar 退出）跑 [failAllPending]，它会把 pending 里这条取走并
     * `cancel()` 掉那个**还没排期**的 TimerTask；主线程随后 `schedule`
     * 就抛 `IllegalStateException: Task already scheduled or cancelled`，
     * 而且是在调用方的线程上抛，调用方拿不到 [RequestOutcome]。
     *
     * 先登记再调度是为了防另一半问题（极短超时在登记前触发导致回调丢失），
     * 两个方向都得堵，所以合成一个原子操作。
     */
    @Synchronized
    private fun registerAndSchedule(id: String, callback: (RequestOutcome) -> Unit) {
        val task = object : TimerTask() {
            override fun run() = onTimeout(id)
        }
        pending[id] = Pending(task, callback)
        timerOrCreate().schedule(task, requestTimeoutMs)
    }

    @Synchronized
    private fun onTimeout(id: String) {
        val p = pending.remove(id) ?: return
        p.timer.cancel()
        p.callback(RequestOutcome.Failed(CcoderText.text("chat.error.requestTimeout")))
    }

    /**
     * @return true 表示这条消息已被某个待决请求消费
     *
     * 与 [registerAndSchedule] / [failAllPending] 同一把锁 —— 三者都在改
     * pending 表，交错执行就会出上面那种窗口。
     */
    @Synchronized
    private fun resolvePending(id: String, msg: SidecarMessage): Boolean {
        val p = pending.remove(id) ?: return false
        p.timer.cancel()
        p.callback(RequestOutcome.Answered(msg))
        return true
    }

    @Synchronized
    private fun failAllPending(reason: String) {
        for (id in pending.keys.toList()) {
            val p = pending.remove(id) ?: continue
            p.timer.cancel()
            p.callback(RequestOutcome.Failed(reason))
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        failAllPending(CcoderText.text("chat.error.channelClosed"))
        runCatching { output.close() }
        readerThread?.interrupt()
        timer?.cancel()
    }

    companion object {
        /** 列表与回放都是本地读取，实测 140ms 量级。10 秒是很宽的余量。 */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
    }
}
