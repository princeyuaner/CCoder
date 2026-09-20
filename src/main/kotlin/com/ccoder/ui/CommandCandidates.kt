package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo
import com.ccoder.text.CcoderText

/**
 * 分组标题。
 *
 * 为什么是「插件 / 其它」而不是「技能 / 内置」：**协议里没有字段能区分
 * "CLI 内置命令"和"用户技能目录里的技能"** —— 实测（2026-09-13 探针）
 * `caveman`、`react-doctor`、`verify` 这些用户技能和 `compact`、`clear`
 * 在可发送名那一份里**长得一模一样，都是裸名**。把这一组叫「内置」是在撒谎。
 *
 * 唯一的结构性信号是**插件命名空间**（`superpowers:brainstorming`），
 * 拿它当分界线：带前缀的确实是插件，其余确实不是。标签照实说。
 */
internal val GROUP_PLUGIN: String get() = CcoderText.text("composer.commands.groupPlugin")
internal val GROUP_OTHER: String get() = CcoderText.text("composer.commands.groupOther")

/**
 * 命令名归一化：小写、空白折成连字符。
 *
 * 两份来源对同一批命令的写法不一样（设计稿 §2 事实 5）：`supportedCommands()`
 * 给的是 `Debug Issue`，可发送的名字是 `debug-issue`。不归一化就一条都配不上，
 * 而配不上按规则是**不显示** —— 整个技能组会凭空消失。
 */
internal fun normalizeCommandName(name: String): String =
    name.trim().lowercase().replace(Regex("\\s+"), "-")

/**
 * 从描述里读**插件命名空间** —— 描述以 `(x)` 开头时那个 `x`。
 *
 * 用途只有一个：init 未到、拿不到可发送名的那几秒里，别靠"归一化显示名"硬猜
 * （实测 31 条里有 2 条会猜错，见 [commandCandidates]）。
 *
 * 判据是量出来的（2026-09-16，probe-init-before-send.mjs 把 31 条的抬头全打了一遍）：
 * **只有 3 条描述以 `(x)` 开头，而这 3 条恰好就是要命名空间的那 3 条** ——
 * 其中 `code-review:code-review` 的 A 名自己就带着 `code-review:`，
 * 等于直接演示了"括号里那个词就是命名空间"。另外两条端到端也对得上：
 * `(frontend-design)` + 显示名 `frontend-design` → `frontend-design:frontend-design`；
 * 拿设计稿 §2 事实 5 那张表的例子套：`(superpowers)` + `brainstorming`
 * → `superpowers:brainstorming`（**前缀是插件名、不一定是技能名**，所以只能读、不能推）。
 *
 * 只认「开头的 + 形状像 slug 的」括号：描述里别处的括号（比如末尾那个
 * `(user)`）一律不当信号。认错会把名字拼歪，而拼歪正是这条规则要消灭的东西。
 */
internal fun namespaceOf(description: String?): String? {
    val text = description?.trimStart() ?: return null
    if (!text.startsWith("(")) return null
    val end = text.indexOf(')')
    if (end <= 1) return null
    val token = text.substring(1, end).trim()
    return token.takeIf { candidate ->
        candidate.isNotEmpty() && candidate.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}

/**
 * 两个名字是否指同一条命令。
 *
 * 除了归一化，还要容忍**插件命名空间**。实测（2026-09-13 探针）两份来源的写法：
 *
 * | 显示（A） | 可发送（B） |
 * |---|---|
 * | `brainstorming` | `superpowers:brainstorming` |
 * | `frontend-design` | `frontend-design:frontend-design` |
 * | `skill-creator` | `skill-creator:skill-creator` |
 *
 * 不认这一层的话 45 条里只配上 27 条 —— **superpowers 整个技能库一条都不显示**，
 * 而那正是用户日常在用的那套。容忍之后 45/45 全部配上。
 *
 * 两个方向都试（`na` 带前缀或 `nb` 带前缀），因为哪一边带命名空间取决于
 * 命令来自哪一层，不是固定的。
 */
internal fun sameCommand(a: String, b: String): Boolean {
    val na = normalizeCommandName(a)
    val nb = normalizeCommandName(b)
    return na == nb || nb.endsWith(":$na") || na.endsWith(":$nb")
}

/**
 * 把两份来源拼成候选。
 *
 * @param commands 显示信息（A）
 * @param sendable 可发送的名字（B，来自 init 事件）
 *
 * 只在 A 里有、或配不上 B 的命令**直接不出现** —— 显示一个发出去会被当
 * 普通文本的"命令"，比不显示更糟（设计稿 §4.1 规则 2）。
 *
 * 分组只看**可发送名带不带插件命名空间**（见 [GROUP_PLUGIN]）。设计稿 §4.1
 * 原定的 C 来源是 `reloadSkills()`，但实测这台 CLI 不支持
 * （`Unsupported control request subtype: reload_skills`），所以走命名空间。
 */
internal fun commandCandidates(
    commands: List<CommandInfo>,
    sendable: Set<String>,
): List<CompletionItem> {
    // 「可发送名」那一份（`system/init.slash_commands`）在**第一条消息之前是不存在的** ——
    // 流式输入下 init 要等用户先说话（2026-09-15 探针 probe-init-before-send.mjs 复现：
    // 什么都不发，15 秒一个事件都没有）。
    //
    // 于是刚打开插件时那条"配不上就不显示"的规则会把整个列表滤空：显示用的那份
    // （supportedCommands()）明明已经拿到了，用户看到的却是"打 / 什么都没有"。
    //
    // 所以 init 未到时改用**归一化后的显示名**当插入值。这不是拍脑袋，是量过的
    // （同一个探针）：31 条里 25 条的显示名本来就是可发送名、4 条归一化后正确
    // （`Debug Issue` → `debug-issue`）、2 条要插件命名空间前缀。
    //
    // 那 2 条**不再靠猜**：它们的描述以 `(命名空间)` 开头，读出来拼上去即可
    // （见 [namespaceOf]，31 条的抬头全量核过，判据不误伤）。剩下真正无解的
    // 情况一条都没有了 —— 归一名拼出来的就是 CLI 认的那个。
    //
    // init 一到（`sendable` 非空）立刻回到严格配对 —— 宁缺勿错那半条规矩还在。
    val preInit = sendable.isEmpty()
    return commands.mapNotNull { cmd ->
        val insert = if (preInit) {
            val bare = normalizeCommandName(cmd.name)
            // 显示名自己就带命名空间的（`code-review:code-review`）原样用；
            // 否则看描述里有没有 `(namespace)` —— 有就拼上，没有才是裸名
            if (bare.contains(':')) bare else namespaceOf(cmd.description)?.let { "$it:$bare" } ?: bare
        } else {
            sendable.firstOrNull { sameCommand(cmd.name, it) } ?: return@mapNotNull null
        }
        CompletionItem(
            display = cmd.name,
            insert = insert,
            description = describeCommand(cmd),
            aliases = cmd.aliases,
            group = if (insert.contains(':')) GROUP_PLUGIN else GROUP_OTHER,
        )
    }
}

/**
 * 副标题：描述、参数提示、别名拼成一行。
 *
 * 别名要出现在这里 —— 用户从终端带过来的习惯是敲 `/cost`，而列表里显示的
 * 是 `/usage`。不写出来他会以为这个插件没有那个命令。
 */
internal fun describeCommand(cmd: CommandInfo): String = buildList {
    cmd.description?.takeIf { it.isNotBlank() }?.let { add(oneLine(it)) }
    cmd.argumentHint?.takeIf { it.isNotBlank() }?.let { add(CcoderText.text("composer.commands.args", it)) }
    if (cmd.aliases.isNotEmpty()) add(CcoderText.text("composer.commands.aliases", cmd.aliases.joinToString(CcoderText.text("common.listSeparator"))))
}.joinToString(" · ")

/**
 * 压成单行并截断。
 *
 * 真实数据里技能描述是**整段**的：实测 `/react-doctor` 的描述有五行、含换行。
 * 原样塞进弹层会让一行变五行，整个列表失去形状。
 */
internal fun oneLine(text: String, max: Int = 110): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1) + "…"
}
