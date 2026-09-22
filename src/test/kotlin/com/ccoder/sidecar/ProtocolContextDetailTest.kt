package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 上下文明细的解析。
 *
 * 钉的核心是**拼法**：d.ts 写 snake_case，而顶层那两个数 2026-09-14 实测是驼峰。
 * 这几层谁也没实测过 —— 两种都认才是安全的读法（认错一个就是静默少一列，
 * 而"少一列"在界面上长得像"这次没有工具"）。
 */
class ProtocolContextDetailTest {

    private fun parse(json: String): SidecarMessage.ContextUsageReport? =
        Protocol.parse(json) as? SidecarMessage.ContextUsageReport

    private fun line(detail: String) =
        """{"type":"contextUsage","id":"r1","usedTokens":268000,"windowTokens":1000000,"detail":$detail}"""

    @Test
    fun `驼峰拼法读得出来`() {
        val msg = parse(
            line(
                """{"model":"m","percentage":27,"overLimit":null,
                    "categories":[{"name":"Messages","tokens":250000,"kind":"used"}],
                    "mcpTools":[{"name":"mcp__a__b","serverName":"srv","tokens":8100}],
                    "memoryFiles":[{"path":"/p/CLAUDE.md","type":"Project","tokens":3200}],
                    "agents":[{"agentType":"explore","source":"projectSettings","tokens":1600}],
                    "skills":[{"name":"sk","source":"plugin","pluginName":"cc","tokens":800}]}"""
            )
        )

        val d = msg?.detail
        assertNotNull(d)
        assertEquals("m", d!!.model)
        assertEquals(27, d.percentage)
        assertEquals(listOf(ContextRow("Messages", "", 250_000L, "used")), d.categories)
        assertEquals("srv", d.mcpTools[0].sub, "serverName 没读出来")
        assertEquals("/p/CLAUDE.md", d.memoryFiles[0].label)
        assertEquals("explore", d.agents[0].label, "agentType 没读出来")
        assertEquals("plugin", d.skills[0].sub)
    }

    @Test
    fun `行内的下划线拼法也读得出来`() {
        val msg = parse(
            line(
                """{"model":"m","percentage":27,
                    "overLimit":{"tokensOver":12400,"kind":"hard_limit"},
                    "categories":[{"name":"Messages","tokens":250000,"kind":"used"}],
                    "mcpTools":[{"name":"mcp__a__b","server_name":"srv","tokens":8100}],
                    "memoryFiles":[],"agents":[{"agent_type":"explore","source":"x","tokens":1}],"skills":[]}"""
            )
        )

        val d = msg!!.detail!!
        assertEquals("srv", d.mcpTools[0].sub, "server_name 没读出来")
        assertEquals("explore", d.agents[0].label, "agent_type 没读出来")
        assertEquals(ContextOverLimit(12_400, ContextOverLimit.HARD_LIMIT), d.overLimit)
    }

    @Test
    fun `没有明细时 detail 是 null（老版本 sidecar）`() {
        val msg = parse("""{"type":"contextUsage","id":"r1","usedTokens":1,"windowTokens":2}""")

        assertNull(msg?.detail)
        assertEquals(1L, msg?.usedTokens)
    }

    @Test
    fun `坏行丢掉、好行留下 —— 一条读不出来不该让整页空着`() {
        val msg = parse(
            line(
                """{"model":"","percentage":null,"overLimit":null,
                    "categories":[{"tokens":1},{"name":"Messages","tokens":250000,"kind":"used"}],
                    "mcpTools":[{"name":"x"}],"memoryFiles":[],"agents":[],"skills":[]}"""
            )
        )

        val d = msg!!.detail!!
        assertEquals(1, d.categories.size, "缺名字的那行该丢掉")
        assertEquals("Messages", d.categories[0].label)
        // 缺 tokens 的行留下但记 0 —— 零 token 由**显示层**决定不画（ContextDetail.kt）
        assertEquals(0L, d.mcpTools[0].tokens)
    }

    @Test
    fun `明细里字段缺失不炸`() {
        val msg = parse(line("{}"))

        val d = msg!!.detail
        assertNotNull(d, "空对象也是一份明细 —— 它读出来只是什么都空")
        assertEquals("", d!!.model)
        assertEquals(emptyList<ContextRow>(), d.categories)
        assertNull(d.percentage)
    }
}
