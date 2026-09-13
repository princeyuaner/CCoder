package com.ccoder.ui

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 按图片处理的扩展名。
 *
 * webp 在列，但 Java 原生**解不开**它 —— 归一化会原样放行（见 normalizeImage），
 * 由 Claude 自己认。这不是遗漏，是唯一不丢图的做法。
 */
internal val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

internal fun isImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/**
 * 剪贴板里有没有图。
 *
 * **有图优先**是刻意的：截图工具（Win+Shift+S、Snipaste、微信）基本只放图。
 * 代价是"从 Excel 复制表格"会粘成一张图而不是文本 —— 需要手动删一下，
 * 这个取舍记在 spec §4。
 */
internal fun clipHasImage(flavors: List<DataFlavor>): Boolean =
    flavors.any { it == DataFlavor.imageFlavor }

/** 从剪贴板取图。取不到就返回空表，不抛。 */
internal fun readClipboardImages(): List<RawImage> {
    val contents = runCatching { CopyPasteManager.getInstance().getContents<Any>(DataFlavor.imageFlavor) }
        .getOrNull() ?: return emptyList()
    val image = contents as? Image ?: return emptyList()
    val bytes = image.toPngBytes() ?: return emptyList()
    return listOf(RawImage(bytes, "clipboard.png"))
}

/** 从文件读。非图片、读不出来、目录都跳过 —— 拖了一堆东西进来时不该整批失败。 */
internal fun readImageFiles(files: List<File>): List<RawImage> = files.mapNotNull { f ->
    if (!f.isFile || !isImageFile(f.name)) return@mapNotNull null
    runCatching { RawImage(f.readBytes(), f.name) }.getOrNull()
}

/**
 * `java.awt.Image` → PNG 字节。
 *
 * 走 `MediaTracker` 等它加载完：剪贴板给的常常是个还没解码完的懒加载 Image，
 * 直接画会得到一张空白图。
 */
private fun Image.toPngBytes(): ByteArray? = runCatching {
    val tracker = java.awt.MediaTracker(java.awt.Label())
    tracker.addImage(this, 0)
    tracker.waitForID(0)

    val w = getWidth(null)
    val h = getHeight(null)
    if (w <= 0 || h <= 0) return null

    val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = buffered.createGraphics()
    g.drawImage(this, 0, 0, null)
    g.dispose()

    val out = ByteArrayOutputStream()
    ImageIO.write(buffered, "png", out)
    out.toByteArray()
}.getOrNull()
