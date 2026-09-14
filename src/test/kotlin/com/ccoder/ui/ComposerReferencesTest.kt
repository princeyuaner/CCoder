package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 片段记号与它的展开。
 *
 * 这一层是"发送的那一刻把记号换成代码"的全部逻辑。它必须与 Swing 无关 ——
 * 换错一个字节的代价是"模型看到的是另一段代码"，而那种错在界面上看不出来。
 */
class ComposerReferencesTest {

    private val snippet = "src/main/kotlin/com/ccoder/ui/Composer.kt:24-27\n\n```kotlin\nval x = 1\n```"

    private fun refs(vararg pairs: Pair<String, String>) = SnippetRefs().apply {
        pairs.forEach { (token, text) -> remember(token, text) }
    }

    // ---- 记号长什么样 ----

    @Test
    fun `记号里带着路径、行范围与行数`() {
        val token = refToken("src/main/kotlin/com/ccoder/ui/Composer.kt", 24..27)

        assertEquals("⟦src/main/kotlin/com/ccoder/ui/Composer.kt 24-27 · 4 行⟧", token)
    }

    @Test
    fun `单行不写区间`() {
        assertTrue(refToken("a/B.kt", 24..24).contains(" 24 · 1 行"))
    }

    @Test
    fun `记号里带完整相对路径，不是文件名`() {
        // 只写文件名的话，`src/a/X.kt` 与 `test/b/X.kt` 会长得一模一样 ——
        // 而展开表是按记号文本索引的，那两份快照就会互相顶掉、喂错内容
        val a = refToken("src/a/X.kt", 1..2)
        val b = refToken("test/b/X.kt", 1..2)

        assertFalse(a == b, "同名的两个文件必须区分得开：$a / $b")
    }

    // ---- 展开 ----

    @Test
    fun `发送时记号被换成完整片段`() {
        val token = refToken("a/B.kt", 24..27)
        val text = "$token 这两块能不能合并？"

        assertEquals("$snippet 这两块能不能合并？", refs(token to snippet).expand(text))
    }

    @Test
    fun `一段话里两个记号各自展开`() {
        val t1 = refToken("a/B.kt", 1..2)
        val t2 = refToken("c/D.kt", 9..9)
        val s1 = "a/B.kt:1-2\n\n```kotlin\none\n```"
        val s2 = "c/D.kt:9\n\n```kotlin\ntwo\n```"

        val out = refs(t1 to s1, t2 to s2).expand("$t1 和 $t2 对不对？")

        assertEquals("$s1 和 $s2 对不对？", out)
    }

    @Test
    fun `表里没有的记号原样留着`() {
        // 手打的、或者会话里已经不认得的记号：不动它。
        // 拿不准就什么都别做 —— 把用户写的东西原封不动发出去，比替换成空强
        val text = "看看 ⟦手打的 1-2 · 2 行⟧ 这个"

        assertEquals(text, SnippetRefs().expand(text))
    }

    @Test
    fun `文本被改过（记号前后打字、删字）照样对得上`() {
        // 展开按**记号文本**索引，不按位置 —— 所以"用户在开头补了一句话"
        // 这种最常见的事不可能把展开弄错位
        val token = refToken("a/B.kt", 1..2)
        val r = refs(token to snippet)

        assertEquals("先问一句：$snippet 就这样", r.expand("先问一句：$token 就这样"))
    }

    @Test
    fun `记号被删掉就等于没加过`() {
        val token = refToken("a/B.kt", 1..2)
        val r = refs(token to snippet)

        assertEquals("就只有这句话", r.expand("就只有这句话"))
    }

    @Test
    fun `没有记号时原样返回，不做无谓的字符串处理`() {
        val text = "普通的一句话，没有记号"
        assertEquals(text, refs(refToken("a/B.kt", 1..2) to snippet).expand(text))
    }

    @Test
    fun `发送之后清空表，下一轮不会误展开`() {
        // 输入框在发送时被清空，记号也就不在文本里了；表跟着清掉，
        // 免得它随会话越攒越大
        val token = refToken("a/B.kt", 1..2)
        val r = refs(token to snippet)
        r.clear()

        assertEquals(token, r.expand(token))
    }

    // ---- 着色用的范围 ----

    @Test
    fun `记号范围是闭区间，切出来正好是那个记号`() {
        val token = refToken("a/B.kt", 1..2)
        val text = "看 $token 这个"

        val range = refRanges(text).single()

        assertEquals(token, text.substring(range.first, range.last + 1))
    }

    @Test
    fun `一段话里两个记号就有两段范围`() {
        val t1 = refToken("a/B.kt", 1..2)
        val t2 = refToken("c/D.kt", 9..9)

        assertEquals(2, refRanges("$t1 和 $t2").size)
    }

    @Test
    fun `没有记号就没有范围`() {
        assertTrue(refRanges("普通的一句话").isEmpty())
    }

    @Test
    fun `文件引用与补全插进去的是同一个形状`() {
        // 两处形状必须一致：CLI 靠 `@` 认它（实测纯路径会被展开成文件内容）。
        // 形状一旦分叉，右键加进来的那个就成了普通文字
        assertEquals("@sidecar/session.js ", fileMention("sidecar/session.js"))
        assertTrue(fileMention("a/B.kt").endsWith(" "), "尾随空格要留 —— 接着就要打字了")
    }

    @Test
    fun `片段里的美元符号不会被当成替换模板`() {
        // Regex.replace 的替换串里 `$` 有特殊含义。这段代码里出现美元符号
        // 太正常了（Kotlin 字符串模板），用函数式替换就是为了绕开它
        val token = refToken("a/B.kt", 1..2)
        val withDollar = "a/B.kt:1-2\n\n```kotlin\nval s = \"\$name\"\n```"

        assertEquals(withDollar, refs(token to withDollar).expand(token))
    }
}
