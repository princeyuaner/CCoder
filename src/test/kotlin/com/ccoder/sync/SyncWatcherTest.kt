package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 看盘（2026-09-24）。**真文件系统 + 真 `WatchService`** —— 这一层没有可注入的抽象，
 * 而它要回答的问题（"事件到底会不会来"）只有真的等一次才知道。
 *
 * 所以用例都是"做一件事 → 等提示"，等不到就算红（给 10 秒，正常是毫秒级）。
 * 负向的那条（垃圾目录里的改动不给提示）只能靠等一小会儿再看有没有 —— 值得，
 * 因为"注册时就用过滤规则剪枝"正是这个类存在的理由（`node_modules` 一棵树能吃掉
 * 几千个内核句柄，见类头注）。
 *
 * 这些用例**不测"同步对不对"**：看盘只是提示，丢一个事件最坏是滞后一次巡检。
 * 同步的正确性在 `SyncEngineTest` 与 `SyncPlanTest` 里。
 */
class SyncWatcherTest {

    @TempDir
    lateinit var root: Path

    /** 收提示的桶。 */
    private class Hints {
        val seen = CopyOnWriteArrayList<String>()
        val first = CountDownLatch(1)

        fun watcher(root: Path, cfg: SyncConfig = SyncConfig()) = SyncWatcher(
            root = root,
            config = { cfg },
            onHint = { rel ->
                seen += rel ?: "?"
                first.countDown()
            },
        )

        fun await(timeoutSec: Long = 10): Boolean = first.await(timeoutSec, TimeUnit.SECONDS)
    }

    private fun write(rel: String, body: String = "x"): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, body)
        return p
    }

    @Test
    @Timeout(30)
    fun `新增一个文件会给出提示`() {
        val h = Hints()
        val w = h.watcher(root)
        w.start()
        assertTrue(w.awaitReady(10_000), "注册该在 10 秒内完成")
        try {
            write("a.kt")
            assertTrue(
                h.await(),
                "写了文件却没有任何提示（已注册 ${w.watchedDirs} 个目录，给出 ${w.hintCount} 条，" +
                    "root=$root，收到 ${h.seen}）",
            )
            assertTrue(h.seen.any { it.endsWith("a.kt") }, "提示里该看得见那个文件，实际：${h.seen}")
        } finally {
            w.stop()
        }
    }

    @Test
    @Timeout(30)
    fun `改一个已存在的文件也会给出提示`() {
        Files.createDirectories(root)
        val file = write("a.kt", "一")
        val h = Hints()
        val w = h.watcher(root)
        w.start()
        assertTrue(w.awaitReady(10_000), "注册该在 10 秒内完成")
        try {
            Files.writeString(file, "二")
            assertTrue(h.await(), "改文件没提示（已注册 ${w.watchedDirs} 个目录，收到 ${h.seen}）")
        } finally {
            w.stop()
        }
    }

    @Test
    @Timeout(30)
    fun `新建目录之后再往里写文件，也有提示 —— 目录的新增要递归补注册`() {
        val h = Hints()
        val w = h.watcher(root)
        w.start()
        assertTrue(w.awaitReady(10_000), "注册该在 10 秒内完成")
        try {
            Files.createDirectories(root.resolve("sub"))
            write("sub/b.kt")
            // 断言的是"至少来了提示"而不是"b.kt 那条提示来了"：目录的 CREATE 与
            // 里面文件的写入哪个先到没有保证，而我们**不需要**它到 ——
            // 目录那一条提示已经足以叫醒一轮全树扫描，b.kt 自然会被发现。
            assertTrue(h.await(), "新建目录没提示（已注册 ${w.watchedDirs} 个目录，收到 ${h.seen}）")
            assertTrue(h.seen.isNotEmpty())
        } finally {
            w.stop()
        }
    }

    @Test
    @Timeout(30)
    fun `垃圾目录里的改动不给提示 —— 注册时就要剪枝，不然 node_modules 会吃掉几千个句柄`() {
        val h = Hints()
        val w = h.watcher(root)
        w.start()
        assertTrue(w.awaitReady(10_000), "注册该在 10 秒内完成")
        try {
            write("node_modules/pkg/index.js")
            Thread.sleep(1_500)
            assertTrue(h.seen.isEmpty(), "垃圾目录不该注册、也不该给提示，实际收到：${h.seen}")
        } finally {
            w.stop()
        }
    }

    @Test
    @Timeout(30)
    fun `被排除的目录也不给提示`() {
        val h = Hints()
        val w = h.watcher(root, SyncConfig(exclude = listOf("temp")))
        w.start()
        assertTrue(w.awaitReady(10_000), "注册该在 10 秒内完成")
        try {
            write("temp/x.txt")
            Thread.sleep(1_500)
            assertTrue(h.seen.isEmpty(), "排除项与扫描共用同一份规则，实际收到：${h.seen}")
        } finally {
            w.stop()
        }
    }

    @Test
    @Timeout(30)
    fun `stop 之后不再有提示`() {
        val h = Hints()
        val w = h.watcher(root)
        w.start()
        assertTrue(w.awaitReady(10_000), "注册该在 10 秒内完成")
        write("a.kt")
        assertTrue(h.await(), "先确认它本来是工作的（已注册 ${w.watchedDirs} 个目录，收到 ${h.seen}）")

        w.stop()
        Thread.sleep(300)
        val seen = h.seen.size
        write("b.kt")
        Thread.sleep(1_500)
        assertEquals(seen, h.seen.size, "stop 之后不该再有提示")
    }
}
