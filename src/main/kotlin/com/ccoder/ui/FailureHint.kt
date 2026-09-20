package com.ccoder.ui

import com.ccoder.text.CcoderText

/**
 * 错误码 → 可操作的提示。**纯函数**：用例直接打，面板只负责把结果推进转写区
 * （照 `MainButtonState` / `SidecarExitReport` 那套拆法）。
 *
 * spec §5.3：认证失败要附带提示 —— 实测最常见的原因是环境变量污染（spec §11.1），
 * 但用户看到 "authentication_failed" 无从下手。
 *
 * ## 这五段正文在词表里（`failure.*.body`）
 *
 * 它们是仓库里最密的散文（每段 2-3 句、含可操作的记号如 `ANTHROPIC_AUTH_TOKEN`
 * 与页面路径「设置 → 环境 → 运行依赖」）。翻译时**记号与页签名要逐字对应**，
 * `FailureHintEnTest` 把这两件事钉住了 —— 指错页面比不指更糟。
 *
 * 与 `message`（sidecar 给的那句）之间那个空行**留在代码里拼**：值里不带首尾空白，
 * 免得译者看不见的空行被顺手删掉（见 `TextCatalogTest` 里那条）。
 *
 * ## 为什么没有 `NODE_NOT_FOUND` 这一条
 *
 * spec §5.3 的错误码表里列了它，但**它从来没有被发出来过**：没装 node 时根本起不到
 * sidecar，判定发生在 Kotlin 侧（`ClaudePanel.startSession` 的启动前检查），
 * 那条路的文案自己就带引导。这里再加一条只会是死代码。
 */
internal fun failureHintText(code: String?, message: String): String = when (code) {
    "AUTH_FAILED" -> message + "\n\n" + CcoderText.text("failure.auth.body")

    // 资格位现在启动时一律带上（见 session.js），所以"这条会话不是以绕过
    // 启动的"已经不是失败原因。剩下的是 CLI 侧真把它关了 ——
    // settings.json 的 permissions.disableBypassPermissionsMode，或受限配置
    "SET_MODE_FAILED" -> message + "\n\n" + CcoderText.text("failure.setMode.body")

    // 切档失败的常见原因是 CLI 太老 —— applyFlagSettings 是较新的控制请求，
    // 老版本上根本没有。不把原因说死：也可能是会话没建起来
    "SET_EFFORT_FAILED" -> message + "\n\n" + CcoderText.text("failure.setEffort.body")

    // 2026-09-18 补：终止钮那颗（stopTask）。失败时最要紧的是说清**它多半还在
    // 跑**，以及还有别的停法 —— 只报一句失败，用户会以为"停是停过了"
    "STOP_TASK_FAILED" -> message + "\n\n" + CcoderText.text("failure.stopTask.body")

    // 2026-09-17 补：这条以前只会把 sidecar 那句「未找到 claude 可执行文件」
    // 原样推出来，用户不知道去哪儿装。现在指到环境页那块上
    "CLAUDE_NOT_FOUND" -> message + "\n\n" + CcoderText.text("failure.claudeNotFound.body")

    else -> message
}

