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
    fun `带 alpha 的 PNG 不许降成 JPEG，且 alpha 必须活下来`() {
        // 用噪声而不是单色：单色 PNG 太小，根本走不到降质那条路，
        // 那样这条测试删掉守卫也照样绿 —— 等于没测
        val img = BufferedImage(MAX_EDGE, MAX_EDGE, BufferedImage.TYPE_INT_ARGB)
        val rnd = java.util.Random(11)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) img.setRGB(x, y, rnd.nextInt())
        }
        // 挖一块透明区，模拟截图里的透明圆角
        for (y in 0 until 200) {
            for (x in 0 until 200) img.setRGB(x, y, 0x00000000)
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)

        val a = normalizeImage(out.toByteArray(), "image/png")

        assertEquals("image/png", a.mediaType)
        val result = decode(a)
        assertEquals(0, result.getRGB(10, 10) ushr 24, "透明区被填实了 —— 大概率是转成了 JPEG")
    }

    @Test
    fun `已被压过的大图也必须缩到 1568 —— 不许因重编码变大而回退成原尺寸`() {
        // 2400×1800 存成低质量 JPEG：字节很小，于是"缩完再按 q0.85 重编码"
        // 一定比原图大。这正是回退分支最容易走到的形状。
        // 实测同一构造：原图 279KB，缩到 1568 再按 q0.85 编出来 792KB（大一倍多），
        // base64 才 106 万字符、远没到上限 —— 所以不看"有没有缩过"的老代码
        // 必然在这里回退成 2400×1800
        val img = BufferedImage(2400, 1800, BufferedImage.TYPE_INT_RGB)
        val rnd = java.util.Random(7)
        for (y in 0 until img.height) {
            for (x in 0 until img.width) img.setRGB(x, y, rnd.nextInt(0xFFFFFF))
        }
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.05f
        }
        ImageIO.createImageOutputStream(out).use {
            writer.output = it
            writer.write(null, javax.imageio.IIOImage(img, null, null), param)
        }

        val a = normalizeImage(out.toByteArray(), "image/jpeg")
        val result = decode(a)

        assertEquals(MAX_EDGE, maxOf(result.width, result.height))
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
    fun `GIF 源被重编码成 PNG 后，mediaType 跟着改成 png`() {
        // 与规则③同一类：报了 image/gif 却给 PNG 字节，Claude 会直接解不开。
        // 这条路径从 Task 4 的拖拽/选文件就能走到 —— 用户拖一张 gif 进来
        for (ext in listOf("gif", "bmp")) {
            // bmp 走的是同一条路：encode(jpeg = false) 写出来的永远是 PNG 字节
            val img = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            val out = ByteArrayOutputStream()
            ImageIO.write(img, ext, out)

            val a = normalizeImage(out.toByteArray(), "image/$ext")

            assertEquals("image/png", a.mediaType, "$ext 源")
        }
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

    // ---- 张数上限的第二道闸 ----

    /** 缩略图那一步才解 base64，这里只当个占位。 */
    private val one = ImageAttachment("image/png", "AAAA")

    @Test
    fun `还有位置时原样通过，没有被截的`() {
        val (kept, clamped) = clampToLimit(List(3) { one }, currentCount = 1)

        assertEquals(3, kept.size)
        assertEquals(0, clamped)
    }

    @Test
    fun `已经满了就全拒 —— 两次粘贴落在同一个解码窗口里的那一半`() {
        // 第一道闸在后台线程上看到的 existing 是旧数，两边都可能以为"还有位置"。
        // 这一道是在 EDT 上按当下的张数截的，所以必须全拒而不是再收 5 张
        val (kept, clamped) = clampToLimit(List(5) { one }, currentCount = MAX_IMAGES)

        assertTrue(kept.isEmpty())
        assertEquals(5, clamped, "被这道截掉几张，就该报几张 —— 否则提示行不会出现")
    }

    @Test
    fun `只剩两个位置时留两个，其余算被截`() {
        val (kept, clamped) = clampToLimit(List(5) { one }, currentCount = MAX_IMAGES - 2)

        assertEquals(2, kept.size)
        assertEquals(3, clamped)
    }
}
