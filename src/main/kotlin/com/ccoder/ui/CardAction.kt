package com.ccoder.ui

import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncSnapshot
import com.ccoder.text.CcoderText
import com.google.gson.JsonObject

/**
 * 状态卡上那几颗按钮的全部纯逻辑（spec `2026-09-17-card-actions-design.md`）。
 *
 * 抽出来的理由与 [mainButtonState]、[isSessionSwitch] 同一个：ClaudePanel 依赖
 * Swing 与平台、起不了单测，而"什么时候能点""点了发什么"正是这次最容易写错的部分。
 * 组件（[StatusCardView]）只负责画与转发，一个判断都不做。
 */

/**
 * 卡上那颗按钮要做什么。[Clear] 走 `/clear`，[Compact] 走 `/compact`，
 * [TurnOn] / [TurnOff] 拨同步那个总开关（2026-09-24）。
 *
 * 开关是**两态两图形**（▶ / ⏸）而不是"一颗电源符号、字面靠卡上的词说"：
 * 图标是这颗按钮里唯一说"点下去会往哪个方向走"的地方，而卡上的词说的是
 * "现在是什么状态" —— 两件事混成一个图形，用户就得先读字才知道能不能点。
 */
internal enum class CardActionKind { Clear, Compact, TurnOn, TurnOff }

/**
 * 一颗动作按钮的全部内容。
 *
 * [danger] 不并用 [Tone]：Tone 说的是"这个**状态**要不要紧"，而这里是
 * "这个**动作**危不危险" —— 清空是 danger，而连接卡此刻的状态可能是 Ok（已连接）。
 * 两件事混进一个枚举，将来加动作就得给 Tone 瞎添成员，最后没人说得清
 * `Tone.Ok` 加在动作上是什么意思。
 */
internal data class CardAction(
    val kind: CardActionKind,
    val label: String,
    val tooltip: String,
    val danger: Boolean,
    val enabled: Boolean,
)

/** 卡面文案。四个字与五个字是量过 83px 行宽的（设计稿里同尺寸渲染过）。 */
internal val CLEAR_ACTION_LABEL: String get() = CcoderText.text("card.action.clear")
internal val COMPACT_ACTION_LABEL: String get() = CcoderText.text("card.action.compact")

/** 同步那两颗的卡面文案（同一颗按钮的两个方向）。 */
internal val SYNC_ON_ACTION_LABEL: String get() = CcoderText.text("card.action.syncOn")
internal val SYNC_OFF_ACTION_LABEL: String get() = CcoderText.text("card.action.syncOff")

/**
 * 清空那颗按钮。
 *
 * 可用条件 = 会话就绪**且**空闲（spec §3.5）：未就绪时发都发不出去，
 * 忙时点等于把进行中的回合连根拔了 —— 用户已定这两个都灰掉
 * （**只灰按钮**；输入框敲 `/clear` 的命令入口保持原样，照排）。
 *
 * 工具提示随可用性换一句话：灰着不解释，用户只会以为坏了。
 */
internal fun clearActionOf(ready: Boolean, busy: Boolean): CardAction {
    val usable = ready && !busy
    return CardAction(
        kind = CardActionKind.Clear,
        label = CLEAR_ACTION_LABEL,
        tooltip = if (usable) {
            CcoderText.text("card.action.clearTip")
        } else {
            CcoderText.text("card.action.clearDisabled")
        },
        danger = true,
        enabled = usable,
    )
}

/**
 * 压缩那颗按钮。
 *
 * **压缩中给 null**：那一刻值行让给「压缩中…」，按钮整个不存在（spec §3.5）——
 * 灰按钮还占着位子的话，用户会盯着一个永远不亮的按钮等。
 *
 * 判据只认 `ready && !busy`：压缩本身是一个回合，跑起来时 `busy` 已经为真。
 */
internal fun compactActionOf(ready: Boolean, busy: Boolean, compacting: Boolean): CardAction? {
    if (compacting) return null
    val usable = ready && !busy
    return CardAction(
        kind = CardActionKind.Compact,
        label = COMPACT_ACTION_LABEL,
        tooltip = if (usable) {
            CcoderText.text("card.action.compactTip")
        } else {
            CcoderText.text("card.action.compactDisabled")
        },
        danger = false,
        enabled = usable,
    )
}

/**
 * 同步那个总开关。
 *
 * ## 这是**拨开关**，不是发命令
 *
 * 另外两颗动作按钮发的是一条命令（`/clear`、`/compact`，见 `ClaudePanel` 里那两行
 * 注释），而这一颗拨的是 `SyncConfig.enabled` ——
 * 它没有任何命令要发，服务自己会照着新配置重建（`SyncSettings.update` → 通知
 * → `SyncService.reconcile`）。
 *
 * ## 两态怎么分：看**开关现在在哪一边**，不看它跑没跑起来
 *
 * | 状态 | 图形 | 点下去 |
 * |---|---|---|
 * | `RUNNING` / `FAILED` | ⏸ 关闭 | 把开关拨到关 |
 * | `OCCUPIED`（另一窗口占着，开关本身是开的） | ⏸ 关闭 | 同上 |
 * | `STOPPED`（开关开着但起不来） | ⏸ 关闭 | 同上 |
 * | `DISABLED`（开关在关那一侧） | ▶ 开启 | 把开关拨到开 |
 *
 * **`STOPPED` 那一档必须给 ⏸**：那时 `enabled` 已经是真，再"开启"一次
 * `SyncSettings.update` 会判"没变"直接返回 —— 那是个点了什么都不发生的按钮
 * （仓库里管这叫"画出可点的东西却点不动，是在骗人"）。给 ⏸ 则**两个方向都真**：
 * 关掉 → 下一次打开就是一次真正的重试（服务照着新配置从头来一遍）。
 *
 * ## 灰的那一档：路都没选，开了也没用
 *
 * [pathsReady] = 两端目录都填了。没填时开关仍然是开的（`SyncConfig.isConfigured`
 * 三个条件里缺一个就不启动），所以点下去只会得到一次白跑的重建 —— 灰掉并说清
 * 缺什么（同清空那颗"灰着不解释，用户只会以为坏了"）。
 *
 * @param snapshot 同步状态快照。null = 还没有过（当"开关在关那一侧"）。
 * @param pathsReady 两端目录是否都填好了。
 */
internal fun syncActionOf(snapshot: SyncSnapshot?, pathsReady: Boolean): CardAction {
    val run = snapshot?.run ?: SyncRun.DISABLED
    // 开关在"开"那一侧的状态有三个：在跑、在跑但有失败、以及"开着却起不来"。
    // OCCUPIED 也在其中 —— 它的 enabled 是真，只是那一对目录被别人占着
    val switchOn = run != SyncRun.DISABLED
    return if (switchOn) {
        CardAction(
            kind = CardActionKind.TurnOff,
            label = SYNC_OFF_ACTION_LABEL,
            tooltip = CcoderText.text("card.action.syncOffTip"),
            // 关同步不删任何东西（已经复制过去的留在原地），所以不是危险动作 ——
            // 危险色留给真的会毁东西的那一颗（清空）
            danger = false,
            enabled = true,
        )
    } else {
        CardAction(
            kind = CardActionKind.TurnOn,
            label = SYNC_ON_ACTION_LABEL,
            tooltip = if (pathsReady) {
                CcoderText.text("card.action.syncOnTip")
            } else {
                CcoderText.text("card.action.syncOnDisabled")
            },
            danger = false,
            enabled = pathsReady,
        )
    }
}

/** 点击落在哪儿。 */
internal enum class CardClickTarget { Action, OpenDetail, None }

/**
 * 卡片点击的去向。
 *
 * **只有落在右上角那块命中区里才算动作**（spec §3.3）：卡片其余部分照旧 ——
 * 走详情（上下文卡 / 同步卡）或什么也不做（连接卡）。
 *
 * 这条同时是"清空不设确认框"的安全垫：误触得正好点中那 16×16 的一格。
 * 反过来，没这道闸的话，点卡片想开详情的人会有机会把会话清掉。
 *
 * 注意判据是**命中的位置**，不是"悬停过" —— 图标常驻，点击不依赖任何悬停状态
 * （"悬停换值"那一版正是死在这里：指针往按钮去的路上悬停就没了）。
 */
internal fun cardClickTargetOf(
    onActionIcon: Boolean,
    hasAction: Boolean,
    hasDetail: Boolean,
): CardClickTarget = when {
    onActionIcon && hasAction -> CardClickTarget.Action
    hasDetail -> CardClickTarget.OpenDetail
    else -> CardClickTarget.None
}

/** 压缩这件事的两态。**没有"未知"** —— 认不出的 status 值返回 null 表示"不变"。 */
internal enum class CompactState { Idle, Compacting }

/**
 * status 事件 → 压缩中的状态。null = 这条与压缩无关，保持原样。
 *
 * 为什么判据取 CLI 的 status 而不取"我们刚发了什么"（spec §3.5）：**自动压缩**
 * 也要能在卡上看见，而那种情况我们没发过任何命令。与 [permissionModeOfStatus]
 * 同一条规矩：CLI 报的实况是权威。
 *
 * 实测（spec 事实 9、11，`sidecar/tools/probe-compact.mjs`）：
 * `compacting` 进入；`status:null` + `compact_result:"success"` 退出 —— 退出**不等**
 * `compact_boundary`，它更晚（boundary 在 init 之后，status 在它之前）。
 *
 * ⚠️ `requesting` **每一回合都会发**（普通问答也有），拿它当压缩中会让卡上一直
 * 显示"压缩中…"。所以它对不上任何分支，走 null。
 */
internal fun compactStateOfStatus(event: JsonObject): CompactState? {
    if (event.str("type") != "system" || event.str("subtype") != "status") return null
    val raw = event.get("status") ?: return null
    if (raw.isJsonNull) return CompactState.Idle
    val text = raw.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    return when (text) {
        "compacting" -> CompactState.Compacting
        else -> null
    }
}

/**
 * `compact_boundary` → 转写区那条系统提示。不是 boundary 事件给 null。
 *
 * **两种字段形状都要认**（spec 事实 12）：实时事件是 snake_case
 * （`compact_metadata.pre_tokens`），而落进会话文件、恢复会话时重放的是
 * camelCase（`compactMetadata.preTokens`）—— 而回放同样走 [MessageRenderer]。
 * 只认一种的话，回执就只在"现场"或只在"历史"里出现，这是最难被发现的那类半边功能。
 *
 * ⚠️ 2026-09-20 实测（SDK 0.3.274 的 `getSessionMessages`）：回放项里**只有
 * user/assistant**（某会话 107 条里 0 条 system），所以 camelCase 这一份今天
 * 没有实战路径 —— 恢复会话时看不到这条回执。留着是防御（SDK 哪天开始透传
 * system 条目，它自动补上），但"恢复后转写区里还有压缩的痕迹"这句话不成立：
 * 唯一的痕迹是落盘那条压缩摘要，而它按 CLI 的规矩也不再画了
 * （见 [MessageRenderer.renderPrompt]）。
 *
 * 字段缺就降级照写，**不猜**（三个字段里两个是可选的）：不知道压缩前多少，
 * 就只说压过了，不编一个数字。
 */
internal fun compactReceiptOf(event: JsonObject): String? {
    if (event.str("subtype") != "compact_boundary") return null

    val meta = event.obj("compact_metadata") ?: event.obj("compactMetadata")
    val auto = meta?.str("trigger") == "auto"

    val pre = meta?.long("pre_tokens") ?: meta?.long("preTokens")
    val post = meta?.long("post_tokens") ?: meta?.long("postTokens")
    val durationMs = meta?.long("duration_ms") ?: meta?.long("durationMs")

    val head = if (auto) CcoderText.text("card.compact.auto") else CcoderText.text("card.compact.done")
    return when {
        pre == null -> head
        post == null -> CcoderText.text("card.compact.before", head, formatTokenCount(pre))
        else -> {
            val base = CcoderText.text("card.compact.range", head, formatTokenCount(pre), formatTokenCount(post))
            if (durationMs == null) base else CcoderText.text("card.compact.took", base, elapsedText(secondsOf(durationMs)))
        }
    }
}

/** 毫秒 → 秒，四舍五入。与工具卡、思考块、等待响应卡同一个写法（[elapsedText]）。 */
private fun secondsOf(durationMs: Long): Int = Math.round(durationMs / 1000.0).toInt()

// 与 ui 包里其它文件同一写法：每个文件自带一份，别为这个把包结构改了
private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
