package com.ccoder.ui

/**
 * 多题提问的推进（与 Swing 无关，可单独测）。
 *
 * 一次 `AskUserQuestion` 可以带 1–4 道题，而现在**一题一个弹框** ——
 * 于是"现在第几题、答完这题往哪走、回退时答案还在不在"就成了需要被
 * 单独管起来的状态。这些判断不该散在对话框的事件监听里：那里既测不到，
 * 又会被下一个改弹窗的人改坏。
 *
 * ## 不变式
 *
 * **站在最后一题上时，前面每一题都已经答过。** 由 [submitCurrent] 把守着 ——
 * 当前题没答就不许往前走，所以"跨过一道空白题"这件事从结构上不可能发生。
 * 调用方在真正提交前仍要过一遍 [allAnswered]：那是最后一道闸，不是这条不变式的替代品。
 *
 * ## 为什么按钮的可点性只由 [canAdvance] 决定
 *
 * 「下一题」和「提交」在界面上是同一个按钮（只是文案不同），它可不可点
 * 与"是不是最后一题"无关，只与"这一题答了没"有关。最后一题提交前的
 * 完整性检查走 [allAnswered]，两件事分开，省得一个表达式里塞两种判断。
 */
internal class AskFlow(val request: AskRequest) {

    /** 作答状态还是交给 [AskState] —— 那是已有的、被单测钉住的实现。 */
    private val state = AskState(request)

    private var cursor = 0

    val count: Int get() = request.questions.size

    /** 从 0 数。界面上显示时 +1。 */
    val index: Int get() = cursor

    /** 当前题的**模型**（题干、选项、是否多选）。 */
    val question: AskQuestion get() = request.questions[cursor]

    /** 当前题的**作答状态**。 */
    val current: QuestionState get() = state.states[cursor]

    val isLast: Boolean get() = cursor == count - 1

    /** 界面上那个「下一题 / 提交」按钮可不可点。 */
    val canAdvance: Boolean get() = current.answered

    /**
     * 存下当前题的作答并往前走。
     *
     * 返回 true = **已经是最后一题且答完了，该提交了**。
     * 当前题没答就调用它时原地不动并返回 false —— 这条是保护 [AskFlow] 类注释里
     * 那条不变式的闸，不是"顺手写的容错"。
     */
    fun submitCurrent(): Boolean {
        if (!canAdvance) return false
        if (isLast) return true
        cursor++
        return false
    }

    /** 回上一题。已经在第一题就原地不动，返回 false。 */
    fun back(): Boolean {
        if (cursor == 0) return false
        cursor--
        return true
    }

    /** 全部题目的作答，形状与回传协议一致。 */
    fun picked(): Picked = state.picked()
}
