package com.ccoder.ui

import com.ccoder.text.CcoderText

/**
 * 卡片的色调。**只描述语义上的"要不要紧"**，具体颜色由视图决定：
 *
 * - 状态点：Ok 绿 / Warn 琥珀 / Danger 红 / Idle 次要色
 * - 指示器（条、分段、点）：Warn 琥珀 / Danger 红 / **其余一律强调色**
 *
 * 分成两层是因为上下文卡的进度条既不该是灰的（那是装饰），
 * 也不该是绿的（"绿"会被读成"正常"，可它平时就是正常，说它绿等于没说）。
 */
internal enum class Tone { Ok, Warn, Danger, Idle }

/**
 * 卡片底部那条微指示。
 *
 * **每一种都必须有真实分母或真实计数。** 画一个固定长度的格子条出来，
 * 会被读成"M 分之 N"，而那个 M 如果不存在，就是凭空造的信息。
 * 子代理正是这种情况 —— 它只有计数，没有总数，所以只能画 N 个点。
 */
internal sealed interface Indicator {
    object None : Indicator

    /**
     * 比例条。fraction 恒在 0..1。
     *
     * **不需要自己带颜色**：`IndicatorView.paintMeter` 的规矩是"只有警示色调才
     * 覆盖指示器颜色"（见 [Tone]）—— 压缩中那张卡整体就是 Warn，条子自己会转琥珀色。
     * 2026-09-17 试过加一个 `warn` 参数，发现是重复的，撤了。
     */
    data class Meter(val fraction: Double) : Indicator

    /** 分段。分母真实 —— 就是任务清单的条数。 */
    data class Segments(val done: Int, val total: Int) : Indicator

    /** N 个点，N 就是在跑的任务数。**没有分母。** */
    data class Dots(val count: Int) : Indicator
}

/**
 * 一张卡要显示的全部内容。
 *
 * @param quiet true = 这格没内容，**收边**：不画边框、值降为次要色。
 *   与 [Tone.Idle] 分开而不是合并 —— `Tone.Idle` 也在连接卡上用
 *   （"未连接"是真实状态，该有边框），而 quiet 只表示"没数据"。
 */
internal data class StatusCardModel(
    val label: String,
    val value: String,
    val tone: Tone = Tone.Idle,
    /**
     * 副信息（`12.3k / 200k` 这种）。
     *
     * 2026-09-14 改版后**不上卡面**，改挂 tooltip —— 卡面从四层收到两行，
     * 靠的就是把它让出去。搬走不等于弄丢：悬停能看到，详情浮层里也有。
     */
    val sub: String? = null,
    val indicator: Indicator = Indicator.None,
    val quiet: Boolean = false,
    /**
     * 这张卡的数**正在被改写**（今天只有"压缩中"这一种）。
     *
     * 卡片拿它决定水位要不要**一直**动：普通的数值变化只跑一趟动画就停，
     * 而"正在改写"没有确定的进度可言 —— 那件事本来就该看着在动
     * （2026-09-17 水位那版的 spec §4）。
     */
    val busy: Boolean = false,
)

/** 空格子里写什么。写"空闲"而不是"—"—— 破折号读起来像坏了。 */
internal val CARD_IDLE_TEXT: String get() = CcoderText.text("status.card.idle")

/**
 * 压缩中，上下文卡值行写什么（spec §3.8）。
 *
 * 与连接卡的忙态同一族（"正在做一件事"）——所以色调走 Warn 而不是上下文
 * 平时的 Idle/危险分档：那分档说的是"上下文有多满"，而这一刻它在被改写，
 * 两个语义同时显示会互相盖。
 */
internal val CARD_COMPACTING_TEXT: String get() = CcoderText.text("status.card.compacting")

/** 点数封顶。数字才是权威，点只是让"2"变得看得见。 */
internal const val MAX_DOTS = 6

// 四张卡的格子名。取值时机是**每次建模**（getter 而不是 const）—— 语言在
// 一个面板的生命周期里是定的，但卡片会随每一轮 token 重算，没有理由把文案钉在类加载那一刻。

internal val CARD_LINK: String get() = CcoderText.text("status.card.link")
internal val CARD_CONTEXT: String get() = CcoderText.text("status.card.context")
internal val CARD_TASKS: String get() = CcoderText.text("status.card.tasks")
internal val CARD_AGENTS: String get() = CcoderText.text("status.card.agents")

private fun quietCard(label: String) =
    StatusCardModel(label = label, value = CARD_IDLE_TEXT, quiet = true)

// ---- 连接 ----

/**
 * 连接卡**永远不空闲** —— "未连接"是一种状态，不是"没数据"。
 *
 * 色调由 [ConnectionState] 自己带（那一档要不要紧是它的语义），界面把它画在
 * **图标**上（2026-09-14 改版：原来那个前置状态点并进了图标，一排卡因此统一成
 * "图标 + 标签 / 值"两行）。
 *
 * 这里**没有** `connectionTone(status: String)` 那种按文字查表的函数了 ——
 * 那种写法翻译一次就会整体塌成"什么都不重要"，且不报错（见 [ConnectionState]）。
 */
internal fun connectionCardOf(state: ConnectionState) = StatusCardModel(
    label = CARD_LINK,
    value = state.text(),
    tone = state.tone,
)

/**
 * 连接卡在"正在做事"时的样子。
 *
 * 色调统一 Warn：与"启动中…""载入中…"同一族 —— 它们都是**过渡态**，
 * 而绿色只留给"已连接"这种安定状态。
 */
internal fun activityCardOf(activity: Activity) = StatusCardModel(
    label = CARD_LINK,
    value = activity.text(),
    tone = Tone.Warn,
)

/**
 * 卡在「等待响应」时的样子：**动作词上标签行，秒数上值行**。
 *
 * 2026-09-15 加。用户问"为什么调用工具后会突然卡几十秒" —— 那几十秒是上游还没
 * 吐出第一个 token（实测 P90 10.3s、P99 38.8s、最长 89s，见 [activityChangeOf]
 * 里那段）。只写四个字「等待响应」分不出"它在走"还是"它挂了"，秒数得动起来。
 *
 * **为什么换行放**（动作词让到标签行、秒数进值行）：值那一行是居中大字，而这一格
 * 只有约 95px 宽 —— 「等待响应 12s」并排会被省略号截掉（[ActivityTest] 里那条
 * "每个动作词都得短得住"就是量这个的）。换过来之后两行各自都窄。
 *
 * 秒数写法与工具卡、进行中的思考块一致（都是 `12s`）—— 同一个东西在三个地方
 * 长得一样，用户不用重新认一遍。
 */
internal fun waitingCardOf(seconds: Int) = StatusCardModel(
    label = Activity.Waiting.text(),
    value = elapsedText(seconds),
    tone = Tone.Warn,
)

/** 秒数怎么写。单独一个函数是为了让三处（这里、工具卡、思考块）有同一个出处。 */
internal fun elapsedText(seconds: Int): String = "${seconds}s"

// ---- 上下文 ----

/**
 * 上下文卡。
 *
 * 值给百分比（"还剩多少"一眼可见），副值给绝对数（"12.3k / 200k"）——
 * 绝对数在拆卡前就显示着，不能因为格子变窄就弄丢。
 *
 * 阈值 70/90 是计划外新增：一个永远同色的进度条是装饰，而上下文写满
 * 是长会话里唯一会**静默**毁掉会话的事。
 *
 * **没有测量值时显示 0，不写「空闲」**：那个词的意思是"没在跑"（任务列表、子代理
 * 用它是对的），而上下文恰恰不是这回事 —— 恢复一场长对话之后它一点都不空闲，
 * 我们只是没测过。显示 0 至少是个能被纠正的数字（新会话本来就近乎空），
 * 而「空闲」是一句说反了的话。
 */
internal fun contextCardOf(usage: ContextUsage?, compacting: Boolean = false): StatusCardModel {
    val u = usage ?: ContextUsage(usedTokens = 0, windowTokens = 0)
    val percent = contextPercentOf(u)

    // 压缩中：值行让给进度语义（spec §3.8），水位留着 —— 它是**最后测到**的
    // 数，抹掉的话卡上会突然什么都没有，而读者并不知道那是"暂时"。色调走 Warn：
    // 水位跟着变琥珀，表示"这个数正在被改写"；`busy` 让那层水一直动。
    if (compacting) {
        return StatusCardModel(
            label = CARD_CONTEXT,
            value = CARD_COMPACTING_TEXT,
            tone = Tone.Warn,
            sub = if (u.windowTokens > 0) contextRatioText(u) else null,
            indicator = if (percent != null) Indicator.Meter(percent / 100.0) else Indicator.None,
            busy = true, // 水位一直动（见 busy 的注释）
        )
    }
    return StatusCardModel(
        label = CARD_CONTEXT,
        value = if (percent != null) "$percent%" else formatTokenCount(u.usedTokens),
        tone = when {
            percent == null -> Tone.Idle
            percent >= 90 -> Tone.Danger
            percent >= 70 -> Tone.Warn
            else -> Tone.Idle
        },
        // 窗口未知时不给副值：那会拼出"0 / 0"或"325.4k / 325.4k"这种把同一个数
        // 说两遍的样子。绝对数在值上已经给过了
        sub = if (u.windowTokens > 0) contextRatioText(u) else null,
        indicator = if (percent != null) Indicator.Meter(percent / 100.0) else Indicator.None,
    )
}

// ---- 任务列表 ----

/**
 * 任务列表卡。
 *
 * `total == 0` 也收边：一条清单都没拆出来时画"0/0"或七个空格子，
 * 都是在说并不存在的事。
 */
internal fun todoCardOf(todos: TaskList?): StatusCardModel {
    if (todos == null || todos.total == 0) return quietCard(CARD_TASKS)

    return StatusCardModel(
        label = CARD_TASKS,
        value = "${todos.completed}/${todos.total}",
        indicator = Indicator.Segments(done = todos.completed, total = todos.total),
    )
}

// ---- 子代理 ----

/**
 * 子代理卡。
 *
 * **不画进度条。** 子代理没有分母 —— 在跑几个就是几个。见 [Indicator]。
 */
internal fun runningCardOf(running: List<RunningTask>): StatusCardModel {
    if (running.isEmpty()) return quietCard(CARD_AGENTS)

    return StatusCardModel(
        label = CARD_AGENTS,
        value = running.size.toString(),
        indicator = Indicator.Dots(minOf(running.size, MAX_DOTS)),
    )
}
