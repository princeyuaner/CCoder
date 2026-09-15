package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/** 按文本找标签。只认完全相等 —— 表单里的字段名不会跟列表行名撞。 */
private fun findLabel(root: Container, text: String): Component? {
    for (child in root.components) {
        if (child is JLabel && child.text == text) return child
        if (child is Container) findLabel(child, text)?.let { return it }
    }
    return null
}

/** 按字段标签找它下面那个输入控件 —— 表单是「标签在上、输入在下」的两层结构。 */
private fun inputOf(root: Container, label: String): JComponent {
    val lab = findLabel(root, label) ?: error("找不到字段标签「$label」")
    val panel = lab.parent as? Container ?: error("「$label」不在容器里")
    return panel.components.filterIsInstance<JComponent>().first { it !== lab }
}

/** 内容框外面裹着滚动壳，真正的 `JTextArea` 在视口里。 */
private fun textAreaOf(root: Container, label: String): JTextArea {
    val box = inputOf(root, label)
    return (box as? JScrollPane)?.viewport?.view as? JTextArea
        ?: error("「$label」那栏不是多行输入框，而是 ${box.javaClass.name}")
}

/** 列表行的**监听器挂在行面板上**，不在标签上 —— 所以得点父容器。 */
private fun clickRow(root: Container, name: String) {
    val lab = findLabel(root, name) ?: error("列表里找不到「$name」")
    clickOn(lab.parent)
}

private fun clickOn(target: Component) {
    val e = MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
    target.mouseListeners.forEach { it.mouseClicked(e) }
}

private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

class PromptPresetsPageTest {

    private fun open(): Pair<JComponent, PromptPresets> {
        val service = PromptPresets()
        lateinit var root: JComponent
        onEdt { root = PromptPresetsPage(service).component() }
        return root to service
    }

    @Test
    fun `点添加只是进编辑态，空条目不该落库`() {
        val (root, service) = open()

        onEdt { clickOn(findLabel(root, ADD_PRESET_LABEL)!!) }

        assertTrue(service.presets().isEmpty(), "空条目落了库，列表里会攒下一行幽灵")
        assertNotNull(findLabel(root, "名称"), "没有进编辑态 —— 表单里应该出现字段")
    }

    @Test
    fun `空态文案指的是左栏那个按钮`() {
        val (root, _) = open()

        // 空态下表单里什么都没有，指着表单里的东西说 = 让人去找一个不在屏幕上的按钮
        assertNotNull(
            findLabel(root, "在左边选一条预设，或点「$ADD_PRESET_LABEL」"),
            "空态文案不见了，或者它指错了按钮",
        )
    }

    @Test
    fun `打了名字就落库`() {
        val (root, service) = open()
        onEdt {
            clickOn(findLabel(root, ADD_PRESET_LABEL)!!)
            (inputOf(root, "名称") as JTextComponent).text = "写测试"
        }

        assertEquals(1, service.presets().size)
        assertEquals("写测试", service.presets().first().name)
    }

    @Test
    fun `内容也写回服务，多行原样保存`() {
        val (root, service) = open()
        onEdt {
            clickOn(findLabel(root, ADD_PRESET_LABEL)!!)
            textAreaOf(root, "内容").text = "请加日志\n- 用项目的 logger"
        }

        assertEquals("请加日志\n- 用项目的 logger", service.presets().first().content)
    }

    @Test
    fun `只写内容不写名字，名字用内容首行顶上（接线到底了）`() {
        // 纯函数那边已经有单测，这一条测的是**页面真的接上了它**
        val (root, service) = open()
        onEdt {
            clickOn(findLabel(root, ADD_PRESET_LABEL)!!)
            textAreaOf(root, "内容").text = "解释这段报错"
        }

        assertEquals("解释这段报错", service.presets().first().name)
    }

    @Test
    fun `改完 A 再点 B，A 的改动不丢`() {
        // 模型页那条"增删前必须先 collect()"的坑，在预置这里靠"边打边存"绕开了 ——
        // 这条用例把这个性质钉住：换编辑对象不该吞掉上一条的编辑
        val service = PromptPresets()
        val a = PromptPreset(name = "A", content = "AAA")
        service.upsert(a)
        service.upsert(PromptPreset(name = "B", content = "BBB"))
        lateinit var root: JComponent
        onEdt { root = PromptPresetsPage(service).component() }

        onEdt {
            clickRow(root, "A")
            textAreaOf(root, "内容").text = "AAA 改过了"
            clickRow(root, "B")
        }

        assertEquals("AAA 改过了", service.presets().first { it.id == a.id }.content)
        assertEquals("BBB", service.presets().first { it.name == "B" }.content, "B 被误改了")
    }

    @Test
    fun `多行内容进内容框是真换行`() {
        // 离屏渲染的图上看不出"真换行"和"字面量 \n"的区别（都挤在一行里），
        // 所以这条得用行数问清楚 —— 探针图上核对过一次，看走眼了
        val service = PromptPresets()
        service.upsert(PromptPreset(name = "多行", content = "第一行\n第二行\n第三行"))
        lateinit var root: JComponent
        onEdt { root = PromptPresetsPage(service).component() }

        onEdt { clickRow(root, "多行") }

        assertEquals(3, textAreaOf(root, "内容").lineCount, "内容框里不是三行 —— 换行成了字面量")
    }

    @Test
    fun `删除会把这条从服务里去掉`() {
        val (root, service) = open()
        onEdt {
            clickOn(findLabel(root, ADD_PRESET_LABEL)!!)
            (inputOf(root, "名称") as JTextComponent).text = "不要了"
        }
        assertEquals(1, service.presets().size)

        onEdt { clickOn(findLabel(root, "删除")!!) }

        assertTrue(service.presets().isEmpty())
    }
}
