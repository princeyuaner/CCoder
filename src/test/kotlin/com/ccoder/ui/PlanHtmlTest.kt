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

    // ---- 表格（2026-09-17 用户截图："计划里这个展示的格式很难看"）----

    /**
     * 用户那张表**原样**搬进来：三列、带 `code`、还有一格是 `ResetCondition`。
     *
     * 改之前它被拍成流水文字 —— `| 分类 | 配表判定 |` 与 `|---|---|---|` 铺在脸上。
     */
    private val userTable = """
        | 分类 | 配表判定 | 注册键 | 重置时机 |
        | --- | --- | --- | --- |
        | 不重置 | 两个 bool 都 False | `mutantFarmTarget_never` | 永不 |
        | 每日 | isDailyCondition=True | `mutantFarmTarget_daily` | 跨天 |
        | 每周 | isWeekCondition=True | `mutantFarmTarget_weekly` | 跨周 |
    """.trimIndent()

    @Test
    fun `表格变成真表格 —— 竖线与分隔行不再铺在脸上`() {
        val out = html(userTable)

        assertTrue(out.contains("<table"), "没有 table 标签：$out")
        assertTrue(out.contains("</table>"), out)
        // 表头进 th，数据进 td；四列各一格
        assertTrue(out.contains("<th>分类"), out)
        assertTrue(out.contains("<td>不重置"), out)
        assertFalse(out.contains("|"), "竖线还在屏幕上：$out")
        assertFalse(out.contains("---"), "分隔行还在屏幕上：$out")
        // 三行数据都得在，一格不许少
        for (cell in listOf("永不", "跨天", "跨周")) {
            assertTrue(out.contains(cell), "少了 $cell：$out")
        }
    }

    @Test
    fun `单元格里的记号照样解析，照样先转义`() {
        val out = html(
            """
            | 键 | 说明 |
            | --- | --- |
            | `a<b` | **必须**转义 |
            """.trimIndent(),
        )

        assertTrue(out.contains("<code>"), "行内代码没解析：$out")
        assertTrue(out.contains("&lt;"), "尖括号没转义：$out")
        assertTrue(out.contains("<b>必须</b>"), "粗体没解析：$out")
    }

    @Test
    fun `分隔行里的冒号变成对齐`() {
        val out = html(
            """
            | 左 | 中 | 右 |
            | :-- | :-: | --: |
            | 1 | 2 | 3 |
            """.trimIndent(),
        )

        assertTrue(out.contains("align=\"left\""), out)
        assertTrue(out.contains("align=\"center\""), out)
        assertTrue(out.contains("align=\"right\""), out)
    }

    @Test
    fun `没有分隔行就不是表格 —— 一行里带竖线照样当文字`() {
        // 宁可漏认也不能把普通文字吃成表格：这一屏是给人审批用的
        val out = html("用 a | b 表示二选一")

        assertFalse(out.contains("<table"), "把普通文字吃成表格了：$out")
        assertTrue(out.contains("a | b"), "竖线该原样留着：$out")
    }

    @Test
    fun `只有一格的竖线不算表格行`() {
        // `|` 一条线的"表格"没有意义；真正的表格至少两列
        val out = html("| 孤零零一条 |\n| --- |")

        assertFalse(out.contains("<table"), "一格也当表格了：$out")
    }

    @Test
    fun `表格打断段落，后面接着的段落各自成段`() {
        val out = html("前面一句\n$userTable\n后面一句")

        assertTrue(out.contains("<p>前面一句</p>"), out)
        assertTrue(out.contains("<p>后面一句</p>"), out)
        assertTrue(out.indexOf("<table") > out.indexOf("前面一句"), "顺序错了：$out")
        assertTrue(out.indexOf("后面一句") > out.indexOf("</table>"), "顺序错了：$out")
    }

    @Test
    fun `表格中间隔一行就结束了 —— 不会把后面的文字吞进去`() {
        val out = html("| a | b |\n| --- | --- |\n| 1 | 2 |\n\n这是一段普通文字")

        assertTrue(out.contains("<td>1"), out)
        // 空行之后那句要**在表格外面**：表格里只有两行数据，普通文字得另起一段
        assertFalse(out.substringBefore("</table>").contains("普通文字"), "被吞进表格了：$out")
        assertTrue(out.substringAfter("</table>").startsWith("<p>"), "表格后面没成独立段落：$out")
    }

    @Test
    fun `转义的竖线留在格子里，不当分隔`() {
        val out = html("| 记号 | 意思 |\n| --- | --- |\n| \\| | 竖线本身 |")

        assertTrue(out.contains("|</td>") || out.contains("|"), out)
        // 两列两格，不是三格：`\|` 不是分隔符
        assertEquals(4, Regex("<t[hd]>").findAll(out).count(), "格数不对（转义没生效）：$out")
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
