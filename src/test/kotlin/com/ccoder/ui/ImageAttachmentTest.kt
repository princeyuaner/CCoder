package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** 造一张纯色 PNG。尺寸可控，用来验缩放。 */
private fun png(w: Int, h: Int, alpha: Boolean = false): ByteArray {
    val img = BufferedImage(w, h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color(0x33, 0x66, 0x99)
    g.fillRect(0, 0, w, h)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
}

private fun jpeg(w: Int, h: Int): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "jpeg", out)
    return out.toByteArray()
}

private fun decode(a: ImageAttachment): BufferedImage =
    ImageIO.read(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(a.base64)))

class ImageAttachmentTest {

    @Test
    fun `长边超过 1568 的等比缩到 1568`() {
        val a = normalizeImage(png(4000, 2000), "image/png")
        val img = decode(a)
        assertEquals(1568, img.width)
        assertEquals(784, img.height) // 4000:2000 = 2:1，等比下来 784
    }

    @Test
    fun `长边不超标就原尺寸`() {
        val img = decode(normalizeImage(png(800, 600), "image/png"))
        assertEquals(800, img.width)
        assertEquals(600, img.height)
    }

    @Test
    fun `源是 JPEG 就出 JPEG，源是 PNG 就出 PNG`() {
        assertEquals("image/jpeg", normalizeImage(jpeg(800, 600), "image/jpeg").mediaType)
        assertEquals("image/png", normalizeImage(png(800, 600), "image/png").mediaType)
    }

    @Test
    fun `带 alpha 的 PNG 不许降成 JPEG`() {
        // 透明区在 JPEG 里会变成黑块 —— 截图带透明圆角时特别明显
        val a = normalizeImage(png(2400, 2400, alpha = true), "image/png")
        assertEquals("image/png", a.mediaType)
    }

    @Test
    fun `PNG 超限时降成 JPEG，mediaType 跟着改`() {
        // 随机噪声压不动：1568² 的 PNG 会明显超过 1.5MB 的 base64 上限。
        // 这条钉的是规则③ —— 报了 image/png 却给 JPEG 字节，Claude 会直接解不开。
        // 也是"重编码比原图大就收手"那个坑的钉子：那正是最该降质的时刻
        val noisy = BufferedImage(MAX_EDGE, MAX_EDGE, BufferedImage.TYPE_INT_RGB)
        val rnd = java.util.Random(42)
        for (y in 0 until noisy.height) {
            for (x in 0 until noisy.width) noisy.setRGB(x, y, rnd.nextInt(0xFFFFFF))
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(noisy, "png", out)

        val a = normalizeImage(out.toByteArray(), "image/png")

        assertEquals("image/jpeg", a.mediaType)
        assertTrue(a.base64.length <= MAX_BASE64_CHARS, "降质后仍然超限：${a.base64.length}")
    }

    @Test
    fun `解不开的字节原样返回，不缩放也不抛`() {
        // webp 就走这条路：Java 原生解不开，但 Claude 认这个格式
        val raw = byteArrayOf(1, 2, 3, 4, 5)
        val a = normalizeImage(raw, "image/webp")
        assertEquals("image/webp", a.mediaType)
        assertEquals(java.util.Base64.getEncoder().encodeToString(raw), a.base64)
    }

    @Test
    fun `mediaTypeOf 认扩展名，认不出的退回 png`() {
        assertEquals("image/jpeg", mediaTypeOf("a.JPG"))
        assertEquals("image/jpeg", mediaTypeOf("a.jpeg"))
        assertEquals("image/png", mediaTypeOf("a.png"))
        assertEquals("image/gif", mediaTypeOf("a.gif"))
        assertEquals("image/bmp", mediaTypeOf("a.bmp"))
        assertEquals("image/webp", mediaTypeOf("a.webp"))
        assertEquals("image/png", mediaTypeOf("a.unknown"))
    }

    @Test
    fun `超过 5MB 的单张被拒，并给出原因`() {
        val big = ByteArray(MAX_RAW_BYTES + 1)
        val intake = acceptImages(0, listOf(RawImage(big, "big.png")))
        assertTrue(intake.accepted.isEmpty())
        assertEquals(1, intake.rejected)
        assertEquals("单张超过 5MB", intake.reason)
    }

    @Test
    fun `一次最多 5 张，多出来的被拒`() {
        val small = png(10, 10)
        val intake = acceptImages(0, List(7) { RawImage(small, "s$it.png") })
        assertEquals(MAX_IMAGES, intake.accepted.size)
        assertEquals(2, intake.rejected)
        assertEquals("一次最多 5 张", intake.reason)
    }

    @Test
    fun `已经有 3 张时只能再收 2 张`() {
        val small = png(10, 10)
        val intake = acceptImages(3, List(3) { RawImage(small, "s$it.png") })
        assertEquals(2, intake.accepted.size)
        assertEquals(1, intake.rejected)
    }

    @Test
    fun `没被拒就没有原因`() {
        val intake = acceptImages(0, listOf(RawImage(png(10, 10), "s.png")))
        assertEquals(1, intake.accepted.size)
        assertNull(intake.reason)
    }
}
