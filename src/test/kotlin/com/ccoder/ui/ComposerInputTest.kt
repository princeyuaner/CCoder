package com.ccoder.ui

import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.TransferHandler
import javax.swing.text.DefaultEditorKit

/**
 * 输入框的外观。
 *
 * **这里曾经断言的是反面**：早先用户反馈"输入框与转写区糊在一起分不清"，
 * 于是给输入框自己画了一条线边框。方案 A 把那条线挪到了外层卡片上——
 * 卡片包住的是整个输入区（上下文行 + 输入框 + 工具栏），边界比只框住
 * 文字那一块更明确。
 *
 * 所以"分不清"那个问题仍然是被回答的，只是换了地方回答：
 * 见 [ComposerRulesTest] 里对卡片描边的断言。
 */
class ComposerInputTest {

    private fun styled() = JBTextArea(3, 40).also { styleComposerInput(it) }

    @Test
    fun `输入框自己不画线 —— 线归外层卡片`() {
        // 这里再画一层就退回成"框里套框"，那是方案 A 要付的代价，
        // 不能连它换来的好处一起丢掉
        val area = styled()

        val insets = area.border.getBorderInsets(area)
        assertEquals(
            0, listOf(insets.top, insets.left, insets.bottom, insets.right).count { it > 7 },
            "边框内衬过大，像是画了线：$insets",
        )
        assertFalse(
            area.border is javax.swing.border.CompoundBorder,
            "输入框的边框应当只剩内边距，实际是 ${area.border.javaClass.simpleName}",
        )
    }

    @Test
    fun `文字与边缘之间有内边距`() {
        val area = styled()

        val padding = area.border.getBorderInsets(area)
        assertTrue(
            padding.top >= 2 && padding.left >= 2,
            "没有内边距的话文字会贴着卡片的描边，看着像被框住的段落，实际 $padding",
        )
    }

    @Test
    fun `输入框不填底 —— 底色由卡片统一决定`() {
        // 不透明会把卡片的底色盖掉，视觉上又变成嵌了一层。
        // 这是"卡片是一个整体"的必要条件：内部的几块不能各自有底
        val area = styled()

        assertFalse(area.isOpaque, "输入框不该自己填底")
    }

    // ---- 图片入口 ----

    @Test
    fun `粘贴动作被换掉 —— Ctrl+V 绑的是 ActionMap 里那个，覆写 paste() 拦不住`() {
        val area = JTextArea()
        val before = area.actionMap.get(DefaultEditorKit.pasteAction)

        installImagePaste(area) {}

        val after = area.actionMap.get(DefaultEditorKit.pasteAction)
        assertNotNull(after, "换完必须还有一个 paste 动作，否则 Ctrl+V 直接失灵")
        assertNotSame(before, after, "没换掉的话 Ctrl+V 根本走不到我们的判定")
    }

    @Test
    fun `拖拽只认图片文件 —— 拖一堆别的进来不粘任何东西`(@TempDir dir: Path) {
        val png = dir.resolve("a.png").toFile()
        val txt = dir.resolve("b.txt").toFile()
        val area = JPanel()
        var got: List<File>? = null
        installImageDrop(area) { got = it }
        val handler = area.transferHandler

        assertTrue(handler.canImport(drop(area, listOf(png))), "图片文件该接")
        assertFalse(
            handler.canImport(drop(area, listOf(txt))),
            "一堆非图片不该接 —— 接了等于什么都没发生，却让人以为粘上了",
        )
        assertTrue(handler.importData(drop(area, listOf(png, txt))))
        assertEquals(listOf(png), got, "只把图片交下去；非图片在这一层就挑掉了")
    }

    @Test
    fun `图片文件先归我们 —— 原来的处理器不参与`() {
        val png = File("a.png")
        val area = JPanel()
        val original = FakeOriginal()
        area.transferHandler = original
        var got: List<File>? = null
        installImageDrop(area) { got = it }
        val handler = area.transferHandler

        assertTrue(handler.canImport(drop(area, listOf(png))), "图片该接")
        assertEquals(0, original.canImportCalls, "图片轮不到原来的处理器")
        assertTrue(handler.importData(drop(area, listOf(png))))
        assertEquals(listOf(png), got)
        assertEquals(0, original.importCalls)
    }

    @Test
    fun `非图片拖拽交回原来的处理器 —— 从编辑器拖段文字进来的能力不能弄丢`() {
        // 输入框自带 BasicTextUI$TextTransferHandler，而 Swing 的拖放**不向父级
        // 冒泡**：本层若不把非图片的拖拽转交出去，那个既有能力就被我们吃掉了
        val area = JTextArea()
        val original = FakeOriginal()
        area.transferHandler = original
        var got: List<File>? = null
        installImageDrop(area) { got = it }
        val handler = area.transferHandler

        assertTrue(handler.canImport(drop(area, listOf(File("b.txt")))), "原来的处理器接得住，整体就该接得住")
        assertEquals(1, original.canImportCalls, "没有委派的话，问都没问过它")
        assertTrue(handler.importData(drop(area, listOf(File("b.txt")))), "原处理器接下了，就该报 true")
        assertEquals(1, original.importCalls, "导入也要委派")
        assertNull(got, "非图片不该进我们的回调")
    }

    /** 假的"原来的处理器"：只记自己被问过几次，并声称自己接得住任何东西。 */
    private class FakeOriginal : TransferHandler() {
        var canImportCalls = 0
        var importCalls = 0

        override fun canImport(support: TransferSupport): Boolean {
            canImportCalls++
            return true
        }

        override fun importData(support: TransferSupport): Boolean {
            importCalls++
            return true
        }
    }

    /** 一个只认 javaFileListFlavor 的 Transferable，模拟从资源管理器拖文件进来。 */
    private fun drop(component: java.awt.Component, files: List<File>) =
        TransferHandler.TransferSupport(
            component,
            object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> =
                    arrayOf(DataFlavor.javaFileListFlavor)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                    flavor == DataFlavor.javaFileListFlavor

                override fun getTransferData(flavor: DataFlavor): Any = files
            },
        )
}
