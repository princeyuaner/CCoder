package com.ccoder.settings

import com.ccoder.sidecar.Command
import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.Os
import com.ccoder.sidecar.RuntimeDep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 命令表与兜底路径的用例。**逐字**比对命令 —— 这里每个字都是实测核对过的
 * （设计稿 §2 那张事实表），改命令必须连着改用例。
 */
class InstallCommandsTest {

    private val winget = "C:\\Users\\tester\\AppData\\Local\\Microsoft\\WindowsApps\\winget.exe"
    private val brew = "/opt/homebrew/bin/brew"
    private val npmWin = "C:\\Program Files\\nodejs\\npm.cmd"
    private val npmMac = "/usr/local/bin/npm"
    private val nodeDir = "C:\\Program Files\\nodejs"

    private fun missing() = DepStatus.NotFound
    private fun old() = DepStatus.TooOld("C:\\Program Files\\nodejs\\node.exe", "16.20.2", 18)
    private fun ok(version: String = "24.13.1") = DepStatus.Ok(nodeDir + "\\node.exe", version)

    // ---- node ------------------------------------------------------------

    @Test
    fun `Windows 缺 node 走 winget，三个 flag 一个不能少`() {
        val plan = installPlan(RuntimeDep.NODE, missing(), ToolSet(winget, null, npmWin), Os.WINDOWS)

        assertEquals(InstallRoute.RUN, plan.route)
        assertEquals(InstallAction.INSTALL, plan.action)
        assertEquals(
            Command(
                winget,
                listOf(
                    "install", "--id", "OpenJS.NodeJS.LTS", "--exact",
                    "--accept-package-agreements", "--accept-source-agreements",
                    "--disable-interactivity",
                ),
            ),
            plan.command,
        )
        assertEquals(plan.command!!.display(), plan.copyText, "RUN 时复制的那份就是即将执行的那句")
        assertTrue(plan.note.contains("UAC"), "必须提前说清会弹管理员权限：${plan.note}")
    }

    @Test
    fun `Windows 的 node 太旧时换成 upgrade`() {
        val plan = installPlan(RuntimeDep.NODE, old(), ToolSet(winget, null, npmWin), Os.WINDOWS)

        assertEquals(InstallAction.UPGRADE, plan.action)
        assertEquals("upgrade", plan.command!!.args.first())
    }

    @Test
    fun `Windows 没有 winget 就退到官网，不编一条跑不通的命令`() {
        val plan = installPlan(RuntimeDep.NODE, missing(), ToolSet(null, null, null), Os.WINDOWS)

        assertEquals(InstallRoute.MANUAL, plan.route)
        assertNull(plan.command)
        assertEquals(NODE_DOWNLOAD_URL, plan.copyText)
        assertEquals(NODE_DOWNLOAD_URL, plan.url)
    }

    @Test
    fun `macOS 有 brew 就代跑`() {
        val plan = installPlan(RuntimeDep.NODE, missing(), ToolSet(null, brew, npmMac), Os.MAC)

        assertEquals(Command(brew, listOf("install", "node")), plan.command)
    }

    @Test
    fun `macOS 没 brew 也退到官网`() {
        val plan = installPlan(RuntimeDep.NODE, missing(), ToolSet(null, null, npmMac), Os.MAC)

        assertEquals(InstallRoute.MANUAL, plan.route)
        assertEquals(NODE_DOWNLOAD_URL, plan.copyText)
    }

    // ---- claude ----------------------------------------------------------

    @Test
    fun `claude 走 npm 全局装，带 at-latest`() {
        val plan = installPlan(
            RuntimeDep.CLAUDE, missing(), ToolSet(null, null, npmWin), Os.WINDOWS,
            nodeDir = nodeDir, nodeMajor = 24,
        )

        assertEquals(InstallRoute.RUN, plan.route)
        assertEquals(Command(npmWin, listOf("install", "-g", "@anthropic-ai/claude-code@latest")), plan.command)
    }

    @Test
    fun `node 不到 22 时不装 claude，先把话说清楚`() {
        val plan = installPlan(
            RuntimeDep.CLAUDE, missing(), ToolSet(null, null, npmWin), Os.WINDOWS,
            nodeDir = nodeDir, nodeMajor = 20,
        )

        assertEquals(InstallRoute.MANUAL, plan.route)
        assertTrue(plan.note.contains("22"), "提示里必须点明 22 这个门槛：${plan.note}")
        assertTrue(plan.note.contains("20"), "也要说明现在是多少：${plan.note}")
        assertEquals(CLAUDE_SETUP_URL, plan.url)
    }

    @Test
    fun `没有 npm 就只给官方文档 —— 复制一条跑不了的命令没有意义`() {
        val plan = installPlan(RuntimeDep.CLAUDE, missing(), ToolSet(null, null, null), Os.WINDOWS)

        assertEquals(InstallRoute.MANUAL, plan.route)
        assertEquals(CLAUDE_SETUP_URL, plan.copyText)
        assertEquals(CLAUDE_SETUP_URL, plan.url)
    }

    // ---- Linux -----------------------------------------------------------

    @Test
    fun `Linux 一律不代跑，两个依赖都是复制加打开`() {
        val node = installPlan(RuntimeDep.NODE, missing(), ToolSet(null, null, npmMac), Os.LINUX)
        val claude = installPlan(RuntimeDep.CLAUDE, missing(), ToolSet(null, null, npmMac), Os.LINUX)

        assertEquals(InstallRoute.MANUAL, node.route)
        assertNull(node.command)
        assertEquals(NODE_DOWNLOAD_URL, node.copyText)
        assertEquals(InstallRoute.MANUAL, claude.route)
        assertEquals("npm install -g @anthropic-ai/claude-code@latest", claude.copyText)
        assertEquals(CLAUDE_SETUP_URL, claude.url)
    }

    // ---- 命令的展示形态 --------------------------------------------------

    @Test
    fun `带空格的程序路径在给用户看的这一行里补引号`() {
        val c = Command("C:\\Program Files\\nodejs\\npm.cmd", listOf("install", "-g", "@anthropic-ai/claude-code@latest"))

        assertEquals(
            "\"C:\\Program Files\\nodejs\\npm.cmd\" install -g @anthropic-ai/claude-code@latest",
            c.display(),
        )
    }

    @Test
    fun `工具解析：npm 挨着 node 找，winget 在 WindowsApps，brew 在 opt`() {
        val files = setOf(npmWin, winget)
        val tools = toolSet(
            os = Os.WINDOWS,
            env = mapOf("PATH" to "", "LOCALAPPDATA" to "C:\\Users\\tester\\AppData\\Local"),
            home = java.nio.file.Path.of("C:\\Users\\tester"),
            isFile = { it in files },
            nodeDir = nodeDir,
        )

        assertEquals(npmWin, tools.npm)
        assertEquals(winget, tools.winget)
        assertNull(tools.brew)
    }
}
