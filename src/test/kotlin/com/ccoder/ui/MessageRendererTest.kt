package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageRendererTest {

    private fun event(json: String) =
        SidecarMessage.Event(JsonParser.parseString(json.trimIndent()).asJsonObject)

    @Test
    fun `assistant 文本块渲染为 AssistantText`() {
        val items = MessageRenderer.render(
            event("""{"type":"assistant","message":{"content":[{"type":"text","text":"你好"}]}}""")
        )
        assertEquals(1, items.size)
        assertEquals("你好", (items[0] as RenderItem.AssistantText).text)
    }

    @Test
    fun `thinking 块与正文分开渲染`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"assistant","message":{"content":[
                  {"type":"thinking","thinking":"让我想想"},
                  {"type":"text","text":"答案是 42"}]}}
                """
            )
        )
        assertEquals(2, items.size)
        assertTrue(items[0] is RenderItem.Thinking)
        assertTrue(items[1] is RenderItem.AssistantText)
    }

    @Test
    fun `tool_use 块渲染为 ToolUse 并带工具名`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","name":"Read","input":{"file_path":"/a.txt"}}]}}
                """
            )
        )
        val toolUse = items[0] as RenderItem.ToolUse
        assertEquals("Read", toolUse.name)
        assertTrue(toolUse.input.contains("/a.txt"))
    }

    @Test
    fun `hook 事件不进入消息流`() {
        // 实测每次会话必现且无信息量（spec §11.2）
        assertEquals(0, MessageRenderer.render(event("""{"type":"system","subtype":"hook_started"}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"system","subtype":"hook_response"}""")).size)
    }

    @Test
    fun `未知事件类型返回空列表而不抛错`() {
        assertEquals(0, MessageRenderer.render(event("""{"type":"some_future_type_v99"}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"totally_unknown"}""")).size)
    }

    @Test
    fun `result 事件渲染为 Result 并带成本与耗时`() {
        val items = MessageRenderer.render(
            event("""{"type":"result","subtype":"success","total_cost_usd":0.0644,"duration_ms":2185}""")
        )
        val r = items[0] as RenderItem.Result
        assertEquals("success", r.subtype)
        assertEquals(0.0644, r.costUsd!!, 0.0001)
        assertEquals(2185L, r.durationMs)
    }

    @Test
    fun `assistant 错误事件渲染为 ErrorItem`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"assistant","error":"authentication_failed",
                 "message":{"content":[{"type":"text","text":"Not logged in"}]}}
                """
            )
        )
        assertTrue(
            items.any { it is RenderItem.ErrorItem },
            "error 字段必须产生 ErrorItem，否则认证失败会被当成正常回复显示"
        )
    }

    @Test
    fun `system init 渲染为 SystemNote 并带会话 ID`() {
        val items = MessageRenderer.render(
            event("""{"type":"system","subtype":"init","session_id":"abc-123","model":"m"}""")
        )
        val note = items[0] as RenderItem.SystemNote
        assertTrue(note.text.contains("abc-123"))
    }

    @Test
    fun `permission 消息不产生渲染项`() {
        // 权限由 PermissionCard 处理，不走消息流
        val items = MessageRenderer.render(
            SidecarMessage.Permission(
                "r1", "Read", JsonParser.parseString("{}").asJsonObject,
                null, null, null, null, null
            )
        )
        assertEquals(0, items.size)
    }

    @Test
    fun `content 缺失或类型不符时不抛错`() {
        assertEquals(0, MessageRenderer.render(event("""{"type":"assistant"}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"assistant","message":{}}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"assistant","message":{"content":"不是数组"}}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"assistant","message":{"content":[1,2]}}""")).size)
    }

    // ---- 逐 token 增量（includePartialMessages: true 的产物）----

    @Test
    fun `stream_event 的 text_delta 渲染为 AssistantDelta`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"stream_event","event":
                  {"type":"content_block_delta","index":0,
                   "delta":{"type":"text_delta","text":"你"}}}
                """
            )
        )
        assertEquals(1, items.size)
        assertEquals("你", (items[0] as RenderItem.AssistantDelta).text)
    }

    @Test
    fun `stream_event 的 thinking_delta 渲染为 ThinkingDelta`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"stream_event","event":
                  {"type":"content_block_delta","index":0,
                   "delta":{"type":"thinking_delta","thinking":"嗯……"}}}
                """
            )
        )
        assertEquals(1, items.size)
        assertEquals("嗯……", (items[0] as RenderItem.ThinkingDelta).text)
    }

    @Test
    fun `stream_event 的起止帧不产生渲染项`() {
        // message_start / content_block_start / content_block_stop / message_stop
        // 都没有可显示内容，多产生渲染项会让 UI 出现空行
        for (kind in listOf("message_start", "content_block_start", "content_block_stop", "message_delta", "message_stop")) {
            val items = MessageRenderer.render(
                event("""{"type":"stream_event","event":{"type":"$kind"}}""")
            )
            assertEquals(0, items.size, "$kind 不应产生渲染项")
        }
    }

    @Test
    fun `stream_event 的空文本增量被忽略`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"stream_event","event":{"type":"content_block_delta",
                 "delta":{"type":"text_delta","text":""}}}
                """
            )
        )
        assertEquals(0, items.size)
    }

    @Test
    fun `stream_event 载荷缺失或畸形时不抛错`() {
        assertEquals(0, MessageRenderer.render(event("""{"type":"stream_event"}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"stream_event","event":"不是对象"}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"stream_event","event":{"type":"content_block_delta"}}""")).size)
        // 未来可能出现的新 delta 类型（如 input_json_delta）必须被忽略
        assertEquals(
            0,
            MessageRenderer.render(
                event(
                    """
                    {"type":"stream_event","event":{"type":"content_block_delta",
                     "delta":{"type":"input_json_delta","partial_json":"{"}}}
                    """
                )
            ).size
        )
    }

    // ---- 工具调用与它的结果（设计稿 transcript-tools.html 方案乙）----
    //
    // 界面上要把结果挂回对应的那次调用，所以两侧都得有 id：
    // 调用带 tool_use.id，结果带 tool_use_id，两边靠它配对。

    @Test
    fun `tool_use 带出它的 id`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"assistant","message":{"content":[
                  {"type":"tool_use","id":"toolu_1","name":"Edit","input":{"file_path":"/a.kt"}}]}}
                """
            )
        )
        assertEquals("toolu_1", (items[0] as RenderItem.ToolUse).id)
    }

    @Test
    fun `工具结果渲染为 ToolResult 并带上调用 id`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"user","message":{"content":[
                  {"type":"tool_result","tool_use_id":"toolu_1","content":"LICENSE.md\nREADME.md"}]}}
                """
            )
        )
        assertEquals(1, items.size)
        val result = items[0] as RenderItem.ToolResult
        assertEquals("toolu_1", result.toolUseId)
        assertEquals("LICENSE.md\nREADME.md", result.text)
        assertEquals(false, result.isError)
    }

    @Test
    fun `数组形式的工具结果把文本块拼起来`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"user","message":{"content":[
                  {"type":"tool_result","tool_use_id":"t1","content":[
                     {"type":"text","text":"第一行"},
                     {"type":"text","text":"第二行"}]}]}}
                """
            )
        )
        assertEquals("第一行\n第二行", (items[0] as RenderItem.ToolResult).text)
    }

    @Test
    fun `失败的工具结果标上 is_error`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"user","message":{"content":[
                  {"type":"tool_result","tool_use_id":"t1","is_error":true,"content":"boom"}]}}
                """
            )
        )
        assertEquals(true, (items[0] as RenderItem.ToolResult).isError)
    }

    @Test
    fun `只有图片的结果给一句占位`() {
        // 不能给空字符串：卡片会变成一块什么都没有的空白，
        // 用户不知道是"没输出"还是"界面坏了"
        val items = MessageRenderer.render(
            event(
                """
                {"type":"user","message":{"content":[
                  {"type":"tool_result","tool_use_id":"t1","content":[
                     {"type":"image","source":{"data":"..."}}]}]}}
                """
            )
        )
        assertEquals("（非文本结果）", (items[0] as RenderItem.ToolResult).text)
    }

    @Test
    fun `没有 tool_use_id 的结果丢弃`() {
        // 挂不回任何一次调用，画出来只能是一条无主的输出
        val items = MessageRenderer.render(
            event("""{"type":"user","message":{"content":[{"type":"tool_result","content":"x"}]}}""")
        )
        assertEquals(0, items.size)
    }

    @Test
    fun `普通提问的 user 事件不进消息流`() {
        // live 路径下用户气泡由 sendCurrentInput 直接推。这里再产一次就是两条
        val items = MessageRenderer.render(
            event("""{"type":"user","message":{"content":"这是什么项目"}}""")
        )
        assertEquals(0, items.size)
    }

    // ---- 回放路径的 user 消息（Task 5）----

    private fun prompt(json: String) = MessageRenderer.renderPrompt(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `纯文本提问被取出`() {
        val text = prompt("""{"type":"user","message":{"role":"user","content":"这是什么项目"}}""")
        assertEquals("这是什么项目", text)
    }

    @Test
    fun `工具结果被丢弃`() {
        // 实测：247 条 user 消息里 236 条是这个形状。不过滤会把转写区淹掉
        val text = prompt(
            """{"type":"user","message":{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"t1","content":"一堆文件内容"}]}}"""
        )
        assertNull(text, "工具结果不是提问")
    }

    @Test
    fun `数组形式的文本块被拼接`() {
        val text = prompt(
            """{"type":"user","message":{"role":"user","content":[
                {"type":"text","text":"第一段"},
                {"type":"text","text":"第二段"}]}}"""
        )
        assertEquals("第一段\n第二段", text)
    }

    @Test
    fun `同时含文本块与工具结果时整条丢弃`() {
        // 真实提问不会和 tool_result 混在一条里。混着出现说明这是工具回合，
        // 那点文本是工具上下文而非用户输入
        val text = prompt(
            """{"type":"user","message":{"role":"user","content":[
                {"type":"text","text":"顺带一提"},
                {"type":"tool_result","tool_use_id":"t1","content":"x"}]}}"""
        )
        assertNull(text)
    }

    @Test
    fun `空白提问返回 null`() {
        assertNull(prompt("""{"type":"user","message":{"role":"user","content":"   "}}"""))
        assertNull(prompt("""{"type":"user","message":{"role":"user","content":[]}}"""))
    }

    @Test
    fun `非 user 类型返回 null`() {
        assertNull(prompt("""{"type":"assistant","message":{"role":"assistant","content":"x"}}"""))
        assertNull(prompt("""{"type":"system","subtype":"init"}"""))
    }

    @Test
    fun `畸形输入不抛错`() {
        // 回放会把整份历史喂进来，任何一条畸形都不能让整个过程崩掉
        assertNull(prompt("""{"type":"user"}"""))
        assertNull(prompt("""{"type":"user","message":"不是对象"}"""))
        assertNull(prompt("""{"type":"user","message":{"role":"user","content":42}}"""))
    }
}
