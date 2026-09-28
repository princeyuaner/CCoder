package com.ccoder.sync

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Path

private val LOG = Logger.getInstance("com.ccoder.sync.SyncService")

/**
 * 目录同步的生命周期拥有者。项目级服务，项目关掉就停。
 *
 * 它自己几乎不做判断 —— 判断都在能单测的地方（[syncStartDecision] / [needsRestart] /
 * [configProblem]），这里只负责**把东西按正确的顺序接起来**，以及"什么时候不该跑"。
 *
 * ## 它是怎么被启动的
 *
 * 注解式服务的实例化是**惰性**的，所以得有人先取它一次。取它的是状态栏组件
 * （`SyncStatusBar`，项目打开时平台就会创建它）—— 这也是仓库里既有的做法，
 * `PendingPermissionCount` 的显示就依赖同一件事。
 *
 * > 待办：换成一个**明确的 project-open 钩子**（`postStartupActivity` 之类）会更稳，
 * > 但那属于启动路径，得跑 `runIde` 在真 IDE 里验一次（仓库的记忆里记着这条规矩）。
 * > 现在这条路是可用的，只是"靠状态栏组件顺带启动"这件事需要写在注释里。
 *
 * ## 顺序上有一处不能换
 *
 * **先等看盘就绪，再启动调度器。** 调度器的第一轮是基线轮（全树扫描）；它跑在"武装窗口"
 * 之后的话，启动时发生的改动由那一轮全树扫描兜住，不必指望事件 —— 而那个窗口里的事件是
 * 永远收不到的（见 [SyncWatcher.awaitReady]）。
 *
 * ## 停止
 *
 * **等在飞的那一轮跑完**（[SyncScheduler.stopAndJoin]）：复制到一半被掐掉会在目标端留个
 * 半截文件，而它不在基线里，于是要等到下一轮才会被覆盖回来。
 */
@Service(Service.Level.PROJECT)
class SyncService(private val project: Project) : Disposable {

    private class Running(
        val claimPath: Path,
        val scheduler: SyncScheduler,
        val watcher: SyncWatcher,
    )

    private var running: Running? = null

    /** 上一次**已生效**（或已判定不生效）的配置，用来判断要不要重启。 */
    private var applied: SyncConfig? = null

    private val recentLog = ArrayDeque<String>()

    private var debounce: javax.swing.Timer? = null

    /**
     * "被别的窗口占着"时的重试定时器。
     *
     * 没有它就有个说不通的死角：**占着的那一方关掉之后，我们永远发现不了** ——
     * 认领只在 `reconcile()` 里争一次，而那时不会再有任何东西来叫我们
     * （用户得去设置里随便动一下）。表现是状态栏一直写着"另一窗口"，而那边其实早关了。
     * 一分钟问一次，代价可以忽略。
     */
    private var occupancyRetry: javax.swing.Timer? = null

    private val settingsListener: () -> Unit = { scheduleReconcile() }

    init {
        SyncSettings.getInstance(project).addListener(settingsListener)
        scheduleReconcile()
    }

    // ---------------------------------------------------------------- 触发

    /**
     * 攒一下再动手。
     *
     * 设置页是**改动即写**的（没有保存按钮），改一个数字就是一次通知；不防抖的话，
     * 在"静默期"里敲三个字符会触发三次重建 —— 而在映射网络盘上重建一次看盘是 3.4 秒
     * （探针实测 1391 个目录）。
     */
    private fun scheduleReconcile() {
        ApplicationManager.getApplication()?.invokeLater({
            if (project.isDisposed) return@invokeLater
            val t = debounce ?: javax.swing.Timer(RECONCILE_DEBOUNCE_MS) { reconcileOnPooled() }
                .apply {
                    isRepeats = false
                    debounce = this
                }
            t.restart()
        }, ModalityState.any())
    }

    private fun reconcileOnPooled() {
        val app = ApplicationManager.getApplication()
        if (app == null) reconcile() else app.executeOnPooledThread { reconcile() }
    }

    /** 配置变了（设置页改动即写）或者用户点了什么 —— 走防抖那条路。 */
    internal fun onConfigChanged() = scheduleReconcile()

    // ---------------------------------------------------------------- 判定与接线

    /** 在**后台线程**上跑：这里要碰盘（写认领、注册看盘），不能在 EDT 上做。 */
    private fun reconcile() {
        if (project.isDisposed) return
        val settings = SyncSettings.getInstanceOrNull(project) ?: return
        val cfg = settings.config

        // 时长与镜像删除的改动**当场生效**（调度器与引擎每轮现读），不必重建看盘
        if (running != null && !needsRestart(applied, cfg)) {
            applied = cfg
            return
        }

        // 先停旧的（顺带释放旧的认领），再按新配置从头来过
        stopRunning()
        applied = cfg

        configProblem(cfg)?.let { problem ->
            publishSnapshot(
                SyncSnapshot(
                    run = if (problem == ConfigProblem.DISABLED) SyncRun.DISABLED else SyncRun.STOPPED,
                    problem = problem,
                    log = logSnapshot(),
                ),
            )
            return
        }

        val claimPath = SyncPaths.claim(cfg.src, cfg.dst)
        val claim = SyncClaim.tryAcquire(claimPath, project.basePath ?: project.name)
        when (val decision = syncStartDecision(cfg, claim)) {
            is SyncStart.Cannot -> publishSnapshot(
                SyncSnapshot(run = SyncRun.STOPPED, problem = decision.reason, log = logSnapshot()),
            )

            is SyncStart.Occupied -> {
                remember(
                    "[占用] 这一对目录已被另一个 IDE 窗口同步（${decision.by}）—— " +
                        "本窗口不启动（两个窗口同时同步会互相删掉对方刚写的文件）",
                )
                publishSnapshot(
                    SyncSnapshot(run = SyncRun.OCCUPIED, occupiedBy = decision.by, log = logSnapshot()),
                )
                scheduleOccupancyRetry()
            }

            SyncStart.Start -> {
                cancelOccupancyRetry()
                startEverything(cfg, claimPath)
            }
        }
    }

    /** 一分钟后再来看一眼那个占着窗口还在不在（见 [occupancyRetry]）。 */
    private fun scheduleOccupancyRetry() {
        if (project.isDisposed) return
        val existing = occupancyRetry
        val t = existing ?: javax.swing.Timer(OCCUPANCY_RETRY_MS) { reconcileOnPooled() }.apply {
            isRepeats = false
            occupancyRetry = this
        }
        t.restart()
    }

    private fun cancelOccupancyRetry() {
        occupancyRetry?.stop()
        occupancyRetry = null
    }

    private fun startEverything(cfg: SyncConfig, claimPath: Path) {
        val settings = SyncSettings.getInstanceOrNull(project) ?: return
        val srcRoot = runCatching { Path.of(cfg.src) }.getOrNull()
        val dstRoot = runCatching { Path.of(cfg.dst) }.getOrNull()
        if (srcRoot == null || dstRoot == null) {
            publishSnapshot(SyncSnapshot(run = SyncRun.STOPPED, problem = ConfigProblem.NO_SRC, log = logSnapshot()))
            return
        }

        val target = LocalTarget(dstRoot) { settings.config }
        val engine = SyncEngine(
            srcRoot = srcRoot,
            target = target,
            cfg = { settings.config },
            baselinePath = SyncPaths.baseline(cfg.src, cfg.dst),
            log = ::remember,
        )
        val scheduler = SyncScheduler(
            config = { settings.config },
            runRound = { full ->
                val report = engine.runRound(full)
                publishRound(report)
                report.ok
            },
            log = ::remember,
        )
        val watcher = SyncWatcher(
            root = srcRoot,
            config = { settings.config },
            onHint = { rel -> scheduler.poke(rel) },
            log = ::remember,
        )

        watcher.start()
        if (!watcher.awaitReady(WATCHER_READY_MS)) {
            remember("[监听] 注册超时（${WATCHER_READY_MS / 1000} 秒）—— 本次只靠巡检发现改动")
        }
        running = Running(claimPath, scheduler, watcher)
        scheduler.start()   // 顺序不能换：见类头注
        publishSnapshot(
            SyncSnapshot(run = SyncRun.RUNNING, watchedDirs = watcher.watchedDirs, log = logSnapshot()),
        )
    }

    private fun stopRunning() {
        val r = running ?: return
        running = null
        r.watcher.stop()
        if (!r.scheduler.stopAndJoin(STOP_JOIN_MS)) {
            LOG.warn("CCoder 同步：worker 没在 ${STOP_JOIN_MS} ms 内结束，先放它走（它只读也不写盘了）")
        }
        SyncClaim.release(r.claimPath)
    }

    override fun dispose() {
        SyncSettings.getInstanceOrNull(project)?.removeListener(settingsListener)
        debounce?.stop()
        debounce = null
        cancelOccupancyRetry()
        stopRunning()
    }

    // ---------------------------------------------------------------- 状态与日志

    /**
     * 记一行日志。
     *
     * 三个地方会调它：看盘线程、调度线程、以及启动路径 —— 所以它必须自己保证线程安全
     * （有界环形缓冲 + 锁）。**同时写一份进 IDE 日志**：真机上出问题时，用户能给的
     * 往往只有 idea.log。
     */
    private fun remember(line: String) {
        LOG.info("CCoder 同步：$line")
        synchronized(recentLog) {
            recentLog.addLast(line)
            while (recentLog.size > LOG_KEEP) recentLog.removeFirst()
        }
    }

    private fun logSnapshot(): List<String> = synchronized(recentLog) { recentLog.toList() }

    private fun publishRound(report: RoundReport) {
        val r = running
        publishSnapshot(
            SyncSnapshot(
                run = if (report.ok) SyncRun.RUNNING else SyncRun.FAILED,
                problem = null,
                lastRoundAtMs = System.currentTimeMillis(),
                lastCostMs = report.costMs,
                copied = report.copied,
                deleted = report.deleted,
                // 气泡要说的"哪几个文件"就从这儿来 —— 取的是**做成的**那些
                // （`copiedRel` / `deletedRel`，不是计划）。**有界**：全量轮可能上万条，
                // 而气泡只列三行（`SyncSnapshot.NAME_LIMIT`）
                copiedNames = report.copiedRel.take(SyncSnapshot.NAME_LIMIT),
                deletedNames = report.deletedRel.take(SyncSnapshot.NAME_LIMIT),
                pruned = report.pruned.size,
                unchanged = report.unchanged,
                plannedFiles = report.plannedCopies,
                plannedDeletes = report.plannedDeletes,
                failed = report.failedCopies + report.failedDeletes,
                kept = report.kept,
                firstRun = report.firstFullReconcile,
                watchedDirs = r?.watcher?.watchedDirs ?: 0,
                hints = r?.watcher?.hintCount ?: 0,
                log = logSnapshot(),
            ),
        )
    }

    /**
     * 回 EDT 再发布 —— [SyncStatus.set] 的契约是"必须在 EDT 上调"，订阅方直接刷控件。
     *
     * `ModalityState.any()` **必须显式给**：不带它的话，模态对话框开着时这些任务根本不执行
     * （`RuntimeDepsService` 里记着这个坑）。
     */
    private fun publishSnapshot(snapshot: SyncSnapshot) {
        val app = ApplicationManager.getApplication() ?: return
        app.invokeLater({
            if (!project.isDisposed) SyncStatus.getInstance(project).set(snapshot)
        }, ModalityState.any())
    }

    companion object {
        fun getInstance(project: Project): SyncService = project.getService(SyncService::class.java)

        /** 防抖：设置页改动即写，敲一个字符一次通知 —— 攒 600 毫秒再动手。 */
        private const val RECONCILE_DEBOUNCE_MS = 600

        /** 等看盘注册完成的上限（网络盘上 1391 个目录实测 3.4 秒，给足余量）。 */
        private const val WATCHER_READY_MS = 30_000L

        /** 停止时等在飞那一轮的上限。 */
        private const val STOP_JOIN_MS = 30_000L

        /** 界面能看到的日志行数上限。 */
        private const val LOG_KEEP = 60

        /** "被另一个窗口占着"时多久回来看一眼（那个窗口关了就该轮到我们）。 */
        private const val OCCUPANCY_RETRY_MS = 60_000
    }
}
