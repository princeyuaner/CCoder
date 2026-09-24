package com.ccoder.ui

import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.TransferHandler
import javax.swing.TransferHandler.TransferSupport
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
 * **"有文件"那一半不归这条管**（2026-09-24）：粘贴一批文件走的是 [pasteFilesWanted]
 * → [routeIncomingFiles]，那里的判据是"有文件就接下"（复制文件时本来就会带一份
 * 路径文本，按"文字优先"判等于永不触发）。拖拽那条线没动，仍是老路。
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
 * 给输入框装上"粘贴能收东西"：**图**（截图）进附件带，**文件**（复制来的）
 * 走附件按钮那套分流（2026-09-24 加了后者，见 `FilePaste.kt`）。
 *
 * **必须留着原来的处理器**：`JTextComponent` 只有一个 `transferHandler`，换掉之后
 * 文字粘贴（Ctrl+V 一段代码）就得我们自己实现一遍 —— 而它比看上去麻烦得多
 * （选区替换、撤销栈、输入法）。所以这里包一层：图与文件归我们，其余原样转交。
 *
 * 拿不到原处理器就**不装**：装上去等于把文字粘贴弄丢，那比不支持贴图严重。
 */
internal fun installComposerPaste(
    area: JTextComponent,
    onImages: (List<IncomingImage>) -> Unit,
    onMentions: (List<String>) -> Unit,
    /** 纯文本路径那条要用（见 [filePathsInText]）：认不出路径就交回默认粘贴。 */
    resolve: FileResolver = { null },
) {
    val fallback = area.transferHandler
    if (fallback == null) {
        LOG.warn("输入框没有默认的 TransferHandler —— 粘贴不接（保住文字粘贴更要紧）")
        return
    }
    area.transferHandler = ImagePasteHandler(fallback, onImages, onMentions)
    // 还有第二条路：**平台的粘贴**（见 composerPasteProvider）。IDE 里 Ctrl+V 根本不
    // 走 Swing 的 TransferHandler —— keymap 把 `$Paste` 交给平台的 paste action，
    // 而按平台自己的实现，它是从数据上下文里取 PasteProvider 再调。两条都接上，
    // 谁先到都能用（TransferHandler 那条还兼着拖拽）
    (area as? ComposerTextArea)?.let {
        it.pasteProvider = composerPasteProvider(onImages, onMentions, resolve)
        // 自己那条 Ctrl+V（见 ComposerPasteAction）：平台那条在焦点上有一堆前提
        it.pasteAttach = { pasteFromClipboard(onImages, onMentions, resolve) }
    }
    LOG.info("粘贴已接线：默认处理器是 ${fallback.javaClass.name}，平台 PasteProvider 也挂上了")
}

/**
 * 包在默认处理器外面那层。
 *
 * `canImport` 的两条路要一起看：我们的判定说"不接"时，**不能**直接返回 false ——
 * 那会让这一次粘贴彻底没反应（TransferHandler 只有一个，没有"下一个"）。
 * 必须转交给原来那个。
 *
 * ## 每一条分支都要留话（2026-09-15 的教训）
 *
 * 第一版这里三处静默返回（装不上、判定不接、读不出来），结果真机上"按 Ctrl+V
 * 什么都没发生"，而**日志里一个字都没有** —— 只能靠一个独立 Swing 小程序对着
 * 真剪贴板复现才定位到。贴图这条路的每一步现在都留 INFO/WARN。
 */
private class ImagePasteHandler(
    private val fallback: TransferHandler,
    private val onImages: (List<IncomingImage>) -> Unit,
    private val onMentions: (List<String>) -> Unit,
) : TransferHandler() {

    override fun canImport(support: TransferSupport): Boolean {
        val flavors = imageSource(support)?.transferDataFlavors?.toList() ?: emptyList()
        // 文件那条与图那条是**两条**判定，不能合成一个"要不要"：粘贴一批文件时
        // 可能也带位图，而那时该走文件那条（分流更全，见 FilePaste.kt）
        val want = pasteFilesWanted(flavors, support.isDrop) || attachImagesWanted(flavors, support.isDrop)
        // 拖拽时这个方法会被调很多次，只记"要接"和"粘贴"这两种有信息量的
        if (want || !support.isDrop) {
            LOG.info("粘贴判定：isDrop=${support.isDrop} → ${if (want) "接下" else "交回默认"}（$flavors）")
        }
        return want || fallback.canImport(support)
    }

    override fun importData(support: TransferSupport): Boolean {
        val source = imageSource(support)
        val flavors = source?.transferDataFlavors?.toList() ?: emptyList()
        // 粘贴 + 有一批文件：走附件按钮那套分流（图进附件带，其余变 @ 引用）
        if (pasteFilesWanted(flavors, support.isDrop)) {
            val paths = source?.let(::filePathsFrom) ?: emptyList()
            if (paths.isNotEmpty()) {
                LOG.info("粘贴：读到 ${paths.size} 个文件")
                routeIncomingFiles(paths, onImages, onMentions)
                return true
            }
            LOG.warn("粘贴：判定该接文件却一个都没读到 —— 交回默认处理器")
        }
        if (attachImagesWanted(flavors, support.isDrop)) {
            val images = source?.let { readFromTransferable(it) } ?: emptyList()
            LOG.info("贴图读取：拿到 ${images.size} 张（isDrop=${support.isDrop}）")
            if (images.isNotEmpty()) {
                onImages(images)
                return true
            }
            LOG.warn("贴图：判定该接图却一张都没读到 —— 交回默认处理器")
        }
        // 认得出是文件但解不出图（.psd、坏文件）也走这里 —— 与"不是图片"一个待遇：
        // 让默认行为去处理，用户至少能看到路径进来了
        return fallback.importData(support)
    }
}

/**
 * 这次传输该读哪一份数据。
 *
 * **粘贴时以平台剪贴板为准**（[CopyPasteManager]），不用 Swing 递进来的那份。
 * 2026-09-15 复现出来的坑：剪贴板是"延迟渲染"的时候，递给 importData 的那份
 * transferable **第一次问它有哪些 flavor 会什么都说没有**，再问一次才补齐 ——
 * 同一个进程里两次调用的差别仅此而已（换了个顺序打印就复现不出来了）。
 * 而判定只有一次机会：判成"不是图"就静默交回默认处理器，用户看到的就是
 * "按 Ctrl+V 什么都没发生"。
 *
 * 平台那个 facade 自带重试与缓存（`ClipboardSynchronizer`），是 IntelliJ 里读
 * 剪贴板的正路。拿不到 Application（无头单测）时退回 Swing 那份。
 *
 * 拖拽不走剪贴板 —— 数据就在这次传输里，用它自己的那份。
 */
private fun imageSource(support: TransferSupport): Transferable? {
    if (support.isDrop) return support.transferable
    return platformClipboard() ?: support.transferable
}

/**
 * 平台剪贴板那份 transferable。
 *
 * **整个包在 runCatching 里**：无头环境（单测、某些远程开发场景）摸剪贴板会抛
 * HeadlessException，而这里是 EDT 上的粘贴流程 —— 抛出去就是一个 IDE 报错弹窗。
 * 读不到就是"这次没图"，不是错误。
 */
private fun platformClipboard(): Transferable? {
    val app = ApplicationManager.getApplication() ?: return null
    return runCatching { app.getService(CopyPasteManager::class.java).getContents() }
        .onFailure { LOG.warn("贴图：读平台剪贴板失败", it) }
        .getOrNull()
}

/**
 * 回答平台的 `PlatformDataKeys.PASTE`。
 *
 * **只在"剪贴板里是图、而且没有文字"时才接管** —— 其余一律返回 null，让平台自己
 * 那个 provider 去处理。这样文字粘贴一个字都没变，也就不用把那套逻辑（选区替换、
 * 撤销栈）重写一遍。规则与 TransferHandler 那条路是同一条（见 [attachImagesWanted]）。
 *
 * **那个 context 参数用平台自带的 [DataContext.EMPTY_CONTEXT]，别自己实现 `DataContext`。**
 * 它标着 `@ApiStatus.NonExtendable`（插件里实现它就是"non-extendable API usage
 * violation"），而它那个 `getData(String)` 又是 `@ApiStatus.Internal`。2026-09-15：
 * 一个 6 行的 `EmptyDataContext` 同时触发了这两条红字，足以让市场审核不通过 ——
 * 平台本来就提供了空上下文，没有任何理由自己造一个。
 */
internal fun composerPasteData(dataId: String, provider: PasteProvider?): PasteProvider? =
    if (provider != null && PlatformDataKeys.PASTE_PROVIDER.`is`(dataId) &&
        provider.isPastePossible(DataContext.EMPTY_CONTEXT)
    ) {
        provider
    } else {
        null
    }

/**
 * 真正干活的 provider。挂在输入框上（它实现 [DataProvider]）。
 *
 * 收什么由 [takeover] 说了算（默认 [clipboardPasteTakeover]：有文件、或只有图），
 * 收下之后怎么分流由 [pasteFromClipboard] 说了算 —— 两条都在 `FilePaste.kt` 里。
 *
 * [takeover] 抽成参数只为可测：真机上这个是拿 [CopyPasteManager] 问的，
 * 而单测里没有 Application。
 */
internal fun composerPasteProvider(
    onImages: (List<IncomingImage>) -> Unit,
    onMentions: (List<String>) -> Unit,
    resolve: FileResolver = { null },
    takeover: () -> Boolean = { clipboardPasteTakeover(resolve) },
): PasteProvider = object : PasteProvider {
    override fun isPastePossible(dataContext: DataContext): Boolean = takeover()

    override fun isPasteEnabled(dataContext: DataContext): Boolean = takeover()

    override fun performPaste(dataContext: DataContext) {
        LOG.info("粘贴：平台的粘贴链上接手了这次粘贴")
        pasteFromClipboard(onImages, onMentions, resolve)
    }
}

/**
 * 剪贴板里是不是"只有图"（有图、没有文字）。
 *
 * 拿不到 Application（无头单测）或剪贴板不可用时按 false 处理 —— 也就是不接管，
 * 与"没有图"同一个待遇。
 */
internal fun clipboardImageOnly(): Boolean {
    val app = ApplicationManager.getApplication() ?: return false
    return runCatching {
        val cp = app.getService(CopyPasteManager::class.java)
        val hasImage = cp.areDataFlavorsAvailable(DataFlavor.imageFlavor)
        val hasText = cp.areDataFlavorsAvailable(DataFlavor.stringFlavor)
        // 只有"既有图又有文字"这种**本来会让人意外**的拒绝才留话（文字优先那条规则
        // 生效）：这条路上其它拒绝都是常态（剪贴板里就是段代码），记了全是噪音
        if (hasImage && hasText) {
            LOG.info("贴图：剪贴板里图和文字都有 —— 按文字优先交给平台")
        }
        hasImage && !hasText
    }.onFailure { LOG.warn("贴图：问平台剪贴板失败", it) }.getOrDefault(false)
}

/** 从平台剪贴板读图。读不到就是空列表，不抛。 */
internal fun readImagesFromPlatformClipboard(): List<IncomingImage> {
    val app = ApplicationManager.getApplication() ?: return emptyList()
    val image = runCatching {
        app.getService(CopyPasteManager::class.java).getContents<Image>(DataFlavor.imageFlavor)
    }.onFailure { LOG.warn("贴图：读平台剪贴板失败", it) }.getOrNull() ?: return emptyList()
    LOG.info("贴图：从平台剪贴板读到了 ${image.getWidth(null)}x${image.getHeight(null)}")
    return listOf(IncomingImage(toBuffered(image), sourceBytes = 0, name = null))
}

/**
 * 一个图片文件 → 一张待发的图。解不开（坏文件、PSD）或看名字就不是图就返回 null。
 *
 * **拖拽与附件按钮共用这一个**：两处各写一遍的话，"什么算图"迟早会分成两套判据
 * （拖进来的认、选进来的不认，或者反过来）。
 */
internal fun imageFromFile(file: File): IncomingImage? {
    if (!looksLikeImageFile(file.name)) return null
    val img = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
    return IncomingImage(img, sourceBytes = file.length().toInt(), name = file.name)
}

/** 从一份 transferable 里把图抠出来。解不开的**跳过而不是抛** —— 拖进来一堆文件时不该整个失败。 */
private fun readFromTransferable(transferable: Transferable): List<IncomingImage> {
    val out = mutableListOf<IncomingImage>()

    if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        // 失败要留话：第一版这里是静默的 runCatching，真机上出问题时无从下手
        val raw = runCatching { transferable.getTransferData(DataFlavor.imageFlavor) }
            .onFailure { LOG.warn("贴图：imageFlavor 认，但读不出来", it) }
            .getOrNull()
        (raw as? Image)?.let { out += IncomingImage(toBuffered(it), sourceBytes = 0, name = null) }
    } else {
        LOG.info("贴图：这份 transferable 不认 imageFlavor（flavors=${transferable.transferDataFlavors.toList()}）")
    }

    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
        val raw = runCatching {
            transferable.getTransferData(DataFlavor.javaFileListFlavor)
        }.getOrNull() as? List<*>
        for (file in raw.orEmpty().filterIsInstance<File>()) {
            imageFromFile(file)?.let { out += it }
        }
    }

    return out
}

private val LOG = Logger.getInstance("com.ccoder.ui.ImagePaste")

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
