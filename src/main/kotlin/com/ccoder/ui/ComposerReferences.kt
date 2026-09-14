package com.ccoder.ui

/**
 * 输入框里的**片段记号**，以及它到"真正发出去的那段文本"的展开。
 *
 * ## 为什么要有记号这一层
 *
 * 右键「添加到 CCoder 聊天框」原本把整段代码铺进输入框：选 30 行就顶掉 30 行，
 * 想再写句话得先滚过它。改成**一行记号**（`⟦路径 24-27 · 4 行⟧`），
 * 发送的那一刻才展开成完整的路径 + 代码围栏 —— 模型收到的东西一个字节都没变。
 *
 * ## 为什么展开表按**记号文本**索引，而不是按位置
 *
 * 位置是错的：用户会在记号前后打字、粘贴、删字。按文本索引则天然不受影响 ——
 * 记号被删掉就等于没有它，文本被挪到哪儿都还是同一个记号。
 * 代价是"同一文件同一行范围加两次"会共用最后那次快照（无害：内容本就一样）。
 * 所以记号里带的是**完整相对路径**而不是文件名 —— 否则 `src/a/X.kt` 与
 * `test/b/X.kt` 会长得一模一样，那一份快照就会喂错给另一处。
 *
 * 全部在 EDT 上用，没有同步。
 */
internal class SnippetRefs {

    /** 记号文本 → 展开后要发出去的那段。 */
    private val snippets = mutableMapOf<String, String>()

    /** 记住一个记号对应的片段。重复记住同一个记号时以最后一次为准。 */
    fun remember(token: String, snippet: String) {
        snippets[token] = snippet
    }

    /**
     * 把文本里认得出来的记号换成片段；认不出来的（用户手打的、或者表里已经
     * 没有的）**原样留着** —— 拿不准就什么都别做，把用户写的东西原封不动发出去
     * 比自作主张替换成空强。
     */
    fun expand(text: String): String {
        if (snippets.isEmpty() || REF_OPEN !in text) return text
        return REF_PATTERN.replace(text) { m ->
            snippets[m.value] ?: m.value
        }
    }

    fun clear() = snippets.clear()
}

/**
 * 文本里所有记号落在哪几段。**闭区间、含两端**（`text.substring(first, last + 1)`
 * 就是那一段）；喂 `Highlighter.addHighlight` 时再加一。
 *
 * 与 [SnippetRefs.expand] 走**同一个** [REF_PATTERN]：看得见的那一层
 * 与认得出的那一层必须是同一批位置，否则会出现"画了底却没展开"
 * 或者反过来 —— 那种不一致在屏幕上完全看不出来。
 */
internal fun refRanges(text: String): List<IntRange> =
    REF_PATTERN.findAll(text).map { it.range }.toList()

/** 记号的开括号。用 `⟦⟧` 而不是方括号：中英文正文里几乎不可能撞上。 */
internal const val REF_OPEN = "⟦"
internal const val REF_CLOSE = "⟧"

/** 一段记号，`⟦` 到 `⟧` 之间不含 `⟧`。 */
private val REF_PATTERN = Regex("$REF_OPEN[^$REF_CLOSE]*$REF_CLOSE")

/** 行范围的显示：单行不写区间（`24`），多行写区间（`24-27`）。 */
internal fun lineRangeText(lines: IntRange): String =
    if (lines.first == lines.last) "${lines.first}" else "${lines.first}-${lines.last}"

/**
 * 输入框里那一行记号。
 *
 * 里面三样东西都是为了**让人一眼认出来**：完整相对路径（哪个文件）、行范围
 * （哪几行）、行数（有多长）。发送时它会被整段替换掉，所以这里怎么显示
 * 不影响模型看到的内容。
 */
internal fun refToken(path: String, lines: IntRange): String =
    "$REF_OPEN$path ${lineRangeText(lines)} · ${lines.count()} 行$REF_CLOSE"

/**
 * 文件引用：`@相对路径 `，尾随一个空格。
 *
 * **与 `@` 补全插进去的是同一个形状**（见 `Completion.applyCompletion`：文件也
 * 多一个尾随空格）。这不是巧合，是必须的 —— CLI 就是靠这个 `@` 把文件内容
 * 展开进上下文的（2026-09-14 实测：纯路径展开，0 次工具调用；带行范围不展开）。
 * 两处形状一旦不一致，右键加进来的那个就成了普通文字。
 */
internal fun fileMention(path: String): String = "@$path "
