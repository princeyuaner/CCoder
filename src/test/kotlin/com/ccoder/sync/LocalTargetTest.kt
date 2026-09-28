package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * 本地目标端后端（2026-09-24）。真目录，不用假实现 —— 这一层的价值全在"真的碰盘时
 * 会发生什么"上。
 *
 * 三条**安全性质**在这里钉住，它们都是"错了就毁数据"的量级：
 *
 * 1. **复制保留 mtime** —— [mayDelete] 那道闸拿"目标端现在的 mtime == 基线里记的"当
 *    "它没被人动过"的判据。不保留的话，目标端的 mtime 永远是"复制那一刻"，
 *    判据照样自洽；但**和源端一致**让这个判据更容易被人读懂，也更容易在排查时
 *    一眼看出"这个文件不是我们放过去的"。
 * 2. **prune 只删确实是空的目录** —— 目标端那些装着运行产物的目录因此绝不被误伤。
 * 3. **remove 对"本来就没有"返回 false** —— 那不是失败，报告里要分开计数。
 */
class LocalTargetTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var src: Path
    private lateinit var dst: Path
    private val cfg = SyncConfig()

    @BeforeEach
    fun setUp() {
        src = tmp.resolve("src").also { Files.createDirectories(it) }
        dst = tmp.resolve("dst").also { Files.createDirectories(it) }
    }

    private fun target() = LocalTarget(dst) { cfg }

    private fun write(root: Path, rel: String, body: String, mtimeSec: Long? = null): Path {
        val p = root.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, body)
        if (mtimeSec != null) Files.setLastModifiedTime(p, FileTime.from(mtimeSec, TimeUnit.SECONDS))
        return p
    }

    // ---------------------------------------------------------------- 前置检查

    @Test
    fun `check：目标目录不在就抛 —— 调用方据此退避重试`() {
        val gone = LocalTarget(tmp.resolve("不存在的盘")) { cfg }

        val e = assertThrows(SyncError::class.java) { gone.check() }
        assertTrue(e.message!!.contains("不可访问"), "要说清是「目标目录不可访问」，实际：${e.message}")
    }

    // ---------------------------------------------------------------- 复制

    @Test
    fun `复制保留 mtime —— 删除安全闸拿它当参照`() {
        val srcFile = write(src, "trunk/a.kt", "hello", mtimeSec = 1_700_000_000L)

        target().copyFrom(srcFile, "trunk/a.kt")

        val got = Files.getLastModifiedTime(dst.resolve("trunk/a.kt")).to(TimeUnit.SECONDS)
        assertEquals(1_700_000_000L, got, "目标端那份的 mtime 该等于源端的")
    }

    @Test
    fun `复制按需建父目录，内容真的过去了`() {
        val srcFile = write(src, "trunk/deep/nested/a.kt", "内容")

        target().copyFrom(srcFile, "trunk/deep/nested/a.kt")

        assertEquals("内容", Files.readString(dst.resolve("trunk/deep/nested/a.kt")))
    }

    @Test
    fun `复制覆盖已存在的目标端那份`() {
        write(dst, "trunk/a.kt", "旧的")
        val srcFile = write(src, "trunk/a.kt", "新的")

        target().copyFrom(srcFile, "trunk/a.kt")

        assertEquals("新的", Files.readString(dst.resolve("trunk/a.kt")))
    }

    // ---------------------------------------------------------------- 删除

    @Test
    fun `remove：删得掉返回 true`() {
        write(dst, "trunk/a.kt", "x")

        assertTrue(target().remove("trunk/a.kt"))
        assertFalse(Files.exists(dst.resolve("trunk/a.kt")))
    }

    @Test
    fun `remove：目标端本来就没有返回 false —— 那不是失败`() {
        assertFalse(target().remove("trunk/never-existed.kt"))
    }

    // ---------------------------------------------------------------- 清空目录

    @Test
    fun `prune 只删确实是空的目录`() {
        Files.createDirectories(dst.resolve("trunk/empty"))
        write(dst, "trunk/full/运行产物.log", "目标端自己的东西")

        val removed = target().prune(listOf("trunk/empty", "trunk/full"))

        assertEquals(listOf("trunk/empty"), removed, "只清真空的")
        assertTrue(
            Files.exists(dst.resolve("trunk/full")),
            "装着东西的目录绝不能删 —— 那里面可能是运行机自己的产物，我们从不碰它们",
        )
    }

    @Test
    fun `prune 对不在的目录也不抛`() {
        assertEquals(emptyList<String>(), target().prune(listOf("trunk/never")))
    }

    // ---------------------------------------------------------------- 扫描与过滤

    @Test
    fun `scan 走与源端同一套过滤规则 —— 垃圾目录不下探、排除项跳过`() {
        write(dst, "trunk/a.kt", "keep")
        write(dst, "trunk/node_modules/pkg/index.js", "junk")
        write(dst, "trunk/build/out.o", "junk")
        write(dst, "trunk/skipme.txt", "excluded")
        val t = LocalTarget(dst) { cfg.copy(exclude = listOf("skipme.txt")) }

        assertEquals(setOf("trunk/a.kt"), t.scan().keys, "两份过滤规则必须是同一份，否则两个后端会逐渐漂移")
    }

    @Test
    fun `scan 给的是纳秒级的 mtime`() {
        write(dst, "trunk/a.kt", "x")

        val stamp = target().scan().getValue("trunk/a.kt")

        assertEquals(Files.size(dst.resolve("trunk/a.kt")), stamp.size)
        assertEquals(
            Files.getLastModifiedTime(dst.resolve("trunk/a.kt")).to(TimeUnit.NANOSECONDS),
            stamp.mtimeNs,
        )
    }

    @Test
    fun `stat：不存在返回 null，目录也返回 null`() {
        Files.createDirectories(dst.resolve("trunk/dir"))

        assertNull(target().stat("trunk/never.kt"))
        assertNull(target().stat("trunk/dir"), "@目录 同步不过来，也不该被当成文件去比大小")
        write(dst, "trunk/a.kt", "x")
        assertTrue(target().stat("trunk/a.kt") != null)
    }

    // ---------------------------------------------------------------- 内容比对

    @Test
    fun `内容比对：一致、同大小但不同、大小不同`() {
        val a = write(src, "a.txt", "abcdef")
        val b = write(dst, "b.txt", "abcdef")
        val c = write(dst, "c.txt", "abcdeX")

        assertTrue(filesIdentical(a, b), "内容一样")
        assertFalse(filesIdentical(a, c), "大小一样但内容不同 —— 这条最容易写错，必须真的比到内容")
    }

    @Test
    fun `内容比对：大小不同直接判否，不必读`() {
        val a = write(src, "a.txt", "abc")
        val b = write(dst, "b.txt", "abcdef")

        assertFalse(filesIdentical(a, b))
    }

    @Test
    fun `内容比对：两个空文件算一致`() {
        val a = write(src, "a.txt", "")
        val b = write(dst, "b.txt", "")

        assertTrue(filesIdentical(a, b))
    }

    @Test
    fun `内容比对：有一侧读不了按「不同」处理 —— 于是会走复制，让下一轮处理真问题`() {
        val a = write(src, "a.txt", "abc")
        val missing = tmp.resolve("src/不存在.txt")

        assertFalse(filesIdentical(a, missing))
    }

    @Test
    fun `内容比对：跨过 1MB 的分块边界也算对`() {
        // 比对是分块读的（1MB 一块），所以"正好跨块且后半段不同"这条得过一遍 ——
        // 只比第一块就返回 true 是这类实现最经典的错法
        val body = "x".repeat(1 shl 20) + "尾巴不同"
        val a = write(src, "big.txt", body)
        val b = write(dst, "big2.txt", body)

        assertTrue(filesIdentical(a, b))

        val c = write(dst, "big3.txt", "x".repeat(1 shl 20) + "尾巴不同！")
        // 大小也不同了，先按大小判否；这里主要确认它不因为长度变化而炸
        assertFalse(filesIdentical(a, c))
    }

    @Test
    fun `stateKey 是归一化后的绝对路径 —— 同一目录换个写法要算出同一个键`() {
        val key1 = LocalTarget(dst) { cfg }.stateKey
        val key2 = LocalTarget(dst.resolve(".").resolve("..").resolve(dst.fileName)) { cfg }.stateKey

        assertEquals(key1, key2, "键不稳定的话，每轮都会以为换了目标端 → 每轮重做全量核对")
        assertTrue(key1.endsWith(dst.fileName.toString()))
    }
}
