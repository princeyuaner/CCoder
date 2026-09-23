package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 心跳判死规则（`ClaudeTranscriptView` 的「第二副面孔」那条：通道断了、推送被静默丢掉）。
 *
 * 纯状态机，所以这里不碰 Swing / JCEF —— 要钉的是"多久算死"，不是"怎么发 ping"。
 * 真机上误判的代价是把还看得见的对话收走，所以规则必须在这里说得清清楚楚。
 */
class BridgeHeartbeatTest {

    @Test
    fun `刚发出去的那一拍不算 miss`() {
        val h = BridgeHeartbeat()
        assertFalse(h.onPing(), "第一拍就判死 —— 回信当然还没到")
    }

    @Test
    fun `连着两拍没回信才判死`() {
        val h = BridgeHeartbeat()
        assertFalse(h.onPing(), "第 1 拍")
        assertFalse(h.onPing(), "第 2 拍（上一拍没回信 = 1 次 miss，还没到限）")
        assertTrue(h.onPing(), "第 3 拍（连着 2 次 miss）—— 这一拍该判死")
    }

    @Test
    fun `回信把计数清零 —— 中间隔一次抖动不该死`() {
        val h = BridgeHeartbeat()
        h.onPing()
        h.onPing() // miss = 1
        h.onPong() // 活着
        assertFalse(h.onPing(), "回信之后重新数，不该接着上一轮的 miss")
        assertFalse(h.onPing(), "再两拍才该死，这一拍还早")
        assertTrue(h.onPing(), "再连着两拍没回信，这时候才死")
    }

    @Test
    fun `reset 之后从头数`() {
        val h = BridgeHeartbeat()
        h.onPing()
        h.onPing()
        h.reset()
        assertFalse(h.onPing(), "reset 连着 miss 一起清")
    }

    @Test
    fun `自定义限数`() {
        val h = BridgeHeartbeat(missLimit = 1)
        assertFalse(h.onPing(), "限数是 1 也要等下一拍才看得出没回信")
        assertTrue(h.onPing(), "限数 1：一拍没回信就该死")
    }

    @Test
    fun `判死的那拍之后保持判死状态由调用方收场`() {
        // 这里只负责报 true；收场（teardown / 降级）在 ClaudeTranscriptView。
        // 记着这条边界：状态机自己不会"自动痊愈"，也不会自动清掉自己。
        val h = BridgeHeartbeat()
        h.onPing()
        h.onPing()
        assertTrue(h.onPing())
        // 再 ping 仍然报死 —— 调用方该已经收走了，不该指望它自己好
        assertTrue(h.onPing())
        assertEquals(BridgeHeartbeat.MISS_LIMIT, 2, "限数改了要连着文档与用例一起改")
    }
}
