package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
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
import java.awt.image.BufferedImage
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * 在 EDT 上跑一段。
 *
 * `DialogWrapper` 的构造函数带线程断言（平台规定 UI 只能在 EDT 上碰），而 JUnit
 * 默认跑在测试线程上。顺带把 `invokeAndWait` 包的那层 `InvocationTargetException`
 * 拆掉 —— 否则断言失败在报告里只剩一句 "InvocationTargetException"。
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

/**
 * 一个只应答 `getService` 的 Project 替身 —— 同 `ModelProfilesDialogProbe`。
 *
 * `DialogWrapper` 拿 Project 是为了认窗口；测试里没有窗口，给它个壳就行。
 */
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

private val someSuggestions: JsonArray = JsonParser.parseString(
    """[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test *"}],""" +
        """"behavior":"allow","destination":"localSettings"}]"""
).asJsonArray

private fun permFixture(
    suggestions: JsonArray? = someSuggestions,
    suppressAlwaysAllowRule: Boolean = false,
) = SidecarMessage.Permission(
    requestId = "r1",
    toolName = "Bash",
    input = JsonParser.parseString("""{"command":"npm test","description":"跑单元测试"}""").asJsonObject,
    title = "Claude 想运行 npm test",
    displayName = "允许",
    description = "在项目根目录跑单元测试",
    blockedPath = """C:\Users\CY\Desktop\CCoder\sidecar""",
    decisionReason = null,
    defaultToNo = false,
    suppressAlwaysAllowRule = suppressAlwaysAllowRule,
    suggestions = suggestions,
)

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

private fun labelsIn(root: Container): List<String> {
    val out = mutableListOf<String>()
    fun walk(c: Container) {
        for (child in c.components) {
            if (child is JLabel) out += child.text.orEmpty()
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out
}

/**
 * 权限**对话框**（不是卡片 —— 卡片那层在 [PermissionCardTest]）。
 *
 * 这里盯的全是"换了个壳之后规矩还在不在"：
 *
 * - 规则①：**关窗与 Esc 都等于拒绝**。这条最要紧 —— SDK 那边挂着的是一个
 *   没有期限的 Promise，框关了这个 Promise 也得有个答案。
 * - 规则②：焦点在"拒绝"上，且没有默认按钮（回车不批准）。
 * - 结论只回一条：连点两下、或者先回决定再被关窗，都不该发出第二条。
 *
 * 测试 JVM 里没有 IDE：`show()` 起不来（它是阻塞的模态循环），所以这里一律
 * **不 show**，只构造 + 走组件上真挂着的监听器。这也是本仓库探针的既有做法。
 */
class PermissionDialogTest {

    private val decisions = mutableListOf<PermissionDecision>()

    private fun dialog(
        suggestions: JsonArray? = someSuggestions,
        suppressAlwaysAllowRule: Boolean = false,
        queuedCount: Int = 0,
    ) = PermissionDialog(
        fakeProject(),
        permFixture(suggestions, suppressAlwaysAllowRule),
        queuedCount,
    ) { decisions += it }

    /** 窗口右上角那个 X。平台的关闭事件就是派发给这些监听器的。 */
    private fun closeWindow(dialog: PermissionDialog) {
        val window = dialog.window ?: fail("对话框没有窗口")
        val e = WindowEvent(window, WindowEvent.WINDOW_CLOSING)
        window.windowListeners.forEach { it.windowClosing(e) }
    }

    /**
     * 按 Esc 走的那条路。
     *
     * 从根面板的键盘绑定里找出 ESC 对应的 Action 再执行 —— 不直接喊
     * `doCancelAction()`：那是平台的取消路径，而我们要测的恰恰是
     * "按 Esc 会不会走到拒绝"。绑定挪了地方、或者没接上，这条会红。
     */
    private fun pressEsc(dialog: PermissionDialog) {
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

    private fun clickButton(dialog: PermissionDialog, text: String) {
        buttonsIn(dialog.card).first { it.text == text }.doClick()
    }

    // ---- 规则①：关掉就是拒绝 ----

    @Test
    fun `关窗等于拒绝，不是稍后再问`() = onEdt {
        val dialog = dialog()
        closeWindow(dialog)

        val decision = decisions.singleOrNull() ?: fail("关窗没有回决定")
        assertTrue(!decision.allow, "关窗必须是拒绝")
        assertEquals(DENY_MESSAGE, decision.message, "拒绝要带上理由，模型才知道发生了什么")
    }

    @Test
    fun `按 Esc 等于拒绝`() = onEdt {
        val dialog = dialog()
        pressEsc(dialog)

        val decision = decisions.singleOrNull() ?: fail("Esc 没有回决定")
        assertTrue(!decision.allow, "Esc 必须是拒绝")
    }

    // ---- 只回一条 ----

    @Test
    fun `回决定之后再关窗不会再回一条`() = onEdt {
        val dialog = dialog()
        clickButton(dialog, "拒绝")
        closeWindow(dialog)

        assertEquals(1, decisions.size, "决定只该有一条：${decisions.map { it.message }}")
    }

    @Test
    fun `静默关掉不回决定`() = onEdt {
        val dialog = dialog()
        dialog.closeSilently()
        closeWindow(dialog)

        assertTrue(decisions.isEmpty(), "终止路径关的框不该再说话：$decisions")
    }

    @Test
    fun `静默关掉之后按钮也点不动`() = onEdt {
        val dialog = dialog()
        dialog.closeSilently()
        clickButton(dialog, "允许")

        assertTrue(decisions.isEmpty(), "框已经关了，点它不该发出决定：$decisions")
    }

    // ---- 规则②：不能被误触批准 ----

    @Test
    fun `首选焦点是拒绝按钮`() = onEdt {
        val dialog = dialog()

        assertEquals(
            dialog.card.denyButton,
            dialog.preferredFocusedComponent,
            "焦点必须先落在拒绝上（规则②）",
        )
    }

    @Test
    fun `没有默认按钮，回车不批准`() = onEdt {
        val dialog = dialog()

        assertNull(dialog.rootPane.defaultButton, "有默认按钮 = 有一个键能批准（规则②）")
    }

    @Test
    fun `按钮上没有键盘捷径`() = onEdt {
        val dialog = dialog()

        val withMnemonic = buttonsIn(dialog.card).filter { it.mnemonic != 0 }
        assertTrue(withMnemonic.isEmpty(), "助记符就是键盘捷径：${withMnemonic.map { it.text }}")
    }

    // ---- 规则③：那个按钮只在 SDK 允许时**渲染** ----

    @Test
    fun `停问按钮只在 SDK 允许时渲染`() = onEdt {
        val allowed = dialog(suggestions = someSuggestions)
        assertTrue(
            buttonsIn(allowed.card).any { it.text.contains(AUTO_ALLOW_LABEL) },
            "有 suggestions 时该有这个入口",
        )

        val suppressed = dialog(suggestions = someSuggestions, suppressAlwaysAllowRule = true)
        assertTrue(
            buttonsIn(suppressed.card).none { it.text.contains(AUTO_ALLOW_LABEL) },
            "suppressAlwaysAllowRule 为真时它该**不存在**，不是禁用",
        )

        val noSuggestions = dialog(suggestions = null)
        assertTrue(
            buttonsIn(noSuggestions.card).none { it.text.contains(AUTO_ALLOW_LABEL) },
            "没有 suggestions 就没有可写的规则，入口也不该有",
        )
    }

    // ---- 换壳不该多出按钮、也不该被挤窄 ----

    @Test
    fun `窗口里只有卡片自己那几个按钮`() = onEdt {
        val dialog = dialog()

        val inDialog = buttonsIn(dialog.contentPanel).map { it.text }
        val inCard = buttonsIn(dialog.card).map { it.text }
        assertEquals(inCard, inDialog, "多出来的按钮一定是 DialogWrapper 自带的那对")
    }

    @Test
    fun `窗口里带着排队数量`() = onEdt {
        val dialog = dialog(queuedCount = 2)

        assertTrue(
            labelsIn(dialog.card).any { it.contains("还有 2 个") },
            "排队数量要显示出来：${labelsIn(dialog.card)}",
        )
    }

    /**
     * 卡片在窗口里得有一份"够读"的宽度。
     *
     * 不钉这一条的话，窗口会缩成 preferred 宽度（152px 那个数由里面**换行的**
     * JSON 文本区决定，它的意思是"最窄也能活"）—— 三个按钮一行都排不下。
     */
    @Test
    fun `卡片不会缩成一条`() = onEdt {
        val dialog = dialog()

        val cardWidth = dialog.card.preferredSize.width
        assertTrue(
            cardWidth >= cardMinimumWidth(),
            "卡片自然宽度只有 ${cardWidth}px，提示词会被截断",
        )
    }

    /** 期望宽度的下限。写在这里而不是抄卡片里的常量，免得两边一起改错。 */
    private fun cardMinimumWidth(): Int = 400
}

/**
 * 渲染探针：把权限对话框离屏画成 PNG。
 *
 * **没有断言** —— 它存在的唯一理由是"观测感"。本项目的教训是单测全绿时
 * 观感问题照样在（那个圆点），所以改完要看图。同时把量出来的尺寸打到 stdout，
 * 好过在注释里写一个估的数。
 */
class PermissionDialogProbe {

    @Test
    fun `把权限对话框画成图片`() = onEdt {
        val dialog = PermissionDialog(fakeProject(), permFixture(), queuedCount = 2) {}

        println("PROBE 卡片 preferred=${dialog.card.preferredSize}")
        println("PROBE 窗口 preferred=${dialog.preferredSize}")

        writePng(dialog.contentPanel, File("build/permission-dialog.png"))
        writePng(dialog.card, File("build/permission-dialog-card.png"))
    }

    /** 离屏渲染要先铺一遍布局 —— 没上屏的组件靠 `revalidate()` 是一动不动的。 */
    private fun writePng(c: JComponent, file: File) {
        val w = maxOf(c.preferredSize.width, 1)
        val h = maxOf(c.preferredSize.height, 1)
        c.setSize(w, h)
        layoutAll(c)

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = c.background ?: java.awt.Color.DARK_GRAY
        g.fillRect(0, 0, w, h)
        c.paint(g)
        g.dispose()

        file.parentFile?.mkdirs()
        ImageIO.write(img, "png", file)
    }

    private fun layoutAll(c: Container) {
        c.invalidate()
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
