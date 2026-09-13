package com.ccoder.ui

import java.awt.event.KeyEvent

/** 显示在补全弹层里的一项。 */
internal data class CompletionItem(
    /** 显示的名字。命令可能是人话标题（`Debug Issue`），文件是相对路径。 */
    val display: String,
    /** 真正写进输入框的文本，**不含触发字符**。 */
    val insert: String,
    /** 副标题。命令是描述，文件为空。 */
    val description: String? = null,
    /** 只在过滤时参与匹配的别名，不单独成行。 */
    val aliases: List<String> = emptyList(),
    /** 分组标题；null = 不分组。 */
    val group: String? = null,
)

/** 触发补全的字符。 */
internal enum class Trigger(val char: Char) {
    Command('/'),
    File('@'),
}

/**
 * 光标前这段文本触发了什么。
 *
 * [start] 是触发字符在整个文本里的下标 —— 采纳时要从这里开始替换，
 * 而不是在光标处插入：用户已经敲了 `/comp`，那五个字符是**查询词**。
 */
internal data class CompletionQuery(
    val trigger: Trigger,
    val query: String,
    val start: Int,
)

/** 弹层开着时，某个键要做什么。 */
internal enum class CompletionKey { Up, Down, Accept, Dismiss, Ignore }

/**
 * 光标处该不该弹补全。不弹返回 null。
 *
 * 两条触发规则（设计稿 §3.1）：
 *  - `/` **只在消息开头**。`C:/Users`、`1/2`、`and/or` 里都有斜杠，
 *    任何位置都弹的话正常打字会被一直打断。
 *  - `@` **只在词边界**。`foo@bar.com` 的 `@` 前面是字母，不算。
 *
 * 还有一条共同的：**打了空格就收**。`/compact 自定义说明` 的参数、
 * `@path` 后面接的话，都不再是候选的一部分。
 */
internal fun completionQuery(text: String, caret: Int): CompletionQuery? {
    val at = caret.coerceIn(0, text.length)
    val before = text.substring(0, at)

    // 命令优先：`/` 必须占住整段前缀，所以它和 `@` 不可能同时成立
    if (before.startsWith("/") && before.none { it.isWhitespace() }) {
        return CompletionQuery(Trigger.Command, before.drop(1), 0)
    }

    val atIndex = before.lastIndexOf('@')
    if (atIndex >= 0) {
        val boundary = atIndex == 0 || before[atIndex - 1].isWhitespace()
        val body = before.substring(atIndex + 1)
        if (boundary && body.none { it.isWhitespace() }) {
            return CompletionQuery(Trigger.File, body, atIndex)
        }
    }

    return null
}

/**
 * 前缀过滤。大小写不敏感，匹配显示名、插入文本或别名。
 *
 * 别名也参与匹配：`/usage` 的别名是 `cost`。不匹配别名的话，用户在终端里
 * 敲惯的 `cost` 在插件里一条候选都没有 —— 而那条命令其实完全可用。
 */
internal fun filterCandidates(items: List<CompletionItem>, query: String): List<CompletionItem> {
    if (query.isEmpty()) return items
    val q = query.lowercase()
    return items.filter { item ->
        item.display.lowercase().startsWith(q) ||
            item.insert.lowercase().startsWith(q) ||
            item.aliases.any { it.lowercase().startsWith(q) }
    }
}

/**
 * 高亮移动。**到头就停，不循环。**
 *
 * 循环的话"最后一项再往下"会跳回第一项，而用户按向下键的预期是"没有了"。
 * IDE 的补全也是不循环的。
 */
internal fun nextHighlight(current: Int, delta: Int, size: Int): Int {
    if (size <= 0) return 0
    return (current + delta).coerceIn(0, size - 1)
}

/**
 * 弹层开着时，这个键要做什么。
 *
 * **只在弹层真的开着时调用。** 关着的时候 Enter 该不该发送是 [isSendKey]
 * 的事，那个函数一行都不改（设计稿 §3.2）。
 */
internal fun completionKey(keyCode: Int): CompletionKey = when (keyCode) {
    KeyEvent.VK_UP -> CompletionKey.Up
    KeyEvent.VK_DOWN -> CompletionKey.Down
    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> CompletionKey.Accept
    KeyEvent.VK_ESCAPE -> CompletionKey.Dismiss
    else -> CompletionKey.Ignore
}

/**
 * 采纳一项，返回新的文本与光标位置。
 *
 * 替换的是**从触发字符到光标**那一段（而不是在光标处插入）。光标之后的
 * 文字原样保留：`看看 @Comp 这个文件` 里在 `@Comp` 处采纳，后面那半句
 * 不该被吃掉。
 *
 * 文件多写一个尾随空格（`@路径 ` 之后接着打字），命令不写 —— 命令后面
 * 要么是参数要么就该直接回车，多一个空格是噪音。
 */
internal fun applyCompletion(
    text: String,
    caret: Int,
    q: CompletionQuery,
    item: CompletionItem,
): Pair<String, Int> {
    val at = caret.coerceIn(0, text.length)
    val head = text.substring(0, q.start)
    val tail = text.substring(at)
    val written = q.trigger.char + item.insert + if (q.trigger == Trigger.File) " " else ""
    return (head + written + tail) to (head.length + written.length)
}
