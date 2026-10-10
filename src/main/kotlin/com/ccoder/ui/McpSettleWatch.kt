package com.ccoder.ui

import com.ccoder.sidecar.McpServerStatus

/**
 * 「连接中」的续问表：读数里还有 [PENDING] 就隔一会儿再问一遍，落定（或问满）就停。
 *
 * ## 为什么需要它（2026-10-10 用户报）
 *
 * > 在mcp页面如果是连接中，即便他连接成功了状态也不会马上变，要重新打开页面才会变更状态
 *
 * 状态只在三个时刻被问：开会话、打开设置框、动作回执。而 SDK 从 0.3.293 起 MCP 是
 * **后台连**的：第一份读数多半是 `pending`，连上（或失败原因）晚一拍才到
 * （2026-10-09 探针重跑量的，见 `docs/superpowers/specs/2026-09-28-mcp-controls-design.md` §8）。
 * 页面开着的时候没人再问，于是"连接中"一直挂着 —— 直到用户把页面重开一次。
 *
 * ## 只跟 `pending` 这一档
 *
 * `connected` / `failed` / `needs-auth` / `disabled` 都是**落定**的，不会自己再变
 * （`needs-auth` 要用户去认证，`disabled` 是我们自己关的）。CLI 将来加新的过渡值时
 * 这里不认识就不跟：宁可少跟，不可空转。
 *
 * ## 封顶，以及"看的时候从头跟"
 *
 * 一场 pending 最多续问 [MAX_ASKS] 回（[INTERVAL_MS] × [MAX_ASKS] ≈ 一分钟）就安静：
 * 一个永远连不上的 server 不该让面板一直问下去。用户打开设置框、按了那两颗会话按钮，
 * 或新会话起来时 [reset] 会把牌还满 —— 他正看着的这一场从头跟起。
 *
 * 与 Swing 无关（同 [UsageRequestGate]）：判断本身要能被单测直接打。
 */
internal class McpSettleWatch {

    private var asks = 0

    /**
     * 收到一份读数之后问一句：**还要不要再问一次**。
     *
     * true = 消耗一张牌，调用方去排一次续问；读数已落定就把牌还满（下一场从头起算）。
     */
    fun nextAskWanted(servers: List<McpServerStatus>): Boolean {
        if (servers.none { it.status == PENDING }) {
            asks = 0
            return false
        }
        if (asks >= MAX_ASKS) return false
        asks++
        return true
    }

    /** 用户自己催过一次（重连/启用停用、打开设置、新会话）：牌还满。 */
    fun reset() {
        asks = 0
    }

    companion object {
        /** 续问的间隔。两秒：连上之后的下一跳就能看见，又不至于一秒一问。 */
        const val INTERVAL_MS = 2_000L

        /**
         * 一场 pending 最多续问的回数（30 回 ≈ 60 秒）。
         *
         * `npx` 冷启动（首次解析/下载包）是这条线上最慢的常见情形，几十秒都算正常；
         * 封顶只是防"永远连不上"，不是掐正常的慢启动。
         */
        const val MAX_ASKS = 30

        /** CLI 那档"还在连"的线上字面量（词表映射在 `McpCardText.mcpStatusWordOf`）。 */
        private const val PENDING = "pending"
    }
}
