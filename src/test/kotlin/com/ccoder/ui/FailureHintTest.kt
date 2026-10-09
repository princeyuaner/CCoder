package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 错误码 → 提示的纯函数用例。
 *
 * 这些字符串是**用户唯一的出路**：只说"失败了"，他无从下手。所以逐条钉住
 * "原文还在不在 + 引导指向哪儿"。
 */
class FailureHintTest {

    private val raw = "authentication_failed: 401 Invalid token"

    @Test
    fun `认证失败保留原文，并指向 settings_json 与环境变量污染`() {
        val got = failureHintText("AUTH_FAILED", raw)

        assertTrue(got.startsWith(raw), "原文必须在最前面（它是唯一的事实）：$got")
        assertTrue(got.contains("ANTHROPIC_AUTH_TOKEN"), "要点名是哪个键：$got")
        assertTrue(got.contains("环境变量污染"), "第二条常见原因也要说：$got")
    }

    @Test
    fun `模式切换失败说清"没切成功"，并指出查哪儿`() {
        val got = failureHintText("SET_MODE_FAILED", "cannot set mode")

        assertTrue(got.contains("没有切换"), "得说清现状：$got")
        assertTrue(got.contains("disableBypassPermissionsMode"), "得指出查哪个设置：$got")
    }

    @Test
    fun `切档失败把"CLI 可能太老"这条摆在明面上`() {
        val got = failureHintText("SET_EFFORT_FAILED", "unknown control request")

        assertTrue(got.contains("较新版本"), "得提示版本这条可能：$got")
        assertTrue(got.contains("重开会话"), "得给出路：$got")
    }

    /**
     * 2026-09-18 新加的那条（终止钮）。失败时最要紧的是"它还在跑"这个事实，
     * 以及还有别的停法 —— 只说一句失败，用户会以为已经停过了。
     */
    @Test
    fun `终止失败说清"它还在跑"，并给出第二条停法`() {
        val got = failureHintText("STOP_TASK_FAILED", "没有这个任务")

        assertTrue(got.startsWith("没有这个任务"), "原文必须在最前面（它是唯一的事实）：$got")
        assertTrue(got.contains("还在跑"), "得说清现状：$got")
        assertTrue(got.contains("停止"), "得给出别的停法：$got")
    }

    /**
     * 2026-09-17 新加的那条：以前 claude 找不到时，用户看到的只有
     * sidecar 那句「未找到 claude 可执行文件」，不知道去哪儿装。
     */
    @Test
    fun `claude 找不到时指到环境页那块`() {
        val got = failureHintText("CLAUDE_NOT_FOUND", "未找到 claude 可执行文件。")

        assertTrue(got.contains("运行依赖"), "得指到那一块：$got")
        assertTrue(got.contains("重新检测"), "得说清装完做什么：$got")
    }

    @Test
    fun `没见过的错误码、没有错误码，都原样透传`() {
        assertEquals(raw, failureHintText("SOMETHING_ELSE", raw))
        assertEquals(raw, failureHintText(null, raw))
        assertEquals("", failureHintText(null, ""))
    }

    /**
     * 2026-09-28 新加的那条：MCP 的两颗动作（重连 / 启用停用）。
     *
     * 最要紧的是**别让用户以为"重连"能修好一条本来就坏的配置** —— 它只负责
     * 再试一次，真起不来的话要去改的是那条命令本身。
     */
    @Test
    fun `MCP 动作失败时说清它只负责再试一次，并指到改哪儿`() {
        val got = failureHintText("MCP_RECONNECT_FAILED", "Connection closed")

        assertTrue(got.startsWith("Connection closed"), "原文必须在最前面（它是唯一的事实）：$got")
        assertTrue(got.contains("再连一次"), "得说清这一颗做了什么（没做什么）：$got")
        assertTrue(got.contains("MCP"), "得指到改命令的那一页：$got")
    }

    @Test
    fun `启用停用失败与重连失败共用同一段说明`() {
        // 两者要说的话一模一样（都是"只再试一次，改命令去 MCP 页"），
        // 一个码一段只会多一份要维护的散文
        assertEquals(
            failureHintText("MCP_RECONNECT_FAILED", "boom"),
            failureHintText("MCP_TOGGLE_FAILED", "boom"),
        )
    }
}
