package com.ccoder.sidecar

/** 转写区里的一张图。与 [ImageAttachment] 同形 —— 后者是插件内部模型，这个是对外契约。 */
data class TranscriptImage(val mediaType: String, val base64: String)

/**
 * 转写区中的一条消息项。
 *
 * 与 sidecar 协议无关 —— 那是 [SidecarMessage] 的职责。这里的类型是
 * Kotlin 与 React 之间的渲染契约，由 [com.ccoder.ui.TranscriptOpCodec] 序列化。
 */
sealed interface TranscriptItem {
    val id: String
    val ts: Long

    data class User(
        override val id: String,
        override val ts: Long,
        val text: String,
        val images: List<TranscriptImage> = emptyList(),
        /** 回放时因体积预算被省掉的张数（见 MessageRenderer 的 replayBudget）。 */
        val omittedImages: Int = 0,
    ) : TranscriptItem

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
