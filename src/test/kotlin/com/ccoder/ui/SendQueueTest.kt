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
}
