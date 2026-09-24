package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * "复制一个文件 → 粘贴变 `@` 引用"这条线（2026-09-24）。
 *
 * 与附件按钮是**同一套分流**（[routeIncomingFiles] 复用 [splitChosenFiles]），
 * 这里钉的正是那个"同一套"：图进附件带、其余变引用、解不开的图退回引用、
 * 目录跳过。
 *
 * 真机那条路（真剪贴板、真 IDE）测不了 —— `CopyPasteManager` 要 Application。
 * 所以判据都在纯函数里，这里直接喂数据；`pasteFilesWanted` 的 `isDrop` 单测里
 * 设不了，只能喂标志位（同 `attachImagesWanted` 那条注释）。
 */
class FilePasteTest {

    @TempDir
    lateinit var tmp: Path

    /** 一小张真 PNG（内容无所谓，能被 [ImageIO] 读出来就行）。 */
    private fun png(name: String): File = tmp.resolve(name).toFile().apply {
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", this)
    }

    private fun text(name: String, body: String = "x"): File =
        tmp.resolve(name).toFile().apply { writeText(body) }

    private class FakeTransferable(private val data: Map<DataFlavor, Any>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = data.containsKey(flavor)
        override fun getTransferData(flavor: DataFlavor): Any = data.getValue(flavor)
    }

    // ---- 判据（纯函数）----

    @Test
    fun `粘贴一批文件就接下 —— 哪怕它还带着一份路径文本`() {
        assertTrue(pasteFilesWanted(listOf(DataFlavor.javaFileListFlavor), isDrop = false))

        // 复制文件时 transferable 本来就会回答 stringFlavor（内容是路径清单），
        // 所以"有文字就让路"那条规矩在这条路上等于永不触发（见 FilePaste.kt 头注）
        assertTrue(
            pasteFilesWanted(listOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor), isDrop = false),
            "复制文件带的那份文字是路径清单，不是用户要粘的内容",
        )

        assertFalse(pasteFilesWanted(listOf(DataFlavor.stringFlavor), isDrop = false), "一段纯文本不该被接管")
        assertFalse(pasteFilesWanted(emptyList(), isDrop = false))
    }

    @Test
    fun `拖拽那条线不动 —— 非图片文件维持老路（插路径）`() {
        assertFalse(pasteFilesWanted(listOf(DataFlavor.javaFileListFlavor), isDrop = true))
    }

    // ---- 分流（与附件按钮同一套）----

    @Test
    fun `图片文件进附件带，其余变成 @ 引用`() {
        val pic = png("shot.png")
        val src = text("Main.kt", "fun main() {}")
        var images: List<IncomingImage> = emptyList()
        var mentions: List<String> = emptyList()

        routeIncomingFiles(
            listOf(pic.absolutePath, src.absolutePath),
            onImages = { images = it },
            onMentions = { mentions = it },
        )

        assertEquals(listOf("shot.png"), images.map { it.name }, "图该进附件带")
        assertEquals(listOf(src.absolutePath), mentions, "非图文件该变成引用")
    }

    @Test
    fun `解不开的图退回引用 —— 不静默丢掉`() {
        // 扩展名是 .png、内容是文本：附件按钮那条路也是这么退的
        val broken = text("broken.png", "not an image")
        var images: List<IncomingImage> = emptyList()
        var mentions: List<String> = emptyList()

        routeIncomingFiles(listOf(broken.absolutePath), onImages = { images = it }, onMentions = { mentions = it })

        assertTrue(images.isEmpty(), "读不出来就不该进附件带")
        assertEquals(listOf(broken.absolutePath), mentions, "解不开也得让用户能引用它")
    }

    @Test
    fun `一个文件都没有时不惊动任何回调`() {
        var calls = 0

        routeIncomingFiles(emptyList(), onImages = { calls++ }, onMentions = { calls++ })

        assertEquals(0, calls)
    }

    // ---- 读剪贴板/拖拽那份 transferable ----

    @Test
    fun `从 transferable 读文件清单：目录跳过`() {
        val dir = tmp.resolve("sub").toFile().apply { mkdirs() }
        val file = text("a.kt")

        val paths = filePathsFrom(
            FakeTransferable(mapOf(DataFlavor.javaFileListFlavor to listOf(dir, file))),
        )

        assertEquals(listOf(file.absolutePath), paths, "目录进输入框没有意义（@目录 模型读不了）")
    }

    @Test
    fun `认不出文件清单就给空表，不抛`() {
        assertEquals(emptyList<String>(), filePathsFrom(FakeTransferable(mapOf(DataFlavor.stringFlavor to "abc"))))
    }

    // ---- 纯文本路径那条（IDE 里复制文件在剪贴板上就是它，2026-09-24 追加）----

    /** 假解析器：只有登记过的名字算"盘上真有这个文件"。 */
    private fun resolver(vararg known: String): FileResolver = { text ->
        known.firstOrNull { it == text }?.let { "/abs/$it" }
    }

    @Test
    fun `单行路径 → 一条引用`() {
        assertEquals(listOf("/abs/src/Main.kt"), filePathsInText("src/Main.kt", resolver("src/Main.kt")))
    }

    @Test
    fun `多行路径 → 一条一行`() {
        val resolve = resolver("a.kt", "b.kt")

        assertEquals(listOf("/abs/a.kt", "/abs/b.kt"), filePathsInText("a.kt\nb.kt\n", resolve))
    }

    @Test
    fun `一行里空格分开的多个路径也认（项目树里多选复制的样子）`() {
        val resolve = resolver("a.kt", "b.kt")

        assertEquals(listOf("/abs/a.kt", "/abs/b.kt"), filePathsInText("a.kt b.kt", resolve))
    }

    @Test
    fun `只要有一行不是真文件，整段就判否 —— 交回默认粘贴`() {
        val resolve = resolver("a.kt")

        // 一段代码里恰好有一行像路径，整段就会变成引用 —— 那是帮倒忙
        assertNull(filePathsInText("a.kt\nval x = 1\n", resolve))
        assertNull(filePathsInText("这不是路径", resolve))
    }

    @Test
    fun `空文本与超长清单都判否`() {
        assertNull(filePathsInText("   \n\n", resolver("a.kt")))
        assertNull(filePathsInText((1..60).joinToString("\n") { "a.kt" }, resolver("a.kt")))
    }

    @Test
    fun `真解析器认绝对路径，目录不算`() {
        val file = text("real.kt")
        val dir = tmp.resolve("sub").toFile().apply { mkdirs() }
        val resolve = projectFileResolver(null)   // 没有项目 → 只认绝对路径

        assertEquals(file.absolutePath, resolve(file.absolutePath))
        assertNull(resolve(dir.absolutePath), "目录不是能引用的目标")
        assertNull(resolve("相对路径.kt"), "没有项目根就算不出来")
    }
}
