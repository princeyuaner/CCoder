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
}
