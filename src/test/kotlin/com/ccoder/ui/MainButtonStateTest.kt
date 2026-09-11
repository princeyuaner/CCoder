package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 发送/停止合一按钮的状态规则。
 *
 * ClaudePanel 依赖 Swing 与平台、写不了单测，所以规则抽在纯函数里验证。
 */
class MainButtonStateTest {

    @Test
    fun `空闲时是发送`() {
        val s = mainButtonState(ready = true, busy = false, disconnected = false)
        assertEquals("发送", s.text)
        assertEquals(MainAction.Send, s.action)
        assertTrue(s.enabled)
    }

    @Test
    fun `回合进行中时是停止`() {
        val s = mainButtonState(ready = true, busy = true, disconnected = false)
        assertEquals("停止", s.text)
        assertTrue(s.enabled)
    }

    @Test
    fun `停止走的是 interrupt 而非 stop`() {
        // 这条是本次改动的要害：stop 会把整个会话销毁（index.js 里 session = null），
        // 界面却还显示"已连接"，之后再也发不出消息。必须用 interrupt
        val s = mainButtonState(ready = true, busy = true, disconnected = false)
        assertEquals(MainAction.Interrupt, s.action)
    }

    @Test
    fun `未就绪时禁用并显示启动中`() {
        val s = mainButtonState(ready = false, busy = false, disconnected = false)
        assertEquals("启动中…", s.text)
        assertEquals(MainAction.Disabled, s.action)
        assertFalse(s.enabled)
    }

    @Test
    fun `断开后是重启会话`() {
        val s = mainButtonState(ready = false, busy = false, disconnected = true)
        assertEquals("重启会话", s.text)
        assertEquals(MainAction.Restart, s.action)
        // 必须可点，否则"重启"无处可点
        assertTrue(s.enabled)
    }

    @Test
    fun `断开优先于停止——进程没了就没有东西可中断`() {
        val s = mainButtonState(ready = false, busy = true, disconnected = true)
        assertEquals(MainAction.Restart, s.action)
    }

    @Test
    fun `忙优先于未就绪——回合进行中按钮就该是停止`() {
        val s = mainButtonState(ready = false, busy = true, disconnected = false)
        assertEquals(MainAction.Interrupt, s.action)
    }
}
