package com.ccoder.ui

import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "现在在做什么"的映射（连接卡上那行字）。
 *
 * 用户的原话是「我希望能实时显示当前在做什么，比如思考中，编辑文件，运行指令等等」——
 * 这行字要一眼看得懂，所以盯着两件事：**认不认得出来**、**一轮中间会不会闪回空闲**。
 *
 * 2026-09-20 起这里比的是 [Activity] 枚举而不是中文串：原先那十个 `const val`
 * 与 `ClaudePanel` 里的比较是"常量对常量"，翻译之后会静默失配（秒表不走、卡停在错误的档）。
 */
class ActivityTest {

    private fun change(item: RenderItem, toolsStillRunning: Boolean = false) =
        activityChangeOf(item, toolsStillRunning)

    private fun now(item: RenderItem) = (change(item) as ActivityChange.Now).activity

    @Test
    fun `思考增量与整块思考都说思考中`() {
        assertEquals(Activity.Thinking, now(RenderItem.ThinkingDelta("嗯")))
        assertEquals(Activity.Thinking, now(RenderItem.Thinking("想完了")))
    }

    @Test
    fun `正文增量与整块正文都说回复中`() {
        assertEquals(Activity.Replying, now(RenderItem.AssistantDelta("在")))
        assertEquals(Activity.Replying, now(RenderItem.AssistantText("在的")))
    }

    @Test
    fun `工具刚起头就说动作词 —— 不必等到参数生成完`() {
        // 参数生成那一段（大文件可能十几秒）转写区是静的，状态卡是唯一能说明
        // "它还在动"的地方。等到 ToolUse 才改口就晚了 —— 那正是要补的那段窗口
        assertEquals(Activity.Editing, now(RenderItem.ToolStarting("Write")))
        assertEquals(Activity.Running, now(RenderItem.ToolStarting("Bash")))
        assertEquals(Activity.Tool, now(RenderItem.ToolStarting("mcp__whatever")))
    }

    @Test
    fun `工具调用按工具名给动作词`() {
        assertEquals(Activity.Running, now(tool("Bash")))
        assertEquals(Activity.Running, now(tool("BashOutput")))
        assertEquals(Activity.Editing, now(tool("Edit")))
        assertEquals(Activity.Editing, now(tool("MultiEdit")))
        assertEquals(Activity.Editing, now(tool("Write")))
        assertEquals(Activity.Reading, now(tool("Read")))
        assertEquals(Activity.Reading, now(tool("NotebookRead")))
        assertEquals(Activity.Searching, now(tool("Grep")))
        assertEquals(Activity.Searching, now(tool("Glob")))
        assertEquals(Activity.Agent, now(tool("Task")))
    }

    @Test
    fun `认不出的工具说调用工具 —— 不把 MCP 的工具名写进那格`() {
        // 那格只有 95px：写 `mcp__codegraph__explore` 既放不下，也没人认得出
        assertEquals(Activity.Tool, now(tool("mcp__codegraph__explore")))
        assertEquals(Activity.Tool, now(tool("WebFetch")))
    }

    @Test
    fun `一批工具跑完了就说等待响应 —— 那几十秒的 TTFT 落在这儿`() {
        // 2026-09-15 用户报"调用工具后会突然卡几十秒，然后说思考中"。那几十秒是
        // 上游还没吐出第一个 token（实测这一段 P50 3.2s / P90 10.3s / P99 38.8s /
        // 最长 89s，且慢窗口之后模型先吐的**全是 thinking 块**）。这期间卡上原来
        // 还写着「运行指令」—— 一句假话，于是看起来就成了"卡住"。
        //
        // **不是回空闲**：空闲是"已连接"，那会在一轮中间撒谎。
        assertEquals(Activity.Waiting, now(toolResult()))
    }

    @Test
    fun `还有别的工具在跑时不改口 —— 并行调用里一个结果不代表整批完了`() {
        assertTrue(change(toolResult(), toolsStillRunning = true) is ActivityChange.Keep)
    }

    @Test
    fun `只有回合结束与报错才回空闲`() {
        assertTrue(change(RenderItem.Result(subtype = "success", costUsd = null, durationMs = null)) is ActivityChange.Idle)
        assertTrue(change(RenderItem.ErrorItem("认证失败")) is ActivityChange.Idle)
    }

    @Test
    fun `用户提问与系统提示都不改动作`() {
        assertTrue(change(RenderItem.UserText("你好")) is ActivityChange.Keep)
        assertTrue(change(RenderItem.SystemNote("会话已就绪")) is ActivityChange.Keep)
    }

    @Test
    fun `每个动作词都短得住 —— 卡面只有约 95px`() {
        // 中文那一栏是按**汉字**量的（≤4 个字 ≈ 52px，留出图标与内边距）；
        // 拉丁字母窄得多，英文那份的实际上限由 StatusCardsRenderProbe 在英文下
        // 量出来看（设计稿 §8），这里只给一个"明显放不下"的粗界。
        val max = if (CcoderText.tag() == "zh") 6 else 12

        for (activity in Activity.entries) {
            val text = activity.text()
            assertTrue(text.length <= max, "「$text」有 ${text.length} 个字符，卡面放不下")
        }
    }

    private fun tool(name: String) = RenderItem.ToolUse(name = name, input = "{}", id = "t1")

    private fun toolResult() = RenderItem.ToolResult(toolUseId = "t1", text = "ok", isError = false)

    // ---- 归属：这活是主线程还是子代理在跑（连接卡上那个小角标）----

    @Test
    fun `带 parent 的项就是子代理的，主线程的不带`() {
        // 子代理的工具/思考/正文都带 parent（见 MessageRenderer.renderAssistant）——
        // 动作词与主线程长得一样，2026-09-20 起靠这个位在卡上画个小点区分
        assertTrue(subagentOf(RenderItem.ToolUse("Read", "{}", "t1", parent = "task1"), previous = false))
        assertTrue(subagentOf(RenderItem.Thinking("看完了", parent = "task1"), previous = false))
        assertTrue(subagentOf(RenderItem.AssistantText("它是这么写的", parent = "task1"), previous = false))

        assertFalse(subagentOf(RenderItem.ToolUse("Read", "{}", "t2", parent = null), previous = false))
        assertFalse(subagentOf(RenderItem.Thinking("看完了", parent = null), previous = false))
        assertFalse(subagentOf(RenderItem.AssistantText("它是这么写的", parent = null), previous = false))
    }

    @Test
    fun `等待响应继承归属 —— 子代理调完工具那段静默别让角标闪`() {
        // 工具结果会切成「等待响应」，而那次等待（TTFT，实测 P90 10.3s）等的人
        // 还是刚才那个跑者。不继承的话角标每调一次工具就灭一下
        assertTrue(subagentOf(toolResult(), previous = true))
        assertFalse(subagentOf(toolResult(), previous = false))
    }

    @Test
    fun `回合结束与报错把归属清干净`() {
        assertFalse(
            subagentOf(
                RenderItem.Result(subtype = "success", costUsd = null, durationMs = null),
                previous = true,
            )
        )
        assertFalse(subagentOf(RenderItem.ErrorItem("认证失败"), previous = true))
    }

    @Test
    fun `起头帧与增量帧本来就没有归属`() {
        // 流事件只走主线程（子代理设计稿事实 5/12）：它们不该把角标点亮
        assertFalse(subagentOf(RenderItem.ToolStarting("Write"), previous = true))
        assertFalse(subagentOf(RenderItem.AssistantDelta("在"), previous = true))
        assertFalse(subagentOf(RenderItem.ThinkingDelta("嗯"), previous = true))
    }
}
