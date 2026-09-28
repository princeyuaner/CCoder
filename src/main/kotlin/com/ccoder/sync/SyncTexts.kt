package com.ccoder.sync

import com.ccoder.text.CcoderText

/**
 * 同步那几个枚举 → 词表键的映射。
 *
 * ## 为什么返回「键」而不是翻好的字
 *
 * 界面里那些控件是**长命**的：语言一变，`retranslateTree` 要照着**记在控件上的键**
 * 重取一遍（见 `LocalizedText` 那一套）。所以调用点需要的是键，不是字。
 * 键的字面量集中在这里，于是 `TextKeysTest` 那两条（引用的键都在、词表里的键都有人引用）
 * 都看得见它们。
 *
 * ## 为什么不写 `when` 在各自调用点
 *
 * 两个地方要这套映射（状态栏组件、设置页），各写一份迟早出现"一处加了个状态、
 * 另一处漏了" —— 而漏了的表现是**界面上少一句话**，没有报错。
 */
internal object SyncTexts {

    /** 配置缺哪一项。 */
    internal fun problemKey(problem: ConfigProblem?): String = when (problem) {
        ConfigProblem.DISABLED, null -> "sync.problem.disabled"
        ConfigProblem.NO_SRC -> "sync.problem.noSrc"
        ConfigProblem.NO_DST -> "sync.problem.noDst"
    }

    /** 运行状态。 */
    internal fun runKey(run: SyncRun): String = when (run) {
        SyncRun.DISABLED -> "sync.state.disabled"
        SyncRun.OCCUPIED -> "sync.state.occupied"
        SyncRun.RUNNING -> "sync.state.running"
        SyncRun.FAILED -> "sync.state.failed"
        SyncRun.STOPPED -> "sync.state.stopped"
    }

    /** 本地已删却**没删**目标端的原因（`[提醒]` 段那几条）。 */
    internal fun keptKey(why: KeptWhy): String = when (why) {
        KeptWhy.TARGET_MODIFIED -> "sync.kept.targetModified"
        KeptWhy.AUTO_DELETE_OFF -> "sync.kept.autoDeleteOff"
    }

    /**
     * 立刻翻好的一句（给**不进树**的地方用：状态栏的 `getText()`、tooltip 参数、
     * 以及那些把字符串当参数喂给共享辅助函数的场合）。
     */
    internal fun problemText(problem: ConfigProblem?): String =
        CcoderText.text(problemKey(problem))
}
