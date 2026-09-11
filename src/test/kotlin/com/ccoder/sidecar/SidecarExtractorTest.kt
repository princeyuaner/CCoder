package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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

        val result = SidecarExtractor.extract(src, dst, "0.1.0")

        assertEquals(dst.resolve("0.1.0"), result)
        assertTrue(Files.exists(result.resolve("index.js")))
        assertTrue(Files.exists(result.resolve("test/env.test.js")), "子目录必须递归复制")
    }

    @Test
    fun `版本相同的重复提取是幂等的`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val first = SidecarExtractor.extract(src, dst, "0.1.0")
        Files.writeString(first.resolve("index.js"), "已被修改")
        val second = SidecarExtractor.extract(src, dst, "0.1.0")

        assertEquals(first, second)
        assertEquals(
            "已被修改", Files.readString(second.resolve("index.js")),
            "版本未变时不应重复覆盖——避免每次启动都做无谓的磁盘写入"
        )
    }

    @Test
    fun `版本变化时重新提取且不留旧文件`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val v1 = SidecarExtractor.extract(src, dst, "0.1.0")
        Files.writeString(v1.resolve("stale.js"), "旧版本的残留文件")

        val v2 = SidecarExtractor.extract(src, dst, "0.2.0")

        assertFalse(Files.exists(v2.resolve("stale.js")), "新版本目录必须是干净的")
        assertTrue(Files.exists(v2.resolve("index.js")))
    }

    @Test
    fun `清理其他版本目录只保留当前版本`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        SidecarExtractor.extract(src, dst, "0.1.0")
        val current = SidecarExtractor.extract(src, dst, "0.2.0")

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

        val result = SidecarExtractor.extract(src, nested, "0.1.0")

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

        val result = SidecarExtractor.extract(src, dst, "0.1.0")

        assertTrue(Files.exists(result.resolve("index.js")), "半成品必须被重建而非当成完整版")
        assertTrue(Files.exists(result.resolve("env.js")))
    }
}
