package com.ccoder.ui

/**
 * 上下文右边那条要显示的内容。
 *
 * 三个字段分得这么细，是为了让**计数与名字能分开取舍**：
 *
 * - `todoProgress` / `runningCount` 是这条存在的理由，窄栏里也不许掉
 * - `currentTask` 是锦上添花，放不下就省略
 *
 * 如果只做成一个字符串，视图层就得从文本里反抠出计数 —— 那是做不到的。
 * 所以取舍必须在模型这一层就分好。
 */
internal data class RunStrip(
    /** "3/7"。有清单就一定有，即使一条都没做完 —— "0/2"说明它拆了活还没动手，是真信息。 */
    val todoProgress: String?,
    /** 进行中那一项的标题。没有进行中的项时为 null。 */
    val currentTask: String?,
    /** 在跑的任务数（已排除 ambient）。0 表示没有。 */
    val runningCount: Int,
)

/**
 * 这一刻条上该显示什么。**null 表示整条隐藏。**
 *
 * 与 [todoListOf]、[contextUsageOf] 同一条规则：取不到就不显示，不造零值。
 * 一条显示"0/0"的常驻空条，比不显示更糟 —— 它占着位置还说假话。
 */
internal fun runStripOf(tracker: RunStatusTracker): RunStrip? {
    val todos = tracker.todos
    val running = tracker.running.size

    if (todos == null && running == 0) return null

    return RunStrip(
        todoProgress = todos?.let { "${it.completed}/${it.total}" },
        currentTask = todos?.current,
        runningCount = running,
    )
}
