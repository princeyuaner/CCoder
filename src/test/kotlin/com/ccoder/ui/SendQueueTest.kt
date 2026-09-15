package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 排队语义。抽成纯类就是为了这几条能钉住 ——
 * 真正的风险不是"排队不工作"，是"顺序错了"和"点了停止还在发"。
 */
class SendQueueTest {

    @Test
    fun `先进先出`() {
        val q = SendQueue()
        q.enqueue("第一条", "第一条")
        q.enqueue("第二条", "第二条")
        assertEquals("第一条", q.peek()?.text)
        q.remove(q.peek()!!)
        assertEquals("第二条", q.peek()?.text)
    }

    @Test
    fun `peek 不取走`() {
        val q = SendQueue()
        q.enqueue("a", "a")
        assertEquals(1, q.size)
        q.peek()
        assertEquals(1, q.size)
    }

    @Test
    fun `撤回单条只撤一条`() {
        val q = SendQueue()
        q.enqueue("a", "a")
        q.enqueue("b", "b")
        assertTrue(q.remove(QueuedInput("a", "a")))
        assertEquals(1, q.size)
        assertEquals("b", q.peek()?.text)
    }

    @Test
    fun `撤一条不在队里的什么也不做`() {
        val q = SendQueue()
        q.enqueue("a", "a")
        assertFalse(q.remove(QueuedInput("没有这条", "没有这条")))
        assertEquals(1, q.size)
    }

    @Test
    fun `drain 返回被清掉的并按原顺序`() {
        val q = SendQueue()
        q.enqueue("a", "说 a")
        q.enqueue("b", "说 b")
        assertEquals(listOf("a", "b"), q.drain().map { it.text })
        assertTrue(q.isEmpty)
    }

    @Test
    fun `空队列的行为`() {
        val q = SendQueue()
        assertNull(q.peek())
        assertEquals(emptyList<QueuedInput>(), q.drain())
        assertEquals(0, q.size)
    }

    // ---- 贴图（2026-09-15）----

    /** 一张够小的图，只为"这条消息带着图"这件事本身。 */
    private fun pic(index: Int = 0): AttachedImage {
        val img = java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB)
        return prepareAttachment(img, index) ?: error("夹具没做成")
    }

    @Test
    fun `图跟着那条消息走，不串到下一条上`() {
        // 撤掉 A 之后，A 的图绝不能留在队列里跟着 B 发出去 —— 那是"发给别人的截图"
        val q = SendQueue()
        val a = pic(0)
        q.enqueue("带图的", "带图的", listOf(a))
        q.enqueue("光文字", "光文字")

        assertEquals(listOf(a), q.peek()!!.images)
        assertTrue(q.drain()[1].images.isEmpty())
    }

    @Test
    fun `撤掉一条时它的图一起走`() {
        val q = SendQueue()
        val a = pic(0)
        q.enqueue("甲", "甲", listOf(a))
        q.enqueue("乙", "乙")

        q.remove(q.peek()!!)

        val rest = q.snapshot()
        assertEquals(1, rest.size)
        assertTrue(rest[0].images.isEmpty(), "剩下的那条不该捡到前一条的图")
    }

    @Test
    fun `不传图时字段是空列表，不是 null`() {
        // 默认值那条路：既有调用点（命令回合、既有测试）都不用改
        val q = SendQueue()
        q.enqueue("光文字", "光文字")

        assertTrue(q.peek()!!.images.isEmpty())
    }
}
