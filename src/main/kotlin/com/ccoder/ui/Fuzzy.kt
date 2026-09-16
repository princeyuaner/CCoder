package com.ccoder.ui

/**
 * 子序列匹配（"模糊"）—— **一份实现，文件补全与符号补全都用它**。
 *
 * 起因（2026-09-16）：想把 `@` 补全换成 CLI 的 `file_suggestions`，量完发现那头
 * 是降级（见 sidecar/tools/probe-file-suggestions.mjs），**只有模糊匹配这一样
 * 比我们强** —— 于是把这一样搬过来，顺便让 `#` 符号那套排序吃同一份实现，
 * 免得两处各写一套、哪天各漂各的。
 *
 * 判据就一条：query 的字符按顺序出现在文本里（大小写不敏感）。
 * 顺序之外还看三样，都是"看起来更像"的直觉换算成分数：
 *
 * - **连着命中**加分最多（`Comp` 打中 `Composer` 比打中 `c…o…m…p` 强）
 * - **词边界**加分（`/`、`.`、`-`、`_` 之后，以及驼峰的大写处）
 * - 命中得越晚、文本越长，扣一点
 */
internal data class FuzzyMatch(
    /** 越大越像。只在同一份实现内部可比，别跨来源比大小。 */
    val score: Int,
    /** 命中字符在**文本**里的下标（升序）—— 弹层拿它做高亮。 */
    val hits: List<Int>,
)

/** 空前缀不当匹配：那等于"什么都算命中"。 */
internal fun fuzzyMatch(text: String, query: String, from: Int = 0): FuzzyMatch? {
    if (query.isEmpty() || query.length > text.length) return null

    var qi = 0
    var first = -1
    var prev = -2
    var consecutive = 0
    var boundary = 0
    val hits = ArrayList<Int>(query.length)
    var i = from
    while (i < text.length && qi < query.length) {
        if (text[i].lowercaseChar() == query[qi]) {
            if (first < 0) first = i
            if (i == prev + 1) consecutive++
            if (i == 0 || !text[i - 1].isLetterOrDigit()) boundary++
            else if (text[i - 1].isLowerCase() && text[i].isUpperCase()) boundary++
            prev = i
            hits += i
            qi++
        }
        i++
    }
    if (qi < query.length) return null

    val score = 100 + consecutive * 12 + boundary * 8 - (first - from) / 4 - text.length / 20
    return FuzzyMatch(score, hits)
}

/**
 * 文件路径的匹配：**先只在文件名那一段里找**，凑不出完整子序列才回到整条路径。
 *
 * 两条路的分数**不在一个量级**（文件名那条 +1000），于是"文件名命中"永远排在
 * "只在目录里命中"前面。为什么值得这么偏：用户敲的片段多半是文件名
 * （`cmprk` 想找 `ComposerMode.kt`），而拿整条路径从头贪婪匹配会先把
 * `com/ccoder` 里的字符吃掉、拼出一个散架的低分匹配。
 */
internal fun fuzzyMatchPath(path: String, query: String): FuzzyMatch? {
    val baseStart = path.lastIndexOf('/') + 1
    fuzzyMatch(path, query, from = baseStart)?.let {
        return FuzzyMatch(it.score + 1000, it.hits)
    }
    return fuzzyMatch(path, query)
}
