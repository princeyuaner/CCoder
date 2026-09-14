package com.ccoder.ui

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.lang.reflect.Proxy
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * 提问**对话框**的壳（卡片那层在 [AskQuestionCardTest]，接续在 [AskSequenceTest]）。
 *
 * 两条规矩与权限框完全一致，这里各钉一遍：**Esc 与关窗都等于放掉整条提问**
 * （不是"跳过这题"），**回车不批准**（焦点在拒绝上）。
 */
class AskQuestionDialogTest {

    private var cancelled = 0

    private val request = askRequestOf(
        JsonParser.parseString(
            """
            {"questions":[
              {"question":"你希望我接下来做什么？","header":"要做什么","options":[
                {"label":"继续未提交的改动","description":"接着往下做。"},
                {"label":"审查当前 diff","description":"做一次结构化审查。"}
              ]}
            ]}
            """
        ).asJsonObject
    )!!

    private fun fakeProject(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "ask-dialog-test"
                "hashCode" -> 0
                "equals" -> false
                "isDisposed" -> false
                else -> null
            }
        } as Project

    private fun dialog(): AskQuestionDialog {
        val flow = AskFlow(request)
        val card = AskQuestionCard(
            flow,
            onSubmit = {},
            onAdvance = {},
            onBack = {},
            onDeny = {},
        )
        return AskQuestionDialog(fakeProject(), card) { cancelled++ }
    }

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

    private fun buttonsIn(root: Container): List<JButton> {
        val out = mutableListOf<JButton>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JButton) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    /**
     * 按 Esc 走的那条路：从根面板的键盘绑定里找出 ESC 的 Action 再执行。
     * （平台自己那条绑在 `show()` 里注册，测试里查不到 —— 见 `bindEscapeToDeny` 的说明。）
     */
    private fun pressEsc(dialog: AskQuestionDialog) {
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
        fail("根面板上没有 ESC 的键盘绑定 —— 关掉框却没有给 SDK 一个答案（规则①）")
    }

    private fun closeWindow(dialog: AskQuestionDialog) {
        val window = dialog.window ?: fail("对话框没有窗口")
        val e = WindowEvent(window, WindowEvent.WINDOW_CLOSING)
        window.windowListeners.forEach { it.windowClosing(e) }
    }

    @Test
    fun `首选焦点是拒绝按钮`() = onEdt {
        val dialog = dialog()
        assertEquals(dialog.card.denyButton, dialog.preferredFocusedComponent)
    }

    @Test
    fun `没有默认按钮，回车不批准`() = onEdt {
        assertNull(dialog().rootPane.defaultButton)
    }

    @Test
    fun `按钮上没有键盘捷径`() = onEdt {
        val withMnemonic = buttonsIn(dialog().card).filter { it.mnemonic != 0 }
        assertTrue(withMnemonic.isEmpty(), "助记符就是键盘捷径：${withMnemonic.map { it.text }}")
    }

    @Test
    fun `按 Esc 等于放掉整条提问`() = onEdt {
        val dialog = dialog()
        pressEsc(dialog)

        assertEquals(1, cancelled, "Esc 该走取消那条路")
        assertTrue(!dialog.isOpen)
    }

    @Test
    fun `关窗等于放掉整条提问`() = onEdt {
        val dialog = dialog()
        closeWindow(dialog)

        assertEquals(1, cancelled)
    }

    @Test
    fun `关掉之后再关一次不会再报一遍`() = onEdt {
        val dialog = dialog()
        pressEsc(dialog)
        closeWindow(dialog)

        assertEquals(1, cancelled, "取消只该报一次")
    }

    @Test
    fun `静默关掉不回回调`() = onEdt {
        val dialog = dialog()
        dialog.closeNow()
        closeWindow(dialog)

        assertEquals(0, cancelled, "终止路径关的框不该再说话")
        assertTrue(!dialog.isOpen)
    }

    @Test
    fun `窗口里只有卡片自己那几个按钮`() = onEdt {
        val dialog = dialog()
        assertEquals(
            buttonsIn(dialog.card).map { it.text },
            buttonsIn(dialog.contentPanel).map { it.text },
            "多出来的按钮一定是 DialogWrapper 自带的那对",
        )
    }
}
