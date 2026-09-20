package com.ccoder.ui

import com.ccoder.text.CcoderText

/**
 * 连接卡上那一格的状态。
 *
 * ## 为什么是枚举，不再是字符串（2026-09-20）
 *
 * 这段文字原来直接拿**中文当身份**：`connectionTone("已连接")` 用字面量做 `when`，
 * 生产端是 [ClaudePanel] 里散着的九处 `setConnection("…")`。翻译一动手，两边只要
 * 有一边没跟上，`when` 就整体落到 `else -> Tone.Idle` —— **灰点、零报错**，
 * 而且看起来跟"没事"一模一样。枚举把这件事挪到编译期：加一档忘了给色调，编译器不让过。
 *
 * ## 色调与文案现在分家了
 *
 * [tone] 是语义（要不要紧），[text] 从词表取。所以以后翻这八个词，一个字都不会
 * 牵动这边任何逻辑。
 *
 * ## 每档对应今天哪一处（迁移时的对照，别删）
 *
 * | 这一档 | 今天那句话 | 出处 |
 * |---|---|---|
 * | [Idle] | 未连接 | 字段初值 |
 * | [Loading] | 载入中… | 回放历史期间 |
 * | [Starting] | 启动中… | 起会话 |
 * | [Connected] | 已连接 | `Ready` |
 * | [RestoreFailed] | 恢复失败 | 恢复那条路失败 |
 * | [StartFailed] | 启动失败 | 起会话失败 |
 * | [Disconnected] | 已断开 | 断了 / 致命错误 |
 * | [Ended] | 已结束 | sidecar 进程退了 |
 */
internal enum class ConnectionState(val key: String, val tone: Tone) {
    /** 会话还没起来的初始态。**不是**"没数据"——所以它有边框、有准确的词。 */
    Idle("status.connection.idle", Tone.Idle),
    Loading("status.connection.loading", Tone.Warn),
    Starting("status.connection.starting", Tone.Warn),
    Connected("status.connection.connected", Tone.Ok),
    RestoreFailed("status.connection.restoreFailed", Tone.Danger),
    StartFailed("status.connection.startFailed", Tone.Danger),
    Disconnected("status.connection.disconnected", Tone.Danger),

    /**
     * sidecar 进程退了。
     *
     * **色调刻意留 [Tone.Idle]** —— 那正是今天的实际行为（这一档原来没写进映射表，
     * 落到 `else`）。同一件事有两种读法：进程退出是会话的**正常收尾**（用户自己停的、
     * 关标签），灰是对的；可它也可能是崩溃，而崩溃值得显出来（`SidecarExitReport`
     * 存在的全部理由就是"崩了看不出"). 这是产品判断，不该藏在一次翻译的 diff 里 ——
     * 见设计稿口径 5，另开一条改。这里用一条以这个问题命名的用例把现状钉住。
     */
    Ended("status.connection.ended", Tone.Idle),
    ;

    fun text(): String = CcoderText.text(key)
}
