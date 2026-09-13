package com.ccoder.ui

import com.ccoder.sidecar.SidecarExit

/**
 * 进程崩溃时贴给用户看的 stderr 行数。
 *
 * node 的启动报错（如 `ERR_INVALID_PACKAGE_CONFIG`）连同堆栈约十来行，
 * 关键的 `Error: ...` 行在第 5 行上下 —— 取末 8 行会正好把它切掉，
 * 所以留够 15 行。再多就会把转写区刷屏。
 */
internal const val STDERR_TAIL_LINES = 15

/**
 * 把 sidecar 的退出实况写成一条用户看得懂的说明。
 *
 * 抽成纯函数是因为 ClaudePanel 依赖 Swing 与平台、起不了单测，而
 * "取多少行 stderr"正是这次修复里最容易切掉关键信息的地方
 * （同 [mainButtonState] 的理由）。
 *
 * stderr 全文照贴、不做解释 —— node 的报错本身就是最好的说明，
 * 转述一遍只会丢掉信息。
 */
internal fun sidecarExitReport(exit: SidecarExit, maxLines: Int = STDERR_TAIL_LINES): String =
    buildString {
        append("sidecar 进程已退出（退出码 ${exit.code}）。")

        val tail = exit.stderr.takeLast(maxLines)
        if (tail.isEmpty()) {
            // 不能只报"进程已退出"就完事：那和卡在「正在启动…」一样无从下手
            append("\n进程没有留下任何错误信息。")
            return@buildString
        }

        if (tail.size < exit.stderr.size) {
            append("\n（共 ${exit.stderr.size} 行，只显示最后 ${tail.size} 行）")
        }
        append("\n")
        append(tail.joinToString("\n"))
    }
