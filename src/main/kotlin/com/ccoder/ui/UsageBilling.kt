package com.ccoder.ui

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalTime
import java.util.Locale

/**
 * 供应商账单 —— 方案 A「对账层」（2026-10-12 用户从选型台选的）。
 *
 * 只做 DMXAPI：它的令牌日志站点自带一个公开 API（`GET tokenlog.dmxapi.cn/?key=sk-…`，
 * 免登录、返回 JSON，默认查当天）。口径上**实扣 ≠ 估算**：
 *
 * - 估算（本地账本 × API 单价）是美元、只算这台机器、离线可用；
 * - 实扣是这枚令牌在网关的账：**人民币**、算令牌下所有来源、含网关的缓存计价；
 * - 分不了「本项目」（接口没有项目字段）—— 界面上写死「本令牌」。
 *
 * 单位换算（额度 ÷ 500000 = 元）与错误形状都是从站点自己的 JS / 实测里抄的，
 * 见 spec `2026-10-12-usage-bill-design.md` §2。密钥不落盘、不进日志、不给 sidecar。
 */

/** 一次账单查询的结果（只保界面要用的那几个数）。 */
internal data class UsageBill(
    val provider: String,
    /** 今日实扣（元）——`stats.total_quota / 500000`。 */
    val yuanToday: Double,
    /** 账单口径的提示 tokens（含缓存）。 */
    val promptTokens: Long,
    /** 账单口径的补全 tokens。 */
    val completionTokens: Long,
    /** 令牌余额（元）；`token_unlimited` 为真时 null（界面显示「不限量」）。 */
    val balanceYuan: Double?,
    val fetchedAt: LocalTime,
)

/** 账单查询/解析失败。message 可能是站点给的中文（400/429 的形状）—— 进日志，不上界面。 */
internal class UsageBillError(message: String) : Exception(message)

/** DMXAPI 账单站点。 */
internal const val DMX_BILL_ENDPOINT = "https://tokenlog.dmxapi.cn/"

/** 界面上的供应商名（品牌，不翻译）。 */
internal const val DMX_PROVIDER_NAME = "DMXAPI"

/** 额度 → 元：站点自己的 JS 就这么除（500000 额度 = 1 元）。 */
internal const val QUOTA_PER_YUAN = 500_000.0

/**
 * baseUrl → 账单端点；不是 DMXAPI 就 null（界面就整条不出现 —— "退回今天的样子"）。
 *
 * 判据是**主机名**（含子域），不是字符串包含 —— `dmxapi.cn.evil.com` 那种不能认，
 * `notdmxapi.cn` 也不能（"dmxapi" 前面没有 `.`）。没写协议的也认（host 判定不靠它）。
 */
internal fun dmxBillEndpoint(baseUrl: String): String? {
    var s = baseUrl.trim()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "https://$s"
    val host = runCatching { URI(s).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
    return if (host == "dmxapi.cn" || host.endsWith(".dmxapi.cn")) DMX_BILL_ENDPOINT else null
}

/** 查当天的 URL（不传时间参数 = 站点自己的「当天」）。key 转义 —— query 里它是个普通参数。 */
internal fun dmxBillUrl(endpoint: String, key: String): String =
    endpoint + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)

/** 从任意响应体里抽 `error` 字段 —— 错误形状的唯一出处。没有（或不是 JSON）给 null。 */
internal fun errorMessageOf(body: String): String? = runCatching {
    (JsonParser.parseString(body) as? JsonObject)
        ?.get("error")
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
}.getOrNull()

/**
 * 解析站点 GET 的 JSON。`error` 优先抛（400/429/500 的形状，实测过 400）；
 * 其余字段能缺就缺、缺了当零 —— 一个字段改口不该让整条对账消失。
 */
internal fun parseDmxBill(json: String, at: LocalTime = LocalTime.now()): UsageBill {
    val root = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject
        ?: throw UsageBillError("账单返回不是 JSON 对象")
    errorMessageOf(json)?.let { throw UsageBillError(it) }
    val stats = root.getAsJsonObject("stats") ?: JsonObject()
    val unlimited = root.get("token_unlimited")?.let { it.isJsonPrimitive && it.asBoolean } ?: false
    return UsageBill(
        provider = DMX_PROVIDER_NAME,
        yuanToday = doubleOf(stats, "total_quota") / QUOTA_PER_YUAN,
        promptTokens = longOf(stats, "total_prompt"),
        completionTokens = longOf(stats, "total_completion"),
        balanceYuan = if (unlimited) null else doubleOf(root, "token_balance") / QUOTA_PER_YUAN,
        fetchedAt = at,
    )
}

/** 数字字段的宽容读取（缺、null、类型不对都给默认）。 */
private fun doubleOf(o: JsonObject, name: String): Double =
    o.get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() } ?: 0.0

private fun longOf(o: JsonObject, name: String): Long =
    o.get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L

/**
 * HTTP GET。**在池化线程上调**（同步 send）。
 * 非 200：先试 `error` 字段（站点在 400/429 时也给 JSON），拿不到就报状态码。
 */
internal fun fetchDmxBill(url: String, timeoutMs: Long = 10_000L): String {
    val req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Accept", "application/json")
        .GET()
        .build()
    val resp = billHttp.send(req, HttpResponse.BodyHandlers.ofString())
    val body = resp.body().orEmpty()
    if (resp.statusCode() != 200) {
        throw UsageBillError(errorMessageOf(body) ?: "HTTP ${resp.statusCode()}")
    }
    return body
}

private val billHttp: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

/**
 * 应用级节流闸：60 秒窗口 + 飞行中不重入；`force`（统计框上的 ↻）只跳过时间窗。
 *
 * **为什么是全局的**：限速是 **per-IP**（10 次/分钟）—— 闸放在每个面板里的话，
 * 多标签 / 多窗口各查各的，叠起来就能把限速打爆。账单又是 token 级数据（与标签、
 * 项目都无关），全局一份闸在语义上正好。
 */
internal object UsageBillGate {

    /** 两次**非强制**查询之间至少隔这么久。 */
    const val INTERVAL_MS = 60_000L

    private var lastAttemptMs = 0L
    private var inFlight = false

    /** 可以开始一次查询吗？可以的话就地占位（时间窗与飞行位一起占）。 */
    @Synchronized
    fun tryStart(nowMs: Long, force: Boolean): Boolean {
        if (inFlight) return false
        if (!force && nowMs - lastAttemptMs < INTERVAL_MS) return false
        lastAttemptMs = nowMs
        inFlight = true
        return true
    }

    /** 查询结束（无论成败）—— 让下一次可以进。 */
    @Synchronized
    fun finish() {
        inFlight = false
    }

    /** 用例用：复位。 */
    @Synchronized
    internal fun resetForTest() {
        lastAttemptMs = 0L
        inFlight = false
    }
}
