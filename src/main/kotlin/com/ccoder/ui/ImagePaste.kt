package com.ccoder.ui

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.TransferHandler
import javax.swing.text.JTextComponent
import kotlin.math.max

/**
 * 一张刚被粘/拖进来的图。
 *
 * [sourceBytes] 只对**文件**有意义（用来判"这张太大了"）；剪贴板上的位图没有字节数，
 * 给 0 —— 尺寸那条路交给 [prepareAttachment] 的回退去缩。
 * [name] 同理：拖进来的文件有自己的名字（bug.png），粘进来的没有（[imageName] 兜底）。
 */
internal data class IncomingImage(
    val image: BufferedImage,
    val sourceBytes: Int,
    val name: String?,
)

/**
 * 这次传输该不该当"贴图"处理。两条规则都是用户视角的：
 *
 * - **粘（paste）时文字优先**：剪贴板里同时有文字和位图时（从网页上复制一段带图的内容
 *   就是这样），用户十有八九要的是文字。粘一段代码结果多出一张图，是帮倒忙。
 * - **拖（drop）时图优先**：从项目树/资源管理器拖一张 `.png` 进来，想要的就是那张图，
 *   而不是它的路径被当文本插进来。非图片文件仍然走老路（插路径）。
 *
 * 抽成纯函数是为了可测：拖与粘的分支在真机上很难手动覆盖（要真的去拖），
 * 而"文字优先"这条规则一旦写反，用户每次粘代码都会多出一张图。
 */
internal fun attachImagesWanted(flavors: Collection<DataFlavor>, isDrop: Boolean): Boolean {
    val hasImage = flavors.any { it == DataFlavor.imageFlavor }
    val hasFiles = flavors.any { it == DataFlavor.javaFileListFlavor }
    if (!hasImage && !hasFiles) return false
    val hasText = flavors.any { it == DataFlavor.stringFlavor }
    return isDrop || !hasText
}

/**
 * 给输入框装上"图能粘进来"。
 *
 * **必须留着原来的处理器**：`JTextComponent` 只有一个 `transferHandler`，换掉之后
 * 文字粘贴（Ctrl+V 一段代码）就得我们自己实现一遍 —— 而它比看上去麻烦得多
 * （选区替换、撤销栈、输入法）。所以这里包一层：图归我们，其余原样转交。
 *
 * 拿不到原处理器就**不装**：装上去等于把文字粘贴弄丢，那比不支持贴图严重。
 */
internal fun installImagePaste(
    area: JTextComponent,
    onImages: (List<IncomingImage>) -> Unit,
) {
    val fallback = area.transferHandler ?: return
    area.transferHandler = ImagePasteHandler(fallback, onImages)
}

/**
 * 包在默认处理器外面那层。
 *
 * `canImport` 的两条路要一起看：我们的判定说"不接"时，**不能**直接返回 false ——
 * 那会让这一次粘贴彻底没反应（TransferHandler 只有一个，没有"下一个"）。
 * 必须转交给原来那个。
 */
private class ImagePasteHandler(
    private val fallback: TransferHandler,
    private val onImages: (List<IncomingImage>) -> Unit,
) : TransferHandler() {

    override fun canImport(support: TransferSupport): Boolean =
        attachImagesWanted(support.dataFlavors.toList(), support.isDrop) ||
            fallback.canImport(support)

    override fun importData(support: TransferSupport): Boolean {
        if (attachImagesWanted(support.dataFlavors.toList(), support.isDrop)) {
            val images = readImages(support.transferable)
            if (images.isNotEmpty()) {
                onImages(images)
                return true
            }
        }
        // 认得出是文件但解不出图（.psd、坏文件）也走这里 —— 与"不是图片"一个待遇：
        // 让默认行为去处理，用户至少能看到路径进来了
        return fallback.importData(support)
    }
}

/** 从传输里把图抠出来。解不开的**跳过而不是抛** —— 拖进来一堆文件时不该整个失败。 */
private fun readImages(transferable: Transferable): List<IncomingImage> {
    val out = mutableListOf<IncomingImage>()

    if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        val raw = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) }.getOrNull()
        (raw as? Image)?.let { out += IncomingImage(toBuffered(it), sourceBytes = 0, name = null) }
    }

    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val raw = runCatching {
            transferable.getTransferData(DataFlavor.javaFileListFlavor)
        }.getOrNull() as? List<*>
        for (file in raw.orEmpty().filterIsInstance<File>()) {
            if (!looksLikeImageFile(file.name)) continue
            val img = runCatching { ImageIO.read(file) }.getOrNull() ?: continue
            out += IncomingImage(img, sourceBytes = file.length().toInt(), name = file.name)
        }
    }

    return out
}

/** 剪贴板上的东西可能是任何 `Image` 实现；统一成 [BufferedImage]，不然没法缩放与编码。 */
private fun toBuffered(image: Image): BufferedImage {
    if (image is BufferedImage) return image
    val w = max(1, image.getWidth(null))
    val h = max(1, image.getHeight(null))
    return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply {
            drawImage(image, 0, 0, null)
            dispose()
        }
    }
}
