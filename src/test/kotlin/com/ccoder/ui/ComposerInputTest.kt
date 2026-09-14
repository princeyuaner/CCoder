package com.ccoder.ui

import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 输入框的外观。
 *
 * **这里曾经断言的是反面**：早先用户反馈"输入框与转写区糊在一起分不清"，
 * 于是给输入框自己画了一条线边框。方案 A 把那条线挪到了外层卡片上——
 * 卡片包住的是整个输入区（上下文行 + 输入框 + 工具栏），边界比只框住
 * 文字那一块更明确。
 *
 * 所以"分不清"那个问题仍然是被回答的，只是换了地方回答：
 * 见 [ComposerRulesTest] 里对卡片描边的断言。
 */
class ComposerInputTest {

    private fun styled() = JBTextArea(3, 40).also { styleComposerInput(it) }

    @Test
    fun `输入框自己不画线 —— 线归外层卡片`() {
        // 这里再画一层就退回成"框里套框"，那是方案 A 要付的代价，
        // 不能连它换来的好处一起丢掉
        val area = styled()

        val insets = area.border.getBorderInsets(area)
        assertEquals(
            0, listOf(insets.top, insets.left, insets.bottom, insets.right).count { it > 7 },
            "边框内衬过大，像是画了线：$insets",
        )
        assertFalse(
            area.border is javax.swing.border.CompoundBorder,
            "输入框的边框应当只剩内边距，实际是 ${area.border.javaClass.simpleName}",
        )
    }

    @Test
    fun `文字与边缘之间有内边距`() {
        val area = styled()

        val padding = area.border.getBorderInsets(area)
        assertTrue(
            padding.top >= 2 && padding.left >= 2,
            "没有内边距的话文字会贴着卡片的描边，看着像被框住的段落，实际 $padding",
        )
    }

    @Test
    fun `记号会被刷上底色`() {
        // 底色是"它是个东西、不是乱码"的全部依据 —— 纯文本组件改不了字符颜色，
        // 能用的就只有 Highlighter 这一层
        val area = JBTextArea(3, 40)
        area.text = "看 " + refToken("a/B.kt", 1..2) + " 这个"

        applyRefHighlights(area)

        assertEquals(1, area.highlighter.highlights.size)
    }

    @Test
    fun `记号被删掉之后底色跟着没`() {
        // 每来一次文本变化就整批重刷，就是为了这件事 ——
        // 逐段维护高亮的生命周期，删字/粘贴那几条路太难覆盖全
        val area = JBTextArea(3, 40)
        area.text = refToken("a/B.kt", 1..2)
        applyRefHighlights(area)

        area.text = "没了"
        applyRefHighlights(area)

        assertEquals(0, area.highlighter.highlights.size)
    }

    @Test
    fun `没有记号时一层底色都不画`() {
        val area = JBTextArea(3, 40)
        area.text = "普通的一句话"

        applyRefHighlights(area)

        assertEquals(0, area.highlighter.highlights.size)
    }

    @Test
    fun `输入框不填底 —— 底色由卡片统一决定`() {
        // 不透明会把卡片的底色盖掉，视觉上又变成嵌了一层。
        // 这是"卡片是一个整体"的必要条件：内部的几块不能各自有底
        val area = styled()

        assertFalse(area.isOpaque, "输入框不该自己填底")
    }
}
