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
import javax.swing.BorderFactory
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

/** 一行"标题 —— 值"（清单段在用）。与 [recordRow] 同一套观感，但只读、不可点。 */
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
 * ## 2026-09-24 改版：从"两段摊开"改成"卡 + 一条线"（用户从选型台上挑了方案甲）
 *
 * 从前的排法是两段摊开：上面「运行中」每条两行 + 右侧统计 + 一颗终止方块，
 * 紧接着「全部子代理」把**同一批人再列一遍**。用户的原话是"信息太多了"，
 * 而拆开看，多的不是细节，是**重复** —— 第二段那几条正在跑的和上面一字不差。
 * 而第二段还**天生只有名字**：`SubagentInfo` 就四个字段（id / 类型 / 描述 /
 * 对上号的 id），没有 token、没有耗时、没有状态 —— 那不是没排好，是数据里就没有。
 * 它又**不能删**：点一条回看它的转写是目前唯一的回看入口。
 *
 * 现在分成两种形态：
 *
 * - **在跑的各自一张卡**（[agentCard]）：卡 = 在跑，一眼分得清，不用读小标题；
 *   统计回到它自己那张卡里，不再飘到 300px 之外。
 * - **记录收在一条线后面**（"看已结束的 N 个 ›"），展开之后是压暗的单行。
 *   这一档空闲时本来就有（2026-09-20 加的），现在"有在跑"时也用同一套 ——
 *   重复就是这么消掉的。
 *
 * 于是两段小标题都没了（`transcript.detail.running` / `allSubagents` 两个键一并删）。
 *
 * ## 怎么区分"在跑"与"记录"
 *
 * `SubagentInfo` 没有状态字段，只能用 [SubagentInfo.toolUseId] 去 [RunningTask.id]
 * 里找：对得上就是在跑（它已经是上面某张卡了），对不上就是记录。**没有别的判据**，
 * 所以对不上号的那种永远是记录 —— 这一条同时决定了卡片可不可点。
 *
 * @param onStop 点每张卡右端那颗方块 → 终止这个任务（给的是任务的 id，即事件里那个
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
    /**
     * "看已结束的 N 个"那一行**是否已经展开**（2026-09-20 加，2026-09-24 起
     * 有在跑时也用它）。
     *
     * 用户看着一张写着「空闲」的卡问"子代理都没了点开为什么还有内容" —— 那是**记录**，
     * 不是"还在跑"：这条浮层的下半段列的是本会话跑过的子代理（点一条能回看它的转写）。
     * 问题不在有内容，在于"空闲 + 18 条"读起来自相矛盾。
     * 展开状态住在调用方（`ClaudePanel`）：这里每画一次都是一次性构建。
     */
    historyExpanded: Boolean = false,
    onToggleHistory: () -> Unit = {},
): JComponent {
    val box = detailBox()
    if (running.isEmpty() && subagents.isEmpty()) {
        box.add(dimNote(CcoderText.text("transcript.detail.noTasks")))
        return box
    }

    val runningIds = running.mapTo(mutableSetOf()) { it.id }

    if (running.isNotEmpty()) {
        running.take(MAX_AGENT_CARDS).forEach { task ->
            // 任务 → 子代理：靠 tool_use id 对上。对不上就不可点，但终止钮照旧
            box.add(agentCard(task, subagents.firstOrNull { it.toolUseId == task.id }, onStop, onOpen))
            box.add(Box.createVerticalStrut(JBUI.scale(AGENT_CARD_GAP)))
        }
        val hidden = running.size - MAX_AGENT_CARDS
        if (hidden > 0) {
            // 卡不画了，但**数目不能瞒**：卡面上写着 6，这里只画 4 张，
            // 不说一句就成了"看漏了"（状态卡上的数才是权威的那个数）
            box.add(dimNote(CcoderText.text("transcript.detail.moreRunning", hidden)))
        }
    }

    // 记录 = 子代理里对不上任何在跑任务的那些。对得上的已经在上面某张卡里了
    val records = subagents.filter { it.toolUseId == null || it.toolUseId !in runningIds }
    if (records.isEmpty()) return box

    if (running.isEmpty()) {
        box.add(dimNote(CcoderText.text("transcript.detail.noRunningSubagents")))
    }
    // 这一行**展开之后也留着**：从前只在收着时画，于是展开就再也收不回去
    // （只能关掉浮层重开）—— 一个只能进不能出的开关不是开关
    box.add(
        disclosureRow(
            if (historyExpanded) {
                CcoderText.text("transcript.detail.hideFinished")
            } else {
                CcoderText.text("transcript.detail.showFinished", records.size)
            },
            onToggleHistory,
        ),
    )
    if (historyExpanded) records.forEach { box.add(recordRow(it, onOpen)) }
    return box
}

/** 一句压暗的说明（空态、"还有 N 个在跑"）。靠左——见 [dimNote] 里那条对齐的坑。 */
private fun dimNote(text: String): JComponent = JBLabel(text).apply {
    foreground = UIUtil.getInactiveTextColor()
    // 靠左：detailBox 是 BoxLayout，裸标签默认按 0.5 对齐 —— 会居中，看着像放歪了
    alignmentX = Component.LEFT_ALIGNMENT
}

/**
 * 这两份清单画出来**长得一样**吗 —— 只有不影响布局的那些数（token、时长、进行时的字）
 * 有差别时给 true。
 *
 * ## 为什么要问这个（2026-09-24 用户报"闪来闪去"）
 *
 * 开着的浮层靠 `refreshRunningPopup` 跟着清单刷新，而那个判等用的是整条列表的 `==`
 * —— `task_progress` 每报一次就把 tokens / 时长 / 进行时改掉，于是**每走一步都判"变了"**。
 * 而"变了"那条路是把浮层 **cancel 掉再新建一个**：四个子代理一起跑的时候，
 * 这个窗口每秒被拆掉重建好几次，屏幕上就是"闪来闪去"。
 *
 * 现在分两档：形状没变就**就地换内容**（窗口不动，所以不闪），形状真变了
 * （多了/少了一个任务、或者某条从"没有进行时"变成"有"）才重建 —— 那时高度确实变了，
 * 重新量一次尺寸与位置是对的。
 *
 * 判据只认**会改高度的东西**：
 *  - `id` / `kind` / `label`：换了就是另一张卡；
 *  - 进行时**有没有**（不是内容）：有一条就多一行，高度不一样；
 *  - 统计**有没有**（不是具体数）：`47.5k tok · 19s` 与 `1m02s` 一样宽，但
 *    "还没有统计"到"有统计"会多出那条分隔线与一行。
 */
internal fun List<RunningTask>.rendersSameShapeAs(other: List<RunningTask>): Boolean =
    size == other.size && zip(other).all { (a, b) ->
        a.id == b.id &&
            a.kind == b.kind &&
            a.label == b.label &&
            (a.detail?.isNotBlank() == true) == (b.detail?.isNotBlank() == true) &&
            hasMeta(a) == hasMeta(b)
    }

private fun hasMeta(task: RunningTask): Boolean = task.tokens > 0 || task.durationMs > 0

/**
 * 「看已结束的 N 个 ›」那一行：可点，颜色按"这是条能点的文字"来取。
 *
 * 监听器**同时挂在行与文字上**：Swing 的事件不冒泡，只挂行的话，点在字上没反应
 * —— 而那正是人会点的地方（[recordRow] 也有过同一个坑，一并修了）。
 */
private fun disclosureRow(text: String, onClick: () -> Unit): JComponent {
    val label = JBLabel("$text ›").apply {
        foreground = accentTextFor(focusColor(), UIUtil.getListBackground())
    }
    return JPanel(BorderLayout()).apply {
        isOpaque = false
    // detailBox 是 BoxLayout（Y）：交叉轴上**所有**子件都得是 LEFT。混着 0.5 时，
    // 窄的那个会被对齐到最宽子件的中心 —— 表现为裸标签各居中一次、看着像放歪了
    alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(2, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(label, BorderLayout.WEST)
        val watcher = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        }
        addMouseListener(watcher)
        label.addMouseListener(watcher)
    }
}

/**
 * 一条**记录**（子代理里对不上在跑任务的那些，也就是已经结束的）。压暗的单行。
 *
 * 有描述就用描述（那是人写的任务名），没有退回类型，再没有给 id。
 * 行首那个 `✓` 与上面那些卡上的活点是一对：**点 = 在跑，勾 = 记录**。
 *
 * 压暗是因为这里列的是过去的事 —— 上面那些卡才是"现在"，它们不该抢一样的注意力
 * （同 `SessionCard` 对被别的标签占着的会话的处理）。
 */
private fun recordRow(agent: SubagentInfo, onOpen: (SubagentInfo) -> Unit): JComponent {
    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
    // detailBox 是 BoxLayout（Y）：交叉轴上**所有**子件都得是 LEFT。混着 0.5 时，
    // 窄的那个会被对齐到最宽子件的中心 —— 表现为裸标签各居中一次、看着像放歪了
    alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(3, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = CcoderText.text("transcript.detail.viewTranscript")
    }

    val text = listOfNotNull(agent.agentType, agent.description).joinToString("  ")
    val name = JBLabel("✓  ${text.ifBlank { agent.agentId.take(8) }}").apply {
        foreground = UIUtil.getInactiveTextColor()
    }
    row.add(name, BorderLayout.WEST)

    // 监听器行与文字各挂一份：Swing 的事件不冒泡，只挂行的话**点在名字上没反应**
    // —— 而那正是人会点的那块（2026-09-20 与「看已结束的 N 个」那一行一起修的）
    val watcher = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) = onOpen(agent)
    }
    row.addMouseListener(watcher)
    name.addMouseListener(watcher)
    return row
}

// ---- 在跑的子代理那张卡（2026-09-24）----

/**
 * 最多画几张卡，超了的用"还有 N 个在跑"交代。
 *
 * 4 这个数是**量出来的**（`RunningDetailRenderProbe` 每次跑都会打印）：
 * 2026-09-24 那台机器上 3 张卡 + 那条线 = 267px，4 张卡 + "还有 N 个在跑" = 359px。
 * 浮层是**向上**弹的（挂在面板底部那张卡上，见 `showAboveOrBelow`），
 * 而工具窗口首选高才 600 —— 再往上长就顶出屏幕，那里点不着也看不见。
 *
 * 超了不画那几条**不是瞒着**：状态卡上的数才是权威的那个数（`子代理 6`），
 * 这里少画几张就明说一句"还有 N 个在跑"。
 */
internal const val MAX_AGENT_CARDS = 4

/** 卡与卡之间的缝。卡自己不留缝（不像 `SessionCard` 那样把缝画进组件里）——
 *  这里是 `BoxLayout`，用一根 strut 更直白。 */
private const val AGENT_CARD_GAP = 6

/** 嵌在浮层里的卡，圆角跟浮层一致（8）。`CARD_CORNER_ARC`(16) 是**顶层**卡片的
 *  语言，缩在浮层里再 16 会显得泡泡的。 */
private const val AGENT_CARD_ARC = 8

/**
 * 一个在跑的子代理 = 一张卡。
 *
 * 三段：**谁**（活点 + 类型与任务名，右上角终止）/ **现在在干嘛**（有才画）/
 * **用了多少**（一条分隔线下面那行统计）。布局照 `docs/design/subagent-detail.html`
 * 的方案甲，一处按真机改了：那条分隔线只有统计非空时才画（刚起的任务还没有
 * token 与耗时，一条空分隔线比少一行难看）。
 *
 * ## "两行"那个结构是 2026-09-18 定的（B2，用户从选型台上挑的），没动它
 *
 * 第一行 = 任务名（`task_started.description`，一直有），第二行 = **现在在干嘛**，
 * 没有就不画这行。从前只有一行、用的是 `detail ?: label` —— 有进行时就**顶掉**
 * 任务名，而 `detail` 只从 `task_progress.summary` 来、要 CLI 开
 * `agentProgressSummaries` 才生成（每 ~30s 一句），所以绝大多数时候那一行就是
 * 任务名本身，等于白顶。这次只是把同一个结构装进卡里。
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
    val meta = buildList {
        if (task.tokens > 0) add("${formatTokenCount(task.tokens)} tok")
        if (task.durationMs > 0) add(formatDuration(task.durationMs))
    }.joinToString(" · ")

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

    val head = JPanel(BorderLayout()).apply {
        isOpaque = false
        // **这一句不能少**：detailBox/这一列都是 BoxLayout，交叉轴上所有子件共用
        // 一个对齐基准 —— 混着一个 0.5（面板的默认值）就会把窄的那些（详情行、
        // 统计行）按最宽子件的中心摆，表现为"文字莫名其妙缩进了一截"。
        // 2026-09-24 就是这么栽的：探针把每件的 x 倒出来才看见（详情行 x=125）。
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
    }

    val column = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(head)
        task.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            add(
                JBLabel(detail).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    font = font.deriveFont(font.size2D - 1f)
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
        }
        if (meta.isNotEmpty()) {
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    // 分隔线铺满卡片内宽（正好是"卡片的下半格面"，同 ComposerCard 那条底带的思路）
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, lineColor()),
                        JBUI.Borders.emptyTop(5),
                    )
                    add(
                        JBLabel(meta).apply {
                            foreground = UIUtil.getInactiveTextColor()
                            font = font.deriveFont(font.size2D - 1f)
                        },
                        BorderLayout.WEST,
                    )
                },
            )
        }
    }
    card.add(column, BorderLayout.CENTER)
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
