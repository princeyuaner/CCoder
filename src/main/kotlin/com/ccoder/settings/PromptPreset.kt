package com.ccoder.settings

import java.util.UUID

/**
 * 一条预置 prompt。
 *
 * 形状约定与 [ModelProfile] 一致：属性一律 `var` + 默认值、集合用 [MutableList]
 * —— 这是 XmlSerializer 认的形状（先例见 `ClaudeSettings.State`）。
 */
data class PromptPreset(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var content: String = "",
)

/** 摘要长度。够在补全弹层与列表里认出来，又不至于把整段 prompt 铺进去。 */
private const val SUMMARY_MAX = 40

/**
 * 收敛成不变量成立的样子。**不变量只在这一个函数里维护** ——
 * 只在 [PromptPresets.loadState] 与 [PromptPresets.upsert] 两处调用（照
 * `normalizeModelProfile` 的先例）。
 *
 * 名字空着就用内容首行顶上：列表里一行空白比一条难看的名字更难用，
 * 而"只写了内容、忘了起名"是很常见的一步。
 */
fun normalizePromptPreset(preset: PromptPreset): PromptPreset {
    val content = preset.content.trim()
    val name = preset.name.trim()
    return preset.copy(
        name = name.ifEmpty { summarizePrompt(content) },
        content = content,
    )
}

/**
 * 一条 preset 是不是彻底空的（名字与内容都空）。
 *
 * 用户点「＋ 添加」会立刻建一条空条目进编辑态（照模型页的先例），
 * 若他直接关掉对话框，读盘时就要把这种条目滤掉 —— 否则列表里会攒下幽灵行。
 */
fun isBlankPromptPreset(preset: PromptPreset): Boolean =
    preset.name.isBlank() && preset.content.isBlank()

/**
 * 内容摘要：**首个非空行**、封顶 [SUMMARY_MAX]。
 *
 * 取"首个非空行而不是第 0 行"：prompt 常常以空行或缩进开头，
 * 直接取第 0 行会得到一条空摘要。
 */
fun summarizePrompt(content: String): String {
    val firstLine = content.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return ""
    return if (firstLine.length <= SUMMARY_MAX) {
        firstLine
    } else {
        firstLine.take(SUMMARY_MAX - 1) + "…"
    }
}
