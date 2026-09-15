package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 排队条的排版规则。交给纯函数是因为 ClaudePanel 起不了单测，
 * 而"一行写什么""什么时候收成一行"正是这条带子最容易失控的两件事。
 */
class QueueStripModelTest {

    @Test
    fun `空队列给 null —— 那块整块不占位`() {
        assertNull(queueStripModel(SendQueue()))
    }

    @Test
    fun `一条时不收折，内容就写在这一行上`() {
        val q = SendQueue().apply { enqueue("跑完把测试补一下", "跑完把测试补一下") }
        val m = queueStripModel(q)!!
        assertEquals(1, m.count)
        assertFalse(m.collapsible)
        // 「排队 1」单独占一行是白占一行 —— 一条时连内容一起写
        assertEquals("排队 1 · 跑完把测试补一下", queueLineText(m))
    }

    @Test
    fun `两条以上收成一行，点开才是列表`() {
        val q = SendQueue()
        repeat(3) { q.enqueue("第 $it 条", "第 $it 条") }
        val m = queueStripModel(q)!!
        assertEquals(3, m.count)
        assertTrue(m.collapsible)
        assertEquals("排队 3", queueLineText(m))
    }

    @Test
    fun `再多也不砍行数 —— 空间由收折解决，不是由藏掉几条解决`() {
        val q = SendQueue()
        repeat(9) { q.enqueue("第 $it 条", "第 $it 条") }
        assertEquals(9, queueStripModel(q)!!.rows.size)
    }

    @Test
    fun `超长的行截断加省略号`() {
        val long = "平".repeat(60)
        assertEquals("平".repeat(QUEUE_ROW_CHARS) + "…", queueRowText(long))
    }

    @Test
    fun `正好到上限不截断`() {
        val exact = "平".repeat(QUEUE_ROW_CHARS)
        assertEquals(exact, queueRowText(exact))
    }

    @Test
    fun `多行文本折成一行 —— 一行记号，不是一个块`() {
        assertEquals("第一行 第二行", queueRowText("第一行\n第二行"))
        assertEquals("a b", queueRowText("  a \n\n   b  "))
    }

    // ---- 贴图（2026-09-15）----

    @Test
    fun `带图的那条挂一个「图 N」标签`() {
        // 队列里的图是看不见的（缩略图只画在附件带上，而入队那一刻附件带就清空了）
        // —— 没有这个标签，"带着三张图的那条"和"纯文字的"在屏幕上长得一模一样
        assertEquals("图 1", queueChipText(1))
        assertEquals("图 3", queueChipText(3))
    }

    @Test
    fun `没图不给标签 —— 空标签比没有更糟`() {
        assertNull(queueChipText(0))
    }

    @Test
    fun `单条折叠那一行也要写出图数 —— 否则「排着一张截图」看不出来`() {
        val withImages = QueueStripModel(
            count = 1,
            rows = listOf(QueueRow(QueuedInput("看下", "看下", listOf(pic(0))), "看下")),
        )
        val without = QueueStripModel(
            count = 1,
            rows = listOf(QueueRow(QueuedInput("看下", "看下"), "看下")),
        )

        assertEquals("排队 1 · 看下 · 图 1", queueLineText(withImages))
        assertEquals("排队 1 · 看下", queueLineText(without))
    }

    private fun pic(index: Int): AttachedImage {
        val img = java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB)
        return prepareAttachment(img, index) ?: error("夹具没做成")
    }
}
