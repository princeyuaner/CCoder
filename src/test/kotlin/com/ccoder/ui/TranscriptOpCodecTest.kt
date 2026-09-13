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
}
