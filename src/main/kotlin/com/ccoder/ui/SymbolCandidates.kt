package com.ccoder.ui

/**
 * 一个解析完成的符号 —— 把它内联出去所需的**全部数据**，且全是纯数据。
 *
 * 为什么是快照而不是直接搬 PSI 元素：解析必须在读操作里做（见 [SymbolLookup]），
 * 而 PSI 元素既不能带出读操作、也不能跨线程带着走。出口只有这一份快照，
 * 后面（排序、记号、展开）就都是纯逻辑 —— 也就是能写单测的那一半。
 */
internal data class SymbolHit(
    val name: String,
    /** 项目相对路径（`/` 分隔）。**同名符号靠它区分** —— 记号是按文本作键的。 */
    val path: String,
    /** 1 起、含两端。 */
    val lines: IntRange,
    /** 元素源码，展开时原样发给模型。 */
    val code: String,
    /** 文件类型名（`Kotlin` / `Python`），围栏标签用它；不认识时给 null。 */
    val fileTypeName: String?,
)

/**
 * 解析上限 = 弹层能显示的行数。
 *
 * 多解析出来的候选**永远看不见**（弹层不可滚动，见 [COMPLETION_MAX_ROWS]），
 * 白花一次 PSI 加载的钱 —— 而解析是这条路上最贵的一步。
 */
internal const val SYMBOL_LIMIT = COMPLETION_MAX_ROWS

/**
 * 输入框里那一行记号。
 *
 * 名字放在**最前**：用户在输入框里要一眼看得见引用了谁（选区的记号以路径打头，
 * 那是因为选区没有名字）。
 *
 * **路径必须在里面**：`SnippetRefs` 按 token 文本作键，`src/a/Foo.py` 与
 * `test/b/Foo.py` 里的同名符号若记成同一个 token，后记的那份会覆盖前一份
 * （见 `ComposerReferences.kt` 头注释里那条教训）。
 */
internal fun symbolToken(hit: SymbolHit): String =
    "$REF_OPEN${hit.name} · ${hit.path} ${lineRangeText(hit.lines)} · ${hit.lines.count()} 行$REF_CLOSE"

/**
 * 发送时**真正发出去**的那段：与选区**同一个形状**（[formatSnippet]），不另造一种。
 *
 * 模型收到的东西因此与"用户自己选中这个符号再右键"完全一致 —— 两条路只在
 * 怎么选上有区别，发出去的东西没有区别。
 */
internal fun symbolSnippet(hit: SymbolHit): String =
    formatSnippet(hit.path, hit.lines, hit.fileTypeName, hit.code)

/**
 * 名字的去重、排序、截断。三条排序规则，按优先级：
 *
 *  1. **完全同名最前** —— 打 `#Foo` 时 `Foo` 通常就是要的那个；
 *  2. **短名优先** —— 同前缀的一串里，短的更可能是本意（`Filter` 先于 `FilterChainFactory`）；
 *  3. 同长度按字母序 —— 只为稳定，不承载别的意思。
 *
 * **前缀过滤在这里做，不假设平台已经滤过**：平台那个 `processNames` 究竟是
 * 索引级收窄还是全量吐名字，在实测之前没人知道（设计稿 §1.7 的探针就是去量这个的）。
 * 多滤一遍是幂等的；漏滤一遍则会在弹层里出现与输入毫无关系的名字。
 *
 * 空查询返回空：光打一个 `#` 不该闪一屏候选出来（与 `@` 同一条规则）。
 */
internal fun rankSymbolHits(
    names: List<String>,
    query: String,
    limit: Int = SYMBOL_LIMIT,
): List<String> {
    val q = query.lowercase()
    if (q.isEmpty()) return emptyList()
    return names.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase().startsWith(q) }
        .distinct()
        .sortedWith(
            compareBy(
                { if (it.equals(query, ignoreCase = true)) 0 else 1 },
                { it.length },
                { it.lowercase() },
            ),
        )
        .take(limit)
        .toList()
}

/**
 * 解析结果 → 补全候选。
 *
 * `display` 是名字、`description` 是路径（弹层那一行就是 `名字  ·  路径`，走现成的
 * [completionRowText]）—— 重名符号因此分得开，用户采纳前就看得见选的是哪一个。
 *
 * **verbatim**：写进去的是记号本身，不加触发字符、也不加尾随空格。
 * 不分组：单一来源不需要组头。
 */
internal fun symbolCandidates(hits: List<SymbolHit>): List<CompletionItem> =
    hits.map { hit ->
        CompletionItem(
            display = hit.name,
            insert = symbolToken(hit),
            // 整条路径原样带着：**塞不塞得下是绘制层的事**（那里有真实字体可以量）。
            // 在这里按字符数预截过一版，探针图上证明是错的：等宽与比例字体下，
            // 同样的字符数宽度能差一半，结果仍然被容器从尾部裁掉一截
            description = hit.path,
            verbatim = true,
            symbol = hit,
        )
    }
