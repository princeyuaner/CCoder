package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Container
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

        strip.reject("这张图太大了（12MB），先裁一下再粘")

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
}
