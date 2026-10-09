package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 设置页 → 会话那条动作线（[McpControl]）。
 *
 * 钉的都是**不做就看不出来**的那几件：谁挂的谁摘（多标签）、收不下要放掉
 * （不然按钮一直画着）、发之前先清上一次的报错（不然用户读的是旧消息）。
 */
class McpControlTest {

    private val me = Any()
    private val other = Any()

    @Test
    fun `没人接的时候 send 返回 false，不抛`() {
        // 界面这时候不该画按钮；真点了也不能炸 —— 那句话叫"静默失败"才是问题，
        // 而这里返回值就是那条"没送出去"
        assertFalse(McpControl().send(McpControl.Action.Reconnect("proj")))
    }

    @Test
    fun `挂上之后 armed 为真，动作原样交给处理器`() {
        val seen = mutableListOf<McpControl.Action>()
        val control = McpControl().apply { attach(me) { seen += it; true } }

        assertTrue(control.armed)
        assertTrue(control.send(McpControl.Action.Toggle("proj", false)))
        assertEquals(listOf<McpControl.Action>(McpControl.Action.Toggle("proj", false)), seen)
    }

    @Test
    fun `detach 只认主人 —— 后台标签的 Exit 不该抹掉前台刚挂上的`() {
        val seen = mutableListOf<McpControl.Action>()
        val control = McpControl().apply { attach(me) { seen += it; true } }

        control.detach(other)

        assertTrue(control.armed, "不是自己挂的却把线摘了")
        assertTrue(control.send(McpControl.Action.Reconnect("proj")))
    }

    @Test
    fun `收不下就把线放掉，免得按钮一直画着而每一下都石沉大海`() {
        val control = McpControl().apply { attach(me) { false } }

        assertFalse(control.send(McpControl.Action.Reconnect("proj")))
        assertFalse(control.armed, "收不下还攥着处理器，界面就会一直画那几颗按钮")
    }

    @Test
    fun `发新动作之前先清掉上一次的报错`() {
        // 留着的话：用户刚点了重连，却在读上一次的报错，会以为这次也失败了
        val control = McpControl().apply { attach(me) { true } }
        control.report(McpControl.Action.Reconnect("proj"), "Connection closed")

        control.send(McpControl.Action.Reconnect("proj"))

        assertNull(control.failure)
    }

    @Test
    fun `report(null) 是成功 —— 不记一笔，界面不必为一件事说两遍`() {
        val control = McpControl().apply { attach(me) { true } }
        control.report(McpControl.Action.Reconnect("proj"), "先失败一次")
        control.report(McpControl.Action.Reconnect("proj"), null)

        assertNull(control.failure)
    }

    @Test
    fun `换会话（重新挂上或摘掉）会把上一次的失败清掉`() {
        // 那句失败说的是上一个会话的事，留着会让新会话看起来"刚出过错"
        val control = McpControl().apply { attach(me) { true } }
        control.report(McpControl.Action.Toggle("proj", true), "没有了")

        control.detach(me)
        assertNull(control.failure)

        control.attach(me) { true }
        control.report(McpControl.Action.Toggle("proj", true), "又没了")
        control.attach(other) { true }
        assertNull(control.failure)
    }

    @Test
    fun `监听器在动作的每个节点上都被叫一次`() {
        // 界面靠它重画：armed 变了、失败变了，画出来的东西都得跟着变
        var fired = 0
        val control = McpControl().apply { addListener { fired++ } }
        control.attach(me) { true }
        val afterAttach = fired
        control.send(McpControl.Action.Reconnect("proj"))
        control.report(McpControl.Action.Reconnect("proj"), "Connection closed")
        control.detach(me)

        assertTrue(afterAttach >= 1, "挂上处理器时界面就该知道能画按钮了")
        assertTrue(fired > afterAttach, "发动作、记失败、摘线都该通知")
    }
}
