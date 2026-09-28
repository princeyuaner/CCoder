package com.ccoder.sync

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val LOG = Logger.getInstance("com.ccoder.sync.SyncWatcher")

/**
 * 看盘：把文件系统事件折算成"该同步了"的**提示**（[onHint]），不做任何同步。
 *
 * ## 它只是提示，所以可以简单
 *
 * 调度器那边已经定了"事件只当提示、真正同步什么由扫描决定"（见 [SyncScheduler] 头注）。
 * 于是这一层的任何缺陷 —— 事件丢了、合并了、重复了、监听整个哑掉 —— **都不影响正确性**，
 * 最坏是滞后一个巡检周期。这决定了它不必去做"可恢复的事件队列"那种复杂事。
 *
 * ## 绝对不要对 `event.context()` 做 Path 运算（2026-09-24 踩到，线程就是这么死的）
 *
 * `WatchEvent.context()` 返回的 [Path] **可能与我们自己造的 Path 不是同一个类**：
 * 平台（测试框架、以及 IDE 自己的类加载器）会把 `sun.nio.fs.WindowsPath` 加载两次，
 * 于是 `dir.resolve(context)` / `root.relativize(context)` 抛
 * `java.nio.file.ProviderMismatchException`。而它是在**看盘线程里**抛的 ——
 * 线程直接死掉，监听永久哑掉，日志里一个字都没有。
 *
 * 所以对事件路径**只取 `toString()`**，相对路径靠"注册时记下来的目录 → 相对路径"这张表
 * 用字符串拼出来（[relByDir]）。顺带也省掉了每个事件一次 `relativize`。
 *
 * ## 为什么要在注册时就剪枝
 *
 * **Java 的 `WatchService` 不支持递归**：一个目录一个句柄。而参考实现用的 Python
 * `watchdog` 在 Windows 上是一个句柄看整棵树（`ReadDirectoryChangesW` 的
 * `bWatchSubtree = TRUE`）。所以这边必须自己递归注册，而 `node_modules` 那种一棵树
 * 就能吃掉几千个句柄 —— 于是**过滤规则在注册时就要用上**，不能等事件来了再筛。
 *
 * 过滤用的是与扫描**同一个** [isJunkEntry] / [inScopeDir] / [isExcluded]：两份规则
 * 各自演化就会出现"扫描认为该同步、监听却把事件挡掉"，症状是那个文件要等一次巡检
 * （默认 60 秒）才动，而且看不出为什么。参考实现的注释专门警告过这一点。
 * 超过 [MAX_WATCHED_DIRS] 时记一条日志（**不静默**），其余靠巡检兜底。
 *
 * ## 目录的新增要递归补注册
 *
 * 新建一个目录并在里面写文件时，事件可能比我们的注册先到。所以收到目录的 CREATE 就
 * 递归注册它整棵子树 —— 补注册之前写进去的文件不会漏，因为目录那一条提示已经足以
 * 叫醒一轮全树扫描。
 */
internal class SyncWatcher(
    private val root: Path,
    private val config: () -> SyncConfig,
    /** 有东西被触碰。参数是相对路径（认不出来时给 null），**只用于日志与提示**。 */
    private val onHint: (String?) -> Unit,
    private val log: (String) -> Unit = {},
) {

    private val watchService: WatchService = FileSystems.getDefault().newWatchService()

    /**
     * 已注册的目录 → 它的相对路径（`""` = 根）。**键是路径的字符串**，不是 [Path]。
     *
     * 为什么连 map 键都要退化成字符串：同一个类加载器分裂问题（见类头注）会让
     * `key.watchable()` 返回的那个 [Path] 与 `register` 时传进去的**不相等** ——
     * `WindowsPath.equals` 里那句 `instanceof` 跨类加载器就为假，于是按对象查表
     * 永远查不到，表现是"注册了 1 个目录、事件也收到了、却一条提示都没有"。
     * 字符串没有这个问题，而且 `toString()` 对任何来源的 Path 都是安全的。
     */
    private val relByDir = ConcurrentHashMap<String, String>()

    /** 真实路径去重：junction / 符号链接指回祖先时会成环，注册不完。 */
    private val seenReal = HashSet<Path>()

    private var thread: Thread? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var overflowSeen = false

    private var capLogged = false

    /** 首次注册完成时放行。递归注册一棵大树要一会儿，而这之前的事件是收不到的。 */
    private val ready = CountDownLatch(1)

    private val hints = AtomicInteger()

    /** 已注册的目录数（状态卡与日志用）。 */
    val watchedDirs: Int get() = relByDir.size

    /** 给出过多少条提示（状态卡用：它证明监听是活的）。 */
    val hintCount: Int get() = hints.get()

    /** 起那条唯一的看盘线程。只调一次。 */
    fun start() {
        if (thread != null) return
        val t = Thread({ loop() }, "ccoder-sync-watcher")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /**
     * 等首次注册完成，再等过"武装窗口"。
     *
     * **注册返回 ≠ 已经在看盘。** Windows 的 `WatchService` 是异步的：`register()` 只是
     * 把注册排给内部的轮询线程，真正发 `ReadDirectoryChangesW` 的是那一边。这个窗口里
     * 发生的改动**一个事件都收不到**，而且永远补不回来（事件没有队列，只有"当时在看吗"）。
     * 2026-09-24 的探针实测到了它 —— 最小复现里恰好 `sleep(300)` 了一下，把它盖住了，
     * 而 `SyncWatcherTest` 一上来就写文件，于是四条用例全红。
     *
     * 所以"就绪"要等到它过去。**服务必须在启动基线轮之前**调一次这个方法：
     * 那样启动时的改动由基线轮的全树扫描兜住，不必指望事件。
     *
     * 里面有一次 `sleep` —— **不要从 EDT 调**（服务在后台线程上等它）。
     */
    fun awaitReady(timeoutMs: Long): Boolean {
        val ok = ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (ok) Thread.sleep(ARM_SETTLE_MS)
        return ok
    }

    fun stop() {
        stopped = true
        ready.countDown()   // 别让等就绪的人干等到超时
        runCatching { watchService.close() }
        thread?.interrupt()
    }

    // ---------------------------------------------------------------- 主循环

    private fun loop() {
        val t0 = System.currentTimeMillis()
        registerTree(root, "")
        log("[监听] 已注册 ${relByDir.size} 个目录（用时 ${System.currentTimeMillis() - t0} ms）")
        ready.countDown()

        while (!stopped) {
            val key = try {
                watchService.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                return   // 关闭是正常路径
            } ?: continue

            val watchableKey = key.watchable()?.toString()
            val relDir = watchableKey?.let { relByDir[it] }
            if (watchableKey != null && relDir != null) {
                for (event in key.pollEvents()) {
                    if (event.kind() == OVERFLOW) {
                        // 系统丢事件了（缓冲区溢出）→ 让调度器赶紧跑一轮，全树扫描会补齐
                        if (!overflowSeen) {
                            overflowSeen = true
                            log("[监听] 事件缓冲区溢出，已请求一轮完整扫描（后续溢出不再重复记）")
                        }
                        onHint(null)
                        continue
                    }
                    // **只取字符串**：这个 Path 可能来自另一个类加载器，见类头注
                    val name = (event.context() as? Path)?.toString() ?: continue
                    handle(relDir, name)
                }
            }
            if (!key.reset()) {
                // 目录没了 → 这个 key 失效，别在表里留幽灵
                watchableKey?.let { relByDir.remove(it) }
            }
        }
    }

    private fun handle(relDir: String, name: String) {
        val rel = if (relDir.isEmpty()) name else "$relDir/$name"
        val cfg = config()

        // 用 root 拼（root 与它派生出来的 Path 同源，安全）；不要碰事件那个 Path
        val child = if (relDir.isEmpty()) root.resolve(name) else root.resolve(rel)
        val isDir = Files.isDirectory(child)

        if (isDir) {
            if (isJunkEntry(rel, cfg.junk, isDirectory = true) ||
                !inScopeDir(rel, cfg.syncRoots) ||
                isExcluded(rel, cfg.exclude)
            ) {
                return
            }
            if (Files.exists(child)) registerTree(child, rel)
        } else if (!syncable(rel, cfg)) {
            // 删掉的目录也走这一条（那时 isDirectory 已经是 false）——无害：
            // 它照样给出提示，具体删什么由对账决定
            return
        }
        hints.incrementAndGet()
        onHint(rel)
    }

    // ---------------------------------------------------------------- 注册

    /** 递归注册一棵子树（含 [dir] 自己），沿途按同一套过滤规则剪枝。 */
    private fun registerTree(dir: Path, relDir: String) {
        val stack = ArrayDeque<Pair<Path, String>>()
        stack.addLast(dir to relDir)
        while (stack.isNotEmpty()) {
            val (d, rel) = stack.removeLast()
            if (!register(d, rel)) continue
            val children = try {
                Files.newDirectoryStream(d).use { it.toList() }
            } catch (_: Exception) {
                continue
            }
            val cfg = config()
            for (c in children) {
                if (!Files.isDirectory(c)) continue
                // c 是从 d（我们自己的 Path）派生的，同源，可以安全地取名字
                val childRel = if (rel.isEmpty()) c.fileName.toString() else "$rel/${c.fileName}"
                if (isJunkEntry(childRel, cfg.junk, isDirectory = true) ||
                    !inScopeDir(childRel, cfg.syncRoots) ||
                    isExcluded(childRel, cfg.exclude)
                ) {
                    continue
                }
                stack.addLast(c to childRel)
            }
        }
    }

    private fun register(dir: Path, rel: String): Boolean {
        if (relByDir.size >= MAX_WATCHED_DIRS) {
            if (!capLogged) {
                capLogged = true
                log(
                    "[监听] 目录数超过 $MAX_WATCHED_DIRS，不再继续注册；" +
                        "**剩下的改动只能靠巡检发现**（默认每 ${config().sweepMs / 1000} 秒一次）",
                )
            }
            return false
        }
        // 真实路径去重：junction / 符号链接指回祖先时会成环，注册不完
        val real = runCatching { dir.toRealPath() }.getOrNull() ?: return false
        synchronized(seenReal) { if (!seenReal.add(real)) return false }

        val ok = runCatching { dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY) }
            .onFailure { LOG.info("CCoder 同步：注册监听失败（跳过）：$dir — ${it.message}") }
            .isSuccess
        if (!ok) return false

        relByDir[dir.toString()] = rel
        return true
    }

    private companion object {
        /** poll 的超时：也用来定期看一眼 [stopped]（poll 在 close 时会抛，那是正常路径）。 */
        const val POLL_MS = 2_000L

        /**
         * 注册目录数的上限。
         *
         * 每个注册占一个内核句柄（这是 Java `WatchService` 的形状，不是我们的选择），
         * 上万之后不值得再往上堆。超了就记一条日志、靠巡检兜底 —— **不静默**。
         */
        const val MAX_WATCHED_DIRS = 10_000

        /**
         * 武装窗口：注册返回之后还要等这么久，才敢说"在看盘了"。
         *
         * 300 毫秒是探针那个最小复现里用的值（它那样才稳），这里取同样的量级。
         * 见 [awaitReady] 关于这个窗口为什么存在、以及为什么服务必须在基线轮之前等它。
         */
        const val ARM_SETTLE_MS = 300L
    }
}
