package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 内容基线的读写（2026-09-24）。
 *
 * 三条性质各有一个用例，它们对应的都是**错了但不响**的故障：
 *
 * 1. **纳秒精度**：mtime 约 1.8e18，超出 double 能精确表示的整数范围。一旦哪一层把它
 *    当 double 过一遍，症状是"每一轮都在重新核对内容"（慢），而且没有任何报错。
 * 2. **身份作废**：换了源或目标端，基线必须整份失效 —— 否则会把"另一个目标端的状态"
 *    当成这个目标端的，于是那道删除安全闸会拿错参照物。
 * 3. **读坏了大不了重来**：文件不在 / 读不动 / 不是 JSON，一律空表。后果只是下一轮
 *    慢一次（全量核对），**没有正确性代价** —— 这正是它敢这么写的前提。
 */
class SyncBaselineTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var path: Path
    private val src = "C:\\M71\\server"
    private val dst = "Z:\\m71\\server"

    @BeforeEach
    fun setUp() {
        path = tmp.resolve("state/baseline.json")
    }

    private fun pair(rel: String, ns: Long) =
        rel to FilePair(FileStamp(10, ns), FileStamp(20, ns + 7))

    @Test
    fun `往返：纳秒时间戳逐位精确 —— 精度丢了就是「每轮都重新核对」`() {
        val ns = 1_756_000_000_123_456_789L
        val files = mapOf(pair("trunk/a.kt", ns))

        SyncBaseline.save(path, src, dst, files)

        assertEquals(files, SyncBaseline.load(path, src, dst))
    }

    @Test
    fun `多个文件的顺序与内容都保住`() {
        val files = linkedMapOf(
            pair("trunk/a.kt", 1_700_000_000_000_000_001L),
            pair("trunk/deep/b/c.kt", 1_700_000_000_000_000_002L),
            pair("trunk/d.h", 1_700_000_000_000_000_003L),
        )

        SyncBaseline.save(path, src, dst, files)

        assertEquals(files, SyncBaseline.load(path, src, dst))
    }

    @Test
    fun `换了目标端，基线整份作废`() {
        SyncBaseline.save(path, src, dst, mapOf(pair("trunk/a.kt", 42)))

        assertTrue(
            SyncBaseline.load(path, src, "\\\\别的机器\\share").isEmpty(),
            "两个目标端的状态不能混用 —— 删除安全闸就是拿基线里的目标端状态做参照的",
        )
    }

    @Test
    fun `换了源目录，基线也整份作废`() {
        SyncBaseline.save(path, src, dst, mapOf(pair("trunk/a.kt", 42)))

        assertTrue(SyncBaseline.load(path, "D:\\别的\\server", dst).isEmpty())
    }

    @Test
    fun `文件不在、读坏了、不是 JSON —— 一律空表，不抛`() {
        assertTrue(SyncBaseline.load(path, src, dst).isEmpty(), "文件还不存在")

        Files.createDirectories(path.parent)
        Files.writeString(path, "这不是 JSON")
        assertTrue(SyncBaseline.load(path, src, dst).isEmpty())

        Files.writeString(path, "[1, 2, 3]")
        assertTrue(SyncBaseline.load(path, src, dst).isEmpty(), "是个 JSON，但不是对象")

        Files.writeString(path, """{"src":"$src","dst":"$dst","files":"不是对象"}""")
        assertTrue(SyncBaseline.load(path, src, dst).isEmpty())
    }

    @Test
    fun `形状不对的单条记录被跳过，其余照读`() {
        // 这份 JSON 是手写的，所以**路径里的反斜杠必须自己转义**：直接插进字符串字面量
        // 会得到非法 JSON（`\M` 不是合法转义），而那边一律按"读坏了"处理 ——
        // 症状是本用例误判成"基线整个作废"。真跑的时候走 Gson，它自己会转义。
        val esc = { s: String -> s.replace("\\", "\\\\") }
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            """{"src":"${esc(src)}","dst":"${esc(dst)}","files":{
              "good.kt":[1,2,3,4],
              "short":[1,2],
              "notarray":"x",
              "notnumber":["a",2,3,4]
            }}""",
        )

        val loaded = SyncBaseline.load(path, src, dst)

        assertEquals(setOf("good.kt"), loaded.keys, "坏的那几条丢掉就行，不该让整份基线作废")
    }

    @Test
    fun `空表也能往返，且会建出父目录`() {
        SyncBaseline.save(path, src, dst, emptyMap())

        assertTrue(Files.exists(path), "父目录（state/）本来不存在，要建出来")
        assertTrue(SyncBaseline.load(path, src, dst).isEmpty())
    }

    @Test
    fun `写盘不留临时文件 —— 那半个文件绝不能被当成基线`() {
        SyncBaseline.save(path, src, dst, mapOf(pair("trunk/a.kt", 42)))

        val leftovers = Files.list(path.parent).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf(path.fileName.toString()), leftovers, "临时文件必须已经被 move 掉")
    }

    @Test
    fun `键是哈希：同一对稳定，不同对不撞`() {
        val a = SyncBaseline.keyFor(src, dst)

        assertEquals(a, SyncBaseline.keyFor(src, dst), "同一对目录每次算出来要一样，否则每轮都在重做全量核对")
        assertNotEquals(a, SyncBaseline.keyFor(src, "Z:\\另一个\\server"))
        assertFalse(a.contains(":"), "盘符里的冒号不能进文件名")
        assertFalse(a.contains("\\"), "分隔符也不能进文件名")
        assertEquals(16, a.length, "16 个十六进制字符（8 字节）")
    }
}
