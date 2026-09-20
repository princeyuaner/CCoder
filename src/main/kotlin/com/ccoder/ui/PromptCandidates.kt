package com.ccoder.ui

import com.ccoder.settings.PromptPreset
import com.ccoder.settings.summarizePrompt
import com.ccoder.text.CcoderText

/**
 * 预置 prompt 在补全弹层里的分组标题。
 *
 * 与 [GROUP_PLUGIN] / [GROUP_OTHER] 同一族。分组标题**只在换组时插一条**
 * （`CompletionPopup.buildCompletionList`），所以合并时候选必须按组连续排列 ——
 * 见 [promptCandidates] 调用方的拼接顺序。
 */
internal val GROUP_PRESET: String get() = CcoderText.text("composer.presets.group")

/**
 * 把预置 prompt 转成补全候选。
 *
 * 两条与命令候选**不同**的地方，都是刻意的：
 *
 *  - `insert` 放的是 prompt **正文**，且 [CompletionItem.verbatim] 为 true ——
 *    它要写进输入框的是整段话，不是 `/名字`
 *  - `description` 只在**与显示名不同**时才给。名字空着会用内容首行顶上
 *    （见 `normalizePromptPreset`），那种情况下再显示一遍内容摘要，
 *    一行里就会重复两遍同样的话
 *
 * 不做任何过滤：过滤是 [filterCandidates] 的事，这一层只管转换。
 */
internal fun promptCandidates(presets: List<PromptPreset>): List<CompletionItem> =
    presets.map { preset ->
        CompletionItem(
            display = preset.name,
            insert = preset.content,
            description = summarizePrompt(preset.content).takeIf { it != preset.name },
            group = GROUP_PRESET,
            verbatim = true,
        )
    }
