package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 会话占用登记。
 *
 * 守的是多标签引入的那条必然：两个标签各自 `listSessions` 然后恢复"最近那条"，
 * 很容易挑中同一条 → 两边同时写同一个 jsonl（设计稿里"本版不做检测"那条风险）。
 *
 * [OpenSessions] 自己起不了单测（要 Project），判定与记账全在 [ClaimTable] 里 ——
 * 这里钉的就是它。
 */
class OpenSessionsTest {

    private val a = Any()
    private val b = Any()

    @Test
    fun `空表里谁都不算被占`() {
        val table = ClaimTable()
        assertFalse(table.isTaken("s1"))
        assertNull(table.ownerOf("s1"))
    }

    @Test
    fun `占上之后归自己`() {
        val table = ClaimTable()
        assertTrue(table.reserve("s1", a))
        assertTrue(table.isTaken("s1"))
        assertSame(a, table.ownerOf("s1"))
        assertEquals(setOf("s1"), table.takenIds())
    }

    @Test
    fun `同一个 owner 重复占是幂等的`() {
        // "确认"那一步会拿 init 报的 id 再占一次自己已经预占的那条
        val table = ClaimTable()
        assertTrue(table.reserve("s1", a))
        assertTrue(table.reserve("s1", a))
        assertSame(a, table.ownerOf("s1"))
    }

    @Test
    fun `别人占着的抢不走，而且不动原主`() {
        val table = ClaimTable()
        table.reserve("s1", a)

        assertFalse(table.reserve("s1", b), "已经被占了就该失败")
        assertSame(a, table.ownerOf("s1"), "失败时不能把原主顶掉")
    }

    @Test
    fun `只能放掉自己占的`() {
        val table = ClaimTable()
        table.reserve("s1", a)

        table.release("s1", b)   // 别人的释放请求
        assertTrue(table.isTaken("s1"), "别人的 release 不该生效")

        table.release("s1", a)
        assertFalse(table.isTaken("s1"))
    }

    @Test
    fun `面板停会话时一次放干净，别人随后能占`() {
        val table = ClaimTable()
        table.reserve("s1", a)
        table.reserve("s2", a)
        table.reserve("s3", b)

        table.releaseAll(a)

        assertEquals(setOf("s3"), table.takenIds(), "只放自己的")
        assertTrue(table.reserve("s1", b), "放开之后别人能占")
    }

    @Test
    fun `snapshot 是拷贝，改不动内部`() {
        val table = ClaimTable()
        table.reserve("s1", a)

        val snap = table.snapshot() as MutableMap
        snap["s2"] = b

        assertFalse(table.isTaken("s2"), "快照不能反向写回")
    }
}
