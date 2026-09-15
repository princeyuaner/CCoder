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
        onSuspendChange: (Boolean) -> Unit = {},
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
        onSuspendChange = onSuspendChange,
    )

    /** 选项可能渲染成标签（芯片、按钮）也可能是会换行的文本块 —— 见 TextLookup.kt。 */
    private fun clickOption(dialog: AskQuestionDialog, text: String) {
        val target = componentWithText(dialog.card, text)
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
        assertTrue(textsIn(second.card).any { it.contains("热切要不要写回长期设置？") })
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

    // ---- 最小化：先去看代码，回来接着答（2026-09-15 用户报的那条）----
    //
    // 形态是"关掉框、但不结束这条提问"：序列挂起，恢复时用同一个 flow 重开一题。
    // 这里盯三件事：收起来之后什么都不拒绝、回来时答案还在、终止路径能把挂起清掉。

    @Test
    fun `最小化把框收起来，但什么都不拒绝`() = onEdt {
        val suspended = mutableListOf<Boolean>()
        val seq = sequence(onSuspendChange = { suspended += it })
        seq.start()
        val first = presented[0]

        first.card.minimizeButton.doClick()

        assertFalse(first.isOpen, "框该收起来")
        assertTrue(seq.suspended)
        assertEquals(listOf(true), suspended, "状态变化要通知出去 —— 状态栏靠它")
        assertEquals(0, denied, "最小化不是拒绝")
        assertEquals(0, submitted.size, "也没有提交任何答案")
        assertEquals(1, presented.size, "不该顺手弹下一题 —— 序列挂起了")
    }

    @Test
    fun `恢复回到同一题，答案还在`() = onEdt {
        val seq = sequence()
        seq.start()
        clickOption(presented[0], "继续未提交的改动")

        presented[0].card.minimizeButton.doClick()
        seq.restore()

        assertEquals(2, presented.size, "该重开一个框")
        assertTrue(presented[1].isOpen)
        assertFalse(seq.suspended, "恢复之后不再是挂起态")
        assertTrue(
            seq.flow.current.isSelected("继续未提交的改动"),
            "最小化前选的那一项该还在",
        )
        assertTrue(presented[1].card !== presented[0].card, "是重开的新卡片（旧的已经关掉了）")
    }

    @Test
    fun `挂起在哪一题，回来就还在哪一题`() = onEdt {
        val seq = sequence()
        seq.start()
        val second = advance(presented[0], "审查当前 diff")

        second.card.minimizeButton.doClick()
        seq.restore()

        assertEquals(1, seq.flow.index, "回来时该还在第二题")
        assertTrue(
            textsIn(presented.last().card).any { it.contains("热切要不要写回长期设置？") },
            "重开的该是第二题",
        )
    }

    @Test
    fun `没挂起时恢复什么都不做`() = onEdt {
        // 状态栏那一下可能来两次（点了两下、或恢复后又被点）。第二次必须无害
        val seq = sequence()
        seq.start()

        seq.restore()

        assertEquals(1, presented.size, "没挂起时恢复不该弹框")
    }

    @Test
    fun `最小化之后接着作答，答案是完整的`() = onEdt {
        val seq = sequence()
        seq.start()
        clickOption(presented[0], "继续未提交的改动")
        presented[0].card.minimizeButton.doClick()

        seq.restore()
        clickOption(presented.last(), "审查当前 diff") // 单选：顶掉最小化之前那个
        presented.last().card.submitButton.doClick()
        clickOption(presented.last(), "写回设置")
        presented.last().card.submitButton.doClick()

        assertEquals(1, submitted.size)
        assertEquals(listOf("审查当前 diff"), submitted[0]["你希望我接下来做什么？"])
        assertEquals(listOf("写回设置"), submitted[0]["热切要不要写回长期设置？"])
    }

    @Test
    fun `终止路径把挂起也清掉`() = onEdt {
        // 不清的话状态栏会一直写着"有提问待回答"，点下去却什么都没有 ——
        // 那比没有这个入口更糟
        val suspended = mutableListOf<Boolean>()
        val seq = sequence(onSuspendChange = { suspended += it })
        seq.start()
        presented[0].card.minimizeButton.doClick()

        seq.closeSilently()

        assertFalse(seq.suspended)
        assertEquals(listOf(true, false), suspended, "挂起与解除都要通知")
    }

    @Test
    fun `终止之后恢复不弹框`() = onEdt {
        val seq = sequence()
        seq.start()
        presented[0].card.minimizeButton.doClick()
        seq.closeSilently()

        seq.restore()

        assertEquals(1, presented.size, "已经终止了，恢复不该再弹")
    }
}
