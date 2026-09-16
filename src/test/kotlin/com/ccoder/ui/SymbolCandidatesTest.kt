package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 符号引用的纯逻辑：记号的形状、候选的排序与上限、展开的形状。
 *
 * 抽出来的理由同 [CompletionTest]：真机上这一路要 IDE 的符号索引（起不了单测），
 * 而"记号长什么样、同名怎么区分、前 8 个是谁"正是最容易写错的地方。
 */
class SymbolCandidatesTest {

    private fun hit(
        name: String = "Foo.bar",
        path: String = "src/a/Foo.kt",
        lines: IntRange = 12..18,
        code: String = "fun bar() {}",
        fileTypeName: String? = "Kotlin",
    ) = SymbolHit(name, path, lines, code, fileTypeName)

    // ---- 记号 ----

    @Test
    fun `记号：名字在前，路径与行数在后`() {
        assertEquals("⟦Foo.bar · src/a/Foo.kt 12-18 · 7 行⟧", symbolToken(hit()))
    }

    @Test
    fun `记号：单行不写成 12-12`() {
        assertEquals("⟦bar · src/a/Foo.kt 12 · 1 行⟧", symbolToken(hit(name = "bar", lines = 12..12)))
    }

    @Test
    fun `记号：同名但不同文件必须区分得开`() {
        val a = symbolToken(hit(path = "src/a/Foo.kt"))
        val b = symbolToken(hit(path = "test/b/Foo.kt"))
        assertNotEquals(a, b, "SnippetRefs 按 token 文本作键，撞上了就会互相覆盖：$a / $b")
    }

    // ---- 展开 ----

    @Test
    fun `展开：与选区同一个形状`() {
        assertEquals(
            "src/a/Foo.kt:12-18\n\n```kotlin\nfun bar() {}\n```",
            symbolSnippet(hit()),
        )
    }

    @Test
    fun `展开：内部类型名不当语言标签`() {
        val snippet = symbolSnippet(hit(fileTypeName = "PLAIN_TEXT"))
        assertTrue(snippet.contains("\n```\n"), "带下划线的内部类型名应退化成无标签围栏：$snippet")
    }

    // ---- 长路径（塞不下时的处理在绘制层，见 CompletionPopupTest）----

    @Test
    fun `路径原样带着，一个字都不预截`() {
        // 预截过一版（按字符数估预算），探针图上证明是错的：比例字体下
        // 同样的字符数宽度能差一半，照样被容器从尾部裁掉。量宽度是绘制层的事
        val long = "src/main/kotlin/com/ccoder/ui/SymbolCandidates.kt"

        assertEquals(long, symbolCandidates(listOf(hit(path = long))).single().description)
    }

    // ---- 排序 ----

    @Test
    fun `排序：完全同名最前，短名其次，最后才是字母序`() {
        val ranked = rankSymbolHits(
            listOf("FilterChainFactory", "Filter", "filter", "FilterChain"),
            "Filter",
            limit = 10,
        )
        assertEquals(listOf("Filter", "filter", "FilterChain", "FilterChainFactory"), ranked)
    }

    @Test
    fun `排序：同名的只留一条`() {
        assertEquals(listOf("Foo"), rankSymbolHits(listOf("Foo", "Foo", "Foo"), "F", limit = 10))
    }

    @Test
    fun `子序列也能进候选 —— cmprk 找得到 ComposerMode`() {
        // 2026-09-16：原先只收前缀命中，`#cmprk` 一条都出不来。
        // 判据换成与文件补全共用的那份（见 [fuzzyMatch]）。
        // 名字里得真有那个 `k`（`ComposerMode` 没有，`.kt` 那个有）
        assertEquals(
            listOf("ComposerMode.kt"),
            rankSymbolHits(listOf("ComposerMode.kt", "CommandCandidates"), "cmprk", limit = 10),
        )
    }

    @Test
    fun `候选带上命中的下标，给弹层加粗`() {
        // C0 o1 m2 p3 o4 s5 e6 r7 M8 o9 d10 e11 .12 k13 t14
        val items = symbolCandidates(listOf(hit(name = "ComposerMode.kt")), "cmprk")

        assertEquals(listOf(0, 2, 3, 7, 13), items.single().hits)
    }

    @Test
    fun `排序：不匹配的丢掉，子序列也算匹配`() {
        // 2026-09-16 改：原先**非前缀一律丢**（`barFoo` 进不来），现在按子序列收。
        // 前半句钉的仍是"不匹配就是不要"，后半句钉新的那半条
        assertEquals(emptyList<String>(), rankSymbolHits(listOf("barFoo", "xFoo", "Whoops"), "zzz", limit = 10))
        assertEquals(listOf("xFoo", "barFoo"), rankSymbolHits(listOf("xFoo", "barFoo"), "Foo", limit = 10))
    }

    @Test
    fun `排序：上限就是弹层的行数 —— 多解析出来的永远看不见`() {
        val names = ('a'..'z').map { "sym$it" }
        assertEquals(SYMBOL_LIMIT, rankSymbolHits(names, "sym", limit = SYMBOL_LIMIT).size)
    }

    @Test
    fun `排序：空查询不弹任何候选 —— 光打一个井号不该闪一屏`() {
        assertEquals(emptyList<String>(), rankSymbolHits(listOf("Foo", "Bar"), "", limit = 10))
    }

    // ---- 候选 ----

    @Test
    fun `候选：名字与路径同框，插入的是记号而不是名字`() {
        val item = symbolCandidates(listOf(hit(name = "Foo.bar", path = "src/a/Foo.kt"))).single()
        assertEquals("Foo.bar", item.display)
        assertEquals("src/a/Foo.kt", item.description)
        assertEquals("⟦Foo.bar · src/a/Foo.kt 12-18 · 7 行⟧", item.insert)
        assertTrue(item.verbatim, "记号是整段文本，不该再加触发字符或尾随空格")
        assertNull(item.group, "单一来源不分组")
    }

    @Test
    fun `候选：解析结果跟着候选走 —— 采纳时不必再解析一遍`() {
        val source = hit()
        assertEquals(source, symbolCandidates(listOf(source)).single().symbol)
    }

    @Test
    fun `候选：采纳写进输入框的就是那个记号本身`() {
        val item = symbolCandidates(listOf(hit())).single()
        val (text, caret) = applyCompletion("看看 ", 3, CompletionQuery(Trigger.Symbol, "", 3), item)
        assertEquals("看看 " + item.insert, text)
        assertEquals(text.length, caret)
    }
}
