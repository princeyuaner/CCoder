package com.ccoder.ui

import com.ccoder.sidecar.TranscriptOp
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Reset 对账台账（2026-10-14）。
 *
 * 钉的是"什么时候算落后、补发什么"这一层纯逻辑 —— 真机那条"清空那发被通道
 * 静默丢掉"（用户报「clear 之后输出区没清空」）的兜底就押在它身上。
 * 与 `BridgeHeartbeatTest` 同一条规矩：可能出错的算术放在纯类里，用例钉得住。
 */
class ResetLedgerTest {

    @Test
    fun `没推过就不算落后`() {
        val l = ResetLedger()
        l.onPageReady()
        assertEquals(0, l.behindBy(0))
    }

    @Test
    fun `推了一发、页面也数到了 —— 不落后`() {
        val l = ResetLedger()
        l.onPageReady()
        l.onPush(TranscriptOp.Reset)
        assertEquals(1, l.expected)
        assertEquals(0, l.behindBy(1))
    }

    @Test
    fun `那一发丢了 —— 落后 1，补发就是那一发；补上了差额平掉`() {
        val l = ResetLedger()
        l.onPageReady()
        l.onPush(TranscriptOp.Reset)

        // 页面报 0 = 那一发丢了
        assertEquals(1, l.behindBy(0))
        assertEquals(listOf(TranscriptOp.Reset), l.resendOps())

        // 补发到了：页面数到 1，差额平掉。
        // **补发不走 onPush**（它补的是差额，不是新的代数）—— expected 仍是 1
        assertEquals(0, l.behindBy(1))
        assertEquals(1, l.expected)
    }

    @Test
    fun `紧跟在 Reset 后面的 Fresh 会配上对 —— 补发时把引导卡也补回来`() {
        val l = ResetLedger()
        l.onPageReady()
        l.onPush(TranscriptOp.Reset)
        l.onPush(TranscriptOp.Fresh)
        l.onPush(TranscriptOp.Append(appendedItem()))

        assertEquals(listOf(TranscriptOp.Reset, TranscriptOp.Fresh), l.resendOps())
    }

    @Test
    fun `隔了别的 op 的 Fresh 不配对 —— 那是新会话的引导卡，不是清空的尾巴`() {
        val l = ResetLedger()
        l.onPageReady()
        l.onPush(TranscriptOp.Reset)
        l.onPush(TranscriptOp.Append(appendedItem()))
        l.onPush(TranscriptOp.Fresh)

        assertEquals(listOf(TranscriptOp.Reset), l.resendOps())
    }

    @Test
    fun `换页基线：页面上线那一刻已发的不再要求它数 —— 重载后不误报`() {
        val l = ResetLedger()
        l.onPush(TranscriptOp.Reset) // 旧页见过的
        l.onPageReady() // 新页上线：基线落在这里
        assertEquals(0, l.expected, "旧页的那些不该算在新页头上")

        // 新页之后推的才算
        l.onPush(TranscriptOp.Reset)
        assertEquals(1, l.expected)
        assertEquals(1, l.behindBy(0))
    }

    @Test
    fun `连补两发也对：第二发丢了照样报落后`() {
        val l = ResetLedger()
        l.onPageReady()
        l.onPush(TranscriptOp.Reset)
        l.onPush(TranscriptOp.Reset)
        // 两发都丢：页面报 0 → 落后 2（补发每中一发少一）
        assertEquals(2, l.behindBy(0))
        assertEquals(1, l.behindBy(1))
        assertEquals(0, l.behindBy(2))
    }

    @Test
    fun `pong 里读代数：数字收、缺字段与坏形状给 null（老页面走这条）`() {
        fun obj(s: String) = JsonParser.parseString(s).asJsonObject

        assertEquals(3, pongResetsOf(obj("""{"op":"pong","resets":3}""")))
        assertEquals(0, pongResetsOf(obj("""{"op":"pong","resets":0}""")))
        assertNull(pongResetsOf(obj("""{"op":"pong"}""")), "老页面没有这个字段 —— 跳过对账，不是落后")
        assertNull(pongResetsOf(obj("""{"op":"pong","resets":"3"}""")), "坏形状按读不到处理")
    }

    private fun appendedItem() = com.ccoder.sidecar.TranscriptItem.SystemNote("n1", 0L, "x")
}
