package com.ccoder.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.BorderFactory
import javax.swing.border.CompoundBorder

/**
 * 输入框的外观。
 *
 * 用户反馈：输入框与转写区糊在一起分不清，要一条明显的边框。
 * 原来只有 `JBUI.Borders.empty(6)` —— 那是内边距，完全不画线。
 */
class ComposerInputTest {

    private fun styled() = JBTextArea(3, 40).also { styleComposerInput(it) }

    @Test
    fun `有可见的线边框`() {
        val area = styled()

        val border = area.border
        assertTrue(
            border is CompoundBorder,
            "边框应是「线 + 内边距」的组合，实际是 ${border?.javaClass?.simpleName}",
        )

        val line = (border as CompoundBorder).outsideBorder
        val insets = line.getBorderInsets(area)
        assertTrue(
            insets.top >= 1 && insets.left >= 1 && insets.bottom >= 1 && insets.right >= 1,
            "四条边都要有线，实际内衬 $insets",
        )
    }

    @Test
    fun `文字与边框之间有内边距`() {
        val area = styled()

        val padding = (area.border as CompoundBorder).insideBorder.getBorderInsets(area)
        assertTrue(
            padding.top >= 2 && padding.left >= 2,
            "没有内边距的话文字会贴着线，看着像被框住的段落而不是输入框，实际 $padding",
        )
    }

    @Test
    fun `底色取自 IDE 的输入框色且控件不透明`() {
        // 只能守到"用了 IDE 的色值"这一层：JBTextArea 的默认底色本就等于
        // 该色值，所以这条在实现前后都会通过，不具区分度。
        // 保留它是为了钉住"不要改成硬编码颜色"。真正的显隐差异
        // （深色主题下输入框底色比面板略亮）只在真实 IDE 主题下成立
        val area = styled()

        assertEquals(UIUtil.getTextFieldBackground(), area.background)
        assertTrue(area.isOpaque, "不透明才画得出底色")
    }
}
