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
}
