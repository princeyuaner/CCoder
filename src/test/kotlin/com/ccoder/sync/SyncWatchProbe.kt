package com.ccoder.sync

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * `WatchService` 行为探针（2026-09-24）。设计稿 §2.3 第 1 条。
 *
 * ## 为什么需要它
 *
 * 看盘那条线的**正确性**不依赖它（事件只当提示，丢了最坏滞后一次巡检），但**响应速度**
 * 完全依赖它：监听哑掉的话，从"改了文件"到"同步过去"就变成 60 秒而不是 1 秒。
 * 所以要知道两件事：
 *
 * 1. **纯 JDK 的最小复现能不能收到事件**（不带我们那层）—— 收不到就说明是环境/JVM 的问题，
 *    而不是 [SyncWatcher] 写错了。这条先分清楚，省得对着自己的代码瞎改。
 * 2. **递归注册一棵真树的代价**：Java 的 `WatchService` 不支持递归，一个目录一个句柄。
 *    要量出"1.7 万个文件的那棵树有多少个目录、注册要多久"，才知道 [SyncWatcher] 的
 *    剪枝与上限该定在哪。
 *
 * 没有断言，也不该有 —— 这是个量尺。产物：`build/probe/sync-watch.txt`。
 */
class SyncWatchProbe {

    private val out = StringBuilder()

    /** 每个用例写自己的产物文件 —— 共用一个的话，跑得晚的那个会把前一个覆盖掉。 */
    private var artifact = "build/probe/sync-watch.txt"

    private fun emit(line: String) {
        out.appendLine(line)
        println(line)
    }

    @Test
    @Timeout(120)
    fun `纯 JDK 最小复现：WatchService 在本机能收到事件吗`() {
        emit("==================== WatchService 最小复现 ====================")
        emit("JVM ${System.getProperty("java.version")}　OS ${System.getProperty("os.name")}")

        val dir = Files.createTempDirectory("ccoder-watch-probe")
        emit("目录：$dir")
        val ws = FileSystems.getDefault().newWatchService()
        dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        Thread.sleep(300)          // 给 Windows 那边的完成端口线程一点时间就绪

        val t0 = System.nanoTime()
        Files.writeString(dir.resolve("a.txt"), "1")           // 新建
        Files.writeString(dir.resolve("a.txt"), "2")           // 修改
        Files.createDirectories(dir.resolve("sub"))            // 建目录
        Files.writeString(dir.resolve("sub/b.txt"), "x")       // 子目录里的文件（不该收到）
        Files.delete(dir.resolve("a.txt"))                     // 删除

        val got = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val k = ws.poll(200, TimeUnit.MILLISECONDS) ?: continue
            for (e in k.pollEvents()) {
                got += "${e.kind().name()} ${e.context()} @${(System.nanoTime() - t0) / 1_000_000}ms"
            }
            k.reset()
        }
        ws.close()

        emit("收到 ${got.size} 条事件：")
        got.forEach { emit("  $it") }
        emit("")
        emit("结论：${if (got.isEmpty()) "**收不到事件** —— 这是环境/JVM 层面的问题，不是我们那层" else "能收到（子目录那条收不到是正常的：WatchService 不递归）"}")
        emit("")
        writeOut()
    }

    @Test
    @Timeout(300)
    fun `递归注册一棵真树：有多少个目录、要多久、多少个会因剪枝而省掉`() {
        artifact = "build/probe/sync-watch-register.txt"
        val trees = listOf("C:\\M71\\server", "Z:\\m71\\server").map { Path.of(it) }
            .filter { Files.isDirectory(it) }
        if (trees.isEmpty()) {
            emit("两棵树都不在，跳过")
            writeOut()
            return
        }

        emit("==================== 递归注册的代价 ====================")
        val cfg = SyncConfig()
        val ws = FileSystems.getDefault().newWatchService()
        try {
            for (root in trees) {
                var all = 0
                var kept = 0
                val t0 = System.nanoTime()
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        all++
                        val rel = runCatching { root.relativize(dir).toString().replace('\\', '/') }.getOrNull() ?: ""
                        if (rel.isNotEmpty()) {
                            if (isJunkEntry(rel, cfg.junk, isDirectory = true) ||
                                !inScopeDir(rel, cfg.syncRoots) ||
                                isExcluded(rel, cfg.exclude)
                            ) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                        }
                        kept++
                        runCatching { dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
                })
                val ms = (System.nanoTime() - t0) / 1_000_000
                emit(
                    "$root：目录 $all 个，剪枝后要注册 $kept 个（省掉 ${all - kept} 个），" +
                        "注册耗时 $ms ms　每个目录一个内核句柄（Java WatchService 不支持递归）",
                )
            }
        } finally {
            ws.close()
        }
        emit("")
        writeOut()
    }

    private fun writeOut() {
        File("build/probe").mkdirs()
        File(artifact).writeText(out.toString(), Charsets.UTF_8)
    }
}
