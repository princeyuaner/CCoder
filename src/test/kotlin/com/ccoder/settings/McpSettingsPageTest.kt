package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

private fun clickRow(root: Container, name: String) {
    val lab = findLabel(root, name) ?: error("列表里找不到「$name」")
    clickOn(lab.parent)
}

private fun clickOn(target: Component) {
    val e = MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
    target.mouseListeners.forEach { it.mouseClicked(e) }
}

private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

private fun textOf(root: Container, label: String): JTextComponent =
    inputOf(root, label) as? JTextComponent ?: error("「$label」那栏不是单行输入框")

class McpSettingsPageTest {

    private fun open(baseDir: Path?, status: McpStatus = McpStatus()): Pair<JComponent, McpSettingsPage> {
        val page = McpSettingsPage(baseDir, status)
        lateinit var root: JComponent
        onEdt { root = page.component() }
        return root to page
    }

    private fun serversIn(dir: Path) = mcpServersOf(ProjectJson.read(mcpJsonPath(dir)))

    @Test
    fun `新加一条 server 会写进文件`(@TempDir dir: Path) {
        val (root, _) = open(dir)

        onEdt { clickOn(findLabel(root, ADD_SERVER_LABEL)!!) }
        onEdt { textOf(root, "名称").text = "我的" }
        onEdt { textOf(root, "命令").text = "npx" }

        val servers = serversIn(dir).servers
        assertEquals(listOf("我的"), servers.map { it.name })
        assertEquals("npx", servers.first().command)
    }

    @Test
    fun `名字还没填时不落库 —— 否则文件里会留一条会被读丢的空条目`(@TempDir dir: Path) {
        val (root, _) = open(dir)

        onEdt { clickOn(findLabel(root, ADD_SERVER_LABEL)!!) }
        onEdt { textOf(root, "命令").text = "npx" }   // 只填了命令，没填名字

        assertTrue(serversIn(dir).servers.isEmpty(), "空名字的条目被写进文件了")
        assertFalse(Files.exists(mcpJsonPath(dir)), "不该凭空建出这个文件")
    }

    @Test
    fun `改一条不会弄丢文件里别人的东西`(@TempDir dir: Path) {
        Files.writeString(
            mcpJsonPath(dir),
            """
            {
              "mcpServers": {
                "a": { "command": "旧命令" },
                "weird": { "type": "sdk", "name": "插件不认识的形状" }
              },
              "别的工具": { "k": 1 }
            }
            """.trimIndent(),
        )
        val (root, _) = open(dir)

        onEdt { clickRow(root, "a") }
        onEdt { textOf(root, "命令").text = "新命令" }

        val file = ProjectJson.read(mcpJsonPath(dir))
        assertTrue(file.has("别的工具"), "顶层别的键被弄丢了")
        assertTrue(file.getAsJsonObject("mcpServers").has("weird"), "认不得的那条被弄丢了")
        assertEquals("新命令", file.getAsJsonObject("mcpServers").getAsJsonObject("a").get("command").asString)
    }

    @Test
    fun `删除会把这条从文件里去掉`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val (root, _) = open(dir)

        onEdt { clickRow(root, "a") }
        onEdt { clickOn(findLabel(root, "删除")!!) }

        assertTrue(serversIn(dir).servers.isEmpty())
    }

    @Test
    fun `拿不到项目目录时只读，并说清楚`() {
        val (root, _) = open(null)

        assertNotNull(findLabel(root, "拿不到项目目录，改不了"), "没说清为什么改不了")
    }

    @Test
    fun `切成 SSE 后命令那几栏收起来、地址那栏出来`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val (root, _) = open(dir)
        onEdt { clickRow(root, "a") }

        assertTrue(inputOf(root, "命令").parent.isVisible, "stdio 下命令那栏该在")

        onEdt { (inputOf(root, "形状") as JComboBox<Any>).selectedItem = McpKind.SSE }

        assertFalse(inputOf(root, "命令").parent.isVisible, "切成 SSE 后命令那栏还没收起来")
        assertTrue(inputOf(root, "地址").parent.isVisible, "切成 SSE 后地址那栏没出来")
    }

    @Test
    fun `还没拿到状态时说清楚，而不是显示成空的`() {
        val (root, _) = open(null, McpStatus())

        assertNotNull(
            findLabel(root, "还没拿到 —— 开一次会话后就有"),
            "「没问过」与「一个都没有」必须分开说",
        )
    }

    @Test
    fun `拿到状态后列出名字与中文状态`() {
        val status = McpStatus().apply {
            set(
                listOf(
                    McpServerStatus("codegraph", "failed", "user", "连不上", emptyList()),
                    McpServerStatus("proj", "connected", "project", null, listOf("t1", "t2")),
                ),
            )
        }
        val (root, _) = open(null, status)

        assertNotNull(findLabel(root, "codegraph"), "没列出 server 名字")
        assertNotNull(findLabel(root, "失败"), "状态没翻成人话")
        assertNotNull(findLabel(root, "已连接 · 2 个工具"), "连上的该报工具数")
    }

    @Test
    fun `认不出的状态照原样显示，不编一个词`() {
        val status = McpStatus().apply {
            set(listOf(McpServerStatus("x", "将来才有的一档", "user", null, emptyList())))
        }
        val (root, _) = open(null, status)

        assertNotNull(findLabel(root, "将来才有的一档"), "原样值没显示出来")
    }

    @Test
    fun `状态推过来时界面跟着更新`() {
        val status = McpStatus()
        val (root, _) = open(null, status)
        onEdt { status.set(listOf(McpServerStatus("后来的", "connected", "user", null, emptyList()))) }

        assertNotNull(findLabel(root, "后来的"), "服务推了新状态，界面没跟着动")
    }

    @Test
    fun `dispose 之后不再跟着状态动 —— 不退订就是每开一次设置漏一个监听器`() {
        val status = McpStatus()
        val (_, page) = open(null, status)
        onEdt { page.dispose() }

        // 退订之后再推一次：不该抛，也不该还挂在那儿
        onEdt { status.set(listOf(McpServerStatus("推的", "connected", "user", null, emptyList()))) }

        assertEquals(1, status.servers.size, "服务本身照常记录")
    }

    @Test
    fun `空态文案指的是左栏那个按钮`() {
        val (root, _) = open(null)

        assertNotNull(findLabel(root, "在左边选一条 server，或点「$ADD_SERVER_LABEL」"))
    }
}
