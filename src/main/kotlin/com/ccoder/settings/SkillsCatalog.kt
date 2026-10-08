package com.ccoder.settings

import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.NodeCheck
import com.ccoder.sidecar.RuntimeDep
import com.ccoder.sidecar.SidecarLocator
import com.ccoder.sidecar.SkillsOnceReply
import com.ccoder.sidecar.parseSkillsOnce
import com.ccoder.text.CcoderText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 技能清单的**当前一份**，加上改一条之后的核对结果。
 *
 * ## 为什么是服务
 *
 * 同 `McpStatus` / `RuntimeDepsService`：问一次 CLI 要 3～5 秒（真起进程），
 * 关掉设置框再打开不该重跑；"正在问"这个中间态也得活得比那一页久。
 *
 * ## 为什么写完要**再问一遍**
 *
 * 写手**什么都不验**（探针 Q5）：喂它一个不是设置键的名字（比如显示名
 * "Debug Issue"），它照写、还回 `changed:true`，而那条覆盖永远不会命中。
 * 所以 [setState] 是**写 → 重新枚举 → 核对那条变了没有**：没变就如实报出来。
 * 比"开关一拨就假装成功"贵三秒，但它是真的。
 *
 * ## 线程规矩（照 `RuntimeDepsService`）
 *
 * 状态只在 EDT 上改；服务自己不 import `ApplicationManager`（平台构造器那一次除外）。
 */
@Service(Service.Level.PROJECT)
class SkillsCatalog(
    private val baseDir: () -> Path?,
    private val home: () -> Path,
    private val enumerate: (Path?) -> SkillsOnceReply,
    private val edit: (Path?, String, SkillState) -> ProcessOutcome,
    private val pluginCommand: (Path?, String, String) -> ProcessOutcome,
    private val post: (() -> Unit) -> Unit,
    private val runAsync: (() -> Unit) -> Unit,
) {

    /**
     * 平台靠这个构造器实例化（能拿到 [Project]）。
     *
     * 注入那几个**不能**写成主构造器的默认参数 —— 平台反射找不到那种合成构造器，
     * 服务会静默注册失败（`RuntimeDepsService` 的注释里记着这条）。
     */
    constructor(project: Project) : this(
        baseDir = { project.basePath?.let { Path.of(it) } },
        home = { Path.of(System.getProperty("user.home") ?: ".") },
        enumerate = { base -> runEnumeration(project, base) },
        edit = { base, key, state -> runEdit(project, base, key, state) },
        pluginCommand = { base, verb, name -> runPluginCommand(project, base, verb, name) },
        post = { task ->
            ApplicationManager.getApplication()?.invokeLater(task, ModalityState.any()) ?: task()
        },
        runAsync = { task -> Thread(task, "ccoder-skills").apply { isDaemon = true }.start() },
    )

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 失效闸：每次问一遍加一。在飞的那次回来时对不上号就丢掉 ——
     * 用户连点两下「刷新」，先发的那次不该盖掉后发的那次。
     */
    private val epoch = AtomicInteger(0)

    // ---- 状态都用平字段存，`state` 现算 ----
    //
    // 用密封类包着的话，"刷新时把上一次的提示语弄丢"这类小事会天天出现：
    // Loading 那一步没有地方放 notice，而提示语是给用户看"刚才那次改有没有效果"的。

    private var loading = false
    private var failure: String? = null
    private var rows: List<SkillRow> = emptyList()
    private var notice: SkillsNotice? = null

    /** 问过一轮没有。没问过时列表是空的 ≠ 一条技能都没有（同 `McpStatus.known`）。 */
    var known: Boolean = false
        private set

    val state: SkillsState
        get() = when {
            failure != null -> SkillsState.Failed(failure!!)
            loading && !known -> SkillsState.Loading(first = true)
            loading -> SkillsState.Loading(first = false)
            else -> SkillsState.Ready(rows, notice, known)
        }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 问一遍 CLI，顺便重读设置与磁盘。正在问就不重入（连点两下只算一次）。 */
    fun refresh() {
        if (loading) return
        loading = true
        failure = null
        val mine = epoch.incrementAndGet()
        val base = baseDir()
        update()
        runAsync {
            val reply = runCatching { enumerate(base) }
                .getOrElse { SkillsOnceReply.Failed(it.message ?: it.toString()) }
            post {
                if (epoch.get() != mine) return@post
                loading = false
                when (reply) {
                    is SkillsOnceReply.Failed -> failure = reply.reason
                    is SkillsOnceReply.Ok -> {
                        rows = rowsOf(base, reply)
                        known = true
                        notice = null     // 普通刷新不带提示语：上一次那件事已经过去了
                    }
                }
                update()
            }
        }
    }

    /**
     * 改一条技能的状态：**写、再问、核对**。
     *
     * [name] 只给提示语用；写盘用的是 [key]（设置键，见 `skillRowsOf`）。
     */
    fun setState(key: String, name: String, state: SkillState) {
        val base = baseDir()
        if (base == null) {
            notice = SkillsNotice.WriteFailed(name, CcoderText.text("settings.skills.error.noBaseDir"))
            update()
            return
        }
        loading = true
        notice = null
        update()
        runAsync {
            val outcome = runCatching { edit(base, key, state) }
                .getOrElse { ProcessOutcome(-1, "", it.message ?: it.toString()) }
            if (!skillEditSucceeded(outcome.stdout)) {
                post {
                    loading = false
                    notice = SkillsNotice.WriteFailed(name, firstLine(outcome))
                    update()
                }
                return@runAsync
            }
            val mine = epoch.incrementAndGet()
            // 写成功了还不够 —— 立刻重新枚举，看那条到底变了没有
            val reply = runCatching { enumerate(base) }
                .getOrElse { SkillsOnceReply.Failed(it.message ?: it.toString()) }
            post {
                if (epoch.get() != mine) return@post
                loading = false
                when (reply) {
                    is SkillsOnceReply.Failed -> failure = reply.reason
                    is SkillsOnceReply.Ok -> {
                        val fresh = rowsOf(base, reply)
                        rows = fresh
                        known = true
                        val changed = fresh.firstOrNull { it.key == key }?.state == state
                        notice = if (changed) SkillsNotice.Written(name) else SkillsNotice.NoEffect(name)
                    }
                }
                update()
            }
        }
    }

    /**
     * 插件那两条（`disable` / `update`）。作用于**整个插件**，且重启会话才生效。
     *
     * 跑完照样重新问一遍：插件的技能会跟着来去，而清单得说真话。
     */
    fun runPlugin(verb: String, plugin: String, label: String) {
        val base = baseDir()
        loading = true
        notice = null
        update()
        runAsync {
            val outcome = runCatching { pluginCommand(base, verb, plugin) }
                .getOrElse { ProcessOutcome(-1, "", it.message ?: it.toString()) }
            post {
                loading = false
                notice = SkillsNotice.PluginAction(label, plugin, outcome.exit == 0, if (outcome.exit == 0) null else firstLine(outcome))
                update()
            }
            refresh()
        }
    }

    // ---- 内部 ----

    private fun update() {
        listeners.toList().forEach { it() }
    }

    private fun rowsOf(base: Path?, reply: SkillsOnceReply.Ok): List<SkillRow> {
        val local = readOverrides(base?.let { projectLocalSettingsPath(it) })
        val project = readOverrides(base?.let { projectSettingsPath(it) })
        val user = readOverrides(userSettingsPath(home()))
        return skillRowsOf(
            commands = reply.commands,
            usage = reply.usage,
            overrides = mergedSkillOverrides(local, project, user),
            scanned = scanSkillDirs(base, home()),
        )
    }

    private fun readOverrides(path: Path?): Map<String, String> =
        if (path != null && Files.isRegularFile(path)) skillOverridesOf(ProjectJson.read(path)) else emptyMap()

    companion object {
        fun getInstance(project: Project): SkillsCatalog = project.getService(SkillsCatalog::class.java)
    }
}

/**
 * 清单的三种状态 —— 页照着画。
 *
 * [Loading] 带 [first]：第一次问（列表还空着）与刷新（列表还在、只是有点旧）
 * 该说两句不同的话（后者不该把已经画出来的行清掉）。
 *
 * [Ready.known] 与"列表是空的"是两件事：前者是"问过了"，后者是"真的没有技能"。
 */
sealed interface SkillsState {
    data class Loading(val first: Boolean) : SkillsState

    data class Ready(
        val rows: List<SkillRow>,
        val notice: SkillsNotice? = null,
        val known: Boolean = true,
    ) : SkillsState

    data class Failed(val reason: String) : SkillsState
}

/** 改完一条之后要告诉用户的那句话（文案在页面上拼，这里只带事实）。 */
sealed interface SkillsNotice {
    /** 写进去了，而且重新枚举之后确认它生效了。 */
    data class Written(val name: String) : SkillsNotice

    /** 写进去了、回执也说改了，但**清单里没变** —— 多半不是它的设置键。 */
    data class NoEffect(val name: String) : SkillsNotice

    data class WriteFailed(val name: String, val reason: String) : SkillsNotice

    data class PluginAction(val label: String, val plugin: String, val ok: Boolean, val reason: String?) : SkillsNotice
}

/** 一次外部命令的结局。[exit] 为 -1 表示根本没起来（路径不对、超时……）。 */
data class ProcessOutcome(val exit: Int, val stdout: String, val stderr: String)

/** 提示语里只放一行：CLI 的报错有时是整段堆栈。 */
internal fun firstLine(outcome: ProcessOutcome): String {
    val text = (outcome.stderr.ifBlank { outcome.stdout }).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    return text.ifBlank { CcoderText.text("settings.skills.error.exitCode", outcome.exit) }
}

// ---------------------------------------------------------------------------
// 生产实现：两条进程调用
// ---------------------------------------------------------------------------

/**
 * 起一次性枚举（`sidecar/skills-once.js`）。
 *
 * 用**会话自己那套**发现路径（`NodeCheck` + `SidecarLocator`），不另找一份 ——
 * 两处各找各的迟早出现"聊天能跑、这一页说找不到 node"。
 */
private fun runEnumeration(project: Project, base: Path?): SkillsOnceReply {
    val cwd = base ?: return SkillsOnceReply.Failed(CcoderText.text("settings.skills.error.noBaseDir"))
    val node = NodeCheck.resolve() ?: "node"
    val sidecarDir = runCatching { SidecarLocator.resolve(project.basePath) }
        .getOrElse { return SkillsOnceReply.Failed(it.message ?: it.toString()) }

    val args = buildList {
        add(node)
        add(sidecarDir.resolve("skills-once.js").toString())
        add("--cwd")
        add(cwd.toString())
        claudePathOf(project)?.takeIf { it.isNotBlank() }?.let {
            add("--claude")
            add(it)
        }
    }

    val outcome = runSync(args, workDir = sidecarDir, stdin = null, timeoutMs = ENUMERATE_TIMEOUT_MS)
        ?: return SkillsOnceReply.Failed(CcoderText.text("settings.skills.error.timeout"))
    return parseSkillsOnce(outcome.stdout)
}

/** 走 CLI 自己的写手改一条。 */
private fun runEdit(project: Project, base: Path?, key: String, state: SkillState): ProcessOutcome {
    val claude = claudePathOf(project)
        ?: return ProcessOutcome(-1, "", CcoderText.text("settings.skills.error.noClaude"))
    val cwd = base ?: return ProcessOutcome(-1, "", CcoderText.text("settings.skills.error.noBaseDir"))
    return runSync(skillEditArgs(claude), workDir = cwd, stdin = skillEditInput(key, state), timeoutMs = EDIT_TIMEOUT_MS)
        ?: ProcessOutcome(-1, "", CcoderText.text("settings.skills.error.timeout"))
}

private fun runPluginCommand(project: Project, base: Path?, verb: String, name: String): ProcessOutcome {
    val claude = claudePathOf(project)
        ?: return ProcessOutcome(-1, "", CcoderText.text("settings.skills.error.noClaude"))
    return runSync(listOf(claude, "plugin", verb, name), workDir = base, stdin = null, timeoutMs = PLUGIN_TIMEOUT_MS)
        ?: ProcessOutcome(-1, "", CcoderText.text("settings.skills.error.timeout"))
}

/**
 * claude 的路径取自依赖那一页的检测结果 —— **那一份是三级解析过的**
 * （显式路径 → PATH → 已知安装目录），自己再解析一次就是第二套规矩。
 * 还没查过时给 null，调用方按"找不到 claude"报出来。
 */
private fun claudePathOf(project: Project): String? =
    (RuntimeDepsService.getInstance(project).statusOf(RuntimeDep.CLAUDE) as? DepStatus.Ok)?.path

/**
 * 同步跑一条命令，两个流各起一个线程读。
 *
 * **必须另起线程读**：不读的话，子进程写满管道缓冲区就会停在那儿等，
 * 而我们在 `waitFor` 里等它 —— 经典的互相等死。今天输出都只有几 KB，
 * 但这条规矩不该由"现在输出小"来保证。
 */
private fun runSync(
    args: List<String>,
    workDir: Path?,
    stdin: String?,
    timeoutMs: Long,
): ProcessOutcome? {
    val process = runCatching {
        ProcessBuilder(args).apply { if (workDir != null) directory(workDir.toFile()) }.start()
    }.getOrNull() ?: return null

    val out = StringBuilder()
    val err = StringBuilder()
    val outThread = Thread { runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
    val errThread = Thread { runCatching { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } } }
    outThread.isDaemon = true
    errThread.isDaemon = true
    outThread.start()
    errThread.start()

    runCatching {
        if (stdin != null) process.outputStream.write(stdin.toByteArray(Charsets.UTF_8))
        process.outputStream.close()
    }

    val finished = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    if (!finished) {
        runCatching { process.destroyForcibly() }
        return null
    }
    outThread.join(1000)
    errThread.join(1000)
    return ProcessOutcome(process.exitValue(), out.toString(), err.toString())
}

/** 枚举要等 CLI 起来，实测 3～5 秒；60 秒是"这台机器在忙"的余量。 */
private const val ENUMERATE_TIMEOUT_MS = 60_000L

/** 写手只是改一行 JSON，但它可能正被别的进程占着文件。 */
private const val EDIT_TIMEOUT_MS = 20_000L

/** 插件的更新要走网络（`claude plugin update`），给宽一点。 */
private const val PLUGIN_TIMEOUT_MS = 120_000L
