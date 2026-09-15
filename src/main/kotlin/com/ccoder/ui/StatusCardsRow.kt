package com.ccoder.ui

import com.intellij.util.ui.JBUI
import java.awt.GridLayout
import javax.swing.JPanel

/** 四张卡之间的横向间隙。 */
private const val CARD_GAP = 5

/**
 * 一排四张状态卡。
 *
 * ## 为什么用 GridLayout 而不是 BoxLayout
 *
 * 四张卡必须**等宽**。BoxLayout 按首选宽度分配，而"已连接"比"2"宽得多，
 * 结果就是连接卡挤掉子代理卡。GridLayout 强制等分，宽度与内容无关。
 *
 * ## 为什么公开四个子视图而不是收一个 `setModels(...)`
 *
 * 四份数据来自四个互不相干的地方（连接状态的文字、[contextUsageOf]、
 * `todos`、`running`），刷新时机也各不相同 —— 收成一个四参函数会逼着
 * 每次刷新都重算另外三份。
 *
 * ## 参数顺序
 *
 * 两个 `() -> Unit` 都在最后。**将来加参数一律加到它们前面** ——
 * 尾随 lambda 会静默绑到最后一个参数上，这个坑本项目里踩过两次。
 *
 * ## 加第五张卡之前先算宽度
 *
 * 2026-09-15 试过一次"累计 token"卡（当天又按用户要求撤了）。那一版留下的
 * 教训是**加卡是拿宽度换的**：一行 404px 里每张卡从 97px 掉到 76px，
 * 连接卡那些四五个字的文案当场放不下（`StatusCardsRowTest` 里那条
 * "八种连接文字都放得下"就是量它的），最后不得不把文案收短。
 * 想再加之前，先看那张测试会不会红。
 */
internal class StatusCardsRow(
    onOpenContext: () -> Unit,
    onOpenTodos: () -> Unit,
    onOpenRunning: () -> Unit,
) : JPanel(GridLayout(1, 4, JBUI.scale(CARD_GAP), 0)) {

    internal val connection = StatusCardView(icon = CardIcon.Link)
    internal val context = StatusCardView(icon = CardIcon.Context, onOpen = onOpenContext)
    internal val todos = StatusCardView(icon = CardIcon.Tasks, onOpen = onOpenTodos)
    internal val running = StatusCardView(icon = CardIcon.Agents, onOpen = onOpenRunning)

    init {
        isOpaque = false
        add(connection)
        add(context)
        add(todos)
        add(running)
    }
}
