package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.PermissionModeSetting

/**
 * 设置对话框关掉之后，要不要把「权限模式」「思考深度」推到正在跑的会话上。
 *
 * @property permissionMode 与当前会话记着的值不同、需要推的那一个；null = 没变
 * @property effort 同上
 * @property deferred 会话还没就绪：这一拍**什么都不推**，但上面两个字段仍然
 *   表示"用户改过" —— 调用方据此决定要不要插一句"下次生效"。两条都为 null 时
 *   它是 false（没改就没必要说）
 */
internal data class SettingsApplyPlan(
    val permissionMode: PermissionModeSetting?,
    val effort: EffortSetting?,
    val deferred: Boolean,
)

/**
 * 判定抽成纯函数（同 [pickEffect] 的拆法）：真正干活的 `pickPermissionMode` /
 * `pickEffort` 要发协议、要弹提示，在单测里跑不动；而**边界全在这个判定里**。
 *
 * ## 三条边界，每条都对应一个真坑
 *
 * 1. **值没变就一个字节都不发。** 这条是隐藏的正确性要求，不是省事：
 *    `pickPermissionMode` 的第一件事就是在**相等判断之前**清掉 `autoAllow`
 *    （见那里的注释）—— 无脑调一次，"打开设置、什么都没改、关掉"就会把
 *    「本会话不再询问」静默关掉。
 * 2. **会话没就绪就什么都别推。** `client != null` 但 `ready == false` 的窗口期
 *    是真实存在的（`sendStart` 之后、`Ready` 之前）。那时推过去，协议消息会落到
 *    一个**启动参数早就发出去**的会话上 —— 标签显示一个这条会话实际没在用的模式，
 *    正是 `currentMode` 注释里要防的那种"控件撒谎"。
 * 3. **会话忙不算障碍。** 这两项改的都是**下一轮**的行为，回合进行中照样该生效
 *    （`pickEffort` 那边的注释明说了"忙时也允许改，也不弹确认框"）。
 *    所以这里没有、也**不该**有 `busy` 这个参数。
 */
internal fun settingsApplyPlan(
    sessionReady: Boolean,
    currentMode: PermissionModeSetting,
    currentEffort: EffortSetting,
    savedMode: PermissionModeSetting,
    savedEffort: EffortSetting,
): SettingsApplyPlan {
    val mode = savedMode.takeIf { it != currentMode }
    val effort = savedEffort.takeIf { it != currentEffort }

    // 没改就到此为止 —— 这一句是**必需的**，不是省事：没有它的话，
    // "会话没就绪 + 什么都没改"会走到下面那条 return，插一句"下次生效"，
    // 于是每开一次设置都冒一句凭空的话
    if (mode == null && effort == null) return SettingsApplyPlan(null, null, deferred = false)
    if (!sessionReady) return SettingsApplyPlan(null, null, deferred = true)
    return SettingsApplyPlan(mode, effort, deferred = false)
}
