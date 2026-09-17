package com.ccoder.ui

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 待发的图片：缩放、编码、命名。
 *
 * 纯逻辑，**不碰剪贴板也不碰 Swing**（喂进来的是 [BufferedImage]）—— 与
 * [SendQueue]、[MainButtonState] 同一个分工：能单测的部分与只能出图看的部分分开。
 *
 * ## 上限是从 CLI 里读出来的，不是拍的
 *
 * `claude.exe` 内嵌 JS 里那两条：
 * ```
 *   rk  = { maxWidth: 2000, maxHeight: 2000, maxBase64Size: 5242880,
 *           targetRawSize: 3932160 }
 *   pxPerToken = 28, 目标长边 1568
 * ```
 * 也就是说：边长超 2000 或 base64 超 5MB，CLI 直接回
 * 「Image was too large. Try resizing the image…」——**用户粘一张 4K 截图不该
 * 换来这句话**，该缩的是我们。而 token 数是 `ceil(w/28)*ceil(h/28)`：
 * 一张 1568 宽的截图 ≈ 1800 token，所以"顺手缩一下"同时也是省钱。
 */

/** 一条消息最多几张图。CLI 侧没有条数上限，这个数是**我们**定的：4 张覆盖"这张加那张"的真实用法。 */
internal const val MAX_IMAGES = 4

/** 长边缩到这个数。理由见文件头：CLI 的目标长边就是它，再大不会更清楚，只会更贵。 */
internal const val MAX_EDGE = 1568

/** 原图超过这个字节数**不接**（8MB 已经是 4K 全屏 PNG 的两倍）。 */
internal const val MAX_SOURCE_BYTES = 8 * 1024 * 1024

/** 编码后 base64 超过它就拒绝 —— CLI 的 maxBase64Size。 */
internal const val MAX_PAYLOAD_BYTES = 5_242_880

/** CLI 的 targetRawSize：PNG 超过它就转 JPEG，省下来的是用户的钱。 */
private const val RAW_TARGET_BYTES = 3_936_216

/**
 * 推给转写区那份图的长边（见 [transcriptDataUrl]）。
 *
 * 900 是"点开放大看得清"与"过 JCEF 桥别太沉"之间的折中：面板也就 420px 宽，
 * 900 在放大浮层里已经比屏幕还宽了。
 */
internal const val TRANSCRIPT_EDGE = 900

/** JPEG 质量。与 CLI 自己那一档（`Aw = 85`）对齐，别自创一个数。 */
private const val JPEG_QUALITY = 0.85f

/** 缩略图尺寸。与设计稿一致：一条 56px 高的带子，横竖图都占同一个方块。 */
internal const val THUMB_W = 56
internal const val THUMB_H = 42

/**
 * 缩略图右上角那个 ✕ 的边长。
 *
 * 14 是"手指头够得着"与"别把缩略图本尊挡住"之间的折中：再小点不准（它压在 56px 的
 * 图上），再大就把内容压掉一块、认不出是哪张截图了。
 */
internal const val BADGE_SIDE = 14

/**
 * 一张待发的图。
 *
 * [bytes] 是**已经缩过、可以直接发**的那份（见 [prepareAttachment]）；[thumb] 只给界面；
 * [base64] 在这里算一次缓存住 —— 排队之后 flush 时还要再发一次，别每次重算。
 */
internal class AttachedImage(
    val mediaType: String,
    val bytes: ByteArray,
    val thumb: BufferedImage,
    val name: String,
    /** 给转写区看的那份（data URL）。发给 Claude 的不是它，见 [transcriptDataUrl]。 */
    val transcriptDataUrl: String = "",
) {
    val base64: String = Base64.getEncoder().encodeToString(bytes)

    /** 过桥时的实际体积（JSON 里放的就是这个字符串）。 */
    val payloadBytes: Int get() = base64.length
}

/**
 * 推给转写区的那份图（data URL）。
 *
 * **不是原图**：原图可能 3MB，而这一份要过一趟 JCEF 的 `executeJavaScript`、
 * 还要常驻页面内存（排版探针里量过：一张 3000px 的截图原样推过去，光字符串
 * 就是几 MB）。长边缩到 [TRANSCRIPT_EDGE] 就够点开放大看了。
 * **发给 CLI 的仍是原尺寸那份** —— 这一份只给眼睛。
 *
 * 用 JPEG 而不是 PNG：这一份的任务是"认出是哪张、点开看得清"，截图的文字在
 * 900px 缩到面板宽度之后，q85 的振铃看不出来，而体积差好几倍。
 */
internal fun transcriptDataUrl(bytes: ByteArray, maxEdge: Int = TRANSCRIPT_EDGE): String {
    val decoded = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return ""
    val scaled = fitToLimit(decoded, maxEdge)
    val jpeg = encode(toRgb(scaled), "jpeg", JPEG_QUALITY)
    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)
}

/**
 * 点开看图那一份：**把将要发出去的那份字节直接解回来**（2026-09-17 起输入框里
 * 点缩略图会走到这儿）。
 *
 * 不是 [AttachedImage.thumb]（56px，放大就是糊的）、也不是 [transcriptDataUrl]
 * （那是 JPEG q85，还要再过一次 base64）：放大查看同时就是**发送前的最后一眼**，
 * 该看的正是真正要发的那一份。
 *
 * 解不开返回 null —— 字节是我们自己刚编出来的，理论上不该发生；真发生了调用方
 * 给一句"打不开"，别让用户对着一个空白框猜。
 */
internal fun previewImageOf(bytes: ByteArray): BufferedImage? =
    runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()

/**
 * 这张来源能不能收。null = 可以；否则是一句能直接显示给用户的原因。
 *
 * 只判**原图体积**这一条：格式认不认得出、解出来多大，都要等解完才知道，
 * 那是 [prepareAttachment] 的事。
 */
internal fun imageRejectReason(sourceBytes: Int): String? = when {
    sourceBytes > MAX_SOURCE_BYTES ->
        "这张图太大了（${sourceBytes / 1024 / 1024}MB），先裁一下再粘"

    else -> null
}

/** 拖进来的是不是图片文件。按扩展名认 —— 真正的判据是能不能解码，那要在读完之后。 */
internal fun looksLikeImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in
        setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/**
 * 等比缩到长边 ≤ [maxEdge]。
 *
 * **已经够小就原样返回同一个对象**：重画一次就掉一次锐度，而"大多数截图本来就在
 * 1568 以内"是常态，不该为它们付这个代价（单测钉着这一条）。
 */
internal fun fitToLimit(img: BufferedImage, maxEdge: Int = MAX_EDGE): BufferedImage {
    val longEdge = max(img.width, img.height)
    if (longEdge <= maxEdge) return img
    val scale = maxEdge.toDouble() / longEdge
    return scaleTo(
        img,
        max(1, (img.width * scale).roundToInt()),
        max(1, (img.height * scale).roundToInt()),
    )
}

/**
 * 编码成能发出去的那份，返回 (mediaType, bytes)。
 *
 * **PNG 优先**：截图是界面，文字边缘只有 PNG 干净；只有超过 CLI 的 targetRawSize 才
 * 退到 JPEG（质量 85，与 CLI 自己那档一致）。转 JPEG 时必须先去 alpha ——
 * JPEG 编码器遇到带 alpha 的图会写出偏色的结果，这是 ImageIO 的老毛病。
 */
internal fun encodeForApi(img: BufferedImage): Pair<String, ByteArray> {
    val png = encode(img, "png")
    if (png.size <= RAW_TARGET_BYTES) return "image/png" to png
    val jpeg = encode(toRgb(img), "jpeg", JPEG_QUALITY)
    // 万一 PNG 反而更小（纯色大图会），就用小的那个
    return if (jpeg.size < png.size) "image/jpeg" to jpeg else "image/png" to png
}

/**
 * 从一张位图做出可发送的附件。**自己缩到能发为止**。
 *
 * 只缩到 [MAX_EDGE] 还不够：一张 1568px 的彩色噪声 PNG 可以超过 5MB。所以这里
 * 带一轮回退（每轮再降三分之一），三档之后仍超才放弃 —— 那时候返回 null，
 * 调用方给一句提示。用户粘一张 4K 截图不该看到这句话。
 */
internal fun prepareAttachment(
    source: BufferedImage,
    index: Int,
    sourceBytes: Int = 0,
    name: String? = null,
): AttachedImage? {
    if (imageRejectReason(sourceBytes) != null) return null
    var edge = MAX_EDGE
    repeat(3) {
        val scaled = fitToLimit(source, edge)
        val (mediaType, bytes) = encodeForApi(scaled)
        val base64 = Base64.getEncoder().encodeToString(bytes)
        if (base64.length <= MAX_PAYLOAD_BYTES) {
            return AttachedImage(
                mediaType = mediaType,
                bytes = bytes,
                thumb = thumbOf(scaled),
                name = name ?: imageName(index, mediaType),
                // 给转写区的那份在**这一刻**做掉：发送是热路径，那时再解码+缩放
                // 会在按回车的一瞬间卡一下（四张图能到几百毫秒）
                transcriptDataUrl = transcriptDataUrl(bytes),
            )
        }
        edge = edge * 2 / 3
    }
    return null
}

/** 附件带上那个名字。粘进来的图没有名字，就按序号叫「截图N」。 */
internal fun imageName(index: Int, mediaType: String): String {
    val ext = if (mediaType == "image/jpeg") "jpg" else "png"
    return "截图${index + 1}.$ext"
}

/**
 * 附件带上那张小图：**等比放大到铺满 [w]×[h] 再居中裁**，不是"缩进框里"。
 *
 * 一排缩略图必须等大，否则参差不齐；截图多是 16:9，塞进 4:3 的框会留下两条黑边，
 * 那比裁掉两侧更不像"一张图"。缩略图的职责是让人认出"这是我刚截的那张"，
 * 不是当证据 —— 完整的那份原样发给了 Claude。
 */
internal fun thumbOf(img: BufferedImage, w: Int = THUMB_W, h: Int = THUMB_H): BufferedImage {
    val scale = max(w.toDouble() / img.width, h.toDouble() / img.height)
    val scaledW = max(w, (img.width * scale).roundToInt())
    val scaledH = max(h, (img.height * scale).roundToInt())
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, (w - scaledW) / 2, (h - scaledH) / 2, scaledW, scaledH, null)
    } finally {
        g.dispose()
    }
    return out
}

/**
 * 画一张图。出口统一是 [BufferedImage.TYPE_INT_RGB]：
 * 截图没有 alpha，而每多一个通道就多 1/3 的体积（还要按 base64 再涨 1/3）。
 */
private fun scaleTo(img: BufferedImage, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, 0, 0, w, h, null)
    } finally {
        g.dispose()
    }
    return out
}

private fun toRgb(img: BufferedImage): BufferedImage =
    if (img.type == BufferedImage.TYPE_INT_RGB) img else scaleTo(img, img.width, img.height)

private fun encode(img: BufferedImage, format: String, quality: Float? = null): ByteArray {
    val out = ByteArrayOutputStream()
    if (quality == null) {
        ImageIO.write(img, format, out)
        return out.toByteArray()
    }
    val writer = ImageIO.getImageWritersByFormatName(format).next()
    try {
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.write(null, IIOImage(img, null, null), param)
        }
    } finally {
        writer.dispose()
    }
    return out.toByteArray()
}
