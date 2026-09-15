package com.ccoder.ui

/**
 * 输入框的 ↑/↓ 历史。行为照抄 shell：**正在写的那句不会被吞掉**。
 *
 * 第一次按 ↑ 时把当前内容存成"草稿"，随后在历史里往回走；↓ 一路走回来，
 * 走到底就把草稿还回输入框。中途改动过的那一份也会被下一次 ↑ 存成新草稿 ——
 * 所以"翻上去 → 改两个字 → 翻下来"拿回来的是**改过的**那份，不是最初那份。
 *
 * ## 为什么按"文本"记，不按位置
 *
 * 与 [SnippetRefs] 同一个理由：用户会在记号前后打字、粘贴、删字，位置早就不对了。
 *
 * ## 记号（`⟦…⟧`）在历史里是一条已知的缝
 *
 * 记的是**用户敲进去的那份**（`typed`），不是展开后的全文 —— 后者可能是几百行
 * 代码，塞回输入框就没法用了。代价：如果那句里有片段记号，而记号对应的片段在
 * 发送时已经从表里清掉（见 `ClaudePanel.sendCurrentInput`），翻出来的记号再发一次
 * 就只是一行字面文本。这一条**留在这儿说明**，因为它只影响"重发带记号的旧消息"
 * 这一种用法。
 *
 * 全部在 EDT 上用，没有同步。历史只在内存里（面板关掉就没了）——
 * 跨重启的那份该由设置来管，不该顺手混进这里。
 */
internal class InputHistory(private val limit: Int = HISTORY_LIMIT) {

    /** 旧 → 新。 */
    private val entries = ArrayDeque<String>()

    /** 正在历史里走时，脚下那条的下标；null = 没在翻（手里是用户自己的草稿）。 */
    private var cursor: Int? = null

    /** 开始翻之前输入框里的那份（↓ 走到底要还回去的就是它）。 */
    private var draft: String = ""

    /**
     * 记一条刚发出去的。[text] 是**用户敲的那份**（`typed`），不是展开后的全文。
     *
     * 连着发两条一样的只留一条（shell 也是这个规矩）—— 通常是手抖按了两下回车，
     * 留着它只会让后面的 ↑ 多按一次。
     */
    fun remember(text: String) {
        cursor = null
        draft = ""
        if (text.isBlank()) return
        if (entries.lastOrNull() == text) return
        entries.addLast(text)
        while (entries.size > limit) entries.removeFirst()
    }

    /**
     * ↑。返回该放进输入框的文本；**null = 没有历史可翻**（调用方别消费这个键，
     * 让光标照常往上走）。
     *
     * [current] 是输入框此刻的内容 —— 第一次翻时它变成草稿。
     */
    fun prev(current: String): String? {
        if (entries.isEmpty()) return null
        val at = cursor
        if (at == null) {
            draft = current
            val newest = entries.size - 1
            cursor = newest
            return entries[newest]
        }
        // 翻历史的过程中输入框里原本是上一条回填的文本；**不一样就说明用户改过**，
        // 那份改动才是他按 ↓ 想拿回来的东西 —— 存它，而不是最初那份草稿
        if (current != entries[at]) draft = current
        val older = (at - 1).coerceAtLeast(0)
        cursor = older
        // 已经到最旧的一条：停在那儿（再按还是它，与 shell 一致），
        // 而不是绕回最新的
        return entries[older]
    }

    /**
     * ↓。往回走；走出最新一条就把草稿还回去。
     *
     * null = 本来就没在翻历史（那一下该是正常的光标下移）。
     */
    fun next(): String? {
        val at = cursor ?: return null
        val newer = at + 1
        if (newer >= entries.size) {
            cursor = null
            return draft
        }
        cursor = newer
        return entries[newer]
    }

    internal companion object {
        /** 留多少条。50 条足够覆盖"刚才那句"这一档需求，又不至于常驻一大坨文本。 */
        const val HISTORY_LIMIT = 50
    }
}

/**
 * 光标是不是在**首行**（↑ 该不该翻历史）。
 *
 * 光标前面还有换行 = 它在第二行或更下面 —— 那时 ↑ 是正常的光标上移。
 */
internal fun onFirstLine(text: String, caret: Int): Boolean =
    text.take(caret.coerceIn(0, text.length)).indexOf('\n') < 0

/** 光标是不是在**末行**（↓ 该不该往回翻）。 */
internal fun onLastLine(text: String, caret: Int): Boolean =
    text.drop(caret.coerceIn(0, text.length)).indexOf('\n') < 0
