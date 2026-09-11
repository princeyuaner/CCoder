package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 消息流中的一项。渲染层的输入，与 Swing 解耦以便测试。 */
sealed interface RenderItem {
    data class UserText(val text: String) : RenderItem
    data class AssistantText(val text: String) : RenderItem

    /** 逐 token 增量。面板把它累积到"进行中"的气泡里。 */
    data class AssistantDelta(val text: String) : RenderItem
    data class ThinkingDelta(val text: String) : RenderItem

    data class Thinking(val text: String) : RenderItem
    data class ToolUse(val name: String, val input: String) : RenderItem
    data class ErrorItem(val message: String) : RenderItem
    data class Result(val subtype: String, val costUsd: Double?, val durationMs: Long?) : RenderItem
    data class SystemNote(val text: String) : RenderItem
}

/**
 * 把 sidecar 消息翻译为渲染项。
 *
 * 核心原则（spec §3.3）：**未知即忽略**。SDKMessage 是 40+ 成员的联合类型
 * 且会随版本增长，任何"只处理已知类型、其余报错"的写法都会在升级时炸掉。
 * 因此这里的 else 分支返回空列表，且所有字段访问都做类型校验。
 */
object MessageRenderer {

    fun render(msg: SidecarMessage): List<RenderItem> = when (msg) {
        is SidecarMessage.Event -> renderEvent(msg.event)
        // 权限由 PermissionCard 处理；ready/error/exit 由面板状态处理
        else -> emptyList()
    }

    private fun renderEvent(event: JsonObject): List<RenderItem> =
        when (event.str("type")) {
            "assistant" -> renderAssistant(event)
            "result" -> renderResult(event)
            "system" -> renderSystem(event)
            "stream_event" -> renderStreamEvent(event)
            else -> emptyList()
        }

    private fun renderAssistant(event: JsonObject): List<RenderItem> {
        val out = mutableListOf<RenderItem>()

        // error 字段必须最先检查：认证失败等情况下 content 里会有文本，
        // 但那是错误说明而非正常回复
        event.str("error")?.let { err ->
            val detail = event.obj("message")?.arr("content")
                ?.firstOrNull { it.isJsonObject && it.asJsonObject.str("type") == "text" }
                ?.asJsonObject?.str("text")
            out += RenderItem.ErrorItem(detail ?: err)
            return out
        }

        val content = event.obj("message")?.arr("content") ?: return out
        for (block in content) {
            if (!block.isJsonObject) continue
            val b = block.asJsonObject
            when (b.str("type")) {
                "text" -> b.str("text")?.takeIf { it.isNotBlank() }
                    ?.let { out += RenderItem.AssistantText(it) }

                "thinking" -> b.str("thinking")?.takeIf { it.isNotBlank() }
                    ?.let { out += RenderItem.Thinking(it) }

                "tool_use" -> out += RenderItem.ToolUse(
                    name = b.str("name") ?: "unknown",
                    input = b.get("input")?.toString() ?: "",
                )
                // 其他块类型（redacted_thinking、server_tool_use 等）忽略
            }
        }
        return out
    }

    /**
     * 逐 token 增量。
     *
     * 载荷是 Messages API 的原始流事件（sdk.d.ts:4901），文本要下钻到
     * `event.delta.text`。只有 content_block_delta 带内容 ——
     * message_start / content_block_start / content_block_stop / message_stop
     * 都是无内容的边界帧，多产生渲染项会让 UI 出现空行。
     */
    private fun renderStreamEvent(event: JsonObject): List<RenderItem> {
        val inner = event.obj("event") ?: return emptyList()
        if (inner.str("type") != "content_block_delta") return emptyList()
        val delta = inner.obj("delta") ?: return emptyList()

        return when (delta.str("type")) {
            "text_delta" -> delta.str("text")?.takeIf { it.isNotEmpty() }
                ?.let { listOf(RenderItem.AssistantDelta(it)) } ?: emptyList()

            "thinking_delta" -> delta.str("thinking")?.takeIf { it.isNotEmpty() }
                ?.let { listOf(RenderItem.ThinkingDelta(it)) } ?: emptyList()

            // input_json_delta 等工具参数的增量不显示
            else -> emptyList()
        }
    }

    private fun renderResult(event: JsonObject): List<RenderItem> = listOf(
        RenderItem.Result(
            subtype = event.str("subtype") ?: "unknown",
            costUsd = event.get("total_cost_usd")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble,
            durationMs = event.get("duration_ms")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong,
        )
    )

    private fun renderSystem(event: JsonObject): List<RenderItem> =
        when (event.str("subtype")) {
            // 实测这两个事件在每次会话中必现且无信息量（spec §11.2）
            "hook_started", "hook_response" -> emptyList()

            "init" -> {
                val sid = event.str("session_id")?.take(8) ?: "?"
                val model = event.str("model") ?: "?"
                listOf(RenderItem.SystemNote("会话 $sid · 模型 $model"))
            }

            else -> emptyList()
        }

    // ---- 容错取值 ----

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
