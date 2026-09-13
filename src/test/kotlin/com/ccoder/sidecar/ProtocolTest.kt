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
