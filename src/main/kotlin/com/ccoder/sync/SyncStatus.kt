package com.ccoder.sync

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/** 同步现在处于哪种状态。显示口径见 `SyncTexts`（界面层），这里只管事实。 */
internal enum class SyncRun {
    /** 没配置（或者被关掉了）—— **不配置就不开启**。 */
    DISABLED,

    /** 被另一个活着的 IDE 窗口占着同一对目录。 */
    OCCUPIED,

    /** 在跑。 */
    RUNNING,

    /** 在跑，但上一轮有失败项。 */
    FAILED,

    /** 配了但起不来（目录不存在之类），或者已经停了。 */
    STOPPED,
}

/**
 * 一次状态快照。
 *
 * 刻意做成**不可变的一整份**而不是一堆零散字段：界面（状态栏组件、设置页）拿到的
 * 永远是自洽的一份，不会看到"计数已经更新、时刻还是上一轮的"这种半新半旧。
 */
internal data class SyncSnapshot(
    val run: SyncRun = SyncRun.DISABLED,
    /** 配置缺哪一项（`run` 是 DISABLED / STOPPED 时有意义）。 */
    val problem: ConfigProblem? = ConfigProblem.DISABLED,
    /** 占着的那一方是哪个项目（`run` 是 OCCUPIED 时有意义）。 */
    val occupiedBy: String? = null,

    /** 上一轮跑完的时刻与用时。 */
    val lastRoundAtMs: Long? = null,
    val lastCostMs: Long? = null,

    val copied: Int = 0,
    val deleted: Int = 0,
    val pruned: Int = 0,
    val unchanged: Int = 0,
    val plannedFiles: Int = 0,
    val plannedDeletes: Int = 0,

    /**
     * 这一轮**真的动过**的那几条相对路径（复制 / 删除各一份，[NAME_LIMIT] 条封顶）。
     *
     * 只给同步气泡用（它列三行）；设置页与状态栏读的是上面那些计数。
     * **有界是硬要求**：首次全量核对可能动一万多个文件，快照里塞全量就是每次发布都要
     * 拷一份巨大的列表 —— 而气泡最多也就显示三行（"还有 N 个"那个数从计数里算）。
     */
    val copiedNames: List<String> = emptyList(),
    val deletedNames: List<String> = emptyList(),

    /** 上一轮的失败项（要能上屏，不能只留"失败了"三个字）。 */
    val failed: List<Failed> = emptyList(),

    /** 本地已删却**没删**目标端的（带原因，`[提醒]` 段）。 */
    val kept: List<KeptItem> = emptyList(),

    /** 上一轮是不是"首次全量核对"（要提示"可能要一两分钟"）。 */
    val firstRun: Boolean = false,

    val watchedDirs: Int = 0,
    val hints: Int = 0,

    /** 最近若干条日志（有界）。"我改的东西怎么没同步"的答案在里面。 */
    val log: List<String> = emptyList(),
) {
    companion object {
        /**
         * [copiedNames] / [deletedNames] 各留几条。
         *
         * 取 3 是因为**同步气泡只列三行**（`docs/design/sync-bubble.html` 甲，用户选定）；
         * 两边各留 3 条，气泡里"复制在前、删除补位"最多也就凑满三行。
         * 哪天气泡要列更多，改这里与 `SYNC_BUBBLE_ROWS` 一处即可。
         */
        const val NAME_LIMIT = 3
    }
}

/**
 * 同步状态的发布点。项目级。
 *
 * 为什么不直接让界面去问 [SyncService]：同步跑在**另一条线程**上，而界面（状态栏组件、
 * 设置页）随时可能被创建和销毁 —— 让它们订阅一份快照，比让它们各自去摸引擎的状态干净得多。
 * 这一对（发布服务 + 订阅它的控件）是仓库里既有的做法，见 `PendingPermissionCount` 与
 * `McpStatus`。
 *
 * **`set` 必须在 EDT 上调**（订阅方直接拿它刷控件，不自己回 EDT —— 同 `McpStatus` 的契约）。
 * 调用方是 [SyncService]，它在工作线程上跑，所以自己 `invokeLater`。
 */
@Service(Service.Level.PROJECT)
class SyncStatus {

    internal var snapshot: SyncSnapshot = SyncSnapshot()
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    internal fun set(next: SyncSnapshot) {
        snapshot = next
        notifyListeners()
    }

    internal fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    internal fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        // 先快照再遍历：监听器可能在回调里退订（同 PendingPermissionCount 那条规矩）
        for (l in listeners.toList()) {
            runCatching { l() }
        }
    }

    companion object {
        fun getInstance(project: Project): SyncStatus = project.getService(SyncStatus::class.java)
    }
}
