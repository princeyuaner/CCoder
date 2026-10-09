package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

private fun clickOn(target: Component) {
    val e = MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
    target.mouseListeners.forEach { it.mouseClicked(e) }
}

private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

/** 这一页上所有能看见的字（卡头、卡体、动作、段头）。用来查"值有没有漏到卡上"。 */
private fun textsOf(root: Container): List<String> {
    val out = mutableListOf<String>()
    fun walk(c: Container) {
        for (child in c.components) {
            when (child) {
                is JLabel -> out += child.text
                is JTextComponent -> out += child.text
                else -> Unit
            }
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out.filter { it.isNotBlank() }
}

/**
 * MCP 页：**一台 server 一张卡**（选型稿 `docs/design/mcp-page.html` 方案一，2026-09-30）。
 *
 * 与旧版的差别集中在两处，用例也围着这两处：
 *  - 从前"加一条"是**点开右栏表单、边打边写文件**；现在是一张弹窗，提交才写
 *    （无头里开不了框，所以弹窗那半用注入的假件验**接线**，正文由 `McpServerFormTest` 管）
 *  - 状态从"另开一张卡"并进了每张卡的卡头
 */
class McpSettingsPageTest {

    /** 假的弹窗：记下"被怎么打开的"，并把提交回调交出来。 */
    private class Editor {
        var opened: Triple<McpServer, List<String>, Boolean>? = null
        var submit: ((McpServer) -> Unit)? = null

        fun open(server: McpServer, others: List<String>, isNew: Boolean, onSubmit: (McpServer) -> Unit) {
            opened = Triple(server, others, isNew)
            submit = onSubmit
        }
    }

    private class Fixture(val root: JComponent, val page: McpSettingsPage, val editor: Editor)

    private fun open(
        baseDir: Path?,
        status: McpStatus = McpStatus(),
        // 缺省**没人接**（`armed` 是 false）：那几颗会话动作一颗都不该画 ——
        // 于是绝大多数用例看到的界面与加这条线之前一模一样
        control: McpControl = McpControl(),
    ): Fixture {
        val editor = Editor()
        val page = McpSettingsPage(baseDir, status, control, null, editor::open)
        lateinit var root: JComponent
        onEdt { root = page.component() }
        return Fixture(root, page, editor)
    }

    private fun serversIn(dir: Path) = mcpServersOf(ProjectJson.read(mcpJsonPath(dir)))

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

    private fun clickLabel(root: Container, text: String) {
        val label = findLabel(root, text) ?: error("找不到「$text」")
        clickOn(label)
    }

    // ---- 卡片本身 ----

    @Test
    fun `文件里每一条都有自己的卡`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" }, "b": { "command": "b" } } }""")

        val f = open(dir)

        assertNotNull(findLabel(f.root, "a"), "第一条没有自己的卡")
        assertNotNull(findLabel(f.root, "b"), "第二条没有自己的卡")
    }

    @Test
    fun `卡上写着命令与变量键名`(@TempDir dir: Path) {
        Files.writeString(
            mcpJsonPath(dir),
            """{ "mcpServers": { "a": { "command": "npx", "args": ["-y", "x"], "env": { "TOKEN": "v" } } } }""",
        )

        val f = open(dir)

        assertNotNull(findLabel(f.root, "npx -y x"), "命令那一行没画出来")
        assertNotNull(findLabel(f.root, "环境变量 1 个：TOKEN"), "变量键名那一行没画出来")
    }

    @Test
    fun `变量值不许出现在卡上 —— 这一页会被截图`(@TempDir dir: Path) {
        Files.writeString(
            mcpJsonPath(dir),
            """{ "mcpServers": { "a": { "command": "npx", "env": { "GITHUB_TOKEN": "ghp_秘密" } } } }""",
        )

        val f = open(dir)

        val leaked = textsOf(f.root).filter { it.contains("ghp_秘密") }
        assertEquals(emptyList<String>(), leaked, "变量值漏到界面上了")
    }

    @Test
    fun `失败原因摊在卡上，不只塞进 tooltip`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val status = McpStatus().apply {
            set(listOf(McpServerStatus("a", "failed", "project", "Connection closed", emptyList())))
        }

        val f = open(dir, status)

        assertNotNull(findLabel(f.root, "Connection closed"), "失败原因看不见 —— 只说\"失败\"等于什么都没说")
    }

    @Test
    fun `状态并进卡头 —— 名字与状态不再挤在同一行两端`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val status = McpStatus().apply {
            set(listOf(McpServerStatus("a", "connected", "project", null, listOf("t1", "t2"))))
        }

        val f = open(dir, status)

        assertNotNull(findLabel(f.root, "已连接 · 2 个工具"), "卡头上没有状态")
    }

    // ---- 新增 / 编辑：弹窗接线 ----

    @Test
    fun `点加号给弹窗一条空的，且一个字都不落库`(@TempDir dir: Path) {
        val f = open(dir)

        onEdt { clickLabel(f.root, ADD_SERVER_LABEL) }

        val opened = f.editor.opened
        assertNotNull(opened, "点加号没开弹窗")
        assertEquals("", opened!!.first.name)
        assertTrue(opened.third, "该按\"新增\"打开")
        assertFalse(Files.exists(mcpJsonPath(dir)), "只是打开框，不该凭空建出文件")
    }

    @Test
    fun `弹窗提交之后才写进文件`(@TempDir dir: Path) {
        val f = open(dir)
        onEdt { clickLabel(f.root, ADD_SERVER_LABEL) }

        onEdt { f.editor.submit!!(McpServer(name = "我的", command = "npx")) }

        val servers = serversIn(dir).servers
        assertEquals(listOf("我的"), servers.map { it.name })
        assertEquals("npx", servers.first().command)
    }

    @Test
    fun `点编辑时把这一条交给弹窗，并排除它自己的名字`(@TempDir dir: Path) {
        Files.writeString(
            mcpJsonPath(dir),
            """{ "mcpServers": { "a": { "command": "a" }, "b": { "command": "b" } } }""",
        )
        val f = open(dir)

        onEdt { clickLabel(f.root, "编辑") }

        val opened = f.editor.opened
        assertEquals("a", opened!!.first.name)
        assertFalse(opened.third, "该按\"编辑\"打开")
        assertEquals(listOf("b"), opened.second, "除自己以外的名字给错了")
    }

    @Test
    fun `重名校验把"认不出的那些"也算上 —— 撞上就是覆盖人家的原件`(@TempDir dir: Path) {
        Files.writeString(
            mcpJsonPath(dir),
            """{ "mcpServers": { "a": { "command": "a" }, "weird": { "type": "sdk" } } }""",
        )
        val f = open(dir)

        onEdt { clickLabel(f.root, "编辑") }

        assertTrue(
            f.editor.opened!!.second.contains("weird"),
            "认不出的名字没算进重名 —— 改个名就能把人家原样保留的那份覆盖掉",
        )
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
        val f = open(dir)

        onEdt { clickLabel(f.root, "编辑") }
        onEdt { f.editor.submit!!(McpServer(name = "a", command = "新命令")) }

        val file = ProjectJson.read(mcpJsonPath(dir))
        assertTrue(file.has("别的工具"), "顶层别的键被弄丢了")
        assertTrue(file.getAsJsonObject("mcpServers").has("weird"), "认不得的那条被弄丢了")
        assertEquals("新命令", file.getAsJsonObject("mcpServers").getAsJsonObject("a").get("command").asString)
    }

    @Test
    fun `名字空着时提交不落库 —— 那种条目读回来会被丢掉`(@TempDir dir: Path) {
        val f = open(dir)

        onEdt { f.page.submit(null, McpServer()) }

        assertFalse(Files.exists(mcpJsonPath(dir)), "空名字的条目被写进文件了")
    }

    @Test
    fun `在弹窗里改名，替换的是原来那条 —— 不是多出一条`(@TempDir dir: Path) {
        Files.writeString(
            mcpJsonPath(dir),
            """{ "mcpServers": { "旧名": { "command": "npx" }, "别的": { "command": "b" } } }""",
        )
        val f = open(dir)
        onEdt { clickLabel(f.root, "编辑") }   // 第一条就是「旧名」

        onEdt { f.editor.submit!!(McpServer(name = "新名", command = "npx")) }

        val names = serversIn(dir).servers.map { it.name }
        assertEquals(listOf("新名", "别的"), names, "改名之后多出一条了")
    }

    @Test
    fun `删除会把这条从文件里去掉`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val f = open(dir)

        onEdt { clickLabel(f.root, "删除") }

        assertTrue(serversIn(dir).servers.isEmpty())
    }

    // ---- 分段：全局 / 认不出的 ----

    @Test
    fun `认不出的那条有自己一张卡，并说清不会动它`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "weird": { "type": "sdk" } } }""")

        val f = open(dir)

        assertNotNull(findLabel(f.root, "weird"), "认不出的那条在这页上还是隐形的")
        assertNotNull(findLabel(f.root, "插件不认这种形状，但一个字都不会动它"))
    }

    @Test
    fun `全局段只列会话里连上、但不在这个文件里的那些`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val status = McpStatus().apply {
            set(
                listOf(
                    McpServerStatus("a", "connected", "project", null, emptyList()),
                    McpServerStatus("global-one", "connected", "user", null, emptyList()),
                ),
            )
        }

        val f = open(dir, status)

        assertNotNull(findLabel(f.root, "global-one"), "全局那条没列出来")
        assertNotNull(findLabel(f.root, "这页只读 —— 插件只写项目里的 .mcp.json"))
    }

    @Test
    fun `一台全局的都没有时不出现那一整段`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val status = McpStatus().apply {
            set(listOf(McpServerStatus("a", "connected", "project", null, emptyList())))
        }

        val f = open(dir, status)

        assertNull(findLabel(f.root, "这页只读 —— 插件只写项目里的 .mcp.json"), "空段不该画出来")
    }

    // ---- 只读与状态的老规矩（一条都没松）----

    @Test
    fun `拿不到项目目录时只读，并说清楚`() {
        val f = open(null)

        assertNotNull(findLabel(f.root, "拿不到项目目录，改不了"), "没说清为什么改不了")
        assertNull(findLabel(f.root, ADD_SERVER_LABEL), "只读时不该给\"添加\"那张卡")
    }

    @Test
    fun `文件里一条都没有时给一句`(@TempDir dir: Path) {
        val f = open(dir)

        assertNotNull(findLabel(f.root, "这个文件里还没有 server"))
    }

    @Test
    fun `还没拿到状态时说清楚，而不是显示成空的`() {
        val f = open(null, McpStatus())

        assertNotNull(findLabel(f.root, "还没拿到 —— 开一次会话后就有"))
    }

    @Test
    fun `认不出的状态照原样显示，不编一个词`() {
        val status = McpStatus().apply {
            set(listOf(McpServerStatus("x", "将来才有的一档", "user", null, emptyList())))
        }
        val f = open(null, status)

        assertNotNull(findLabel(f.root, "将来才有的一档"), "原样值没显示出来")
    }

    @Test
    fun `状态推过来时界面跟着更新`() {
        val status = McpStatus()
        val f = open(null, status)

        onEdt { status.set(listOf(McpServerStatus("后来的", "connected", "user", null, emptyList()))) }

        assertNotNull(findLabel(f.root, "后来的"), "服务推了新状态，界面没跟着动")
    }

    @Test
    fun `dispose 之后不再跟着状态动 —— 不退订就是每开一次设置漏一个监听器`() {
        val status = McpStatus()
        val f = open(null, status)
        onEdt { f.page.dispose() }

        onEdt { status.set(listOf(McpServerStatus("推的", "connected", "user", null, emptyList()))) }

        assertEquals(1, status.servers.size, "服务本身照常记录")
    }

    // ---- 卡脚那几颗会话动作（重连 / 启用停用，2026-10-09 回接到卡片页）----

    /** 这一页上叫这个名字的标签有几个（`findLabel` 只回第一个，数不出来）。 */
    private fun countLabels(root: Container, text: String): Int {
        var n = 0
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JLabel && child.text == text) n++
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return n
    }

    @Test
    fun `没有会话接动作时，一颗按钮都不画`(@TempDir dir: Path) {
        // 画一颗点了没反应的按钮，正是这个仓库最不肯做的事（CardAction 那条）
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val f = open(dir, statusOf(McpServerStatus("a", "connected", "project", null, emptyList())))

        assertNull(findLabel(f.root, "重连"), "没人接的时候不该有「重连」")
        assertNull(findLabel(f.root, "停用"), "没人接的时候不该有「停用」")
    }

    @Test
    fun `有会话时，文件里的卡给重连与停用`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val f = open(
            dir,
            statusOf(McpServerStatus("a", "failed", "project", "连不上", emptyList())),
            armed(mutableListOf()),
        )

        assertNotNull(findLabel(f.root, "重连"), "失败中的 server 最需要重连")
        assertNotNull(findLabel(f.root, "停用"))
    }

    @Test
    fun `点停用，交出去的是 Toggle(name, false)`(@TempDir dir: Path) {
        // false 是"停用"这个正经营生：一路上被当成"没设置"吞掉的话，
        // 用户点了停用而 server 还在跑
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val seen = mutableListOf<McpControl.Action>()
        val f = open(dir, statusOf(McpServerStatus("a", "connected", "project", null, emptyList())), armed(seen))

        onEdt { clickLabel(f.root, "停用") }

        assertEquals(listOf<McpControl.Action>(McpControl.Action.Toggle("a", false)), seen)
    }

    @Test
    fun `点重连，交出去的是 Reconnect(name)`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val seen = mutableListOf<McpControl.Action>()
        val f = open(dir, statusOf(McpServerStatus("a", "failed", "project", "连不上", emptyList())), armed(seen))

        onEdt { clickLabel(f.root, "重连") }

        assertEquals(listOf<McpControl.Action>(McpControl.Action.Reconnect("a")), seen)
    }

    @Test
    fun `已经停用的那条只给启用 —— 再给一颗停用是废话`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val f = open(
            dir,
            statusOf(McpServerStatus("a", "disabled", "project", null, emptyList())),
            armed(mutableListOf()),
        )

        assertNotNull(findLabel(f.root, "启用"))
        assertNull(findLabel(f.root, "停用"), "它本来就是停着的")
        assertNull(findLabel(f.root, "重连"), "停用的 server 重连什么")
    }

    @Test
    fun `会话里还没有这个 server 的卡不给动作 —— 发出去只会拿"没有这个 server"撞回来`(@TempDir dir: Path) {
        // .mcp.json 改了要等新会话：文件里新加的那条会话还不知道。
        // 数一数：两张卡里只有会话认得的 "a" 那张有动作（findLabel 只回第一个，数不出来）
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" }, "b": { "command": "b" } } }""")
        val f = open(
            dir,
            statusOf(McpServerStatus("a", "connected", "project", null, emptyList())),
            armed(mutableListOf()),
        )

        assertNotNull(findLabel(f.root, "b"), "前提：b 那张卡在")
        assertEquals(1, countLabels(f.root, "重连"), "有动作的该只有会话里认得的那张卡")
        assertEquals(1, countLabels(f.root, "停用"))
    }

    @Test
    fun `全局那张卡也给会话动作 —— 它动的就是这个会话`(@TempDir dir: Path) {
        // 全局的那些在这页上不能改不能删（插件只写项目里的 .mcp.json），
        // 但"死了重连一下"恰恰是它们最需要的
        val seen = mutableListOf<McpControl.Action>()
        val f = open(dir, statusOf(McpServerStatus("global-one", "failed", "user", "掉了", emptyList())), armed(seen))

        assertNotNull(findLabel(f.root, "重连"), "全局 server 没有会话动作，那颗按钮在这页上就无处可点")
        onEdt { clickLabel(f.root, "重连") }

        assertEquals(listOf<McpControl.Action>(McpControl.Action.Reconnect("global-one")), seen)
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
    fun `失败那句话显示在最上面，按点的哪一颗挑措辞`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val control = armed(mutableListOf())
        val f = open(dir, statusOf(McpServerStatus("a", "failed", "project", null, emptyList())), control)

        onEdt { control.report(McpControl.Action.Reconnect("a"), "Connection closed") }

        assertTrue(failureShown(f.root, f.page), "重连失败得在框里说得出来 —— 聊天被这个模态框挡着")
        assertEquals(
            CcoderText.text("settings.mcp.reconnectFailed", "a", "Connection closed"),
            f.page.failureArea.text,
        )
        // "最上面"不是措辞：这一块就是列里第一个子件，卡都在它下面
        assertSame(f.page.failureHolder, (f.page.failureHolder.parent as Container).getComponent(0))
    }

    @Test
    fun `停用失败说的是停用，不是重连`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val control = armed(mutableListOf())
        val f = open(dir, statusOf(McpServerStatus("a", "connected", "project", null, emptyList())), control)

        onEdt { control.report(McpControl.Action.Toggle("a", false), "没有了") }

        assertTrue(failureShown(f.root, f.page))
        assertEquals(
            CcoderText.text("settings.mcp.disableFailed", "a", "没有了"),
            f.page.failureArea.text,
            "点的是停用，说的却是重连 —— 用户会以为点错了按钮",
        )
    }

    @Test
    fun `接着点一次，上一次的报错先消失`(@TempDir dir: Path) {
        // 留着的话：用户刚点了重连，却在读上一次的报错，会以为这次也失败了
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val control = armed(mutableListOf())
        val f = open(dir, statusOf(McpServerStatus("a", "failed", "project", null, emptyList())), control)
        onEdt { control.report(McpControl.Action.Reconnect("a"), "Connection closed") }
        assertTrue(failureShown(f.root, f.page), "前提：这会儿它是在的")

        onEdt { clickLabel(f.root, "重连") }

        assertFalse(failureShown(f.root, f.page), "刚点了重连却在读上一次的报错，会以为这次也失败了")
    }

    @Test
    fun `会话断了之后按钮跟着消失`(@TempDir dir: Path) {
        // armed 变了没人通知的话，用户看到的是几颗永远点不动的按钮
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val control = armed(mutableListOf())
        val f = open(dir, statusOf(McpServerStatus("a", "connected", "project", null, emptyList())), control)
        assertNotNull(findLabel(f.root, "重连"))

        onEdt { control.detach("测试") }

        assertNull(findLabel(f.root, "重连"), "没人接了，那颗按钮该跟着消失")
    }

    @Test
    fun `收不下的那一下会把这条线放掉，按钮跟着消失`(@TempDir dir: Path) {
        // 会话在"画按钮"和"点下去"之间断了：这一下没送出去。若服务还攥着那个
        // 处理器，这几颗按钮会一直画着、点一次石沉大海一次
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val control = armed(mutableListOf(), answer = false)
        val f = open(dir, statusOf(McpServerStatus("a", "connected", "project", null, emptyList())), control)

        onEdt { clickLabel(f.root, "停用") }

        assertFalse(control.armed, "收不下就该把线放掉，别攥着一个没会话的处理器")
        assertNull(findLabel(f.root, "停用"), "按钮该跟着消失")
    }

    @Test
    fun `dispose 之后不再跟着动作那条线动`(@TempDir dir: Path) {
        Files.writeString(mcpJsonPath(dir), """{ "mcpServers": { "a": { "command": "a" } } }""")
        val control = armed(mutableListOf())
        val f = open(dir, statusOf(McpServerStatus("a", "failed", "project", null, emptyList())), control)

        onEdt { f.page.dispose() }

        // 退订之后再报一次失败：不该抛，也不该还挂在那儿
        onEdt { control.report(McpControl.Action.Reconnect("a"), "晚了") }

        assertFalse(failureShown(f.root, f.page), "退了订还在跟着画，就是每开一次设置漏一个监听器")
    }
}
