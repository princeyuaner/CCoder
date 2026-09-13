package com.ccoder.ui

import com.ccoder.settings.SendShortcut
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

/**
 * 输入区的规则：高度、发送快捷键、布局。
 *
 * 抽成纯函数/纯布局函数是因为 ClaudePanel 依赖 Project，起不了单测；
 * 而这三处正是这次重设计里最容易写错、也最该被钉住的。
 */
class ComposerRulesTest {

    // ---- 高度 ----

    @Test
    fun `输入框竖向撑满视口，拖高输入区才会真的变大`() {
        val area = ComposerTextArea(COMPOSER_MIN_ROWS, 40)

        assertTrue(
            area.scrollableTracksViewportHeight,
            "返回 false 时拖高高输入区只会多出空白，输入框本身不变 —— 看起来像拖了没用",
        )
    }

    @Test
    fun `最小行数是一行 —— 它同时决定输入框能拖到多矮`() {
        assertEquals(1, COMPOSER_MIN_ROWS)
    }

    // ---- 发送快捷键 ----

    private fun enter(shift: Boolean = false, ctrl: Boolean = false, shortcut: SendShortcut) =
        isSendKey(KeyEvent.VK_ENTER, shiftDown = shift, ctrlDown = ctrl, shortcut = shortcut)

    @Test
    fun `聊天惯例下 Enter 发送、Shift+Enter 换行`() {
        assertTrue(enter(shortcut = SendShortcut.ENTER))
        assertFalse(enter(shift = true, shortcut = SendShortcut.ENTER), "Shift+Enter 必须留给换行")
    }

    @Test
    fun `编辑器惯例下 Ctrl+Enter 发送、Enter 换行`() {
        assertTrue(enter(ctrl = true, shortcut = SendShortcut.CTRL_ENTER))
        assertFalse(enter(shortcut = SendShortcut.CTRL_ENTER), "裸 Enter 必须留给换行")
        assertFalse(
            enter(shift = true, shortcut = SendShortcut.CTRL_ENTER),
            "Shift+Enter 在两种惯例下都该是换行",
        )
    }

    @Test
    fun `其他按键一律不发送`() {
        val other = KeyEvent.VK_A
        assertFalse(isSendKey(other, false, false, SendShortcut.ENTER))
        assertFalse(isSendKey(other, false, true, SendShortcut.CTRL_ENTER))
    }

    @Test
    fun `两种惯例的取值可持久化且默认是聊天惯例`() {
        assertEquals("ENTER", SendShortcut.ENTER.name)
        assertEquals("CTRL_ENTER", SendShortcut.CTRL_ENTER.name)
        assertEquals(SendShortcut.ENTER, SendShortcut.DEFAULT)
    }

    // ---- 布局 ----

    @Test
    fun `输入框居中、工具栏在下，NORTH 空着`() {
        val scroll = JPanel()
        val toolbar = JPanel()

        val card = buildComposerCard(scroll, toolbar)

        val layout = card.layout as BorderLayout
        assertSame(scroll, layout.getLayoutComponent(BorderLayout.CENTER), "输入框应在中间区域")
        assertSame(
            toolbar, layout.getLayoutComponent(BorderLayout.SOUTH),
            "工具栏必须在下方；摆到 EAST 就退回成「按钮挤在输入框右边」，右侧放不下以后的控件",
        )
        assertNull(
            layout.getLayoutComponent(BorderLayout.NORTH),
            "NORTH 该空着 —— 四张状态卡是独立的一排，在输入卡外面",
        )
    }

    @Test
    fun `卡片自己画圆角描边，边框不在输入框上`() {
        // 方案 A 的核心取舍：边框包住**整个输入区**（输入框 + 工具栏），
        // 输入框自己是裸的。若边框回到输入框上，就退回成"一个直角矩形框住文字"
        val card = buildComposerCard(JPanel(), JPanel())

        val outer = card.border as CompoundBorder
        assertTrue(outer.outsideBorder is RoundedLineBorder, "卡片应有圆角描边")
    }

    @Test
    fun `聚焦只换描边颜色，不加粗 —— 加粗会让正在输入的文字抖一下`() {
        val card = buildComposerCard(JPanel(), JPanel())
        val border = (card.border as CompoundBorder).outsideBorder as RoundedLineBorder

        val probe = JPanel()
        val colorBefore = border.color()
        val insetBefore = border.getBorderInsets(probe)
        card.setFocused(true)

        assertNotEquals(colorBefore, border.color(), "聚焦后描边颜色应变")
        assertEquals(
            insetBefore, border.getBorderInsets(probe),
            "内边距没变 —— 变了就是加粗了，正在输入的内容会位移一个像素",
        )
    }
}
