package com.ccoder.ui

import com.ccoder.sidecar.SubagentInfo
import com.google.gson.JsonObject
import com.ccoder.text.CcoderText
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.BasicStroke
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

// ---- 详情浮层的内容 ----

/** 浮层内容的外框：竖向排列 + 一圈内边距。 */
private fun detailBox() = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(10, 12, 11, 12)
}

/**
 * 任务列表那一段。点"任务列表"卡弹它。
 *
 * 与运行段分成两个函数而不是合成一个，是因为两段在 SDK 里是**两套不同的
 * 来源**：这里全是模型自己声明的计划（`TaskCreate` / `TodoWrite`），那里是真正在跑的东西
 * （task 消息族）。拆卡之后点哪张只看哪段，才不会让人以为清单里每一项
 * 都有个进程在跑。
 */
internal fun buildTodoDetail(todos: TaskList): JComponent {
    val box = detailBox()
    box.add(sectionHeader(CARD_TASKS, "${todos.completed}/${todos.total}"))
    todos.items.forEach { box.add(todoRow(it)) }
    return box
}

/**
 * 上下文那一段。点"上下文"卡弹它。
 *
 * 卡面只有一个百分比 —— 这里补上**绝对数**：分母（窗口多大）才是"还能聊多久"
 * 的关键，而它恰恰是卡面放不下的那个数（改版时它被让到 tooltip 里，这里给它
 * 一个正经位置）。
 *
 * 没有测量值时不装作有：照实说"还没拿到窗口大小"，与卡面显示 0 是同一条规矩。
 */
internal fun buildContextDetail(usage: ContextUsage?): JComponent {
    val box = detailBox()
    val u = usage ?: ContextUsage(usedTokens = 0, windowTokens = 0)
    val percent = contextPercentOf(u)
    box.add(sectionHeader(CARD_CONTEXT, percent?.let { "$it%" } ?: formatTokenCount(u.usedTokens)))

    box.add(detailRow(CcoderText.text("transcript.detail.used"), "${formatTokenCount(u.usedTokens)} tokens"))
    if (u.windowTokens <= 0) {
        box.add(hint(CcoderText.text("transcript.detail.noWindowMeasured")))
        return box
    }

    box.add(detailRow(CcoderText.text("transcript.detail.window"), "${formatTokenCount(u.windowTokens)} tokens"))
    val left = (u.windowTokens - u.usedTokens).coerceAtLeast(0)
    box.add(detailRow(CcoderText.text("transcript.detail.left"), "${formatTokenCount(left)} tokens"))
    box.add(
        hint(
            when {
                percent == null -> CcoderText.text("transcript.detail.hint.noWindow")
                percent >= 90 -> CcoderText.text("transcript.detail.hint.over90")
                percent >= 70 -> CcoderText.text("transcript.detail.hint.over70")
                else -> CcoderText.text("transcript.detail.hint.plenty")
            }
        )
    )
    return box
}

/** 一行"标题 —— 值"。与 [taskRow] 同一套观感，但只读、不可点。 */
private fun detailRow(label: String, value: String): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.emptyBottom(3)
    add(JBLabel(label).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.WEST)
    add(JBLabel(value), BorderLayout.EAST)
}

/** 一句话的说明，压在最后。 */
private fun hint(text: String): JComponent = JBLabel(text).apply {
    foreground = UIUtil.getInactiveTextColor()
    font = font.deriveFont(font.size2D - 1f)
    border = JBUI.Borders.emptyTop(6)
}

/**
 * 运行中那一段。点"子代理"卡弹它。
 *
 * 空着时给一句实话而不是一个空框 —— 卡上写着"空闲"时本不该弹得出来，
 * 但真弹出来了就得说清楚。
 *
 * ## 为什么还有第二段
 *
 * [running] 是**现在在跑**的东西（事件流里的 task 消息），[subagents] 是
 * **这个会话的全部子代理**（磁盘上的转写记录）。两套来源不同：
 * 跑完的子代理只在后者里。
 *
 * 同一件事会在两段里都出现一次 —— 那是**故意的**：上面回答"现在在跑什么"，
 * 下面回答"这个会话都干过什么"。靠 [SubagentInfo.toolUseId] 与任务的 id
 * 对上号（任务的 id 就是 tool_use id），于是上面那段的条目也能点开看转写。
 *
 * 第二段的标题是**「全部子代理」**，不是"跑过的"：转写是边跑边写的，
 * 所以**还在跑的子代理也在这段里**。2026-09-17 之前它写的是「这个会话的子代理」——
 * 准确，但整个浮层本来就是"这个会话"的，那四个字不区分任何东西
 * （真正要区分的是上面那段"此刻在跑"）。
 *
 * @param onStop 点每行右端那颗方块 → 终止这个任务（给的是任务的 id，即事件里那个
 *   `task_id`）。放在 [onOpen] **前面**：尾随 lambda 只绑最后一个参数
 *   （StatusCardsRow 的注释里记过这个坑），新参数一律加在它们前面。
 * @param onOpen 点某条 → 看它的转写。对不上号的（元信息没读到、或本来就不是
 *   子代理而是后台命令）不可点 —— 没有 agentId 就取不到转写
 */
internal fun buildRunningDetail(
    running: List<RunningTask>,
    subagents: List<SubagentInfo>,
    onStop: (String) -> Unit,
    onOpen: (SubagentInfo) -> Unit,
): JComponent {
    val box = detailBox()
    if (running.isEmpty() && subagents.isEmpty()) {
        box.add(JBLabel(CcoderText.text("transcript.detail.noTasks")).apply { foreground = UIUtil.getInactiveTextColor() })
        return box
    }

    if (running.isNotEmpty()) {
        box.add(sectionHeader(RUNNING_SECTION, running.size.toString()))
        running.forEach { task ->
            // 任务 → 子代理：靠 tool_use id 对上。对不上就还是普通一行
            box.add(taskRow(task, subagents.firstOrNull { it.toolUseId == task.id }, onStop, onOpen))
        }
    }

    if (subagents.isNotEmpty()) {
        box.add(sectionHeader(SUBAGENTS_SECTION, subagents.size.toString()))
        subagents.forEach { box.add(subagentRow(it, onOpen)) }
    }
    return box
}

/**
 * 一个子代理。有描述就用描述（那是人写的任务名），没有退回类型，再没有给 id。
 */
private fun subagentRow(agent: SubagentInfo, onOpen: (SubagentInfo) -> Unit): JComponent {
    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = CcoderText.text("transcript.detail.viewTranscript")
    }

    val text = listOfNotNull(agent.agentType, agent.description).joinToString("  ")
    val name = JBLabel(text.ifBlank { agent.agentId.take(8) })
    row.add(name, BorderLayout.WEST)

    row.addMouseListener(
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onOpen(agent)
        }
    )
    return row
}

/**
 * 某个子代理的转写。
 *
 * 逐条列**纯文本**，不复用转写区的渲染器：那一套是 JCEF 里的，而这个浮层是
 * Swing 的 —— 为它把整套渲染搬过来不值得。这里要回答的只是"它干了什么"。
 */
internal fun buildSubagentDetail(agent: SubagentInfo, items: List<JsonObject>): JComponent {
    val box = detailBox()
    val title = listOfNotNull(agent.agentType, agent.description).joinToString("  ")
    box.add(sectionHeader(title.ifBlank { agent.agentId.take(8) }, if (items.size == 1) CcoderText.text("transcript.detail.itemCountOne") else CcoderText.text("transcript.detail.itemCount", items.size)))

    val lines = items.mapNotNull { item ->
        val text = messageText(item) ?: return@mapNotNull null
        val who = if (item.get("type")?.asString == "user") "›" else "‹"
        "$who $text"
    }
    if (lines.isEmpty()) {
        box.add(JBLabel(CcoderText.text("transcript.detail.emptyTranscript")).apply {
            foreground = UIUtil.getInactiveTextColor()
        })
        return box
    }

    val area = JBTextArea(lines.joinToString("\n\n")).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(4, 6)
    }
    // 限高 + 可滚：转写可以很长，让它撑开浮层会把整个面板顶出去
    box.add(
        JBScrollPane(area).apply {
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(380, 280)
        }
    )
    return box
}

/**
 * 一条消息里能读出来的文字。读不出来给 null（整条都是工具调用之类的）。
 *
 * `message.content` 有两种形状：裸字符串（用户消息常见），或块数组。
 * 数组里只取 `text`，工具调用折成一行 `→ 工具名` —— 少了它，一段全是工具
 * 调用的转写会看起来像空的。
 */
internal fun messageText(item: JsonObject): String? {
    val content = item.obj("message")?.get("content") ?: return null
    if (content.isJsonPrimitive && content.asJsonPrimitive.isString) {
        return content.asString.takeIf { it.isNotBlank() }
    }
    if (!content.isJsonArray) return null

    val parts = content.asJsonArray.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val block = el.asJsonObject
        when (block.str("type")) {
            "text" -> block.str("text")?.takeIf { it.isNotBlank() }
            "tool_use" -> block.str("name")?.let { "→ $it" }
            else -> null
        }
    }
    return parts.joinToString("\n").takeIf { it.isNotBlank() }
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

/**
 * 详情浮层里那两段的小标题。
 *
 * 写成常量（而不是两处字面量）：用例要按**文字**找到那个小标题，本仓惯例；
 * 而且"这一段叫什么"就该只写在一个地方。
 *
 * [SUBAGENTS_SECTION] 原来叫「这个会话的子代理」——准确，但整个浮层本来就是
 * "这个会话"的，那四个字不区分任何东西（真正要区分的是上面那段"此刻在跑"）。
 * **也不能叫"跑过的"**：转写是边跑边写的，还在跑的子代理同样在这一段里。
 */
internal val RUNNING_SECTION: String get() = CcoderText.text("transcript.detail.running")
internal val SUBAGENTS_SECTION: String get() = CcoderText.text("transcript.detail.allSubagents")

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

private fun taskRow(
    task: RunningTask,
    /** 与它对应的子代理（按 tool_use id 对上）。null = 对不上，那就不可点。 */
    agent: SubagentInfo?,
    onStop: (String) -> Unit,
    onOpen: (SubagentInfo) -> Unit,
): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.emptyBottom(3)
    if (agent != null) {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = CcoderText.text("transcript.detail.viewTranscript")
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onOpen(agent)
            }
        )
    }

    // 两行（B2，2026-09-18 用户从选型台上挑的）：
    //
    //   第一行 = 任务名（`task_started.description`，一直有）
    //   第二行 = **现在在干嘛**，没有就不画这行
    //
    // 从前只有一行，而且用的是 `detail ?: label` —— 有进行时就**顶掉**任务名。
    // 那在真机上是一句空话：`detail` 只从 `task_progress.summary` 来，而 summary
    // 要 CLI 开 agentProgressSummaries 才生成（每 ~30s 一句），所以绝大多数时候
    // 那一行就是任务名本身。改成两行之后两件事都在，代价是每条高一档。
    val name = listOfNotNull(task.kind, task.label)
        .joinToString("  ")
        .ifBlank { task.id.take(8) }

    val meta = buildList {
        if (task.tokens > 0) add("${formatTokenCount(task.tokens)} tok")
        if (task.durationMs > 0) add(formatDuration(task.durationMs))
    }.joinToString(" · ")

    val head = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel(name), BorderLayout.WEST)
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

    val detail = task.detail?.takeIf { it.isNotBlank() }
    if (detail == null) {
        // 没有进行时：与从前**一字不差**（一行，任务名在左、统计在右）
        add(head, BorderLayout.CENTER)
    } else {
        val col = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(head)
            add(
                JBLabel(detail).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    font = font.deriveFont(font.size2D - 1f)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            )
        }
        add(col, BorderLayout.CENTER)
    }

    // 右端那颗「终止」挂在**整行的** EAST（不是 head 里）：两行的任务也要
    // 它贴着右沿，与单行那批对齐
    add(TaskStopButton(task.id, onStop), BorderLayout.EAST)
}

/**
 * 行右端那颗「终止」。
 *
 * 自绘一个小方块 —— 与发送键上的「停止」同一个形状语言（那个也是实心方块），
 * 不为这个动作新引入一颗图标。**常驻可见**（很淡），悬停提亮：它的用途是
 * "赶紧把在跑的东西停下来"，藏进悬停里等于让人先找一遍。
 *
 * 点它**不会**触发行上的"看它的转写"：Swing 的点击只发给最深的那个组件，
 * 行上的监听器收不到子组件里的点击。
 */
internal class TaskStopButton(
    private val taskId: String,
    private val onStop: (String) -> Unit,
) : JComponent() {

    private var hovered = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = CcoderText.text("transcript.detail.stopTask")
        preferredSize = Dimension(JBUI.scale(SIDE_W), JBUI.scale(SIDE_H))
        minimumSize = preferredSize
        maximumSize = preferredSize
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) = onStop(taskId)
            }
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 左边这段空是给方块与右边的统计数字（或第二行文字）透气用的。
            // 它也在点击区里 —— 方块本身太小，不差这一下
            val left = JBUI.scale(GAP)
            val boxW = width - left

            // 悬停给一层浅底，免得"提亮了"只体现在 8px 的方块上
            if (hovered) {
                g2.color = UIUtil.getListSelectionBackground(false)
                g2.fillRoundRect(left, 0, boxW - 1, height - 1, JBUI.scale(4), JBUI.scale(4))
            }

            // 方块本体：与发送键那个「停止」同一条画法（fillRoundRect）。
            // **不画字符**（■ 之类）—— 那个取决于系统字体装没装
            val side = JBUI.scale(8)
            g2.color = if (hovered) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
            g2.fillRoundRect(
                left + (boxW - side) / 2,
                (height - side) / 2,
                side,
                side,
                JBUI.scale(2),
                JBUI.scale(2),
            )
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        /** 整颗钮的宽 × 高（未缩放）。左边 [GAP] 那截是透气的空。 */
        const val SIDE_W = 26
        const val SIDE_H = 18
        const val GAP = 8
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
