package com.ccoder.sidecar

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * sidecar 进程的生命周期。
 *
 * 进程树是三层：PyCharm → node sidecar → claude CLI。
 * 杀掉直接子进程**不会**触及孙进程 claude，因此关闭时必须杀整棵树
 * （spec §7.1）。这是 Windows 上最容易留下孤儿进程的地方。
 *
 * 会话的工作目录不在构造器里 —— 它随 `start` 消息的 `params.cwd` 传递，
 * 单一来源，避免两处配置漂移。
 */
class SidecarProcess(
    private val sidecarDir: Path,
    private val nodePath: String,
) {
    private var process: Process? = null
    private val stderrLines = Collections.synchronizedList(mutableListOf<String>())

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
        Thread({
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
        }, "ccoder-sidecar-stderr").apply { isDaemon = true }.start()
    }

    /**
     * 关闭进程。按 spec §7.4 的顺序：
     * 等宽限期 → destroyForcibly → 杀整棵进程树。
     */
    fun shutdown(graceMillis: Long = DEFAULT_GRACE_MILLIS) {
        val p = process ?: return

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
