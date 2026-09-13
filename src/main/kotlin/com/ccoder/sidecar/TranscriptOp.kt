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

    data class User(override val id: String, override val ts: Long, val text: String) : TranscriptItem

    data class Assistant(override val id: String, override val ts: Long, val text: String) : TranscriptItem

    data class Thinking(override val id: String, override val ts: Long, val text: String) : TranscriptItem

    /**
     * 一次工具调用。
     *
     * [toolUseId] 是 SDK 的 `tool_use.id`（**不是**这一项的 [id]，那个是渲染用的
     * 消息号）。[ToolResult] 靠它与这次调用配对。
     */
    data class ToolUse(
        override val id: String,
        override val ts: Long,
        val toolUseId: String,
        val name: String,
        val input: String,
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
}
