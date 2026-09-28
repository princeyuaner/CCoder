package com.ccoder.ui

import com.intellij.util.ui.JBUI
import java.awt.GridLayout
import javax.swing.JPanel

/** 卡与卡之间的横向间隙。 */
private const val CARD_GAP = 5

/** 这一行有几张卡。`GridLayout` 与「等宽下限」那条用例都读它。 */
internal const val STATUS_CARDS = 5

/**
 * 一排五张状态卡：连接 · **同步** · 上下文 · 任务列表 · 子代理。
 *
 * ## 为什么用 GridLayout 而不是 BoxLayout
 *
 * 五张卡必须**等宽**。BoxLayout 按首选宽度分配，而"已连接"比"2"宽得多，
 * 结果就是连接卡挤掉子代理卡。GridLayout 强制等分，宽度与内容无关。
 *
 * ## 为什么公开五个子视图而不是收一个 `setModels(...)`
 *
 * 五份数据来自五个互不相干的地方（连接状态的文字、[SyncStatus]、[contextUsageOf]、
 * `todos`、`running`），刷新时机也各不相同 —— 收成一个五参函数会逼着
 * 每次刷新都重算另外四份。
 *
 * ## 参数顺序
 *
 * 七个 `() -> Unit` 都在最后。**将来加参数一律加到它们前面** ——
 * 尾随 lambda 会静默绑到最后一个参数上，这个坑本项目里踩过两次
 * （2026-09-17 加 `onClear` / `onCompact` 时就是照这条办的）。
 *
 * ## 加第六张卡之前先算宽度（2026-09-24 又量了一次）
 *
 * 2026-09-15 试过一次"累计 token"卡（当天又按用户要求撤了）。那一版留下的
 * 教训是**加卡是拿宽度换的**：一行 404px 里每张卡从 97px 掉到 76px，
 * 连接卡那些四五个字的文案当场放不下（`StatusCardsRowTest` 里那条
 * "八种连接文字都放得下"就是量它的），最后不得不把文案收短。
 *
 * 2026-09-24 用户要加同步卡（"上下文左侧"），于是**又量了一遍**：
 * 五张卡下每张约 76px、值行约 58px。四条连接文案是当年为 58px 收短过的那一版，
 * 照旧放得下；真正被这一格挤到的是**同步卡自己那五个状态词**与**压缩中那句**：
 * `sync.state.*` 收成「运行中 / 未开启 / 已停止 / 被占用 / 有失败」这一档，
 * 上下文卡的「压缩中…」去掉省略号（英文那份的 `Compacting` 换成 `Compress`）。
 * 想再加之前，先看那两条宽度用例会不会红。
 */
internal class StatusCardsRow(
    onClear: () -> Unit,
    onCompact: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenTodos: () -> Unit,
    onOpenRunning: () -> Unit,
    onOpenSync: () -> Unit,
    onToggleSync: () -> Unit,
) : JPanel(GridLayout(1, STATUS_CARDS, JBUI.scale(CARD_GAP), 0)) {

    /** 连接卡多了个动作（右上角那颗扫把＝清空会话）—— 它从 v2 起连监听器都没有。 */
    internal val connection = StatusCardView(icon = CardIcon.Link, onAction = onClear)

    /**
     * 同步卡：点别处看日志、点右上角那颗拨总开关 —— 分流见 [cardClickTargetOf]。
     *
     * 排在连接与上下文之间（用户 2026-09-24 要的位置：**上下文左边**）。
     * 位置由 `add` 的顺序定，改动它是"用户能不能一眼找到"的事，不是美观问题。
     */
    internal val sync = StatusCardView(
        icon = CardIcon.Sync,
        onOpen = onOpenSync,
        onAction = onToggleSync,
    )

    /** 上下文卡：点别处开详情、点右上角那颗图标压缩 —— 分流见 [cardClickTargetOf]。 */
    internal val context = StatusCardView(
        icon = CardIcon.Context,
        onOpen = onOpenContext,
        onAction = onCompact,
    )
    internal val todos = StatusCardView(icon = CardIcon.Tasks, onOpen = onOpenTodos)
    internal val running = StatusCardView(icon = CardIcon.Agents, onOpen = onOpenRunning)

    init {
        isOpaque = false
        add(connection)
        add(sync)
        add(context)
        add(todos)
        add(running)
    }
}
