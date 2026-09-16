package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier

class SidecarExtractorTest {

    private fun makeSource(root: Path) {
        Files.createDirectories(root.resolve("test"))
        Files.writeString(root.resolve("index.js"), "console.log('hi')")
        Files.writeString(root.resolve("env.js"), "export const x = 1")
        Files.writeString(root.resolve("test/env.test.js"), "// test")
    }

    @Test
    fun `提取到目标目录`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val result = SidecarExtractor.extract(src, dst, "0.1.0", "fp-1")

        assertEquals(dst.resolve("0.1.0"), result)
        assertTrue(Files.exists(result.resolve("index.js")))
        assertTrue(Files.exists(result.resolve("test/env.test.js")), "子目录必须递归复制")
    }

    @Test
    fun `版本与内容都未变时重复提取是幂等的`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val first = SidecarExtractor.extract(src, dst, "0.1.0", "fp-1")
        Files.writeString(first.resolve("index.js"), "已被修改")
        val second = SidecarExtractor.extract(src, dst, "0.1.0", "fp-1")

        assertEquals(first, second)
        assertEquals(
            "已被修改", Files.readString(second.resolve("index.js")),
            "版本与内容都没变时不应重复覆盖——避免每次启动都做无谓的磁盘写入"
        )
    }

    @Test
    fun `版本相同但内容变化时必须重新提取`(@TempDir tmp: Path) {
        // 这是本类最要紧的一条。提取目录按版本号命名，若只判 index.js 存在就复用，
        // 那么"改了 sidecar 代码但没动版本号"的包里，新代码永远不会被提取，
        // 旧的会一直被使用 —— 而且完全无声。实测踩过：session.js 的 interrupt
        // 修复在非 CCoder 项目里被旧的提取结果顶掉
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val first = SidecarExtractor.extract(src, dst, "0.2.0", "fp-A")
        assertEquals("console.log('hi')", Files.readString(first.resolve("index.js")))

        // 同一个版本号，但包里的内容变了
        Files.writeString(src.resolve("index.js"), "console.log('新代码')")
        val second = SidecarExtractor.extract(src, dst, "0.2.0", "fp-B")

        assertEquals(first, second, "目录仍是同一个版本目录")
        assertEquals(
            "console.log('新代码')", Files.readString(second.resolve("index.js")),
            "内容指纹变了就必须重新提取，否则新 sidecar 永远不生效"
        )
    }

    @Test
    fun `版本变化时重新提取且不留旧文件`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val v1 = SidecarExtractor.extract(src, dst, "0.1.0", "fp-1")
        Files.writeString(v1.resolve("stale.js"), "旧版本的残留文件")

        val v2 = SidecarExtractor.extract(src, dst, "0.2.0", "fp-2")

        assertFalse(Files.exists(v2.resolve("stale.js")), "新版本目录必须是干净的")
        assertTrue(Files.exists(v2.resolve("index.js")))
    }

    @Test
    fun `清理其他版本目录只保留当前版本`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        SidecarExtractor.extract(src, dst, "0.1.0", "fp-1")
        val current = SidecarExtractor.extract(src, dst, "0.2.0", "fp-2")

        val siblings = Files.list(dst).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(
            listOf("0.2.0"), siblings,
            "旧版本目录应被清理，否则每次升级都在磁盘上留一份 28M 的副本"
        )
        assertTrue(Files.exists(current.resolve("index.js")))
    }

    @Test
    fun `目标目录不存在时自动创建`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        makeSource(src)
        val nested = tmp.resolve("a/b/c")

        val result = SidecarExtractor.extract(src, nested, "0.1.0", "fp-1")

        assertTrue(Files.exists(result.resolve("index.js")))
    }

    @Test
    fun `中断留下的半成品目录会被重建`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        // 模拟上次提取中断：目录存在但没有 index.js
        val partial = dst.resolve("0.1.0")
        Files.createDirectories(partial)
        Files.writeString(partial.resolve("half-written.js"), "残缺内容")

        val result = SidecarExtractor.extract(src, dst, "0.1.0", "fp-1")

        assertTrue(Files.exists(result.resolve("index.js")), "半成品必须被重建而非当成完整版")
        assertTrue(Files.exists(result.resolve("env.js")))
    }

    /**
     * 两个标签同时冷启动（多标签，2026-09-16）。
     *
     * 提取是"**先删目录再重拷**"：没有互斥时，后到的那个可能把先到那个正在用的
     * 目录删掉 —— 症状是那边刚起好的 node 找不到 index.js（生产模式才会走到这儿，
     * 开发模式用源码树）。这条钉的是"两边都拿到同一个、内容完整的目录"。
     */
    @Test
    fun `并发提取不会互删`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val barrier = CyclicBarrier(2)
        val results = ConcurrentHashMap<Int, Path>()
        val errors = ConcurrentHashMap<Int, Throwable>()
        val threads = (0 until 2).map { i ->
            Thread {
                try {
                    barrier.await()
                    results[i] = SidecarExtractor.extract(src, dst, "0.3.0", "fp-C")
                } catch (t: Throwable) {
                    errors[i] = t
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(errors.isEmpty(), "并发提取不该抛：$errors")
        assertEquals(2, results.size)
        assertEquals(1, results.values.toSet().size, "两边必须指向同一个目录")
        val dir = results.values.first()
        assertTrue(Files.exists(dir.resolve("index.js")), "内容必须完整")
        assertTrue(Files.exists(dir.resolve("test/env.test.js")), "子目录也得在")
        // 指纹文件的名字是 SidecarExtractor 的私有常量，这里按它的契约直接读
        assertEquals("fp-C", Files.readString(dir.resolve(".fingerprint")))
    }
}
