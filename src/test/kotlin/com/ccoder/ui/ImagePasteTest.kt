package com.ccoder.ui

import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.TransferHandler

/**
 * 粘贴这条线：图进附件带（老功能）、文件变 `@` 引用（2026-09-24 加的，见 `FilePaste.kt`）。
 *
 * **拖那半边在这里测不到**：`TransferSupport.setDrop` 是包内可见，只有 AWT 自己在
 * 拖拽时才会设 —— 所以凡是构造出来的 `TransferSupport` 都是**粘贴**，两条路的分支
 * 判据（[attachImagesWanted] / [pasteFilesWanted]）都抽成了纯函数，直接喂标志位。
 * 剩下的传输路径用构造出来的 `TransferSupport` 走（它的公开构造器就是给测试用的）。
 */
class ImagePasteTest {

    private class FakeTransferable(private val data: Map<DataFlavor, Any>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = data.containsKey(flavor)
        override fun getTransferData(flavor: DataFlavor): Any = data.getValue(flavor)
    }

    /** 原来那个处理器：只负责记录"有没有被转到"。 */
    private class RecordingHandler : TransferHandler() {
        var imports = 0
        override fun canImport(support: TransferSupport) = true
        override fun importData(support: TransferSupport): Boolean {
            imports++
            return true
        }
    }

    private fun png(w: Int = 40, h: Int = 30): BufferedImage =
        BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                color = Color(200, 40, 40)
                fillRect(0, 0, w, h)
                dispose()
            }
        }

    // ---- 规则（纯函数）----

    @Test
    fun `粘的时候文字优先 —— 剪贴板里同时有文字时用户要的是文字`() {
        val flavors = listOf(DataFlavor.imageFlavor, DataFlavor.stringFlavor)

        assertFalse(attachImagesWanted(flavors, isDrop = false), "粘代码不该多出一张图")
    }

    @Test
    fun `拖的时候图优先 —— 拖一张 png 进来要的就是那张图`() {
        val flavors = listOf(DataFlavor.imageFlavor, DataFlavor.stringFlavor)

        assertTrue(attachImagesWanted(flavors, isDrop = true))
    }

    @Test
    fun `截图工具粘上来的只有位图，接`() {
        assertTrue(attachImagesWanted(listOf(DataFlavor.imageFlavor), isDrop = false))
    }

    @Test
    fun `只有文字不接`() {
        assertFalse(attachImagesWanted(listOf(DataFlavor.stringFlavor), isDrop = false))
    }

    // ---- 传输路径 ----

    @Test
    fun `粘一张位图：进回调，默认处理器一个都不碰`() {
        val recorder = RecordingHandler()
        val area = JBTextArea().apply { transferHandler = recorder }
        val got = mutableListOf<IncomingImage>()
        installComposerPaste(area, onImages = { got += it }, onMentions = {})

        val support = TransferHandler.TransferSupport(
            area,
            FakeTransferable(mapOf(DataFlavor.imageFlavor to png())),
        )
        assertTrue(area.transferHandler.importData(support))

        assertEquals(1, got.size)
        assertEquals(0, recorder.imports, "图既然接下了，就不该再走默认处理器")
    }

    @Test
    fun `粘图又粘字：整件事交回默认处理器`() {
        val recorder = RecordingHandler()
        val area = JBTextArea().apply { transferHandler = recorder }
        var called = 0
        installComposerPaste(area, onImages = { called++ }, onMentions = {})

        val support = TransferHandler.TransferSupport(
            area,
            FakeTransferable(
                mapOf(DataFlavor.imageFlavor to png(), DataFlavor.stringFlavor to "一段代码"),
            ),
        )
        area.transferHandler.importData(support)

        assertEquals(0, called, "文字优先：不该往附件带里塞图")
        assertEquals(1, recorder.imports, "必须转交默认处理器，否则这次粘贴整个没反应")
    }

    @Test
    fun `拖进来一个 png 文件：解得出图，名字保留`() {
        val recorder = RecordingHandler()
        val area = JBTextArea().apply { transferHandler = recorder }
        val got = mutableListOf<IncomingImage>()
        installComposerPaste(area, onImages = { got += it }, onMentions = {})

        val file = File.createTempFile("ccoder-paste", ".png").apply {
            ImageIO.write(png(), "png", this)
            deleteOnExit()
        }
        // 只有文件味道、没有文字味道 —— 正是"从资源管理器复制一个图片文件"的样子
        val support = TransferHandler.TransferSupport(
            area,
            FakeTransferable(mapOf(DataFlavor.javaFileListFlavor to listOf(file))),
        )
        area.transferHandler.importData(support)

        assertEquals(1, got.size)
        assertEquals(file.name, got[0].name, "拖进来的文件有自己的名字，不该被改叫截图N")
        assertTrue(got[0].sourceBytes > 0, "文件那条路要拿得到字节数：太大了要能拦住")
    }

    @Test
    fun `粘贴一个 md 文件：变成 @ 引用，不走默认处理器`() {
        // 2026-09-24：粘贴文件从"插一段路径文本"改成 `@` 引用（同附件按钮）。
        // **拖拽**那条线没动 —— 但这里造出来的 TransferSupport 永远是粘贴
        // （`isDrop` 是包内可见，只有 AWT 自己能设），拖拽那条规则钉在
        // [pasteFilesWanted] 的纯函数用例里。
        val recorder = RecordingHandler()
        val area = JBTextArea().apply { transferHandler = recorder }
        var images = 0
        var mentions: List<String> = emptyList()
        installComposerPaste(area, onImages = { images++ }, onMentions = { mentions = it })

        val file = File.createTempFile("ccoder-paste", ".md").apply { deleteOnExit() }
        val support = TransferHandler.TransferSupport(
            area,
            FakeTransferable(mapOf(DataFlavor.javaFileListFlavor to listOf(file))),
        )
        area.transferHandler.importData(support)

        assertEquals(0, images, "md 不是图")
        assertEquals(listOf(file.absolutePath), mentions, "非图文件该变成引用")
        assertEquals(0, recorder.imports, "我们接下了，就不该再走默认处理器（那会多插一段路径）")
    }

    @Test
    fun `挂着 png 名但解不开的文件：退回 @ 引用，不把整次粘贴弄失败`() {
        val recorder = RecordingHandler()
        val area = JBTextArea().apply { transferHandler = recorder }
        val got = mutableListOf<IncomingImage>()
        var mentions: List<String> = emptyList()
        installComposerPaste(area, onImages = { got += it }, onMentions = { mentions = it })

        val broken = File.createTempFile("ccoder-paste", ".png").apply {
            writeText("这不是一张图")
            deleteOnExit()
        }
        val support = TransferHandler.TransferSupport(
            area,
            FakeTransferable(mapOf(DataFlavor.javaFileListFlavor to listOf(broken))),
        )
        area.transferHandler.importData(support)

        assertEquals(0, got.size, "读不出来的不该进附件带")
        assertEquals(listOf(broken.absolutePath), mentions, "解不开也得让用户能引用它")
        assertEquals(0, recorder.imports, "接下之后不该再走默认处理器")
    }

    @Test
    fun `接线之后 Ctrl+V 那条路真的会走到我们 —— actionMap 里的 paste 得是 TransferHandler 的`() {
        // 2026-09-15 真机上"按 Ctrl+V 什么都没发生"，排查时先确认的就是这条：
        // JTextComponent.paste() 的实现是 invokeAction("paste", TransferHandler.getPasteAction())
        // —— 也就是说 actionMap 里那个 "paste" 必须是 TransferHandler 的 action，
        // 我们的处理器才会被调；被换成别人的，处理器再对也没用。
        // 所以这条盯的是**平台/LAF 的接线**，不是我们的逻辑。
        IdeLaf.withRealLaf {
            val area = JBTextArea()
            installComposerPaste(area, onImages = {}, onMentions = {})

            val paste = area.actionMap.get("paste")
            assertNotNull(paste, "actionMap 里没有 paste —— Ctrl+V 这条路断了")
            assertEquals(
                javax.swing.TransferHandler.getPasteAction().javaClass,
                paste.javaClass,
                "paste 被别人换掉了：${paste.javaClass.name}",
            )
            assertTrue(
                area.transferHandler is TransferHandler,
                "装完之后处理器应该是我们的",
            )
        }
    }

    // ---- 平台的粘贴（IDE 里 Ctrl+V 真正走的那条）----

    @Test
    fun `只在该接管时才回答平台的 PASTE —— 文字粘贴一个字不变`() {
        // IDE 里 Ctrl+V 走的是平台的 `$Paste` action，它从数据上下文里取
        // PasteProvider。**只有"该我们接"时才回答**（[takeover] 真机上是
        // clipboardPasteTakeover：剪贴板里有文件、或只有图）：其余一律返回 null，
        // 平台自己那个 provider 接着处理 —— 不这么做的话，文字粘贴就得我们
        // 自己重写一遍（选区替换、撤销栈），那是另一个量级的坑。
        val key = PlatformDataKeys.PASTE_PROVIDER.name
        val take = composerPasteProvider(onImages = {}, onMentions = {}, takeover = { true })
        val pass = composerPasteProvider(onImages = {}, onMentions = {}, takeover = { false })

        assertSame(take, composerPasteData(key, take))
        assertNull(composerPasteData(key, pass), "不该接的时候还抢 Ctrl+V 会挡住文字粘贴")
        assertNull(composerPasteData("别的 key", take), "只回答 PASTE 这一个 key")
        assertNull(composerPasteData(key, null), "没挂 provider 的组件不该被当成有")
    }

    @Test
    fun `输入框在有图时把 PASTE 交给我们的 provider`() {
        // 组件级：平台构建数据上下文时会问焦点组件，输入框要实现 DataProvider
        val area = ComposerTextArea(1, 20)
        val provider = composerPasteProvider(onImages = {}, onMentions = {}, takeover = { true })

        assertNull(area.getData(PlatformDataKeys.PASTE_PROVIDER.name), "没挂之前不回答")

        area.pasteProvider = provider

        assertSame(provider, area.getData(PlatformDataKeys.PASTE_PROVIDER.name))
        assertNull(area.getData("别的 key"))
    }

    @Test
    fun `拿不到默认处理器时干脆不装 —— 装上去等于把文字粘贴弄丢`() {
        val area = JBTextArea().apply { transferHandler = null }
        var called = 0

        installComposerPaste(area, onImages = { called++ }, onMentions = {})

        assertEquals(null, area.transferHandler, "宁可不支持贴图，也不能让 Ctrl+V 代码失效")
        assertEquals(0, called)
    }
}
