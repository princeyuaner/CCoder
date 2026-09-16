package com.ccoder.ui

import com.ccoder.settings.PermissionModeSetting

/**
 * CLI 报回来的生效模式与界面显示的不一致时，该怎么办。
 *
 * 抽成纯函数的理由同 [SessionSwitchState]、[ModelSwitchState]：ClaudePanel
 * 依赖 Swing 与平台，起不了单测，而这里三条分支的差别正是最容易写错的地方 ——
 * 「什么都不做 / 改标签但不吭声 / 改标签并说明」三者的边界只有一行条件。
 *
 * 背景（2026-09-16 实测，`sidecar/tools/probe-auto-mode.mjs`）：`system/status`
 * 事件里带着 `permissionMode`，那是**唯一一条能读回生效模式**的路。它补的是
 * "会话以什么模式起来"那一半 —— 闸门（`disableAutoMode` / 订阅档 / 断路器）
 * 在 CLI 侧，它若在启动时换了档，我们从前看不见，标签会一直显示用户选的那个。
 */
internal enum class ModeReadback {
    /** 什么都不用做：没读出来，或者读出来的和显示的一样。 */
    None,

    /** 改标签，但不必说话 —— 那是用户自己刚点的那次切换，说明由回执那条负责。 */
    Silent,

    /** 改标签，**并且**说明一句 —— 这个分歧用户没点过，得让他知道。 */
    Announce,
}

/**
 * @param reported 刚从 status 里读出来的模式；认不出或没带时为 null
 * @param current 界面此刻显示的模式
 * @param requested 刚发出去、还没等到回执的那次切换；没有就是 null
 */
internal fun modeReadbackOf(
    reported: PermissionModeSetting?,
    current: PermissionModeSetting,
    requested: PermissionModeSetting?,
): ModeReadback = when {
    // 认不出就不动 —— 与回执那条分支同一条规矩：显示一个我们自己都不认识的
    // 模式，不如保持原样
    reported == null -> ModeReadback.None
    reported == current -> ModeReadback.None
    // CLI 可能先吐 status 再回控制请求。这一次切换是用户点的，
    // 说两遍就成了噪音
    reported == requested -> ModeReadback.Silent
    else -> ModeReadback.Announce
}
