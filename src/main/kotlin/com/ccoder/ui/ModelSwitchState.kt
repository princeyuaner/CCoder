package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import com.ccoder.settings.canHotSwitch
import com.ccoder.text.CcoderText

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
 * 把"这个会话标签用哪个模型"跟当前的配置列表核一遍。
 *
 * [current] 是**快照** —— 这是"选中态按标签分"的代价：它不再跟着配置走，
 * 所以每次刷新都得自己核。三条后果都是具体的：
 *
 *  - 配置被删了 → 不核的话标签还显示着它，而**下一次起会话**会拿一个不再存在的
 *    端点去连（密钥也已经跟着删了，第三方网关上就是一次 401）
 *  - 模型从这条配置的列表里被删了 → 同上，而且是"把一个 CLI 不认识的模型名发过去"
 *  - 配置**改过**（换端点、改名、换认证方式）→ 不核的话这里起会话还去连老地址，
 *    而设置页明明写着新地址
 *
 * 核得上就返回**列表里那份**（端点/名字取最新的）配**本标签选的那个模型**；
 * 核不上就退回 [fallback] —— 本项目最近用的那条，与新标签开局拿到的完全一样，
 * 所以"退回"之后的行为是可预期的。
 *
 * **别把它简化成"每次都取 fallback"**：那正是改版前那个全应用共用的选中态
 * （谁点一下别的窗口全跟着变），也就是这次要拆掉的东西。这条函数存在的全部
 * 意义就是"本标签自己选的优先，只有它没了才退"。
 */
internal fun reconcileModel(
    current: ModelProfile?,
    available: List<ModelProfile>,
    fallback: ModelProfile?,
): ModelProfile? {
    if (current == null) return fallback
    val fresh = available.firstOrNull { it.id == current.id } ?: return fallback
    return if (current.modelId in fresh.modelIds) fresh.copy(modelId = current.modelId) else fallback
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
    if (effect == PickEffect.Restart) CcoderText.text("composer.model.restartHint") else null
