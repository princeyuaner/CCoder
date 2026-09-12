package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 忙时能否切换会话。
 *
 * 拦住的理由不是"体验"，是正确性：切换会 stopSession()，正在跑的回合被腰斩，
 * 挂着的权限询问也会一并作废（sidecar 的 denyAllPending）。
 */
class SessionSwitchStateTest {

    @Test
    fun `空闲时可切`() {
        assertEquals(SwitchBlock.None, switchBlock(busy = false, pendingPermissions = 0))
        assertNull(switchBlockNotice(SwitchBlock.None), "可切时没有话要说")
    }

    @Test
    fun `回合进行中拦住`() {
        assertEquals(SwitchBlock.TurnRunning, switchBlock(busy = true, pendingPermissions = 0))
        assertNotNull(switchBlockNotice(SwitchBlock.TurnRunning))
    }

    @Test
    fun `有权限询问挂着时拦住`() {
        assertEquals(SwitchBlock.PermissionPending, switchBlock(busy = false, pendingPermissions = 1))
    }

    @Test
    fun `权限挂起优先于回合进行中`() {
        // 有权限挂着时 busy 通常也是 true，但"先处理那条询问"才是用户
        // 该做的动作 —— 提示得更具体才有用
        assertEquals(SwitchBlock.PermissionPending, switchBlock(busy = true, pendingPermissions = 2))
    }

    @Test
    fun `两句提示都指向具体动作`() {
        // 光说"不能切"没用，得说清先做什么
        val running = switchBlockNotice(SwitchBlock.TurnRunning)!!
        val pending = switchBlockNotice(SwitchBlock.PermissionPending)!!
        assertEquals(true, running.contains("停止"), "实际：$running")
        assertEquals(true, pending.contains("权限"), "实际：$pending")
    }
}
