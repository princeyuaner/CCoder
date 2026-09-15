package com.ccoder.sidecar

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sidecar 自己退出时的实况。
 *
 * [stderr] 是退出时刻已收到的 stderr 全文（约最后 100 行）。
 * 它是排查启动失败唯一的线索 —— node 崩在包配置、模块解析或依赖缺失上时，
 * 报错只写在 stderr 里，stdout 一个字节都没有。
 */
data class SidecarExit(val code: Int, val stderr: List<String>)

/**
 * sidecar 进程的生命周期。
 *
 * 进程树是三层：PyCharm → node sidecar → claude CLI。
 * 杀掉直接子进程**不会**触及孙进程 claude，因此关闭时必须杀整棵树
 * （spec §7.1）。这是 Windows 上最容易留下孤儿进程的地方。
 *
 * 会话的工作目录不在构造器里 —— 它随 `start` 消息的 `params.cwd` 传递，
 * 单一来源，避免两处配置漂移。
 *
 * @param onExit 进程**自己**退出时回调（主动 [shutdown] 不算）。
 *   不传就没人知道进程死了 —— 2026-09-13 就是这样：`package.json` 被写坏，
 *   node 启动即崩，而 [ProcessBuilder.start] 对"建得起来、随即就死"不抛异常，
 *   于是界面停在「启动中…」，按钮禁用，用户只能重启 IDE。
 */
class SidecarProcess(
    private val sidecarDir: Path,
    private val nodePath: String,
    private val onExit: ((SidecarExit) -> Unit)? = null,
) {
    private var process: Process? = null
    private val stderrLines = Collections.synchronizedList(mutableListOf<String>())

    /** stderr 读取线程。退出时要 join 它收尾。 */
    private var stderrThread: Thread? = null

    /**
     * 主动关闭标记。
     *
     * 看门狗靠它区分"用户停的"和"自己死的" —— 少了它，每次正常换会话
     * 都会在转写区留下一条并不存在的错误。
     */
    private val shuttingDown = AtomicBoolean(false)

    val stdout: InputStream? get() = process?.inputStream
    val stdin: OutputStream? get() = process?.outputStream
    val stderrTail: List<String> get() = synchronized(stderrLines) { stderrLines.toList() }
    val isAlive: Boolean get() = process?.isAlive == true
    val pid: Long? get() = process?.pid()

    fun start() {
        if (process != null) error("进程已启动")

        val p = ProcessBuilder(nodePath, "index.js")
            .directory(sidecarDir.toFile())
            .start()
        process = p

        // stderr 必须单独消费，且必须异步。
        // - 合并进 stdout 会破坏 NDJSON 分帧
        // - 完全不读会在缓冲区满时让子进程阻塞
        // - 同步读（如 useLines）会阻塞到流关闭，使 start() 永不返回
        val errThread = Thread({
            try {
                p.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderrLines) {
                        stderrLines.add(line)
                        if (stderrLines.size > MAX_STDERR_LINES) stderrLines.removeAt(0)
                    }
                }
            } catch (_: Exception) {
                // 进程退出导致流关闭，属预期路径
            }
        }, "ccoder-sidecar-stderr").apply { isDaemon = true }
        errThread.start()
        stderrThread = errThread

        onExit?.let { callback ->
            Thread({
                val code = runCatching { p.waitFor() }.getOrDefault(-1)

                // 进程刚死时 stderr 里可能还压着最后几行，而那几行恰恰最要紧
                // —— node 的 "Error: ..." 就在其中。等读取线程把流读到 EOF
                // 再取值，否则报给用户的是一段没有结论的报错
                runCatching { errThread.join(STDERR_DRAIN_MILLIS) }

                // 主动关闭的不算故障（见 shuttingDown 的说明）
                if (shuttingDown.get()) return@Thread
                runCatching { callback(SidecarExit(code, stderrTail)) }
            }, "ccoder-sidecar-watchdog").apply { isDaemon = true }.start()
        }
    }

    /**
     * 关闭进程。按 spec §7.4 的顺序：
     * 等宽限期 → destroyForcibly → 杀整棵进程树。
     */
    fun shutdown(graceMillis: Long = DEFAULT_GRACE_MILLIS) {
        val p = process ?: return

        // 0. 先于一切置位：看门狗上的 waitFor 会在 destroy 之后返回，
        //    它据此知道这次退出是我们要的，不该报成故障
        shuttingDown.set(true)

        // 1. 先尝试优雅关闭。SIGTERM 在 Windows 上不等价 ——
        //    Java 会走 TerminateProcess，所以主要靠第 3 步兜底。
        runCatching { p.destroy() }

        // 2. 等待宽限期
        val exited = runCatching { p.waitFor(graceMillis, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)

        // 3. 仍在运行则强制结束直接子进程
        if (!exited) {
            runCatching {
                p.destroyForcibly()
                p.waitFor(1000, TimeUnit.MILLISECONDS)
            }
        }

        // 4. 兜底：杀整棵进程树，否则 claude 会变孤儿继续消耗额度
        runCatching { ProcessTreeKiller.killTree(p.pid()) }

        process = null
    }

    companion object {
        const val DEFAULT_GRACE_MILLIS = 3000L

        private const val MAX_STDERR_LINES = 100

        /**
         * 进程退出后等 stderr 读取线程收尾的上限。
         *
         * 进程一死管道就 EOF，读取线程随即结束，正常时几乎立刻返回 ——
         * 这个上限只是为了不让收尾拖住看门狗。
         */
        private const val STDERR_DRAIN_MILLIS = 1000L
    }
}

/**
 * 跨平台杀进程树。
 *
 * 这不是"多此一举"—— [Process.destroyForcibly] 只作用于直接子进程。
 */
object ProcessTreeKiller {

    fun killTree(pid: Long) {
        if (isWindows()) {
            runCatching {
                ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }
        } else {
            runCatching {
                // 类 Unix：先杀进程组（负 PID），失败再杀单进程。
                // 负号是关键 —— 缺了它只杀掉组长，子进程仍存活。
                ProcessBuilder("kill", "-TERM", "-$pid").start().waitFor(3, TimeUnit.SECONDS)
                ProcessBuilder("kill", "-KILL", "-$pid").start().waitFor(3, TimeUnit.SECONDS)
            }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
