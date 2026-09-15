package com.ccoder.sidecar

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolTest {

    @Test
    fun `解析 ready 消息`() {
        val msg = Protocol.parse("""{"type":"ready","sessionId":"s1","model":"m"}""")
        assertTrue(msg is SidecarMessage.Ready)
        assertEquals("s1", (msg as SidecarMessage.Ready).sessionId)
        assertEquals("m", msg.model)
    }

    @Test
    fun `解析 event 消息并保留原始载荷`() {
        val msg = Protocol.parse("""{"type":"event","event":{"type":"assistant","x":1}}""")
        assertTrue(msg is SidecarMessage.Event)
        val event = (msg as SidecarMessage.Event).event
        assertEquals("assistant", event.get("type").asString)
        assertEquals(1, event.get("x").asInt)
    }

    @Test
    fun `解析 permission 消息并填充安全默认值`() {
        val json = """{"type":"permission","requestId":"r1","toolName":"Read","input":{}}"""
        val msg = Protocol.parse(json) as SidecarMessage.Permission
        assertEquals("r1", msg.requestId)
        assertEquals("Read", msg.toolName)
        assertNull(msg.title)
        // 字段缺失时必须偏向拒绝而非放行
        assertTrue(msg.defaultToNo, "defaultToNo 缺失时应默认为 true")
        assertTrue(msg.suppressAlwaysAllowRule, "suppressAlwaysAllowRule 缺失时应默认为 true")
    }

    @Test
    fun `解析 permission 消息的完整字段`() {
        val json = """
        {"type":"permission","requestId":"r1","toolName":"Bash","input":{"command":"ls"},
         "title":"Claude 想执行命令","displayName":"执行命令","description":"副标题",
         "blockedPath":"/etc/passwd","decisionReason":"路径在允许范围外",
         "defaultToNo":false,"suppressAlwaysAllowRule":false,
         "suggestions":[{"type":"addRules"}]}
        """.trimIndent()
        val msg = Protocol.parse(json) as SidecarMessage.Permission
        assertEquals("Claude 想执行命令", msg.title)
        assertEquals("执行命令", msg.displayName)
        assertEquals("副标题", msg.description)
        assertEquals("/etc/passwd", msg.blockedPath)
        assertEquals("路径在允许范围外", msg.decisionReason)
        assertEquals(false, msg.defaultToNo)
        assertEquals(false, msg.suppressAlwaysAllowRule)
        assertEquals(1, msg.suggestions!!.size())
    }

    @Test
    fun `permission 缺失 requestId 时返回 null`() {
        // requestId 是关联决定的唯一凭据，缺了就无法回传，只能当畸形消息丢弃
        assertNull(Protocol.parse("""{"type":"permission","toolName":"Read"}"""))
    }

    @Test
    fun `解析 error 消息`() {
        val msg = Protocol.parse("""{"type":"error","code":"CLAUDE_NOT_FOUND","message":"找不到","fatal":true}""")
        val f = msg as SidecarMessage.Failure
        assertEquals("CLAUDE_NOT_FOUND", f.code)
        assertEquals("找不到", f.message)
        assertTrue(f.fatal)
    }

    @Test
    fun `解析 exit 消息`() {
        val msg = Protocol.parse("""{"type":"exit","code":1,"signal":"SIGTERM"}""")
        val e = msg as SidecarMessage.Exit
        assertEquals(1, e.code)
        assertEquals("SIGTERM", e.signal)
    }

    @Test
    fun `解析 permissionModeChanged 回执`() {
        val msg = Protocol.parse("""{"type":"permissionModeChanged","mode":"plan"}""")
        assertTrue(msg is SidecarMessage.PermissionModeChanged)
        assertEquals("plan", (msg as SidecarMessage.PermissionModeChanged).mode)
    }

    @Test
    fun `permissionModeChanged 缺 mode 时按畸形丢弃`() {
        // 没有 mode 就无从更新标签。留着只会让界面显示一个空模式 ——
        // 而这是个安全控件，显示错的比不显示严重
        assertNull(Protocol.parse("""{"type":"permissionModeChanged"}"""))
        assertNull(Protocol.parse("""{"type":"permissionModeChanged","mode":123}"""))
    }

    @Test
    fun `解析 effortChanged 回执`() {
        val msg = Protocol.parse("""{"type":"effortChanged","level":"xhigh"}""")
        assertTrue(msg is SidecarMessage.EffortChanged)
        assertEquals("xhigh", (msg as SidecarMessage.EffortChanged).level)
    }

    @Test
    fun `effortChanged 的 level 为 null 是合法的默认档，不是畸形`() {
        // 「默认」档就是这个形态：已经从 flag 层清除、回落模型自己的档位。
        // 照抄 permissionModeChanged 那条「缺字段即畸形」的话，这里会被丢掉，
        // 表现是选了「默认」之后标签一动不动 —— 看着像点坏了
        val msg = Protocol.parse("""{"type":"effortChanged","level":null}""")
        assertTrue(msg is SidecarMessage.EffortChanged, "null 档位被当成畸形丢掉了")
        assertNull((msg as SidecarMessage.EffortChanged).level)
    }

    @Test
    fun `effortChanged 缺 level 键时按畸形丢弃`() {
        // 与上一条是一对：**键在不在**才是判据。键都没有，说明对端协议
        // 跟我们对不上，界面无从知道该显示什么 —— 不能猜成「默认」，
        // 那会把一次协议错误变成一次静默的行为改变
        assertNull(Protocol.parse("""{"type":"effortChanged"}"""))
        assertNull(Protocol.parse("""{"type":"effortChanged","level":123}"""))
        assertNull(Protocol.parse("""{"type":"effortChanged","level":{"a":1}}"""))
    }

    @Test
    fun `encodeSetEffort 把档位原样发出去`() {
        val line = Protocol.encodeSetEffort("r1", "high")

        assertTrue(line.endsWith("\n"), "每条消息自带换行（NDJSON 分帧靠它）")
        val obj = com.google.gson.JsonParser.parseString(line.trim()).asJsonObject
        assertEquals("setEffort", obj.get("method").asString)
        assertEquals("r1", obj.get("id").asString)
        assertEquals("high", obj.getAsJsonObject("params").get("level").asString)
    }

    @Test
    fun `encodeSetEffort 的 null 是显式 JSON null，不是省略字段`() {
        // 省略只表示"没提这件事"，清不掉 flag 层里已经有的档位。
        // 写成 `level?.let { addProperty(...) }` 就会变成省略 ——
        // 于是「默认」这一档点了没反应，而回执照样说切成功了
        val line = Protocol.encodeSetEffort("r2", null)
        val params = com.google.gson.JsonParser.parseString(line.trim())
            .asJsonObject.getAsJsonObject("params")

        assertTrue(params.has("level"), "level 字段被省略了：$line")
        assertTrue(params.get("level").isJsonNull, "level 不是 JSON null：$line")
    }

    @Test
    fun `encodeSetModel 把模型名原样发出去`() {
        val line = Protocol.encodeSetModel("r1", "deepseek-v4-pro[1m]")

        assertTrue(line.endsWith("\n"), "每条消息自带换行（NDJSON 分帧靠它）")
        val obj = com.google.gson.JsonParser.parseString(line.trim()).asJsonObject
        assertEquals("setModel", obj.get("method").asString)
        assertEquals("r1", obj.get("id").asString)
        assertEquals("deepseek-v4-pro[1m]", obj.getAsJsonObject("params").get("model").asString)
    }

    @Test
    fun `解析 modelChanged 回执`() {
        val msg = Protocol.parse("""{"type":"modelChanged","model":"glm-4.6"}""")

        assertTrue(msg is SidecarMessage.ModelChanged, "实际：$msg")
        assertEquals("glm-4.6", (msg as SidecarMessage.ModelChanged).model)
    }

    /**
     * 与 `effortChanged` **刻意不同**：那边 `null` 是合法的「默认」档，要靠
     * `has("level")` 分辨"键不在"与"键在且为 null"；模型没有"清除"这个状态，
     * 永远是个具体名字，所以缺字段就是畸形。
     */
    @Test
    fun `modelChanged 缺 model 或类型不对时按畸形丢弃`() {
        assertNull(Protocol.parse("""{"type":"modelChanged"}"""))
        assertNull(Protocol.parse("""{"type":"modelChanged","model":null}"""))
        assertNull(Protocol.parse("""{"type":"modelChanged","model":123}"""))
    }

    @Test
    fun `解析 contextUsage 应答`() {
        val msg = Protocol.parse(
            """{"type":"contextUsage","id":"r7","usedTokens":456990,"windowTokens":1000000}"""
        )

        assertTrue(msg is SidecarMessage.ContextUsageReport)
        val r = msg as SidecarMessage.ContextUsageReport
        assertEquals("r7", r.requestId)
        assertEquals(456990L, r.usedTokens)
        assertEquals(1000000L, r.windowTokens)
    }

    @Test
    fun `contextUsage 缺 id 时按畸形丢弃`() {
        // 没有 id 就配不上对，那条应答永远不会被认领 —— 留着只会让它挂到超时
        assertNull(Protocol.parse("""{"type":"contextUsage","usedTokens":1,"windowTokens":2}"""))
    }

    @Test
    fun `contextUsage 缺计数时记 0`() {
        // 计数缺失不是"无从显示"，卡片本来就有"没测量值显示 0"这条路；
        // 而 id 缺了才真的没法用
        val msg = Protocol.parse("""{"type":"contextUsage","id":"r1"}""")

        assertTrue(msg is SidecarMessage.ContextUsageReport)
        assertEquals(0L, (msg as SidecarMessage.ContextUsageReport).usedTokens)
    }

    @Test
    fun `contextUsage 是请求-响应式的，能被待决表按 id 截走`() {
        // 漏登记 responseIdOf 的症状是：每次问用量都要等到 10 秒超时，
        // 而卡片看起来"只是不更新"—— 很难查
        val msg = Protocol.parse("""{"type":"contextUsage","id":"r7"}""")!!
        assertEquals("r7", Protocol.responseIdOf(msg))
    }

    @Test
    fun `encodeContextUsage 产出可配对的请求`() {
        val line = Protocol.encodeContextUsage("r9")
        val obj = com.google.gson.JsonParser.parseString(line.trim()).asJsonObject

        assertEquals("contextUsage", obj.get("method").asString)
        assertEquals("r9", obj.get("id").asString)
    }

    @Test
    fun `解析会话改名与打标签的回执`() {
        val renamed = Protocol.parse("""{"type":"sessionRenamed","id":"r1","sessionId":"s1","value":"我起的"}""")
        assertTrue(renamed is SidecarMessage.SessionRenamed)
        assertEquals("我起的", (renamed as SidecarMessage.SessionRenamed).title)

        // value 为 null 是有效的：标签被清掉了
        val tagged = Protocol.parse("""{"type":"sessionTagged","id":"r2","sessionId":"s1","value":null}""")
        assertTrue(tagged is SidecarMessage.SessionTagged)
        assertNull((tagged as SidecarMessage.SessionTagged).tag)
    }

    @Test
    fun `改名与标签都登记了配对 id`() {
        // 漏登记的后果是"点了改名要等 10 秒超时"，而界面上看起来只是没反应
        val renamed = Protocol.parse("""{"type":"sessionRenamed","id":"r1","sessionId":"s1"}""")!!
        val tagged = Protocol.parse("""{"type":"sessionTagged","id":"r2","sessionId":"s1"}""")!!
        assertEquals("r1", Protocol.responseIdOf(renamed))
        assertEquals("r2", Protocol.responseIdOf(tagged))
    }

    @Test
    fun `解析子代理列表`() {
        val msg = Protocol.parse(
            """
            {"type":"subagents","id":"r1","agents":[
              {"agentId":"a1","agentType":"Explore","description":"找调用点","toolUseId":"call_9"},
              {"agentId":"a2"}
            ]}
            """.trimIndent(),
        )

        assertTrue(msg is SidecarMessage.Subagents)
        val agents = (msg as SidecarMessage.Subagents).agents
        assertEquals(2, agents.size)
        assertEquals("call_9", agents[0].toolUseId, "运行中的任务靠它对上号")
        assertNull(agents[1].agentType, "元信息读不到时退化成只显示 id")
    }

    @Test
    fun `子代理缺 agentId 的条目跳过，不废掉整张表`() {
        // 与 parseSessionList 同一条：一条坏数据不该让另外几个都看不见
        val msg = Protocol.parse(
            """{"type":"subagents","id":"r1","agents":[{"agentType":"Explore"},{"agentId":"a2"}]}"""
        ) as SidecarMessage.Subagents

        assertEquals(listOf("a2"), msg.agents.map { it.agentId })
    }

    @Test
    fun `解析子代理转写，并登记配对 id`() {
        val msg = Protocol.parse(
            """{"type":"subagentMessages","id":"r3","agentId":"a1","items":[{"type":"user"}]}"""
        )

        assertTrue(msg is SidecarMessage.SubagentMessages)
        val m = msg as SidecarMessage.SubagentMessages
        assertEquals("a1", m.agentId, "要带 agentId 回来，界面才知道这是谁的转写")
        assertEquals(1, m.items.size)
        assertEquals("r3", Protocol.responseIdOf(m))
    }

    @Test
    fun `子代理转写缺 agentId 时按畸形丢弃`() {
        assertNull(Protocol.parse("""{"type":"subagentMessages","id":"r3","items":[]}"""))
    }

    @Test
    fun `会话改名与打标签的编码`() {
        val renamed = com.google.gson.JsonParser
            .parseString(Protocol.encodeRenameSession("r1", "s1", "名字").trim()).asJsonObject
        assertEquals("renameSession", renamed.get("method").asString)
        assertEquals("名字", renamed.getAsJsonObject("params").get("title").asString)

        // 清标签要**显式**写 JSON null —— 省略字段只表示"没提这件事"
        val tagged = com.google.gson.JsonParser
            .parseString(Protocol.encodeTagSession("r2", "s1", null).trim()).asJsonObject
            .getAsJsonObject("params")
        assertTrue(tagged.has("tag"), "tag 字段被省略了：清不掉标签")
        assertTrue(tagged.get("tag").isJsonNull)
    }

    @Test
    fun `未知类型映射为 Unknown 而非 null`() {
        // spec §3.3：未知类型必须被静默忽略，但不能与"解析失败"混淆
        val msg = Protocol.parse("""{"type":"some_future_type_v99"}""")
        assertTrue(msg is SidecarMessage.Unknown)
        assertEquals("some_future_type_v99", (msg as SidecarMessage.Unknown).type)
    }

    @Test
    fun `缺失 type 字段返回 null`() {
        assertNull(Protocol.parse("""{"foo":"bar"}"""))
    }

    @Test
    fun `空行与非法 JSON 返回 null`() {
        assertNull(Protocol.parse(""))
        assertNull(Protocol.parse("   "))
        assertNull(Protocol.parse("[claude-code:unrecognized_model] {\"a\":1}"))
        assertNull(Protocol.parse("not json at all"))
    }

    @Test
    fun `JSON 数组不视为合法消息`() {
        assertNull(Protocol.parse("[1,2,3]"))
    }

    @Test
    fun `字段类型不符时不抛错`() {
        // 容错取值：类型不对就当作缺失，不能抛 ClassCastException
        val msg = Protocol.parse("""{"type":"ready","sessionId":123,"model":null}""")
        val r = msg as SidecarMessage.Ready
        assertNull(r.sessionId, "数字不是字符串，应视为缺失")
        assertNull(r.model)
    }

    @Test
    fun `event 载荷缺失时返回 null`() {
        assertNull(Protocol.parse("""{"type":"event"}"""))
        assertNull(Protocol.parse("""{"type":"event","event":"不是对象"}"""))
    }

    // ---- 编码 ----

    @Test
    fun `encodeStart 产出单行 JSON`() {
        val line = Protocol.encodeStart(
            "req-1",
            StartParams(cwd = "C:\\proj", permissionMode = "default", model = "m")
        )
        assertEquals(1, line.trimEnd('\n').lines().size)
        val obj = JsonParser.parseString(line.trim()).asJsonObject
        assertEquals("start", obj.get("method").asString)
        assertEquals("req-1", obj.get("id").asString)
        assertEquals("C:\\proj", obj.getAsJsonObject("params").get("cwd").asString)
    }

    @Test
    fun `encodeStart 省略空的可选字段`() {
        val line = Protocol.encodeStart("r", StartParams(cwd = "/p", permissionMode = "default"))
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertTrue(!params.has("model"))
        assertTrue(!params.has("claudePath"))
        assertTrue(!params.has("extraDirs"))
        assertTrue(!params.has("envOverrides"))
    }

    @Test
    fun `encodeStart 传递完整可选字段`() {
        val line = Protocol.encodeStart(
            "r",
            StartParams(
                cwd = "/p", permissionMode = "plan", model = "m",
                claudePath = "/bin/claude", extraDirs = listOf("/a", "/b"),
                envOverrides = mapOf("K" to "V"),
            )
        )
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertEquals("plan", params.get("permissionMode").asString)
        assertEquals("/bin/claude", params.get("claudePath").asString)
        assertEquals(2, params.getAsJsonArray("extraDirs").size())
        assertEquals("V", params.getAsJsonObject("envOverrides").get("K").asString)
    }

    @Test
    fun `encodeSend 正确转义内嵌换行`() {
        val line = Protocol.encodeSend("r", "line1\nline2")
        assertEquals(
            1, line.trimEnd('\n').lines().size,
            "载荷中的换行必须被 JSON 转义，否则会破坏 NDJSON 分帧"
        )
    }

    // ---- 贴图（2026-09-15）----

    @Test
    fun `没图时 send 的报文里没有 images 键`() {
        // 向后兼容要能被**证明**，不是"应该没问题"：没图那条路上的报文字节
        // 与贴图之前完全一致（排队/补发那条路上可能还跑着旧侧车）
        val params = JsonParser.parseString(Protocol.encodeSend("r", "只有字").trim())
            .asJsonObject.getAsJsonObject("params")

        assertEquals("只有字", params.get("text").asString)
        assertFalse(params.has("images"), "没图还带 images 键，老那条路就不算没变")
    }

    @Test
    fun `有图时 images 是 mediaType 与 base64 的数组`() {
        val images = JsonParser.parseString(
            Protocol.encodeSend("r", "看这张", listOf(OutgoingImage("image/png", "AAAA"))).trim()
        ).asJsonObject.getAsJsonObject("params").getAsJsonArray("images")

        assertEquals(1, images.size())
        assertEquals("image/png", images[0].asJsonObject.get("mediaType").asString)
        assertEquals("AAAA", images[0].asJsonObject.get("data").asString)
    }

    @Test
    fun `图里的换行不会把一行撑成两行`() {
        // base64 正常是单行，但这条契约（一行一条 NDJSON）值得直接钉住：
        // 破了就是侧车那边 JSON 解析失败、消息整条消失
        val line = Protocol.encodeSend("r", "", listOf(OutgoingImage("image/png", "AA\nBB")))

        assertEquals(1, line.trimEnd('\n').lines().size)
    }

    @Test
    fun `encodeSimple 产出无参数的方法调用`() {
        val line = Protocol.encodeSimple("r", "interrupt")
        val obj = JsonParser.parseString(line.trim()).asJsonObject
        assertEquals("interrupt", obj.get("method").asString)
        assertTrue(obj.getAsJsonObject("params").isEmpty)
    }

    @Test
    fun `encodeSetPermissionMode 带 mode`() {
        val line = Protocol.encodeSetPermissionMode("r", "acceptEdits")
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertEquals("acceptEdits", params.get("mode").asString)
    }

    @Test
    fun `encodePermissionDecision 拒绝时带 message`() {
        val line = Protocol.encodePermissionDecision(
            "r", "tu-1", allow = false, updatedPermissions = null, message = "用户拒绝"
        )
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertEquals("deny", params.get("behavior").asString)
        assertEquals("tu-1", params.get("requestId").asString)
        assertEquals("用户拒绝", params.get("message").asString)
    }

    @Test
    fun `encodePermissionDecision 允许时带 updatedPermissions`() {
        val perms = JsonParser.parseString("""[{"type":"addRules"}]""").asJsonArray
        val line = Protocol.encodePermissionDecision(
            "r", "tu-1", allow = true, updatedPermissions = perms, message = null
        )
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertEquals("allow", params.get("behavior").asString)
        assertEquals(1, params.getAsJsonArray("updatedPermissions").size())
        assertTrue(!params.has("message"))
    }

    @Test
    fun `encodePermissionDecision 可携带 updatedInput`() {
        // AskUserQuestion 的答案就走这里：允许这个工具调用时改写它的入参。
        // PermissionResult 的 allow 分支带 updatedInput（sdk.d.ts:2340）
        val updated = JsonParser.parseString("""{"questions":[],"answers":{"问":"答"}}""").asJsonObject
        val line = Protocol.encodePermissionDecision(
            "r", "tu-1", allow = true, updatedPermissions = null, message = null,
            updatedInput = updated,
        )
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        val answers = params.getAsJsonObject("updatedInput").getAsJsonObject("answers")
        assertEquals("答", answers.get("问").asString)
    }

    @Test
    fun `没有 updatedInput 时不塞这个字段`() {
        // 传 null 与传空对象语义不同：后者等于"显式把入参改写成空"
        val line = Protocol.encodePermissionDecision(
            "r", "tu-1", allow = true, updatedPermissions = null, message = null,
        )
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertTrue(!params.has("updatedInput"), "不该凭空多出 updatedInput")
    }

    @Test
    fun `编码结果可被 parseLine 往返一致`() {
        // 与 sidecar 侧 ndjson.js 的契约：编码出的每一行都能被安全解析
        val line = Protocol.encodeSend("r", "含\"引号\"和\\反斜杠的文本")
        assertEquals(1, line.trimEnd('\n').lines().size)
        val obj = JsonParser.parseString(line.trim()).asJsonObject
        assertEquals("含\"引号\"和\\反斜杠的文本", obj.getAsJsonObject("params").get("text").asString)
    }

    // ---- 会话列表与历史（Task 2）----

    @Test
    fun `sessions 消息解析出列表`() {
        val line = """{"type":"sessions","id":"r1","sessions":[
            {"sessionId":"a","summary":"标题甲","firstPrompt":"甲","lastModified":111},
            {"sessionId":"b","summary":null,"firstPrompt":null,"lastModified":222}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.SessionList

        assertEquals("r1", msg.requestId)
        assertEquals(2, msg.sessions.size)
        assertEquals("标题甲", msg.sessions[0].summary)
        assertEquals(111L, msg.sessions[0].lastModified)
        assertNull(msg.sessions[1].summary)
    }

    @Test
    fun `sessions 缺 id 时整条丢弃`() {
        // 没有 id 就无从配对，留着只会变成一个永远等不到结果的占位
        val line = """{"type":"sessions","sessions":[{"sessionId":"a"}]}"""
        assertNull(Protocol.parse(line))
    }

    @Test
    fun `sessions 里坏条目只跳过它自己`() {
        // 一条坏数据不该让另外 49 个会话都看不见
        val line = """{"type":"sessions","id":"r1","sessions":[
            {"summary":"没有 id"},
            "不是对象",
            {"sessionId":"good","summary":"好的","lastModified":5}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.SessionList

        assertEquals(1, msg.sessions.size)
        assertEquals("good", msg.sessions[0].sessionId)
    }

    @Test
    fun `sessions 缺 lastModified 时给 0 而不是丢弃`() {
        val line = """{"type":"sessions","id":"r1","sessions":[{"sessionId":"a"}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.SessionList

        assertEquals(0L, msg.sessions[0].lastModified)
    }

    @Test
    fun `history 消息解析出条目`() {
        val line = """{"type":"history","id":"r2","sessionId":"s1","items":[
            {"type":"user","message":{"role":"user","content":"你好"}},
            {"type":"assistant","message":{"role":"assistant","content":[]}}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.History

        assertEquals("r2", msg.requestId)
        assertEquals("s1", msg.sessionId)
        assertEquals(2, msg.items.size)
        assertEquals("user", msg.items[0].get("type").asString)
    }

    @Test
    fun `history 缺 sessionId 时丢弃`() {
        val line = """{"type":"history","id":"r2","items":[]}"""
        assertNull(Protocol.parse(line))
    }

    @Test
    fun `history 里非对象条目被过滤`() {
        val line = """{"type":"history","id":"r2","sessionId":"s1","items":[1,"x",{"type":"user"}]}"""
        val msg = Protocol.parse(line) as SidecarMessage.History

        assertEquals(1, msg.items.size, "只有对象才该留下")
    }

    @Test
    fun `encodeStart 带 resumeSessionId`() {
        val json = Protocol.encodeStart(
            "r1",
            StartParams(cwd = "/tmp", permissionMode = "default", resumeSessionId = "sess-1"),
        )
        val obj = JsonParser.parseString(json.trim()).asJsonObject

        assertEquals("sess-1", obj.getAsJsonObject("params").get("resumeSessionId").asString)
    }

    @Test
    fun `encodeStart 不带 resumeSessionId 时不写这个字段`() {
        // 传 null 与传空字符串语义不同 —— 绝不能写成空串
        val json = Protocol.encodeStart("r1", StartParams(cwd = "/tmp", permissionMode = "default"))
        val obj = JsonParser.parseString(json.trim()).asJsonObject

        assertFalse(obj.getAsJsonObject("params").has("resumeSessionId"))
    }

    @Test
    fun `encodeListSessions 与 encodeLoadHistory 的形状`() {
        val a = JsonParser.parseString(
            Protocol.encodeListSessions("r1", "C:/proj", 50, 0).trim()
        ).asJsonObject
        assertEquals("listSessions", a.get("method").asString)
        assertEquals(50, a.getAsJsonObject("params").get("limit").asInt)
        assertEquals("C:/proj", a.getAsJsonObject("params").get("dir").asString)

        val b = JsonParser.parseString(
            Protocol.encodeLoadHistory("r2", "C:/proj", "sess-1").trim()
        ).asJsonObject
        assertEquals("loadHistory", b.get("method").asString)
        assertEquals("sess-1", b.getAsJsonObject("params").get("sessionId").asString)
    }

    @Test
    fun `responseIdOf 只认响应类消息`() {
        assertEquals(
            "r1",
            Protocol.responseIdOf(
                SidecarMessage.SessionList("r1", emptyList())
            ),
        )
        assertEquals(
            "r2",
            Protocol.responseIdOf(SidecarMessage.History("r2", "s", emptyList<JsonObject>())),
        )
        // 非响应消息必须返回 null，否则 SidecarClient 会把它们从 listener 那里截走
        assertNull(Protocol.responseIdOf(SidecarMessage.Ready("s", "m")))
        assertNull(Protocol.responseIdOf(SidecarMessage.Unknown("whatever")))

        // ModelChanged 是**广播**（同 PermissionModeChanged / EffortChanged）：
        // 它没有 id，认领它的是 ClaudePanel 手里的 pendingModelPick ——
        // 那边还要拿回执里的名字与发出去的那个对一遍，比 id 配对多一层校验。
        // 顺手给它登记 responseIdOf 会让它被待决表截走，而 sidecar 失败时发的是
        // 一条**不带 id** 的 error（responseIdOf 对 Failure 返回 null），
        // 于是那条待决请求只能等 10 秒超时关闭
        assertNull(Protocol.responseIdOf(SidecarMessage.ModelChanged("m")))
    }

    // ---- 删除会话 ----

    @Test
    fun `encodeDeleteSession 带 id 与 sessionId`() {
        val line = Protocol.encodeDeleteSession("r7", "d9617553-1a2b-3c4d-5e6f-7890abcdef12")
        val obj = JsonParser.parseString(line.trim()).asJsonObject

        assertEquals("r7", obj.get("id").asString)
        assertEquals("deleteSession", obj.get("method").asString)
        assertEquals(
            "d9617553-1a2b-3c4d-5e6f-7890abcdef12",
            obj.getAsJsonObject("params").get("sessionId").asString,
        )
    }

    @Test
    fun `sessionDeleted 解析出 id 与 sessionId`() {
        val line = """{"type":"sessionDeleted","id":"r7","sessionId":"abc-123"}"""

        val msg = Protocol.parse(line)

        assertTrue(msg is SidecarMessage.SessionDeleted)
        assertEquals("r7", (msg as SidecarMessage.SessionDeleted).requestId)
        assertEquals("abc-123", msg.sessionId)
    }

    @Test
    fun `sessionDeleted 缺 sessionId 时丢弃`() {
        // 缺了它就不知道该把哪一行从列表里去掉 —— 留着只会让界面删错行
        assertNull(Protocol.parse("""{"type":"sessionDeleted","id":"r7"}"""))
    }

    @Test
    fun `responseIdOf 认得 sessionDeleted`() {
        // 不认的话这条回执就配不上对，删除会一直挂到超时
        assertEquals(
            "r7",
            Protocol.responseIdOf(SidecarMessage.SessionDeleted("r7", "abc-123")),
        )
    }

    @Test
    fun `commands 消息解析出命令与技能两组`() {
        val line = """
            {"type":"commands","id":"7","commands":[
              {"name":"compact","description":"压缩上下文","argumentHint":"","aliases":["compress"]},
              {"name":"Debug Issue","description":"查问题","argumentHint":"","aliases":[]}
            ],"skills":[{"name":"Debug Issue","description":"查问题","argumentHint":"","aliases":[]}]}
        """.trimIndent()

        val msg = Protocol.parse(line) as SidecarMessage.Commands

        assertEquals("7", msg.requestId)
        assertEquals(2, msg.commands.size)
        assertEquals("compact", msg.commands[0].name)
        assertEquals(listOf("compress"), msg.commands[0].aliases)
        assertEquals(1, msg.skills.size)
        assertEquals(listOf("Debug Issue"), msg.skills.map { it.name })
    }

    @Test
    fun `commands 缺 id 时整条丢弃 —— 留着会变成永远等不到结果的占位`() {
        val line = """{"type":"commands","commands":[],"skills":[]}"""
        assertNull(Protocol.parse(line))
    }

    @Test
    fun `commands 里缺 name 的条目跳过，不废掉整个列表`() {
        val line = """
            {"type":"commands","id":"7","commands":[
              {"description":"没有名字"},
              {"name":"compact","description":"压缩","argumentHint":"","aliases":[]}
            ],"skills":[]}
        """.trimIndent()

        val msg = Protocol.parse(line) as SidecarMessage.Commands
        assertEquals(1, msg.commands.size)
        assertEquals("compact", msg.commands[0].name)
    }

    @Test
    fun `commands 是响应消息，带关联 id`() {
        val line = """{"type":"commands","id":"7","commands":[],"skills":[]}"""
        val msg = Protocol.parse(line)!!
        assertEquals("7", Protocol.responseIdOf(msg))
    }

    @Test
    fun `encodeListCommands 不带参数`() {
        val json = Protocol.encodeListCommands("req-1")
        assertTrue(json.contains(""""method":"listCommands""""))
        assertTrue(json.endsWith("\n"), "NDJSON 必须以换行结尾")
    }
}
