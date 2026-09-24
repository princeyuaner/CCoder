package com.ccoder.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/**
 * 剪贴板里的**文件**：复制一个文件、在输入框里粘贴 → 变成 `@` 引用（2026-09-24）。
 *
 * 与左下角那颗附件按钮**同一套分流**（[routeIncomingFiles] 直接复用
 * [splitChosenFiles]）：图片进附件带，其余变成 `@相对路径`，内容由 CLI 自己展开
 * （`@` 这条是实测过的，见 `AddFileToChat.kt` 的头注）。
 *
 * ## 剪贴板上"文件"的三种样子
 *
 * 1. **真文件清单**（`javaFileListFlavor`）—— 资源管理器 / 桌面复制的那种
 * 2. **一段路径文本** —— IDE 里复制的就是这一种（项目树 Ctrl+C、右键 Copy Path）。
 *    平台压根没有"文件清单"这种内部格式（`FileCopyPasteUtil` 认的三种味道全是 OS 来的，
 *    这也解释了"IDE 里复制的文件粘不进资源管理器"），所以只能认文本，见 [filePathsInText]
 * 3. **纯图**（截图）—— 老功能，进附件带
 *
 * ## 为什么要把"文字优先"那条规矩绕开
 *
 * 贴图那条线有条规矩：**剪贴板里同时有文字和位图时，文字优先** —— 从网页上复制
 * 一段带图的内容时，用户要的是文字（见 [attachImagesWanted]）。
 *
 * 文件这条**不能照搬**：复制文件时那份 transferable **本来就会**回答 `stringFlavor`
 * （内容是路径清单，好让你能把它粘成文本）—— 按"有文字就让路"判，用户要的那个
 * 场景一次都不会触发。所以这里的判据是：**是路径就接下**（而路径的判据很窄，
 * 见 [filePathsInText]：每一行都得是盘上真实存在的文件）。
 *
 * 目录**跳过**：`@某目录` 对模型没有意义（附件按钮那个选择器本来也不让选目录），
 * 而且跳过的要写进日志 —— 静默吞掉会让人以为"复制的文件没进来"。
 */
private val LOG = Logger.getInstance("com.ccoder.ui.FilePaste")

/** 剪贴板里有没有一批文件（IDE 项目树 / 资源管理器里复制的那个形状）。 */
internal fun clipboardHasFiles(): Boolean {
    val app = ApplicationManager.getApplication() ?: return false
    return runCatching {
        app.getService(CopyPasteManager::class.java)
            .areDataFlavorsAvailable(DataFlavor.javaFileListFlavor)
    }.onFailure { LOG.warn("粘贴：问平台剪贴板失败", it) }.getOrDefault(false)
}

/**
 * 从平台剪贴板读那批文件（绝对路径）。
 *
 * 平台那个 facade 自带重试与缓存（`ClipboardSynchronizer`），是 IDE 里读剪贴板的
 * 正路 —— 同 [imageSource] 那条注释记的"延迟渲染"坑（第一遍问它什么都没有）。
 */
internal fun filePathsFromPlatformClipboard(): List<String> {
    val app = ApplicationManager.getApplication() ?: return emptyList()
    val contents = runCatching { app.getService(CopyPasteManager::class.java).getContents() }
        .onFailure { LOG.warn("粘贴：读平台剪贴板失败", it) }.getOrNull() ?: return emptyList()
    return filePathsFrom(contents)
}

/** 从一份 transferable 里读文件清单（拖拽 / Swing 那条路也用它）。读不出来就是空表，不抛。 */
internal fun filePathsFrom(transferable: Transferable): List<String> {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    val raw = runCatching { transferable.getTransferData(DataFlavor.javaFileListFlavor) }
        .onFailure { LOG.warn("粘贴：认了文件清单却读不出来", it) }
        .getOrNull() as? List<*> ?: return emptyList()

    val files = raw.filterIsInstance<File>()
    val (dirs, plain) = files.partition { it.isDirectory }
    if (dirs.isNotEmpty()) {
        LOG.info("粘贴：跳过 ${dirs.size} 个目录（${dirs.joinToString { it.name }}）")
    }
    return plain.map { it.absolutePath }
}

/**
 * "相对/绝对路径 → 真实存在的文件"，认不出返回 null。
 *
 * 由调用方注入（要 Project 算 basePath）。**纯文本那条路**（见 [filePathsInText]）
 * 靠它把"一段文本"判成"一串路径"。
 */
internal typealias FileResolver = (String) -> String?

/**
 * 项目根对上的解析器：绝对路径直接看盘，相对路径按项目根拼。**目录不算**（同 [filePathsFrom]）。
 */
internal fun projectFileResolver(project: com.intellij.openapi.project.Project?): FileResolver {
    val base = project?.basePath
    return { text ->
        val candidates = buildList {
            add(File(text))
            if (base != null && !File(text).isAbsolute) add(File(base, text))
        }
        candidates.firstOrNull { it.isFile }?.absolutePath
    }
}

/**
 * 这段**文本**是不是"一串文件路径"（2026-09-24）。
 *
 * ## 为什么需要它
 *
 * IDE 里复制文件（项目树 Ctrl+C、右键 Copy Path）**不产生文件清单** —— 平台从头到尾
 * 没把"一批文件"用 `javaFileListFlavor` 那种格式放过剪贴板（`FileCopyPasteUtil` 认的
 * 三种文件味道全是 OS 来的；这也是"IDE 里复制的文件粘不进资源管理器"的原因）。
 * 它在剪贴板上就是**一段路径文本**。所以想让那条路生效，只能认这段文本。
 *
 * ## 判据要窄（认错比漏认糟）
 *
 * - 逐行看，**空行丢掉**；行数上限 [MAX_PATH_LINES]
 * - 每行**整行**先试解析；不行再按空白切开、**每一段都要能解析**（项目树里多选复制的
 *   样子）；还不行 → 整段判否
 * - **任何一行判否，整段就判否** —— 一段正常的文本（代码、散文）里恰好有一行像路径，
 *   整段就会变成引用，那是帮倒忙
 *
 * 判否时返回 null，调用方**原样交回默认粘贴**（一个字节都不改）。
 */
internal fun filePathsInText(text: String, resolve: FileResolver): List<String>? {
    // 这个函数在动作的 update 里被调（Ctrl+V 随时候选），而剪贴板可能是万把字的正文 ——
    // 先按长度砍掉明显不可能的，别为一段散文做 50 次 exists()
    if (text.length > MAX_PATH_TEXT_CHARS) return null

    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty() || lines.size > MAX_PATH_LINES) return null

    val out = mutableListOf<String>()
    for (line in lines) {
        resolve(line)?.let {
            out += it
            continue
        }
        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val resolved = parts.map { resolve(it) ?: return null }
        out += resolved
    }
    return out.takeIf { it.isNotEmpty() }
}

/** 一段文本里最多认几条路径。再多就不像"复制了几个文件"了。 */
private const val MAX_PATH_LINES = 50

/** 路径清单的长度上限（[MAX_PATH_LINES] 条 × 每个 80 字还有富余）。超了就不是路径清单。 */
private const val MAX_PATH_TEXT_CHARS = 4000

/** 剪贴板上那段文本解析出来的路径（认不出就是 null）。 */
internal fun textPathsFromPlatformClipboard(resolve: FileResolver): List<String>? {
    val app = ApplicationManager.getApplication() ?: return null
    val text = runCatching {
        app.getService(CopyPasteManager::class.java).getContents<String>(DataFlavor.stringFlavor)
    }.getOrNull() ?: return null
    return filePathsInText(text, resolve)
}

/**
 * 这次粘贴该不该我们接。
 *
 * 三条，**从硬到软**：有文件清单（资源管理器）→ 有纯图（截图）→ 文本是一串真实存在的
 * 文件路径（IDE 里复制文件、Copy Path）。
 *
 * **文件优先于图**：复制一个 `.png` 文件时两种形状都沾（文件清单 + 可能还有位图），
 * 而"文件"那条路的结论更全 —— 它会把图片文件也放进附件带（见 [routeIncomingFiles]）。
 */
internal fun clipboardPasteTakeover(resolve: FileResolver = { null }): Boolean =
    clipboardHasFiles() || clipboardImageOnly() || textPathsFromPlatformClipboard(resolve) != null

/**
 * 收下这次粘贴。返回 true = 我们接下了（调用方不用再走原来那套）。
 *
 * 顺序：文件清单 → 文本路径 → 纯图（截图）。都不沾就返回 false，**交回默认粘贴**。
 */
internal fun pasteFromClipboard(
    onImages: (List<IncomingImage>) -> Unit,
    onMentions: (List<String>) -> Unit,
    resolve: FileResolver = { null },
): Boolean {
    val files = filePathsFromPlatformClipboard()
    if (files.isNotEmpty()) {
        routeIncomingFiles(files, onImages, onMentions)
        return true
    }
    val fromText = textPathsFromPlatformClipboard(resolve)
    if (fromText != null) {
        LOG.info("粘贴：剪贴板是一串路径（${fromText.size} 条），按文件路走")
        routeIncomingFiles(fromText, onImages, onMentions)
        return true
    }
    val images = readImagesFromPlatformClipboard()
    if (images.isEmpty()) return false
    LOG.info("贴图：收下 ${images.size} 张")
    onImages(images)
    return true
}

/**
 * 一批绝对路径怎么进输入框 —— **与附件按钮同一套分流**（[splitChosenFiles]）：
 * 图片进附件带，其余变成 `@` 引用。
 *
 * 解不开的图片（坏文件、PSD 那类）**退回 `@` 引用**，同附件按钮的老规矩：
 * 让用户至少能用引用把它交给模型，而不是静默丢掉。
 */
internal fun routeIncomingFiles(
    absolutePaths: List<String>,
    onImages: (List<IncomingImage>) -> Unit,
    onMentions: (List<String>) -> Unit,
) {
    val chosen = splitChosenFiles(absolutePaths)
    val unreadable = mutableListOf<String>()
    val images = chosen.pictures.mapNotNull { path ->
        imageFromFile(File(path)) ?: run {
            unreadable += path
            null
        }
    }
    val mentions = chosen.mentions + unreadable
    LOG.info("文件进输入框：图 ${images.size} 张、@ 引用 ${mentions.size} 个（一共 ${absolutePaths.size} 个）")
    if (images.isNotEmpty()) onImages(images)
    if (mentions.isNotEmpty()) onMentions(mentions)
}

/**
 * Swing 那条路（`TransferHandler`）的判定：**这次粘贴是不是"复制了一批文件"**。
 *
 * 抽成纯函数只为可测（同 [attachImagesWanted]）：`TransferSupport.isDrop` 在单测里
 * 设不了，只能喂标志位。
 *
 * **只管粘贴，不管拖拽**：拖进来的非图片文件维持老路（插一段路径文本）——
 * 那是拖拽那条线既有的行为，本次没动它。
 */
internal fun pasteFilesWanted(flavors: Collection<DataFlavor>, isDrop: Boolean): Boolean =
    !isDrop && flavors.any { it == DataFlavor.javaFileListFlavor }
