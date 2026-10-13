package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 定时待发表的三条规则：先到先发、按 id 撤、清空要交还。
 * 表本身不依赖 Swing（同 [SendQueue]），所以这些用不着探针。
 */
class ScheduledSendsTest {

    private fun item(id: String, fireAt: Long, text: String = id) =
        ScheduledSend(id = id, fireAt = fireAt, payload = QueuedInput(text = text, typed = text))

    @Test
    fun `快照按到点时刻排序，不按添加顺序`() {
        val q = ScheduledSends()
        q.add(item("late", 300))
        q.add(item("early", 100))
        q.add(item("mid", 200))
        assertEquals(listOf("early", "mid", "late"), q.snapshot().map { it.id })
    }

    @Test
    fun `due 取到点的（含恰好等于 now），先到先发`() {
        val q = ScheduledSends()
        q.add(item("a", 100))
        q.add(item("b", 200))
        q.add(item("c", 300))
        assertEquals(listOf("a", "b"), q.due(200).map { it.id })
        assertEquals(listOf("a", "b", "c"), q.due(999).map { it.id })
    }

    @Test
    fun `due 不改动表 —— 摘是摘、发是发`() {
        val q = ScheduledSends()
        q.add(item("a", 100))
        q.due(200)
        assertEquals(1, q.size, "due 只是看一眼，不许顺手清掉")
    }

    @Test
    fun `removeById 按 id 撤；撤不动返回 false`() {
        val q = ScheduledSends()
        q.add(item("a", 100, text = "同一条内容"))
        q.add(item("b", 100, text = "同一条内容"))
        assertTrue(q.removeById("b"), "内容一样的两条要按 id 分得开")
        assertEquals(listOf("a"), q.snapshot().map { it.id })
        assertFalse(q.removeById("b"), "撤过的不许再撤一次")
    }

    @Test
    fun `drain 清空并交还全部`() {
        val q = ScheduledSends()
        q.add(item("a", 100))
        q.add(item("b", 200))
        assertEquals(listOf("a", "b"), q.drain().map { it.id })
        assertTrue(q.isEmpty)
        assertTrue(q.drain().isEmpty())
    }

    @Test
    fun `nextFireAt 取最早的一条；空表 null`() {
        val q = ScheduledSends()
        assertNull(q.nextFireAt())
        q.add(item("a", 300))
        q.add(item("b", 100))
        assertEquals(100L, q.nextFireAt())
    }
}
