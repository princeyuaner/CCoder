package com.ccoder.ui

/**
 * "现在在做什么" —— 连接卡上那行实时文字。
 *
 * 用户要的是抬头一眼能看见 Claude 此刻在干嘛（原话：「我希望能实时显示当前在做什么，
 * 比如思考中，编辑文件，运行指令等等」），而转写区是滚动区 —— 跑长任务时
 * 最新的那条早被顶上去或还没滚到，所以这件事得在状态卡上有一份。
 *
 * 文案一律 **≤ 4 个字**：420px 里每张卡只有约 95px，扣掉图标与内边距只剩
 * 一两句短词的宽度。放不下就会被 JLabel 打省略号，那是等装进 IDE 才看得见的错。
 *
 * 纯函数：动作词与"这条渲染项意味着什么"都能单独测，不必起面板。
 */

internal const val ACTIVITY_WAITING = "等待响应"
internal const val ACTIVITY_THINKING = "思考中"
internal const val ACTIVITY_REPLYING = "回复中"
internal const val ACTIVITY_RUNNING = "运行指令"
internal const val ACTIVITY_EDITING = "编辑文件"
internal const val ACTIVITY_READING = "读取文件"
internal const val ACTIVITY_SEARCHING = "搜索"
internal const val ACTIVITY_TOOL = "调用工具"
internal const val ACTIVITY_AGENT = "子代理在跑"
internal const val ACTIVITY_PERMISSION = "等待授权"

/**
 * 工具名 → 动作词。
 *
 * 认不出的（MCP、以后新增的）一律「调用工具」—— 宁可说得笼统，也不要在
 * 卡上写一个用户没见过的工具名（那格只有 95px，写不下还认不出）。
 */
internal fun toolActivity(name: String): String = when (name) {
    "Bash", "BashOutput", "KillShell" -> ACTIVITY_RUNNING
    "Edit", "Write", "MultiEdit", "NotebookEdit" -> ACTIVITY_EDITING
    "Read", "NotebookRead" -> ACTIVITY_READING
    "Grep", "Glob" -> ACTIVITY_SEARCHING
    "Task", "Agent" -> ACTIVITY_AGENT
    else -> ACTIVITY_TOOL
}

/** 一条渲染项把"当前动作"改成什么。 */
internal sealed interface ActivityChange {
    data class Now(val text: String) : ActivityChange

    /** 回到空闲：连接状态重新上卡。 */
    object Idle : ActivityChange

    /** 不动。工具结果只是"这一次调用完了"，这一轮还在跑。 */
    object Keep : ActivityChange
}

/**
 * 映射规则。
 *
 * 两处刻意的地方：
 * - **工具结果不清空**：一轮里是「调用 → 结果 → 思考 → 调用 …」，
 *   结果一到就清，卡上会在一轮中间闪一下"已连接"。
 * - **只有回合结束（Result）与报错才回空闲**：那才是真的没在跑。
 */
internal fun activityChangeOf(item: RenderItem): ActivityChange = when (item) {
    is RenderItem.ThinkingDelta, is RenderItem.Thinking -> ActivityChange.Now(ACTIVITY_THINKING)
    is RenderItem.AssistantDelta, is RenderItem.AssistantText -> ActivityChange.Now(ACTIVITY_REPLYING)
    is RenderItem.ToolUse -> ActivityChange.Now(toolActivity(item.name))
    is RenderItem.ToolResult -> ActivityChange.Keep
    is RenderItem.Result, is RenderItem.ErrorItem -> ActivityChange.Idle
    is RenderItem.UserText, is RenderItem.SystemNote -> ActivityChange.Keep
}
