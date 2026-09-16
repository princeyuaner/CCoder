package com.ccoder.ui

/**
 * 启动期取消闸：**这一趟启动还算不算数**。
 *
 * ## 为什么需要它（2026-09-16，多标签引入的真 bug）
 *
 * `startSession` 在池线程里 `p.start()` 之后，要等下一次 `invokeLater` 才把
 * `proc = p; client = c` 赋上。这中间**进程已经起来了，而面板手里什么都没有**——
 * 那段时间里 `stopSession()` 只对 `proc`/`client` 动刀（两者都是 null），
 * 于是没有任何代码会去杀这个进程：它连着它拉起的 claude 一起活到天荒地老，
 * 静默地烧额度。
 *
 * 今天 `canCloseContents` 没开、内容根本移除不了，所以那条路不可达；多标签之后
 * "点「＋」再马上点叉"是再自然不过的动作。
 *
 * ## 语义
 *
 * 令牌是**单调递增**的：作废不需要"告诉"在途的那一趟，它回来时自己问一句
 * [isCurrent] 就知道自己是不是旧的了。`inFlight` 那一半管的是另一件事 ——
 * 一趟走完之后旧令牌也不该再被认。两条合起来，用法就一句话：
 *
 * ```
 * val token = gate.begin()
 * // …异世界（池线程）走一遭…
 * if (!gate.isCurrent(token)) { 收掉刚起的东西; return }
 * ```
 *
 * 纯 JVM 可测：不碰平台、不碰 Swing（同 `Fuzzy` / `SessionSwitchState` 那一类）。
 */
internal class SessionGate {

    private var generation = 0
    private var inFlight = false

    /** 开始一次启动，拿到这一趟的令牌。 */
    fun begin(): Int {
        generation++
        inFlight = true
        return generation
    }

    /**
     * 这个令牌还算不算数。
     *
     * 三种情况都是 false：被 [invalidate] 作废过（停会话 / 面板销毁）、
     * 已经 [finish]、或者又开了新的一趟（令牌是旧的）。
     */
    fun isCurrent(token: Int): Boolean = inFlight && token == generation

    /**
     * 作废在途的启动。
     *
     * 调用点：`stopSession()`（切会话、新建、重启、dispose 全走它）与
     * `onSidecarDied()`（进程自己死了，这一趟当然也不算数了）。
     */
    fun invalidate() {
        inFlight = false
    }

    /** 这一趟走完了（成功或失败）。 */
    fun finish() {
        inFlight = false
    }
}
