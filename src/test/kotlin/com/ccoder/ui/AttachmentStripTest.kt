package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 附件带：收下几张、满了怎么办、删干净之后收不收起来。
 *
 * 好不好看交给 `AttachmentStripRenderProbe` 出图；这里钉的是**行为**：
 * 空的时候不能凭空占地方（排队条那边同一条规矩）、满了要说话（静默丢弃等于坏了）。
 */
class AttachmentStripTest {

    private fun png(w: Int = 40, h: Int = 30, color: Color = Color(30, 60, 220)): BufferedImage =
        BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                this.color = color
                fillRect(0, 0, w, h)
                dispose()
            }
        }

    private fun attachment(index: Int = 0) =
        prepareAttachment(png(), index) ?: error("夹具都没做成，测试本身有问题")

    /** 带子上现在写着的所有文字（缩略图下的文件名 + 那行原因）。 */
    private fun textsIn(c: Container): List<String> {
        val out = mutableListOf<String>()
        for (child in c.components) {
            if (child is JLabel && child.text.isNotBlank()) out += child.text
            if (child is Container) out += textsIn(child)
        }
        return out
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun `空的时候整条收起来 —— 没有附件的输入框不该多出一块空白`() =
        onEdt {
            val strip = AttachmentStrip()

            assertTrue(strip.isEmpty)
            assertFalse(strip.isVisible, "出生就该是隐藏的，等 add 来点亮")
        }

    @Test
    fun `收下一张之后可见，且拿得到它`() = onEdt {
        val strip = AttachmentStrip()
        val img = attachment(0)

        assertTrue(strip.add(img))

        assertEquals(listOf(img), strip.images)
        assertTrue(strip.isVisible)
        assertTrue(textsIn(strip).any { it == img.name }, "缩略图下面要写出名字：${textsIn(strip)}")
    }

    @Test
    fun `满了一条消息只收 MAX_IMAGES 张，并且说话`() = onEdt {
        // 静默丢弃是最糟的：用户刚按了 Ctrl+V，界面上什么都没发生
        val strip = AttachmentStrip()
        repeat(MAX_IMAGES) { strip.add(attachment(it)) }

        assertFalse(strip.add(attachment(9)), "第 ${MAX_IMAGES + 1} 张不该被收下")

        assertEquals(MAX_IMAGES, strip.images.size)
        assertTrue(
            textsIn(strip).any { it.contains("$MAX_IMAGES") },
            "要说清为什么没收：${textsIn(strip)}",
        )
    }

    @Test
    fun `删掉最后一张之后整条又收起来`() = onEdt {
        val strip = AttachmentStrip()
        val img = attachment(0)
        strip.add(img)

        strip.remove(img)

        assertTrue(strip.isEmpty)
        assertFalse(strip.isVisible, "一张都不剩了还占着高度就是白占")
    }

    @Test
    fun `发出去之后 clear 清空并收起`() = onEdt {
        val strip = AttachmentStrip()
        strip.add(attachment(0))
        strip.add(attachment(1))

        strip.clear()

        assertTrue(strip.images.isEmpty())
        assertFalse(strip.isVisible)
    }

    @Test
    fun `太大而没收下的图，原因写在带子上`() = onEdt {
        val strip = AttachmentStrip()

        // 收的是**算法**（不是现成的句子）：换语言时带子会重算一遍，见 AttachmentStrip.reject
        strip.reject { "这张图太大了（12MB），先裁一下再粘" }

        assertTrue(strip.isVisible, "拒绝的理由必须看得见")
        assertTrue(textsIn(strip).any { it.contains("12MB") }, "${textsIn(strip)}")
        assertTrue(strip.images.isEmpty(), "没收下就是没收下，别偷偷塞进去")
    }

    @Test
    fun `列表一变就叫一声 —— 卡片高度跟着变，面板要重新布局`() = onEdt {
        var changes = 0
        val strip = AttachmentStrip { changes++ }

        strip.add(attachment(0))
        strip.clear()

        assertEquals(2, changes)
    }

    // ---- 点缩略图（2026-09-17：✕ 删图、正文放大看）----
    //
    // 这里派发的是**真 MouseEvent**，不是直接调回调：要钉的恰恰是"哪一击走到
    // 哪个回调"这条路由。判定本身（差一个像素算不算 ✕）在 `ImagePreviewTest`。

    /** 在缩略图上点一下。坐标是组件内的像素。 */
    private fun clickThumb(
        strip: AttachmentStrip,
        x: Int,
        y: Int,
        index: Int = 0,
        button: Int = MouseEvent.BUTTON1,
    ) {
        val view = strip.thumbViewAt(index) ?: error("第 $index 张缩略图不见了（夹具问题）")
        view.dispatchEvent(
            MouseEvent(
                view, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                x, y, 1, false, button,
            )
        )
    }

    @Test
    fun `点正文一下 —— 回调拿到带上现在的图与第几张`() = onEdt {
        val strip = AttachmentStrip()
        strip.add(attachment(0))
        strip.add(attachment(1))
        var got: Pair<List<AttachedImage>, Int>? = null
        strip.onPreview = { images, index -> got = images to index }

        clickThumb(strip, x = 28, y = 21, index = 1)

        assertEquals(2, got?.first?.size, "该把整条带子交出去（框里还要左右翻）")
        assertEquals(1, got?.second, "点的哪一张就要说清")
    }

    @Test
    fun `点 ✕ 只删图 —— 不叫放大那条路`() = onEdt {
        val strip = AttachmentStrip()
        strip.add(attachment(0))
        var previews = 0
        strip.onPreview = { _, _ -> previews++ }

        clickThumb(strip, x = 55, y = 1)

        assertEquals(0, strip.images.size, "该删的没删")
        assertEquals(0, previews, "✕ 与正文是两条路，别互相串")
    }

    @Test
    fun `右键点 ✕ 什么都不做 —— 删图只认左键`() = onEdt {
        val strip = AttachmentStrip()
        strip.add(attachment(0))
        var previews = 0
        strip.onPreview = { _, _ -> previews++ }

        clickThumb(strip, x = 55, y = 1, button = MouseEvent.BUTTON3)

        assertEquals(1, strip.images.size, "右键不该把图删掉")
        assertEquals(0, previews, "右键也不该顺手把图点开")
    }
}
