package com.ccoder.ui

import com.ccoder.sidecar.RequestOutcome
import com.ccoder.sidecar.SessionInfo
import com.ccoder.sidecar.SidecarMessage
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

    // ---- 打开面板时恢复哪一条 ----

    private fun at(sessionId: String, lastModified: Long, summary: String? = null) =
        SessionInfo(sessionId, summary, null, lastModified)

    @Test
    fun `没有历史会话时不恢复`() {
        // 首次使用。这里必须给 null —— 打开面板要退回"开新会话"，
        // 给一个占位会话的话，用户会进到一个不存在的东西里
        assertNull(mostRecentSession(emptyList<SessionInfo>()))
    }

    @Test
    fun `取修改时间最新的一条，与列表给的顺序无关`() {
        // 实测 SDK 是新的在顶上，但它的文档没承诺排序。
        // 按下标取 first() 的话，哪天顺序变了会安静地恢复错的那一条
        val picked = mostRecentSession(
            listOf(
                at("旧的", 1_000),
                at("最新的", 3_000),
                at("中间的", 2_000),
            )
        )
        assertEquals("最新的", picked?.sessionId)
    }

    @Test
    fun `修改时间并列时取列表里靠前的那条`() {
        // 并列是可能的（两次改动落在同一毫秒）。规则要定死，
        // 否则同一个列表在不同调用里可能给出不同答案
        val picked = mostRecentSession(listOf(at("前", 5_000), at("后", 5_000)))
        assertEquals("前", picked?.sessionId)
    }

    // ---- 打开面板时那份回执的读法 ----

    @Test
    fun `有历史就挑最新的一条恢复`() {
        val pick = openPick(
            RequestOutcome.Answered(
                SidecarMessage.SessionList(
                    "r1",
                    listOf(at("旧的", 1_000), at("最新的", 2_000)),
                )
            )
        )
        assertEquals(OpenPick.Resume(at("最新的", 2_000)), pick)
    }

    @Test
    fun `没有历史会话是正常情况，不提示`() {
        // 首次使用就是这样。这里若算成"失败"，用户每开一个新项目都会收到
        // 一句"列不出历史会话" —— 而其实什么都没出错
        val pick = openPick(RequestOutcome.Answered(SidecarMessage.SessionList("r1", emptyList())))
        assertEquals(OpenPick.None, pick)
    }

    @Test
    fun `请求失败要说得出原因`() {
        // 与上面那条的区别正是"不静默"：问不出来是异常，
        // 得让用户知道为什么打开面板没回到上次那条
        val pick = openPick(RequestOutcome.Failed("请求超时"))
        assertEquals(OpenPick.Unavailable("请求超时"), pick)
    }

    @Test
    fun `回执是别的消息时也算问不出来`() {
        val pick = openPick(RequestOutcome.Answered(SidecarMessage.SessionDeleted("r1", "s1")))
        assertEquals(true, pick is OpenPick.Unavailable, "实际：$pick")
    }

    // ---- 标签上的会话名 ----

    @Test
    fun `标签标题没有标题时给 null，而不是占位文字`() {
        // 标签的显示规则是"有标题显示标题，没有显示斜体「新会话」"。
        // 这里若给「（无标题）」，打开一个无标题的会话会把标签写成一个
        // 看起来像真标题的东西 —— 那是列表行的占位，不是标签的
        assertEquals("这是摘要", sessionLabelTitle(info(summary = "这是摘要", firstPrompt = "首问")))
        assertEquals("首问", sessionLabelTitle(info(summary = "  ", firstPrompt = "首问")))
        assertNull(sessionLabelTitle(info(summary = "", firstPrompt = null)))
    }
}
