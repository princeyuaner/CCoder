package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import com.ccoder.text.CcoderText
import com.ccoder.ui.Tone

/**
 * 卡片与弹窗上那几行字 —— **纯函数**，单测直接打（同 `McpConfig` 里 args/env 的转换）。
 *
 * 2026-09-30 改版（`docs/design/mcp-page.html` 方案一）：MCP 页从"左列表 + 右常驻表单"
 * 变成"一台 server 一张卡"，于是这些字从"表单里的控件"挪到了"卡片上的几行"。
 * 挪出来顺手办了两件事：
 *
 *  1. **变量只列键名、值一个字都不出现**（[mcpVarsLineOf]）。卡片是会被截图、被投屏的
 *     地方，而 `env` / `headers` 里装的多半是 token。要看值请开弹窗，或直接看文件。
 *  2. **状态取不到就不画**（[mcpStatusOf]），不编一个"未连接" —— 那是把"还没问过"
 *     说成了"坏了"（`McpStatus.known` 那条老规矩）。
 */

/**
 * 卡片正文第一行：stdio 是"命令 + 参数"，远端是 URL。
 *
 * 参数**原样连写**（不是只报个数）：这是页面上唯一能分辨"两台 npx 长得像的 server"的
 * 地方（探针里那两台就是同一个命令不同参数）。
 */
internal fun mcpCommandLineOf(server: McpServer): String = when (server.mcpKind()) {
    McpKind.STDIO -> (listOf(server.command) + server.args)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    McpKind.SSE, McpKind.HTTP -> server.url.trim()
}

/** 变量那行的键名，stdios 取 `env`、远端取 `headers`。顺序按文件里出现的顺序。 */
internal fun mcpVarKeysOf(server: McpServer): List<String> = when (server.mcpKind()) {
    McpKind.STDIO -> server.env.keys.toList()
    McpKind.SSE, McpKind.HTTP -> server.headers.keys.toList()
}.filter { it.isNotBlank() }

/**
 * 变量那一行（**只有键名**）；一个都没有就是 null —— 那一行不画。
 *
 * 键名之间用词表里的顿号（`common.listSeparator`）：英文那份是 `, `，中文是 `、`。
 */
internal fun mcpVarsLineOf(server: McpServer): String? {
    val keys = mcpVarKeysOf(server)
    if (keys.isEmpty()) return null
    val joined = keys.joinToString(CcoderText.text("common.listSeparator"))
    return if (server.mcpKind() == McpKind.STDIO) {
        CcoderText.text("settings.mcp.varsEnv", keys.size, joined)
    } else {
        CcoderText.text("settings.mcp.varsHeaders", keys.size, joined)
    }
}

/**
 * 会话里那台的状态。**没拿到就是 null**：没问过（`known == false`）或这个会话里没有它。
 *
 * null 的后果是"那台不画状态位"，而不是画一个假的 —— 造一份假的"未连接"会让用户
 * 去查一个不存在的毛病（`McpStatus` 类注释里那条）。
 */
internal fun mcpStatusOf(status: McpStatus, name: String): McpServerStatus? =
    if (status.known) status.servers.firstOrNull { it.name == name } else null

/**
 * 状态说成人话（连上的带工具数）。
 *
 * **认不出的照原样显示**，不编一个词：CLI 将来加一档时，用户至少能看见那个原始值
 * （老 `statusTail` 的规矩，一个字没改）。
 *
 * 工具数**分了单复数两条键**：英文里 "1 tools" 是错的，而一个只挂一件工具的 server
 * 完全常见。
 */
internal fun mcpStatusTextOf(server: McpServerStatus): String {
    val text = mcpStatusWordOf(server.status)
    if (server.status != "connected" || server.tools.isEmpty()) return text
    val count = server.tools.size
    // 键**字面量写在调用点上**（两个分支各一条）：词表与源码的对账靠源码扫描
    return if (count == 1) {
        CcoderText.text("settings.mcp.status.toolsOne", text, count)
    } else {
        CcoderText.text("settings.mcp.status.tools", text, count)
    }
}

/** 状态词本身（不带工具数）。CLI 的线上取值一个个对着词表取。 */
internal fun mcpStatusWordOf(status: String): String = when (status) {
    "connected" -> CcoderText.text("settings.mcp.status.connected")
    "failed" -> CcoderText.text("settings.mcp.status.failed")
    "needs-auth" -> CcoderText.text("settings.mcp.status.needsAuth")
    "pending" -> CcoderText.text("settings.mcp.status.pending")
    "disabled" -> CcoderText.text("settings.mcp.status.disabled")
    else -> status
}

/**
 * 状态配哪一档颜色。
 *
 * 用**现成的** [Tone]（状态卡那套）而不是自己再列一个枚举：同一个"失败"在转写区是
 * 红的、在设置页也该是红的，两处各点一支色迟早一深一浅。
 */
internal fun mcpToneOf(status: String): Tone = when (status) {
    "connected" -> Tone.Ok
    "pending" -> Tone.Warn
    "failed", "needs-auth" -> Tone.Danger
    else -> Tone.Idle
}

// ---- 弹窗里的校验（纯函数：弹窗只负责把结果画出来）----

/**
 * 名字（= `.mcp.json` 的键）能不能用。[others] 是**除自己以外**现有的名字。
 *
 * 重名必须拦住：JSON 对象同键只能有一个，写下去就是把别的那条**顶掉**。
 */
internal fun mcpNameError(name: String, others: List<String>): String? = when {
    name.isBlank() -> CcoderText.text("settings.mcp.dialog.err.nameEmpty")
    others.contains(name) -> CcoderText.text("settings.mcp.dialog.err.nameTaken", name)
    else -> null
}

/**
 * 这条现在能不能提交；能就返回 null。
 *
 * 形状决定校验哪一半：命令行查命令、远端查地址 —— 与表单上"哪几栏可见"是同一条判据
 * （`mcpKind()`），两处不会各说各话。
 */
internal fun mcpFormError(server: McpServer, others: List<String>): String? {
    mcpNameError(server.name, others)?.let { return it }
    return when (server.mcpKind()) {
        McpKind.STDIO ->
            if (server.command.isBlank()) CcoderText.text("settings.mcp.dialog.err.commandEmpty") else null

        McpKind.SSE, McpKind.HTTP ->
            if (!server.url.startsWith("http://") && !server.url.startsWith("https://")) {
                CcoderText.text("settings.mcp.dialog.err.urlScheme")
            } else {
                null
            }
    }
}
