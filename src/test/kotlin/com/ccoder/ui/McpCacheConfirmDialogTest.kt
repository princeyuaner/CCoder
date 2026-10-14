package com.ccoder.ui

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.lang.reflect.Proxy
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * 在 EDT 上跑一段（`DialogWrapper` 的构造函数带线程断言；`invokeAndWait` 那层
 * 包装会把断言失败吞成 InvocationTargetException）。
 */
private fun onEdt(block: () -> Unit) {
    var thrown: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            block()
        } catch (t: Throwable) {
            thrown = t
        }
    }
    thrown?.let { throw it }
}

/** 一个只应答的 Project 替身 —— 同 PermissionDialogTest 的做法，DialogWrapper 只拿它认窗口。 */
private fun fakeProject(): Project =
    Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "ccoder-test-project"
            "hashCode" -> 0
            "equals" -> false
            "isDisposed" -> false
            else -> null
        }
    } as Project

/**
 * 「MCP 还没连完」的居中确认框（2026-10-14，选型台 B 案）。
 *
 * 盯的全是"换了个壳之后，旧确认条那三条规矩还在不在"：
 *
 * - **安全的那颗在默认焦点上**（用户刚按的是回车，第二下回车不该把钱花出去）；
 * - **Esc / 关窗 = 等它** —— 输入框原文未动，什么都不用回滚；
 * - **结论只回一条**：连点两下、或者静默关掉之后再点，都不该发出第二条。
 *
 * 测试里一律**不 show**（同 PermissionDialogTest：HeadlessDialog 会立刻关掉，
 * 真模态循环也起不来），只构造 + 走控件上真挂着的监听器。
 */
class McpCacheConfirmDialogTest {

    private val decisions = mutableListOf<CacheWarnDecision>()

    private fun dialog(names: String = "codegraph、code-review-graph") =
        McpCacheConfirmDialog(fakeProject(), names) { decisions += it }

    /** 窗口右上角那个 X。平台的关闭事件就是派发给这些监听器的。 */
    private fun closeWindow(dialog: McpCacheConfirmDialog) {
        val window = dialog.window ?: fail("对话框没有窗口")
        val e = WindowEvent(window, WindowEvent.WINDOW_CLOSING)
        window.windowListeners.forEach { it.windowClosing(e) }
    }

    /** 按 Esc 走的那条路：从根面板的键盘绑定里找出 ESC 的 Action 再执行（同 PermissionDialogTest）。 */
    private fun pressEsc(dialog: McpCacheConfirmDialog) {
        val pane = dialog.rootPane
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        val conditions = listOf(
            JComponent.WHEN_IN_FOCUSED_WINDOW,
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            JComponent.WHEN_FOCUSED,
        )
        for (condition in conditions) {
            val key = pane.getInputMap(condition).get(stroke) ?: continue
            val action: Action = pane.actionMap.get(key) ?: continue
            action.actionPerformed(ActionEvent(pane, ActionEvent.ACTION_PERFORMED, "esc"))
            return
        }
        fail("根面板上没有 ESC 的键盘绑定 —— 关掉框之后那一发就没人管了")
    }

    // ---- 两条出口 ----

    @Test
    fun `点等它 = Wait，什么都不发`() = onEdt {
        val dialog = dialog()
        dialog.card.waitButton.doClick()

        assertEquals(listOf<CacheWarnDecision>(CacheWarnDecision.Wait), decisions)
    }

    @Test
    fun `点照发 = SendAnyway，没勾静音就不带`() = onEdt {
        val dialog = dialog()
        dialog.card.sendButton.doClick()

        assertEquals(listOf<CacheWarnDecision>(CacheWarnDecision.SendAnyway(mute = false)), decisions)
    }

    @Test
    fun `勾了本会话不再问，照发把静音带上`() = onEdt {
        val dialog = dialog()
        dialog.card.muteBox.isSelected = true
        dialog.card.sendButton.doClick()

        assertEquals(listOf<CacheWarnDecision>(CacheWarnDecision.SendAnyway(mute = true)), decisions)
    }

    @Test
    fun `按 Esc 等于等它`() = onEdt {
        val dialog = dialog()
        pressEsc(dialog)

        assertEquals(listOf<CacheWarnDecision>(CacheWarnDecision.Wait), decisions)
    }

    @Test
    fun `关窗等于等它`() = onEdt {
        val dialog = dialog()
        closeWindow(dialog)

        assertEquals(listOf<CacheWarnDecision>(CacheWarnDecision.Wait), decisions)
    }

    // ---- 只回一条 ----

    @Test
    fun `连点两下只回一条`() = onEdt {
        val dialog = dialog()
        dialog.card.sendButton.doClick()
        dialog.card.waitButton.doClick()

        assertEquals(1, decisions.size, "框已经回过话了：$decisions")
    }

    @Test
    fun `静默关掉之后按钮也点不动`() = onEdt {
        val dialog = dialog()
        dialog.closeSilently()
        dialog.card.sendButton.doClick()

        assertTrue(decisions.isEmpty(), "MCP 落定先把框收掉了，点它不该发出决定：$decisions")
        assertTrue(dialog.isDecided(), "收掉之后 showModal 那一拍也不许再把它叫出来")
    }

    // ---- 误触防护 ----

    @Test
    fun `首选焦点在等它上`() = onEdt {
        val dialog = dialog()

        assertEquals(
            dialog.card.waitButton,
            dialog.preferredFocusedComponent,
            "用户刚按的是回车，第二下回车不该把钱花出去",
        )
    }

    @Test
    fun `没有默认按钮 —— 回车不会盲发`() = onEdt {
        val dialog = dialog()

        assertNull(dialog.rootPane.defaultButton, "有默认按钮 = 有一个键能盲发")
    }
}
