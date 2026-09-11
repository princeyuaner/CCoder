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

    data class ToolUse(
        override val id: String,
        override val ts: Long,
        val name: String,
        val input: String,
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

    /** [target] 当前只有 "assistant" 一个取值 —— 思考流的逐字渲染被刻意丢弃。 */
    data class AppendDelta(val target: String, val text: String) : TranscriptOp

    data class FinalizeDelta(val target: String, val text: String) : TranscriptOp

    data class ClearDelta(val target: String) : TranscriptOp

    data object Reset : TranscriptOp
}
