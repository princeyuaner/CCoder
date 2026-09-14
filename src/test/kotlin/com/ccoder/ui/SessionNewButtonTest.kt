package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 顶部那一行最右的「＋」。
 *
 * 它和会话标签的区别是：标签点了有东西可看（弹出列表），而它是个单一动作
 * 按钮 —— 点了没反应更像坏了。所以忙时是**置灰**而不是"点了才说"
 * （设计稿 §3.2）。
 */
class SessionNewButtonTest {

    @Test
    fun `空闲时可点`() {
        val b = SessionNewButton {}
        b.setBlock(SwitchBlock.None)
        assertTrue(b.isEnabled)
    }

    @Test
    fun `忙时置灰`() {
        val b = SessionNewButton {}

        b.setBlock(SwitchBlock.TurnRunning)
        assertFalse(b.isEnabled, "回合进行中不该能新建")

        b.setBlock(SwitchBlock.PermissionPending)
        assertFalse(b.isEnabled, "有权限挂着时不该能新建")
    }

    @Test
    fun `不能点时说清先做什么`() {
        val b = SessionNewButton {}

        b.setBlock(SwitchBlock.None)
        assertEquals("新建会话", b.toolTipText)

        b.setBlock(SwitchBlock.TurnRunning)
        assertNotNull(b.toolTipText)
        assertTrue(b.toolTipText.contains("停止"), "实际：${b.toolTipText}")
    }

    @Test
    fun `点击把动作报出去`() {
        var clicks = 0
        val b = SessionNewButton { clicks++ }

        b.doClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `置灰时点不动`() {
        var clicks = 0
        val b = SessionNewButton { clicks++ }
        b.setBlock(SwitchBlock.TurnRunning)

        b.doClick()

        assertEquals(0, clicks, "置灰了还能点")
    }
}
