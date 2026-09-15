package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "现在在做什么"的映射（连接卡上那行字）。
 *
 * 用户的原话是「我希望能实时显示当前在做什么，比如思考中，编辑文件，运行指令等等」——
 * 这行字要一眼看得懂，所以盯着两件事：**认不认得出来**、**一轮中间会不会闪回空闲**。
 */
class ActivityTest {

    private fun change(item: RenderItem, toolsStillRunning: Boolean = false) =
        activityChangeOf(item, toolsStillRunning)

    private fun now(item: RenderItem) = (change(item) as ActivityChange.Now).text

    @Test
    fun `思考增量与整块思考都说思考中`() {
        assertEquals(ACTIVITY_THINKING, now(RenderItem.ThinkingDelta("嗯")))
        assertEquals(ACTIVITY_THINKING, now(RenderItem.Thinking("想完了")))
    }

    @Test
    fun `正文增量与整块正文都说回复中`() {
        assertEquals(ACTIVITY_REPLYING, now(RenderItem.AssistantDelta("在")))
        assertEquals(ACTIVITY_REPLYING, now(RenderItem.AssistantText("在的")))
    }

    @Test
    fun `工具刚起头就说动作词 —— 不必等到参数生成完`() {
        // 参数生成那一段（大文件可能十几秒）转写区是静的，状态卡是唯一能说明
        // "它还在动"的地方。等到 ToolUse 才改口就晚了 —— 那正是要补的那段窗口
        assertEquals(ACTIVITY_EDITING, now(RenderItem.ToolStarting("Write")))
        assertEquals(ACTIVITY_RUNNING, now(RenderItem.ToolStarting("Bash")))
        assertEquals(ACTIVITY_TOOL, now(RenderItem.ToolStarting("mcp__whatever")))
    }

    @Test
    fun `工具调用按工具名给动作词`() {
        assertEquals(ACTIVITY_RUNNING, now(tool("Bash")))
        assertEquals(ACTIVITY_RUNNING, now(tool("BashOutput")))
        assertEquals(ACTIVITY_EDITING, now(tool("Edit")))
        assertEquals(ACTIVITY_EDITING, now(tool("MultiEdit")))
        assertEquals(ACTIVITY_EDITING, now(tool("Write")))
        assertEquals(ACTIVITY_READING, now(tool("Read")))
        assertEquals(ACTIVITY_READING, now(tool("NotebookRead")))
        assertEquals(ACTIVITY_SEARCHING, now(tool("Grep")))
        assertEquals(ACTIVITY_SEARCHING, now(tool("Glob")))
        assertEquals(ACTIVITY_AGENT, now(tool("Task")))
    }

    @Test
    fun `认不出的工具说调用工具 —— 不把 MCP 的工具名写进那格`() {
        // 那格只有 95px：写 `mcp__codegraph__explore` 既放不下，也没人认得出
        assertEquals(ACTIVITY_TOOL, now(tool("mcp__codegraph__explore")))
        assertEquals(ACTIVITY_TOOL, now(tool("WebFetch")))
    }

    @Test
    fun `一批工具跑完了就说等待响应 —— 那几十秒的 TTFT 落在这儿`() {
        // 2026-09-15 用户报"调用工具后会突然卡几十秒，然后说思考中"。那几十秒是
        // 上游还没吐出第一个 token（实测这一段 P50 3.2s / P90 10.3s / P99 38.8s /
        // 最长 89s，且慢窗口之后模型先吐的**全是 thinking 块**）。这期间卡上原来
        // 还写着「运行指令」—— 一句假话，于是看起来就成了"卡住"。
        //
        // **不是回空闲**：空闲是"已连接"，那会在一轮中间撒谎。
        assertEquals(ACTIVITY_WAITING, now(toolResult()))
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
        val all = listOf(
            ACTIVITY_WAITING, ACTIVITY_THINKING, ACTIVITY_REPLYING, ACTIVITY_RUNNING,
            ACTIVITY_EDITING, ACTIVITY_READING, ACTIVITY_SEARCHING, ACTIVITY_TOOL,
            ACTIVITY_AGENT, ACTIVITY_PERMISSION,
        )
        for (text in all) {
            assertTrue(text.length <= 6, "「$text」有 ${text.length} 个字，卡面放不下")
        }
    }

    private fun tool(name: String) = RenderItem.ToolUse(name = name, input = "{}", id = "t1")

    private fun toolResult() = RenderItem.ToolResult(toolUseId = "t1", text = "ok", isError = false)
}
