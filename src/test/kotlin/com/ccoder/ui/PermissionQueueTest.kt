package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.ccoder.text.CcoderText
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

    /**
     * 工具名默认给个**不参与改动预览**的（这条路上只有 `Edit` / `Write` / `MultiEdit`
     * 有特殊待遇）。下面那些长字段用例查的是"最长字符串字段"那条路，与工具无关。
     */
    private fun bodyOf(json: String, tool: String = "Bash") =
        permissionBody(tool, JsonParser.parseString(json).asJsonObject)

    @Test
    fun `计划按文本铺开，不是一行转义 JSON`() {
        val plan = "# 计划\n\n第一段\n第二段"
        val body = bodyOf("""{"plan":"$plan","planFilePath":"C:\\plans\\x.md"}""")

        assertEquals("计划内容", body.caption, "这一段的标题得说清它是什么")
        assertTrue(body.text.startsWith("# 计划"), "正文该是计划原文：${body.text}")
        // 真换行，不是字面 \n
        assertTrue(body.text.contains("第一段\n第二段"), "换行没还原：${body.text}")
        // 其余字段一个都不能藏（2026-09-16 起它们单独成一段 footer：
        // 计划改走 Markdown 渲染后，混在正文里会以正文字体印出来）
        assertTrue(body.footer?.contains("planFilePath") == true, "其余参数被吞了：${body.footer}")
    }

    @Test
    fun `计划那条走 Markdown 渲染，别的长正文不走`() {
        // 只有计划是 Markdown。Bash 的 command 那种按 Markdown 渲染只会平白
        // 把 `#`、`*` 当记号 —— 比不渲染更糟
        val plan = bodyOf("""{"plan":"${"字".repeat(300)}"}""")
        val script = bodyOf("""{"command":"set -e\n${"字".repeat(300)}"}""")

        assertTrue(plan.markdown, "计划该走 HTML 渲染")
        assertFalse(script.markdown, "多行脚本不是 Markdown")
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

        assertNull(body.footer, "没有别的字段就不该有那一段：${body.footer}")
    }

    // ---- 改动预览（2026-09-24：审批框里的那一次也该看得见改了什么）----

    /**
     * 这条正是加改动预览的**全部理由**：改一个词时 `old_string` 与 `new_string`
     * 都不到 200 字、也没有换行，按旧规则是"没有正文型字段"→ 整份缩进 JSON。
     * 而那是最常见的一次编辑 —— 用户得自己在转义字符里做差集。
     */
    @Test
    fun `改一个词也算正文 —— 改动预览排在长字段那条路前面`() {
        val body = bodyOf("""{"file_path":"a.txt","old_string":"foo","new_string":"bar"}""", "Edit")

        assertEquals(
            CcoderText.text("permission.diffCaption", 1, 1),
            body.caption,
            "标题该说清这是改动预览、以及改了多少",
        )
        assertEquals(
            listOf(DiffKind.Del to "foo", DiffKind.Add to "bar"),
            body.diff?.map { it.kind to it.text },
        )
    }

    /** 同一段文本在框里出现两次（一次配色、一次转义 JSON）才是最让人怀疑"批错了"的。 */
    @Test
    fun `被 diff 吃掉的字段不进「其余参数」`() {
        val edit = bodyOf(
            """{"file_path":"src/a.txt","old_string":"foo","new_string":"bar","replace_all":true}""",
            "Edit",
        )
        assertTrue(edit.footer?.contains("src/a.txt") == true, "路径得留着：${edit.footer}")
        assertTrue(edit.footer?.contains("replace_all") == true, "没被吃掉的参数也得留着：${edit.footer}")
        assertFalse(edit.footer?.contains("old_string") == true, "它已经在上面画过了：${edit.footer}")
        assertFalse(edit.footer?.contains("new_string") == true, "同上：${edit.footer}")

        val multi = bodyOf(
            """{"file_path":"c.txt","edits":[{"old_string":"one","new_string":"1"}]}""",
            "MultiEdit",
        )
        assertFalse(multi.footer?.contains("edits") == true, "整个 edits 都被吃掉了：${multi.footer}")
    }

    @Test
    fun `Write 整份都是新增，标题报得出多少行`() {
        val body = bodyOf("""{"file_path":"b.txt","content":"a\nb\nc"}""", "Write")

        assertEquals(CcoderText.text("permission.diffCaption", 3, 0), body.caption)
        assertEquals(3, body.diff?.size)
        assertTrue(body.diff?.all { it.kind == DiffKind.Add } == true, "Write 不该有删除行")
    }

    @Test
    fun `MultiEdit 每处编辑按顺序铺开`() {
        val body = bodyOf(
            """{"file_path":"c.txt","edits":[{"old_string":"one","new_string":"1"},{"old_string":"two","new_string":"2"}]}""",
            "MultiEdit",
        )

        assertEquals(
            listOf(
                DiffKind.Del to "one", DiffKind.Add to "1",
                DiffKind.Del to "two", DiffKind.Add to "2",
            ),
            body.diff?.map { it.kind to it.text },
        )
    }

    /**
     * 太大就退回纯文本 —— **不是不显示**。这条盯的是"一个字符都不许少"：
     * 那一屏是审批的依据，宁可没有配色。
     */
    @Test
    fun `改动太大时退回纯文本，但一个字都不少`() {
        val big = (1..250).joinToString("\n") { "line $it" }
        val body = bodyOf("""{"file_path":"big.txt","old_string":"$big","new_string":"done"}""", "Edit")

        assertNull(body.diff, "250 行超过了上限，该退回纯文本（不然那一屏是一张 251 行的表格）")
        assertTrue(body.text.contains("line 1\n"), "退回的那份必须还是全部内容：${body.text.take(60)}")
        assertTrue(body.text.contains("line 250"), "最后一行也得在：${body.text.takeLast(60)}")
    }

    @Test
    fun `认不出的工具照旧走 JSON`() {
        // Bash 的 command 不是"删了什么加了什么"，硬套 diff 只会画出假东西
        val body = bodyOf("""{"command":"set -e\ncd /tmp\necho hi","description":"跑一段"}""")

        assertNull(body.diff, "只有编辑类工具有改动预览")
        assertTrue(body.text.startsWith("set -e\ncd /tmp"), body.text)
    }
}
