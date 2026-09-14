package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 上下文卡的取数与格式化。
 *
 * **取数本身不在这里测** —— 那两个数（已占用 / 窗口）是 CLI 的
 * `getContextUsage()` 算的，插件只负责问和显示。这里钉的是显示那一半：
 * 缩写、百分比、以及"窗口未知就不做除法"。
 */
class ContextUsageTest {

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
        val usage = ContextUsage(usedTokens = 12345, windowTokens = 200000)
        assertEquals(6, contextPercentOf(usage))
        assertEquals("12.3k / 200k", contextRatioText(usage))
    }

    @Test
    fun `窗口未知时不给比例，绝对数只给已用量`() {
        val usage = ContextUsage(usedTokens = 500, windowTokens = 0)
        assertNull(contextPercentOf(usage), "没有窗口就没有比例，也不许除零")
        assertEquals("500", contextRatioText(usage))
    }

    @Test
    fun `占用比例四舍五入`() {
        assertEquals(1, contextPercentOf(ContextUsage(usedTokens = 1000, windowTokens = 200000)))
    }

    @Test
    fun `与 CLI 自己报的百分比对得上`() {
        // 2026-09-14 实测：resume 一条长会话，CLI 报
        // totalTokens=456990 / rawMaxTokens=1000000 / percentage=46。
        // 卡片是自己算的（要把百分比和绝对数分开渲染），所以这条钉住两边一致
        val usage = ContextUsage(usedTokens = 456990, windowTokens = 1000000)
        assertEquals(46, contextPercentOf(usage))
    }
}
