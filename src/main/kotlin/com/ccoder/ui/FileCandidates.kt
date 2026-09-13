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
 * 文件候选：路径或文件名**前缀**匹配（大小写不敏感）。
 *
 * 两条都匹配是因为两种习惯都真实：想找某个目录下的东西时按路径敲，
 * 想找 `Composer.kt` 时按文件名敲。
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
    return paths
        .filter { p ->
            val lower = p.lowercase()
            lower.startsWith(q) || lower.substringAfterLast('/').startsWith(q)
        }
        .take(limit)
        .map { CompletionItem(display = it, insert = it) }
}
