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
    /**
     * [display] 里被查询命中的字符下标（升序），弹层拿它加粗。
     *
     * 空 = 不高亮。**匹配发生在哪一段就高亮哪一段**：`@` 的文件是按路径匹配的
     * （`display` 就是路径），`/` 的命令是按显示名或别名匹配的 —— 命中别名时
     * 这里给空，因为下标在 `display` 上对不上，加错比不加糟。
     */
    val hits: List<Int> = emptyList(),
    /**
     * 这一项插入的是**一整段文本**，而不是"命令名 / 文件路径"。
     *
     * 预置 prompt 就是这种：它要写进输入框的是 prompt 正文，不是 `/正文`。
     * 给 true 时 [applyCompletion] 原样写入 [insert] —— 不加触发字符、不加尾随空格。
     */
    val verbatim: Boolean = false,
    /**
     * 符号引用（`#`）解析好的那份数据；别的触发字符下为 null。
     *
     * **记号本身不够**：写进输入框的是一行记号（[insert]），而发送时要展开成
     * "路径:行范围 + 代码块" —— 那一段在采纳时就得按 token 文本记进
     * [SnippetRefs]。所以解析结果要跟着候选一起走，不能在采纳时再去解析一遍
     * （那时用户已经在等光标落位了）。
     */
    val symbol: SymbolHit? = null,
)

/** 触发补全的字符。 */
internal enum class Trigger(val char: Char) {
    Command('/'),
    File('@'),
    Symbol('#'),
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
 * 三条触发规则（设计稿 §3.1；`#` 见 2026-09-15 那份符号引用设计稿 §1.2）：
 *  - `/` **只在消息开头**。`C:/Users`、`1/2`、`and/or` 里都有斜杠，
 *    任何位置都弹的话正常打字会被一直打断。
 *  - `@` **只在词边界**。`foo@bar.com` 的 `@` 前面是字母，不算。
 *  - `#` **与 `@` 同一条规则**：`a#b`（C 的前缀、标签、URL 片段）不该弹。
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

    // 符号：与 `@` 同一套边界规则。排在 `@` 之后 —— 畸形输入（`#Foo@bar`）
    // 由先判的那个吃掉，与 `@` 今天的行为一致，不另立一套优先级。
    val hashIndex = before.lastIndexOf('#')
    if (hashIndex >= 0) {
        val boundary = hashIndex == 0 || before[hashIndex - 1].isWhitespace()
        val body = before.substring(hashIndex + 1)
        if (boundary && body.none { it.isWhitespace() }) {
            return CompletionQuery(Trigger.Symbol, body, hashIndex)
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
    return items.mapNotNull { item ->
        when {
            // 命中显示名：连高亮一起给（这一行的字就是 display）
            item.display.lowercase().startsWith(q) ->
                item.copy(hits = (0 until q.length).toList())
            // 命中插入文本或别名：留下，但**不高亮** —— 下标在 display 上
            // 对不上位置，标错比不标糟
            item.insert.lowercase().startsWith(q) || item.aliases.any { it.lowercase().startsWith(q) } ->
                item
            else -> null
        }
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
 *
 * ## 修饰键也算数（2026-09-23 用户报「Shift+Enter 不换行」）
 *
 * 从前这里**只看 keyCode**：`VK_ENTER` 一律给 [CompletionKey.Accept]。于是弹层开着时
 * Shift+Enter 被当成"采纳候选"吃掉（[ClaudePanel] 那条分支是 `e.consume(); return`），
 * 而 [isSendKey] 上写得明明白白 —— *"Shift+Enter 在两种约定下都留给换行"*。
 * 那句话**根本没机会执行到**：键在更前面就被截了。
 *
 * 所以两个带 Shift 的组合各有归处：
 * - **Shift+Enter 归文本**（[CompletionKey.Ignore]，落回输入框去换行）—— 这是那条
 *   规矩欠的执行；
 * - **Shift+Tab 给 [CompletionKey.Up]**：它在补全弹层里的通用含义就是"上一项"，
 *   从前和 Tab 一样被当成采纳，那是误伤。
 */
internal fun completionKey(keyCode: Int, shiftDown: Boolean = false): CompletionKey = when {
    keyCode == KeyEvent.VK_UP -> CompletionKey.Up
    keyCode == KeyEvent.VK_DOWN -> CompletionKey.Down
    keyCode == KeyEvent.VK_ESCAPE -> CompletionKey.Dismiss
    shiftDown && keyCode == KeyEvent.VK_TAB -> CompletionKey.Up
    // 其余带 Shift 的一律不接管（Shift+Enter 就在这儿落回文本）
    shiftDown -> CompletionKey.Ignore
    keyCode == KeyEvent.VK_ENTER || keyCode == KeyEvent.VK_TAB -> CompletionKey.Accept
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
 *
 * 符号引用走 [CompletionItem.verbatim]：写进去的是**整行记号**（`⟦名字 · 路径 12-18 · 7 行⟧`），
 * 没有"触发字符"这一回事，也不该多一个尾随空格（记号与后面的字之间由用户自己决定要不要空格）。
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
    val written = if (item.verbatim) {
        // 预置 prompt 这一类：写进去的是整段文本，没有"触发字符"这回事
        item.insert
    } else {
        q.trigger.char + item.insert + if (q.trigger == Trigger.File) " " else ""
    }
    return (head + written + tail) to (head.length + written.length)
}
