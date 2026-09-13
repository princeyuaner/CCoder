package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo

/** 分组标题。 */
internal const val GROUP_BUILTIN = "内置"
internal const val GROUP_SKILL = "技能"

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
 * 把三份来源拼成候选。
 *
 * @param commands 显示信息（A）
 * @param skills   技能子集，只用来分组（C）
 * @param sendable 可发送的名字（B，来自 init 事件）
 *
 * 只在 A 里有、或配不上 B 的命令**直接不出现** —— 显示一个发出去会被当
 * 普通文本的"命令"，比不显示更糟（设计稿 §4.1 规则 2）。
 */
internal fun commandCandidates(
    commands: List<CommandInfo>,
    skills: List<CommandInfo>,
    sendable: Set<String>,
): List<CompletionItem> {
    val byNormalized = sendable.associateBy { normalizeCommandName(it) }
    val skillNames = skills.mapTo(mutableSetOf()) { normalizeCommandName(it.name) }

    return commands.mapNotNull { cmd ->
        val insert = byNormalized[normalizeCommandName(cmd.name)] ?: return@mapNotNull null
        CompletionItem(
            display = cmd.name,
            insert = insert,
            description = describeCommand(cmd),
            aliases = cmd.aliases,
            group = if (normalizeCommandName(insert) in skillNames) GROUP_SKILL else GROUP_BUILTIN,
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
