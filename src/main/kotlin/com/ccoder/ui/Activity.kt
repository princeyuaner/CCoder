package com.ccoder.ui

import com.ccoder.text.CcoderText

/**
 * "现在在做什么" —— 连接卡上那行实时文字。
 *
 * 用户要的是抬头一眼能看见 Claude 此刻在干嘛（原话：「我希望能实时显示当前在做什么，
 * 比如思考中，编辑文件，运行指令等等」），而转写区是滚动区 —— 跑长任务时
 * 最新的那条早被顶上去或还没滚到，所以这件事得在状态卡上有一份。
 *
 * ## 为什么是枚举（2026-09-20）
 *
 * 原来这十个动作词是十个中文 `const val`，靠"常量与常量比较"侥幸成立：
 * [ClaudePanel] 里 `if (text == ACTIVITY_WAITING) startWaitingTicker()`、
 * `now == ACTIVITY_WAITING -> waitingCardOf(…)`。翻译之后只要比的是"取出来的那串字"，
 * 这个比较就可能整体为假 —— 秒表不走了、卡上永远停在错误的档，**都不报错**。
 * 枚举把它变成类型问题。
 *
 * ## 文案一律 **≤ 4 个字**（中文）
 *
 * 420px 里每张卡只有约 95px，扣掉图标与内边距只剩一两句短词的宽度。
 * 放不下就会被 `JLabel` 打省略号，那是等装进 IDE 才看得见的错。
 * 英文那份按同一格宽度另量（见设计稿 §8 与 `StatusCardsRenderProbe`）：
 * 拉丁字母比汉字窄得多，所以词长可以到 8-9 个字母，但要**量过**才算。
 *
 * 纯函数：动作词与"这条渲染项意味着什么"都能单独测，不必起面板。
 */
internal enum class Activity(val key: String) {
    Waiting("status.activity.waiting"),
    Thinking("status.activity.thinking"),
    Replying("status.activity.replying"),
    Running("status.activity.running"),
    Editing("status.activity.editing"),
    Reading("status.activity.reading"),
    Searching("status.activity.searching"),
    Tool("status.activity.tool"),
    Agent("status.activity.agent"),
    Permission("status.activity.permission"),
    ;

    fun text(): String = CcoderText.text(key)
}

/**
 * 工具名 → 动作词。
 *
 * 认不出的（MCP、以后新增的）一律 [Activity.Tool] —— 宁可说得笼统，也不要在
 * 卡上写一个用户没见过的工具名（那格只有 95px，写不下还认不出）。
 */
internal fun toolActivity(name: String): Activity = when (name) {
    "Bash", "BashOutput", "KillShell" -> Activity.Running
    "Edit", "Write", "MultiEdit", "NotebookEdit" -> Activity.Editing
    "Read", "NotebookRead" -> Activity.Reading
    "Grep", "Glob" -> Activity.Searching
    "Task", "Agent" -> Activity.Agent
    else -> Activity.Tool
}

/** 一条渲染项把"当前动作"改成什么。 */
internal sealed interface ActivityChange {
    data class Now(val activity: Activity) : ActivityChange

    /** 回到空闲：连接状态重新上卡。 */
    object Idle : ActivityChange

    /** 不动。工具结果只是"这一次调用完了"，这一轮还在跑。 */
    object Keep : ActivityChange
}

/**
 * 映射规则。
 *
 * 三处刻意的地方：
 *
 * - **工具结果不清空成空闲**：一轮里是「调用 → 结果 → 思考 → 调用 …」，
 *   结果一到就清，卡上会在一轮中间闪一下"已连接"。
 * - **但工具结果之后要切成 [Activity.Waiting]**（2026-09-15 用户报"调用工具后会突然卡
 *   几十秒，然后说思考中"）：那几十秒是上游还没吐出第一个 token —— 实测这一段
 *   的间隔 P50 3.2s / P90 10.3s / P99 38.8s / 最长 89s，而且**慢窗口之后模型先
 *   吐的全是 thinking 块**，所以卡上紧跟着就跳到"思考中"。这期间原来还写着
 *   「运行指令」：一句假话，用户看到的就成了"卡住"。
 * - **并行调用按"还有没有别的在跑"判**：一条 assistant 消息可以带多个 tool_use，
 *   一个结果到了不代表整批完了（见 [toolsStillRunning]）。
 * - **只有回合结束（Result）与报错才回空闲**：那才是真的没在跑。
 *
 * @param toolsStillRunning 这条之后还有没有别的工具在跑。调用方维护那批
 *   `tool_use.id`（见 ClaudePanel 里的 pendingToolIds）。**必须在这条 ToolResult
 *   被移除之后**再算，否则永远是它自己把自己数进去。
 */
internal fun activityChangeOf(item: RenderItem, toolsStillRunning: Boolean): ActivityChange = when (item) {
    is RenderItem.ThinkingDelta, is RenderItem.Thinking -> ActivityChange.Now(Activity.Thinking)
    is RenderItem.AssistantDelta, is RenderItem.AssistantText -> ActivityChange.Now(Activity.Replying)
    is RenderItem.ToolUse -> ActivityChange.Now(toolActivity(item.name))
    // 参数还在生成时就先把动作词摆出来 —— 那一段转写区是静的，
    // 状态卡是唯一能说明"它在动"的地方（见 renderStreamEvent 里那段）
    is RenderItem.ToolStarting -> ActivityChange.Now(toolActivity(item.name))
    // 一批工具全跑完了：接下来是模型自己要想（TTFT 那几十秒就落在这里）
    is RenderItem.ToolResult ->
        if (toolsStillRunning) ActivityChange.Keep else ActivityChange.Now(Activity.Waiting)
    is RenderItem.Result, is RenderItem.ErrorItem -> ActivityChange.Idle
    is RenderItem.UserText, is RenderItem.SystemNote -> ActivityChange.Keep
}

/**
 * 这一项把卡面改成动作词时，"跑的人"是不是子代理。
 *
 * 连接卡忙时会顶替成"现在在做什么"，而**子代理**跑工具/思考/说话时，那几个动作词
 * 与主线程跑的**一模一样** —— 2026-09-20 用户问"这里的状态如果是属于子代理的，
 * 能不能加个标识"。归属就落在这个返回值上：卡面据此在图标旁画一个小点
 * （见 [StatusCardView] 里那层 `paintChildren`）。
 *
 * 三条规则：
 * - **带 `parent` 的渲染项就是子代理的**（`ToolUse` / `Thinking` / `AssistantText`，
 *   归属的形状见 `MessageRenderer.renderAssistant`）；
 * - **「等待响应」由工具结果触发，继承上一刻的归属**：等的人还是刚才那个跑者 ——
 *   不继承的话，子代理每次调用工具之后那几十秒（TTFT，实测 P90 10.3s）角标就会灭
 *   一下，闪得比不标还糟；
 * - 其余（回合结束、报错、用户消息）一律 false。
 *
 * [RenderItem.ToolStarting] 与两个增量帧**不带 `parent` 是刻意的**（流事件只走
 * 主线程，见子代理设计稿事实 5/12），所以它们天然落到 false，不用特判。
 */
internal fun subagentOf(item: RenderItem, previous: Boolean): Boolean = when (item) {
    // 三种分开写而不是并成一个分支：Kotlin 对"多类型的 is"不做智能转换，
    // 并起来 `item.parent` 直接是编译错误（它们各自带 parent，没有公共父类型）
    is RenderItem.ToolUse -> item.parent != null
    is RenderItem.Thinking -> item.parent != null
    is RenderItem.AssistantText -> item.parent != null
    is RenderItem.ToolResult -> previous
    else -> false
}
