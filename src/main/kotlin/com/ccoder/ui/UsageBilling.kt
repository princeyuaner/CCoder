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
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Locale

/**
 * 供应商账目 —— 方案 A「对账层」（2026-10-12 用户从选型台选的；2026-10-13 加 opencode）。
 *
 * 三个供应商各一条路：**DMXAPI** 的令牌日志站点自带一个公开 API（`GET tokenlog.dmxapi.cn/?key=sk-…`，
 * 免登录、返回 JSON，默认查当天），给人民币实扣；**opencode Go** 走官方额度接口（Bearer 密钥），
 * 给三档窗口的已用百分比；**DeepSeek** 走官方余额接口（Bearer），给账户余额（2026-10-13 加）。
 * 口径上**实扣 ≠ 估算**：
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

/**
 * 从任意响应体里抽 `error` 字段 —— 错误形状的唯一出处。没有（或不是 JSON）给 null。
 * 两种形状都认：DMX 的字符串 `{"error":"…"}`（实测 400）与 DeepSeek 的对象
 * `{"error":{"message":"…","type":…}}`（2026-10-13 无效密钥实测 401）。
 */
internal fun errorMessageOf(body: String): String? = runCatching {
    val err = (JsonParser.parseString(body) as? JsonObject)?.get("error") ?: return@runCatching null
    when {
        err.isJsonPrimitive -> err.asString
        err.isJsonObject -> err.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        else -> null
    }
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

// ------------------------------------------------------------------ opencode Go 额度

/**
 * opencode Go 的额度窗口（2026-10-13 加的第二个供应商）。
 *
 * 与 DMXAPI 账单同属「对账层」，口径不同：DMX 给**人民币实扣**；opencode 给三档窗口的
 * **已用百分比**（官方接口只有 percent —— 界面上也就没有金额可写）。
 *
 * 接口（2026-10-13 本机实测）：`GET https://opencode.ai/zen/go/v1/usage`，
 * `Authorization: Bearer <密钥>`，返回：
 * `{"usage":{"rolling":{status,percent,resetsAt},"weekly":{…},"monthly":{…}}}`
 * - rolling = 5 小时滚动窗；weekly = 7 天窗（周一 00:00 UTC 重置）；
 * - monthly = 订阅周期窗（跟着付费周期走）；
 * - status 为 `rate-limited` 时那一档已打满（percent 未必再更新，按 100 处理）。
 *
 * 实测两条（别再踩）：java.net.http 的**默认 UA 就能过** Cloudflare；
 * python-urllib 的默认签名会被 1010 拒。Zen 按量付费账号**没有**公开余额接口
 * （官方 issue #44189），那种账号这里会静默失败 —— 不伪造数据。
 */
internal const val OPENCODE_USAGE_ENDPOINT = "https://opencode.ai/zen/go/v1/usage"

/** 界面上的供应商名（品牌，不翻译）。 */
internal const val OPENCODE_PROVIDER_NAME = "opencode Go"

/** 额度窗口的三档。固定顺序就是界面顺序：5 小时 / 周 / 月。 */
internal enum class QuotaWindowKind { Rolling, Weekly, Monthly }

/** 一档窗口的读数。[percent] 是**已用百分比**（口径同 Claude Code 的 /usage）。 */
internal data class QuotaWindow(
    val kind: QuotaWindowKind,
    val percent: Double,
    /** 这一档已打满（原 status=rate-limited）；[percent] 已按 100 下限抬过。 */
    val rateLimited: Boolean,
    /** 重置时刻；站点没给 / 认不出是 null（那就不写"重置"）。 */
    val resetsAt: Instant?,
)

/** 一次额度查询的结果：固定顺序、缺档不入表（缺 ≠ 0%）。 */
internal data class OpencodeQuota(
    val windows: List<QuotaWindow>,
    val fetchedAt: LocalTime,
)

/**
 * baseUrl → 额度端点；不是 opencode.ai（含子域）就 null（"退回今天的样子"）。
 * 判据同 [dmxBillEndpoint]：URI 的 host 精确 / 点边界后缀，字符串包含不算数。
 * Go 订阅与 Zen 按量共用一个主机名 —— 这里只认 Go 的接口；Zen 账号查询会失败，
 * 静默消失（不伪造余额）。
 */
internal fun opencodeQuotaEndpoint(baseUrl: String): String? {
    var s = baseUrl.trim()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "https://$s"
    val host = runCatching { URI(s).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
    return if (host == "opencode.ai" || host.endsWith(".opencode.ai")) OPENCODE_USAGE_ENDPOINT else null
}

/**
 * 解析额度 JSON。`error` 优先抛；**单档缺字段跳过那一档**（不拿 0 糊弄 ——
 * 0% 会被读成"这档没用过"，那是假话）；整个 `usage` 缺了才算错。
 */
internal fun parseOpencodeQuota(json: String, at: LocalTime = LocalTime.now()): OpencodeQuota {
    val root = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject
        ?: throw UsageBillError("额度返回不是 JSON 对象")
    errorMessageOf(json)?.let { throw UsageBillError(it) }
    val usage = root.getAsJsonObject("usage") ?: throw UsageBillError("额度返回没有 usage 字段")
    val order = listOf(
        QuotaWindowKind.Rolling to "rolling",
        QuotaWindowKind.Weekly to "weekly",
        QuotaWindowKind.Monthly to "monthly",
    )
    val windows = order.mapNotNull { (kind, name) ->
        val w = usage.getAsJsonObject(name) ?: return@mapNotNull null
        val raw = w.get("percent")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asDouble }.getOrNull() } ?: return@mapNotNull null
        val limited = stringOf(w, "status") == "rate-limited"
        QuotaWindow(
            kind = kind,
            // 限流 = 打满：percent 有时停在打满前一格，抬到 100 才与"已限流"自洽
            percent = if (limited) maxOf(raw, 100.0) else raw,
            rateLimited = limited,
            resetsAt = stringOf(w, "resetsAt")?.let(::parseInstantOrNull),
        )
    }
    // 一档都没解析出来 = 形状不对（不是"用户没用过"）—— 当失败、静默消失；界面层
    // 由此可以放心假设"有额度就至少有一档"
    if (windows.isEmpty()) throw UsageBillError("额度返回里一档窗口都没有")
    return OpencodeQuota(windows, at)
}

/** 字符串字段的宽容读取（缺、null、非字符串都给 null）。 */
private fun stringOf(o: JsonObject, name: String): String? =
    o.get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

/** ISO-8601（`…Z` 或带偏移）→ Instant；认不出给 null（那一格不写重置时间）。 */
private fun parseInstantOrNull(s: String): Instant? =
    runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

/**
 * HTTP GET（Bearer 密钥）。**在池化线程上调**（同 [fetchDmxBill]）。非 200：先试 `error` 字段。
 * **别给这个请求换 UA / 换 HTTP 库**：本机实测 java.net.http 的默认签名能过 Cloudflare，
 * python-urllib 那类签名会被 1010 拒（见本节头注）。
 */
internal fun fetchOpencodeUsage(url: String, key: String, timeoutMs: Long = 10_000L): String {
    val req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $key")
        .GET()
        .build()
    val resp = billHttp.send(req, HttpResponse.BodyHandlers.ofString())
    val body = resp.body().orEmpty()
    if (resp.statusCode() != 200) {
        throw UsageBillError(errorMessageOf(body) ?: "HTTP ${resp.statusCode()}")
    }
    return body
}

// ------------------------------------------------------------------ DeepSeek 余额

/**
 * DeepSeek 官方账号余额（2026-10-13 加的第三个供应商）。
 *
 * 接口（官方文档 + 无效密钥实测错误形状）：`GET https://api.deepseek.com/user/balance`，
 * `Authorization: Bearer <密钥>`，返回：
 * `{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00",
 *   "granted_balance":"10.00","topped_up_balance":"100.00"}]}`
 *
 * 三条口径（别再搞混）：
 * - 金额是**字符串**（两位小数），不是数字；
 * - 可能**多币种**（CNY/USD 各一条）—— 取 CNY 优先，没有才拿第一条；
 * - 官方**没有**按时间窗口的用量接口，只有余额 —— 与 opencode 的"窗口"不是一回事、
 *   与 DMX 的"实扣"也不是一回事：这是"账户里还剩多少钱"。
 */
internal const val DEEPSEEK_BALANCE_ENDPOINT = "https://api.deepseek.com/user/balance"

/** 界面上的供应商名（品牌，不翻译）。 */
internal const val DEEPSEEK_PROVIDER_NAME = "DeepSeek"

/** 一次余额查询的结果。[available] = is_available（余额是否够调用）。 */
internal data class DeepseekBalance(
    val currency: String,
    /** 总可用余额（含赠金）。 */
    val total: Double,
    /** 未过期的赠金余额。 */
    val granted: Double,
    /** 充值余额。 */
    val toppedUp: Double,
    val available: Boolean,
    val fetchedAt: LocalTime,
)

/**
 * baseUrl → 余额端点；不是 deepseek.com（含子域）就 null（"退回今天的样子"）。
 * 判据同 [dmxBillEndpoint]：URI 的 host 精确 / 点边界后缀，字符串包含不算数。
 * 只认官方主机；转卖 deepseek 的网关（tokenhub 之类）没有这条账。
 */
internal fun deepseekBalanceEndpoint(baseUrl: String): String? {
    var s = baseUrl.trim()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "https://$s"
    val host = runCatching { URI(s).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
    return if (host == "deepseek.com" || host.endsWith(".deepseek.com")) DEEPSEEK_BALANCE_ENDPOINT else null
}

/**
 * 解析余额 JSON。`error` 优先抛（对象形的 `message` 也认，见 [errorMessageOf]）；
 * **一条币种都没有才算错**；金额字段能缺就缺、缺了当零（同 DMX 的宽容）。
 */
internal fun parseDeepseekBalance(json: String, at: LocalTime = LocalTime.now()): DeepseekBalance {
    val root = runCatching { JsonParser.parseString(json) }.getOrNull() as? JsonObject
        ?: throw UsageBillError("余额返回不是 JSON 对象")
    errorMessageOf(json)?.let { throw UsageBillError(it) }
    val infos = root.getAsJsonArray("balance_infos")
        ?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
    if (infos.isEmpty()) throw UsageBillError("余额返回里没有 balance_infos")
    // CNY 优先：多币种时用户天天看的是人民币那条；没有才拿第一条
    val entry = infos.firstOrNull { stringOf(it, "currency") == "CNY" } ?: infos[0]
    return DeepseekBalance(
        currency = stringOf(entry, "currency") ?: "",
        total = amountOf(entry, "total_balance"),
        granted = amountOf(entry, "granted_balance"),
        toppedUp = amountOf(entry, "topped_up_balance"),
        // is_available 缺了当"可用" —— 别为了一个缺字段把条染红
        available = root.get("is_available")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asBoolean }.getOrNull() } ?: true,
        fetchedAt = at,
    )
}

/** 金额字段（站点给的是字符串，防御式地也收数字）→ Double；缺 / 读不动给 0。 */
private fun amountOf(o: JsonObject, name: String): Double =
    o.get(name)?.takeIf { it.isJsonPrimitive }
        ?.let { runCatching { it.asString.toDouble() }.getOrNull() } ?: 0.0

/**
 * HTTP GET（Bearer 密钥）。**在池化线程上调**（同前两家）。非 200：先试 error 字段
 * —— DeepSeek 的错是**对象形**（401 实测 `{"error":{"message":…}}`）。
 */
internal fun fetchDeepseekBalance(url: String, key: String, timeoutMs: Long = 10_000L): String {
    val req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $key")
        .GET()
        .build()
    val resp = billHttp.send(req, HttpResponse.BodyHandlers.ofString())
    val body = resp.body().orEmpty()
    if (resp.statusCode() != 200) {
        throw UsageBillError(errorMessageOf(body) ?: "HTTP ${resp.statusCode()}")
    }
    return body
}

// ------------------------------------------------------------------ 刷新闸

/**
 * 对账层的**刷新闸**：时间窗 + 飞行中不重入；`force`（统计框上的 ↻）只跳过时间窗。
 *
 * **为什么是应用级全局**：DMX 限速 10 次/分钟（per-IP）、opencode 建议别高于每几分钟一次
 * —— 闸放在每个面板里的话，多标签 / 多窗口各查各的，叠起来就能超额。账目又是令牌 /
 * 账号级数据（与标签、项目都无关），全局一份在语义上正好。每个供应商**各一个实例**：
 * 限速是各家自己的，窗口长度也跟着各家走。
 */
internal class RefreshGate(val intervalMs: Long) {

    private var lastAttemptMs = 0L
    private var inFlight = false

    /** 可以开始一次查询吗？可以的话就地占位（时间窗与飞行位一起占）。 */
    @Synchronized
    fun tryStart(nowMs: Long, force: Boolean): Boolean {
        if (inFlight) return false
        if (!force && nowMs - lastAttemptMs < intervalMs) return false
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

/** DMXAPI：站点限速 10 次/分钟（per-IP）—— 60 秒窗。 */
internal val UsageBillGate = RefreshGate(intervalMs = 60_000L)

/** opencode Go：第三方插件的建议是别高于每几分钟一次 —— 120 秒窗。 */
internal val OpencodeQuotaGate = RefreshGate(intervalMs = 120_000L)

/** DeepSeek：余额变得慢 —— 120 秒窗与 opencode 同档（统一点；换配置时仍是强制查）。 */
internal val DeepseekBalanceGate = RefreshGate(intervalMs = 120_000L)
