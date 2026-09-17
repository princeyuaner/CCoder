package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * 依赖发现与判定的纯逻辑用例。
 *
 * 这里**不真起进程**（那是 [probeRuntimeDep] 最后一行的口子，被测例注入掉了），
 * 也不真碰文件系统 —— 四个口子（env / home / isFile / runVersion）全可注入。
 */
class RuntimeDepsTest {

    private val home: Path = Path.of("C:\\Users\\tester")
    private val env = mapOf(
        "PATH" to "C:\\first;C:\\second",
        "APPDATA" to "C:\\Users\\tester\\AppData\\Roaming",
        "LOCALAPPDATA" to "C:\\Users\\tester\\AppData\\Local",
        "ProgramFiles" to "C:\\Program Files",
        "ProgramFiles(x86)" to "C:\\Program Files (x86)",
    )

    private fun win(name: String, vararg parts: String) = Path.of(name, *parts).toString()

    // ---- 候选名 ----------------------------------------------------------

    @Test
    fun `claude 在 Windows 上有三个候选名且 exe 优先`() {
        assertEquals(listOf("claude.exe", "claude.cmd", "claude"), executableNames(RuntimeDep.CLAUDE, Os.WINDOWS))
    }

    @Test
    fun `claude 在 POSIX 上只有一个候选名`() {
        assertEquals(listOf("claude"), executableNames(RuntimeDep.CLAUDE, Os.MAC))
        assertEquals(listOf("claude"), executableNames(RuntimeDep.CLAUDE, Os.LINUX))
    }

    @Test
    fun `node 在 Windows 上找 exe 与裸名`() {
        assertEquals(listOf("node.exe", "node"), executableNames(RuntimeDep.NODE, Os.WINDOWS))
        assertEquals(listOf("node"), executableNames(RuntimeDep.NODE, Os.LINUX))
    }

    // ---- 三级候选 --------------------------------------------------------

    @Test
    fun `显式路径非空时只给这一条 —— 不回退到 PATH`() {
        val got = candidatePaths(RuntimeDep.CLAUDE, "C:\\mine\\claude.exe", env["PATH"]!!, Os.WINDOWS, env, home)

        assertEquals(listOf("C:\\mine\\claude.exe"), got)
    }

    @Test
    fun `显式路径只有空白也算没填`() {
        val got = candidatePaths(RuntimeDep.CLAUDE, "   ", env["PATH"]!!, Os.WINDOWS, env, home)

        assertTrue(got.first().startsWith("C:\\first"), "应按 PATH 解析，实际：${got.first()}")
    }

    @Test
    fun `PATH 在前、已知目录在后，且各自内部的顺序保持`() {
        val got = candidatePaths(RuntimeDep.CLAUDE, null, env["PATH"]!!, Os.WINDOWS, env, home)

        assertEquals(win("C:\\first", "claude.exe"), got[0])
        assertEquals(win("C:\\first", "claude.cmd"), got[1])
        assertEquals(win("C:\\first", "claude"), got[2])
        assertEquals(win("C:\\second", "claude.exe"), got[3])

        // 已知目录里 npm 全局那一站要出现，而且排在 PATH 之后
        val npmDir = win("C:\\Users\\tester\\AppData\\Roaming", "npm", "claude.cmd")
        assertTrue(got.contains(npmDir), "缺 %APPDATA%\\npm 那一站")
        assertTrue(got.indexOf(npmDir) > 3)
    }

    @Test
    fun `PATH 里带引号的条目只是被跳过，不会把检测带崩`() {
        // 实测（2026-09-17）：这台机器的 PATH 里就有一条 `"C:\WINDOWS\system32\WBEM"`
        // 那种带引号的条目，某些安装器会这么写。Path.of 碰到引号会抛
        // InvalidPathException —— 不兜住的话，"打开设置"会崩在一条与本插件毫无关系的 PATH 条目上
        val dirty = mapOf("PATH" to "\"C:\\WINDOWS\\system32\\WBEM\";C:\\first")

        val got = candidatePaths(RuntimeDep.CLAUDE, null, dirty["PATH"]!!, Os.WINDOWS, dirty, home)

        assertTrue(got.any { it.startsWith("C:\\first") }, "好条目还得照样用：$got")
        assertTrue(got.none { it.contains('"') }, "坏条目不该进候选：$got")
    }

    @Test
    fun `PATH 为空也还有已知目录可找`() {
        val got = candidatePaths(RuntimeDep.CLAUDE, null, "", Os.WINDOWS, env, home)

        assertEquals(win("C:\\Program Files", "nodejs", "claude.exe"), got[0])
    }

    @Test
    fun `环境变量缺失时整条模板丢弃，而不是留下一个盘根路径`() {
        val bare = mapOf("PATH" to "")
        val got = candidatePaths(RuntimeDep.NODE, null, "", Os.WINDOWS, bare, home)

        // win32 的九条模板全部含 %VAR%，变量表空 → 一条都不该剩。
        // 只把变量替换成空串的话会得到 `\nodejs\node.exe` 这种指向盘根的东西
        assertEquals(emptyList<String>(), got)
    }

    // ---- 模板展开 --------------------------------------------------------

    @Test
    fun `带括号的变量名也能展开`() {
        val got = expandDirTemplate("%ProgramFiles(x86)%\\nodejs", Os.WINDOWS, env, home)

        assertEquals(listOf(Path.of("C:\\Program Files (x86)\\nodejs")), got)
    }

    @Test
    fun `波浪号展开成 home`() {
        val got = expandDirTemplate("~/.local/bin", Os.LINUX, env, home)

        assertEquals(listOf(Path.of(home.toString(), ".local", "bin")), got)
    }

    @Test
    fun `nvm 的星号取版本最高的那一支 —— 不是字典序最高的`() {
        val dirs = listOf("v9.11.2", "v20.1.0", "v10.24.1", "bin", "node_modules")
        val got = expandDirTemplate(
            "~/.nvm/versions/node/*/bin", Os.LINUX, env, home,
            listDir = { dirs },
        )

        assertEquals(listOf(Path.of(home.toString(), ".nvm", "versions", "node", "v20.1.0", "bin")), got)
    }

    @Test
    fun `nvm 目录里一个版本都没有时返回空`() {
        assertEquals(emptyList<Path>(), expandDirTemplate("~/.nvm/versions/node/*/bin", Os.LINUX, env, home) { emptyList() })
    }

    @Test
    fun `挑版本目录的纯函数认 v 前缀也认裸版本号`() {
        assertEquals("v20.1.0", highestNodeVersionDir(listOf("v20.1.0", "9.11.2")))
        assertEquals("10.24.1", highestNodeVersionDir(listOf("10.24.1", "9.0.0")))
        assertNull(highestNodeVersionDir(listOf("current", "default")))
        assertNull(highestNodeVersionDir(emptyList()))
    }

    // ---- 版本解析与判定 --------------------------------------------------

    @Test
    fun `版本号从两种真实输出里都能抠出来`() {
        assertEquals("24.13.1", parseVersion("v24.13.1"))
        assertEquals("2.1.268", parseVersion("2.1.268 (Claude Code)"))
        assertEquals("11.8.0", parseVersion("11.8.0\n"))
        assertEquals("2.1.268", parseVersion("  2.1.268  "))
        assertNull(parseVersion("command not found"))
    }

    @Test
    fun `判定四分支各说各的话`() {
        assertEquals(DepStatus.NotFound, decideStatus(RuntimeDep.NODE, null, "v24.13.1"))
        assertEquals(
            DepStatus.Broken("C:\\n\\node.exe", "找到了，但读不出版本号"),
            decideStatus(RuntimeDep.NODE, "C:\\n\\node.exe", null),
        )
        assertEquals(
            DepStatus.TooOld("C:\\n\\node.exe", "16.20.2", NodeCheck.MIN_MAJOR),
            decideStatus(RuntimeDep.NODE, "C:\\n\\node.exe", "v16.20.2"),
        )
        assertEquals(
            DepStatus.Ok("C:\\n\\node.exe", "24.13.1"),
            decideStatus(RuntimeDep.NODE, "C:\\n\\node.exe", "v24.13.1"),
        )
    }

    @Test
    fun `node 的下限就是 NodeCheck 那一个 —— 不再复制一份`() {
        assertEquals(NodeCheck.MIN_MAJOR, 18)
        val atMin = decideStatus(RuntimeDep.NODE, "p", "v18.0.0", NodeCheck.MIN_MAJOR)
        assertTrue(atMin is DepStatus.Ok, "18 本身要算通过，实际：$atMin")
    }

    @Test
    fun `claude 没有版本下限，版本读不出来也不算失败得太难看`() {
        assertEquals(DepStatus.Ok("C:\\c\\claude.exe", "2.1.268"), decideStatus(RuntimeDep.CLAUDE, "C:\\c\\claude.exe", "2.1.268 (Claude Code)"))
        assertEquals(DepStatus.Ok("C:\\c\\claude.exe", "1.0.0"), decideStatus(RuntimeDep.CLAUDE, "C:\\c\\claude.exe", "1.0.0"))
    }

    // ---- 探测（口子全注入） ----------------------------------------------

    @Test
    fun `显式路径不存在就是坏，不回退到 PATH 里那个`() {
        val files = setOf(win("C:\\first", "claude.exe"))
        val got = probeRuntimeDep(
            RuntimeDep.CLAUDE,
            explicit = "C:\\mine\\claude.exe",
            os = Os.WINDOWS,
            env = env,
            home = home,
            isFile = { it in files },
            runVersion = { "9.9.9" },
        )

        assertEquals(DepStatus.Broken("C:\\mine\\claude.exe", "设置里指定的路径不存在"), got)
    }

    @Test
    fun `PATH 里没有但已知目录里有 —— 这正是装完 node 之后的情形`() {
        val appData = win("C:\\Users\\tester\\AppData\\Roaming", "npm", "claude.cmd")
        val got = probeRuntimeDep(
            RuntimeDep.CLAUDE,
            os = Os.WINDOWS,
            env = env + ("PATH" to ""),   // IDE 的 PATH 还是旧的，什么都找不到
            home = home,
            isFile = { it == appData },
            runVersion = { "2.1.268 (Claude Code)" },
        )

        assertEquals(DepStatus.Ok(appData, "2.1.268"), got)
    }

    @Test
    fun `三级都找不到就是 NotFound`() {
        val got = probeRuntimeDep(
            RuntimeDep.NODE, os = Os.WINDOWS, env = env, home = home,
            isFile = { false }, runVersion = { "v24.0.0" },
        )

        assertEquals(DepStatus.NotFound, got)
    }

    @Test
    fun `找到但跑不起来时报 Broken 且带上路径`() {
        val node = win("C:\\first", "node.exe")
        val got = probeRuntimeDep(
            RuntimeDep.NODE, os = Os.WINDOWS, env = env, home = home,
            isFile = { it == node }, runVersion = { null },
        )

        assertEquals(DepStatus.Broken(node, "找到了，但读不出版本号"), got)
    }

    // ---- 工具查找 --------------------------------------------------------

    @Test
    fun `npm 先看 node 旁边，再看 PATH`() {
        val beside = win("C:\\Program Files\\nodejs", "npm.cmd")
        val got = findTool(
            listOf("npm.cmd"), Os.WINDOWS, env, home,
            isFile = { it == beside || it == win("C:\\first", "npm.cmd") },
            nodeDir = "C:\\Program Files\\nodejs",
        )

        assertEquals(beside, got)
    }

    @Test
    fun `nodeDir 那条路落空时回到 PATH`() {
        val onPath = win("C:\\second", "npm.cmd")
        val got = findTool(
            listOf("npm.cmd"), Os.WINDOWS, env, home,
            isFile = { it == onPath }, nodeDir = "C:\\elsewhere",
        )

        assertEquals(onPath, got)
    }

    @Test
    fun `winget 住在 WindowsApps —— 那个目录未必在 IDE 的 PATH 里`() {
        val winget = win("C:\\Users\\tester\\AppData\\Local\\Microsoft\\WindowsApps", "winget.exe")
        val got = findTool(listOf("winget.exe"), Os.WINDOWS, env, home, isFile = { it == winget })

        assertNotNull(got)
        assertEquals(winget, got)
    }
}
