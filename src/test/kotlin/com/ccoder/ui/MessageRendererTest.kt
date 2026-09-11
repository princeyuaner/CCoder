package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
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
}
