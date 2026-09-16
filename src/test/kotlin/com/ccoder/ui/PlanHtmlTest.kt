package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 计划（Markdown）→ Swing 的 HTML。
 *
 * 钉三样：记号**变成样式**、该转义的**一定转义**、写漏的记号**不能吞字**。
 * 最后一条最要紧 —— 这一屏是给人审批用的：排版难看只是难看，少一段字是
 * "批准了自己没看见的东西"。
 */
class PlanHtmlTest {

    private val accent = "#548af7"
    private val dim = "#8b8f97"

    private fun html(markdown: String) = planHtml(markdown, accent, dim)

    @Test
    fun `一级标题加粗放大，后面压一道分隔线`() {
        val out = html("# 一键收获")

        assertTrue(out.startsWith("<b>"), out)
        assertTrue(out.contains("<hr>"), out)
        assertTrue(out.contains("一键收获"), out)
    }

    @Test
    fun `小节标题变成色条加小号大写`() {
        val out = html("## Context")

        assertTrue(out.contains("▌"), "色条没了：$out")
        assertTrue(out.contains("CONTEXT"), "该转成大写：$out")
        assertFalse(out.contains("##"), "记号该被吃掉：$out")
    }

    @Test
    fun `粗体与行内代码变成标签，记号本身不留在屏幕上`() {
        val out = html("把 **自己农场** 的 `landSID` 收掉")

        assertTrue(out.contains("<b>自己农场</b>"), out)
        assertTrue(out.contains("<code>"), out)
        assertTrue(out.contains("landSID"), out)
        assertFalse(out.contains("**"), out)
        assertFalse(out.contains("`"), out)
    }

    @Test
    fun `围栏代码块原样，换行也不动`() {
        val out = html("```\nval a = 1\nval b = 2\n```")

        assertTrue(out.contains("<pre>"), out)
        assertTrue(out.contains("val a = 1\nval b = 2"), out)
    }

    @Test
    fun `两种列表都认`() {
        val bullets = html("- 甲\n- 乙")
        val ordered = html("1. 甲\n2. 乙")

        assertTrue(bullets.contains("<ul>"), bullets)
        assertTrue(bullets.contains("<li>甲</li>"), bullets)
        assertTrue(ordered.contains("<ol>"), ordered)
        assertTrue(ordered.contains("<li>甲</li>"), ordered)
    }

    @Test
    fun `分隔线`() {
        assertTrue(html("上\n\n---\n\n下").contains("<hr>"))
    }

    @Test
    fun `尖括号与和号一律转义 —— 计划里全是泛型和箭头`() {
        val out = html("`List<Int>` 与 a && b 以及 -> 箭头")

        assertTrue(out.contains("List&lt;Int&gt;"), out)
        assertTrue(out.contains("&amp;&amp;"), out)
        assertFalse(out.contains("<Int>"), "标签没转义：$out")
    }

    @Test
    fun `连续几行合成一段`() {
        val out = html("第一行\n第二行")

        assertEquals(1, Regex("<p>").findAll(out).count(), out)
        assertTrue(out.contains("第一行 第二行"), out)
    }

    @Test
    fun `空行分段`() {
        val out = html("甲\n\n乙")

        assertEquals(2, Regex("<p>").findAll(out).count(), out)
    }

    @Test
    fun `围栏没闭合也不吞字 —— 写漏的是模型，少字要命的用户`() {
        val out = html("```\ncode\n\n## 后面一小节")

        assertTrue(out.contains("后面一小节"), "内容被吞了：$out")
    }
}
