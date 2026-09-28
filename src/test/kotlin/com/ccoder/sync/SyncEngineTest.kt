package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * 引擎整轮的编排（2026-09-24）—— 对着**内存假目标端**跑，一行真磁盘都不用碰。
 *
 * 这是"引擎只依赖 [SyncTarget] 接口"这个结构换来的东西：要测"复制失败会怎样"，
 * 不必真的去制造一个坏文件或者占住一个句柄 —— 让假目标端在那个名字上抛一次就行。
 * 源目录是真的（`@TempDir`），因为 [scanTree] 要走真文件系统。
 *
 * 钉的是三件"安静地做错"的事，以及它们的反面：
 *
 * 1. 失败项**不进基线** —— 记了的话下一轮就再也发现不了它（永不重试）
 * 2. 目标端被改过的那份**不删** —— 那是运行机自己的东西
 * 3. dry-run **不写基线也不动盘** —— 预览不该改状态
 */
class SyncEngineTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var src: Path
    private lateinit var baseline: Path

    private fun setUpSrc() {
        src = tmp.resolve("src")
        Files.createDirectories(src)
        baseline = tmp.resolve("state/baseline.json")
    }

    private fun write(rel: String, body: String, mtime: Long? = null) {
        val p = src.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, body)
        if (mtime != null) Files.setLastModifiedTime(p, FileTime.from(mtime, TimeUnit.NANOSECONDS))
    }

    private fun engine(target: SyncTarget, dryRun: Boolean = false, log: (String) -> Unit = {}) = SyncEngine(
        srcRoot = src,
        target = target,
        cfg = { SyncConfig() },
        baselinePath = baseline,
        dryRun = dryRun,
        log = log,
    )

    // ---------------------------------------------------------------- 假目标端

    private class FakeTarget : SyncTarget {
        val files = LinkedHashMap<String, ByteArray>()
        val mtime = LinkedHashMap<String, Long>()
        val copied = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var pruneCalledWith: List<String>? = null
        var reachable = true
        var failCopyFor: String? = null
        var failDeleteFor: String? = null
        var scanCount = 0

        override val stateKey: String get() = "fake-target"

        override fun check() {
            if (!reachable) throw SyncError("盘没挂上")
        }

        override fun scan(): Map<String, FileStamp> {
            scanCount++
            return files.mapValues { FileStamp(it.value.size.toLong(), mtime[it.key] ?: 0L) }
        }

        override fun stat(rel: String): FileStamp? =
            files[rel]?.let { FileStamp(it.size.toLong(), mtime[rel] ?: 0L) }

        override fun compareMany(
            srcRoot: Path,
            rels: List<String>,
            onProgress: (Int, Int) -> Unit,
        ): Map<String, Boolean> = rels.associateWith { rel ->
            val onDisk = runCatching { Files.readAllBytes(srcRoot.resolve(rel)) }.getOrNull()
            onDisk != null && files[rel]?.contentEquals(onDisk) == true
        }

        override fun copyFrom(srcFile: Path, rel: String) {
            if (failCopyFor == rel) throw IOException("假装复制失败（被占用）")
            files[rel] = Files.readAllBytes(srcFile)
            mtime[rel] = Files.getLastModifiedTime(srcFile).to(TimeUnit.NANOSECONDS)
            copied += rel
        }

        override fun remove(rel: String): Boolean {
            if (failDeleteFor == rel) throw IOException("假装删不掉")
            val had = files.remove(rel) != null
            mtime.remove(rel)
            if (had) removed += rel
            return had
        }

        override fun prune(rels: List<String>): List<String> {
            pruneCalledWith = rels
            return emptyList()
        }
    }

    // ---------------------------------------------------------------- 新增与复制

    @Test
    @Timeout(30)
    fun `新增文件被复制，基线记下双方状态`() {
        setUpSrc()
        write("trunk/a.kt", "hello")
        val target = FakeTarget()

        val r = engine(target).runRound(reconcileTarget = true)

        assertEquals(listOf("trunk/a.kt"), target.copied)
        assertEquals(1, r.copied)
        assertTrue(r.ok)
        assertTrue(r.baselineSaved, "第一次跑完必须落基线，否则下一轮又要重新核对内容")

        val saved = SyncBaseline.load(baseline, src.toString(), target.stateKey)
        assertEquals(1, saved.size, "基线该记下这一个文件")
        assertEquals(
            Files.size(src.resolve("trunk/a.kt")),
            saved.getValue("trunk/a.kt").src.size,
            "记的是**复制完之后**双方的真实状态，不是计划时的推断",
        )
    }

    @Test
    @Timeout(30)
    fun `第二轮不再复制，也不再读内容`() {
        setUpSrc()
        write("trunk/a.kt", "hello")
        val target = FakeTarget()
        engine(target).runRound(reconcileTarget = true)
        target.copied.clear()

        val r = engine(target).runRound(reconcileTarget = true)

        assertTrue(target.copied.isEmpty(), "两侧都没动过，不该再复制一遍")
        assertEquals(1, r.unchanged)
        assertTrue(r.nothingToDo)
    }

    // ---------------------------------------------------------------- 镜像删除

    @Test
    @Timeout(30)
    fun `本地已删 → 目标端跟着删，并清掉因此变空的目录`() {
        setUpSrc()
        write("trunk/a/b.kt", "x")
        val target = FakeTarget()
        engine(target).runRound(reconcileTarget = true)

        Files.delete(src.resolve("trunk/a/b.kt"))
        val r = engine(target).runRound(reconcileTarget = true)

        assertEquals(listOf("trunk/a/b.kt"), target.removed, "本地删了，目标端该跟着删")
        assertEquals(1, r.deleted)
        assertTrue("trunk/a" in (target.pruneCalledWith ?: emptyList()), "删完要问一句那个目录是不是空了")
    }

    @Test
    @Timeout(30)
    fun `目标端那份被改过 → 只提醒不删`() {
        setUpSrc()
        write("trunk/a.kt", "x")
        val target = FakeTarget()
        engine(target).runRound(reconcileTarget = true)

        Files.delete(src.resolve("trunk/a.kt"))
        target.files["trunk/a.kt"] = "被运行机改过".toByteArray()   // 大小/mtime 都变了
        val r = engine(target).runRound(reconcileTarget = true)

        assertTrue(target.removed.isEmpty(), "目标端那份不是我们同步过去的样子 → 不许删")
        assertEquals(listOf(KeptWhy.TARGET_MODIFIED), r.kept.map { it.why })
        assertEquals(0, r.deleted)
    }

    @Test
    @Timeout(30)
    fun `关掉镜像删除 → 只提醒不删`() {
        setUpSrc()
        write("trunk/a.kt", "x")
        val target = FakeTarget()
        SyncEngine(src, target, { SyncConfig() }, baseline).runRound(reconcileTarget = true)

        Files.delete(src.resolve("trunk/a.kt"))
        val r = SyncEngine(src, target, { SyncConfig(deleteMissing = false) }, baseline)
            .runRound(reconcileTarget = true)

        assertTrue(target.removed.isEmpty())
        assertEquals(listOf(KeptWhy.AUTO_DELETE_OFF), r.kept.map { it.why })
    }

    // ---------------------------------------------------------------- 失败

    @Test
    @Timeout(30)
    fun `复制失败的项不进基线 —— 下一轮会重新发现并重试`() {
        setUpSrc()
        write("trunk/a.kt", "hello")
        val target = FakeTarget().apply { failCopyFor = "trunk/a.kt" }

        val first = engine(target).runRound(reconcileTarget = true)

        assertEquals(1, first.failedTotal)
        assertFalse(first.ok, "有失败项 → 调度器据此退避重试")
        assertTrue(
            SyncBaseline.load(baseline, src.toString(), target.stateKey).isEmpty(),
            "失败项记进基线的话，下一轮就以为它已经同步好了 —— 那样它**永远不会重试**",
        )

        target.failCopyFor = null
        val second = engine(target).runRound(reconcileTarget = true)

        assertEquals(1, second.copied, "下一轮自动重新发现并复制成功")
        assertTrue(second.ok)
    }

    @Test
    @Timeout(30)
    fun `删除失败的项留在基线里 —— 下一轮重新发现并重试`() {
        setUpSrc()
        write("trunk/a.kt", "x")
        val target = FakeTarget()
        engine(target).runRound(reconcileTarget = true)

        Files.delete(src.resolve("trunk/a.kt"))
        target.failDeleteFor = "trunk/a.kt"
        val first = engine(target).runRound(reconcileTarget = true)
        assertEquals(1, first.failedDeletes.size)

        target.failDeleteFor = null
        val second = engine(target).runRound(reconcileTarget = true)
        assertEquals(1, second.deleted, "下一轮该重新发现它仍然是「本地已删」")
    }

    @Test
    @Timeout(30)
    fun `报告里的「做成了哪些」不含失败的那些`() {
        // 2026-09-24 加这条是因为同步气泡要说"同步了哪几个文件"，而 `copies` / `deletes`
        // 是**计划**（含后来失败的）—— 拿计划去报就是把没过去的说成过去了。
        // 界面替引擎撒谎是最坏的一种错，在这件事上尤其（用户正拿它判断"我改的东西过去了没"）
        setUpSrc()
        write("trunk/ok.kt", "ok")
        write("trunk/bad.kt", "bad")
        val target = FakeTarget().apply { failCopyFor = "trunk/bad.kt" }

        val first = engine(target).runRound(reconcileTarget = true)

        assertEquals(2, first.copies.size, "计划里是两个（这一列是计划，不是结果）")
        assertEquals(listOf("trunk/ok.kt"), first.copiedRel, "bad.kt 没复制成功，不该出现在这一列")
        assertEquals(1, first.copied, "计数与列表说的是同一件事")

        target.failCopyFor = null
        val second = engine(target).runRound(reconcileTarget = true)

        assertEquals(listOf("trunk/bad.kt"), second.copiedRel, "下一轮补上了，这一轮才该有它")
    }

    @Test
    @Timeout(30)
    fun `真的删掉的才进 deletedRel`() {
        setUpSrc()
        write("trunk/a.kt", "a")
        write("trunk/b.kt", "b")
        val target = FakeTarget()
        engine(target).runRound(reconcileTarget = true)

        Files.delete(src.resolve("trunk/a.kt"))
        Files.delete(src.resolve("trunk/b.kt"))
        val r = engine(target).runRound(reconcileTarget = true)

        assertEquals(setOf("trunk/a.kt", "trunk/b.kt"), r.deletedRel.toSet())
        assertEquals(r.deleted, r.deletedRel.size)
    }

    @Test
    @Timeout(30)
    fun `目标端不可访问时抛 SyncError —— 调用方退避重试，进程不退出`() {
        setUpSrc()
        write("trunk/a.kt", "x")
        val target = FakeTarget().apply { reachable = false }

        assertThrows(SyncError::class.java) { engine(target).runRound(reconcileTarget = true) }
    }

    // ---------------------------------------------------------------- dry-run

    @Test
    @Timeout(30)
    fun `dry-run 预览但不写基线、不动盘`() {
        setUpSrc()
        write("trunk/a.kt", "hello")
        val target = FakeTarget()

        val r = engine(target, dryRun = true).runRound(reconcileTarget = true)

        assertEquals(1, r.plannedCopies, "报告里要看得出「本来会做什么」")
        assertEquals(0, r.copied)
        assertTrue(target.copied.isEmpty(), "预览不该动盘")
        assertFalse(r.baselineSaved)
        assertFalse(Files.exists(baseline), "dry-run 不该写出基线文件")
    }

    // ---------------------------------------------------------------- 报告

    @Test
    @Timeout(30)
    fun `两侧一致时说得出「没什么可做的」`() {
        setUpSrc()
        write("trunk/a.kt", "x")
        val target = FakeTarget()
        engine(target).runRound(reconcileTarget = true)

        val r = engine(target).runRound(reconcileTarget = true)

        assertTrue(r.nothingToDo)
        assertEquals(0, r.failedTotal)
        assertTrue(r.ok)
    }

    @Test
    @Timeout(30)
    fun `事件轮不遍历目标端，但仍然同步本地的新改动`() {
        setUpSrc()
        write("trunk/a.kt", "one")
        val target = FakeTarget()
        engine(target).runRound(reconcileTarget = true)
        target.scanCount = 0
        target.copied.clear()          // 第一轮复制过 a.kt，这里要单独看第二轮

        write("trunk/b.kt", "two")            // 本地新增（内容对账在**任何**一轮都会发现）
        val r = engine(target).runRound(reconcileTarget = false)

        assertEquals(0, target.scanCount, "事件轮不该遍历目标端（那是巡检轮才做的事）")
        assertEquals(listOf("trunk/b.kt"), target.copied)
        assertEquals(1, r.copied)
    }
}
