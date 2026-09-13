package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path

class ImageIngestTest {

    @Test
    fun `图片扩展名认大小写`() {
        assertTrue(isImageFile("a.png"))
        assertTrue(isImageFile("a.JPG"))
        assertTrue(isImageFile("a.Jpeg"))
        assertTrue(isImageFile("a.webp"))
        assertFalse(isImageFile("a.txt"))
        assertFalse(isImageFile("a.kt"))
        assertFalse(isImageFile("noextension"))
    }

    @Test
    fun `剪贴板同时有图与文本时算有图 —— 有图优先`() {
        // 截图工具基本只放图，所以这条很少触发；代价是"从 Excel 复制表格"
        // 会粘成一张图，需要手动删一下。这个取舍写在 spec §4
        val flavors = listOf(DataFlavor.stringFlavor, DataFlavor.imageFlavor)
        assertTrue(clipHasImage(flavors))
    }

    @Test
    fun `只有文本时不算有图 —— 纯文本粘贴必须原样放行`() {
        assertFalse(clipHasImage(listOf(DataFlavor.stringFlavor)))
        assertFalse(clipHasImage(emptyList()))
    }

    @Test
    fun `文件列表里只挑图片，非图片与读不出的都跳过`(@TempDir dir: Path) {
        val png = dir.resolve("a.png").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val txt = dir.resolve("b.txt").toFile().apply { writeText("hello") }
        val gone = File(dir.toFile(), "missing.png")

        val out = readImageFiles(listOf(png, txt, gone, dir.toFile()))

        assertEquals(1, out.size)
        assertEquals("a.png", out[0].name)
        assertTrue(out[0].bytes.contentEquals(byteArrayOf(1, 2, 3)))
    }
}
