package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo

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
internal const val GROUP_PLUGIN = "插件"
internal const val GROUP_OTHER = "其它"

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
    // （`Debug Issue` → `debug-issue`）、**2 条会猜错**（`frontend-design` →
    // `frontend-design:frontend-design`，要插件命名空间前缀，光看显示名猜不出来）。
    // 那 2 条是这条规则的已知代价：万一是它们，CLI 会把这行当普通文本处理。
    //
    // init 一到（`sendable` 非空）立刻回到严格配对 —— 宁缺勿错那半条规矩还在。
    val preInit = sendable.isEmpty()
    return commands.mapNotNull { cmd ->
        val insert = if (preInit) {
            normalizeCommandName(cmd.name)
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
    cmd.argumentHint?.takeIf { it.isNotBlank() }?.let { add("参数 $it") }
    if (cmd.aliases.isNotEmpty()) add("别名 " + cmd.aliases.joinToString("、"))
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
