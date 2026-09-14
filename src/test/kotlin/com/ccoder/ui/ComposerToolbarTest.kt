package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent

/**
 * 工具栏上的发送键。
 *
 * 稿子里它是一个圆形强调色按钮，而原生 `JButton` 是方的、带 LookAndFeel 的
 * 渐变和边框 —— 整个输入区就它最"不是设计过的样子"。所以自绘。
 */
class ComposerToolbarTest {

    private fun click(component: java.awt.Component) {
        component.dispatchEvent(
            MouseEvent(
                component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 5, 5, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    @Test
    fun `是正方形 —— 圆的前提`() {
        val button = RoundSendButton()

        val size = button.preferredSize
        assertEquals(size.width, size.height, "不是正方形就画不成圆：$size")
        assertTrue(size.width > 16, "太小了按不中：$size")
    }

    @Test
    fun `状态跟着 mainButtonState 走`() {
        // 按钮只负责画，该是什么状态由那个纯函数决定（那边已有测试）
        val button = RoundSendButton()

        button.setState(mainButtonState(ready = true, busy = false, disconnected = false))
        assertEquals(MainAction.Send, button.action)

        button.setState(mainButtonState(ready = true, busy = true, disconnected = false))
        assertEquals(MainAction.Interrupt, button.action)

        button.setState(mainButtonState(ready = false, busy = false, disconnected = true))
        assertEquals(MainAction.Restart, button.action)
    }

    @Test
    fun `点击把动作报出去`() {
        val button = RoundSendButton()
        var got: MainAction? = null
        button.onClick = { got = button.action }

        button.setState(mainButtonState(ready = true, busy = true, disconnected = false))
        click(button)

        assertEquals(MainAction.Interrupt, got)
    }

    @Test
    fun `禁用时不报动作 —— 冒泡出去的点击会让面板误判`() {
        val button = RoundSendButton()
        var clicks = 0
        button.onClick = { clicks++ }

        button.setState(mainButtonState(ready = false, busy = false, disconnected = false))
        click(button)

        assertEquals(0, clicks, "禁用状态不该响应点击")
    }

    @Test
    fun `工具栏左侧是状态区，右侧是发送键`() {
        val model = javax.swing.JLabel()
        val mode = ModeLabel {}
        val effort = EffortLabel {}
        val send = RoundSendButton()

        val bar = buildComposerToolbar(model, mode, effort, send)
        val layout = bar.layout as java.awt.BorderLayout
        val west = layout.getLayoutComponent(java.awt.BorderLayout.WEST) as java.awt.Container

        assertTrue(west.components.contains(model), "模型在左")
        assertTrue(west.components.contains(mode), "权限模式也在左")
        assertTrue(west.components.contains(effort), "思考深度也在左")
        assertSame(send, layout.getLayoutComponent(java.awt.BorderLayout.EAST), "发送键在右")
        // 不透明会把卡片的底色盖住，工具栏就成了卡片里嵌的另一块
        assertFalse(bar.isOpaque, "工具栏不该自己填底")
    }
}
