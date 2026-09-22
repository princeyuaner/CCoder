package com.ccoder.ui

import com.ccoder.sidecar.ContextDetail
import com.ccoder.sidecar.ContextOverLimit
import com.ccoder.sidecar.ContextRow
import com.ccoder.text.CcoderText

/**
 * 上下文明细的**显示层模型** —— 全是纯函数，用例直接打（对话框那一层只负责摆）。
 *
 * 分三件事，各有各的规矩：
 *
 * 1. **判类**：只认 [ContextRow.kind]（SDK 原话 "Classify on this, never on the
 *    English name"）。认不出的按 [ContextKind.Used] —— 它是窗口里的内容，不是保留位；
 *    真来了第四种类别，界面上至少不会少画一段。
 * 2. **求和**：`deferred`（窗口外的工具 schema）**不计入用量**，它只回答"我有几个 MCP
 *    工具、一共多少 token"。自由空间也不进堆叠条 —— 它填剩下的那截。
 * 3. **文案**：CLI 给的是英文展示串（`Messages` / `System prompt`…）。**认识的行给键、
 *    不认识的原样透传**（见 spec §5 的折中）—— 全映射会在 CLI 改词时静默少一行，
 *    全英文会让中文界面恒定夹英文。
 */

/** 一行属于哪一类。四档来自 SDK 的 `kind`。 */
internal enum class ContextKind { Used, Free, Buffer, Deferred }

/** 界面上一行（分类行或清单里的条目）。[label] 是**已经翻过**的字。 */
internal data class ContextLine(
    val label: String,
    val sub: String,
    val tokens: Long,
    val kind: ContextKind,
)

/** 四张清单。标题是**我们自己的词**（不是 CLI 的），所以直接给键，不走折中。 */
internal enum class ContextListKind(val key: String, val titleKey: String) {
    McpTools("mcpTools", "context.list.mcpTools"),
    MemoryFiles("memoryFiles", "context.list.memoryFiles"),
    Agents("agents", "context.list.agents"),
    Skills("skills", "context.list.skills"),
}

/** 一张清单：有内容的才有 title / rows，空的整个不进列表（页签同理）。 */
internal data class ContextList(
    val kind: ContextListKind,
    val title: String,
    val rows: List<ContextLine>,
)

/** 分类行名 → 我们的键。**认得才翻**。 */
private val CATEGORY_KEYS = mapOf(
    "Messages" to "context.row.messages",
    "System prompt" to "context.row.systemPrompt",
    "MCP tools" to "context.row.mcpTools",
    "MCP tools (deferred)" to "context.row.mcpToolsDeferred",
    "Memory files" to "context.row.memoryFiles",
    "Agents" to "context.row.agents",
    "Skills" to "context.row.skills",
    "Free space" to "context.row.freeSpace",
    // 缓冲那一行的英文名**两个都收**：SDK 的注释举的是 Autocompact buffer，
    // 而它自己的字段说明里写的是 Compaction reserve —— 谁也没实测过，
    // 收两个不花钱，认不出时那一行会显示英文（看得见，不静默）
    "Autocompact buffer" to "context.row.autocompactBuffer",
    "Compaction reserve" to "context.row.autocompactBuffer",
)

/**
 * 这一列的全部键 —— 给用例用（"映射表里每个键都得在词表里"，见 `ContextDetailTest`）。
 *
 * 存在的理由：`TextKeysTest` 那个方向只管 `CcoderText.text("…")` 的字面调用点，
 * 而这张表里的键**不是调用点**（它们是 map 的值）。没有这一条，漏写一个词表条目
 * 就会在界面上显示成 `context.row.freeSpace` 而没人拦得住。
 */
internal val CONTEXT_ROW_KEYS: Set<String> = CATEGORY_KEYS.values.toSet()

/** CLI 的行名 → 界面上那一行字。认不出的**原样给英文**。 */
internal fun contextRowLabel(raw: String): String =
    CATEGORY_KEYS[raw]?.let { CcoderText.text(it) } ?: raw

/** `kind` 串 → 类别。认不出的当内容（见文件头第 1 条）。 */
internal fun contextKindOf(raw: String): ContextKind = when (raw) {
    "free" -> ContextKind.Free
    "buffer" -> ContextKind.Buffer
    "deferred" -> ContextKind.Deferred
    else -> ContextKind.Used
}

/**
 * 一行的 token 数要不要显示。
 *
 * SDK 明说分类行**可能带 0**，"renderers typically hide those" —— 一行"技能 0"
 * 是噪音，而它占掉的高度是实打实的。四张清单同理。
 */
private fun ContextRow.visible(): Boolean = tokens > 0

/** 原始行 → 显示行。零 token 的丢掉（见 [visible]）。 */
internal fun contextLinesOf(rows: List<ContextRow>): List<ContextLine> =
    rows.filter { it.visible() }.map {
        ContextLine(
            label = contextRowLabel(it.label),
            sub = it.sub,
            tokens = it.tokens,
            kind = contextKindOf(it.kind),
        )
    }

/** `used` 各段之和 —— 与 SDK 的 `total_tokens` 未必完全相等，两个数各按各的显示。 */
internal fun contextUsedTokensOf(lines: List<ContextLine>): Long =
    lines.filter { it.kind == ContextKind.Used }.sumOf { it.tokens }

/**
 * 堆叠条要画的那几段：内容 + 压缩预留，**按原顺序**。
 *
 * 自由空间与窗口外的行都不在里面：前者是"剩下的那截"（条自己会填），
 * 后者压根不占窗口。
 */
internal fun contextBarOf(lines: List<ContextLine>): List<ContextLine> =
    lines.filter { it.kind == ContextKind.Used || it.kind == ContextKind.Buffer }

/** 四张清单 —— 空的**不给页签**：点进去看见空白，比看不见那个页签更糟。 */
internal fun contextListsOf(detail: ContextDetail): List<ContextList> {
    val raw = mapOf(
        ContextListKind.McpTools to detail.mcpTools,
        ContextListKind.MemoryFiles to detail.memoryFiles,
        ContextListKind.Agents to detail.agents,
        ContextListKind.Skills to detail.skills,
    )
    return ContextListKind.entries.mapNotNull { kind ->
        val lines = contextLinesOf(raw.getValue(kind))
        if (lines.isEmpty()) null
        else ContextList(kind, CcoderText.text(kind.titleKey), lines)
    }
}

/** 一张清单的合计（页签上那个数）。 */
internal fun contextListTokensOf(list: ContextList): Long = list.rows.sumOf { it.tokens }

/**
 * 超窗那一行。**两种 kind 措辞不同**：硬顶是"再问会被 API 拒绝"，压缩窗是
 * "还没到上限，但会自动压缩" —— 今天那张卡只会变红，说不清是哪种。
 *
 * 认不出的 kind 给 null：宁可不说，也不要说错。
 */
internal fun contextOverLimitText(overLimit: ContextOverLimit?): String? = when (overLimit?.kind) {
    ContextOverLimit.HARD_LIMIT ->
        CcoderText.text("context.overLimit.hard", formatTokenCount(overLimit.tokensOver))

    ContextOverLimit.COMPACTION_WINDOW ->
        CcoderText.text("context.overLimit.compaction", formatTokenCount(overLimit.tokensOver))

    else -> null
}

/**
 * 「复制明细」复制的那份 Markdown。
 *
 * 形状与设计稿里那张表同构（分类在前、清单在后），给"要贴给别人看"用 ——
 * 所以它是**自足的**：标题行带上模型与百分比，离开这个框也读得懂。
 */
internal fun contextDetailMarkdownOf(detail: ContextDetail, usage: ContextUsage): String {
    val sb = StringBuilder()
    val percent = contextPercentOf(usage)
    val head = formatTokenCount(usage.usedTokens) + " / " + formatTokenCount(usage.windowTokens)
    sb.append(CcoderText.text("context.title"))
    sb.append(" · ").append(head)
    if (percent != null) sb.append(" · ").append(percent).append('%')
    if (detail.model.isNotEmpty()) sb.append(" · ").append(detail.model)
    sb.append('\n')

    contextOverLimitText(detail.overLimit)?.let { sb.append('\n').append(it).append('\n') }

    val lines = contextLinesOf(detail.categories)
    if (lines.isNotEmpty()) {
        sb.append('\n')
        // 位置参数就是 token 数那两列 —— 表头也走词表（复制出去的东西同样是界面文案）
        sb.append('|').append(CcoderText.text("context.copy.colRow"))
            .append('|').append(CcoderText.text("context.copy.colTokens")).append("|\n")
        sb.append("|---|---|\n")
        lines.forEach { sb.append('|').append(it.label).append('|').append(it.tokens).append("|\n") }
    }

    contextListsOf(detail).forEach { list ->
        sb.append('\n').append(list.title)
            .append(" (").append(list.rows.size).append(" · ")
            .append(formatTokenCount(contextListTokensOf(list))).append(")\n")
        list.rows.forEach { row ->
            sb.append("- ").append(row.label)
            if (row.sub.isNotEmpty()) sb.append(" (").append(row.sub).append(')')
            sb.append(": ").append(row.tokens).append('\n')
        }
    }
    return sb.toString()
}
