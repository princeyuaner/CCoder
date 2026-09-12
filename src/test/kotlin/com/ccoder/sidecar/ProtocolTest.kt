package com.ccoder.sidecar

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
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
}
