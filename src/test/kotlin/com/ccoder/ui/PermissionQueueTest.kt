package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionQueueTest {

    private fun perm(id: String) = SidecarMessage.Permission(
        requestId = id, toolName = "Read",
        input = JsonParser.parseString("{}").asJsonObject,
        title = "读取文件", displayName = "读取", description = null,
        blockedPath = null, decisionReason = null,
        defaultToNo = false, suppressAlwaysAllowRule = false, suggestions = null,
    )

    @Test
    fun `并发询问串行化，一次只激活一个`() {
        // SDK 支持并行工具调用，可能同时挂起多个 canUseTool（spec §6.4）
        val activated = mutableListOf<String>()
        val q = PermissionQueue { p, _ -> activated += p.requestId }

        q.enqueue(perm("a"))
        q.enqueue(perm("b"))
        q.enqueue(perm("c"))

        assertEquals(1, activated.size, "一次只能激活一个，否则会变成弹窗风暴")
        assertEquals(2, q.pendingCount, "排队 2 个")
        assertEquals(3, q.totalPending, "待决总数含当前激活项")
        assertEquals("a", q.activeRequestId)
    }

    @Test
    fun `解决当前项后激活下一个`() {
        val activated = mutableListOf<String>()
        val q = PermissionQueue { p, _ -> activated += p.requestId }

        q.enqueue(perm("a"))
        q.enqueue(perm("b"))
        q.resolve("a", PermissionDecision(allow = true, updatedPermissions = null, message = null))

        assertEquals(listOf("a", "b"), activated)
        // 两个计数含义不同：pendingCount 是排队数，totalPending 含当前激活项
        assertEquals(0, q.pendingCount, "b 已被激活，队列里没有等待项")
        assertEquals(1, q.totalPending, "仍在等待决定的共 1 个")
        assertEquals("b", q.activeRequestId)
    }

    @Test
    fun `队列剩余数量作为回调参数传出`() {
        val flags = mutableListOf<Int>()
        val q = PermissionQueue { _, queued -> flags += queued }

        q.enqueue(perm("a"))
        q.enqueue(perm("b"))
        q.enqueue(perm("c"))
        q.resolve("a", PermissionDecision(false, null, "拒绝"))

        assertEquals(listOf(0, 1), flags, "激活 a 时后面有 0 个，激活 b 时后面有 1 个")
    }

    @Test
    fun `cancelAll 清空队列`() {
        val q = PermissionQueue { _, _ -> }
        q.enqueue(perm("a"))
        q.enqueue(perm("b"))

        q.cancelAll()

        assertEquals(0, q.pendingCount)
        assertNull(q.activeRequestId)
    }

    @Test
    fun `解决不存在的 requestId 不产生副作用`() {
        val activated = mutableListOf<String>()
        val q = PermissionQueue { p, _ -> activated += p.requestId }
        q.enqueue(perm("a"))

        q.resolve("nope", PermissionDecision(false, null, null))

        assertEquals(listOf("a"), activated, "不应误激活或关闭其他项")
        assertEquals(0, q.pendingCount, "队列里没有等待项")
        assertEquals(1, q.totalPending, "a 仍在等待决定，总数不受影响")
        assertEquals("a", q.activeRequestId)
    }

    @Test
    fun `解决已完成的 requestId 不重复激活`() {
        val activated = mutableListOf<String>()
        val q = PermissionQueue { p, _ -> activated += p.requestId }
        q.enqueue(perm("a"))

        q.resolve("a", PermissionDecision(true, null, null))
        q.resolve("a", PermissionDecision(true, null, null))

        assertEquals(listOf("a"), activated)
    }

    @Test
    fun `空队列时 cancelAll 不抛错`() {
        val q = PermissionQueue { _, _ -> }
        q.cancelAll()
        assertEquals(0, q.pendingCount)
    }
}

class PermissionOptionsTest {

    private fun perm(
        suppress: Boolean = false,
        suggestions: JsonArray? = null,
        title: String? = null,
        displayName: String? = null,
        toolName: String = "Bash",
    ) = SidecarMessage.Permission(
        requestId = "r", toolName = toolName,
        input = JsonParser.parseString("{}").asJsonObject,
        title = title, displayName = displayName, description = null,
        blockedPath = null, decisionReason = null,
        defaultToNo = false, suppressAlwaysAllowRule = suppress, suggestions = suggestions,
    )

    private val someSuggestions =
        JsonParser.parseString("""[{"type":"addRules"}]""").asJsonArray

    @Test
    fun `suppressAlwaysAllowRule 为 true 时不提供总是允许`() {
        // sdk.d.ts:249-253 原文要求：该规则会授予比本次询问更大的权限
        assertFalse(PermissionOptions.allowsAlwaysAllow(perm(suppress = true, suggestions = someSuggestions)))
    }

    @Test
    fun `suggestions 为空时不提供总是允许`() {
        assertFalse(PermissionOptions.allowsAlwaysAllow(perm(suppress = false, suggestions = null)))
        assertFalse(PermissionOptions.allowsAlwaysAllow(perm(suppress = false, suggestions = JsonArray())))
    }

    @Test
    fun `两者都满足时才提供总是允许`() {
        assertTrue(PermissionOptions.allowsAlwaysAllow(perm(suppress = false, suggestions = someSuggestions)))
    }

    // ---- 本会话自动放行 ----

    @Test
    fun `自动放行不吞掉提问`() {
        // AskUserQuestion 不是授权请求，是在问你要答案。自动"允许"等于把那个
        // 问题吞掉 —— 用户永远看不到它，而 Claude 拿着一个没人回答的提问往下走
        assertFalse(PermissionOptions.autoAllowApplies(perm(toolName = ASK_TOOL_NAME)))
    }

    @Test
    fun `自动放行适用于普通工具`() {
        assertTrue(PermissionOptions.autoAllowApplies(perm(toolName = "Bash")))
        assertTrue(PermissionOptions.autoAllowApplies(perm(toolName = "Write")))
    }

    @Test
    fun `优先使用 SDK 渲染好的 title`() {
        // sdk.d.ts:228-233：title 是完整问句，不该用 toolName+input 重拼
        val p = perm(title = "Claude wants to read foo.txt", displayName = "Read file")
        assertEquals("Claude wants to read foo.txt", PermissionOptions.primaryText(p))
    }

    @Test
    fun `title 为空或空白时回退到 displayName`() {
        assertEquals("Read file", PermissionOptions.primaryText(perm(title = "", displayName = "Read file")))
        assertEquals("Read file", PermissionOptions.primaryText(perm(title = "   ", displayName = "Read file")))
    }

    @Test
    fun `两者都缺失时回退到工具名`() {
        assertEquals("Bash", PermissionOptions.primaryText(perm()))
        assertEquals("Bash", PermissionOptions.primaryText(perm(title = "", displayName = "  ")))
    }

    @Test
    fun `安全性提示字段被原样保留供卡片使用`() {
        // sdk.d.ts:245-253 的两个安全提示位。卡片据此调整呈现，
        // 这里只钉住协议层不丢字段。
        val p = perm().copy(defaultToNo = true, suppressAlwaysAllowRule = true)
        assertTrue(p.defaultToNo)
        assertTrue(p.suppressAlwaysAllowRule)
    }

    // ---- 标题：把工具名念一遍不算问句（2026-09-15 用户问"这个是什么审批"）----

    @Test
    fun `标题只是工具名时换成人话`() {
        // CLI 对内置工具给的 title/displayName 就是工具名本身，标题于是显示
        // 「ExitPlanMode」—— 用户看不出这是在批准什么
        val p = perm(toolName = "ExitPlanMode", title = "ExitPlanMode", displayName = "ExitPlanMode")
        assertEquals("退出计划模式", PermissionOptions.primaryText(p))
    }

    @Test
    fun `认不出的工具仍退回工具名`() {
        // 宁可给一个生词，也不要编一句可能不对的话
        val p = perm(toolName = "SomeMcpTool", title = "SomeMcpTool")
        assertEquals("SomeMcpTool", PermissionOptions.primaryText(p))
    }

    @Test
    fun `真问句不会被当成工具名`() {
        val p = perm(toolName = "ExitPlanMode", title = "Claude 想退出计划模式")
        assertEquals("Claude 想退出计划模式", PermissionOptions.primaryText(p))
    }

    // ---- 入参正文：看不到内容的审批不是审批 ----

    private fun bodyOf(json: String) =
        permissionBody(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `计划按文本铺开，不是一行转义 JSON`() {
        val plan = "# 计划\n\n第一段\n第二段"
        val body = bodyOf("""{"plan":"$plan","planFilePath":"C:\\plans\\x.md"}""")

        assertEquals("计划内容", body.caption, "这一段的标题得说清它是什么")
        assertTrue(body.text.startsWith("# 计划"), "正文该是计划原文：${body.text}")
        // 真换行，不是字面 \n
        assertTrue(body.text.contains("第一段\n第二段"), "换行没还原：${body.text}")
        // 其余字段一个都不能藏
        assertTrue(body.text.contains("planFilePath"), "其余参数被吞了：${body.text}")
    }

    @Test
    fun `短入参走缩进 JSON`() {
        // Bash 那种 command + description：没有"正文型"字段，照旧
        val body = bodyOf("""{"command":"ls -la","description":"看看目录"}""")

        assertEquals("原始输入", body.caption)
        assertTrue(body.text.contains("\n"), "缩进 JSON 该是多行的：${body.text}")
        assertTrue(body.text.contains("\"command\": \"ls -la\""), body.text)
    }

    @Test
    fun `多行脚本也算正文`() {
        // 长度不到 200 但带换行 —— 一行转义同样读不了
        val body = bodyOf("""{"command":"set -e\ncd /tmp\necho hi","description":"跑一段"}""")

        assertTrue(body.text.startsWith("set -e\ncd /tmp"), "多行命令该按文本铺开：${body.text}")
    }

    @Test
    fun `长正文给更高的行数`() {
        val long = bodyOf("""{"plan":"${"字".repeat(300)}"}""")
        val short = bodyOf("""{"command":"ls"}""")

        assertTrue(long.rows > short.rows, "计划框该给得更高：${long.rows} vs ${short.rows}")
        assertTrue(long.maxHeight > short.maxHeight)
    }

    @Test
    fun `没有其余参数时不拼那一段`() {
        val body = bodyOf("""{"plan":"${"字".repeat(300)}"}""")

        assertFalse(body.text.contains("其余参数"), body.text)
    }
}
