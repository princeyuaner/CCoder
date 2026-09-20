package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `子代理的正文与工具带 parent（A1 嵌套靠它）`() {
        // 形状照探针实测抄：子代理的帧多三个字段，其中 parent_tool_use_id 就是
        // 主线程那条 Task 的 tool_use.id（见 probe-subagent-text.mjs / 设计稿事实 1）
        val items = MessageRenderer.render(
            event(
                """
                {"type":"assistant","parent_tool_use_id":"toolu_task_1",
                 "subagent_type":"Explore","task_description":"找调用点",
                 "message":{"content":[
                   {"type":"text","text":"找到了三处"},
                   {"type":"tool_use","id":"toolu_sub_1","name":"Grep","input":{"pattern":"x"}}]}}
                """
            )
        )

        val text = items[0] as RenderItem.AssistantText
        assertEquals("找到了三处", text.text)
        assertEquals("toolu_task_1", text.parent)

        val tool = items[1] as RenderItem.ToolUse
        assertEquals("toolu_task_1", tool.parent)
        assertEquals("toolu_sub_1", tool.id)
    }

    @Test
    fun `主线程的帧 parent 是 null（字段在、值为 null）`() {
        // 实测：主线程消息上这个键**在**、值是 null —— 所以这里刻意把键写出来
        val items = MessageRenderer.render(
            event(
                """
                {"type":"assistant","parent_tool_use_id":null,
                 "message":{"content":[{"type":"thinking","thinking":"想想"},{"type":"text","text":"好了"}]}}
                """
            )
        )
        assertEquals(null, (items[0] as RenderItem.Thinking).parent)
        assertEquals(null, (items[1] as RenderItem.AssistantText).parent)
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
    fun `result 带上 usage 里的 token —— 界面那一行要显示的是本回合的消耗`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"result","subtype":"success","duration_ms":33818,
                 "usage":{"input_tokens":12432,"output_tokens":1234,
                          "cache_read_input_tokens":8100,"cache_creation_input_tokens":321}}
                """,
            )
        )

        val r = items[0] as RenderItem.Result
        assertEquals(12432L, r.inputTokens)
        assertEquals(1234L, r.outputTokens)
        assertEquals(8100L, r.cacheReadTokens)
        // 缓存"写"的那一份不显示（用户 2026-09-15 定的那一行里没有它），所以不往线上带
        assertEquals(33818L, r.durationMs)
    }

    @Test
    fun `没有 usage 的 result 三项都是 null，不抛`() {
        val items = MessageRenderer.render(event("""{"type":"result","subtype":"success"}"""))

        val r = items[0] as RenderItem.Result
        assertEquals(null, r.inputTokens)
        assertEquals(null, r.outputTokens)
        assertEquals(null, r.cacheReadTokens)
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
    fun `compact_boundary 渲染成压缩回执 —— 落盘的 camelCase 形状也认`() {
        // 回放同样走 renderer（ClaudePanel.replayItems），而落盘那份的字段名是
        // camelCase（spec 事实 12）—— 两种形状都得在这儿出现在系统提示里。
        //
        // ⚠️ 2026-09-20 实测（SDK 0.3.274 的 getSessionMessages）：回放项里**只有
        // user/assistant**（这条会话 107 条里 0 条 system），所以今天实时之外没有
        // 第二条路走到这里。camelCase 那份是防御：SDK 哪天开始透传 system 条目，
        // 回执就自动补上 —— 但别再假设"恢复历史能看到压缩过"
        val live = MessageRenderer.render(
            event(
                """{"type":"system","subtype":"compact_boundary",
                    "compact_metadata":{"trigger":"manual","pre_tokens":30409,"post_tokens":1666,"duration_ms":17219}}"""
            )
        )
        assertEquals("已压缩上下文：30.4k → 1.7k（用时 17s）", (live[0] as RenderItem.SystemNote).text)

        val replayed = MessageRenderer.render(
            event(
                """{"type":"system","subtype":"compact_boundary",
                    "compactMetadata":{"trigger":"manual","preTokens":30402,"postTokens":1182,"durationMs":9704}}"""
            )
        )
        assertEquals("已压缩上下文：30.4k → 1.2k（用时 10s）", (replayed[0] as RenderItem.SystemNote).text)
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
    fun `工具调用的起头帧产出一个只喂状态卡的项`() {
        // 参数（对 Write 来说就是整个文件内容）还在生成时转写区没有东西可画，
        // 屏幕上完全静止 —— 这一项让状态卡提前说「编辑文件」，不必等参数生成完。
        // 它**不进转写区**：ToolStarting 在 toOp 那里给 null
        val items = MessageRenderer.render(
            event(
                """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,
                 "content_block":{"type":"tool_use","id":"toolu_1","name":"Write","input":{}}}}
                """
            )
        )

        assertEquals(listOf(RenderItem.ToolStarting("Write")), items)
    }

    @Test
    fun `文本块的起头帧仍然什么都不产出`() {
        val items = MessageRenderer.render(
            event(
                """
                {"type":"stream_event","event":{"type":"content_block_start","index":0,
                 "content_block":{"type":"text","text":""}}}
                """
            )
        )

        assertEquals(0, items.size, "文本块的起头帧没有可显示内容")
    }

    @Test
    fun `stream_event 的起止帧不产生渲染项`() {
        // message_start / content_block_stop / message_stop 没有可显示内容，
        // 多产生渲染项会让 UI 出现空行。
        // **content_block_start 不在这张名单里**：带 tool_use 的那种会产出一个
        // 只喂状态卡的项（见上一条用例）
        for (kind in listOf("message_start", "content_block_stop", "message_delta", "message_stop")) {
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
        val text = prompt("""{"type":"user","message":{"role":"user","content":"这是什么项目"}}""")?.text
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

    // ---- CLI 的本地命令信封 / 压缩摘要（2026-09-20）----

    /** 会话文件里那种"纯字符串内容的 user 条目"（信封就是这形状）。 */
    private fun userText(text: String) = JsonObject().apply {
        addProperty("type", "user")
        add("message", JsonObject().apply { addProperty("content", text) })
    }

    @Test
    fun `本地命令信封不画成用户气泡`() {
        // 前三条是会话文件里的实物（/compact 那条会话，2026-09-20）：它们被画成
        // 用户气泡的现场就是用户来问"重启后为什么 compact 显示成这样"的那张图
        val envelopes = listOf(
            "<command-name>/compact</command-name>\n            " +
                "<command-message>compact</command-message>\n            <command-args></command-args>",
            "<local-command-stdout>Compacted </local-command-stdout>",
            "<local-command-caveat>Caveat: The messages below were generated by the user " +
                "while running local commands.",
            "<bash-stdout>ok</bash-stdout>",
            "<task-notification>后台命令结束了</task-notification>",
        )
        for (text in envelopes) {
            assertNull(MessageRenderer.renderPrompt(userText(text)), "信封不是提问：$text")
        }
    }

    @Test
    fun `信封只在开头才算 —— 引用这些标签的提问照画`() {
        // 用户真在讨论这套协议时，消息里会出现同样的标签 —— 那是他的话，不是信封
        val text = "为什么恢复会话后会多出 <command-name>/compact</command-name> 这种气泡？"
        assertEquals(text, MessageRenderer.renderPrompt(userText(text))?.text)
    }

    @Test
    fun `压缩摘要不画成用户气泡`() {
        // 实物（会话 84d335d7，15153 字）。**这里是句子判据在拦**：SDK 的
        // getSessionMessages 会把 isCompactSummary / isVisibleInTranscriptOnly
        // 连同 isMeta 一起归一化掉（实测 196 条回来的字段完全一致），所以下面
        // 那两个标记只是兜底
        val summary = userText(
            "This session is being continued from a previous conversation that ran out of " +
                "context. The summary below covers the earlier portion of the conversation.\n\n" +
                "Summary:\n1. Primary Request and Intent: …"
        )
        assertNull(MessageRenderer.renderPrompt(summary))

        // 兜底：两个标记各自都够（CLI 的 Dhn 认前者，另一处认后者）
        assertNull(MessageRenderer.renderPrompt(userText("随便一段").apply { addProperty("isCompactSummary", true) }))
        assertNull(
            MessageRenderer.renderPrompt(userText("随便一段").apply { addProperty("isVisibleInTranscriptOnly", true) })
        )
    }

    @Test
    fun `摘要判据也只认开头 —— 提到"上次那段对话"的提问照画`() {
        val text = "接着 previous conversation 那条线继续，把剩下的用例补上"
        assertEquals(text, MessageRenderer.renderPrompt(userText(text))?.text)
    }

    @Test
    fun `块形式的信封同样不画`() {
        // 实测信封都是纯字符串，但形状不该决定行为
        assertNull(
            prompt(
                """{"type":"user","message":{"content":[
                    {"type":"text","text":"<local-command-stdout>Compacted </local-command-stdout>"}]}}"""
            )
        )
    }

    @Test
    fun `信封判据只认那批标签`() {
        assertTrue(MessageRenderer.isEnvelopeText("<command-name>/clear</command-name>"))
        assertTrue(MessageRenderer.isEnvelopeText("<bash-exit-code>1</bash-exit-code>"))
        assertFalse(MessageRenderer.isEnvelopeText("<command-args>只在后面出现</command-args>"))
        assertFalse(MessageRenderer.isEnvelopeText("这是一句普通的话"))
    }

    @Test
    fun `数组形式的文本块被拼接`() {
        val text = prompt(
            """{"type":"user","message":{"role":"user","content":[
                {"type":"text","text":"第一段"},
                {"type":"text","text":"第二段"}]}}"""
        )?.text
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

    // ---- 历史里的图（2026-09-15）----
    //
    // 实测（sidecar/tools/probe-history-image.mjs）：CLI 把用户发过的图**原样**
    // 存进 JSONL —— 完整 base64，不是 `[Image #1]` 占位。所以在恢复会话时
    // 那几张图是拿得到的，之前不显示是这里只挑 text 块。

    /** 一张真的小图，base64 之后塞进历史条目里。 */
    private fun pngBase64(): String {
        val img = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun `带图的历史提问：文字与图一起取出来`() {
        val json = """{"type":"user","message":{"role":"user","content":[
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${pngBase64()}"}},
            {"type":"text","text":"这张图哪里不对"}]}}"""

        val got = prompt(json)

        assertEquals("这张图哪里不对", got?.text)
        assertEquals(1, got?.images?.size)
        assertTrue(
            got!!.images[0].startsWith("data:image/jpeg;base64,"),
            "给转写区的那份要是能直接塞进 img.src 的 data URL：${got.images[0].take(30)}",
        )
    }

    @Test
    fun `纯图提问也回得来 —— 一个字都没打的那种`() {
        val json = """{"type":"user","message":{"role":"user","content":[
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${pngBase64()}"}}]}}"""

        val got = prompt(json)

        assertEquals("", got?.text)
        assertEquals(1, got?.images?.size)
    }

    @Test
    fun `图坏掉时不整条丢 —— 文字还得留给人看`() {
        // 一条坏图不该让这次恢复少掉一整句话
        val json = """{"type":"user","message":{"role":"user","content":[
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"不是base64"}},
            {"type":"text","text":"这句话必须在"}]}}"""

        val got = prompt(json)

        assertEquals("这句话必须在", got?.text)
        assertTrue(got!!.images.isEmpty(), "解不开的图跳过，不该跟着文字一起没")
    }

    @Test
    fun `认不出的 image 形状也跳过`() {
        // 将来 SDK 换成 URL 形式的图（source.type = "url"）时走这条路
        val json = """{"type":"user","message":{"role":"user","content":[
            {"type":"image","source":{"type":"url","url":"https://example.com/a.png"}},
            {"type":"text","text":"看这个链接"}]}}"""

        assertEquals("看这个链接", prompt(json)?.text)
        assertTrue(prompt(json)!!.images.isEmpty())
    }

    @Test
    fun `工具结果里的图不算提问里的图`() {
        // 那条路上的图属于工具卡片，本来就整条丢（见 renderToolResults）
        val json = """{"type":"user","message":{"role":"user","content":[
            {"type":"tool_result","tool_use_id":"t1","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"${pngBase64()}"}}]}]}}"""

        assertNull(prompt(json))
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

    // ---- 命令回合的空输出 ----

    @Test
    fun `命令回合里的空输出不画气泡`() {
        assertTrue(isEmptyCommandOutput(RenderItem.AssistantText("")))
        assertTrue(isEmptyCommandOutput(RenderItem.AssistantText("   ")))
        assertTrue(isEmptyCommandOutput(RenderItem.AssistantText("(no content)")))
        assertTrue(isEmptyCommandOutput(RenderItem.AssistantText("  (no content)  ")))
    }

    @Test
    fun `命令回合里的真输出照常画 —— cost 与 context 的报告就靠这条`() {
        assertFalse(isEmptyCommandOutput(RenderItem.AssistantText("Total cost: $0.16")))
    }

    @Test
    fun `只挡 assistant 文本，别的渲染项一律不碰`() {
        assertFalse(isEmptyCommandOutput(RenderItem.Result("success", null, null)))
        assertFalse(isEmptyCommandOutput(RenderItem.SystemNote("")))
        assertFalse(isEmptyCommandOutput(RenderItem.UserText("")))
    }
}
