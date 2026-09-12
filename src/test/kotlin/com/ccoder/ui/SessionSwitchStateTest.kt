package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    // ---- 新建会话 ----

    @Test
    fun `空闲时才能新建`() {
        assertTrue(newSessionEnabled(SwitchBlock.None))
    }

    @Test
    fun `忙时不能新建`() {
        // 与切换会话拦的是同一件事：新建同样要 stopSession()，
        // 会把正在跑的回合腰斩
        assertFalse(newSessionEnabled(SwitchBlock.TurnRunning))
        assertFalse(newSessionEnabled(SwitchBlock.PermissionPending))
    }

    @Test
    fun `能点时提示说的是它做什么，不能点时说的是先做什么`() {
        assertEquals("新建会话", newSessionTooltip(SwitchBlock.None))

        val blocked = newSessionTooltip(SwitchBlock.TurnRunning)
        assertTrue(blocked.contains("停止"), "实际：$blocked")
    }

    // ---- 标题与删除确认语 ----

    private fun info(summary: String? = null, firstPrompt: String? = null) =
        SessionInfo("s1", summary, firstPrompt, 0L)

    @Test
    fun `标题三级降级`() {
        assertEquals("这是摘要", sessionTitle(info(summary = "这是摘要", firstPrompt = "首问")))
        assertEquals("首问", sessionTitle(info(summary = "  ", firstPrompt = "首问")))
        assertEquals("（无标题）", sessionTitle(info(summary = "", firstPrompt = null)))
    }

    @Test
    fun `删普通会话时确认语点出是哪一个`() {
        val prompt = deleteConfirmPrompt(info(summary = "这是什么项目"), isCurrent = false)
        assertTrue(prompt.contains("这是什么项目"), "实际：$prompt")
    }

    @Test
    fun `删当前会话时确认语说的是后果`() {
        // 用户已经知道自己点了哪一行；他需要知道的是"删掉之后会发生什么" ——
        // 转写区会清空、回到新会话
        val prompt = deleteConfirmPrompt(info(summary = "这是什么项目"), isCurrent = true)
        assertTrue(prompt.contains("当前"), "实际：$prompt")
        assertTrue(prompt.contains("清空"), "实际：$prompt")
    }
}
