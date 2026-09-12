package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo

/** 会话切换被什么挡住了。 */
internal enum class SwitchBlock {
    /** 可以切 */
    None,

    /** 回合进行中 */
    TurnRunning,

    /** 有权限询问挂着 */
    PermissionPending,
}

/**
 * 忙时能否切换会话。
 *
 * 抽成纯函数的原因同 [mainButtonState]：ClaudePanel 依赖 Swing 与平台、
 * 起不了单测，而"什么时候该拦住"正是这次改动里最容易写错的部分。
 *
 * 拦住的理由不是体验而是正确性 —— 切换要 `stopSession()`：
 *  - 正在跑的回合会被腰斩
 *  - 挂着的权限询问会一并作废（sidecar 收到 stop 后的 denyAllPending）
 *
 * 权限挂起**优先于**回合进行：有询问挂着时 busy 通常也是 true，但
 * "先处理那条询问"才是用户该做的动作。提示得更具体才有用。
 */
internal fun switchBlock(busy: Boolean, pendingPermissions: Int): SwitchBlock = when {
    pendingPermissions > 0 -> SwitchBlock.PermissionPending
    busy -> SwitchBlock.TurnRunning
    else -> SwitchBlock.None
}

/**
 * 拦住时给用户看的一句话。可切时返回 null。
 *
 * 两句话都必须指向**具体动作** —— 光说"不能切"用户不知道下一步做什么。
 */
internal fun switchBlockNotice(block: SwitchBlock): String? = when (block) {
    SwitchBlock.None -> null

    SwitchBlock.TurnRunning ->
        "当前回合还在跑。先按「停止」再切会话 —— 否则这个回合会被腰斩。"

    SwitchBlock.PermissionPending ->
        "还有权限询问没处理。先把它处理掉再切会话 —— 否则那条询问会作废。"
}

/**
 * 列表行的标题。summary 优先，退回 firstPrompt，都没有给占位。
 *
 * 空白行看起来像渲染坏了，不如直说。列表行与删除确认语共用这一个 ——
 * 两处各写一遍，改一处漏一处，确认语里说的名字就会和那一行显示的不是同一个。
 */
internal fun sessionTitle(session: SessionInfo): String =
    session.summary?.takeIf { it.isNotBlank() }
        ?: session.firstPrompt?.takeIf { it.isNotBlank() }
        ?: "（无标题）"

/**
 * 「＋」能不能点。
 *
 * 与切换会话拦的是同一件事：新建同样要 stopSession()，会把正在跑的回合腰斩，
 * 挂着的权限询问也会一并作废。所以直接复用 [switchBlock] 的判定。
 */
internal fun newSessionEnabled(block: SwitchBlock): Boolean = block == SwitchBlock.None

/**
 * 「＋」的提示语。
 *
 * 能点时说明它做什么，不能点时说明**先做什么** —— 光说"不能新建"没用。
 * 不能点那种情况直接复用 [switchBlockNotice]，两句提示不必各写一份。
 */
internal fun newSessionTooltip(block: SwitchBlock): String =
    switchBlockNotice(block) ?: "新建会话"

/**
 * 删除的确认语。
 *
 * 删当前会话时**不写"要不要删"，写"删了会怎样"** —— 用户已经知道自己点了
 * 哪一行，他需要知道的是后果：转写区会清空、回到新会话。
 *
 * 注意这里不负责截断：行宽由布局决定，长标题在组件里省略。
 */
internal fun deleteConfirmPrompt(session: SessionInfo, isCurrent: Boolean): String =
    if (isCurrent) {
        "这是当前对话。删除后转写区会清空，回到新会话。"
    } else {
        "删除「${sessionTitle(session)}」？"
    }
