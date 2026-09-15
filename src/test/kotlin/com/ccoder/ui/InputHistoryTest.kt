package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 输入框的 ↑/↓ 历史。
 *
 * 这套行为照着 shell 来：**没发出去的那句不能被吞掉** —— 翻了半天历史，
 * 结果正在写的那半句没了，是最招人骂的一种实现。
 */
class InputHistoryTest {

    @Test
    fun `没有历史时返回 null —— 调用方别消费这个键`() {
        val history = InputHistory()
        assertNull(history.prev("半句话"))
        assertNull(history.next())
    }

    @Test
    fun `依次往回翻，到底停住`() {
        val history = InputHistory()
        history.remember("第一条")
        history.remember("第二条")

        assertEquals("第二条", history.prev("草稿"))
        assertEquals("第一条", history.prev("草稿"))
        // 再按还是最旧那条，不绕回最新的
        assertEquals("第一条", history.prev("草稿"))
    }

    @Test
    fun `↓ 一路走回来，把没发出去的那句还回去`() {
        val history = InputHistory()
        history.remember("旧")
        history.remember("新")

        val draft = "写了一半的话"
        assertEquals("新", history.prev(draft))
        assertEquals("旧", history.prev(draft))

        assertEquals("新", history.next())
        assertEquals(draft, history.next(), "走到底该把草稿还回来")
        // 已经出了历史：再按 ↓ 不管（该是正常的光标下移）
        assertNull(history.next())
    }

    @Test
    fun `翻上去改两个字，↓ 拿回来的是改过的那份`() {
        val history = InputHistory()
        history.remember("旧")
        history.remember("新")

        assertEquals("新", history.prev(""))
        // 用户把回填出来的「新」改成了「改过的」，又按 ↑ 去看更早的
        assertEquals("旧", history.prev("改过的"))
        // ↓ 依次走回来：先回到中间那条，再 ↓ 才是**改动过的那份**草稿
        assertEquals("新", history.next())
        assertEquals("改过的", history.next(), "拿回来的必须是改动过的那份，不是最初的草稿")
    }

    @Test
    fun `连着发两条一样的只留一条`() {
        val history = InputHistory()
        history.remember("重复")
        history.remember("重复")

        assertEquals("重复", history.prev(""))
        // 只有一条：再按 ↑ 还是它（停在最旧那条，不绕回最新的，与 shell 一致）
        assertEquals("重复", history.prev(""))
    }

    @Test
    fun `空消息不记`() {
        val history = InputHistory()
        history.remember("   ")
        assertNull(history.prev(""))
    }

    @Test
    fun `超过上限时裁掉最旧的`() {
        val history = InputHistory(limit = 3)
        history.remember("一")
        history.remember("二")
        history.remember("三")
        history.remember("四")

        assertEquals("四", history.prev(""))
        assertEquals("三", history.prev(""))
        assertEquals("二", history.prev(""))
        assertEquals("二", history.prev(""), "最旧的「一」已经被裁掉")
    }

    @Test
    fun `发送之后重新从最新一条开始翻`() {
        val history = InputHistory()
        history.remember("旧")
        assertEquals("旧", history.prev(""))
        history.remember("刚发的")

        // 发完再按 ↑：从「刚发的」开始，而不是接着上次那条往下翻
        assertEquals("刚发的", history.prev(""))
    }

    @Test
    fun `光标贴在首行才接管 ↑，贴在末行才接管 ↓`() {
        val twoLines = "第一行\n第二行"

        assertTrue(onFirstLine(twoLines, 0))
        assertTrue(onFirstLine(twoLines, 3))
        assertFalse(onFirstLine(twoLines, 4), "光标已经在第二行：↑ 该是正常的光标上移")

        assertFalse(onLastLine(twoLines, 3))
        assertTrue(onLastLine(twoLines, 4))
        assertTrue(onLastLine(twoLines, twoLines.length))

        // 空输入框：两个方向都算贴着（那时本来就该翻历史）
        assertTrue(onFirstLine("", 0))
        assertTrue(onLastLine("", 0))
        // 越界的光标号不炸（文档事件与按键之间理论上有窗口）
        assertTrue(onFirstLine("a", 99))
    }
}
