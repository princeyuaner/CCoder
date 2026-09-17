package com.ccoder.ui

/**
 * 错误码 → 可操作的提示。**纯函数**：用例直接打，面板只负责把结果推进转写区
 * （照 `MainButtonState` / `SidecarExitReport` 那套拆法）。
 *
 * spec §5.3：认证失败要附带提示 —— 实测最常见的原因是环境变量污染（spec §11.1），
 * 但用户看到 "authentication_failed" 无从下手。
 *
 * ## 为什么没有 `NODE_NOT_FOUND` 这一条
 *
 * spec §5.3 的错误码表里列了它，但**它从来没有被发出来过**：没装 node 时根本起不到
 * sidecar，判定发生在 Kotlin 侧（`ClaudePanel.startSession` 的启动前检查），
 * 那条路的文案自己就带引导。这里再加一条只会是死代码。
 */
internal fun failureHintText(code: String?, message: String): String = when (code) {
    "AUTH_FAILED" ->
        "$message\n\n请检查 ~/.claude/settings.json 的 env 块是否包含有效的 " +
            "ANTHROPIC_AUTH_TOKEN 与 ANTHROPIC_BASE_URL。\n" +
            "若配置无误，可能是宿主环境变量污染——CCoder 已剥离 " +
            "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST 等 10 个变量（设计文档 §3.2）。"

    // 资格位现在启动时一律带上（见 session.js），所以"这条会话不是以绕过
    // 启动的"已经不是失败原因。剩下的是 CLI 侧真把它关了 ——
    // settings.json 的 permissions.disableBypassPermissionsMode，或受限配置
    "SET_MODE_FAILED" ->
        "$message\n\n权限模式没有切换，本会话仍按原来的模式跑。" +
            "若要切到「绕过权限」而被拒：CCoder 启动时已带好可切换的资格，" +
            "被拒说明它被设置或策略禁用了 —— 查 ~/.claude/settings.json 的 " +
            "permissions.disableBypassPermissionsMode，以及是否有托管配置。"

    // 切档失败的常见原因是 CLI 太老 —— applyFlagSettings 是较新的控制请求，
    // 老版本上根本没有。不把原因说死：也可能是会话没建起来
    "SET_EFFORT_FAILED" ->
        "$message\n\n思考深度没有改变，这一轮仍按原来的档位跑。" +
            "会话中途改档位需要较新版本的 claude 可执行文件；" +
            "可以升级它，或在设置里改好后重开会话。"

    // 2026-09-17 补：这条以前只会把 sidecar 那句「未找到 claude 可执行文件」
    // 原样推出来，用户不知道去哪儿装。现在指到环境页那块上
    "CLAUDE_NOT_FOUND" ->
        "$message\n\n设置 → 环境 → 运行依赖 里可以检测 claude 并一键安装，" +
            "装完点「重新检测」。也可以在那里手填 claude 可执行文件的完整路径。"

    else -> message
}
