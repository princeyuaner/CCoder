package com.ccoder.ui

import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.SwingUtilities

/**
 * 工具窗口里那条"回来的路"。
 *
 * 2026-09-15 用户报「最小化之后我找不到从哪里重新打开了」—— 上一版只有状态栏
 * 那一行字。这些用例钉的是：**有没有挂起的提问**决定它露不露头、点它回到那个框。
 */
class AskRestoreBarTest {

    private fun bar(onRestore: () -> Unit = {}) = AskRestoreBar(onRestore)

    @Test
    fun `默认不占地方 —— 没有挂起提问时它不该露头`() {
        SwingUtilities.invokeAndWait {
            assertFalse(bar().isVisible, "一进来就是可见的话，每次开面板都多一条带子")
        }
    }

    @Test
    fun `挂起时显示，恢复后收起`() {
        SwingUtilities.invokeAndWait {
            val b = bar()
            b.setSuspended(true)
            assertTrue(b.isVisible)
            b.setSuspended(false)
            assertFalse(b.isVisible)
        }
    }

    /**
     * 与状态卡之间的 5px 缝长在带子**自己身上**（2026-10-14）—— 原先挂在
     * ClaudePanel 的 strut 上，带子藏着时那条 strut 还占着高度：转写区与状态卡
     * 之间多出一条常驻的幽灵缝（用户报"拖动条上下间隔太宽"，量出来正是它）。
     */
    @Test
    fun `底边距自带与状态卡之间那 5px 的缝`() {
        val b = bar()

        // 底边比上边多出的那截 = 缝（上边只有药丸自己的 4px 内边距）——
        // 按差值钉，将来药丸内边距再调也不会误红
        val insets = b.border.getBorderInsets(b)
        assertEquals(
            JBUI.scale(5),
            insets.bottom - insets.top,
            "5px 缝没了或挪去别处了 —— 带子显出来时会贴住状态卡",
        )
    }

    @Test
    fun `点按钮回到那个框`() {
        SwingUtilities.invokeAndWait {
            var restored = 0
            val b = bar { restored++ }

            val button = findButton(b)
            assertTrue(button != null, "得有一颗能点的按钮 —— 一条纯文字带子不算回来的路")
            button!!.doClick()

            assertEquals(1, restored)
        }
    }

    @Test
    fun `点整条带子也算 —— 只有按钮那几十像素能点会显得很钝`() {
        SwingUtilities.invokeAndWait {
            var restored = 0
            val b = bar { restored++ }
            b.setSize(400, 30)

            val click = MouseEvent(b, MouseEvent.MOUSE_CLICKED, 0L, 0, 200, 10, 1, false)
            b.mouseListeners.forEach { it.mouseClicked(click) }

            assertEquals(1, restored)
        }
    }

    @Test
    fun `写的是提问，不是权限`() {
        // 这一条与状态栏那行字同一个口径：队列里既有授权也有提问，说"待确认"
        // 说不清点下去会发生什么
        SwingUtilities.invokeAndWait {
            val text = bar().hintText()
            assertTrue(text.contains("提问"), "文案里得说清是什么在等：$text")
            assertFalse(text.contains("授权"), "授权走的是权限框，不是这条带子：$text")
        }
    }

    @Test
    fun `露头与否只由'有没有挂起的提问'决定`() {
        // 这个判断是两条路（工具窗口那条带子、状态栏那行字）**共用的同一个状态源**
        assertTrue(shouldShowAskRestore(hasSuspendedAsk = true))
        assertFalse(shouldShowAskRestore(hasSuspendedAsk = false))
    }

    @Test
    fun `按钮文案与状态栏那条路说的是同一件事`() {
        assertEquals("回答提问", RESTORE_LABEL)
    }

    private fun findButton(c: java.awt.Container): JButton? {
        for (child in c.components) {
            if (child is JButton) return child
            if (child is java.awt.Container) findButton(child)?.let { return it }
        }
        return null
    }

}
