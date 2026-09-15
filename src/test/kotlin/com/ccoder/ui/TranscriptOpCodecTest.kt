package com.ccoder.ui

import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin ↔ web 的线格式契约。
 *
 * 这个文件补得晚：2026-09-11 出了"界面全空但零报错"的故障，根因就是
 * [TranscriptOpCodec.encodePushCall] 的调用约定错了——JSON 被内联成对象
 * 字面量传给 `pushBatch`，而 web 侧签名的形参是 `string`，于是
 * `JSON.parse` 收到数组、String() 成 "[object Object],..." 抛错、被空 catch
 * 吞掉。两侧各自的测试都是绿的，因为**没有一条测试覆盖调用约定本身**。
 */
class TranscriptOpCodecTest {

    private fun user(id: String, text: String) =
        TranscriptOp.Append(TranscriptItem.User(id, 1726050000000, text))

    // ---- encodeBatch ----

    @Test
    fun `encodeBatch 产出可被 JSON 解析的数组`() {
        val json = TranscriptOpCodec.encodeBatch(listOf(user("m0", "你好")))
        val parsed = JsonParser.parseString(json).asJsonArray

        assertEquals(1, parsed.size())
        val item = parsed[0].asJsonObject.getAsJsonObject("item")
        assertEquals("append", parsed[0].asJsonObject.get("op").asString)
        assertEquals("user", item.get("kind").asString)
        assertEquals("m0", item.get("id").asString)
        assertEquals("你好", item.get("text").asString)
        // ts 必须是数字：web 侧 parseItem 要求 typeof ts === 'number'
        assertTrue(item.get("ts").asJsonPrimitive.isNumber)
    }

    @Test
    fun `encodeBatch 对空列表产出空数组`() {
        assertEquals("[]", TranscriptOpCodec.encodeBatch(emptyList()))
    }

    // ---- encodePushCall：本次故障的核心 ----

    @Test
    fun `推送调用的实参是字符串字面量而非对象字面量`() {
        val call = TranscriptOpCodec.encodePushCall(TranscriptOpCodec.encodeBatch(listOf(user("m0", "hi"))))

        // 关键断言：实参以单引号开头。若哪天又改回内联 JSON，
        // 这里会是 `pushBatch([`，测试立刻变红。
        assertTrue(
            call.startsWith("window.ccoder.pushBatch('["),
            "实参必须是被引号包裹的字符串，实际是：$call",
        )
        assertTrue(call.endsWith("');"), "调用必须以 '); 收尾，实际是：$call")
    }

    @Test
    fun `推送调用中的反斜杠与引号能完整还原`() {
        // 含反斜杠与引号，模拟 toolUse 的 input 字段
        val originalInput = "{\"path\":\"C:\\\\a\"}"
        val batch = TranscriptOpCodec.encodeBatch(
            listOf(TranscriptOp.Append(TranscriptItem.ToolUse("m0", 1L, "toolu_1", "Read", originalInput))),
        )
        val call = TranscriptOpCodec.encodePushCall(batch)

        // 按 JS 单引号字符串的规则反转义，应当还原成原始 JSON 文本
        val inner = call.removePrefix("window.ccoder.pushBatch('").removeSuffix("');")
        val restored = inner.replace("\\'", "'").replace("\\\\", "\\")
        assertEquals(batch, restored)

        // 还原后的文本仍是合法 JSON，且 input 字段与原值逐字符一致 ——
        // 这才是真正要保证的：内容经过层层转义后不能变样
        val item = JsonParser.parseString(restored).asJsonArray[0]
            .asJsonObject.getAsJsonObject("item")
        assertEquals(originalInput, item.get("input").asString)
    }

    @Test
    fun `推送调用不含裸换行`() {
        val batch = TranscriptOpCodec.encodeBatch(listOf(user("m0", "a\nb")))
        val call = TranscriptOpCodec.encodePushCall(batch)

        assertTrue(!call.contains("\n") && !call.contains("\r"), "调用必须单行：$call")
    }

    // ---- 贴图（2026-09-15）----

    @Test
    fun `用户项带图时 images 是 data URL 数组`() {
        val item = TranscriptItem.User(
            id = "m1",
            ts = 1,
            text = "看这张",
            images = listOf("data:image/jpeg;base64,AAAA", "data:image/jpeg;base64,BBBB"),
        )
        val json = JsonParser.parseString(TranscriptOpCodec.encodeBatch(listOf(TranscriptOp.Append(item))))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertEquals("user", json.get("kind").asString)
        val images = json.getAsJsonArray("images")
        assertEquals(2, images.size())
        assertEquals("data:image/jpeg;base64,AAAA", images[0].asString)
    }

    @Test
    fun `没图时一个字段都不加 —— 纯文字那条路一字不变`() {
        val json = JsonParser.parseString(
            TranscriptOpCodec.encodeBatch(listOf(TranscriptOp.Append(TranscriptItem.User("m1", 1, "只有字"))))
        ).asJsonArray[0].asJsonObject.getAsJsonObject("item")

        assertTrue(!json.has("images"), "没图还带 images 键，那个报文就不算没变")
    }

    // ---- 回合结束那一行（2026-09-15：改成显示本次 token 与耗时）----

    @Test
    fun `result 的 token 与耗时都上线，花费也在（界面不显示它，但数据留着）`() {
        val json = item(
            TranscriptItem.Result(
                id = "m1",
                ts = 1,
                subtype = "success",
                costUsd = 0.0231,
                durationMs = 33818,
                inputTokens = 12432,
                outputTokens = 1234,
                cacheReadTokens = 8100,
            ),
        )

        assertEquals("result", json.get("kind").asString)
        assertEquals("success", json.get("subtype").asString)
        assertEquals(12432L, json.get("inputTokens").asLong)
        assertEquals(1234L, json.get("outputTokens").asLong)
        assertEquals(8100L, json.get("cacheReadTokens").asLong)
        assertEquals(33818L, json.get("durationMs").asLong)
        assertEquals(0.0231, json.get("costUsd").asDouble, 0.0001)
    }

    @Test
    fun `旧记录那三个字段为 null 时整体省略 —— web 侧少一层判空`() {
        val json = item(TranscriptItem.Result("m1", 1, "success", null, null))

        assertTrue(!json.has("inputTokens"))
        assertTrue(!json.has("outputTokens"))
        assertTrue(!json.has("cacheReadTokens"))
        assertTrue(!json.has("durationMs"))
    }

    private fun item(entry: TranscriptItem): com.google.gson.JsonObject =
        JsonParser.parseString(TranscriptOpCodec.encodeBatch(listOf(TranscriptOp.Append(entry))))
            .asJsonArray[0].asJsonObject.getAsJsonObject("item")
}
