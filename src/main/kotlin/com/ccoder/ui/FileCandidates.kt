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
    val fuzzy = ArrayList<Pair<CompletionItem, Int>>()
    for (path in paths) {
        // 用 ignoreCase 比较而不是先把每条路径 lowercase() —— 两万条路径
        // 就是两万个临时字符串，而这是**每敲一个字**都要跑一遍的地方
        val baseStart = path.lastIndexOf('/') + 1
        val atPathHead = path.startsWith(q, ignoreCase = true)
        if (atPathHead || path.startsWith(q, baseStart, ignoreCase = true)) {
            // 命中的是路径开头还是文件名开头，高亮的下标不一样
            val at = if (atPathHead) 0 else baseStart
            out += CompletionItem(
                display = path,
                insert = path,
                hits = (at until at + q.length).toList(),
            )
            // 前缀层收满就回 —— 模糊那一层一个位置都没有了，没必要再扫一遍
            if (out.size >= limit) return out
            continue
        }
        // 单字符不走模糊：`@s` 几乎能子序列命中所有路径，那是个纯噪音列表
        if (q.length < 2) continue
        fuzzyMatchPath(path, q)?.let {
            fuzzy += CompletionItem(display = path, insert = path, hits = it.hits) to it.score
        }
    }

    // 稳定排序：同分保持原顺序（目录遍历顺序），免得列表在同分项之间乱跳
    fuzzy.sortByDescending { it.second }
    for ((item, _) in fuzzy) {
        out += item
        if (out.size >= limit) break
    }
    return out
}
