package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 消息流中的一项。渲染层的输入，与 Swing 解耦以便测试。 */
sealed interface RenderItem {
    /**
     * 用户发出去的一条。[images] 是给转写区看的 data URL（见 [TranscriptItem.User]）——
     * 就绪前的暂存、排队后的补发都靠它把图一起带过去。
     */
    data class UserText(val text: String, val images: List<String> = emptyList()) : RenderItem
    data class AssistantText(val text: String) : RenderItem

    /** 逐 token 增量。面板把它累积到"进行中"的气泡里。 */
    data class AssistantDelta(val text: String) : RenderItem
    data class ThinkingDelta(val text: String) : RenderItem

    data class Thinking(val text: String) : RenderItem

    /**
     * 一次工具调用。
     *
     * [id] 是 SDK 给的 `tool_use.id`，[ToolResult] 靠它与这次调用配对 ——
     * 界面上"把输出挂回那张卡片"全指望它。
     */
    data class ToolUse(val name: String, val input: String, val id: String) : RenderItem

    /**
     * 工具调用**刚开始**（参数还在生成）。
     *
     * 它唯一的去处是状态卡那行「现在在做什么」（见 Activity.kt），**不进转写区** ——
     * 所以 `toOp` 给它 null。理由见 `renderStreamEvent` 里那段。
     */
    data class ToolStarting(val name: String) : RenderItem

    /**
     * 一次工具调用的结果。
     *
     * 结果是**另一条消息**（长的像 user 消息），所以这里单列一项，
     * 由界面按 [toolUseId] 挂回对应的 [ToolUse]。
     */
    data class ToolResult(
        val toolUseId: String,
        val text: String,
        val isError: Boolean,
    ) : RenderItem

    data class ErrorItem(val message: String) : RenderItem

    /**
     * 回合结束。token 与耗时都是**本回合**的（见 [renderResult] 的说明）；
     * [costUsd] 是**累计值** —— 界面那一行已经不显示它了，留着是给将来的成本面板
     * （2026-09-15 用户决定：累计值摆在回合下面会被读成"本次花费"）。
     */
    data class Result(
        val subtype: String,
        val costUsd: Double?,
        val durationMs: Long?,
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val cacheReadTokens: Long? = null,
    ) : RenderItem
    data class SystemNote(val text: String) : RenderItem
}

/**
 * 命令没有可见输出时 SDK 给的占位串（实测 `/clear` 就是这个）。
 *
 * 渲染成气泡会看着像 bug —— 一个写着 `(no content)` 的对话框，
 * 用户没法判断是命令出问题了还是插件坏了。
 */
internal const val NO_CONTENT_PLACEHOLDER = "(no content)"

/**
 * 命令回合里该被丢掉的气泡。
 *
 * **只有命令回合才丢。** 模型自己回一句空话是另一回事，那是它的话；
 * 而命令的空输出是 SDK 的占位，不是内容。
 *
 * 判据是"发出去的消息以 `/` 开头"，由调用方记住 —— 不能用
 * `result.local_command`，实测那个字段恒为 null（设计稿 §2 事实 3）。
 */
internal fun isEmptyCommandOutput(item: RenderItem): Boolean =
    item is RenderItem.AssistantText &&
        (item.text.isBlank() || item.text.trim() == NO_CONTENT_PLACEHOLDER)

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

    /**
     * 历史里的一条提问。正文 + 图。
     *
     * **只供回放路径调用。** live 路径下用户气泡是 `sendCurrentInput()` 直接
     * 推的，这里再产一次就会变成两条 —— 所以刻意不并进 [renderEvent]。
     *
     * 必须过滤工具结果：实测最大会话的 247 条 user 消息里，236 条是工具结果，
     * 真实提问只有 11 条。全渲染出来会把转写区淹掉。
     *
     * [images] 是**给转写区看的那份**（data URL，长边 ≤900 的 JPEG）：CLI 把图
     * 原尺寸存进 JSONL（实测见 `sidecar/tools/probe-history-image.mjs`），
     * 原样推给 JCEF 太重 —— 缩放口径与贴图那条路完全一致，用的是同一个
     * [transcriptDataUrl]。
     */
    internal data class HistoryPrompt(val text: String, val images: List<String>)

    internal fun renderPrompt(item: JsonObject): HistoryPrompt? {
        if (item.str("type") != "user") return null
        val content = item.obj("message")?.get("content") ?: return null

        // 形式一：纯文本提问
        if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            val text = content.asString.takeIf { it.isNotBlank() } ?: return null
            return HistoryPrompt(text, emptyList())
        }
        if (!content.isJsonArray) return null

        val blocks = content.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }

        // 含工具结果即判定为工具回合。真实提问不会和 tool_result 混在一条里，
        // 混着出现时那点文本是工具上下文而非用户输入
        if (blocks.any { it.str("type") == "tool_result" }) return null

        val text = blocks
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("\n")
            .trim()
        val images = blocks
            .filter { it.str("type") == "image" }
            .mapNotNull { historyImage(it) }

        // 一个字没有、图也没有 —— 不是提问（空的 text 块、认不出的块都落这里）
        if (text.isEmpty() && images.isEmpty()) return null
        return HistoryPrompt(text, images)
    }

    /**
     * 历史里的 image 块 → 转写区那份。认不出的（缺 source/data、base64 坏掉）
     * 返回 null：**一条坏图不该让整段历史回放不了**。
     */
    private fun historyImage(block: JsonObject): String? {
        val source = block.obj("source") ?: return null
        if (source.str("type") != "base64") return null
        val data = source.str("data") ?: return null
        val bytes = runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull() ?: return null
        return transcriptDataUrl(bytes).takeIf { it.isNotEmpty() }
    }

    private fun renderEvent(event: JsonObject): List<RenderItem> =
        when (event.str("type")) {
            "assistant" -> renderAssistant(event)
            "user" -> renderToolResults(event)
            "result" -> renderResult(event)
            "system" -> renderSystem(event)
            "stream_event" -> renderStreamEvent(event)
            else -> emptyList()
        }

    /**
     * 工具结果 —— 它们长得像 user 消息，其实是工具的输出。
     *
     * 只产 [RenderItem.ToolResult]，**绝不产用户气泡**：live 路径下用户气泡是
     * `sendCurrentInput()` 直接推的，这里再产一次就是一条消息画两遍。
     * 回放路径的 [renderPrompt] 同样把这类消息挡在门外，两条路一致。
     */
    private fun renderToolResults(event: JsonObject): List<RenderItem> {
        // 结果正文可能是数组（块）也可能是纯字符串（见 toolResultText），
        // 这里只要数组那一种；字符串形状的 user 消息不是工具结果
        val blocks = event.obj("message")?.arr("content") ?: return emptyList()

        val out = mutableListOf<RenderItem>()
        for (block in blocks) {
            if (!block.isJsonObject) continue
            val b = block.asJsonObject
            if (b.str("type") != "tool_result") continue

            // 没有 id 的结果挂不回任何一次调用，画出来是一条无主的输出
            val toolUseId = b.str("tool_use_id")?.takeIf { it.isNotBlank() } ?: continue

            out += RenderItem.ToolResult(
                toolUseId = toolUseId,
                text = toolResultText(b.get("content")),
                isError = b.bool("is_error") ?: false,
            )
        }
        return out
    }

    /**
     * 结果正文。SDK 给两种形状：纯字符串，或文本块数组（还可能夹着图片）。
     *
     * 一个文本块都没有时给一句占位，而不是空串 —— 空串到了界面上就是一块
     * 什么都没有的空白，用户分不清是"这次没有输出"还是"界面坏了"。
     */
    private fun toolResultText(content: JsonElement?): String {
        if (content == null) return ""
        if (content.isJsonPrimitive && content.asJsonPrimitive.isString) return content.asString

        val blocks = content.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject }?.map { it.asJsonObject } ?: return ""

        val text = blocks.asSequence()
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("\n")
        if (text.isNotEmpty()) return text

        // 有块但没文本（图片等）：占位；一个块都没有：空串 —— 那是真的没输出
        return if (blocks.isEmpty()) "" else "（非文本结果）"
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
                    // 缺 id 不丢弃这一项：调用本身该显示出来，只是结果挂不回来
                    id = b.str("id") ?: "",
                )
                // 其他块类型（redacted_thinking、server_tool_use 等）忽略
            }
        }
        return out
    }

    /**
     * 逐 token 增量。
     *
     * 载荷是 Messages API 的原始流事件（sdk.d.ts:5147），文本要下钻到
     * `event.delta.text`。只有 content_block_delta 带内容 ——
     * message_start / content_block_start / content_block_stop / message_stop
     * 都是无内容的边界帧，多产生渲染项会让 UI 出现空行。
     */
    private fun renderStreamEvent(event: JsonObject): List<RenderItem> {
        val inner = event.obj("event") ?: return emptyList()

        // 工具调用的**开头**。这时只知道工具名：参数还在逐字生成，而对 Write 来说
        // 参数就是整个文件内容 —— 几百行的话要十几秒。这一段转写区什么都没有可画
        // （见下面 input_json_delta 那条），于是屏幕上完全静止，用户的原话是
        // 「看起来像卡住了」。而能证明"它在动"的转圈与耗时都长在工具卡上，
        // 工具卡又要等参数生成完才出现 —— 指示器恰好缺席在最需要它的那一段。
        //
        // 这一项**不进转写区**（toOp 给它 null），只让状态卡立刻从「回复中」
        // 变成「编辑文件 / 运行指令」，至少回答"它还在动"。
        if (inner.str("type") == "content_block_start") {
            val block = inner.obj("content_block") ?: return emptyList()
            if (block.str("type") != "tool_use") return emptyList()
            val name = block.str("name")?.takeIf { it.isNotBlank() } ?: return emptyList()
            return listOf(RenderItem.ToolStarting(name))
        }

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

    /**
     * 回合结束那一行。
     *
     * **token 取 `usage`，不取 `modelUsage` / `total_cost_usd`**：后两者是**累计值**
     * （CLI 语义：每次 result 给的是"到目前为止的总和"，`/clear` 还会把它清零），
     * 摆在"这一回合下面"会被读成"本次消耗" —— 那是撒谎。而 `usage` 按回合给，
     * 代价是它**只含主循环**（不含子代理与压缩那几次调用），这一点写在设计稿里。
     */
    private fun renderResult(event: JsonObject): List<RenderItem> {
        val usage = event.obj("usage")
        return listOf(
            RenderItem.Result(
                subtype = event.str("subtype") ?: "unknown",
                costUsd = event.double("total_cost_usd"),
                durationMs = event.long("duration_ms"),
                inputTokens = usage?.long("input_tokens"),
                outputTokens = usage?.long("output_tokens"),
                cacheReadTokens = usage?.long("cache_read_input_tokens"),
            )
        )
    }

    private fun renderSystem(event: JsonObject): List<RenderItem> =
        when (event.str("subtype")) {
            // 实测这两个事件在每次会话中必现且无信息量（spec §11.2）
            "hook_started", "hook_response" -> emptyList()

            "init" -> {
                val sid = event.str("session_id")?.take(8) ?: "?"
                val model = event.str("model") ?: "?"
                listOf(RenderItem.SystemNote("会话 $sid · 模型 $model"))
            }

            // 压缩的回执（2026-09-17，spec §3.7）。**实时与恢复历史都从这一条路进来**：
            // 落进会话文件的那份用的是另一套字段名（camelCase），[compactReceiptOf]
            // 两种都认 —— 只认一种就是"现场有、历史里没有"的半边功能（spec 事实 12）
            "compact_boundary" ->
                listOfNotNull(compactReceiptOf(event)?.let { RenderItem.SystemNote(it) })

            else -> emptyList()
        }

    // ---- 容错取值 ----

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.double(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
}
