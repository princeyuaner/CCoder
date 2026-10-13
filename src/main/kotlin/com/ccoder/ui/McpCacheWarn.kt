package com.ccoder.ui

import com.ccoder.sidecar.McpServerStatus
import com.ccoder.text.CcoderText

/**
 * 「MCP 还没连完 · 现在发送要全价重算」这条提示的**判断** —— 纯逻辑、单测直打
 * （同 [McpSettleWatch] / `UsageRequestGate` 的规矩：判断不许长在 Swing 里）。
 *
 * ## 为什么有它（2026-10-13 实测，三重对账）
 *
 * 用户报「重启 IDE 后缓存命中失效了」。会话文件里 CLI 自己记的 `usage` 逐发看：
 *
 * | 时刻 | 全新输入 | 缓存读 |
 * |---|---|---|
 * | 13:57:39 | 386 | 105,472 |  （重启前最后一发，满命中）
 * | 13:58:33 | **78,536** | 8,704 |
 * | 13:58:37 | **92,545** | 16,128 |
 * | 13:58:39 | 253 | **108,928** | （第三发起恢复满命中）
 *
 * 中间那两发约 **17 万 token 按全价重算了一次**（网关账单与插件账本同数）。
 *
 * 根因不是「重启」本身：缓存命中要求请求前缀**逐字节相同**，而前缀里除了对话，
 * 还有 system prompt 与**工具表**（MCP 服务器的工具与说明）。SDK ≥0.3.293 的 MCP
 * 是**后台连**的（见 [McpSettleWatch] 头注的探针实测），重启后 CLI 刚起来的那几发
 * 正好落在「工具表还没稳定」的窗口里 —— 前两发因此都冷；表一稳定，第三发就满命中。
 *
 * 所以这条提示的语义是「**工具表此刻稳定**」，不是一次性开关：掉线重连同样会再破
 * （同一天本会话里 code-review-graph 就断而复连过一次）。
 *
 * 与「已恢复会话 · N 条历史」无关：那是显示层计数（sidecar 只读会话文件），
 * 模型看什么由 CLI 自己的 resume 决定，两者互不影响 —— 别把提示挂错地方。
 */

/** 状态带此刻该画什么。[cacheWarnStateOf] 算，`McpCacheWarnBar` 画。 */
internal sealed interface CacheWarnState {

    /**
     * 还有服务器在连。`names` 已按词表的分隔符连好（空名不进）。
     *
     * `names` **目前不进正文**：420px 栏宽下带名字一定被切掉尾巴（渲染探针
     * 2026-10-13 抓到过那一版）。名字留在状态里，是"连接中的是谁"本来就属于
     * 状态本身 —— 将来做 tooltip / 详情卡时用它，不必再回头改判定。
     */
    data class Pending(val names: String) : CacheWarnState

    /**
     * 全部落定 —— 绿的收尾（"刚才那件事办完了"）。
     *
     * 它**只在先前显示过时才有意义**，那个前提由组件自己记（[CacheWarnState] 本身
     * 无记忆）：凭空冒出一条绿的在屏幕上没人看得懂。
     */
    object Settled : CacheWarnState

    /** 有连不上 / 要认证的，且已经没人在 pending。 */
    data class Failed(val names: String) : CacheWarnState
}

/** CLI 那档"还在连"的线上字面量（词表映射在 `McpCardText.mcpStatusWordOf`）。 */
private const val PENDING = "pending"

private const val FAILED = "failed"

private const val NEEDS_AUTH = "needs-auth"

/**
 * 读数 → 该显示什么。
 *
 * 优先级：**先看有没有 pending** —— 还在连就意味着前缀随时还会变，此时报别的都是早报。
 * 没有 pending 再看失败。
 *
 * `disabled` 不参与：那是我们自己关掉的，它会一直躺在名单里，报它等于挂一条永远
 * 不消失（也永远不解决）的提示。认不出的 status 同样不参与 —— 同 `McpCardText`
 * 「状态取不到就不画」的规矩，不编一个状态出来。
 */
internal fun cacheWarnStateOf(servers: List<McpServerStatus>): CacheWarnState {
    val pending = servers.filter { it.status == PENDING }
    if (pending.isNotEmpty()) return CacheWarnState.Pending(namesOf(pending))

    val failed = servers.filter { it.status == FAILED || it.status == NEEDS_AUTH }
    if (failed.isNotEmpty()) return CacheWarnState.Failed(namesOf(failed))

    return CacheWarnState.Settled
}

/** 名字按词表的分隔符连（英文 `, `、中文 `、`，同 `mcpVarsLineOf`）。 */
private fun namesOf(servers: List<McpServerStatus>): String =
    servers.map { it.name }
        .filter { it.isNotEmpty() }
        .joinToString(CcoderText.text("common.listSeparator"))

/**
 * 值不值得为这一发开口。
 *
 * 用户 2026-10-13 选定「仅恢复 / 长会话」：新开的空会话第一发本来就只有一两万
 * token，为它弹提示是纯噪音；真正会被破掉的是**已经有历史**的会话。
 * 有东西在连（[state] 是 [CacheWarnState.Pending]）且两条判据任一成立即值得：
 *
 *  1. 本次会话是**恢复**出来的（重启/切存档那一路，实测那次就是它）；
 *  2. 上下文已经长过 [CACHE_WARN_TOKENS]。
 *
 * `usedTokens` 为 null（还没问到）按"不提醒"算 —— 不造零值这条本仓有旧例。
 */
internal fun cacheWarnWorthwhile(state: CacheWarnState, resumed: Boolean, usedTokens: Long?): Boolean =
    state is CacheWarnState.Pending && (resumed || (usedTokens ?: 0L) >= CACHE_WARN_TOKENS)

/**
 * 「长会话」的门槛（token）。
 *
 * 按 2026-10-13 实测的单价（约 ¥1/百万输入 token）：三万 token 重算约三分钱，
 * 再往下提醒就是噪音。选**量**而不是**钱**当门槛，是因为提示不该随模型单价
 * 忽有忽无（贵价模型上同样的量贵两个数量级，但"值不值得开口"是同一件事）。
 */
internal const val CACHE_WARN_TOKENS = 30_000L

/**
 * 这一发要不要拦下来问一句（D 的闸）。
 *
 * 四个前提缺一不可：有东西在连（[state]）、值当（[worth]）、用户没按过
 * 「本会话不再问」（[muted]）、也不是刚从确认条上按了「照发」（[bypass]）。
 * 抽成纯函数是为了让"什么时候该拦"能被单测钉住 —— 它同时是**唯一**会把用户
 * 的输入卡在手里不让走的判断，值当写清楚。
 */
internal fun shouldConfirmCacheWarn(
    state: CacheWarnState?,
    worth: Boolean,
    muted: Boolean,
    bypass: Boolean,
): Boolean = !bypass && !muted && worth && state is CacheWarnState.Pending
