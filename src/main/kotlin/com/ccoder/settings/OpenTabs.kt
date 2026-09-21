package com.ccoder.settings

/**
 * 一个会话标签**存下来的样子**（2026-09-21）。
 *
 * 存的是"哪条会话 + 哪条配置里的哪个模型"这样几个 id，不是一份快照：会话本体住在
 * claude 自己的 jsonl 里（随时可能被删、被「清空全部」），配置住在 [ModelProfiles]
 * 里（随时可能被改名、删模型、删掉整条）。存快照会存下一份永远跟不上的旧值 ——
 * 与 [ClaudeSettings.State.lastProfileId] 那边是同一条理由。
 *
 * 属性一律 `var` + 默认值：这是 XmlSerializer 认的形状（照 [ModelProfile] 的先例）。
 */
data class OpenTab(
    /**
     * 这条标签在跑哪条会话。
     *
     * **空串是合法取值**，意思是"这条标签还没起过会话"（「＋」开出来、一个字没发
     * 就关了 IDE）。重启后它该是一条**空**标签，而不是一句"上次那条不见了"。
     */
    var sessionId: String = "",

    /** 这条标签用的是哪条模型配置。空 = 没选过模型（走 CLI 自己的默认档）。 */
    var profileId: String = "",

    /** 上面那条配置里的哪个模型。 */
    var modelId: String = "",

    /**
     * 标签上写的那个名字（`ClaudePanel.currentSessionTitle` 存下来的原样）。
     *
     * 为什么连它一起存：**没上屏的标签拿不到名字**。恢复出来的后台标签要等用户点它
     * 才起 sidecar、才问得到会话列表，而那之前胶囊行画的就是这个名字 ——
     * 不存的话，一开 IDE 会看到一排「新会话」，而它们其实各有名字。
     *
     * null = 这条会话本来就没名字（新建、一个字没发），胶囊行按老规矩画斜体的
     * 「新会话」。
     */
    var title: String? = null,

    /**
     * 上次退出时**当前**的就是这一条。
     *
     * 不存下标：下标与列表是两处真相，手改一次 XML 就能让它们对不上，而
     * "当前是哪条"本来就该是这个标签自己的属性。
     */
    var selected: Boolean = false,
)

/**
 * 把读回来的标签表收敛成"能照它开工"的样子。
 *
 * 三条规矩，每条挡的都是一个具体的坏结果：
 *
 *  1. **同一条会话只留一次** —— 两条标签开同一条会话会两边同时往同一个 jsonl 里
 *     写（运行时由 `OpenSessions` 挡，这里挡的是存档里那份自相矛盾的数据：
 *     停笔一条、回来两条，第二条其实开不起来）
 *  2. **标记为当前的至多一条** —— 两条都写着"我才是当前的"时，恢复回来该选谁
 *     没有答案；取靠前的那条，规则是确定的
 *  3. **至多 [max] 条** —— 上限是界面侧的产品上限（`MAX_SESSION_TABS`），
 *     手改过 XML 也越不过去。**从尾部裁**：越靠后的标签越是"开完没怎么用"的
 *
 * 刻意**不做**的事：不因为配置或模型不见了就把整条标签丢掉。那种情况由界面侧
 * `reconcileModel` 处理（退回本项目最近一次的选择），在这里丢掉整条等于替用户
 * 关掉一条会话 —— 那个代价比"模型回到默认"大得多。
 */
internal fun pruneOpenTabs(tabs: List<OpenTab>, max: Int): MutableList<OpenTab> {
    val out = mutableListOf<OpenTab>()
    val seen = mutableSetOf<String>()
    var selectedTaken = false
    for (tab in tabs) {
        if (out.size >= max) break
        val sessionId = tab.sessionId.trim()
        // 空 sessionId 不参与去重：好几条"还没起会话"的空标签是合法的
        if (sessionId.isNotEmpty() && !seen.add(sessionId)) continue
        val selected = tab.selected && !selectedTaken
        if (selected) selectedTaken = true
        out += tab.copy(sessionId = sessionId, selected = selected)
    }
    return out
}
