package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 使用量统计的协议层（2026-10-10）。
 *
 * 容错口径照 context 那族：读得出来的要、读不出来的丢那一行 —— 少一天是看得见
 * 的，整页空着不是；而缺 id 的应答**整条丢**（无从配对，留着只会变成一次超时）。
 */
class ProtocolUsageTest {

    @Test
    fun `usage 应答：坏行丢、缺模型丢、缺 id 整条丢`() {
        val ok = Protocol.parse(
            """{"type":"usage","id":"7","scope":"all","rows":[""" +
                """{"d":"2026-10-10","m":{"m1":{"i":1,"o":2,"r":3,"c":4,"cost":0.12}}},""" +
                """{"d":"坏日期","m":{"m1":{"i":1}}},""" +
                """{"d":"2026-10-09","m":{}},""" +
                """{"d":"2026-10-08","m":{"m2":{"i":5}}}]}"""
        ) as SidecarMessage.UsageReport

        assertEquals("7", ok.requestId)
        assertEquals("all", ok.scope)
        assertEquals(2, ok.rows.size)
        assertEquals(LocalDate.of(2026, 10, 10), ok.rows[0].date)
        assertEquals(UsageModelTokens(1, 2, 3, 4, 0.12), ok.rows[0].models["m1"])
        // 缺 cost 的兼容位给 0 —— 旧版本 sidecar 不发它
        assertEquals(UsageModelTokens(5, 0, 0, 0, 0.0), ok.rows[1].models["m2"])
        assertEquals("7", Protocol.responseIdOf(ok), "usage 必须在待决表里配得上对")

        assertNull(
            Protocol.parse("""{"type":"usage","scope":"all","rows":[]}"""),
            "缺 id 无从配对，整条丢",
        )
    }

    @Test
    fun `usageTick：缺字段给零与空表（它是推送，不是应答）`() {
        val t = Protocol.parse(
            """{"type":"usageTick","project":"C:/p","today":{"t":315,"cost":1.5},"spark":[1,0,2]}"""
        ) as SidecarMessage.UsageTick
        assertEquals("C:/p", t.project)
        assertEquals(315L, t.todayTokens)
        assertEquals(1.5, t.todayCost)
        assertEquals(listOf(1L, 0L, 2L), t.spark)
        assertNull(Protocol.responseIdOf(t), "推送没有 id —— 走 listener 那条路")

        val bare = Protocol.parse("""{"type":"usageTick"}""") as SidecarMessage.UsageTick
        assertEquals("", bare.project)
        assertEquals(0L, bare.todayTokens)
        assertNull(bare.todayCost)
        assertEquals(emptyList<Long>(), bare.spark)
    }

    @Test
    fun `encodeUsageReport：scope 必带，project 没有就整个省略`() {
        val with = Protocol.encodeUsageReport("9", "project", "C:/p")
        assertTrue(with.contains("\"method\":\"usageReport\""))
        assertTrue(with.contains("\"scope\":\"project\""))
        assertTrue(with.contains("\"project\":\"C:/p\""))

        val without = Protocol.encodeUsageReport("10", "all", null)
        assertFalse(without.contains("project"), "省略与 null 在 sidecar 侧是两种意思")
    }
}
