package com.ccoder.settings

import com.ccoder.sidecar.Command
import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.Os
import com.ccoder.sidecar.RuntimeDep
import com.ccoder.sidecar.findTool
import com.ccoder.sidecar.hostOs
import com.ccoder.sidecar.majorOf
import com.ccoder.text.CcoderText
import java.io.File
import java.nio.file.Path

/**
 * 「安装/升级」这条路上**要跑什么、装不了时给什么**。
 *
 * 设计稿 `docs/superpowers/specs/2026-09-17-runtime-deps-design.md` §3.6。
 * 命令表里的每个字都对着实测核对过（§2 那张事实表）——**改这里之前先跑一遍**。
 *
 * ## 三条硬规矩
 *
 * 1. **绝不静默执行**：只有 [InstallRoute.RUN] 才代跑，而它必须经过确认框
 *    （正文显示的就是 [InstallPlan.command] 的 `display()`，逐字）。
 * 2. **不编跑不通的命令**：包管理器不在时就退 [InstallRoute.MANUAL]，
 *    那时复制的是**官方页面地址**而不是一条这台机器上跑不起来的命令。
 * 3. **Linux 一律 MANUAL**：发行版差异太大且普遍要 sudo，只给命令与官方页。
 */

/** 装还是升级。界面上两颗动作的文案由它决定。 */
enum class InstallAction(private val labelKey: String) {
    INSTALL("settings.deps.action.install.label"),
    UPGRADE("settings.deps.action.upgrade.label"),
    ;

    /**
     * 动作的那个动词（`安装` / `Install`）。
     *
     * 与依赖名之间**留一个空格**：本仓文案里拉丁词两侧都留（见 [installLabel]）。
     */
    val labelVerb: String get() = CcoderText.text(labelKey)
}

/** 怎么落地：代跑，还是把命令/地址交给用户。 */
enum class InstallRoute {
    /** 插件代跑（先过确认框）。 */
    RUN,

    /** 复制 + 打开官方页，用户自己来。 */
    MANUAL,
}

/**
 * 一次安装的完整计划。
 *
 * [command] 与 [copyText] 的关系是刻意的：RUN 时 `copyText == command.display()`
 * （用户想把命令留着自己跑也行），MANUAL 时 `command == null`。
 */
data class InstallPlan(
    val dep: RuntimeDep,
    val action: InstallAction,
    val route: InstallRoute,
    val command: Command?,
    val copyText: String,
    val url: String?,
    val note: String,
)

/** 这台机器上有哪些包管理器/工具，值是解析出来的绝对路径。 */
data class ToolSet(val winget: String?, val brew: String?, val npm: String?) {
    val any: Boolean get() = winget != null || brew != null || npm != null
}

/** winget 里的包 ID。实测：`winget list --id OpenJS.NodeJS.LTS --exact` 命中已装的 node。 */
internal const val WINGET_NODE_ID = "OpenJS.NodeJS.LTS"

/** npm 包名。官方文档里的命令带 `@latest`。 */
internal const val CLAUDE_NPM_PACKAGE = "@anthropic-ai/claude-code@latest"

/**
 * npm 装 claude 的 node 下限。
 *
 * **与 sidecar 的 18 不是同一个门槛**（实测 `npm view @anthropic-ai/claude-code engines`
 * = `node >= 22.0.0`）。node 只有 18–21 时 npm 装得下来但跑不起来 ——
 * 与其装出一个坏的，不如让用户先升级 node。
 */
internal const val MIN_NODE_MAJOR_FOR_NPM_CLAUDE = 22

internal const val NODE_DOWNLOAD_URL = "https://nodejs.org/en/download"
internal const val CLAUDE_SETUP_URL = "https://docs.claude.com/en/docs/claude-code/setup"

/**
 * 解析这台机器上的工具。四个口子都可注入 —— 用例不必真装 winget/brew。
 *
 * [nodeDir] 由调用方给（node 探针的结果）：npm 就在它旁边。
 */
internal fun toolSet(
    os: Os = hostOs(),
    env: Map<String, String> = System.getenv(),
    home: Path = Path.of(System.getProperty("user.home") ?: "."),
    isFile: (String) -> Boolean = { File(it).isFile },
    nodeDir: String? = null,
): ToolSet {
    val exe = os == Os.WINDOWS
    return ToolSet(
        winget = findTool(listOf("winget.exe", "winget"), os, env, home, isFile),
        brew = findTool(listOf("brew"), os, env, home, isFile),
        npm = findTool(if (exe) listOf("npm.cmd", "npm.exe", "npm") else listOf("npm"), os, env, home, isFile, nodeDir),
    )
}

/**
 * 出一个计划。纯函数（除了 [tools] 是已解析好的路径），用例逐格比对命令。
 *
 * [nodeMajor] 只有装 claude 时才用得上：npm 那条路要求 node ≥ 22。
 */
internal fun installPlan(
    dep: RuntimeDep,
    status: DepStatus,
    tools: ToolSet,
    os: Os = hostOs(),
    nodeDir: String? = null,
    nodeMajor: Int? = null,
): InstallPlan {
    val action = if (status is DepStatus.TooOld) InstallAction.UPGRADE else InstallAction.INSTALL

    if (os == Os.LINUX) {
        return manual(
            dep, action,
            copyText = linuxCopyText(dep),
            url = if (dep == RuntimeDep.NODE) NODE_DOWNLOAD_URL else CLAUDE_SETUP_URL,
            note = CcoderText.text("settings.deps.note.linux"),
        )
    }

    return when (dep) {
        RuntimeDep.NODE -> nodePlan(action, tools, os)
        RuntimeDep.CLAUDE -> claudePlan(action, tools, nodeDir, nodeMajor)
    }
}

/** node：Windows 走 winget、macOS 走 brew；两者都没有就交给用户。 */
private fun nodePlan(action: InstallAction, tools: ToolSet, os: Os): InstallPlan {
    val verb = if (action == InstallAction.UPGRADE) "upgrade" else "install"

    when (os) {
        Os.WINDOWS -> {
            val winget = tools.winget
                ?: return manual(
                    RuntimeDep.NODE, action, NODE_DOWNLOAD_URL, NODE_DOWNLOAD_URL,
                    CcoderText.text("settings.deps.note.noWinget"),
                )
            // 三个 --accept-* / --disable-interactivity **不是装饰**：少一个就可能原地等一个
            // 没人能回答的交互，界面卡在「安装中…」直到超时（设计稿 §3.6）
            val args = listOf(
                verb, "--id", WINGET_NODE_ID, "--exact",
                "--accept-package-agreements", "--accept-source-agreements",
                "--disable-interactivity",
            )
            return run(RuntimeDep.NODE, action, Command(winget, args),
                CcoderText.text("settings.deps.note.uac"))
        }

        Os.MAC -> {
            val brew = tools.brew
                ?: return manual(
                    RuntimeDep.NODE, action, NODE_DOWNLOAD_URL, NODE_DOWNLOAD_URL,
                    CcoderText.text("settings.deps.note.noBrew"),
                )
            return run(RuntimeDep.NODE, action, Command(brew, listOf(verb, "node")), "")
        }

        Os.LINUX -> error("Linux 在 installPlan 开头就返回了")
    }
}

/** claude：npm 全局装。npm 不在、或 node 太旧，都退回"看文档"。 */
private fun claudePlan(
    action: InstallAction,
    tools: ToolSet,
    nodeDir: String?,
    nodeMajor: Int?,
): InstallPlan {
    val npm = tools.npm
    if (npm == null) {
        return manual(
            RuntimeDep.CLAUDE, action, CLAUDE_SETUP_URL, CLAUDE_SETUP_URL,
            // 「重新检测」那颗按钮的名字从词表取，不在句子里抄一份 ——
            // 抄了就会有两处要改，而改了按钮忘了句子看起来像界面坏了
            CcoderText.text("settings.deps.note.noNpm", RECHECK_LABEL),
        )
    }
    val npmCommand = Command(npm, listOf("install", "-g", CLAUDE_NPM_PACKAGE))
    val major = nodeMajor ?: majorOf(null)

    if (major != null && major < MIN_NODE_MAJOR_FOR_NPM_CLAUDE) {
        return manual(
            RuntimeDep.CLAUDE, action, npmCommand.display(), CLAUDE_SETUP_URL,
            CcoderText.text("settings.deps.note.nodeTooOld", MIN_NODE_MAJOR_FOR_NPM_CLAUDE, major),
        )
    }
    return run(RuntimeDep.CLAUDE, action, npmCommand, "")
}

/** Linux 的兜底文案：claude 有官方一行命令，node 只有下载页。 */
private fun linuxCopyText(dep: RuntimeDep): String = when (dep) {
    RuntimeDep.CLAUDE -> "npm install -g $CLAUDE_NPM_PACKAGE"
    RuntimeDep.NODE -> NODE_DOWNLOAD_URL
}

private fun run(dep: RuntimeDep, action: InstallAction, command: Command, note: String) = InstallPlan(
    dep = dep,
    action = action,
    route = InstallRoute.RUN,
    command = command,
    copyText = command.display(),
    url = null,
    note = note,
)

private fun manual(
    dep: RuntimeDep,
    action: InstallAction,
    copyText: String,
    url: String,
    note: String,
) = InstallPlan(
    dep = dep,
    action = action,
    route = InstallRoute.MANUAL,
    command = null,
    copyText = copyText,
    url = url,
    note = note,
)
