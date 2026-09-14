package com.ccoder.ui

import java.util.Locale

/**
 * 上下文占用的数值。
 *
 * 两个数都来自 CLI 自己的 `getContextUsage()`（见
 * [com.ccoder.sidecar.Protocol.encodeContextUsage]）—— 那正是 `/context` 用的
 * 同一份读数，所以卡片显示的就是 CLI 会告诉你的东西。
 *
 * @param usedTokens 已占用的 token（各类目之和：系统提示、工具、消息……）
 * @param windowTokens 算比例用的分母。它是 CLI 解析出来的**可压缩窗口**，未必
 *   等于模型的硬上限（可能被压缩策略收窄过）；为 0 表示没拿到，此时不做除法
 */
internal data class ContextUsage(val usedTokens: Long, val windowTokens: Long)

/** 按量级缩写 token 数：12345 → "12.3k"。 */
internal fun formatTokenCount(tokens: Long): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 1_000_000 -> trimTrailingZero(tokens / 1_000.0) + "k"
    else -> trimTrailingZero(tokens / 1_000_000.0) + "M"
}

/**
 * 上下文占用百分比，四舍五入到整数。窗口未知时给 null —— **不做除法**。
 *
 * 与 [contextRatioText] 分开而不是返回一个拼好的字符串：卡片要把百分比
 * 放大、把绝对数放小，两者得能分开取。
 */
internal fun contextPercentOf(usage: ContextUsage): Int? {
    if (usage.windowTokens <= 0) return null
    return Math.round(usage.usedTokens * 100.0 / usage.windowTokens).toInt()
}

/** "12.3k / 200k"。窗口未知时只给已用量，仍然不做除法。 */
internal fun contextRatioText(usage: ContextUsage): String {
    val used = formatTokenCount(usage.usedTokens)
    if (usage.windowTokens <= 0) return used
    return "$used / ${formatTokenCount(usage.windowTokens)}"
}

/** 200000 → "200k" 而不是 "200.0k"；用 ROOT locale 避免某些语言下小数点是逗号。 */
private fun trimTrailingZero(value: Double): String {
    val formatted = String.format(Locale.ROOT, "%.1f", value)
    return formatted.removeSuffix(".0")
}
