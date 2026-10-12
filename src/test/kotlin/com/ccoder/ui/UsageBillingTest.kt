package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * 供应商账单（方案 A）的数据层 —— 纯函数直接打；网络那条只活在 [fetchDmxBill] 里，
 * 用例一律不碰网。
 *
 * 口径钉死在这里：**额度 ÷ 500000 = 元**（站点自己的算法）、错误形状
 * `{"error": …}`（实测过的 400）、识别按**主机名**（相似域名不许认）、
 * 闸是 60 秒窗口 + 飞行中不重入（force 只跳窗口）。
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
}
