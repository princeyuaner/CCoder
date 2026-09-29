package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JComboBox
import javax.swing.SwingUtilities

private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

/**
 * 新增 / 编辑那个弹窗的**正文**（`McpServerForm.kt`）。
 *
 * 框本身（`DialogWrapper`）在无头里开不起来，所以可测的是这一层：形状切可见性、
 * 校验怎么说、**预览是不是真会写出去的那段**。
 */
class McpServerFormTest {

    private fun form(initial: McpServer = McpServer(), others: List<String> = emptyList()) =
        McpServerForm(initial, others)

    @Test
    fun `预览就是那条会写进文件的东西`() {
        val f = form().apply {
            nameField.text = "codegraph"
            commandField.text = "npx"
            argsArea.text = "-y\ncodegraph"
        }

        val preview = f.preview()

        assertTrue(preview.contains("\"codegraph\""), "预览里没有名字那一层：$preview")
        assertTrue(preview.contains("\"command\": \"npx\""), "预览里没有命令：$preview")
        assertTrue(preview.contains("\"args\""), "预览里没有参数：$preview")
        assertFalse(preview.contains("\"env\""), "空的环境变量不该出现在预览里（也不会写进文件）")
        assertFalse(preview.contains("\"type\""), "命令行的 type 是可省的，预览里也不该有")
    }

    @Test
    fun `远端那条预览里是 type 与 url`() {
        val f = form().apply {
            nameField.text = "context7"
            kindBox.selectedItem = McpKind.HTTP
            urlField.text = "https://mcp.context7.com/mcp"
        }

        val preview = f.preview()

        assertTrue(preview.contains("\"type\": \"http\""), preview)
        assertTrue(preview.contains("\"url\": \"https://mcp.context7.com/mcp\""), preview)
        assertFalse(preview.contains("\"command\""), "远端那条不该写命令出去")
    }

    @Test
    fun `改一个字预览就跟着变`() {
        val f = form()
        var changes = 0
        f.onChange = { changes++ }

        onEdt { f.nameField.text = "后改的" }

        assertEquals(1, changes, "控件改了却没喊 onChange —— 预览与校验会停在旧值上")
    }

    @Test
    fun `切成远端后命令那几栏收起来、地址那栏出来`() {
        val f = form()
        val root = f.component()
        assertTrue(inputParentVisible(root, "命令"), "命令行那种该看得见命令那栏")

        onEdt { f.kindBox.selectedItem = McpKind.SSE }

        assertFalse(inputParentVisible(root, "命令"), "切成远端后命令那栏还开着")
        assertTrue(inputParentVisible(root, "地址"), "切成远端后地址那栏没出来")
    }

    @Test
    fun `读回来的是表单里填的那条`() {
        val f = form().apply {
            nameField.text = "  我的  "
            commandField.text = " npx "
            argsArea.text = "-y\n\n  codegraph  "
            envArea.text = "TOKEN=v\n坏行没有等号"
        }

        val s = f.collect()

        assertEquals("我的", s.name)
        assertEquals("npx", s.command)
        assertEquals(listOf("-y", "codegraph"), s.args)
        assertEquals(mapOf("TOKEN" to "v"), s.env, "没有等号的那行该被丢掉（打字中间态）")
    }

    @Test
    fun `校验不过时 error 会说出来，过了就是 null`() {
        val f = form(others = listOf("别的"))

        f.nameField.text = ""
        assertNotNull(f.error())

        f.nameField.text = "别的"
        assertTrue(f.error()!!.contains("已经有一条"))

        f.nameField.text = "我的"
        f.commandField.text = "npx"
        assertNull(f.error())
    }

    @Test
    fun `校验那句话画得出来、也能藏掉`() {
        val f = form()
        val root = f.component()

        onEdt { f.showError("不行") }
        assertNotNull(findLabel(root, "不行"), "校验结果没画出来")

        onEdt { f.showError(null) }
        assertNull(findLabel(root, "不行"), "问题修好之后那句话还在")
    }

    @Test
    fun `编辑时自己那个名字不算重名`() {
        // 调用方传进来的 others 是"除自己以外"的，这里守的是调用方那条约定
        val f = form(initial = McpServer(name = "a", command = "npx"), others = emptyList())

        assertNull(f.error(), "编辑自己却因为重名被拦住了")
    }

    /** 标签对应的那栏可不可见（`labeledField` 把标签与控件包在一起）。 */
    private fun inputParentVisible(root: java.awt.Container, label: String): Boolean {
        val input = inputOf(root, label)
        return input.isVisible && (input.parent?.isVisible ?: true)
    }
}
