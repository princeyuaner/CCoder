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
 * 按钮 —— 点了没反应更像坏了。所以到上限时是**置灰**而不是"点了才说"
 * （设计稿 §3.2）。
 *
 * **2026-09-16**：置灰的条件从"忙"换成了"标签数到上限" —— 多标签之后新建
 * 不再停掉当前会话，忙、有待决权限都照样能开（见 [newTabEnabled]）。
 */
class SessionNewButtonTest {

    @Test
    fun `没到上限时可点`() {
        val b = SessionNewButton {}
        b.setTabState(1)
        assertTrue(b.isEnabled)

        b.setTabState(MAX_SESSION_TABS - 1)
        assertTrue(b.isEnabled, "还剩一个位置也该能点")
    }

    @Test
    fun `到上限时置灰`() {
        val b = SessionNewButton {}

        b.setTabState(MAX_SESSION_TABS)
        assertFalse(b.isEnabled, "满了就不该还能开")

        b.setTabState(MAX_SESSION_TABS + 1)
        assertFalse(b.isEnabled, "超过上限（理论上不该发生）也不该能点")
    }

    @Test
    fun `到上限时说清先做什么`() {
        val b = SessionNewButton {}

        b.setTabState(1)
        assertEquals("新建会话", b.toolTipText)

        b.setTabState(MAX_SESSION_TABS)
        assertNotNull(b.toolTipText)
        assertTrue(b.toolTipText.contains("先关掉一个"), "实际：${b.toolTipText}")
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
        b.setTabState(MAX_SESSION_TABS)

        b.doClick()

        assertEquals(0, clicks, "置灰了还能点")
    }
}
