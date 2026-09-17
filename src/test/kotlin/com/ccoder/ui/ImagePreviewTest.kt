package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage

/**
 * 点开看图那几件纯逻辑：一击落在哪儿、图缩进框里多少倍、开框开多大，
 * 以及**解出来的到底是哪一份字节**。
 *
 * 框本身（`DialogWrapper`、键盘翻页、画布）交给 `ImagePreviewRenderProbe` 出图看 ——
 * 单测钉不住"这张图看着清不清楚"，那要眼睛。
 */
class ImagePreviewTest {

    private fun png(w: Int, h: Int, color: Color = Color(30, 60, 220)): BufferedImage =
        BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                this.color = color
                fillRect(0, 0, w, h)
                dispose()
            }
        }

    // ---- 一击落在哪儿（缩略图 56×42，✕ 占右上角 14×14）----

    @Test
    fun `右上角那 14×14 算 ✕`() {
        assertEquals(ThumbHit.REMOVE, thumbHit(42, 0, 56, 14), "✕ 的左上角")
        assertEquals(ThumbHit.REMOVE, thumbHit(55, 14, 56, 14), "✕ 的右下角")
    }

    @Test
    fun `差一个像素就不算 ✕ 了`() {
        // 边界像素是最容易写错的那一处：>= 与 > 差一个，边框上就多一条"点不动"的缝
        assertEquals(ThumbHit.OPEN, thumbHit(41, 14, 56, 14), "✕ 左边那一列")
        assertEquals(ThumbHit.OPEN, thumbHit(42, 15, 56, 14), "✕ 下面那一行")
    }

    @Test
    fun `其余地方都算点正文 —— 尤其是缩略图正中`() {
        assertEquals(ThumbHit.OPEN, thumbHit(28, 21, 56, 14), "正中：最常点的地方")
        assertEquals(ThumbHit.OPEN, thumbHit(0, 0, 56, 14), "左上角")
        assertEquals(ThumbHit.OPEN, thumbHit(55, 41, 56, 14), "右下角：离 ✕ 最远")
    }

    // ---- 缩进框里多少倍 ----

    @Test
    fun `大图按比例缩进框里`() {
        assertEquals(0.5, fitScale(1000, 500, 500, 500), 1e-9)
        // 宽是瓶颈：4000×1000 缩到 800 宽时高只剩 200
        assertEquals(0.2, fitScale(4000, 1000, 800, 800), 1e-9)
    }

    @Test
    fun `比框小就 1 比 1 —— 放大超过原图只是把像素摊开`() {
        assertEquals(1.0, fitScale(200, 100, 500, 500), 1e-9)
        assertEquals(1.0, fitScale(500, 500, 500, 500), 1e-9)
    }

    @Test
    fun `量不出尺寸时不除零`() {
        assertEquals(1.0, fitScale(0, 0, 100, 100), 1e-9)
        assertEquals(1.0, fitScale(100, 100, 0, 0), 1e-9)
    }

    // ---- 开框开多大 ----

    @Test
    fun `开框尺寸封顶在屏幕可用区的八成`() {
        // 1920×1080 的八成是 1536×864；4000×3000 的图按高度掐：0.288 → 1152×864
        assertEquals(Dimension(1152, 864), previewImageSize(4000, 3000, 1920, 1080))
    }

    @Test
    fun `小图不放大，但也不至于小得可笑`() {
        val size = previewImageSize(320, 200, 1920, 1080)

        assertEquals(PREVIEW_MIN_W, size.width)
        assertEquals(PREVIEW_MIN_H, size.height)
    }

    @Test
    fun `比例不变 —— 大图的宽高比就是原图的宽高比`() {
        val size = previewImageSize(1600, 800, 1920, 1080)

        assertEquals(2.0, size.width.toDouble() / size.height, 1e-9, "$size")
    }

    @Test
    fun `窄高图被地板顶宽之后，画的时候再按框算一次，图不变形`() {
        // 500×4000 按高度缩到 864，宽只有 108 —— 被 [PREVIEW_MIN_W] 顶成 360。
        // 框宽了没事：画的那一步拿 360×864 再算一次，图仍是 108 宽、左右留白
        val box = previewImageSize(500, 4000, 1920, 1080)
        val drawn = fitScale(500, 4000, box.width, box.height)

        assertEquals(360, box.width)
        assertEquals(864, box.height)
        assertEquals(0.216, drawn, 1e-9)
        assertEquals(500.0 / 4000, (500 * drawn) / (4000 * drawn), 1e-9, "比例该原样")
    }

    // ---- 左下角那行字 ----

    @Test
    fun `多张时才写第几张，一张时整行不显示`() {
        assertEquals("1 / 4 · ← → 翻页", previewHintText(4, 0))
        assertEquals("2 / 4 · ← → 翻页", previewHintText(4, 1))
        assertEquals("", previewHintText(1, 0), "一张的时候写「1 / 1」既没用又白占一行高度")
    }

    // ---- 解出来的是哪一份 ----

    @Test
    fun `解的是将要发出去的那份字节，不是 56px 的缩略图`() {
        val big = prepareAttachment(png(2000, 1000), 0) ?: error("夹具没做成")

        val preview = previewImageOf(big.bytes)

        assertNotNull(preview, "自己刚编出来的字节该解得开")
        assertEquals(MAX_EDGE, preview!!.width, "该是发送那份（长边 $MAX_EDGE），不是缩略图")
        assertEquals(THUMB_W, big.thumb.width, "缩略图是给带上那 56px 用的")
    }

    @Test
    fun `坏字节返回 null —— 让调用方给一句打不开，而不是崩`() {
        assertNull(previewImageOf(byteArrayOf(1, 2, 3, 4)))
        assertNull(previewImageOf(ByteArray(0)))
    }

    @Test
    fun `同一张图解两次是同一份内容 —— 翻页缓存靠的就是它`() {
        val attachment = prepareAttachment(png(300, 200), 0) ?: error("夹具没做成")

        val a = previewImageOf(attachment.bytes)
        val b = previewImageOf(attachment.bytes)

        assertNotNull(a)
        assertTrue(a!!.width == b!!.width && a.height == b.height)
    }
}
