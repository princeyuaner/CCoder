package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun open(
        baseDir: Path?,
        status: McpStatus = McpStatus(),
        // 缺省**没人接**（`armed` 是 false）：那几颗动作按钮一颗都不该画 ——
        // 于是绝大多数用例看到的界面与加这条线之前一模一样
        control: McpControl = McpControl(),
    ): Pair<JComponent, McpSettingsPage> {
        val page = McpSettingsPage(baseDir, status, control)
        lateinit var root: JComponent
        onEdt { root = page.component() }
        return root to page
    }

    /** 一个接了动作的 `McpControl`：收到的动作记进 [seen]，收不收下由 [answer] 定。 */
    private fun armed(seen: MutableList<McpControl.Action>, answer: Boolean = true): McpControl =
        McpControl().apply {
            attach("测试") { action ->
                seen += action
                answer
            }
        }

    private fun statusOf(vararg servers: McpServerStatus): McpStatus =
        McpStatus().apply { set(servers.toList()) }

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

    // ---- 行内动作（重连 / 启用停用，2026-09-28）----

    @Test
    fun `没有会话接动作时，一颗按钮都不画`() {
        // 画一颗点了没反应的按钮，正是这个仓库最不肯做的事（CardAction 那条）
        val (root, _) = open(null, statusOf(McpServerStatus("proj", "connected", "project", null, emptyList())))

        assertNull(findLabel(root, "重连"), "没人接的时候不该有「重连」")
        assertNull(findLabel(root, "停用"), "没人接的时候不该有「停用」")
    }

    @Test
    fun `有会话时每行给重连与停用`() {
        val (root, _) = open(null, statusOf(McpServerStatus("proj", "failed", "project", "连不上", emptyList())), armed(mutableListOf()))

        assertNotNull(findLabel(root, "重连"))
        assertNotNull(findLabel(root, "停用"))
    }

    @Test
    fun `点停用，交出去的是 Toggle(name, false)`() {
        // false 是"停用"这个正经营生：一路上被当成"没设置"吞掉的话，
        // 用户点了停用而 server 还在跑
        val seen = mutableListOf<McpControl.Action>()
        val (root, _) = open(null, statusOf(McpServerStatus("proj", "connected", "project", null, emptyList())), armed(seen))

        onEdt { clickOn(findLabel(root, "停用")!!) }

        assertEquals(listOf<McpControl.Action>(McpControl.Action.Toggle("proj", false)), seen)
    }

    @Test
    fun `点重连，交出去的是 Reconnect(name)`() {
        val seen = mutableListOf<McpControl.Action>()
        val (root, _) = open(null, statusOf(McpServerStatus("proj", "failed", "project", "连不上", emptyList())), armed(seen))

        onEdt { clickOn(findLabel(root, "重连")!!) }

        assertEquals(listOf<McpControl.Action>(McpControl.Action.Reconnect("proj")), seen)
    }

    @Test
    fun `已经停用的那行只给启用 —— 再给一颗停用是废话`() {
        val (root, _) = open(null, statusOf(McpServerStatus("proj", "disabled", "project", null, emptyList())), armed(mutableListOf()))

        assertNotNull(findLabel(root, "启用"))
        assertNull(findLabel(root, "停用"), "它本来就是停着的")
        assertNull(findLabel(root, "重连"), "停用的 server 重连什么")
    }

    /**
     * 那句失败这会儿露着吗。
     *
     * **它不是标签**（要折行，标签不折 —— 一行的标签在这栏里必然被裁掉），
     * 所以 `findLabel` 够不着；它藏在一个整块显示/藏起来的容器里，
     * 问那个容器（"在树上"与"看得见"是两件事，藏起来时组件还在树上）。
     */
    private fun failureShown(root: Container, page: McpSettingsPage): Boolean {
        var found = false
        fun walk(c: Component) {
            if (c === page.failureHolder) found = true
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return found && page.failureHolder.isVisible
    }

    @Test
    fun `失败那句话显示在最上面，按点的哪一颗挑措辞`() {
        val seen = mutableListOf<McpControl.Action>()
        val control = armed(seen)
        val (root, page) = open(null, statusOf(McpServerStatus("proj", "failed", "project", null, emptyList())), control)

        onEdt { control.report(McpControl.Action.Reconnect("proj"), "Connection closed") }

        assertTrue(failureShown(root, page), "重连失败得在框里说得出来 —— 聊天被这个模态框挡着")
        assertEquals(
            CcoderText.text("settings.mcp.reconnectFailed", "proj", "Connection closed"),
            page.failureArea.text,
        )
    }

    @Test
    fun `停用失败说的是停用，不是重连`() {
        val seen = mutableListOf<McpControl.Action>()
        val control = armed(seen)
        val (root, page) = open(null, statusOf(McpServerStatus("proj", "connected", "project", null, emptyList())), control)

        onEdt { control.report(McpControl.Action.Toggle("proj", false), "没有了") }

        assertTrue(failureShown(root, page))
        assertEquals(
            CcoderText.text("settings.mcp.disableFailed", "proj", "没有了"),
            page.failureArea.text,
            "点的是停用，说的却是重连 —— 用户会以为点错了按钮",
        )
    }

    @Test
    fun `接着点一次，上一次的报错先消失`() {
        // 留着的话：用户刚点了重连，却在读上一次的报错，会以为这次也失败了
        val seen = mutableListOf<McpControl.Action>()
        val control = armed(seen)
        val (root, page) = open(null, statusOf(McpServerStatus("proj", "failed", "project", null, emptyList())), control)
        onEdt { control.report(McpControl.Action.Reconnect("proj"), "Connection closed") }
        assertTrue(failureShown(root, page), "前提：这会儿它是在的")

        onEdt { clickOn(findLabel(root, "重连")!!) }

        assertFalse(failureShown(root, page), "刚点了重连却在读上一次的报错，会以为这次也失败了")
    }

    @Test
    fun `会话断了之后按钮跟着消失`() {
        // armed 变了没人通知的话，用户看到的是三颗永远点不动的按钮
        val control = armed(mutableListOf())
        val (root, _) = open(null, statusOf(McpServerStatus("proj", "connected", "project", null, emptyList())), control)
        assertNotNull(findLabel(root, "重连"))

        onEdt { control.detach("测试") }

        assertNull(findLabel(root, "重连"), "没人接了，那颗按钮该跟着消失")
    }

    @Test
    fun `收不下的那一下会把这条线放掉，按钮跟着消失`() {
        // 会话在"画按钮"和"点下去"之间断了：这一下没送出去。若服务还攥着那个
        // 处理器，这几颗按钮会一直画着、点一次石沉大海一次
        val control = armed(mutableListOf(), answer = false)
        val (root, _) = open(null, statusOf(McpServerStatus("proj", "connected", "project", null, emptyList())), control)

        onEdt { clickOn(findLabel(root, "停用")!!) }

        assertFalse(control.armed, "收不下就该把线放掉，别攥着一个没会话的处理器")
        assertNull(findLabel(root, "停用"), "按钮该跟着消失")
    }

    @Test
    fun `dispose 之后不再跟着动作那条线动`() {
        val control = armed(mutableListOf())
        val (root, page) = open(null, statusOf(McpServerStatus("proj", "failed", "project", null, emptyList())), control)
        onEdt { page.dispose() }

        // 退订之后再报一次失败：不该抛，也不该还挂在那儿
        onEdt { control.report(McpControl.Action.Reconnect("proj"), "晚了") }

        assertFalse(failureShown(root, page), "退了订还在跟着画，就是每开一次设置漏一个监听器")
    }
}
