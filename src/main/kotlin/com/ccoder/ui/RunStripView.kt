package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.BasicStroke
import java.util.Locale
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.AbstractBorder

// ---- 圆角描边 ----

/**
 * 圆角线框。
 *
 * 颜色取的是**函数**而不是值：聚焦时描边要变成强调色，若把 Color 存成字段，
 * 每次焦点变化都得重建 border 并重新布局。取函数则只需 repaint。
 *
 * 自己画而不用平台的 `RoundedLineBorder`：这一层总共十几行，换来的是
 * 描边宽度、圆角半径、抗锯齿全在手上，不随平台 API 变动。
 */
internal class RoundedLineBorder(
    private val colorProvider: () -> Color,
    private val arc: Int,
) : AbstractBorder() {

    /** 当前描边色。暴露出来是为了让"聚焦只换色不加粗"这条能被测试钉住。 */
    internal fun color(): Color = colorProvider()

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = colorProvider()
            g2.stroke = BasicStroke(1f)
            // 减 1：线宽 1 时画到 w 会有一半落在组件外，被裁掉
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    override fun getBorderInsets(c: Component) = Insets(1, 1, 1, 1)
}

// ---- 上下文行 ----

/**
 * 连接状态、用量与运行状态共用的那一行。
 *
 * 左边是**连接状态 + 用量**，右边是任务条 —— 三者都是"当前这一刻"的状态，
 * 挤在同一行谁也不多占一层，输入框的高度就不会被它们推来推去。
 *
 * 连接状态原先在顶部单独一行，那一行于是只为了它一个人撑着高度。
 * 挪下来之后顶部那行让给了会话标签。
 */
internal fun buildContextRow(
    status: JComponent,
    usage: JComponent,
    runStrip: JComponent,
): JPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(0, 1, 3, 1)
    add(
        JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            add(status)
            add(usage)
        },
        BorderLayout.WEST,
    )
    add(runStrip, BorderLayout.EAST)
}

/** 用量那一半。取不到用量时整条隐藏，所以它自己不负责可见性。 */
internal fun buildUsageLabel(): JLabel = JBLabel().apply {
    foreground = UIUtil.getInactiveTextColor()
}

// ---- 详情浮层的内容 ----

/**
 * 点开条之后看到的东西。
 *
 * 分两段而不是一段，因为"任务清单"和"运行中"在 SDK 里是**两套不同的来源**：
 * 前者是模型自己声明的计划（TodoWrite），后者是真正在跑的东西
 * （task 消息族）。混在一起会让人以为清单里每一项都有个进程在跑。
 */
internal fun buildRunDetail(tracker: RunStatusTracker): JComponent {
    val box = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(10, 12, 11, 12)
    }

    tracker.todos?.let { todos ->
        box.add(sectionHeader("任务清单", "${todos.completed}/${todos.total}"))
        todos.items.forEach { box.add(todoRow(it)) }
    }

    if (tracker.running.isNotEmpty()) {
        if (box.componentCount > 0) box.add(Box.createVerticalStrut(JBUI.scale(9)))
        box.add(sectionHeader("运行中", tracker.running.size.toString()))
        tracker.running.forEach { box.add(taskRow(it)) }
    }

    // 两段都空时给一句实话，而不是弹出一个空框
    if (box.componentCount == 0) {
        box.add(JBLabel("当前没有任务").apply { foreground = UIUtil.getInactiveTextColor() })
    }
    return box
}

private fun sectionHeader(title: String, count: String): JComponent =
    JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(6)
        add(
            JBLabel(title).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(font.size2D - 1f)
            },
            BorderLayout.WEST,
        )
        add(
            JBLabel(count).apply {
                foreground = JBColor.namedColor("Component.focusColor", UIUtil.getTreeSelectionBackground(true))
                font = font.deriveFont(font.size2D - 1f)
            },
            BorderLayout.EAST,
        )
    }

private fun todoRow(item: TodoItem): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.emptyBottom(3)
    val glyph = when (item.state) {
        TodoState.Completed -> "✓"
        TodoState.InProgress -> "◐"
        TodoState.Pending -> "○"
    }
    add(
        JBLabel("$glyph  ${item.text}").apply {
            // 已完成压暗并划掉：它还在列表里是为了让人看清进度，不是为了读它
            foreground = if (item.state == TodoState.Completed) {
                UIUtil.getInactiveTextColor()
            } else {
                UIUtil.getLabelForeground()
            }
        },
        BorderLayout.WEST,
    )
}

private fun taskRow(task: RunningTask): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.emptyBottom(3)

    val label = listOfNotNull(task.kind, task.detail ?: task.label)
        .joinToString("  ")
        .ifBlank { task.id.take(8) }
    add(JBLabel(label), BorderLayout.WEST)

    val meta = buildList {
        if (task.tokens > 0) add("${formatTokenCount(task.tokens)} tok")
        if (task.durationMs > 0) add(formatDuration(task.durationMs))
    }.joinToString(" · ")
    if (meta.isNotEmpty()) {
        add(
            JBLabel(meta).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = font.deriveFont(font.size2D - 1f)
            },
            BorderLayout.EAST,
        )
    }
}

/** 8000 → "8s"；95000 → "1m35s"。超过一小时只给小时 —— 那时分钟已无意义。 */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format(Locale.ROOT, "%dh%02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.ROOT, "%dm%02ds", minutes, seconds)
        else -> "${seconds}s"
    }
}
