package com.ccoder.settings

import com.ccoder.sidecar.Command
import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.Os
import com.ccoder.sidecar.RunEvent
import com.ccoder.sidecar.RuntimeDep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JTextArea

/**
 * 「运行依赖」那块 UI 的用例。
 *
 * 服务是假的（[depsService]），确认框/剪贴板/浏览器也是假的 —— 这一层要验的是
 * **动作接线**：点下去到底算出了哪个计划、确认真的参与计算没有、文案对不对。
 * 检测与安装本身在 `RuntimeDepsServiceTest` 里。
 */
class RuntimeDepsSectionTest {

    private val scheduler = FakeScheduler()
    private val runs = mutableListOf<FakeRun>()
    private val confirmed = mutableListOf<InstallPlan>()
    private val copied = mutableListOf<String>()
    private val browsed = mutableListOf<String>()
    private var confirmAnswer = true

    /**
     * 开一块。**必须跑一次检测** —— 服务没查过时状态是 [DepStatus.Unknown]，
     * 而 Unknown 不产生动作（那时界面上什么都不该有）。生产路径上这件事由
     * 环境页的 `reload()` 做，这里照做一遍。
     */
    private fun open(
        statuses: Map<RuntimeDep, DepStatus> = ALL_DEPS_MISSING,
        tools: ToolSet = TOOLS_WINDOWS,
        os: Os = Os.WINDOWS,
    ): RuntimeDepsSection {
        val service = depsService(
            probe = { dep, _ -> statuses[dep] ?: DepStatus.NotFound },
            runAsync = scheduler::schedule,
            newRunner = { _, onEvent -> FakeRun(onEvent).also { runs.add(it) } },
        )
        val ui = RuntimeDepsSection(
            deps = service,
            confirm = { plan ->
                confirmed.add(plan)
                confirmAnswer
            },
            copy = { copied.add(it) },
            browse = { browsed.add(it) },
            os = os,
            tools = { tools },
        )
        ui.component()
        ui.recheck()
        scheduler.runAll()   // 两次检测的结果落定
        return ui
    }

    private fun clickOn(target: java.awt.Component) {
        val e = java.awt.event.MouseEvent(
            target, java.awt.event.MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(), 0, 5, 5, 1, false,
        )
        target.mouseListeners.forEach { it.mouseClicked(e) }
    }

    private fun layoutAll(c: Container) {
        c.invalidate()
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    private fun textsIn(root: Container): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Container) {
            when (c) {
                is JLabel -> out += c.text
                is JTextArea -> out += c.text
            }
            for (child in c.components) {
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    @Test
    fun `两个依赖都缺时，各给一颗安装动作`() {
        val ui = open()
        val body = ui.component()

        assertEquals("安装 Node.js", ui.actionLabels[RuntimeDep.NODE]!!.text)
        assertEquals("安装 claude", ui.actionLabels[RuntimeDep.CLAUDE]!!.text)
        assertEquals(NOT_FOUND_TEXT, ui.statusLabels[RuntimeDep.NODE]!!.text)
        assertTrue(textsIn(body).contains(RUNTIME_DEPS_TITLE), "标题应当在：${textsIn(body)}")
    }

    @Test
    fun `确认框拿到的就是即将执行的那条命令`() {
        val ui = open()

        clickOn(ui.actionLabels[RuntimeDep.NODE]!!)

        val plan = confirmed.single()
        assertEquals(InstallRoute.RUN, plan.route)
        assertEquals(
            Command(
                TOOLS_WINDOWS.winget!!,
                listOf(
                    "install", "--id", "OpenJS.NodeJS.LTS", "--exact",
                    "--accept-package-agreements", "--accept-source-agreements",
                    "--disable-interactivity",
                ),
            ),
            plan.command,
            "确认框里显示的必须与命令表逐字相同",
        )
    }

    @Test
    fun `确认没同意就什么都不做 —— 执行器一次都不该被构造`() {
        val ui = open()
        confirmAnswer = false

        clickOn(ui.actionLabels[RuntimeDep.NODE]!!)

        assertEquals(1, confirmed.size, "确认框该弹（被拒绝了）")
        assertTrue(runs.isEmpty(), "用户没同意，却起了进程")
    }

    @Test
    fun `同意之后起执行器，输出逐行落到输出区`() {
        val ui = open()
        val body = ui.component()

        clickOn(ui.actionLabels[RuntimeDep.NODE]!!)
        scheduler.runNext()   // { runner.start() }
        val run = runs.single()

        run.emit(RunEvent.Line("正在下载 node……"))
        run.emit(RunEvent.Line("已安装 24.19.0"))
        run.emit(RunEvent.Line("完成"))

        val texts = textsIn(body)
        assertTrue(texts.any { it.contains("正在下载 node") }, "输出区没见到第一行：$texts")
        assertTrue(texts.any { it.contains("已安装 24.19.0") }, "输出区没见到第二行：$texts")
        assertTrue(texts.any { it.contains(INSTALLING_TEXT) }, "该说「安装中…」：$texts")
        assertEquals(CANCEL_INSTALL_LABEL, ui.actionLabels[RuntimeDep.NODE]!!.text, "安装中那颗动作该是「取消」")
    }

    @Test
    fun `取消的结论是「已取消」，不是「没能装上」`() {
        val ui = open()
        val body = ui.component()
        clickOn(ui.actionLabels[RuntimeDep.NODE]!!)
        scheduler.runNext()

        runs.single().cancel()

        val texts = textsIn(body)
        assertTrue(texts.any { it.contains(INSTALL_CANCELLED_TEXT) }, "实际文案：$texts")
        assertFalse(texts.any { it.contains(INSTALL_FAILED_TEXT) }, "取消不该说成失败：$texts")
    }

    @Test
    fun `装不了的时候那颗动作改说「复制命令并打开安装页」`() {
        val ui = open(tools = ToolSet(null, null, null))

        assertEquals(MANUAL_LABEL, ui.actionLabels[RuntimeDep.NODE]!!.text)

        clickOn(ui.actionLabels[RuntimeDep.NODE]!!)

        assertEquals(listOf(NODE_DOWNLOAD_URL), copied)
        assertEquals(listOf(NODE_DOWNLOAD_URL), browsed)
        assertTrue(confirmed.isEmpty(), "兜底那条路不该弹确认框（没有命令要跑）")
        assertTrue(runs.isEmpty())
    }

    @Test
    fun `node 太旧时给的是「升级」而不是「安装」`() {
        val ui = open(
            statuses = mapOf(
                RuntimeDep.NODE to DepStatus.TooOld("C:\\nodejs\\node.exe", "16.20.2", 18),
                RuntimeDep.CLAUDE to DepStatus.NotFound,
            ),
        )

        assertEquals("升级 Node.js", ui.actionLabels[RuntimeDep.NODE]!!.text)
        assertTrue(ui.statusLabels[RuntimeDep.NODE]!!.text.contains("16.20.2"), "状态里该带上实测版本")
    }

    @Test
    fun `可用的依赖不给动作`() {
        val ui = open(statuses = ALL_DEPS_OK)

        assertEquals("", ui.actionLabels[RuntimeDep.NODE]!!.text)
        assertEquals("", ui.actionLabels[RuntimeDep.CLAUDE]!!.text)
        assertTrue(ui.statusLabels[RuntimeDep.CLAUDE]!!.text.contains("2.1.268"))
    }

    @Test
    fun `两行状态文字从同一条竖线开始`() {
        val ui = open()
        val body = ui.component()
        body.setSize(Dimension(600, 400))
        layoutAll(body)

        val node = ui.statusLabels[RuntimeDep.NODE]!!
        val claude = ui.statusLabels[RuntimeDep.CLAUDE]!!
        assertEquals(
            node.location.x,
            claude.location.x,
            "两行错开了 —— 名字那一列的宽度没钉死（「Node.js」比「claude」长）",
        )
    }

    /**
     * 这条是**量出来**的（2026-09-17 出图看出来的）：`BoxLayout` 那一列里
     * 只要混着两种 `alignmentX`（行是默认的 0.5、输出区是 0.0），0.0 的那些就会被
     * 挤成半宽**并推到右半边**，压在下面的表上。统一之后它们才占满整列。
     */
    @Test
    fun `整列的子件用同一个 alignmentX，输出区占满整列`() {
        val ui = open()
        val body = ui.component()
        clickOn(ui.actionLabels[RuntimeDep.NODE]!!)
        scheduler.runNext()
        runs.single().emit(RunEvent.Line("第一行"))
        body.setSize(Dimension(656, 500))
        layoutAll(body)

        val aligns = body.components.map { it.alignmentX }.distinct()
        assertEquals(1, aligns.size, "子件的 alignmentX 不一致：$aligns")
        assertEquals(656, ui.logScroll.width, "输出区没占满整列 —— 它会被画到右半边去")
    }

    @Test
    fun `输出区的高度是定死的，不会变成页里的弹簧`() {
        val ui = open()

        assertEquals(
            ui.logScroll.preferredSize.height,
            ui.logScroll.maximumSize.height,
            "给了 Int.MAX_VALUE 它就会把整页顶开（tableBox 与 modelListBox 各踩过一次）",
        )
    }
}
