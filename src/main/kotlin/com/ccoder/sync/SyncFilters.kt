package com.ccoder.sync

/**
 * 过滤三层：**同步范围** / **排除项** / **内置垃圾规则**。逐字沿用参考实现的语义
 * （`sync_remote.py` 的 `in_scope` / `in_scope_dir` / `is_excluded` / `is_junk`）。
 *
 * 全是纯函数、无平台依赖：这一层定下了整个功能的正确性（"我改的东西怎么没同步"
 * 与"目标端怎么多了个奇怪的文件"两类问题都出在这里），所以它必须能被单测钉死。
 *
 * ## 为什么整条路都归一成小写再比
 *
 * 参考实现如此，两个理由：Windows 的文件名本来就不区分大小写，而配置项是人手写的
 * （写 `Build` 还是 `build` 全看当时手感）。**归一只作用于"匹配"**——真正落盘的
 * 相对路径用的是扫描时那条原始大小写，基线表的键也是它，所以同一个文件在两侧
 * 永远映射到同一个键。
 */

/**
 * 相对路径 / 配置模式的归一：`\` → `/`、去掉首尾 `/`、转小写。
 *
 * 配置项与扫描出来的相对路径**共用这一个**归一（参考实现也是同一套写法），
 * 否则"配置里写反斜杠就匹配不上"这种坑会从两个方向各来一次。
 */
internal fun normalizeRelPath(raw: String): String =
    raw.replace('\\', '/').trim().trim('/').lowercase()

/**
 * 路径是否落在同步范围内。[syncRoots] 为空 = 整个源目录都在范围内。
 *
 * 注意是**按分量**比（`trunk` 命中 `trunk/a.kt`，但不会命中 `trunkish/a.kt`）——
 * 参考实现用 `startswith(root + "/")` 实现这一点，这里保持一致。
 */
internal fun inScope(rel: String, syncRoots: List<String>): Boolean {
    if (syncRoots.isEmpty()) return true
    val norm = normalizeRelPath(rel)
    if (norm.isEmpty()) return true
    return syncRoots.any { norm == it || norm.startsWith("$it/") }
}

/**
 * 目录是否**可能**含有范围内的文件 —— 决定遍历时要不要下探。
 *
 * 与 [inScope] 的差别只有一处：**范围的祖先目录也返回 true**。
 *
 * 为什么必须有这个差别：配置里写了嵌套范围（`trunk/defines`）时，遍历走到 `trunk`
 * 就会被 [inScope] 判成"不在范围内"而不再下探，扫描结果直接空掉——表现是
 * **"文件明明改了却不同步"**。参考实现踩过这一脚，这里照抄它的修法。
 */
internal fun inScopeDir(rel: String, syncRoots: List<String>): Boolean {
    if (syncRoots.isEmpty()) return true
    val norm = normalizeRelPath(rel)
    if (norm.isEmpty()) return true
    return syncRoots.any { root ->
        norm == root || norm.startsWith("$root/") || root.startsWith("$norm/")
    }
}

/**
 * 用户排除项。`true` = 该路径不参与复制/删除/对账。
 *
 * 匹配语义**刻意做成两种**，为的是书写直观（参考实现的原话）：
 *
 * - **含 `/`** → 按相对路径匹配：等于该路径，或位于它之下。
 *   `.claude` 排除该目录及其全部内容；`trunk/temp` 只排那一处。
 * - **不含 `/`** → 按**任意一层**路径分量匹配，支持 `*` / `?`。
 *   `build` 排除任何位置的 build；`*.log` 排除所有 `.log` 文件。
 *
 * ## 含 `/` 且带通配符的：按整条相对路径匹配
 *
 * 这一条是本版**对参考实现的修正**。它的写法是"含 `/` 就只做字面前缀匹配"，于是
 * "`trunk/temp/` 后面跟个星号"这种路径模式**永远匹配不上任何东西**——静默失效：
 * 用户以为排掉了，实际一个文件都没排掉，而且没有任何地方会告诉他。
 * 所以这里补一条：含 `/` 同时含通配符时，拿**整条相对路径**做通配匹配
 * （此时星号可以跨层，与 Python 的 `fnmatch` 一致）。
 *
 * > **写这个文件时注意**：Kotlin 的块注释是**可嵌套**的，所以这段注释里**绝不能出现
 * > "斜杠紧跟星号"那两个字符**——那会开一个嵌套注释，把收尾的 `*` `/` 吃掉，
 * > 报的是 "Unclosed comment"，然后整个文件的声明全部消失，看起来像一堆"未解析引用"。
 * > 2026-09-24 第一版就是这么红的。真的要写那个形状，就用全角星号或者拆成两个反引号段。
 *
 * 这是**纯扩展**：原先这类模式匹配结果为空，现在按用户的字面意图生效，
 * 没有任何一条原本有效的模式改变含义。
 *
 * 三种写法都要求配置已由 [normalizeConfig] 归一过。
 */
internal fun isExcluded(rel: String, patterns: List<String>): Boolean {
    if (patterns.isEmpty()) return false
    val norm = normalizeRelPath(rel)
    if (norm.isEmpty()) return false
    val parts = norm.split('/').filter { it.isNotEmpty() }
    for (pat in patterns) {
        if (pat.isEmpty()) continue
        when {
            '/' in pat && pat.any { it == '*' || it == '?' } -> {
                if (globMatch(norm, pat)) return true
            }
            '/' in pat -> {
                if (norm == pat || norm.startsWith("$pat/")) return true
            }
            parts.any { globMatch(it, pat) } -> return true
        }
    }
    return false
}

/**
 * 单层名字的通配匹配：`*` 任意串（含空串）、`?` 恰好一个字符。
 *
 * **`[` 当作普通字符**（Python 的 `fnmatch` 会把它当字符集，这里刻意不支持）：
 * 排除项是手写的，多一种括号语义只会多一处惊喜。
 *
 * 用带回溯的双指针而不是递归：递归写法在 `*****a` 这类模式上会指数爆炸，
 * 而这个函数在扫描**每个文件**时都会被调用。
 */
internal fun globMatch(name: String, pattern: String): Boolean {
    var n = 0
    var p = 0
    var star = -1
    var mark = 0
    while (n < name.length) {
        when {
            p < pattern.length && (pattern[p] == '?' || pattern[p] == name[n]) -> {
                n++
                p++
            }
            // 记下这个星号的位置，先假设它匹配空串
            p < pattern.length && pattern[p] == '*' -> {
                star = p
                p++
                mark = n
            }
            // 走不动了就回退到上一个星号，让它多吃一个字符
            star >= 0 -> {
                p = star + 1
                n = ++mark
            }
            else -> return false
        }
    }
    // 尾部剩下的星号可以匹配空串
    while (p < pattern.length && pattern[p] == '*') p++
    return p == pattern.length
}

/** 目录名是不是内置垃圾 —— 遍历时据此**剪枝**（不下探）。 */
internal fun isJunkDir(name: String, rules: JunkRules): Boolean =
    name.lowercase() in rules.dirNames

/**
 * 路径是不是内置垃圾：**祖先目录名**、叶子名、叶子后缀、叶子前缀，四条里中一条就是。
 *
 * ## 那条不对称是刻意的
 *
 * 祖先目录名判的是 `parts[0..n-2]`，**不含叶子**。所以 `isJunk(".git")` 为**假**、
 * `isJunk(".git/x")` 为真。参考实现如此，理由在调用点的分工：
 * 目录靠自己那条 [isJunkDir] 剪枝（于是"这个目录要不要下探"是一个独立的判断），
 * 而一个**文件**的路径里只要有一层是垃圾目录，它就不该被同步。
 *
 * 后果：拿目录路径直接问 [isJunk] 会得到"不是垃圾"，于是监听那条路必须补一层
 * 假子路径 —— 见 [isJunkEntry]，别再手写那个补丁（参考实现里它散在
 * `ChangeHandler._relative` 一处，容易漏）。
 */
internal fun isJunk(rel: String, rules: JunkRules): Boolean {
    val parts = normalizeRelPath(rel).split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return false
    if (parts.dropLast(1).any { it in rules.dirNames }) return true
    val name = parts.last()
    if (name in rules.fileNames) return true
    if (rules.suffixes.any { name.endsWith(it) }) return true
    return rules.prefixes.any { name.startsWith(it) }
}

/**
 * 一条路径（**文件或目录都适用**）该不该被跳过。
 *
 * [isJunk] 只判"文件的路径里有没有垃圾层"，问目录自身它会说不是。这里把两种调用点
 * 的差别收进一个函数：目录事件补一层假子路径，于是 `trunk/.git` 这种目录事件也能被
 * 正确挡掉，与扫描的判断**同语义**。
 *
 * 扫描用 `isDirectory` 剪枝（不下探垃圾目录），监听用它折算事件。
 */
internal fun isJunkEntry(rel: String, rules: JunkRules, isDirectory: Boolean): Boolean =
    if (isDirectory) {
        val name = normalizeRelPath(rel).substringAfterLast('/')
        isJunkDir(name, rules) || isJunk("$rel/_", rules)
    } else {
        isJunk(rel, rules)
    }

/**
 * 一条路径该不该参与同步 —— 三层过完的结论。
 *
 * 抽成一个函数是为了**只有一份**：复制、删除、内容对账三条路共用它，
 * 于是不可能出现"排除项挡得住复制、却绕过去把目标端删了"这种漂移。
 * 参考实现的注释专门强调了这一点（`collect()` 与内容对账走同一份过滤代码）。
 */
internal fun syncable(rel: String, cfg: SyncConfig): Boolean =
    inScope(rel, cfg.syncRoots) && !isExcluded(rel, cfg.exclude) && !isJunk(rel, cfg.junk)

/**
 * 同 [syncable]，但按**目录**判垃圾。
 *
 * 差别就是 [isJunkEntry] 那一条：`trunk/build`、`trunk/node_modules` 这种"自己名字
 * 就是垃圾"的目录，用 [syncable] 问会说"不是垃圾"（[isJunk] 只判祖先层）。遍历剪枝
 * 与空目录清理两条路要的是这个版本。
 *
 * 参考实现里那个补丁写作 `is_junk(rel + "/_")`，散在调用点里；这里给它一个名字，
 * 免得第二个调用点写错（少这一层就"排除项挡得住、剪枝却挡不住"）。
 */
internal fun syncableDir(rel: String, cfg: SyncConfig): Boolean =
    inScope(rel, cfg.syncRoots) &&
        !isExcluded(rel, cfg.exclude) &&
        !isJunkEntry(rel, cfg.junk, isDirectory = true)
