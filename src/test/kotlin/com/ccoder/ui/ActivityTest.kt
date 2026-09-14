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

    private fun change(item: RenderItem) = activityChangeOf(item)

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
    fun `工具结果不动 —— 一轮是「调用 → 结果 → 思考 → 调用」，中途清空会闪一下空闲`() {
        assertTrue(change(toolResult()) is ActivityChange.Keep)
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
