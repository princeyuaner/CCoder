package com.ccoder.sidecar

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.ccoder.text.CcoderText

/**
 * 一条要跑的命令。
 *
 * [program] 能解析出绝对路径时**就写绝对路径** —— 安装命令尤其如此：
 * `npm` 在 Windows 上必须是 `npm.cmd`，而装完 node 之后 IDE 进程的 PATH 并不会刷新，
 * 与其指望 PATH，不如从解析到的 node 目录里推出 npm 的位置（设计稿 §3.6）。
 */
data class Command(val program: String, val args: List<String> = emptyList()) {

    /**
     * 给人看的一行。确认框里显示的就是它 —— **所见即将执行**，
     * 所以带空格的参数必须补引号，否则用户核对的是一句跑不起来的话。
     */
    fun display(): String = (listOf(program) + args).joinToString(" ") { quote(it) }

    private fun quote(part: String): String =
        if (part.any { it.isWhitespace() || it == '"' }) "\"" + part.replace("\"", "\\\"") + "\"" else part
}

/** 跑一条命令过程中回吐的事件。 */
sealed interface RunEvent {
    /** 一行输出（stdout 与 stderr 已合并，时序保真）。 */
    data class Line(val text: String) : RunEvent

    /** 进程自己退出，含非零退出码。 */
    data class Exited(val code: Int) : RunEvent

    /** 用户按的取消。**不是失败** —— 界面必须分开说（见 [CommandRunner.cancel]）。 */
    data object Cancelled : RunEvent

    /** 起不来或超时。[reason] 直接给用户看。 */
    data class Failed(val reason: String) : RunEvent
}

/**
 * 一次外部命令的执行句柄。
 *
 * 抽成接口只有一个理由：上层的用例（服务的竞态、页面的动作接线）要能注入一个
 * **不真起进程**的假件。[CommandRunner] 是唯一的生产实现。
 */
interface CommandRun {
    fun start()
    fun cancel()
    val isAlive: Boolean
}

/**
 * 跑一条一次性命令（安装器），逐行回吐，可取消、有超时。
 *
 * 设计稿 `docs/superpowers/specs/2026-09-17-runtime-deps-design.md` §3.4。
 *
 * ## 与 [SidecarProcess] 的三处刻意不同
 *
 * 1. **`redirectErrorStream(true)` + 单条读取线程。** sidecar 那边 stdout 是 NDJSON 协议通道，
 *    必须与 stderr 分开；这里 stdout 只是日志，合并才保得住两路的**时序** ——
 *    安装器把进度写 stdout、把报错写 stderr，分开读会看到"报错在进度之前"。
 * 2. **杀树的顺序相反。** 见 [killTreeAndWait]。
 * 3. **不看门狗回调里的 `shuttingDown` 分支**：取消语义用 [cancelled]，
 *    终态事件保证只发一次（[finished]）。
 *
 * ## 故意的：不在 IDE 退出时杀安装
 *
 * 这个对象不注册任何 Disposable。项目关掉时把 `msiexec` 腰斩，留下的可能是**半个 node**，
 * 比让它跑完更糟。安装的宿主是项目级服务，服务随项目结束而消失，但**进程不跟着杀**。
 *
 * ## 回调线程
 *
 * [onEvent] 全部在后台线程上回调（读取线程或看门狗线程），调用方负责自己回 EDT。
 */
class CommandRunner(
    private val command: Command,
    private val onEvent: (RunEvent) -> Unit,
    private val maxMillis: Long = DEFAULT_MAX_MILLIS,
) : CommandRun {

    private val cancelled = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    override val isAlive: Boolean get() = process?.isAlive == true

    override fun start() {
        if (process != null) error("进程已启动")
        // 还没起就被取消：直接给终态，别真跑
        if (cancelled.get()) {
            if (finished.compareAndSet(false, true)) onEvent(RunEvent.Cancelled)
            return
        }

        val p = try {
            ProcessBuilder(listOf(command.program) + command.args)
                .redirectErrorStream(true)
                // stdin 保持管道且**永不写入**：批处理垫片在某些路径上会等一个交互输入，
                // 而 inheritIO 会把 IDE 的输入抢走（设计稿 §3.4）
                .start()
        } catch (e: Exception) {
            if (finished.compareAndSet(false, true)) {
                onEvent(RunEvent.Failed(e.message ?: e.toString()))
            }
            return
        }
        process = p

        val reader = Thread({ pump(p) }, "ccoder-install-reader").apply { isDaemon = true }
        reader.start()
        Thread({ watch(p, reader) }, "ccoder-install-watchdog").apply { isDaemon = true }.start()
    }

    /**
     * 取消。杀掉整棵树，终态事件由看门狗发（它才拿得到退出码），这里不重复发。
     *
     * 少了"先置位再杀"这一步，界面就会把用户自己按的取消报成「安装失败（退出码 1）」
     * —— 本仓为同一类谎话付过代价（`SidecarProcess.shuttingDown` 的注释）。
     */
    override fun cancel() {
        cancelled.set(true)
        val p = process ?: return
        killTreeAndWait(p)
    }

    private fun pump(p: Process) {
        try {
            p.inputStream.bufferedReader().forEachLine { onEvent(RunEvent.Line(it)) }
        } catch (_: Exception) {
            // 被 kill 时流会异常关闭，属预期路径
        }
    }

    private fun watch(p: Process, reader: Thread) {
        val exited = runCatching { p.waitFor(maxMillis, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) {
            timedOut.set(true)
            killTreeAndWait(p)
        }
        // 进程刚死时管道里可能还压着最后几行 —— 安装器那句结论恰恰在最后。
        // 等读取线程读到 EOF 再发终态事件（同 SidecarProcess 的 STDERR_DRAIN 理由）。
        runCatching { reader.join(DRAIN_MILLIS) }

        if (!finished.compareAndSet(false, true)) return
        val code = runCatching { p.exitValue() }.getOrDefault(-1)
        onEvent(
            when {
                cancelled.get() -> RunEvent.Cancelled
                timedOut.get() -> RunEvent.Failed(CcoderText.text("chat.error.timeout", maxMillis / 1000))
                else -> RunEvent.Exited(code)
            }
        )
        process = null
    }

    /**
     * 杀掉整棵进程树。
     *
     * **顺序与 `SidecarProcess.shutdown` 相反，而且必须如此**：`npm.cmd` 的真实结构是
     * `cmd.exe → node.exe → …`。先 `destroy()` 只会结束直接子进程（cmd.exe），
     * 等父 PID 消失之后 `taskkill /T` 就**枚举不到子进程**了 ——
     * 界面说"已取消"，而 npm/msiexec 还在后台装。
     */
    private fun killTreeAndWait(p: Process) {
        runCatching { ProcessTreeKiller.killTree(p.pid()) }
        runCatching { p.destroyForcibly() }
        runCatching { p.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
    }

    companion object {
        /** 上限 10 分钟：装 node 可能好几分钟（下载 + msiexec）。 */
        const val DEFAULT_MAX_MILLIS = 10 * 60 * 1000L

        /** 等读取线程收尾的上限。 */
        private const val DRAIN_MILLIS = 1000L

        /** 杀完之后等进程真正消失的时间。 */
        private const val KILL_GRACE_MILLIS = 1000L
    }
}
