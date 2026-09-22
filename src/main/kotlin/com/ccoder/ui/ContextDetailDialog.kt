package com.ccoder.ui

import com.ccoder.sidecar.ContextDetail
import com.ccoder.text.CcoderText
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 上下文详情那张框（2026-09-22，选型稿 `docs/design/context-details.html` 方案乙）。
 *
 * ## 为什么是框，而不是沿用那张卡的浮层
 *
 * 浮层（`buildTodoDetail` / `buildRunningDetail` 那一套）装不下这次的东西：分类表 +
 * 四张清单，MCP 工具动辄几十条。用户在选型稿里挑的就是"**一屏给全**"这套，
 * 并且**看见了它的代价**（模态打断；三张卡里只它一个用框）。
 *
 * ## 内容的规矩全在 [ContextDetail.kt] 里
 *
 * 这一层**只摆位置**：判类、求和、英文名的折中翻译都是那边的纯函数（用例打那边）。
 * 这里冒出 `when (kind)` 式的判断，就说明那件事放错了层。
 *
 * ## 尺寸
 *
 * 宽 [CARD_WIDTH]（与工具窗口那一栏同宽），高按内容给：分类表与每张清单各自套滚动区、
 * 高度封顶（[LIST_AREA_HEIGHT]）—— 条数再多也不让框长到屏幕外面去。
 */
internal class ContextDetailDialog(
    project: Project?,
    private val usage: ContextUsage,
    private val detail: ContextDetail?,
) : DialogWrapper(project) {

    /**
     * 正文。做成属性、并且声明在 [init] **之前**：平台的 `createCenterPanel` 是
     * `protected`，渲染探针够不着 —— 本仓 `ImagePreviewDialog` / `SettingsDialog`
     * 是同一个写法。而 `init()` 里就会走到它，所以顺序不能反。
     */
    internal val contentPanel: JComponent = buildContextDetailPanel(usage, detail)

    private val copyAction = object : DialogWrapper.DialogWrapperAction(CcoderText.text("context.copy")) {
        override fun doAction(e: ActionEvent) {
            // 明细缺失时复制出来的仍是这一页**看得见**的那几个数（标题行），不是空串
            copyToClipboard(contextDetailMarkdownOf(detail ?: EMPTY_DETAIL, usage))
        }
    }

    /** Esc 走平台的 cancel（`DialogWrapper.show()` 里注册），一样关得掉。 */
    private val closeAction = object : DialogWrapper.DialogWrapperAction(CcoderText.text("common.close")) {
        override fun doAction(e: ActionEvent) {
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = CcoderText.text("context.title")
        isResizable = true
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(copyAction, closeAction)

    override fun createCenterPanel(): JComponent = contentPanel

    private companion object {
        val EMPTY_DETAIL = ContextDetail(
            model = "",
            percentage = null,
            overLimit = null,
            categories = emptyList(),
            mcpTools = emptyList(),
            memoryFiles = emptyList(),
            agents = emptyList(),
            skills = emptyList(),
        )
    }
}

/** 打开详情框。生产路径给 [ClaudePanel] 用；探针直接调它，不吃 project。 */
internal fun showContextDetail(project: Project?, usage: ContextUsage, detail: ContextDetail?) {
    ContextDetailDialog(project, usage, detail).show()
}

/** 清单区的高度上限。条数多时在里面滚，不让框长到屏幕外面去。 */
private const val LIST_AREA_HEIGHT = 300

/**
 * 对话框的正文。
 *
 * 自上而下：总量 · 超窗提示 · 堆叠条 · 页签（分类 + 有内容的清单）· 一句话提示。
 */
internal fun buildContextDetailPanel(usage: ContextUsage, detail: ContextDetail?): JComponent {
    val box = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(12, 14, 10, 14)
    }

    box.add(headerRow(usage, detail))
    contextOverLimitText(detail?.overLimit)?.let { box.add(bannerLine(it)) }

    val window = usage.windowTokens
    val lines = detail?.let { contextLinesOf(it.categories) } ?: emptyList()

    if (window > 0) {
        box.add(Box.createVerticalStrut(JBUI.scale(8)))
        box.add(StackBar(contextBarOf(lines), window))
        box.add(Box.createVerticalStrut(JBUI.scale(10)))
    }

    if (detail == null) {
        // 老版本 sidecar / 那一次没拿到：照实说，不画一张空表
        box.add(hint(CcoderText.text("context.noDetail")))
    } else {
        val tabs = JBTabbedPane()
        tabs.addTab(CcoderText.text("context.tab.categories"), scroll(categoryTab(lines, window)))
        contextListsOf(detail).forEach { list ->
            // 页签上带条数：一眼知道值不值得点进去（「MCP 工具 12」比只有名字信息量大）
            tabs.addTab("${list.title} ${list.rows.size}", scroll(listTab(list)))
        }
        tabs.alignmentX = Component.LEFT_ALIGNMENT
        box.add(tabs)
        box.add(footHint(usage))
    }

    // **加完子件之后再钉宽度**：BoxLayout 的首选宽度是所有子件的最大值，
    // 长 MCP 名会把窗口撑到 420 以外；而高度要等子件齐了才算得出来
    // （先设的话，这里读到的是空盒子的 0）
    box.preferredSize = Dimension(CARD_WIDTH, box.preferredSize.height)
    return box
}

/** 页签里那一块：定宽定高 + 可滚。条数再多也不让框跟着长。 */
private fun scroll(content: JComponent): JComponent = JBScrollPane(content).apply {
    border = JBUI.Borders.empty()
    isOpaque = false
    viewport.isOpaque = false
    content.background = UIUtil.getPanelBackground()
    preferredSize = Dimension(CARD_WIDTH - JBUI.scale(40), JBUI.scale(LIST_AREA_HEIGHT))
    verticalScrollBar.unitIncrement = JBUI.scale(16)
}

/** 抬头：`268k / 1M` + 百分比 + 模型。 */
private fun headerRow(usage: ContextUsage, detail: ContextDetail?): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    // BoxLayout 的交叉轴上所有子件都得 LEFT —— 混着 0.5 时窄的那个会被居中
    alignmentX = Component.LEFT_ALIGNMENT
    add(
        // 与卡面副值同一个函数：窗口未知时它只给已用量，**不编造分母**（"0 / 0"那类）
        JBLabel(contextRatioText(usage)).apply {
            font = font.deriveFont(font.size2D + 5f)
            foreground = UIUtil.getLabelForeground()
        },
        BorderLayout.WEST,
    )
    contextPercentOf(usage)?.let { percent ->
        add(
            JBLabel("$percent%").apply {
                font = font.deriveFont(font.size2D + 3f)
                foreground = toneColor(contextToneOf(percent))
                border = JBUI.Borders.emptyLeft(10)
            },
            BorderLayout.CENTER,
        )
    }
    val model = detail?.model.orEmpty()
    if (model.isNotEmpty()) {
        add(JBLabel(model).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.EAST)
    }
}

/** 超窗那一行：**两种措辞分开**（硬顶会被拒绝，压缩窗会自动压缩）。 */
private fun bannerLine(text: String): JComponent = JBLabel("⚠  $text").apply {
    alignmentX = Component.LEFT_ALIGNMENT
    foreground = dangerColor()
    border = JBUI.Borders.emptyTop(6)
}

/**
 * 分类表。
 *
 * 每行：名字 · token 数 · 一条与**占窗口比例**成正比的细条。
 * `deferred`（窗口外）不画条 —— 它不占窗口，画了就等于说它占。
 */
private fun categoryTab(lines: List<ContextLine>, window: Long): JComponent {
    val box = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(8, 2, 4, 8)
    }
    lines.forEach { line ->
        box.add(
            ContextRowView(
                line = line,
                fraction = when {
                    // 窗口外的那一行**不画条**：它不占窗口，画了就等于说它占
                    // （条的长度是按占窗口比例算的，给它画出来必然是错的）
                    line.kind == ContextKind.Deferred -> null
                    window > 0 -> (line.tokens.toDouble() / window).coerceIn(0.0, 1.0)
                    else -> null
                },
            )
        )
    }
    return box
}

/** 一张清单（MCP 工具 / 记忆文件 / 子代理 / 技能）。 */
private fun listTab(list: ContextList): JComponent {
    val box = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(8, 2, 4, 8)
    }
    list.rows.forEach { row ->
        // 同 ContextRowView：**加完子件再钉最大高度**（先钉就会压成一条缝）
        val line = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(3)
            add(
                JBLabel(row.label).apply {
                    font = font.deriveFont(font.size2D - 1f)
                    foreground = UIUtil.getLabelForeground()
                },
                BorderLayout.CENTER,
            )
            add(
                // 来源（server / 类型）与 token 数同放右端：它们都是"这条是什么"的定语
                JBLabel((if (row.sub.isNotEmpty()) row.sub + " · " else "") + formatTokenCount(row.tokens)).apply {
                    font = font.deriveFont(font.size2D - 1f)
                    foreground = UIUtil.getInactiveTextColor()
                },
                BorderLayout.EAST,
            )
        }
        line.maximumSize = Dimension(Int.MAX_VALUE, line.preferredSize.height)
        box.add(line)
    }
    return box
}

/** 最后那行：窗口未知说窗口，否则按比例说一句"还宽裕 / 该留意了"。 */
private fun footHint(usage: ContextUsage): JComponent = hint(
    when (val percent = contextPercentOf(usage)) {
        null -> CcoderText.text("transcript.detail.noWindowMeasured")
        else -> CcoderText.text(
            when {
                percent >= 90 -> "transcript.detail.hint.over90"
                percent >= 70 -> "transcript.detail.hint.over70"
                else -> "transcript.detail.hint.plenty"
            }
        )
    }
)

/** 与状态卡同一个口径的色调（70 琥珀、90 红）。 */
private fun contextToneOf(percent: Int): Tone = when {
    percent >= 90 -> Tone.Danger
    percent >= 70 -> Tone.Warn
    else -> Tone.Idle
}

/**
 * 一行的条用什么颜色。
 *
 * 内容 = 强调色；压缩预留 = 警示色；**自由空间 = 轨道灰**（与堆叠条里那截同色）——
 * 给它强调色的话，那条最长的"剩余空间"会被读成"用了这么多"。窗口外的行不画条。
 */
private fun rowBarColor(line: ContextLine): Color = when (line.kind) {
    ContextKind.Buffer -> warningColor()
    ContextKind.Free -> trackColor()
    else -> focusColor()
}

/** 一行：名字 + 数 + 一条细条（窗口未知或窗口外的行不画）。 */
private class ContextRowView(private val line: ContextLine, private val fraction: Double?) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyBottom(5)

        // 头部那一行**加完子件再钉最大高度**：`preferredSize` 读的是当前内容，
        // 空面板时它是 0 —— 先钉就等于把这行压成一条缝（渲染探针上量到过）
        val head = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(line.label), BorderLayout.WEST)
            add(
                JBLabel(formatTokenCount(line.tokens)).apply {
                    foreground = UIUtil.getInactiveTextColor()
                },
                BorderLayout.EAST,
            )
        }
        head.maximumSize = Dimension(Int.MAX_VALUE, head.preferredSize.height)
        add(head)
        if (fraction != null) {
            add(MiniBar(fraction, rowBarColor(line)))
        } else if (line.kind == ContextKind.Deferred) {
            // 不占窗口的行给一句实话，免得读的人以为漏画了
            add(
                JBLabel(CcoderText.text("context.deferredHint")).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    font = font.deriveFont(font.size2D - 2f)
                    foreground = UIUtil.getInactiveTextColor()
                }
            )
        }
    }
}

/** 堆叠条：内容各段 + 压缩预留；剩下的轨道就是自由空间。 */
private class StackBar(private val segments: List<ContextLine>, private val window: Long) : JComponent() {

    init {
        val h = JBUI.scale(10)
        preferredSize = Dimension(0, h)
        maximumSize = Dimension(Int.MAX_VALUE, h)
        minimumSize = Dimension(0, h)
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        if (window <= 0 || width <= 0) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height.toFloat()
            val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)
            // 轨道 = 自由空间：条画完剩下的那截就是它，不必单独一个 segment
            g2.color = trackColor()
            g2.fill(shape)
            // 各段**裁到圆角里**再画直角矩形：段与段之间才不会出现小圆头造成的白缝
            g2.clip(shape)
            var x = 0.0
            segments.forEach { line ->
                val w = width * (line.tokens.toDouble() / window).coerceIn(0.0, 1.0)
                g2.color = rowBarColor(line)
                g2.fillRect(x.toInt(), 0, w.toInt().coerceAtLeast(1), height)
                x += w
            }
        } finally {
            g2.dispose()
        }
    }
}

/** 行里那一小截。 */
private class MiniBar(private val fraction: Double, private val color: Color) : JComponent() {

    init {
        val h = JBUI.scale(4)
        preferredSize = Dimension(0, h)
        maximumSize = Dimension(Int.MAX_VALUE, h)
        minimumSize = Dimension(0, h)
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        if (width <= 0 || fraction <= 0.0) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = trackColor()
            g2.fillRoundRect(0, 0, width, height, height, height)
            // 长度按占窗口的比例，但给一个下限：0.1% 的行也该看得出"有这么一截"
            val shown = maxOf(fraction, MIN_BAR).coerceIn(0.0, 1.0)
            g2.color = color
            g2.fillRoundRect(0, 0, (width * shown).toInt().coerceAtLeast(1), height, height, height)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        /** 再小也画这么宽（占整条的比例）—— 否则"有一条"与"没有"看不出区别。 */
        const val MIN_BAR = 0.012
    }
}

/** 轨道色：压在背景上的那层灰。明暗两套主题各给一个（透明叠加，跟着主题走）。 */
private fun trackColor(): Color = JBColor(Color(0, 0, 0, 22), Color(255, 255, 255, 26))
