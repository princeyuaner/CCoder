package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Random
import javax.imageio.ImageIO

/**
 * 贴图的纯逻辑：缩放、编码、命名、校验。
 *
 * 全部喂 [BufferedImage]，**不碰剪贴板**（那要 AWT 机器人与一个真窗口，测不稳），
 * 也不碰 Swing —— 与 [SendQueue] 同一个分工。
 *
 * 上限那几条不是随便定的：它们来自 `claude.exe` 内嵌 JS 里的
 * `rk = { maxWidth: 2000, maxHeight: 2000, maxBase64Size: 5242880, targetRawSize: 3932160 }`
 * 与它自己的目标长边 1568（见 AttachedImage.kt 的文件头）。
 */
class AttachedImageTest {

    private fun solid(w: Int, h: Int, color: Color = Color(30, 60, 220)): BufferedImage =
        BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                this.color = color
                fillRect(0, 0, w, h)
                dispose()
            }
        }

    /** 噪声图：压不动的那一类，用来逼出 JPEG 那条路。种子固定，测试不随机。 */
    private fun noisy(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val rnd = Random(42)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, rnd.nextInt(0x1000000))
        return img
    }

    // ---- 缩放 ----

    @Test
    fun `够小的图原样返回，不重画`() {
        // 重画一次就掉一次锐度。而"大多数截图本来就在 1568 以内"是常态，
        // 不该为它们付这个代价 —— 这条钉的就是"别没事找事"
        val img = solid(800, 600)

        assertSame(img, fitToLimit(img))
    }

    @Test
    fun `长边超限按比例缩，横图与竖图都落到 1568`() {
        assertEquals(1568, fitToLimit(solid(3000, 1000)).width)
        assertEquals(523, fitToLimit(solid(3000, 1000)).height)

        val tall = fitToLimit(solid(1000, 3000))
        assertEquals(523, tall.width)
        assertEquals(1568, tall.height)
    }

    // ---- 编码 ----

    @Test
    fun `小图编成 PNG —— 截图是界面，文字边缘只有 PNG 干净`() {
        val (mediaType, bytes) = encodeForApi(solid(400, 300))

        assertEquals("image/png", mediaType)
        assertTrue(bytes.isNotEmpty())
        assertNotNull(ImageIO.read(bytes.inputStream()), "编出来的东西要能读回去")
    }

    @Test
    fun `压不动的噪声图退到 JPEG —— 省的是用户的钱`() {
        val (mediaType, bytes) = encodeForApi(noisy(1568, 1568))

        assertEquals("image/jpeg", mediaType)
        assertNotNull(ImageIO.read(bytes.inputStream()))
        // JPEG 走的是 RGB，不能带 alpha；带上会写出偏色的图（ImageIO 的老毛病）
        assertTrue(bytes.size < 3_936_216, "转完还得比 targetRawSize 小，实测 ${bytes.size}")
    }

    // ---- 缩略图 ----

    @Test
    fun `缩略图是等大的方块 —— 一排缩略图不能参差不齐`() {
        val thumb = thumbOf(solid(1600, 900))

        assertEquals(THUMB_W, thumb.width)
        assertEquals(THUMB_H, thumb.height)
    }

    @Test
    fun `缩略图里真有内容，不是一块空画布`() {
        // 画布是黑的、内容是红的：完全可能"尺寸对、画没画上去"，这条钉住它
        val thumb = thumbOf(solid(1600, 900, Color(220, 30, 30)))
        val center = Color(thumb.getRGB(THUMB_W / 2, THUMB_H / 2))

        assertTrue(center.red > 150 && center.green < 100, "中心像素是 $center，缩略图没画上东西")
    }

    // ---- 命名 ----

    @Test
    fun `名字带序号与扩展名`() {
        assertEquals("截图1.png", imageName(0, "image/png"))
        assertEquals("截图2.jpg", imageName(1, "image/jpeg"))
    }

    // ---- 校验 ----

    @Test
    fun `原图超 8MB 给得出原因，正常图放行`() {
        assertNull(imageRejectReason(1024 * 1024))
        val reason = imageRejectReason(9 * 1024 * 1024)
        assertNotNull(reason)
        assertTrue(reason!!.contains("9MB"), "理由里要带上是多大：$reason")
    }

    @Test
    fun `拖进来的文件名按扩展名认`() {
        assertTrue(looksLikeImageFile("bug.PNG"))
        assertTrue(looksLikeImageFile("C:\\a\\b\\截图.jpeg"))
        assertTrue(!looksLikeImageFile("notes.md"))
        assertTrue(!looksLikeImageFile("没有扩展名"))
    }

    // ---- 端到端 ----

    @Test
    fun `一张 4K 横图能被自己缩到能发为止`() {
        // 用户粘一张 4K 截图，不该换来一句"太大了，先裁一下"—— 那是我们该干的活
        val attachment = prepareAttachment(noisy(3000, 2000), index = 0, sourceBytes = 2_000_000)

        assertNotNull(attachment)
        val a = attachment!!
        // 名字跟着**编出来的**格式走：噪声图会走 JPEG，于是叫截图1.jpg。
        // 写死 .png 就错了（第一次跑就是这条红的）
        assertEquals(imageName(0, a.mediaType), a.name)
        assertTrue(a.name.startsWith("截图1."), "名字要能认出序号：${a.name}")
        assertTrue(a.payloadBytes in 1..MAX_PAYLOAD_BYTES, "过桥体积 ${a.payloadBytes}")
        assertEquals(THUMB_W, a.thumb.width)
        // 长边必须真的落到上限内（CLI 那边 2000 就开始报错）
        val decoded = ImageIO.read(a.bytes.inputStream())
        assertTrue(maxOf(decoded.width, decoded.height) <= MAX_EDGE)
    }

    @Test
    fun `超限的原图连编都不编`() {
        assertNull(prepareAttachment(solid(400, 300), index = 0, sourceBytes = 9 * 1024 * 1024))
    }

    @Test
    fun `拖进来的文件保留自己的名字`() {
        val a = prepareAttachment(solid(400, 300), index = 0, name = "bug.png")

        assertEquals("bug.png", a?.name)
    }

    // ---- 给转写区的那份（2026-09-15）----

    @Test
    fun `转写区那份是缩过的 data URL，不是原图`() {
        // 计划里点名要量的一步：原图几 MB，而它要过一趟 JCEF 的 executeJavaScript、
        // 还要常驻页面内存。这张**压不动的噪声图**是最坏情况
        val attachment = prepareAttachment(noisy(3000, 2000), index = 0)!!
        val url = attachment.transcriptDataUrl

        assertTrue(url.startsWith("data:image/jpeg;base64,"), "给的必须是能直接塞进 img.src 的：${url.take(30)}")
        val bytes = java.util.Base64.getDecoder().decode(url.substringAfter(','))
        assertTrue(bytes.size < 400_000, "一份 ${bytes.size} 字节，过桥太重了")

        val decoded = ImageIO.read(bytes.inputStream())
        assertTrue(maxOf(decoded.width, decoded.height) <= TRANSCRIPT_EDGE)
    }

    @Test
    fun `一份原图只编一次 —— 转写区那份在粘贴那一刻就做好了`() {
        // 发送是热路径：那时再解码+缩放会在按回车的一瞬间卡一下（四张图几百毫秒）
        val a = prepareAttachment(solid(2000, 1200), index = 0)!!

        assertTrue(a.transcriptDataUrl.isNotEmpty(), "没做的话字段是空的，转写区就没图可画")
    }
}
