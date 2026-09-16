package com.ccoder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex

/**
 * 项目里所有文件的相对路径（正斜杠）。
 *
 * 走平台的 `ProjectFileIndex` 而不是自己扫盘 —— 它自带项目范围与排除规则
 * （excluded roots 与 build 产物都不在里面），自己扫会把 `build/` 和
 * `sidecar/node_modules/` 一并扫出来，那是最没用的两万条候选。
 *
 * 抽成独立函数是为了让 [fileCandidates] 那半段能起单测：这个函数依赖
 * Project，测不了（同 [ComposerRulesTest] 的拆分理由）。
 */
internal fun collectProjectFiles(project: Project): List<String> {
    val base = project.basePath?.replace('\\', '/') ?: return emptyList()
    val prefix = "$base/"
    val out = mutableListOf<String>()
    ProjectFileIndex.getInstance(project).iterateContent { vf ->
        if (!vf.isDirectory && vf.path.startsWith(prefix)) {
            out += vf.path.removePrefix(prefix)
        }
        true
    }
    return out
}

/**
 * 文件候选：**前缀优先、模糊其次**（大小写不敏感）。
 *
 * 两条前缀规则都留着，因为两种习惯都真实：想找某个目录下的东西时按路径敲，
 * 想找 `Composer.kt` 时按文件名敲。前缀那批**永远排在模糊那批前面** ——
 * 用户敲对了开头，就该看到它排第一，而不是被某个"子序列恰好更像"的东西挤下去。
 *
 * 模糊这一层是 2026-09-16 加的。起因是想换用 CLI 的 `file_suggestions`
 * （见 sidecar/tools/probe-file-suggestions.mjs），量完发现那头是降级：
 * 前 1.4 秒回空、把 `.gitignore` 里写着的 `build/` 与 `node_modules/` 照样
 * 倒出来、上限 15 条。**它唯一比我们强的是模糊匹配**，所以只把这一条搬了过来，
 * 数据源仍走 IDE 的项目模型。
 *
 * **空前缀给空列表。** 裸 `@` 在正常行文里也会出现（"@ 一下他"），
 * 那时候闪一个文件列表是纯噪音；而 `@` 后面什么都不写本来也不是一条
 * 有效的引用。
 */
internal fun fileCandidates(
    paths: List<String>,
    query: String,
    limit: Int = 50,
): List<CompletionItem> {
    if (query.isEmpty()) return emptyList()
    val q = query.lowercase()

    val out = ArrayList<CompletionItem>(limit)
    val fuzzy = ArrayList<Pair<String, Int>>()
    for (path in paths) {
        // 用 ignoreCase 比较而不是先把每条路径 lowercase() —— 两万条路径
        // 就是两万个临时字符串，而这是**每敲一个字**都要跑一遍的地方
        if (path.startsWith(q, ignoreCase = true) ||
            path.startsWith(q, path.lastIndexOf('/') + 1, ignoreCase = true)
        ) {
            out += CompletionItem(display = path, insert = path)
            // 前缀层收满就回 —— 模糊那一层一个位置都没有了，没必要再扫一遍
            if (out.size >= limit) return out
            continue
        }
        // 单字符不走模糊：`@s` 几乎能子序列命中所有路径，那是个纯噪音列表
        if (q.length < 2) continue
        fuzzyScore(path, q)?.let { fuzzy += path to it }
    }

    // 稳定排序：同分保持原顺序（目录遍历顺序），免得列表在同分项之间乱跳
    fuzzy.sortByDescending { it.second }
    for ((p, _) in fuzzy) {
        out += CompletionItem(display = p, insert = p)
        if (out.size >= limit) break
    }
    return out
}

/**
 * 子序列匹配的打分（高者前）；不匹配给 null。
 *
 * 先只在**文件名**那一段里找：用户敲的片段多半是文件名（`cmprk` 想找
 * `ComposerMode.kt`），而拿整条路径从头贪婪匹配会先把 `com/ccoder` 里的
 * 字符吃掉、拼出一个散架的低分匹配。文件名里凑不出完整子序列，才回到整条
 * 路径上再试 —— 两条路的分数**不在一个量级**（文件名那条 +1000），
 * 于是"文件名命中"永远排在"只在目录里命中"前面。
 *
 * 分数由三样换算，都是"看起来更像"的直觉：
 * - **连着命中**加分最多（`Comp` 打中 `Composer` 比打中 `c…o…m…p` 强）
 * - **词边界**加分（`/`、`.`、`-`、`_` 之后，以及驼峰的大写处）
 * - 命中得越晚、路径越长，扣一点
 */
private fun fuzzyScore(path: String, q: String): Int? {
    val baseStart = path.lastIndexOf('/') + 1
    scoreFrom(path, baseStart, q)?.let { return 1000 + it }
    return scoreFrom(path, 0, q)
}

private fun scoreFrom(path: String, from: Int, q: String): Int? {
    var qi = 0
    var first = -1
    var prev = -2
    var consecutive = 0
    var boundary = 0
    var i = from
    while (i < path.length && qi < q.length) {
        if (path[i].lowercaseChar() == q[qi]) {
            if (first < 0) first = i
            if (i == prev + 1) consecutive++
            if (i == 0 || !path[i - 1].isLetterOrDigit()) boundary++
            else if (path[i - 1].isLowerCase() && path[i].isUpperCase()) boundary++
            prev = i
            qi++
        }
        i++
    }
    if (qi < q.length) return null
    return 100 + consecutive * 12 + boundary * 8 - (first - from) / 4 - path.length / 20
}
