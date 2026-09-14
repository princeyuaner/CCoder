package com.ccoder.ui

/**
 * 用量请求的闸：**一次只允许一个在途**。
 *
 * ## 为什么需要它
 *
 * `requestContextUsage()` 往 sidecar 发一条控制请求，而**控制请求与事件流共用
 * 同一根管道**。2026-09-14 那次事故就是它被每一条事件调了一次：一轮 5861 条事件
 * = 5861 条 `getContextUsage`，事件流被自己的控制请求挤住。屏幕上的表现是
 * "思考走到一半突然停住，过一会儿一大段一起冒出来" —— 用户的原话是
 * "体验很差感觉像卡死了"。
 *
 * 那一处位置已经修好（搬进 result 分支）。这个闸留着，是因为它把这类错误的
 * **代价**从"无上限"压到"一次一条"：多问几次用量本来就没有意义 —— 同一秒问
 * 两遍，答案只会一样。
 *
 * ## 与本仓库其他"状态类"一样：与 Swing 无关，可单独测
 *
 * 放进 ClaudePanel 里就测不动了，而这条判断正是要钉住的那种。
 */
internal class UsageRequestGate {

    private var inFlight = false

    /** 允许发就返回 true（并把闸关上）；已经有在途的就返回 false。 */
    fun acquire(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    /**
     * 放闸。
     *
     * **成功、失败、超时三条路都必须走到它** —— 漏掉任何一条，之后就再也
     * 问不到用量，卡片会永远停在旧读数上。所以调用方把它放在
     * `when (outcome)` 的**前面**，而不是某一个分支里面。
     */
    fun release() {
        inFlight = false
    }
}
