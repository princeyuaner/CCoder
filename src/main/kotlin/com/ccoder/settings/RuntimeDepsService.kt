package com.ccoder.settings

import com.ccoder.sidecar.Command
import com.ccoder.sidecar.CommandRun
import com.ccoder.sidecar.CommandRunner
import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.RunEvent
import com.ccoder.sidecar.RuntimeDep
import com.ccoder.sidecar.probeRuntimeDep
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 一次安装的结局。注意 [CANCELLED] 不是 [FAILED] —— 界面必须分开说。 */
enum class InstallResult { SUCCEEDED, FAILED, CANCELLED }

/**
 * 安装的当前状态。**快照式**：每次变化都换一个新对象，页只管照着画。
 *
 * [Finished] 里留着 [log] 是刻意的：装失败时那几十行输出是**唯一的线索**，
 * 对话框关掉再打开还得看得见。
 */
sealed interface InstallState {
    data object Idle : InstallState
    data class Running(val dep: RuntimeDep, val plan: InstallPlan, val log: List<String>) : InstallState
    data class Finished(
        val dep: RuntimeDep,
        val plan: InstallPlan,
        val log: List<String>,
        val result: InstallResult,
    ) : InstallState
}

/**
 * 运行依赖的检测结果与安装进度。**项目级**，照 `McpStatus` 的形状。
 *
 * 设计稿 `docs/superpowers/specs/2026-09-17-runtime-deps-design.md` §3.5。
 *
 * ## 为什么必须是服务，不能是页构造注入的几个 lambda
 *
 * 1. **安装要活得比设置对话框长**：npm/winget 是几十秒到几分钟，关框不能杀进程
 *    （腰斩 msiexec 会留下半个 node）。
 * 2. **后台结果不能丢**：关框 → 重开，必须还能看到"装完了 / 装失败了 + 那几十行输出"。
 * 3. **检测结果要缓存**：`claude --version` 是几百毫秒的真进程，"关掉再开"不该重跑。
 *
 * ## 线程规矩（照着 McpStatus 那条来，但更严）
 *
 * - **状态只在 EDT 上改**：所有异步结果都经 [post] 回来再落到字段上；
 * - 服务内部**只**通过构造器注入的 [post] / [runAsync] 碰外部世界，
 *   自己不 import `ApplicationManager`（默认值那一次除外）——
 *   测试 JVM 里 `getApplication()` 是 null，破了这条规矩就是一片 NPE。
 *
 * ## 失效闸（为什么要两个计数）
 *
 * - [probeEpoch] **按依赖各一个**：`refreshAll()` 会同时查两个依赖，
 *   共用一个计数的话，后发的那个会把先发的那个的合法结果丢掉。
 * - [installEpoch] **全局一个**：一旦开始安装，所有在飞的检测结果一律作废
 *   （它们答的是"安装之前"的问题）；安装过程中的迟到事件也靠它丢。
 */
@Service(Service.Level.PROJECT)
class RuntimeDepsService(
    private val probe: (RuntimeDep, String?) -> DepStatus,
    private val newRunner: (Command, (RunEvent) -> Unit) -> CommandRun,
    private val post: ((() -> Unit)) -> Unit,
    private val runAsync: ((() -> Unit)) -> Unit,
    private val explicitPath: (RuntimeDep) -> String?,
) {

    /**
     * 平台靠构造器实例化（能拿到 [Project]）。
     *
     * 照 `ModelProfiles` 的先例：注入用的那几个**不能**写成主构造器的默认参数，
     * 平台反射找不到那种合成构造器，服务会静默注册失败。
     */
    constructor(project: Project) : this(
        probe = { dep, explicit -> probeRuntimeDep(dep, explicit) },
        newRunner = { command, onEvent -> CommandRunner(command, onEvent) },
        // **必须显式给 ModalityState.any()**（2026-09-17 真机上卡住之后查出来的）。
        //
        // 不带它的话，`invokeLater(task)` 用的是 `ModalityState.defaultModalityState()`，
        // 而一个普通后台线程在那儿的取值是 **nonModal** —— 在 PyCharm 2025.3.1.1 的
        // 字节码里读出来的：`ApplicationImpl.getDefaultModalityState()` 非 EDT 时走
        // `ModalityKt.defaultModalityImpl()`，一路 fallback 到底就是 `ModalityState.nonModal()`。
        //
        // 后果：**模态对话框开着时这些任务根本不执行**。设置框恰好是模态的，
        // 于是两行永远停在「检测中…」，直到用户把框关掉 —— 界面就在那个框里，
        // 这等于永远不更新。安装日志是同一个道理（事件也从后台线程来）。
        post = { task ->
            ApplicationManager.getApplication()?.invokeLater(task, ModalityState.any()) ?: task()
        },
        runAsync = { task -> Thread(task, "ccoder-deps-probe").apply { isDaemon = true }.start() },
        explicitPath = { dep ->
            // 只有 claude 有"显式路径"这个设置项；node 走三级解析
            if (dep == RuntimeDep.CLAUDE) ClaudeSettings.getInstance(project).claudePath.ifBlank { null } else null
        },
    )

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val statuses = ConcurrentHashMap<RuntimeDep, DepStatus>()
    private val probeEpoch = ConcurrentHashMap<RuntimeDep, Int>()
    private val installEpoch = AtomicInteger(0)

    private var install: InstallState = InstallState.Idle
    private var runner: CommandRun? = null

    /**
     * 装成功之后等着看复检结果的依赖。
     *
     * 为什么要有这一格：「装好了」和「找得到」是两件事 —— 最气人的结局是
     * 安装成功、状态却仍然报「未找到」（npm 全局前缀被改过时就是这样）。
     * 那时**必须补一句指路**，否则用户看着"装好了 + 未找到"完全无从下手。
     */
    private var awaitingRecheck: RuntimeDep? = null

    /** 还没查过时是 [DepStatus.Unknown] —— 与"查了，没有"必须分开（同 McpStatus 的 known）。 */
    fun statusOf(dep: RuntimeDep): DepStatus = statuses[dep] ?: DepStatus.Unknown

    fun installState(): InstallState = install

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 查一个依赖。结果经 [post] 回到 EDT 后才落进 [statusOf]。 */
    fun refresh(dep: RuntimeDep) {
        val epoch = probeEpoch.merge(dep, 1) { a, b -> a + b }!!
        val installAt = installEpoch.get()
        val explicit = explicitPath(dep)

        statuses[dep] = DepStatus.Checking
        notifyListeners()

        runAsync {
            val result = runCatching { probe(dep, explicit) }
                .getOrElse { DepStatus.Broken(explicit.orEmpty(), it.message ?: it.toString()) }
            post {
                // 迟到的结果丢掉：期间又刷新过同一个依赖，或已经开了安装
                if (probeEpoch[dep] != epoch || installEpoch.get() != installAt) return@post
                statuses[dep] = result
                // 刚装完的那一个：装成功却还是找不到，得把话说明白
                if (awaitingRecheck == dep) {
                    awaitingRecheck = null
                    if (result !is DepStatus.Ok) explainStillMissing(dep, result)
                }
                notifyListeners()
            }
        }
    }

    fun refreshAll() {
        RuntimeDep.entries.forEach { refresh(it) }
    }

    /** 开始一次代跑安装。[InstallPlan.command] 为空的（MANUAL）计划不该走到这里。 */
    fun startInstall(plan: InstallPlan) {
        val command = plan.command ?: return
        val epoch = installEpoch.incrementAndGet()
        val log = mutableListOf<String>()

        install = InstallState.Running(plan.dep, plan, log.toList())
        notifyListeners()

        val started = newRunner(command) { event -> post { onRunEvent(epoch, plan, event, log) } }
        runner = started
        runAsync { started.start() }
    }

    fun cancelInstall() {
        runner?.cancel()
    }

    private fun onRunEvent(epoch: Int, plan: InstallPlan, event: RunEvent, lines: MutableList<String>) {
        // 上一轮安装的迟到事件（取消后又开了一轮之类）一律丢掉。
        // 这个列表只在 EDT 上被碰（回调都经 post 进来），所以不必加锁
        if (installEpoch.get() != epoch) return

        when (event) {
            is RunEvent.Line -> {
                appendLine(lines, event.text)
                install = InstallState.Running(plan.dep, plan, lines.toList())
                notifyListeners()
            }

            is RunEvent.Exited -> {
                if (event.code != 0) appendLine(lines, "退出码 ${event.code}")
                finish(plan, lines, if (event.code == 0) InstallResult.SUCCEEDED else InstallResult.FAILED)
            }

            RunEvent.Cancelled -> {
                // 取消**不等于干净**：npm/winget 被中途杀掉可能留下未装完的文件
                appendLine(lines, "已取消 —— 可能留下未装完的文件，建议重跑一次")
                finish(plan, lines, InstallResult.CANCELLED)
            }

            is RunEvent.Failed -> {
                appendLine(lines, event.reason)
                finish(plan, lines, InstallResult.FAILED)
            }
        }
    }

    private fun finish(plan: InstallPlan, lines: MutableList<String>, result: InstallResult) {
        runner = null
        install = InstallState.Finished(plan.dep, plan, lines.toList(), result)
        notifyListeners()
        // 装完自动复检：状态行会自己变成"可用 · vX"
        if (result == InstallResult.SUCCEEDED) {
            awaitingRecheck = plan.dep
            refreshAll()
        }
    }

    /**
     * 装成功了、复检却依然找不到 —— 把最常见的那个原因说出来。
     *
     * 不猜路径、也不替用户把设置写死（写死了反而是个坑：以后 node 一挪位置，
     * "显式路径不存在"就再也不回退了）。只给一条他自己能走的下一步。
     */
    private fun explainStillMissing(dep: RuntimeDep, status: DepStatus) {
        val lines = when (dep) {
            RuntimeDep.CLAUDE ->
                listOf(
                    "装完了，但检测仍然找不到 claude。",
                    "常见原因：npm 的全局前缀被改过（装到了别处）。",
                    "跑一次 npm prefix -g 看它装到哪儿，然后把那个目录下的 claude 填到 " +
                        "设置 → 通用 → claude 可执行文件。",
                )

            RuntimeDep.NODE ->
                listOf(
                    "装完了，但检测仍然找不到 node（${statusTextShort(status)}）。",
                    "它可能装到了一个已知目录之外的地方。",
                    "重启 IDE 让新的 PATH 生效；仍然不行时，用 where node 找到它，" +
                        "把完整路径填到 设置 → 通用。",
                )
        }
        val current = install as? InstallState.Finished ?: return
        install = current.copy(log = current.log + lines)
    }

    private fun statusTextShort(status: DepStatus): String = when (status) {
        DepStatus.NotFound -> "没找到"
        is DepStatus.Broken -> "找到了但跑不起来"
        is DepStatus.TooOld -> "版本过低"
        else -> "状态未知"
    }

    private fun appendLine(lines: MutableList<String>, text: String) {
        lines.add(text)
        // npm install 能刷几千行；只留最后这些，别把整页撑爆
        while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    companion object {
        const val MAX_LOG_LINES = 500

        fun getInstance(project: Project): RuntimeDepsService =
            project.getService(RuntimeDepsService::class.java)
    }
}
