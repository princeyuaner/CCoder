package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 输入框上方那条"上下文长度"的取数与格式化。
 *
 * 数据来自 result 事件的 `modelUsage`（SDK 已给出 `contextWindow`，
 * 所以占用比例是真算出来的，不用猜）。
 */
class ContextUsageTest {

    private fun event(json: String) = JsonParser.parseString(json).asJsonObject

    // ---- 取数 ----

    @Test
    fun `从 result 事件的 modelUsage 里取用量`() {
        val usage = contextUsageOf(
            event(
                """
                {"type":"result","subtype":"success","modelUsage":{
                  "claude-sonnet-5":{"inputTokens":12345,"contextWindow":200000}}}
                """.trimIndent(),
            ),
        )

        assertEquals(12345L, usage?.inputTokens)
        assertEquals(200000L, usage?.contextWindow)
    }

    @Test
    fun `有多个模型时取输入量最大的那个`() {
        // 子 agent / 辅助调用的输入量远小于主对话；主对话才是"上下文占用"
        val usage = contextUsageOf(
            event(
                """
                {"type":"result","modelUsage":{
                  "claude-haiku":{"inputTokens":800,"contextWindow":200000},
                  "claude-sonnet-5":{"inputTokens":12345,"contextWindow":200000}}}
                """.trimIndent(),
            ),
        )

        assertEquals(12345L, usage?.inputTokens)
    }

    @Test
    fun `没有 modelUsage 时返回 null，而不是造一个零值`() {
        // 零值会被显示成"上下文 0"，那是假信息
        assertNull(contextUsageOf(event("""{"type":"result","subtype":"success"}""")))
        assertNull(contextUsageOf(event("""{"type":"assistant"}""")))
        assertNull(contextUsageOf(event("""{"type":"result","modelUsage":{}}""")))
    }

    @Test
    fun `字段缺失或类型不对时返回 null 而不是抛异常`() {
        assertNull(
            contextUsageOf(event("""{"type":"result","modelUsage":{"m":{"contextWindow":200000}}}""")),
            "缺 inputTokens",
        )
        assertNull(
            contextUsageOf(event("""{"type":"result","modelUsage":{"m":{"inputTokens":"abc"}}}""")),
            "inputTokens 是字符串",
        )
    }

    @Test
    fun `contextWindow 缺失时仍返回用量，只是算不出比例`() {
        val usage = contextUsageOf(
            event("""{"type":"result","modelUsage":{"m":{"inputTokens":500}}}"""),
        )

        assertEquals(500L, usage?.inputTokens)
        assertEquals(0L, usage?.contextWindow, "缺失记为 0，由格式化那层决定怎么显示")
    }

    // ---- 格式化 ----

    @Test
    fun `token 数按量级缩写`() {
        assertEquals("0", formatTokenCount(0))
        assertEquals("999", formatTokenCount(999))
        assertEquals("1.5k", formatTokenCount(1500))
        assertEquals("12.3k", formatTokenCount(12345))
        assertEquals("200k", formatTokenCount(200000), "整数不该拖一个 .0")
        assertEquals("1.2M", formatTokenCount(1_234_567))
    }

    @Test
    fun `比例与绝对数分开给，卡片各取所需`() {
        val usage = ContextUsage(inputTokens = 12345, contextWindow = 200000)
        assertEquals(6, contextPercentOf(usage))
        assertEquals("12.3k / 200k", contextRatioText(usage))
    }

    @Test
    fun `窗口未知时不给比例，绝对数只给已用量`() {
        val usage = ContextUsage(inputTokens = 500, contextWindow = 0)
        assertNull(contextPercentOf(usage), "没有窗口就没有比例，也不许除零")
        assertEquals("500", contextRatioText(usage))
    }

    @Test
    fun `占用比例四舍五入`() {
        assertEquals(1, contextPercentOf(ContextUsage(inputTokens = 1000, contextWindow = 200000)))
    }
}
