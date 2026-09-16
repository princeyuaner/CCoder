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

    // ---- 新建会话（「＋」＝开一个新标签，2026-09-16）----

    @Test
    fun `没到上限就能开新标签`() {
        assertTrue(newTabEnabled(0))
        assertTrue(newTabEnabled(1))
        assertTrue(newTabEnabled(MAX_SESSION_TABS - 1))
    }

    @Test
    fun `到上限就不能开了`() {
        // 这是唯一的闸：忙、有待决权限都不再拦（新建不再停当前会话）
        assertFalse(newTabEnabled(MAX_SESSION_TABS))
        assertFalse(newTabEnabled(MAX_SESSION_TABS + 1))
    }

    @Test
    fun `能开时提示说的是它做什么，到上限时说的是先做什么`() {
        assertEquals("新建会话", newTabTooltip(1))

        val full = newTabTooltip(MAX_SESSION_TABS)
        assertTrue(full.contains("先关掉一个"), "实际：$full")
    }

    // ---- 标题与删除确认语 ----

    private fun info(summary: String? = null, firstPrompt: String? = null) =
        SessionInfo("s1", summary, firstPrompt, 0L)

    @Test
    fun `标题三级降级 —— 自己说的第一句优先于 CLI 的摘要`() {
        // 2026-09-15 用户要求把顺序改成 firstPrompt 优先。summary 是 CLI 给的、
        // 可能随会话内容变化 —— 那样名字会飘，认不出是哪条会话；
        // 自己说的第一句是稳的，也与 titleFromFirstMessage 那条规矩一致
        assertEquals("首问", sessionTitle(info(summary = "这是摘要", firstPrompt = "首问")))
        // 没有第一句时才轮到摘要
        assertEquals("这是摘要", sessionTitle(info(summary = "这是摘要", firstPrompt = "  ")))
        assertEquals("（无标题）", sessionTitle(info(summary = "", firstPrompt = null)))
    }

    // ---- 标题就是一段文本的开头（2026-09-15 用户要求）----

    @Test
    fun `标题取开头，超长的截断`() {
        val long = "帮我把设置界面重新设计一下，出几个网页方案给我选择，顺便把那个空栏删掉"

        val snippet = titleSnippet(long)

        assertEquals(SESSION_TITLE_MAX + 1, snippet.length, "截断后应当是 N 个字 + 一个省略号")
        assertTrue(snippet.startsWith("帮我把设置界面重新设计一下"), "实际：$snippet")
        assertTrue(snippet.endsWith("…"), "截断了却没有省略号，看着像本来就短")
    }

    @Test
    fun `够短的标题一个字不动`() {
        assertEquals("改个按钮", titleSnippet("改个按钮"))
    }

    /**
     * **换行必须压成空格。**
     *
     * 第一条消息经常是多行贴进来的，而顶上那个标签只有一行 ——
     * 不压的话换行符会把它撑成两行，把「＋」和齿轮挤出去。
     */
    @Test
    fun `多行的第一条消息压成一行`() {
        val typed = "看下这个报错\n\n  Caused by: java.lang.NullPointerException\n  at Foo.kt:12  "

        val snippet = titleSnippet(typed)

        assertFalse(snippet.contains("\n"), "标题里还有换行：$snippet")
        assertFalse(snippet.contains("  "), "空白没合干净：$snippet")
        assertTrue(snippet.startsWith("看下这个报错 Caused by"), "实际：$snippet")
    }

    @Test
    fun `全是空白时给空串，不给 null`() {
        assertEquals("", titleSnippet("   \n\t  "))
    }

    /** 用户自己起的名字也走同一条规则 —— 截断的规则只有一个。 */
    @Test
    fun `自己起的名字太长也截断`() {
        val long = "一二三四五六七八九十一二三四五六七八九十一二三四五"
        val title = sessionLabelTitle(
            SessionInfo("s1", null, "首问", 0L, customTitle = long)
        )

        assertEquals(SESSION_TITLE_MAX + 1, title?.length)
    }

    // ---- 刚发出去的那条要不要认成标题 ----

    @Test
    fun `全新会话的第一条消息就是标题`() {
        assertEquals("帮我看看这个", titleFromFirstMessage("帮我看看这个", current = null))
    }

    @Test
    fun `已经有标题了就不再改`() {
        assertNull(
            titleFromFirstMessage("第二条消息", current = "第一条消息"),
            "标题被第二条消息顶掉了 —— 会话标题应当是最初那条",
        )
    }

    /**
     * 斜杠命令不算标题。
     *
     * 否则新会话的第一件事如果是 `/clear` 或 `/help`，顶上就挂着一个
     * 对不上号的 `/help` —— 而那根本不是聊天内容。
     */
    @Test
    fun `斜杠命令不当标题`() {
        assertNull(titleFromFirstMessage("/clear", current = null))
        assertNull(titleFromFirstMessage("/help", current = null))
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

    @Test
    fun `恢复最近会话时跳过已被别的标签占住的那条`() {
        // 多标签：两个标签各自 listSessions 挑"最近那条"，很容易撞同一条 ——
        // 两边同时写同一个 jsonl（见 OpenSessions）
        val all = listOf(at("最新的", 3_000), at("次新的", 2_000), at("旧的", 1_000))

        assertEquals("最新的", mostRecentSession(all)?.sessionId, "没人占时行为不变")
        assertEquals("次新的", mostRecentSession(all) { it == "最新的" }?.sessionId)
        assertNull(mostRecentSession(all) { true }, "全被占 → null，调用方据此开新会话")
    }

    @Test
    fun `openPick 也吃这套跳过规则`() {
        val pick = openPick(
            RequestOutcome.Answered(
                SidecarMessage.SessionList("r1", listOf(at("最新的", 3_000), at("次新的", 2_000)))
            )
        ) { it == "最新的" }

        assertEquals(OpenPick.Resume(at("次新的", 2_000)), pick)
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
        assertEquals("首问", sessionLabelTitle(info(summary = "这是摘要", firstPrompt = "首问")))
        assertEquals("这是摘要", sessionLabelTitle(info(summary = "这是摘要", firstPrompt = "  ")))
        assertNull(sessionLabelTitle(info(summary = "", firstPrompt = null)))
    }

    // ---- /clear 换会话 ----

    @Test
    fun `id 变了就是换了会话`() {
        assertTrue(isSessionSwitch("aaa", "bbb"))
    }

    @Test
    fun `id 没变不是换会话 —— init 每个回合都发一次`() {
        assertFalse(
            isSessionSwitch("aaa", "aaa"),
            "每回合都判一次换会话的话，转写区会被清空无数次",
        )
    }

    @Test
    fun `第一次拿到 id 不算换会话`() {
        assertFalse(
            isSessionSwitch(null, "aaa"),
            "全新会话的第一个 init 就是这种情况：该做的是填上 id，不是清空转写区",
        )
    }

    @Test
    fun `任一边缺了就当作没换`() {
        assertFalse(isSessionSwitch("aaa", null))
        assertFalse(isSessionSwitch(null, null))
    }
}
