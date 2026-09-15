package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 状态栏那一行字。
 *
 * 只测纯函数：组件本身要真实 `Project` 才活得起来（见 [PendingPermissionStatusBar]），
 * 而"什么时候显示什么、点下去该去哪儿"这件事与 Swing 无关 —— 与
 * `connectionTone` / `switchBlock` 同一个做法。
 */
class PendingPermissionStatusBarTest {

    @Test
    fun `没有待决时不占状态栏`() {
        assertEquals("", statusBarText(0, askSuspended = false))
        assertEquals("", statusBarTooltip(0, askSuspended = false))
    }

    @Test
    fun `有待决时显示数量`() {
        assertEquals("Claude 待确认：2", statusBarText(2, askSuspended = false))
    }

    @Test
    fun `挂起提问优先于数量`() {
        // 被最小化的那条提问本来就计在数量里。这时用户要找的是他刚才收起来的
        // 那个框，而"待确认：1"说不清点下去会发生什么 —— 所以文案换掉。
        assertEquals("Claude 有提问待回答", statusBarText(1, askSuspended = true))
        assertEquals("Claude 有提问待回答", statusBarText(3, askSuspended = true))
    }

    @Test
    fun `提示说清点下去会发生什么`() {
        assertTrue(
            statusBarTooltip(1, askSuspended = true).contains("回到那个框"),
            "挂起时的提示得说清点它会回到提问",
        )
        // 口径要准：队列里既有授权请求也有提问，只说"授权请求"是错的
        // （2026-09-15 之前就是这么写的，而提问一直混在里面）
        assertTrue(
            statusBarTooltip(2, askSuspended = false).contains("授权 / 提问"),
            "提示的口径要把提问算进去",
        )
    }
}
