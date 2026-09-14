package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import com.ccoder.settings.canHotSwitch

/**
 * 点一个模型会**发生什么**。
 *
 * 弹层要拿它说人话（"这条要重开会话"），[ClaudePanel.switchModel] 要拿它分岔 ——
 * 两边必须走同一个判定。各判各的就会漂移，而漂移的后果是弹层说"秒切"、
 * 实际把用户的上下文丢了。
 */
internal enum class PickEffect {
    /** 发一条 `set_model`，会话、上下文、转写全留着。 */
    Hot,

    /** 重开会话 —— **这段对话的上下文不保留**。 */
    Restart,

    /** 没有会话在跑：改的只是"下次启动用哪个"，不存在丢不丢上下文。 */
    NoSession,
}

/**
 * 这一选会走哪条路。
 *
 * 没有会话时一律 [PickEffect.NoSession]，**哪怕端点完全一样** —— 那时没有
 * "切"这个动作，选中的值只是落到设置里。这一支优先判，否则弹层会在一个
 * 根本没有会话的面板上标"会重开会话"，而那时没有任何东西可重开。
 *
 * 其余情况交给 [canHotSwitch]：端点与凭证没变就是热切，变了只能重开
 * （`options.env` 烤在子进程里，中途改不了）。
 */
internal fun pickEffect(
    hasSession: Boolean,
    current: ModelProfile?,
    currentSecret: String,
    to: ModelProfile,
    toSecret: String,
): PickEffect = when {
    !hasSession -> PickEffect.NoSession
    canHotSwitch(current, currentSecret, to, toSecret) -> PickEffect.Hot
    else -> PickEffect.Restart
}

/**
 * 这一行要不要标「会重开会话」。
 *
 * 只有 [PickEffect.Restart] 标。热切不标（它本来就不丢东西，标了反而像警告），
 * 没有会话也不标 —— 那时没有上下文可丢，标出来是吓唬人。
 *
 * 补的是一个**既有的**诚实缺口：今天跨配置切换只在忙的时候弹确认框，
 * 空闲时一个字都不说，用户切完才发现上下文没了。
 */
internal fun restartBadge(effect: PickEffect): String? =
    if (effect == PickEffect.Restart) "会重开会话" else null
