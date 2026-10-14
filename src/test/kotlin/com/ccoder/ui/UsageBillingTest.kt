package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime

/**
 * 供应商账目（方案 A）的数据层 —— DMXAPI 账单 + opencode 额度 + DeepSeek 余额都在这屋。
 * 纯函数直接打；网络那几条（[fetchDmxBill] / [fetchOpencodeUsage] / [fetchDeepseekBalance]）
 * 用例一律不碰网。
 *
 * 口径钉死在这里：**额度 ÷ 500000 = 元**（DMX 站点自己的算法）、错误形状
 * `{"error": …}`（实测过的 400）、识别按**主机名**（相似域名不许认）、
 * 闸是时间窗 + 飞行中不重入（force 只跳窗口；**窗口长度跟着各家走** ——
 * DMX 60 秒 / opencode 120 秒）、opencode 三档窗口的 JSON 形状（2026-10-13 实测样本）。
 */
class UsageBillingTest {

    // ------------------------------------------------------------------ 识别

    @Test
    fun `dmxapi 域名才认 —— 含子域、不认相似域名`() {
        assertEquals(DMX_BILL_ENDPOINT, dmxBillEndpoint("https://www.dmxapi.cn/api"))
        assertEquals(DMX_BILL_ENDPOINT, dmxBillEndpoint("https://api.dmxapi.cn/v1"))
        assertEquals(DMX_BILL_ENDPOINT, dmxBillEndpoint("DMXAPI.CN"))
        assertEquals(DMX_BILL_ENDPOINT, dmxBillEndpoint("dmxapi.cn"), "没写协议也认")
        assertNull(dmxBillEndpoint(""))
        assertNull(dmxBillEndpoint("   "))
        assertNull(dmxBillEndpoint("https://dmxapi.cn.evil.com/v1"), "后缀像 dmxapi.cn 但不是")
        assertNull(dmxBillEndpoint("https://notdmxapi.cn/v1"), "前缀像 dmxapi.cn 但不是")
        assertNull(dmxBillEndpoint("https://api.anthropic.com"))
    }

    @Test
    fun `opencode 域名才认 —— 含子域、不认相似域名、不吞 dmx`() {
        assertEquals(OPENCODE_USAGE_ENDPOINT, opencodeQuotaEndpoint("https://opencode.ai/zen/go"))
        assertEquals(OPENCODE_USAGE_ENDPOINT, opencodeQuotaEndpoint("https://api.opencode.ai"))
        assertEquals(OPENCODE_USAGE_ENDPOINT, opencodeQuotaEndpoint("opencode.ai"), "没写协议也认")
        assertNull(opencodeQuotaEndpoint(""))
        assertNull(opencodeQuotaEndpoint("   "))
        assertNull(opencodeQuotaEndpoint("https://opencode.ai.evil.com/v1"), "后缀像 opencode.ai 但不是")
        assertNull(opencodeQuotaEndpoint("https://myopencode.ai/v1"), "前缀像 opencode.ai 但不是")
        assertNull(opencodeQuotaEndpoint("https://www.dmxapi.cn/api"), "dmx 不是 opencode")
    }

    // ------------------------------------------------------------------ URL

    @Test
    fun `URL：key 当 query 参数、要转义`() {
        assertEquals("https://tokenlog.dmxapi.cn/?key=sk-abc", dmxBillUrl(DMX_BILL_ENDPOINT, "sk-abc"))
        assertEquals("https://tokenlog.dmxapi.cn/?key=sk-a%2Bb", dmxBillUrl(DMX_BILL_ENDPOINT, "sk-a+b"))
    }

    // ------------------------------------------------------------------ 解析

    @Test
    fun `解析：额度除 500000 才是元；余额、提示、补全各就各位`() {
        val json = """
        {
          "token_name": "我的令牌",
          "token_unlimited": false,
          "token_balance": 61700000,
          "stats": { "total_quota": 4250000, "total_prompt": 12340000, "total_completion": 380000, "model_count": 2 },
          "data": []
        }
        """.trimIndent()
        val bill = parseDmxBill(json, LocalTime.of(14, 32))
        assertEquals(DMX_PROVIDER_NAME, bill.provider)
        assertEquals(8.5, bill.yuanToday, 1e-9) // 4_250_000 / 500_000
        assertEquals(12_340_000L, bill.promptTokens)
        assertEquals(380_000L, bill.completionTokens)
        assertEquals(123.4, bill.balanceYuan!!, 1e-9) // 61_700_000 / 500_000
        assertEquals(LocalTime.of(14, 32), bill.fetchedAt)
    }

    @Test
    fun `解析：不限额时余额是 null（界面显示不限量）；字段缺了当零`() {
        val bill = parseDmxBill("""{ "token_unlimited": true }""", LocalTime.of(9, 0))
        assertNull(bill.balanceYuan)
        assertEquals(0.0, bill.yuanToday, 1e-9)
        assertEquals(0L, bill.promptTokens)
        assertEquals(0L, bill.completionTokens)
    }

    @Test
    fun `解析：error 优先抛 —— 实测 400 的形状`() {
        val e = assertThrows(UsageBillError::class.java) {
            parseDmxBill("""{"error":"API Key 格式不正确（应为 sk- 开头）"}""")
        }
        assertEquals("API Key 格式不正确（应为 sk- 开头）", e.message)
    }

    @Test
    fun `解析：不是 JSON 对象也抛 UsageBillError（别漏成 Gson 自己的异常）`() {
        assertThrows(UsageBillError::class.java) { parseDmxBill("<html>502 Bad Gateway</html>") }
        assertThrows(UsageBillError::class.java) { parseDmxBill("[1,2,3]") }
    }

    @Test
    fun `errorMessageOf：非 JSON 与没有 error 都给 null`() {
        assertNull(errorMessageOf("<html>"))
        assertNull(errorMessageOf("""{"stats":{}}"""))
        assertEquals("x", errorMessageOf("""{"error":"x"}"""))
        assertEquals(
            "m",
            errorMessageOf("""{"error":{"message":"m","type":"t"}}"""),
            "对象形（DeepSeek 401 实测）也认",
        )
    }

    // ------------------------------------------------------------------ opencode 额度

    @Test
    fun `额度解析：三档窗口按固定顺序、时间是 UTC 瞬时（2026-10-13 实测样本）`() {
        val json = """
        {"usage":{"rolling":{"status":"ok","percent":12,"resetsAt":"2026-10-13T12:46:37.000Z"},
                  "weekly":{"status":"ok","percent":4,"resetsAt":"2026-10-19T00:00:00.000Z"},
                  "monthly":{"status":"ok","percent":2,"resetsAt":"2026-11-13T06:58:17.000Z"}}}
        """.trimIndent()
        val q = parseOpencodeQuota(json, LocalTime.of(20, 3))
        assertEquals(
            listOf(QuotaWindowKind.Rolling, QuotaWindowKind.Weekly, QuotaWindowKind.Monthly),
            q.windows.map { it.kind },
        )
        assertEquals(12.0, q.windows[0].percent, 1e-9)
        assertEquals(Instant.parse("2026-10-13T12:46:37Z"), q.windows[0].resetsAt)
        assertFalse(q.windows[0].rateLimited)
        assertEquals(4.0, q.windows[1].percent, 1e-9)
        assertEquals(2.0, q.windows[2].percent, 1e-9)
        assertEquals(LocalTime.of(20, 3), q.fetchedAt)
    }

    @Test
    fun `额度解析：限流档抬到 100；缺 percent 跳过那一档（不拿 0 糊弄）；坏时间给 null`() {
        val json = """
        {"usage":{"rolling":{"status":"rate-limited","percent":98},
                  "weekly":{"status":"ok"},
                  "monthly":{"status":"ok","percent":2,"resetsAt":"不是时间"}}}
        """.trimIndent()
        val q = parseOpencodeQuota(json)
        assertEquals(listOf(QuotaWindowKind.Rolling, QuotaWindowKind.Monthly), q.windows.map { it.kind })
        assertTrue(q.windows[0].rateLimited)
        assertEquals(100.0, q.windows[0].percent, 1e-9, "限流 = 打满，抬到 100")
        assertNull(q.windows[1].resetsAt, "认不出的重置时间给 null —— 不毁整档")
    }

    @Test
    fun `额度解析：error 优先抛；非 JSON、缺 usage、一档都没有 —— 都抛 UsageBillError`() {
        assertEquals(
            "boom",
            assertThrows(UsageBillError::class.java) { parseOpencodeQuota("""{"error":"boom"}""") }.message,
        )
        assertThrows(UsageBillError::class.java) { parseOpencodeQuota("<html>502 Bad Gateway</html>") }
        assertThrows(UsageBillError::class.java) { parseOpencodeQuota("""{"ok":true}""") }
        assertThrows(UsageBillError::class.java) { parseOpencodeQuota("""{"usage":{}}""") }
    }

    // ------------------------------------------------------------------ DeepSeek 余额

    @Test
    fun `deepseek 域名才认 —— 含子域、不认相似域名、不吞别家`() {
        assertEquals(DEEPSEEK_BALANCE_ENDPOINT, deepseekBalanceEndpoint("https://api.deepseek.com/anthropic"))
        assertEquals(DEEPSEEK_BALANCE_ENDPOINT, deepseekBalanceEndpoint("https://api.deepseek.com"))
        assertEquals(DEEPSEEK_BALANCE_ENDPOINT, deepseekBalanceEndpoint("deepseek.com"), "没写协议也认")
        assertNull(deepseekBalanceEndpoint(""))
        assertNull(deepseekBalanceEndpoint("https://api.deepseek.com.evil.com/v1"), "后缀像但不是")
        assertNull(deepseekBalanceEndpoint("https://mydeepseek.com/v1"), "前缀像但不是")
        assertNull(deepseekBalanceEndpoint("http://tokenhub.lihoogame.com:3000"), "转卖的没有这条账")
    }

    @Test
    fun `余额解析：金额是字符串、多币种取 CNY、赠金与充值各就各位`() {
        val json = """
        {"is_available":true,"balance_infos":[
          {"currency":"USD","total_balance":"20.50","granted_balance":"0.50","topped_up_balance":"20.00"},
          {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}
        """.trimIndent()
        val b = parseDeepseekBalance(json, LocalTime.of(20, 52))
        assertEquals("CNY", b.currency)
        assertEquals(110.0, b.total, 1e-9)
        assertEquals(10.0, b.granted, 1e-9)
        assertEquals(100.0, b.toppedUp, 1e-9)
        assertTrue(b.available)
        assertEquals(LocalTime.of(20, 52), b.fetchedAt)
    }

    @Test
    fun `余额解析：不可用与缺字段的宽容 —— 但一条币种都没有要抛`() {
        val b = parseDeepseekBalance("""{"is_available":false,"balance_infos":[{"currency":"CNY"}]}""")
        assertFalse(b.available)
        assertEquals(0.0, b.total, 1e-9, "缺的金额当零")
        assertThrows(UsageBillError::class.java) { parseDeepseekBalance("""{"is_available":true}""") }
        assertThrows(UsageBillError::class.java) { parseDeepseekBalance("""{"balance_infos":[]}""") }
    }

    @Test
    fun `余额解析：error 是对象形（401 实测），message 要抽出来`() {
        val e = assertThrows(UsageBillError::class.java) {
            parseDeepseekBalance(
                """{"error":{"message":"Authentication Fails","type":"authentication_error","code":"invalid_request_error"}}"""
            )
        }
        assertEquals("Authentication Fails", e.message)
    }

    // ------------------------------------------------------------------ 闸

    @Test
    fun `闸：60 秒窗口、飞行中不重入、force 只跳窗口不跳飞行`() {
        UsageBillGate.resetForTest()
        val t0 = 10_000_000L // 远离 0 —— 否则第一次调用会被 60 秒窗口拦掉
        try {
            assertTrue(UsageBillGate.tryStart(t0, force = false))
            assertFalse(UsageBillGate.tryStart(t0 + 1_000, force = false), "窗口内免进")
            assertFalse(UsageBillGate.tryStart(t0 + 1_000, force = true), "飞行中 force 也不重入")
            UsageBillGate.finish()
            assertFalse(UsageBillGate.tryStart(t0 + 30_000, force = false), "窗口还没过")
            assertTrue(UsageBillGate.tryStart(t0 + 30_000, force = true), "force 跳过时间窗")
            UsageBillGate.finish()
            assertFalse(UsageBillGate.tryStart(t0 + 89_999, force = false), "距上次尝试 59_999ms")
            assertTrue(UsageBillGate.tryStart(t0 + 90_000, force = false), "正好 60_000ms 可以进")
            UsageBillGate.finish()
        } finally {
            UsageBillGate.resetForTest()
        }
    }

    @Test
    fun `opencode 闸：窗口长度跟着各家走 —— 120 秒，不是 60`() {
        OpencodeQuotaGate.resetForTest()
        val t0 = 10_000_000L
        try {
            assertTrue(OpencodeQuotaGate.tryStart(t0, force = false))
            OpencodeQuotaGate.finish()
            assertFalse(OpencodeQuotaGate.tryStart(t0 + 119_999, force = false), "距上次 119_999ms")
            assertTrue(OpencodeQuotaGate.tryStart(t0 + 120_000, force = false), "正好 120_000ms 可以进")
            OpencodeQuotaGate.finish()
        } finally {
            OpencodeQuotaGate.resetForTest()
        }
    }

    @Test
    fun `deepseek 闸也是 120 秒窗 —— 与 opencode 同档`() {
        assertEquals(120_000L, DeepseekBalanceGate.intervalMs)
    }
}
