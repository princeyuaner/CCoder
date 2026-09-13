package com.ccoder.ui

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** 一张准备发送 / 已经发送的图。存 base64 而不是字节：协议、转写区契约、缩略图三条路最终要的都是它。 */
internal data class ImageAttachment(val mediaType: String, val base64: String)

/** 刚采到、还没归一化的图。[name] 只用来推断媒体类型（剪贴板没有文件名时用 "image.png"）。 */
internal data class RawImage(val bytes: ByteArray, val name: String)

/** 一次收图的结果。[reason] 给界面拼提示行用。 */
internal data class ImageIntake(
    val accepted: List<ImageAttachment>,
    val rejected: Int,
    val reason: String?,
)

/** 单张原始字节上限。base64 后约 6.7MB，NDJSON 单行撑得住；再大就该用户自己压。 */
internal const val MAX_RAW_BYTES = 5 * 1024 * 1024

/** 单条消息张数上限。 */
internal const val MAX_IMAGES = 5

/**
 * 缩放后的长边上限。
 *
 * 取 1568 而不是 Claude Code 自己用的 2000：1568 是 API 的视觉最优长边，
 * 超过它只会被服务端再缩一次，token 却照算。既然要缩，就一步缩到位。
 */
internal const val MAX_EDGE = 1568

/** 归一化之后的 base64 上限，超了逐档降质。 */
internal const val MAX_BASE64_CHARS = 1_500_000

private const val JPEG_QUALITY = 0.85
private val FALLBACK_QUALITIES = listOf(0.6f, 0.4f)

internal fun mediaTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "webp" -> "image/webp"
    else -> "image/png"
}

/**
 * 归一化。
 *
 * 三步：缩放（长边 > [MAX_EDGE]）→ 编码（源 JPEG 出 JPEG，其余出 PNG）→ 超限逐档降质。
 * 重编码比原图还大、**又没超限**时才回退原图 —— 那说明重编码得不偿失。
 * 超限时不能回退：那恰恰是最该降质的时刻。解不开的字节（webp 等）原样放行 ——
 * 宁可让 Claude 自己认，也不要在这里把图丢掉。
 */
internal fun normalizeImage(raw: ByteArray, hintMediaType: String): ImageAttachment {
    val decoded = runCatching { ImageIO.read(raw.inputStream()) }.getOrNull()
        ?: return ImageAttachment(hintMediaType, raw.b64())

    val scaled = scaleToMaxEdge(decoded, MAX_EDGE)
    val hasAlpha = scaled.colorModel.hasAlpha()
    val sourceIsJpeg = hintMediaType == "image/jpeg" && !hasAlpha

    // 先按源的格式编。编不出来，或者编出来比原图还大、**又没超限**，都回退原图 ——
    // 后者说明重编码得不偿失。注意这里不能无条件回退：超限恰恰是最该降质的时刻
    var best = encode(scaled, jpeg = sourceIsJpeg)
        ?: return ImageAttachment(hintMediaType, raw.b64())
    if (best.bytes.size >= raw.size && best.bytes.b64Length <= MAX_BASE64_CHARS) {
        return ImageAttachment(hintMediaType, raw.b64())
    }

    // 超限就逐档降质。**没有 alpha 时允许转 JPEG** —— PNG 没有"降质"这个旋钮，
    // 转格式是它唯一的路。有 alpha 的不能转：透明区会变成黑块
    if (!hasAlpha) {
        for (quality in FALLBACK_QUALITIES) {
            if (best.bytes.b64Length <= MAX_BASE64_CHARS) break
            best = encode(scaled, jpeg = true, quality = quality) ?: break
        }
    }

    // 格式跟着**实际**编码走：把 PNG 降成了 JPEG 却还报 PNG，Claude 会按 PNG 去解。
    // 这里不能回落到 hint —— `encode(jpeg = false)` 写出来的**永远是 PNG 字节**，
    // 源是 gif / bmp 时 hint 说的是假话（拖一张 gif 进来就会撞上）。
    // 两条早退返回的是没动过的原始字节，那时 hint 才是对的，保持原样。
    // 仍超限也照发（spec §8：宁可不省，不丢图）
    val mediaType = if (best.jpeg) "image/jpeg" else "image/png"
    return ImageAttachment(mediaType, Base64.getEncoder().encodeToString(best.bytes))
}

private class Encoded(val bytes: ByteArray, val jpeg: Boolean)

/**
 * 标准（带填充、不换行）base64 的长度，与 [Base64.getEncoder] 的输出一致。
 *
 * [MAX_BASE64_CHARS] 是按**字符**定的，所以拿字节数去比会比自己以为的宽松 4/3 ——
 * 实测：噪声图降到 q0.6 得到 116 万字节，看着"没超"，编成 base64 却有 155 万字符。
 */
private val ByteArray.b64Length: Int get() = (size + 2) / 3 * 4

private fun encode(img: BufferedImage, jpeg: Boolean, quality: Float = JPEG_QUALITY.toFloat()): Encoded? {
    val out = ByteArrayOutputStream()
    return runCatching {
        if (!jpeg) {
            ImageIO.write(img, "png", out)
        } else {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            ImageIO.createImageOutputStream(out).use { writer.output = it; writer.write(null, IIOImage(img, null, null), param) }
        }
        Encoded(out.toByteArray(), jpeg)
    }.getOrNull()
}

private fun scaleToMaxEdge(img: BufferedImage, maxEdge: Int): BufferedImage {
    val longEdge = maxOf(img.width, img.height)
    if (longEdge <= maxEdge) return img

    val ratio = maxEdge.toDouble() / longEdge
    val w = (img.width * ratio).toInt().coerceAtLeast(1)
    val h = (img.height * ratio).toInt().coerceAtLeast(1)
    val out = BufferedImage(w, h, if (img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    return out
}

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

/**
 * 收图策略：先按大小过、再按张数截。
 *
 * **拒绝要给出理由**：粘了 6 张只发 5 张却不吭声，用户会以为全发出去了。
 */
internal fun acceptImages(existingCount: Int, incoming: List<RawImage>): ImageIntake {
    val accepted = mutableListOf<ImageAttachment>()
    var rejected = 0
    var reason: String? = null

    for (raw in incoming) {
        if (raw.bytes.size > MAX_RAW_BYTES) {
            rejected++
            reason = reason ?: "单张超过 5MB"
            continue
        }
        if (existingCount + accepted.size >= MAX_IMAGES) {
            rejected++
            reason = reason ?: "一次最多 5 张"
            continue
        }
        accepted += normalizeImage(raw.bytes, mediaTypeOf(raw.name))
    }
    return ImageIntake(accepted, rejected, reason)
}
