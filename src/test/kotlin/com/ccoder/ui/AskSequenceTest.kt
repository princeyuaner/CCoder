package com.ccoder.ui

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.lang.reflect.Proxy
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 在 EDT 上跑一段（平台的对话框构造函数带线程断言），并拆掉
 * `invokeAndWait` 包的那层异常 —— 否则断言失败只剩一句 InvocationTargetException。
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

private fun fakeProject(): Project =
    Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "ask-sequence-test"
            "hashCode" -> 0
            "equals" -> false
            "isDisposed" -> false
            else -> null
        }
    } as Project

private fun labelsIn(root: Container): List<JLabel> {
    val out = mutableListOf<JLabel>()
    fun walk(c: Container) {
        for (child in c.components) {
            if (child is JLabel) out += child
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out
}

/**
 * 多题提问的弹窗序列。
 *
 * 这里盯的是**框与框之间的接续**：前一题答完怎么走到下一题、上一个框有没有真的
 * 先关掉、终止路径能不能把整条链子掐断。每道题自己的渲染与门控在
 * [AskQuestionCardTest]，题号推进在 [AskFlowTest]，这里不重复。
 */
class AskSequenceTest {

    private val request = askRequestOf(
        JsonParser.parseString(
            """
            {"questions":[
              {"question":"你希望我接下来做什么？","header":"要做什么","options":[
                {"label":"继续未提交的改动","description":"接着往下做。"},
                {"label":"审查当前 diff","description":"做一次结构化审查。"}
              ]},
              {"question":"热切要不要写回长期设置？","header":"作用范围","multiSelect":true,"options":[
                {"label":"只影响本次会话","description":"下次启动还是原来的。"},
                {"label":"写回设置","description":"下次启动还用这个。"}
              ]}
            ]}
            """
        ).asJsonObject
    )!!

    private val submitted = mutableListOf<Picked>()
    private val presented = mutableListOf<AskQuestionDialog>()
    private var denied = 0

    /**
     * 建一个序列。`present` 换成"记下来"，`defer` 默认同步跑 ——
     * 这样推进过程是确定的，不需要等事件队列。
     */
    private fun sequence(
        defer: ((() -> Unit)) -> Unit = { it() },
        onPresent: (AskQuestionDialog) -> Unit = {},
    ) = AskSequence(
        project = fakeProject(),
        request = request,
        onSubmit = { submitted += it },
        onDeny = { denied++ },
        present = { dialog ->
            presented += dialog
            onPresent(dialog)
        },
        defer = defer,
    )

    private fun clickOption(dialog: AskQuestionDialog, text: String) {
        val target = labelsIn(dialog.card).firstOrNull { it.text.contains(text) }
            ?: fail("找不到「$text」，树里有：${labelsIn(dialog.card).map { it.text }}")
        target.dispatchEvent(
            MouseEvent(
                target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 3, 3, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    /** 窗口右上角那个 X。 */
    private fun closeWindow(dialog: AskQuestionDialog) {
        val window = dialog.window ?: fail("对话框没有窗口")
        val e = WindowEvent(window, WindowEvent.WINDOW_CLOSING)
        window.windowListeners.forEach { it.windowClosing(e) }
    }

    /** 答完当前题并按「下一题」，返回新弹出来的那个框。 */
    private fun advance(dialog: AskQuestionDialog, option: String): AskQuestionDialog {
        clickOption(dialog, option)
        dialog.card.submitButton.doClick()
        return presented.last()
    }

    @Test
    fun `起手弹第一题`() = onEdt {
        val seq = sequence()
        seq.start()

        assertEquals(1, presented.size)
        assertTrue(presented[0].isOpen)
        assertEquals(0, seq.flow.index)
    }

    @Test
    fun `答完一题接着弹下一题，前一个框先关掉`() = onEdt {
        val seq = sequence()
        seq.start()
        val first = presented[0]

        val second = advance(first, "审查当前 diff")

        assertEquals(2, presented.size, "该弹第二题")
        assertFalse(first.isOpen, "上一个框必须已经关掉")
        assertTrue(second.isOpen)
        assertEquals(1, seq.flow.index)
        assertTrue(labelsIn(second.card).any { it.text.contains("热切要不要写回长期设置？") })
    }

    @Test
    fun `最后一题提交时一次交出全部答案`() = onEdt {
        val seq = sequence()
        seq.start()
        val second = advance(presented[0], "审查当前 diff")

        clickOption(second, "写回设置")
        second.card.submitButton.doClick()

        assertEquals(1, submitted.size, "只该提交一次：$submitted")
        assertEquals(listOf("审查当前 diff"), submitted[0]["你希望我接下来做什么？"])
        assertEquals(listOf("写回设置"), submitted[0]["热切要不要写回长期设置？"])
        assertFalse(second.isOpen, "提交之后框该关掉")
        assertEquals(0, denied, "提交不是拒绝")
    }

    @Test
    fun `上一题会重弹上一题，答案还在`() = onEdt {
        val seq = sequence()
        seq.start()
        val second = advance(presented[0], "审查当前 diff")

        second.card.backButton.doClick()

        assertEquals(3, presented.size, "该重开第一题")
        assertEquals(0, seq.flow.index)
        val reopened = presented.last()
        assertTrue(reopened.isOpen)
        assertFalse(second.isOpen)
        // 现场恢复：上一个框里选过的那个选项，回来还是选中的
        assertTrue(seq.flow.current.isSelected("审查当前 diff"))
    }

    @Test
    fun `按拒绝整条拒掉，不再弹下一个`() = onEdt {
        val seq = sequence()
        seq.start()

        presented[0].card.denyButton.doClick()

        assertEquals(1, denied, "整条拒绝只报一次")
        assertEquals(1, presented.size, "拒绝之后不该再弹")
        assertFalse(presented[0].isOpen)
        assertTrue(submitted.isEmpty(), "拒绝不是提交")
    }

    @Test
    fun `关窗也整条拒掉`() = onEdt {
        val seq = sequence()
        seq.start()

        closeWindow(presented[0])

        assertEquals(1, denied)
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `终止路径关掉框但不回决定`() = onEdt {
        val seq = sequence()
        seq.start()

        seq.closeSilently()
        closeWindow(presented[0])

        assertFalse(presented[0].isOpen)
        assertEquals(0, denied, "会话都没了，别再回一条拒绝")
        assertTrue(submitted.isEmpty())
    }

    /**
     * 取消之后，**已经排在事件队列里的那一拍**也不许再弹。
     *
     * 这条不是假想的：会话停止可能正好发生在"答完一题、下一题还没弹出来"
     * 的那个空当里（`defer` 让出的那一拍）。
     */
    @Test
    fun `取消之后排队的那一拍不再弹`() = onEdt {
        val queued = mutableListOf<() -> Unit>()
        val seq = sequence(defer = { queued += it })
        seq.start()

        clickOption(presented[0], "审查当前 diff")
        presented[0].card.submitButton.doClick()
        assertEquals(1, presented.size, "这一拍只是在排队，还没弹")

        seq.closeSilently()
        queued.forEach { it() }

        assertEquals(1, presented.size, "取消之后不该再弹出来")
        assertTrue(submitted.isEmpty())
    }

    /**
     * 不变式：**present 的当下，不许还有别的框开着。**
     *
     * 从上一个框的按钮处理里同步 `show()` 下一个，得到的是嵌套模态框 ——
     * 焦点与层级都不可靠。这条用"present 时回头看"的方式把它钉住。
     */
    @Test
    fun `弹下一个之前上一个必须已经关掉`() = onEdt {
        var nested: AskQuestionDialog? = null
        val seq = sequence(
            onPresent = { fresh -> if (presented.any { it !== fresh && it.isOpen }) nested = fresh },
        )
        seq.start()

        advance(presented[0], "审查当前 diff")

        assertEquals(null, nested, "弹新框的时候上一个还开着 —— 嵌套模态框")
    }
}
