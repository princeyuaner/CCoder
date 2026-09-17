package com.ccoder.settings

import com.ccoder.sidecar.Command
import com.ccoder.sidecar.CommandRun
import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.Os
import com.ccoder.sidecar.RunEvent
import com.ccoder.sidecar.RuntimeDep

/**
 * 依赖这一块的测试基建：服务、页面、渲染探针三处共用。
 *
 * 一条总规矩：**用例里不真起进程、也不碰 EDT** —— 探针是假的、调度是手动或同步的。
 * 真进程只留给 `CommandRunnerTest`（那里"真的杀得掉"才是被测对象）。
 */

/**
 * 手动调度器：`runAsync` 进来的任务排队等着，由用例点名执行。
 *
 * 竞态用例因此是**确定的**，不靠 sleep（同 `ClaudePanel` 那些 epoch 闸用例的思路）。
 */
internal class FakeScheduler {
    private val tasks = ArrayDeque<() -> Unit>()

    val pending: Int get() = tasks.size

    fun schedule(task: () -> Unit) {
        tasks.addLast(task)
    }

    fun runNext() {
        tasks.removeFirst().invoke()
    }

    /** 跑**最后**入队的那个 —— 用来构造"后发的先回来"这种竞态。 */
    fun runLast() {
        tasks.removeLast().invoke()
    }

    fun runAll() {
        while (tasks.isNotEmpty()) runNext()
    }
}

/** 假执行器：事件由用例点名发。 */
internal class FakeRun(private val onEvent: (RunEvent) -> Unit) : CommandRun {
    var cancelled = false
        private set

    override fun start() = Unit

    override fun cancel() {
        cancelled = true
        onEvent(RunEvent.Cancelled)
    }

    override val isAlive: Boolean = false

    fun emit(event: RunEvent) = onEvent(event)
}

/**
 * 一个不真起进程、也不碰 EDT 的依赖服务。
 *
 * `post` **始终同步**（测试 JVM 里没有 EDT）；`runAsync` 默认也同步，
 * 要测竞态就传 [FakeScheduler.schedule]。
 */
internal fun depsService(
    probe: (RuntimeDep, String?) -> DepStatus = { _, _ -> DepStatus.NotFound },
    runAsync: ((() -> Unit)) -> Unit = { it() },
    newRunner: (Command, (RunEvent) -> Unit) -> CommandRun = { _, _ ->
        error("这个用例不该起执行器")
    },
    explicitPath: (RuntimeDep) -> String? = { null },
): RuntimeDepsService = RuntimeDepsService(
    probe = probe,
    newRunner = newRunner,
    post = { it() },
    runAsync = runAsync,
    explicitPath = explicitPath,
)

/** 两个依赖都"可用"的样子。 */
internal val ALL_DEPS_OK: Map<RuntimeDep, DepStatus> = mapOf(
    RuntimeDep.NODE to DepStatus.Ok("C:\\Program Files\\nodejs\\node.exe", "24.13.1"),
    RuntimeDep.CLAUDE to DepStatus.Ok("C:\\npm\\claude.cmd", "2.1.268"),
)

/** 两个依赖都缺的样子。 */
internal val ALL_DEPS_MISSING: Map<RuntimeDep, DepStatus> = mapOf(
    RuntimeDep.NODE to DepStatus.NotFound,
    RuntimeDep.CLAUDE to DepStatus.NotFound,
)

/** 这台机器上包管理器齐全（Windows 口径）。 */
internal val TOOLS_WINDOWS: ToolSet = ToolSet(
    winget = "C:\\WindowsApps\\winget.exe",
    brew = null,
    npm = "C:\\Program Files\\nodejs\\npm.cmd",
)

/**
 * 对话框用例用的外壳：**工具解析也钉死**。
 *
 * 不钉的话，`toolSet()` 会去扫**跑测试这台机器**的 PATH 与已知目录 ——
 * 用例的结果于是取决于本机装了什么（实测 2026-09-17：本机 PATH 里一条带引号的
 * 条目直接把环境页的用例打崩，测的是别人的环境）。
 */
internal val TEST_DEPS_UI: DepsUi = DepsUi(os = Os.WINDOWS, tools = { TOOLS_WINDOWS })
