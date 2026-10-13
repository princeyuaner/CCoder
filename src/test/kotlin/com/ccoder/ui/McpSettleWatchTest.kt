package com.ccoder.ui

import com.ccoder.sidecar.McpServerStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「连接中」的续问表。
 *
 * 钉的是 2026-10-10 用户报的那个形状：页面开着时 server 自己连上了，状态行却不变
 * —— 因为没有人再去问。这张表决定"还问不问"，所以它必须对：落定就停（不然一直空转）、
 * 问满就停（不然一个连不上的 server 问到天荒地老）、落定之后下一场从头数
 * （不然这场会话往后再也不跟了）。
 */
class McpSettleWatchTest {

    @Test
    fun `落定的读数不再续问`() {
        val watch = McpSettleWatch()
        assertFalse(watch.nextAskWanted(emptyList()))
        assertFalse(watch.nextAskWanted(listOf(status("a", "connected"))))
        assertFalse(watch.nextAskWanted(listOf(status("a", "failed"), status("b", "needs-auth"))))
        assertFalse(watch.nextAskWanted(listOf(status("a", "disabled"))))
    }

    @Test
    fun `还有连接中的就问，问满就安静`() {
        val watch = McpSettleWatch()
        val pending = listOf(status("a", "pending"))
        repeat(McpSettleWatch.MAX_ASKS) { assertTrue(watch.nextAskWanted(pending)) }
        assertFalse(watch.nextAskWanted(pending))
    }

    @Test
    fun `中途落定之后，下一场连接中重新数`() {
        // 不重新数的话：第一场问满之后，这个会话里往后所有 pending 都再也不跟 ——
        // 而"重连一个刚连上的 server"这类事随时会发生
        val watch = McpSettleWatch()
        repeat(McpSettleWatch.MAX_ASKS + 1) { watch.nextAskWanted(listOf(status("a", "pending"))) }
        assertFalse(watch.nextAskWanted(listOf(status("a", "pending"))))
        assertFalse(watch.nextAskWanted(listOf(status("a", "connected")))) // 落定
        assertTrue(watch.nextAskWanted(listOf(status("a", "pending"))))    // 新一场从头跟
    }

    @Test
    fun `reset 之后从头跟`() {
        // 用户打开设置框 / 按了重连 / 新会话：他正看着的这一场从头跟起
        val watch = McpSettleWatch()
        repeat(McpSettleWatch.MAX_ASKS + 1) { watch.nextAskWanted(listOf(status("a", "pending"))) }
        assertFalse(watch.nextAskWanted(listOf(status("a", "pending"))))
        watch.reset()
        assertTrue(watch.nextAskWanted(listOf(status("a", "pending"))))
    }

    @Test
    fun `起手宽限：空读数也要接着问，问满宽限才停`() {
        // 2026-10-13：SDK 刚起来可能先答一份空表（还没枚举出服务器），而"空 = 落定"
        // 会让续问当场停表 —— "连接中 → 连上"整个窗口被跳过，输入框旁那条提示
        // 就永远等不到转绿。宽限只在起手兑现、耗尽即回到原判据
        val watch = McpSettleWatch()
        watch.armStartup()
        repeat(McpSettleWatch.STARTUP_ASKS) { assertTrue(watch.nextAskWanted(emptyList())) }
        assertFalse(watch.nextAskWanted(emptyList()))
    }

    @Test
    fun `起手宽限不越过 pending 的封顶`() {
        // 宽限是"多问几跳"，不是"多跟一场"：真有 pending 时仍按 MAX_ASKS 封顶，
        // 一个永远连不上的 server 不该因为宽限而多转
        val watch = McpSettleWatch()
        watch.armStartup()
        val pending = listOf(status("a", "pending"))
        repeat(McpSettleWatch.MAX_ASKS) { assertTrue(watch.nextAskWanted(pending)) }
        assertFalse(watch.nextAskWanted(pending))
    }

    @Test
    fun `reset 会把宽限也收回`() {
        // 打开设置框/新会话这类"牌还满"的动作，宽限跟着还满没有意义 —— 它由
        // 调用方在需要时重新 arm。若不收回，重置过的表会带着上一条会话的宽限乱转
        val watch = McpSettleWatch()
        watch.armStartup()
        watch.reset()
        assertFalse(watch.nextAskWanted(emptyList()))
    }

    private fun status(name: String, status: String) =
        McpServerStatus(name = name, status = status, scope = null, error = null, tools = emptyList())
}
