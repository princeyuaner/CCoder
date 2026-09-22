package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ccoder.text.CcoderText

/** 消息流中的一项。渲染层的输入，与 Swing 解耦以便测试。 */
sealed interface RenderItem {
    /**
     * 用户发出去的一条。[images] 是给转写区看的 data URL（见 [TranscriptItem.User]）——
     * 就绪前的暂存、排队后的补发都靠它把图一起带过去。
     */
    data class UserText(val text: String, val images: List<String> = emptyList()) : RenderItem
    data class AssistantText(val text: String, val parent: String? = null) : RenderItem

    /** 逐 token 增量。面板把它累积到"进行中"的气泡里。 */
    data class AssistantDelta(val text: String) : RenderItem
    data class ThinkingDelta(val text: String) : RenderItem

    data class Thinking(val text: String, val parent: String? = null) : RenderItem

    /**
     * 一次工具调用。
     *
     * [id] 是 SDK 给的 `tool_use.id`，[ToolResult] 靠它与这次调用配对 ——
     * 界面上"把输出挂回那张卡片"全指望它。
     *
     * **同一次调用会产出两条这个**：起头帧那条 [ToolStarting] 先画一张参数还是空串的卡
     * （2026-09-22 起），完整消息到了再画一条同 [id] 的 —— 界面按 id **合并成一张**
     * （见 web/src/codec.ts 的 applyOps）。不合并就是一次调用两张卡，而且结果按 id 配对，
     * 两张都会配上。
     *
     * [parent] 是**子代理归属**：非空时它的值就是主线程那条 `Task` 的 `tool_use.id`，
     * 界面据此把这一项收进那张卡里（A1，2026-09-18）。空 = 主线程自己跑的。
     *
     * 主线程消息上这个字段**在、值是 null**（实测，不是缺字段），所以 `.str()` 取到的
     * 就是 null —— 两种来源在这里长得一样。
     */
    data class ToolUse(
        val name: String,
        val input: String,
        val id: String,
        val parent: String? = null,
    ) : RenderItem

    /**
     * 工具调用**刚开始**（参数还在生成）。
     *
     * 从 2026-09-22 起它**会进转写区**：卡片在模型刚决定要用这个工具时就出生
     * （名字 + 转圈 + 秒表），参数生成完再由完整消息那条同 [id] 的项把它补全 ——
     * 见 [startedToolCard]。在此之前转写区要等完整消息，而"参数生成完"约等于
     * "工具也跑完了"：实测 Read 从卡片出生到结果**中位 21ms**，读取/搜索那类卡
     * 生下来就已经是完成态，用户的原话是「只有调用完成才会显示出来」。
     *
     * 它同时仍然是状态卡那行「现在在做什么」的来源（见 Activity.kt）。
     *
     * [id] 是起头帧里就带着的 `tool_use.id`。**空 = 画不了卡**（结果靠 id 配对，
     * 空 id 的卡会永远转圈），那种情况只喂状态卡，退回老路。
     *
     * [parent] 同 [ToolUse.parent]。实测子代理的流事件是 0 条，所以今天恒为 null；
     * 带着它只是防将来 —— 真发过来时卡片会直接生在 Task 卡里（见 Activity.subagentOf）。
     */
    data class ToolStarting(
        val name: String,
        val id: String = "",
        val parent: String? = null,
    ) : RenderItem

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
 * 起头帧要提前画的那张卡：**参数还是空串**的那条 [RenderItem.ToolUse]。
 *
 * 为什么要有它：一张卡原先只在完整 assistant 消息到达时才出生，而那时参数已经生成完、
 * 工具也基本跑完了 —— 实测 Read 从卡片出生到结果中位 21ms，读取/搜索那类卡生下来就是
 * 完成态，屏幕上永远看不到转圈（2026-09-22 用户报「只有调用完成才会显示出来」）。
 * 起头帧（`content_block_start[tool_use]`）里已经带着 id 与工具名，卡片从这里出生，
 * 参数到了由完整消息那条同 id 的项补全（界面按 id 合并）。
 *
 * **id 空 → null**：`ToolResult` 靠 `tool_use.id` 配对，空 id 的卡永远等不到自己的结果，
 * 只会一直转圈 —— 而且转得和"真的在跑"一模一样，用户没有任何办法分辨。那种情况退回
 * 老路：只喂状态卡（见 `Activity.kt`），等完整消息到了再画。
 */
internal fun startedToolCard(started: RenderItem.ToolStarting): RenderItem.ToolUse? =
    started.id.takeIf { it.isNotBlank() }?.let {
        RenderItem.ToolUse(
            name = started.name,
            // 空串 = 「参数还没拿到」，不是「这个工具没有参数」—— 界面上那张卡
            // 这时只有名字与转圈，标题行是空的（见 web/src/tools.ts）
            input = "",
            id = it,
            parent = started.parent,
        )
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

    /**
     * 历史里的一条提问。正文 + 图。
     *
     * **只供回放路径调用。** live 路径下用户气泡是 `sendCurrentInput()` 直接
     * 推的，这里再产一次就会变成两条 —— 所以刻意不并进 [renderEvent]。
     *
     * 必须过滤工具结果：实测最大会话的 247 条 user 消息里，236 条是工具结果，
     * 真实提问只有 11 条。全渲染出来会把转写区淹掉。
     *
     * **还必须过滤 CLI 的「信封」与压缩摘要**（2026-09-20，见计划文档
     * `docs/superpowers/plans/2026-09-20-resume-envelope.md`）：CLI 把本地命令
     * （`/compact`、`/clear`、`!` 的 bash …）的**回显与输出**、以及压缩摘要，都写成
     * 普通的 `type:"user"` 条目 —— 纯字符串、`isMeta` 不标，所以 SDK 也不滤它们。
     * 实时那条路到不了这里（[renderToolResults] 只认块数组，纯字符串的 user 事件
     * 一律丢），而这条路原先的判据是「内容是**非空字符串**就算提问」：于是恢复会话
     * 后转写区里躺着 `<command-name>/compact</command-name>` 这样的气泡，像是用户
     * 说的。判据照抄 CLI 自己的转写过滤器（claude.exe 内嵌 JS）：
     * `Dhn(e) = e.type==="user" && !e.isCompactSummary && !Z2r(e)`。
     *
     * [images] 是**给转写区看的那份**（data URL，长边 ≤900 的 JPEG）：CLI 把图
     * 原尺寸存进 JSONL（实测见 `sidecar/tools/probe-history-image.mjs`），
     * 原样推给 JCEF 太重 —— 缩放口径与贴图那条路完全一致，用的是同一个
     * [transcriptDataUrl]。
     */
    internal data class HistoryPrompt(val text: String, val images: List<String>)

    /**
     * CLI 的**本地命令信封**标签 —— 内容是这些开头的 user 条目不是谁说的话。
     *
     * 名单是 claude.exe 内嵌 JS 里三张表并起来的（`Q1e` 的 bash 四件套 +
     * `v1` 的 `record` / `output` / `caveat` 三档 + `YN` 那串 startsWith）：
     * 命令回显、命令输出、给模型的说明、`!` 的 bash、后台任务完成的通知。
     * `<command-args>` 不在名单里 —— 它只出现在 `<command-name>` 之后，做不了开头。
     *
     * **只认开头**（CLI 自己也是 `startsWith`）：用户真在消息里引用这些标签
     * （比如讨论这套协议）不该被当成信封吞掉。
     */
    private val ENVELOPE_TAGS = listOf(
        "<command-name>", "<command-message>",
        "<local-command-stdout>", "<local-command-stderr>", "<local-command-caveat>",
        "<bash-input>", "<bash-stdout>", "<bash-stderr>", "<bash-exit-code>",
        "<task-notification>",
    )

    internal fun isEnvelopeText(text: String): Boolean = ENVELOPE_TAGS.any { text.startsWith(it) }

    /**
     * 压缩摘要那条的**开头** —— CLI 自己生成的模板句，见 claude.exe 的 `Nae()`：
     *
     * ```
     * `This session is being continued from a previous conversation that ran out of
     *  context. The summary below covers the earlier portion of the conversation.`
     * ```
     *
     * **为什么认句子而不认标记**：`isCompactSummary` / `isVisibleInTranscriptOnly`
     * 在会话文件里是有的，但 SDK 的 `getSessionMessages` 会把条目**归一化**
     * （实测 196 条回来的字段完全一致：`message,parent_tool_use_id,session_id,
     * timestamp,type,uuid`，三个标记连同 `isMeta` 一起没了）—— 所以标记只能当兜底，
     * 真正拦住它的是这句话。CLI 哪天改了措辞，最坏结果是那条 15KB 的气泡又冒出来
     * （看得见，不是静默出错）。
     */
    private const val COMPACT_SUMMARY_PREFIX =
        "This session is being continued from a previous conversation"

    internal fun isCompactSummaryText(text: String): Boolean =
        text.startsWith(COMPACT_SUMMARY_PREFIX)

    internal fun renderPrompt(item: JsonObject): HistoryPrompt? {
        if (item.str("type") != "user") return null

        // 压缩摘要、以及"只在转写里可见"的条目，都不是谁说的话。CLI 自己也不画
        // 它们（见 Dhn 里的 isCompactSummary、JE 里的 isVisibleInTranscriptOnly）。
        // 这两条是**兜底**：当前 SDK 版本会把标记丢掉（见 [COMPACT_SUMMARY_PREFIX]），
        // 实际拦住摘要的是下面的句子判据。实测那条摘要有 15153 字，画成用户气泡
        // 等于凭空多出一大段"你说的话"
        if (item.bool("isCompactSummary") == true) return null
        if (item.bool("isVisibleInTranscriptOnly") == true) return null

        val content = item.obj("message")?.get("content") ?: return null

        // 形式一：纯文本提问
        if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            val text = content.asString.takeIf { it.isNotBlank() } ?: return null
            // 信封就在这个形状上：整条内容是一个纯字符串（实测）
            if (isEnvelopeText(text) || isCompactSummaryText(text)) return null
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
        // 信封/摘要也有可能以块的形式来（实测都是纯字符串，但形状不该决定行为）
        if (isEnvelopeText(text) || isCompactSummaryText(text)) return null
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
        return if (blocks.isEmpty()) "" else CcoderText.text("transcript.nonTextResult")
    }

    private fun renderAssistant(event: JsonObject): List<RenderItem> {
        val out = mutableListOf<RenderItem>()

        // 子代理归属：非空 = 这条消息是某个子代理说的/做的，值是主线程那条 Task 的
        // tool_use.id（实测形状见 docs/superpowers/specs/2026-09-18-subagent-nesting-design.md）。
        // 主线程的帧上这个键在、值是 null，所以两种情况在这儿都落到 null。
        val parent = event.str("parent_tool_use_id")

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
                    ?.let { out += RenderItem.AssistantText(it, parent) }

                "thinking" -> b.str("thinking")?.takeIf { it.isNotBlank() }
                    ?.let { out += RenderItem.Thinking(it, parent) }

                "tool_use" -> out += RenderItem.ToolUse(
                    name = b.str("name") ?: "unknown",
                    input = b.get("input")?.toString() ?: "",
                    // 缺 id 不丢弃这一项：调用本身该显示出来，只是结果挂不回来
                    id = b.str("id") ?: "",
                    parent = parent,
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
     * `event.delta.text`。带内容的是 content_block_delta 与**带 tool_use 的
     * content_block_start**（后者让工具卡提前出生，见 [startedToolCard]）——
     * message_start / content_block_stop / message_stop 是无内容的边界帧，
     * 多产生渲染项会让 UI 出现空行。
     */
    private fun renderStreamEvent(event: JsonObject): List<RenderItem> {
        val inner = event.obj("event") ?: return emptyList()

        // 工具调用的**开头**。这时只知道工具名与 id：参数还在逐字生成，而对 Write
        // 来说参数就是整个文件内容 —— 几百行的话要十几秒。这一段没有正文可画
        // （见下面 input_json_delta 那条），从前转写区是**完全静止**的，用户的原话是
        // 「看起来像卡住了」。
        //
        // 这一项有两个去处（2026-09-22 起）：
        // - **转写区**：卡片在这里出生，先只有名字 + 转圈 + 秒表（见 [startedToolCard]）。
        //   从前它要等完整消息，而那时参数已经生成完、工具也基本跑完 —— 读取/搜索那类
        //   卡生下来就是完成态，用户报的「只有调用完成才会显示出来」说的就是它。
        // - **状态卡**：立刻从「回复中」变成「编辑文件 / 运行指令」（见 Activity.kt）。
        //
        // id 在这帧里就带着（实测形状见 MessageRendererTest 那条用例）；万一没有，
        // `startedToolCard` 会给 null，只剩状态卡这一条路 —— 与改动前一样。
        if (inner.str("type") == "content_block_start") {
            val block = inner.obj("content_block") ?: return emptyList()
            if (block.str("type") != "tool_use") return emptyList()
            val name = block.str("name")?.takeIf { it.isNotBlank() } ?: return emptyList()
            return listOf(
                RenderItem.ToolStarting(
                    name = name,
                    id = block.str("id") ?: "",
                    // 子代理归属取**信封上**那个字段，不取 content_block 里的：
                    // 流事件的归属写在消息层（见 SDKPartialAssistantMessage 的形状）
                    parent = event.str("parent_tool_use_id"),
                )
            )
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
                listOf(RenderItem.SystemNote(CcoderText.text("chat.note.sessionModel", sid, model)))
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
