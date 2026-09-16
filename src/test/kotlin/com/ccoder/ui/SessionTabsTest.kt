package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 关标签前该不该问一句。
 *
 * [SessionTabs] 本身起不了单测（要 Project / ToolWindow，同 ClaudePanel 的理由），
 * 所以判定抽成纯函数 [closeNeedsConfirm]，这里钉住它那张真值表。
 */
class SessionTabsTest {

    @Test
    fun `空闲且没有进程时直接关，不问`() {
        assertFalse(closeNeedsConfirm(starting = false, procAlive = false, busy = false, pendingPermissions = 0))
    }

    @Test
    fun `正在启动也算"在跑" —— 那一刻 proc 还是 null`() {
        // 点「＋」之后立刻点叉是最常见的路径：进程已经起来了（p.start() 跑过了），
        // 而 proc/client 要等下一次 invokeLater 才被赋值。只判 proc 会静默放过，
        // 结果是一个没人管的 node + claude 在后台烧额度
        assertTrue(closeNeedsConfirm(starting = true, procAlive = false, busy = false, pendingPermissions = 0))
    }

    @Test
    fun `四个条件各能单独触发确认`() {
        assertTrue(closeNeedsConfirm(starting = false, procAlive = true, busy = false, pendingPermissions = 0))
        assertTrue(closeNeedsConfirm(starting = false, procAlive = false, busy = true, pendingPermissions = 0))
        assertTrue(closeNeedsConfirm(starting = false, procAlive = false, busy = false, pendingPermissions = 1))
    }

    @Test
    fun `上限是 5`() {
        // 产品决定（用户 2026-09-16 拍板），不是性能保险 —— 改它要同时改设计稿
        assertEquals(5, MAX_SESSION_TABS)
    }

    @Test
    fun `工具窗口 id 只有一份`() {
        assertEquals("CCoder", TOOL_WINDOW_ID)
    }
}
