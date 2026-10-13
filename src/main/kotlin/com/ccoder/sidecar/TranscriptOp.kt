package com.ccoder.sidecar

/**
 * 转写区中的一条消息项。
 *
 * 与 sidecar 协议无关 —— 那是 [SidecarMessage] 的职责。这里的类型是
 * Kotlin 与 React 之间的渲染契约，由 [com.ccoder.ui.TranscriptOpCodec] 序列化。
 */
sealed interface TranscriptItem {
    val id: String
    val ts: Long

    /**
     * 用户发的一条。[images] 是**给转写区看的**那份图（data URL，长边 ≤900 的 JPEG），
     * 不是发给模型的原图 —— 两者的用途不同：一个给人认，一个给模型读。
     * 空列表 = 纯文字那条路，与从前一字不差。
     */
    data class User(
        override val id: String,
        override val ts: Long,
        val text: String,
        val images: List<String> = emptyList(),
    ) : TranscriptItem

    data class Assistant(
        override val id: String,
        override val ts: Long,
        val text: String,
        /** 非空 = 这句是**子代理**说的，值是主线程那条 `Task` 的 `toolUseId`。 */
        val parent: String? = null,
    ) : TranscriptItem

    data class Thinking(
        override val id: String,
        override val ts: Long,
        val text: String,
        /** 同 [Assistant.parent]。 */
        val parent: String? = null,
    ) : TranscriptItem

    /**
     * 一次工具调用。
     *
     * [toolUseId] 是 SDK 的 `tool_use.id`（**不是**这一项的 [id]，那个是渲染用的
     * 消息号）。[ToolResult] 靠它与这次调用配对。
     *
     * **同一次调用会推两条**（2026-09-22 起）：起头帧那条 [input] 是空串
     * （卡片先出生，见 `startedToolCard`），完整 assistant 消息那条带着真参数。
     * 界面按 [toolUseId] **合并成一张卡**（`web/src/codec.ts` 的 `applyOps`），
     * 所以流水仍然是只追加，只是同一个 id 会出现两次。合并时保留**前一条**的
     * [id] / [ts] —— React 的 key 用的是 [id]，换掉等于重挂组件、秒表归零。
     */
    data class ToolUse(
        override val id: String,
        override val ts: Long,
        val toolUseId: String,
        val name: String,
        val input: String,
        /**
         * 非空 = 这一下是**子代理**跑的，值是主线程那条 `Task` 的 `toolUseId`；
         * 界面据此把这张卡收进那张 Task 卡里（A1 嵌套）。
         */
        val parent: String? = null,
    ) : TranscriptItem

    /**
     * 一次工具调用的输出。
     *
     * 与 [ToolUse] 是**两条独立的项**（在协议里它们本来就是两条消息），
     * 挂回哪张卡片由界面按 [toolUseId] 配对 —— 这样操作序列保持只追加，
     * 不需要"改一条已经推出去的项"这种操作。
     */
    data class ToolResult(
        override val id: String,
        override val ts: Long,
        val toolUseId: String,
        val text: String,
        val isError: Boolean,
    ) : TranscriptItem

    data class Error(override val id: String, override val ts: Long, val text: String) : TranscriptItem

    data class Result(
        override val id: String,
        override val ts: Long,
        val subtype: String,
        val costUsd: Double?,
        val durationMs: Long?,
        /** 本回合的 token 数（来自 `usage`，只含主循环）。null = 这条没有（旧记录 / 取不到）。 */
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val cacheReadTokens: Long? = null,
    ) : TranscriptItem

    data class SystemNote(override val id: String, override val ts: Long, val text: String) : TranscriptItem
}

/**
 * 推送给 React 的操作。
 *
 * [FinalizeDelta] 的存在是必需的：逐 token 增量与最终 assistant 消息是两条
 * 独立来源，两者都渲染会出现重复文本。以最终消息为准收尾。
 */
sealed interface TranscriptOp {
    data class Append(val item: TranscriptItem) : TranscriptOp

    /** [target]：`assistant` 是正文气泡，`thinking` 是收着的「思考中」块。 */
    data class AppendDelta(val target: String, val text: String) : TranscriptOp

    data class FinalizeDelta(val target: String, val text: String) : TranscriptOp

    data class ClearDelta(val target: String) : TranscriptOp

    data object Reset : TranscriptOp

    /**
     * **新会话就绪**：这条转写属于一条刚建立、还没有任何内容的会话。
     *
     * 从前这件事由一条「会话已就绪」的系统提示承担，而那条提示会永远留在
     * 时间线里（第一条消息上方一直挂着它）。换成标记之后，空态由网页侧的
     * 引导卡表达，内容一到卡自然消失，时间线里不留痕（2026-10-10，设计稿
     * `docs/design/empty-session.html` 方案乙）。
     *
     * 只在**新会话**（含 /clear 之后）推；恢复/切换会话走 Reset + 回放，
     * 绝不推它 —— 网页侧的空态判据靠这个区分"真新会话"与"回放真空期"。
     */
    data object Fresh : TranscriptOp

    /**
     * 文件快照的标记（2026-10-13，「本轮改动」卡）。
     *
     * 两条来源、同一个形状：**实时**每抓一份推一条（[SidecarMessage.TurnSnapshot]
     * 转来）；**恢复会话**时 ready 那一刻把最新一轮的全量推一次
     *（[SidecarMessage.SnapshotIndexReport]）。网页按 `toolUseId` 把标记贴到
     * 工具卡行上 —— 「新」（existed=false）与「无存档」（existed=null）都由它来。
     *
     * 它**不进时间线**：只进网页侧那份"本轮的标记表"，新一轮开始（一条新 user 项
     * 落地）时清空重记。
     */
    data class Snapshots(val entries: List<SnapshotEntry>) : TranscriptOp

    /**
     * 一次回滚的逐文件回执（同一件事的结果宣告）。
     *
     * 网页据此把行标成「已回滚」、把失败的行标上原因；旁边还会有一条
     * **系统提示**（那是给人读的句子）—— 两者分工：提示进时间线，这条进卡片。
     */
    data class RevertReport(val results: List<RevertResult>) : TranscriptOp
}
