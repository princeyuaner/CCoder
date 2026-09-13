package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

/**
 * 弹层。测属性与位置计算 —— 好不好看交给 [CompletionRenderProbe]。
 */
class CompletionPopupTest {

    private val builtin = CompletionItem("compact", "compact", "压缩上下文", group = GROUP_BUILTIN)
    private val skill = CompletionItem("brainstorming", "brainstorming", "想清楚", group = GROUP_SKILL)

    private fun labelsOf(c: Component): List<JLabel> = buildList {
        if (c is JLabel) add(c)
        if (c is Container) c.components.forEach { addAll(labelsOf(it)) }
    }

    private fun textsOf(c: Component): List<String> = labelsOf(c).map { it.text }

    /** 去掉分组标题，剩下的就是候选行。 */
    private fun rowsOf(c: Component): List<JLabel> =
        labelsOf(c).filter { it.text != GROUP_BUILTIN && it.text != GROUP_SKILL }

    @Test
    fun `弹层不可聚焦 —— 焦点跑掉的话接下来的字符就进了弹层`() {
        val list = buildCompletionList(listOf(builtin, skill), selected = 0)
        assertFalse(list.isFocusable, "内容组件不可聚焦是这套配置的机制本身")
    }

    @Test
    fun `分组标题只在换组时出现一次`() {
        val two = buildCompletionList(listOf(builtin, skill), selected = 0)
        assertEquals(1, textsOf(two).count { it == GROUP_BUILTIN })
        assertEquals(1, textsOf(two).count { it == GROUP_SKILL })
    }

    @Test
    fun `同组连续多项只画一个标题`() {
        val a = CompletionItem("a", "a", group = GROUP_BUILTIN)
        val b = CompletionItem("b", "b", group = GROUP_BUILTIN)
        val list = buildCompletionList(listOf(a, b), selected = 0)
        assertEquals(1, textsOf(list).count { it == GROUP_BUILTIN })
    }

    @Test
    fun `没有分组的候选不画标题`() {
        val list = buildCompletionList(listOf(CompletionItem("x", "x")), selected = 0)
        assertFalse(textsOf(list).contains(GROUP_BUILTIN))
        assertFalse(textsOf(list).contains(GROUP_SKILL))
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
}
