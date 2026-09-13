/**
 * 采集层：三入口（剪贴板、拖入的文件、粘贴的文件）的原始字节。
 *
 * 本层只做两件事：认出图、把字节拿出来。**判不出来的静默跳过，绝不抛错** ——
 * 用户拖一把文件进来时，其中一个读不出来不该让整批失败。
 *
 * 两条边界在这里划死：
 * - **0 字节不算采到**。空字节最后会变成 `ImageAttachment(mediaType, "")`，
 *   一个空 `data` 的图片块会让 API 拒掉**整条消息** —— 那正是本层最该避免的事。
 * - **大小不在这里判**。5MB 上限交给 `acceptImages` 去拒并给出"单张超过 5MB"的提示；
 *   若在这里静默丢掉，那条提示就再也不会出现（spec §8：拒绝必须可见）。
 *   代价是超限文件仍会被读进内存，这个峰已记进 ledger 交给最终审查。
 */
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
    // 空字节不算采到：一个 0 字节的图会让整条消息被 API 拒掉（见文件头）
    val bytes = image.toPngBytes()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return listOf(RawImage(bytes, "clipboard.png"))
}

/** 从文件读。非图片、空文件、读不出来、目录都跳过 —— 拖了一堆东西进来时不该整批失败。 */
internal fun readImageFiles(files: List<File>): List<RawImage> = files.mapNotNull { f ->
    if (!f.isFile || !isImageFile(f.name)) return@mapNotNull null
    // 只跳 0 字节。**大小不在这里判**：那会让 5MB 以上被静默丢掉，
    // 而 acceptImages 的"单张超过 5MB"提示正是要让它可见（spec §8）
    if (f.length() == 0L) return@mapNotNull null
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
