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
 */
internal class StatusCardsRow(
    onOpenTodos: () -> Unit,
    onOpenRunning: () -> Unit,
) : JPanel(GridLayout(1, 4, JBUI.scale(CARD_GAP), 0)) {

    internal val connection = StatusCardView()
    internal val context = StatusCardView()
    internal val todos = StatusCardView(onOpen = onOpenTodos)
    internal val running = StatusCardView(onOpen = onOpenRunning)

    init {
        isOpaque = false
        add(connection)
        add(context)
        add(todos)
        add(running)
    }
}
