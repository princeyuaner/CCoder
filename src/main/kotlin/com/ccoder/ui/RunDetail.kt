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

/** 一行"标题 —— 值"（清单段在用）。与 [agentCard] 的抬头同一套观感，但只读、不可点。 */
private fun detailRow(label: String, value: String): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    // detailBox 是 BoxLayout（Y）：交叉轴上**所有**子件都得是 LEFT。混着 0.5 时，
    // 窄的那个会被对齐到最宽子件的中心 —— 表现为裸标签各居中一次、看着像放歪了
    alignmentX = Component.LEFT_ALIGNMENT

    border = JBUI.Borders.emptyBottom(3)
    add(JBLabel(label).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.WEST)
    add(JBLabel(value), BorderLayout.EAST)
}

/** 一句话的说明，压在最后。详情浮层与上下文详情框共用。 */
internal fun hint(text: String): JComponent = JBLabel(text).apply {
    foreground = UIUtil.getInactiveTextColor()
    // detailBox 是 BoxLayout（Y）：交叉轴上**所有**子件都得是 LEFT。混着 0.5 时，
    // 窄的那个会被对齐到最宽子件的中心 —— 表现为裸标签各居中一次、看着像放歪了
    alignmentX = Component.LEFT_ALIGNMENT

    font = font.deriveFont(font.size2D - 1f)
    border = JBUI.Borders.emptyTop(6)
}

/**
 * 运行中那一段。点"子代理"卡弹它。
 *
 * 空着时给一句实话而不是一个空框 —— 卡上写着"空闲"时本不该弹得出来，
 * 但真弹出来了就得说清楚。
 *
 * ## 这一天改了两次，两次都记在这儿（2026-09-24）
 *
 * **第一次**：从"两段摊开"改成"卡 + 一条线"（用户从选型台上挑了方案甲）。
 * 从前的排法是上面「运行中」每条两行 + 右侧统计 + 一颗终止方块，紧接着
 * 「全部子代理」把**同一批人再列一遍**。用户的原话是"信息太多了"，而拆开看，
 * 多的不是细节，是**重复**。于是：**在跑的各自一张卡**（[agentCard]，卡 = 在跑，
 * 一眼分得清），记录收在一条线（"看已结束的 N 个 ›"）后面；两个小标题一并删掉。
 *
 * **第二次**：用户说"查看已结束的不要了，只要显示当前在跑的子代理" ——
 * 那条线与它后面那列记录**整段去掉**，这一屏现在只剩在跑的子代理。
 *
 * **第三次（当前）**：用户说"也不用显示当前运行的工具，只需要显示标题即可" ——
 * 卡上只剩**一行**（活点 + 类型与任务名 + 右端终止钮），进行时那行与统计那行都去掉。
 * 三层结构（列 / 分隔线 / 统计）一起塌回 [agentCard] 里那一行。
 *
 * 代价说清楚：`RunningTask` 的 `detail` / `tokens` / `durationMs` **从此没有任何
 * 地方显示**（`RunStatusTracker` 照样解析、用例照样钉着解析结果，只是没人画了）。
 *
 * ## [subagents] 这个参数现在只用来"对上号"
 *
 * 它不再被列出来。剩下的唯一用途：拿 [SubagentInfo.toolUseId] 去 [RunningTask.id]
 * 里找，对上了这张卡才点得开（没有 agentId 就取不到转写）。**没有别的判据** ——
 * `SubagentInfo` 没有状态字段。
 *
 * **代价说清楚**：已结束的子代理从此**回看不了转写了** —— 那一段从前是唯一的入口
 * （在跑的卡还能点开）。要回看只能去会话列表重开那条会话。
 *
 * @param onStop 点每张卡右端那颗方块 → 终止这个任务（给的是任务的 id，即事件里那个
 *   `task_id`）。放在 [onOpen] **前面**：尾随 lambda 只绑最后一个参数
 *   （StatusCardsRow 的注释里记过这个坑），新参数一律加在它们前面。
 * @param onOpen 点某张卡 → 看它的转写。对不上号的（元信息没读到、或本来就不是
 *   子代理而是后台命令）不可点 —— 没有 agentId 就取不到转写
 */
internal fun buildRunningDetail(
    running: List<RunningTask>,
    subagents: List<SubagentInfo>,
    onStop: (String) -> Unit,
    onOpen: (SubagentInfo) -> Unit,
): JPanel {
    val box = detailBox()
    if (running.isEmpty()) {
        // 卡上写着"空闲"时本不该弹得出来，但真弹出来了就得说实话
        box.add(dimNote(CcoderText.text("transcript.detail.noRunningSubagents")))
        return box
    }

    running.take(MAX_AGENT_CARDS).forEachIndexed { index, task ->
        // 缝加在前一张下面（不是在每张后面），免得最后一张底下多出一截空
        if (index > 0) box.add(Box.createVerticalStrut(JBUI.scale(AGENT_CARD_GAP)))
        // 任务 → 子代理：靠 tool_use id 对上。对上了这张卡才点得开；对不上也不影响
        // 终止钮（停的是任务，不是"子代理"这个身份）
        box.add(agentCard(task, subagents.firstOrNull { it.toolUseId == task.id }, onStop, onOpen))
    }
    val hidden = running.size - MAX_AGENT_CARDS
    if (hidden > 0) {
        // 卡不画了，但**数目不能瞒**：卡面上写着 6，这里只画 4 张，
        // 不说一句就成了"看漏了"（状态卡上的数才是权威的那个数）
        box.add(dimNote(CcoderText.text("transcript.detail.moreRunning", hidden)))
    }
    return box
}

/** 一句压暗的说明（空态、"还有 N 个在跑"）。靠左——见 [dimNote] 里那条对齐的坑。 */
private fun dimNote(text: String): JComponent = JBLabel(text).apply {
    foreground = UIUtil.getInactiveTextColor()
    // 靠左：detailBox 是 BoxLayout，裸标签默认按 0.5 对齐 —— 会居中，看着像放歪了
    alignmentX = Component.LEFT_ALIGNMENT
}

/**
 * 这两份清单画出来**长得一样**吗 —— 只有不影响布局的东西有差别时给 true。
 *
 * ## 为什么要问这个（2026-09-24 用户报"闪来闪去"）
 *
 * 开着的浮层靠 `refreshRunningPopup` 跟着清单刷新，而那个判等用的是整条列表的 `==`
 * —— `task_progress` 每报一次就把 tokens / 时长 / 进行时改掉，于是**每走一步都判"变了"**。
 * 而"变了"那条路是把浮层 **cancel 掉再新建一个**：四个子代理一起跑的时候，
 * 这个窗口每秒被拆掉重建好几次，屏幕上就是"闪来闪去"。
 *
 * 现在分两档：形状没变就**就地换内容**（窗口不动，所以不闪），形状真变了才重建
 * —— 那时高度确实变了，重新量一次尺寸与位置是对的。
 *
 * ## 判据只认**会改高度的东西**
 *
 * 卡上现在只有一行字，而那一行是 `kind + label`（见 [agentCard]），所以判据就剩
 * 这三样。`id` 不影响高度，但它决定这张卡点不点得开（要拿它去对子代理），
 * 换了就重建一次，省得点击绑在旧的目标上。
 *
 * **2026-09-24 第三次改版顺手把判据瘦了一圈**：从前还要判"进行时有没有""统计有没有"
 * （各会多出一行），现在那两行都不画了 —— 于是 `task_progress` 报什么都**不可能**
 * 再触发重建，只有"多了/少了一个任务/换了任务"会。
 */
internal fun List<RunningTask>.rendersSameShapeAs(other: List<RunningTask>): Boolean =
    size == other.size && zip(other).all { (a, b) ->
        a.id == b.id && a.kind == b.kind && a.label == b.label
    }

// ---- 在跑的子代理那张卡（2026-09-24）----

/**
 * 最多画几张卡，超了的用"还有 N 个在跑"交代。
 *
 * 8 这个数是**量出来的**（`RunningDetailRenderProbe` 每次跑都会打印）：
 * 2026-09-24 卡塌成一行之后，4 张卡 + "还有 1 个在跑" = 188px，一张卡约 30px
 * （塌之前一张 62px，那时 4 张就 359px，所以上限曾是 4）。浮层是**向上**弹的
 * （挂在面板底部那张卡上，见 `showAboveOrBelow`），而工具窗口首选高才 600 ——
 * 8 张 + "还有 2 个在跑" = 344px，**仍比旧版 4 张（359px）矮**。
 *
 * 超了不画那几条**不是瞒着**：状态卡上的数才是权威的那个数（`子代理 12`），
 * 这里少画几张就明说一句"还有 N 个在跑"。
 */
internal const val MAX_AGENT_CARDS = 8

/** 卡与卡之间的缝。卡自己不留缝（不像 `SessionCard` 那样把缝画进组件里）——
 *  这里是 `BoxLayout`，用一根 strut 更直白。 */
private const val AGENT_CARD_GAP = 6

/** 嵌在浮层里的卡，圆角跟浮层一致（8）。`CARD_CORNER_ARC`(16) 是**顶层**卡片的
 *  语言，缩在浮层里再 16 会显得泡泡的。 */
private const val AGENT_CARD_ARC = 8

/**
 * 一个在跑的子代理 = 一张卡。**一行**：活点 + 类型与任务名，右端一颗终止钮。
 *
 * ## 这一天改了三次，三次都记在这儿（2026-09-24）
 *
 * **第三次（当前）**：用户说"也不用显示当前运行的工具，只需要显示标题即可" ——
 * 进行时那行（`task_progress` 报的"现在在干嘛"，常是 `Reading xxx.kt` 这种）
 * 与统计那行（`47.5k tok · 19s`，带一条分隔线）都去掉。三层结构（竖向的列 /
 * 分隔线 / 统计）一起塌回这一行。
 *
 * **第二次**（同一天的上一版）：从"运行时一行 + 全部子代理一段"收成"卡 = 在跑、
 * 记录收在一条线后面"（布局照 `docs/design/subagent-detail.html` 的方案甲）。
 *
 * **第一次**（再往上）：那个"两行"是 2026-09-18 定的（B2，用户从选型台上挑的）——
 * 第一行任务名、第二行进行时。当时要它是因为再早的版本用 `detail ?: label`
 * 让进行时**顶掉**任务名，而 `detail` 要 CLI 开 `agentProgressSummaries`
 * 才生成（每 ~30s 一句），绝大多数时候等于白顶。
 *
 * 可点性只看 [agent]：对得上才能看转写（[SubagentInfo.toolUseId] 那条）。
 * 不可点时**连悬停监听都不挂** —— 一是没有反馈可给，二是用例拿"树里第一个挂
 * 监听器的组件"找那颗终止钮（[RunDetailTest]），多一个无关的监听器会把那条钉子
 * 弄成假绿。
 */
private fun agentCard(
    task: RunningTask,
    agent: SubagentInfo?,
    onStop: (String) -> Unit,
    onOpen: (SubagentInfo) -> Unit,
): JComponent {
    val card = AgentCard(clickable = agent != null)

    val name = listOfNotNull(task.kind, task.label)
        .joinToString("  ")
        .ifBlank { task.id.take(8) }

    val nameLabel = JBLabel(name)
    if (agent != null) {
        val watcher = object : MouseAdapter() {
            // 悬停提亮由卡自己管（它才知道自己在画什么），点击的回调在这里
            override fun mouseClicked(e: MouseEvent) = onOpen(agent)
        }
        nameLabel.addMouseListener(watcher)
        card.addMouseListener(watcher)
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        card.toolTipText = CcoderText.text("transcript.detail.viewTranscript")
    }

    card.add(
        JPanel(BorderLayout()).apply {
            isOpaque = false
            // **这一句不能少**：detailBox 是 BoxLayout，交叉轴上所有子件共用一个
            // 对齐基准 —— 混着一个 0.5（面板的默认值）就会把窄的那些按最宽子件的
            // 中心摆，表现为"文字莫名其妙缩进了一截"。
            // 2026-09-24 就是这么栽的：探针把每件的 x 倒出来才看见（当时那行 x=125）。
            alignmentX = Component.LEFT_ALIGNMENT
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(LiveDot())
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                    add(nameLabel)
                },
                BorderLayout.WEST,
            )
            add(TaskStopButton(task.id, onStop), BorderLayout.EAST)
        },
        BorderLayout.CENTER,
    )
    return card
}

/**
 * 卡片自己画底与描边，不用 [RoundedLineBorder] 当 border —— 悬停要**整块换底色**，
 * 而 border 只画线、画不了底（同 `SessionCard` 的理由）。
 *
 * [clickable] 为 false 时一点反馈都不给：这张卡点不动（对不上子代理），
 * 提亮会让人以为点了会有事发生。
 */
internal class AgentCard(private val clickable: Boolean) : JPanel(BorderLayout()) {

    private var hovered = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(7, 9, 8, 9)
        // 卡自己撑满整宽，本来摆在哪都一样 —— 但**同一条 BoxLayout 里那个"最歪"的
        // 子件会决定其他窄子件的位置**：卡是 JPanel（默认 0.5），它一走 0.5，
        // 旁边那些不能撑宽的小标签（"还有 N 个在跑"、"看已结束的 N 个 ›"）就跟着
        // 跑到中间去了。2026-09-24 从探针图上看见的，量出 x 才认准。
        alignmentX = Component.LEFT_ALIGNMENT
        if (clickable) {
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            })
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(AGENT_CARD_ARC)
            val w = width - 1
            val h = height - 1
            g2.color = if (hovered && clickable) UIUtil.getListSelectionBackground(false) else cardFill()
            g2.fillRoundRect(0, 0, w, h, arc, arc)
            g2.color = lineColor()
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, w, h, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 在跑那个小活点。
 *
 * **只画一个点，不画进度**：数据里只有计数（`RunningTask` 没有分母，
 * 见 [Indicator.Dots] 那段"没有分母就是没有分母"），画成条就成了编出来的进度。
 * 深色/浅色各拿 [focusColor] 一份。
 */
internal class LiveDot : JComponent() {
    init {
        alignmentY = Component.CENTER_ALIGNMENT
        preferredSize = JBUI.size(DOT_SIZE, DOT_SIZE)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = focusColor()
            val d = JBUI.scale(DOT_SIZE)
            g2.fillOval((width - d) / 2, (height - d) / 2, d, d)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val DOT_SIZE = 7
    }
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
        box.add(
            JBLabel(CcoderText.text("transcript.detail.emptyTranscript")).apply {
                foreground = UIUtil.getInactiveTextColor()
            // 靠左：detailBox 是 BoxLayout，裸标签默认按 0.5 对齐 —— 会居中，看着像放歪了
            alignmentX = Component.LEFT_ALIGNMENT
            },
        )
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
 * 详情浮层里的小标题（清单段、子代理转写段在用）。**运行段 2026-09-24 起不用了**：
 * 那一屏改成了"在跑的各一张卡 + 记录收在一条线后面"，卡本身就是"在跑"的标题，
 * 再给一段小标题就成了那批重复信息的一部分（`transcript.detail.running` /
 * `allSubagents` 两个键一并删掉了）。
 *
 * 写成常量（而不是两处字面量）：用例要按**文字**找到那个小标题，本仓惯例；
 * 而且"这一段叫什么"就该只写在一个地方。
 */
private fun sectionHeader(title: String, count: String): JComponent =
    JPanel(BorderLayout()).apply {
        isOpaque = false
    // detailBox 是 BoxLayout（Y）：交叉轴上**所有**子件都得是 LEFT。混着 0.5 时，
    // 窄的那个会被对齐到最宽子件的中心 —— 表现为裸标签各居中一次、看着像放歪了
    alignmentX = Component.LEFT_ALIGNMENT

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
    // detailBox 是 BoxLayout（Y）：交叉轴上**所有**子件都得是 LEFT。混着 0.5 时，
    // 窄的那个会被对齐到最宽子件的中心 —— 表现为裸标签各居中一次、看着像放歪了
    alignmentX = Component.LEFT_ALIGNMENT
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

