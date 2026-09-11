package com.ccoder.ui

import com.google.gson.JsonObject
import java.util.Locale

/**
 * 一次 result 事件带来的上下文用量。
 *
 * @param contextWindow 上下文窗口容量；缺失记为 0，由格式化那层决定怎么显示
 */
internal data class ContextUsage(val inputTokens: Long, val contextWindow: Long)

/**
 * 从 result 事件的 `modelUsage` 里取出主对话的用量。
 *
 * `modelUsage` 是 `Record<模型名, ModelUsage>`（sdk.d.ts:5056），子 agent 与
 * 辅助模型也各占一项，而它们的输入量远小于主对话。取输入量最大的那一项作为
 * "上下文占用"—— 主对话的输入包含完整历史，必然是最大的。
 *
 * 取不到就返回 null 而**不是造一个零值**：零值会被显示成"上下文 0"，
 * 那是假信息。
 */
internal fun contextUsageOf(event: JsonObject): ContextUsage? {
    val perModel = event.get("modelUsage")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null

    return perModel.entrySet()
        .mapNotNull { (_, value) -> value.takeIf { it.isJsonObject }?.asJsonObject }
        .mapNotNull { entry ->
            val input = entry.long("inputTokens") ?: return@mapNotNull null
            ContextUsage(inputTokens = input, contextWindow = entry.long("contextWindow") ?: 0L)
        }
        .maxByOrNull { it.inputTokens }
}

/** 按量级缩写 token 数：12345 → "12.3k"。 */
internal fun formatTokenCount(tokens: Long): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 1_000_000 -> trimTrailingZero(tokens / 1_000.0) + "k"
    else -> trimTrailingZero(tokens / 1_000_000.0) + "M"
}

/**
 * "12.3k / 200k · 6%"
 *
 * 窗口未知（≤ 0）时只显示已用量，不做除法。
 */
internal fun formatContextUsage(usage: ContextUsage): String {
    val used = formatTokenCount(usage.inputTokens)
    if (usage.contextWindow <= 0) return used

    val percent = Math.round(usage.inputTokens * 100.0 / usage.contextWindow)
    return "$used / ${formatTokenCount(usage.contextWindow)} · $percent%"
}

/** 200000 → "200k" 而不是 "200.0k"；用 ROOT locale 避免某些语言下小数点是逗号。 */
private fun trimTrailingZero(value: Double): String {
    val formatted = String.format(Locale.ROOT, "%.1f", value)
    return formatted.removeSuffix(".0")
}

private fun JsonObject.long(key: String): Long? {
    val value = get(key) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
    return value.asLong
}
