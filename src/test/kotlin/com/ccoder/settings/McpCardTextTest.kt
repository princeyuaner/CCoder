package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import com.ccoder.ui.Tone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 卡片与弹窗上那几行字（`McpCardText.kt`）。
 *
 * 2026-09-30 改版把它们从"表单里的控件"挪到了"卡片上的几行"，
 * 于是这几条从"顺手"变成了**页面上唯一能看见的东西** —— 尤其两条：
 *
 *  - 变量那行**只有键名**（值里常有 token，卡片是会被截图的地方）
 *  - 状态取不到就是 null（**不编一个"未连接"**）
 */
class McpCardTextTest {

    private fun stdio(
        name: String = "a",
        command: String = "npx",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ) = McpServer(
        name = name,
        command = command,
        args = args.toMutableList(),
        env = env.toMutableMap(),
    )

    private fun remote(url: String = "https://x/mcp", headers: Map<String, String> = emptyMap()) = McpServer(
        name = "r",
        kind = McpKind.HTTP.name,
        url = url,
        headers = headers.toMutableMap(),
    )

    @Test
    fun `命令行那种把命令与参数连成一行`() {
        assertEquals("npx -y codegraph", mcpCommandLineOf(stdio(args = listOf("-y", "codegraph"))))
    }

    @Test
    fun `参数空着时不留一串空格`() {
        assertEquals("npx", mcpCommandLineOf(stdio(command = " npx ", args = listOf("", "  "))))
    }

    @Test
    fun `远端那种那一行就是地址`() {
        assertEquals("https://mcp.context7.com/mcp", mcpCommandLineOf(remote(url = "https://mcp.context7.com/mcp")))
    }

    @Test
    fun `变量那行只有键名 —— 值一个字都不出现`() {
        val line = mcpVarsLineOf(stdio(env = mapOf("GITHUB_TOKEN" to "ghp_秘密", "LOG_LEVEL" to "debug")))

        assertEquals("环境变量 2 个：GITHUB_TOKEN、LOG_LEVEL", line)
        assertFalse(line!!.contains("ghp_秘密"), "变量值被画到卡片上了 —— 那是会被截图、被投屏的地方")
        assertFalse(line.contains("debug"))
    }

    @Test
    fun `一个变量都没有时那一行为空 —— 不画`() {
        assertNull(mcpVarsLineOf(stdio()))
    }

    @Test
    fun `请求头走同一套，只是说法不同`() {
        assertEquals("请求头 1 个：CONTEXT7_API_KEY", mcpVarsLineOf(remote(headers = mapOf("CONTEXT7_API_KEY" to "k"))))
    }

    @Test
    fun `连上的报工具数，没连上的只报状态`() {
        assertEquals("已连接 · 2 个工具", mcpStatusTextOf(McpServerStatus("a", "connected", "project", null, listOf("t1", "t2"))))
        assertEquals("失败", mcpStatusTextOf(McpServerStatus("a", "failed", "project", null, emptyList())))
    }

    @Test
    fun `只连上一件工具时说一个工具 —— 英文里 1 tools 是错的`() {
        assertEquals("已连接 · 1 个工具", mcpStatusTextOf(McpServerStatus("a", "connected", "user", null, listOf("t"))))
    }

    @Test
    fun `认不出的状态照原样显示，不编一个词`() {
        assertEquals("将来才有的一档", mcpStatusTextOf(McpServerStatus("a", "将来才有的一档", "user", null, emptyList())))
    }

    @Test
    fun `状态取不到就是 null —— 不编一个未连接`() {
        val status = McpStatus()

        // 还没问过
        assertNull(mcpStatusOf(status, "a"), "没问过时不该给一个状态位")

        // 问过了，但会话里没有这一台
        status.set(listOf(McpServerStatus("别的", "connected", "user", null, emptyList())))
        assertNull(mcpStatusOf(status, "a"))

        // 有这一台
        status.set(listOf(McpServerStatus("a", "connected", "user", null, emptyList())))
        assertEquals("connected", mcpStatusOf(status, "a")?.status)
    }

    @Test
    fun `色档分得开 —— 四档各归各位`() {
        assertEquals(Tone.Ok, mcpToneOf("connected"))
        assertEquals(Tone.Warn, mcpToneOf("pending"))
        assertEquals(Tone.Danger, mcpToneOf("failed"))
        assertEquals(Tone.Danger, mcpToneOf("needs-auth"))
        assertEquals(Tone.Idle, mcpToneOf("disabled"))
        assertEquals(Tone.Idle, mcpToneOf("将来才有的一档"), "认不出的不该乱点红")
    }

    @Test
    fun `名字空着不能提交`() {
        assertEquals("名字不能为空 —— 它就是 .mcp.json 里的键", mcpNameError("", emptyList()))
        assertEquals("名字不能为空 —— 它就是 .mcp.json 里的键", mcpNameError("   ", emptyList()))
    }

    @Test
    fun `重名不能提交 —— 同名会把别的那条顶掉`() {
        assertEquals("已经有一条叫「a」的 server", mcpNameError("a", listOf("a", "b")))
        assertNull(mcpNameError("c", listOf("a", "b")))
    }

    @Test
    fun `命令行那种命令空着不能提交`() {
        assertEquals("命令不能为空", mcpFormError(stdio(command = "  "), emptyList()))
    }

    @Test
    fun `远端那种地址不是 http 或 https 不能提交`() {
        assertNull(mcpFormError(remote(url = "https://x/mcp"), emptyList()))
        assertNull(mcpFormError(remote(url = "http://x/mcp"), emptyList()))
        assertEquals("地址要以 http:// 或 https:// 开头", mcpFormError(remote(url = "x/mcp"), emptyList()))
        assertEquals("地址要以 http:// 或 https:// 开头", mcpFormError(remote(url = ""), emptyList()))
    }

    @Test
    fun `校验按形状走 —— 远端那条不该被命令空着拦住`() {
        assertNull(mcpFormError(remote(url = "https://x/mcp"), emptyList()))
    }

    @Test
    fun `名字那条先报 —— 名都没定，别的都白搭`() {
        val err = mcpFormError(stdio(name = "", command = ""), emptyList())

        assertTrue(err!!.contains("名字"), "该先报名字，实际报的是：$err")
    }
}
