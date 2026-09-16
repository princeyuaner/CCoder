package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import javax.swing.JLabel

/**
 * 弹层。测属性与位置计算 —— 好不好看交给 [CompletionRenderProbe]。
 */
class CompletionPopupTest {

    private val builtin = CompletionItem("compact", "compact", "压缩上下文", group = GROUP_OTHER)
    private val skill = CompletionItem("brainstorming", "brainstorming", "想清楚", group = GROUP_PLUGIN)

    private fun labelsOf(c: Component): List<JLabel> = buildList {
        if (c is JLabel) add(c)
        if (c is Container) c.components.forEach { addAll(labelsOf(it)) }
    }

    private fun textsOf(c: Component): List<String> = labelsOf(c).map { it.text }

    /** 去掉分组标题，剩下的就是候选行。 */
    private fun rowsOf(c: Component): List<JLabel> =
        labelsOf(c).filter { it.text != GROUP_OTHER && it.text != GROUP_PLUGIN }

    @Test
    fun `弹层不可聚焦 —— 焦点跑掉的话接下来的字符就进了弹层`() {
        val list = buildCompletionList(listOf(builtin, skill), selected = 0)
        assertFalse(list.isFocusable, "内容组件不可聚焦是这套配置的机制本身")
    }

    @Test
    fun `分组标题只在换组时出现一次`() {
        val two = buildCompletionList(listOf(builtin, skill), selected = 0)
        assertEquals(1, textsOf(two).count { it == GROUP_OTHER })
        assertEquals(1, textsOf(two).count { it == GROUP_PLUGIN })
    }

    @Test
    fun `同组连续多项只画一个标题`() {
        val a = CompletionItem("a", "a", group = GROUP_OTHER)
        val b = CompletionItem("b", "b", group = GROUP_OTHER)
        val list = buildCompletionList(listOf(a, b), selected = 0)
        assertEquals(1, textsOf(list).count { it == GROUP_OTHER })
    }

    @Test
    fun `没有分组的候选不画标题`() {
        val list = buildCompletionList(listOf(CompletionItem("x", "x")), selected = 0)
        assertFalse(textsOf(list).contains(GROUP_OTHER))
        assertFalse(textsOf(list).contains(GROUP_PLUGIN))
    }

    // ---- 塞不下时怎么让位（只有符号行是两段）----

    private fun metrics(): FontMetrics =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().getFontMetrics(JLabel().font)

    private fun symbolRow(name: String, path: String) =
        symbolCandidates(listOf(SymbolHit(name, path, 1..2, "x", "Python"))).single()

    @Test
    fun `放得下就一个字不动`() {
        val row = symbolRow("parse", "a/b.py")

        assertEquals(completionRowText(row), rowTextFor(row, metrics(), maxWidth = 1000))
    }

    @Test
    fun `放不下时先让路径，留下的是文件名那一头`() {
        // 探针图上坏过：容器从尾部裁，长名字那行被裁成 `tests/ap…` —— 文件名没了
        val row = symbolRow("parse", "src/app/parse/streaming/reader.py")

        val text = rowTextFor(row, metrics(), maxWidth = 200)

        assertTrue(text.startsWith("parse$ROW_SEPARATOR"), "名字一个字都不该动：$text")
        assertTrue(text.endsWith("reader.py"), "文件名必须留着：$text")
        assertTrue(metrics().stringWidth(text) <= 200, "让完还得放得下：$text")
    }

    @Test
    fun `名字太长挤到文件名时，砍的是名字`() {
        // 探针图上先坏成 `…/_test.py`（把文件名切了一半）—— 名字截一半还认得出，
        // 文件名没了就什么都不知道了
        val row = symbolRow("parseWithConfigAndOverridesForTests", "tests/app/parse_test.py")

        val text = rowTextFor(row, metrics(), maxWidth = 200)

        assertTrue(metrics().stringWidth(text) <= 200, "还是得放得下：$text")
        assertTrue(text.endsWith("parse_test.py"), "文件名必须完整：$text")
        val keptName = text.substringBefore("…").removeSuffix(ROW_SEPARATOR.trimEnd()).trimEnd()
        assertTrue(keptName.isNotEmpty() && row.display.startsWith(keptName), "该在名字上收口：$text")
    }

    @Test
    fun `窄到放不下任何名字时，也不留空白`() {
        val row = symbolRow("parseWithConfigAndOverridesForTests", "tests/app/parse_test.py")

        assertTrue(rowTextFor(row, metrics(), maxWidth = 12).isNotEmpty())
    }

    @Test
    fun `非符号行不动它 —— 另外三组的观感是另一回事`() {
        val item = CompletionItem("src/main/kotlin/A.kt", "src/main/kotlin/A.kt")

        assertEquals(completionRowText(item), rowTextFor(item, metrics(), maxWidth = 20))
    }

    // ---- 状态行（符号那条路：正在搜 / 搜不成）----

    @Test
    fun `一个候选都没有时，状态行才出现`() {
        val list = buildCompletionList(emptyList(), selected = 0, status = "正在搜索符号…")

        assertEquals(listOf("正在搜索符号…"), textsOf(list))
    }

    @Test
    fun `有候选时不挂状态行 —— 那时它是噪音`() {
        val list = buildCompletionList(listOf(builtin), selected = 0, status = "正在搜索符号…")

        assertFalse(textsOf(list).contains("正在搜索符号…"))
        assertEquals(1, rowsOf(list).size)
    }

    @Test
    fun `不传状态时一行都不多`() {
        assertEquals(emptyList<String>(), textsOf(buildCompletionList(emptyList(), selected = 0)))
    }

    @Test
    fun `候选上限落在候选层，不是绘制层`() {
        val many = (1..20).map { CompletionItem("c$it", "c$it") }

        assertEquals(COMPLETION_MAX_ROWS, visibleCandidates(many).size)
        assertEquals("c1", visibleCandidates(many).first().display)
        assertEquals(3, visibleCandidates(many.take(3)).size, "不足上限时原样返回")
    }

    @Test
    fun `弹层高度取内容的真实首选高，末一行不会被裁掉`() {
        val many = (1..3).map { CompletionItem("c$it", "c$it", group = GROUP_OTHER) }
        val list = buildCompletionList(many, selected = 0)
        val column = (list as java.awt.Container).components.single()

        assertEquals(
            column.preferredSize.height,
            list.preferredSize.height,
            "自己按行数估高度会漏掉分组标题与字体行高，末一行就没了",
        )
    }

    @Test
    fun `长描述不会把弹层撑宽 —— 截断是造候选那层的职责`() {
        val long = CompletionItem("x", "x", "字".repeat(500))
        val list = buildCompletionList(listOf(long), selected = 0)

        // 弹层原样渲染给它的东西（截断由 describeCommand 那条 oneLine 负责，
        // 见 CommandCandidatesTest），但宽度是定死的
        assertEquals(completionRowText(long), labelsOf(list).single { it.text.contains("字") }.text)
        assertEquals(com.intellij.util.ui.JBUI.scale(COMPLETION_WIDTH), list.preferredSize.width)
    }

    @Test
    fun `行文本带描述`() {
        assertEquals("compact  ·  压缩上下文", completionRowText(builtin))
        assertEquals("x", completionRowText(CompletionItem("x", "x")))
    }

    @Test
    fun `选中行有底色，其余没有`() {
        val list = buildCompletionList(listOf(builtin, skill), selected = 0)
        val rows = rowsOf(list)

        assertEquals(2, rows.size, "两条候选各一行")
        assertTrue(rows[0].isOpaque, "选中行要有底色")
        assertFalse(rows[1].isOpaque, "没选中的不该有底色")
    }

    // ---- 位置 ----

    @Test
    fun `光标下方放得下就往下弹`() {
        assertEquals(
            120,
            completionPopupY(
                caretTop = 100, caretBottom = 116, popupHeight = 80,
                screenTop = 0, screenBottom = 900, gap = 4,
            ),
        )
    }

    @Test
    fun `下方放不下就往上翻 —— 输入框本来就在窗口底部`() {
        // 下方 884+80 超出屏底 900，于是翻到光标上方：864-4-80
        assertEquals(
            780,
            completionPopupY(
                caretTop = 864, caretBottom = 880, popupHeight = 80,
                screenTop = 0, screenBottom = 900, gap = 4,
            ),
        )
    }

    @Test
    fun `上下都放不下时贴屏幕顶 —— 贴底会让第一项看不见`() {
        assertEquals(
            0,
            completionPopupY(
                caretTop = 5, caretBottom = 20, popupHeight = 900,
                screenTop = 0, screenBottom = 900, gap = 4,
            ),
        )
    }

    // ---- 命中字符加粗（2026-09-16）----

    @Test
    fun `命中字符包在 b 里，没命中的原样`() {
        val item = CompletionItem(
            display = "src/Composer.kt",
            insert = "src/Composer.kt",
            hits = listOf(4, 5),
        )

        assertEquals("<html>src/<b>Co</b>mposer.kt</html>", highlightedRowText("src/Composer.kt", item))
    }

    @Test
    fun `没有命中就还是纯文本 —— 不为不相干的行付 HTML 排版的代价`() {
        val item = CompletionItem(display = "src/Composer.kt", insert = "src/Composer.kt")

        assertEquals("src/Composer.kt", highlightedRowText("src/Composer.kt", item))
    }

    @Test
    fun `行文本被让位砍过时不加粗 —— 下标对不上就不标`() {
        // 符号行塞不下时会被让位逻辑改成 `…/X.kt`，那时 display 已经不在行首
        val item = CompletionItem(
            display = "src/a/X.kt",
            insert = "x",
            hits = listOf(0, 1, 2),
        )

        assertEquals("…/a/X.kt", highlightedRowText("…/a/X.kt", item))
    }

    @Test
    fun `尖括号与和号要转义 —— 路径里真的会出现`() {
        val item = CompletionItem(display = "a<b>&c.kt", insert = "x", hits = listOf(0))

        assertEquals("<html><b>a</b>&lt;b&gt;&amp;c.kt</html>", highlightedRowText("a<b>&c.kt", item))
    }
}
