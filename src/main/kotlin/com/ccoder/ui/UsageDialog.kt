package com.ccoder.ui

import com.ccoder.sidecar.UsageDay
import com.ccoder.sidecar.UsageModelTokens
import com.ccoder.text.CcoderText
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.time.LocalDate
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 使用量统计对话框（V1 仪表盘，2026-10-10 用户从选型台选的）。
 *
 * 形状照选型台的 V1：620 宽、三块砖（今日/本周/本月）、分段切粒度、堆叠柱、
 * 按模型分解表、底下一列口径脚注。与 [ContextDetailDialog] 同源：DialogWrapper
 * + 自绘内容 + [复制 CSV] / [关闭]。
 *
 * 数据是"快照 + 回调"：打开时把面板手里已有的 rows 摆上（瞬间可见），再让
 * 面板去要最新的一份；换口径（本项目/全部项目）走 [onScopeChange] 同一条路。
 * **对话框不碰 sidecar** —— 那是 [ClaudePanel] 的事（同 requestContextUsage 的取舍）。
 */
internal class UsageDialog(
    project: Project?,
    initialScope: String,
    initialRows: List<UsageDay>,
    initialBill: UsageBill? = null,
    private val onScopeChange: (String) -> Unit,
    private val onRefreshBill: () -> Unit = {},
    private val onClosed: () -> Unit = {},
) : DialogWrapper(project) {

    /** 当前口径（"project"/"all"）。切换即时生效，数据到了再重画。 */
    private var scope: String = initialScope
    private var rows: List<UsageDay> = initialRows

    /** 最近一次账单（方案 A 的对账条）。null = 非 dmx / 还没查到 —— 那条整条不出现。 */
    private var bill: UsageBill? = initialBill
    private var gran: UsageGran = UsageGran.Day

    private val holder = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = UIUtil.getPanelBackground() // 会被 rebuild 换成统计框自己的纸色
    }

    /** 统计框自己的纸（正文底/砖底/描边）—— 见 [usagePaletteFor]。 */
    private fun dialogPalette(): UsagePalette = usagePaletteFor(UIUtil.getPanelBackground())

    private var closeNotified = false

    private val copyAction = object : DialogWrapper.DialogWrapperAction(CcoderText.text("usage.copy")) {
        override fun doAction(e: ActionEvent) {
            // 复制的是**当前口径的全部行**（不是屏幕上那 30 天）—— 它是给对账用的
            copyToClipboard(usageCsvOf(rows))
        }
    }

    private val closeAction = object : DialogWrapper.DialogWrapperAction(CcoderText.text("common.close")) {
        override fun doAction(e: ActionEvent) {
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = CcoderText.text("usage.title")
        isResizable = true
        // **先摆内容、再 init()**：平台在 init 里按当时那个 preferred size 定窗口大小
        //（与 ContextDetailDialog 的 contentPanel 同一个顺序坑）
        rebuild()
        init()
        // 这时窗口才建出来、holder 也挂上了：平台铺的那些底跟着换纸
        recolorWindow()
    }

    /** 面板拿到新数据后送进来。[scope] 是响应里回显的口径。 */
    fun updateRows(scope: String, rows: List<UsageDay>) {
        this.scope = scope
        this.rows = rows
        rebuild()
    }

    /** 面板拿到新账单（成败都走）后送进来；null = 清掉（非 dmx / 换了配置）。 */
    fun updateBill(bill: UsageBill?) {
        this.bill = bill
        rebuild()
    }

    override fun createCenterPanel(): JComponent = holder

    override fun createActions(): Array<Action> = arrayOf(copyAction, closeAction)

    /**
     * 按钮条与正文同一张纸。平台的南方条自己带着主题面板色的底 —— 正文换纸后，
     * 底部会在灰纸下面露出一道近黑的条（第一个会看见的就是它）。
     */
    override fun createSouthPanel(): JComponent? {
        val south = super.createSouthPanel() ?: return null
        val bg = dialogPalette().bg
        return if (south is JPanel) {
            south.isOpaque = true
            south.background = bg
            south
        } else {
            JPanel(BorderLayout()).apply {
                isOpaque = true
                background = bg
                add(south, BorderLayout.CENTER)
            }
        }
    }

    /**
     * 窗口里平台铺的那些底也换纸：从 [holder] 一路上溯到窗口，凡 Swing 组件都刷成
     * 同一张纸 —— 框缘那一圈露出来的就是它们（Islands 系主题里是近黑）。
     */
    private fun recolorWindow() {
        val bg = dialogPalette().bg
        var c: Component? = holder.parent
        while (c != null && c !is Window) {
            (c as? JComponent)?.background = bg
            c = c.parent
        }
    }

    override fun dispose() {
        if (!closeNotified) {
            closeNotified = true
            onClosed()
        }
        super.dispose()
    }

    private fun rebuild() {
        // 纸色每次都重算（跟得上主题）；窗口那一圈的平台底交给 recolorWindow
        holder.background = dialogPalette().bg
        holder.removeAll()
        holder.add(
            buildUsagePanel(
                rows = rows,
                scope = scope,
                gran = gran,
                today = LocalDate.now(),
                onPickGran = { g -> gran = g; rebuild() },
                onPickScope = { s ->
                    scope = s
                    rebuild() // chip 先动（数据还是旧的），新数据到了 updateRows 再画一遍
                    onScopeChange(s)
                },
                bill = bill,
                onRefreshBill = onRefreshBill,
            ),
            BorderLayout.CENTER,
        )
        holder.revalidate()
        holder.repaint()
        // 内容高度会变（模型行数、空态）—— 窗口跟着重算。show 之前 window 还没
        // 建出来，那时不用管（init()/show() 自己按 preferred 来）
        (window as? Dialog)?.takeIf { it.isShowing }?.pack()
        recolorWindow()
    }
}

// ------------------------------------------------------------------ 正文
//
// 拆成 top-level 纯摆放（单测直接调 buildUsagePanel —— 同 buildContextDetailPanel），
// 顺带让渲染探针够得着。

/** 正文宽度。比现有弹窗（CARD_WIDTH=420）宽一档 —— 图表要地方（选型台里定下的）。 */
internal val USAGE_DIALOG_WIDTH = JBUI.scale(620)

/**
 * 统计框的正文。[today] 可注入 —— 画的是"近 30 天"这种相对窗口，探针得钉死。
 */
internal fun buildUsagePanel(
    rows: List<UsageDay>,
    scope: String,
    gran: UsageGran,
    today: LocalDate,
    onPickGran: (UsageGran) -> Unit,
    onPickScope: (String) -> Unit,
    /** 方案 A 的账单；null = 非 dmx / 没查到 —— 对账条整条不出现（"退回今天的样子"）。 */
    bill: UsageBill? = null,
    onRefreshBill: () -> Unit = {},
): JComponent {
    val palette = usagePaletteFor(UIUtil.getPanelBackground())
    val bg = palette.bg
    val dark = palette.dark
    val accentText = accentTextFor(UIUtil.getTreeSelectionBackground(true), UIUtil.getTextFieldBackground())

    val box = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = bg
        border = JBUI.Borders.empty(12, 14, 10, 14)
    }

    // ---- 工具行：粒度分段 + 口径 chip ----
    val granItems = listOf(
        UsageGran.Day to "usage.gran.day",
        UsageGran.Week to "usage.gran.week",
        UsageGran.Month to "usage.gran.month",
    )
    val toolbar = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }
    toolbar.add(
        SegBar(
            items = granItems.map { CcoderText.text(it.second) },
            selected = granItems.indexOfFirst { it.first == gran }.coerceAtLeast(0),
            base = bg,
            line = palette.border,
            onPick = { i -> onPickGran(granItems[i].first) },
        )
    )
    toolbar.add(Box.createHorizontalGlue())
    toolbar.add(scopeChip(scope, onPickScope))
    box.add(toolbar.stretchWidth())
    box.add(Box.createVerticalStrut(JBUI.scale(10)))

    if (rows.isEmpty()) {
        // 一条数据都没有：照实说，不画一张全零的图（那会被读成"你什么都没用"）
        box.add(hint(CcoderText.text("usage.empty")).stretchWidth())
        box.add(Box.createVerticalStrut(JBUI.scale(8)))
        box.add(footNote(null).stretchWidth())
        box.preferredSize = Dimension(USAGE_DIALOG_WIDTH, box.preferredSize.height)
        return box
    }

    // ---- 三块砖 ----
    val summary = usageSummary(rows, today)
    val tiles = JPanel(GridLayout(1, 3, JBUI.scale(10), 0)).apply { isOpaque = false }
    tiles.add(
        usageTile(
            CcoderText.text("usage.tile.today"), summary.today,
            tileSub(summary.today, cachePctOf(summary.today)?.let { CcoderText.text("usage.tile.cache", it) }),
            palette,
        )
    )
    tiles.add(
        usageTile(
            CcoderText.text("usage.tile.week"), summary.week,
            tileSub(
                summary.week,
                usageDeltaText(summary.weekDelta)?.let { CcoderText.text("usage.tile.vsLastWeek", it) }
                    ?: cachePctOf(summary.week)?.let { CcoderText.text("usage.tile.cache", it) },
            ),
            palette,
        )
    )
    tiles.add(
        usageTile(
            CcoderText.text("usage.tile.month"), summary.month,
            tileSub(summary.month, cachePctOf(summary.month)?.let { CcoderText.text("usage.tile.cache", it) }),
            palette,
        )
    )
    box.add(tiles.stretchWidth())
    box.add(Box.createVerticalStrut(JBUI.scale(10)))

    // ---- 账单对账条（方案 A）：估算之下并排一行「本令牌」的实扣 ----
    if (bill != null) {
        box.add(billPanel(bill, onRefreshBill, palette).stretchWidth())
        box.add(Box.createVerticalStrut(JBUI.scale(10)))
    }

    // ---- 主序列（图 + 图例 + 表共用同一份顺序与配色）----
    val buckets = usageBuckets(rows, gran, today)
    val modelNames = usageModelsOrdered(rows)
    val colors = usageModelColors(modelNames, accentText, dark)

    val rangeKey = when (gran) {
        UsageGran.Day -> "usage.range.day"
        UsageGran.Week -> "usage.range.week"
        UsageGran.Month -> "usage.range.month"
    }
    val totalTokens = buckets.sumOf { it.tokens }
    val totalCost = buckets.sumOf { it.costUsd }
    box.add(
        captionRow(
            CcoderText.text(rangeKey),
            CcoderText.text(
                "usage.caption.total",
                formatTokenCount(totalTokens),
                "$" + formatUsageCost(totalCost),
            ),
        ).stretchWidth()
    )
    box.add(Box.createVerticalStrut(JBUI.scale(4)))

    box.add(
        UsageBars().apply {
            this.buckets = buckets
            this.colors = colors
            labelEvery = when (gran) {
                UsageGran.Day -> 5
                UsageGran.Week -> 2
                UsageGran.Month -> 2
            }
        }.stretchWidth()
    )
    box.add(Box.createVerticalStrut(JBUI.scale(6)))

    if (modelNames.isNotEmpty()) {
        val perModel = usageTotalsByModel(buckets)
        box.add(legendRow(modelNames, colors, perModel).stretchWidth())
        box.add(Box.createVerticalStrut(JBUI.scale(8)))
        box.add(modelTable(modelNames, perModel, colors, palette).stretchWidth())
    }

    box.add(Box.createVerticalStrut(JBUI.scale(8)))
    box.add(footNote(bill).stretchWidth())

    box.preferredSize = Dimension(USAGE_DIALOG_WIDTH, box.preferredSize.height)
    return box
}

// ------------------------------------------------------------------ 小件

/** 弹层里的宽度：所有直接吃满整行的子件都要这一句（BoxLayout Y 里默认按首选宽对齐）。 */
private fun JComponent.stretchWidth(): JComponent = apply {
    alignmentX = Component.LEFT_ALIGNMENT
    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private fun labelFont() = UIUtil.getLabelFont()

/** 数字用的等宽字体。探针环境拿不到编辑器 scheme 时退回系统等宽 —— 不因为一个字体整页画不出来。 */
private fun monoFont(): Font = runCatching {
    EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
}.getOrDefault(Font(Font.MONOSPACED, Font.PLAIN, labelFont().size))

private fun dimText(text: String, font: Font = labelFont().deriveFont(labelFont().size2D - 1f)): JLabel =
    JLabel(text).apply {
        foreground = UIUtil.getInactiveTextColor()
        this.font = font
        alignmentX = Component.LEFT_ALIGNMENT
    }

/**
 * 口径脚注。有账单时换成「实扣为 X 令牌账」那版 —— 两个数并排出现的地方，
 * 口径就必须写在离数字最近的地方（不是只在选型台上写清）。
 */
private fun footNote(bill: UsageBill?): JComponent =
    dimText(
        if (bill == null) CcoderText.text("usage.foot")
        else CcoderText.text("usage.foot.bill", bill.provider),
        labelFont().deriveFont(labelFont().size2D - 2f),
    )

/**
 * 账单对账条（方案 A）。两行：钱那行 + 口径那行；右上角「更新 hh:mm ↻」。
 * 虚线强调框 —— 就是选型台 A 卡里那条（用户挑中的样子）。
 */
private fun billPanel(bill: UsageBill, onRefresh: () -> Unit, palette: UsagePalette): JComponent {
    val accent = UIUtil.getTreeSelectionBackground(true)
    val box = DashPanel(mix(palette.bg, accent, 0.09), accent).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(5, 10, 6, 10)
    }

    val line1 = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }
    val balanceText = bill.balanceYuan
        ?.let { formatBillYuan(it) }
        ?: CcoderText.text("usage.bill.unlimited")
    line1.add(
        JLabel(CcoderText.text("usage.bill.today", formatBillYuan(bill.yuanToday))).apply { font = labelFont() }
    )
    line1.add(dimText(" · "))
    line1.add(JLabel(CcoderText.text("usage.bill.balance", balanceText)).apply { font = labelFont() })
    line1.add(Box.createHorizontalGlue())
    line1.add(dimText(CcoderText.text("usage.bill.updated", formatBillTime(bill.fetchedAt))))
    line1.add(Box.createHorizontalStrut(JBUI.scale(6)))
    line1.add(refreshLabel(onRefresh))
    box.add(line1.stretchWidth())

    val tokens = CcoderText.text(
        "usage.bill.tokens",
        formatTokenCount(bill.promptTokens + bill.completionTokens),
        formatTokenCount(bill.promptTokens),
        formatTokenCount(bill.completionTokens),
    )
    box.add(
        dimText(
            CcoderText.text("usage.bill.source", bill.provider) + " · " + tokens,
            labelFont().deriveFont(labelFont().size2D - 2f),
        ).stretchWidth()
    )
    return box
}

/** 对账条的底：强调色淡填充 + 虚线圆角描边。纸仍是统计框自己的（[UsagePalette]）。 */
private class DashPanel(private val fill: Color, private val line: Color) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(7).toFloat()
            val shape =
                RoundRectangle2D.Float(0.75f, 0.75f, (width - 2).toFloat(), (height - 2).toFloat(), arc, arc)
            g2.color = fill
            g2.fill(shape)
            g2.color = line
            g2.stroke =
                BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(4f, 3f), 0f)
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
    }
}

/** 条上的 ↻：点一下强制重查。**对话框自己不碰网络**（同全框的取舍）—— 走面板的回调。 */
private fun refreshLabel(onRefresh: () -> Unit): JComponent = JLabel("↻").apply {
    toolTipText = CcoderText.text("usage.bill.refresh")
    foreground = UIUtil.getInactiveTextColor()
    font = labelFont()
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (e.button == MouseEvent.BUTTON1) onRefresh()
        }
    })
}

private fun tileSub(b: UsageBucket, extra: String?): String {
    val cost = CcoderText.text("usage.strip.cost", "$" + formatUsageCost(b.costUsd))
    return if (extra == null) cost else "$cost · $extra"
}

/** 缓存读占这段总量的百分比。总量为 0 时 null（不造 "0%" 这种没信息量的尾巴）。 */
private fun cachePctOf(b: UsageBucket): String? {
    if (b.tokens <= 0) return null
    val cache = b.models.sumOf { it.second.cacheRead }
    return "${Math.round(cache * 100.0 / b.tokens)}%"
}

/**
 * 砖底：圆角**填充**自己画。直角填底 + 圆角描边会在四个角露出小尖 ——
 * 边框类只拿它的描边与内边距（同 UsageStrip 的自绘路数）。
 */
private class UsageTilePanel(private val fill: Color) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8).toFloat()
            g2.color = fill
            g2.fill(RoundRectangle2D.Float(0f, 0f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc))
        } finally {
            g2.dispose()
        }
    }
}

private fun usageTile(title: String, bucket: UsageBucket, sub: String, palette: UsagePalette): JComponent {
    val box = UsageTilePanel(palette.surface).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder({ palette.border }, JBUI.scale(8)),
            JBUI.Borders.empty(7, 10, 8, 10),
        )
    }
    box.add(dimText(title))
    box.add(
        JLabel(formatTokenCount(bucket.tokens)).apply {
            font = labelFont().deriveFont(labelFont().size2D + 5f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    )
    box.add(dimText(sub))
    return box
}

private fun captionRow(left: String, right: String): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    add(dimText(left), BorderLayout.WEST)
    add(dimText(right), BorderLayout.EAST)
}

private fun dot(c: Color): JComponent = object : JComponent() {
    init {
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(8), JBUI.scale(8))
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = c
            g2.fill(Ellipse2D.Float(0f, 0f, width.toFloat(), height.toFloat()))
        } finally {
            g2.dispose()
        }
    }
}

private fun legendRow(
    models: List<String>,
    colors: Map<String, Color>,
    totals: Map<String, UsageModelTokens>,
): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    isOpaque = false
    models.forEachIndexed { i, m ->
        if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(14)))
        add(dot(colors[m] ?: Color.GRAY))
        add(Box.createHorizontalStrut(JBUI.scale(5)))
        add(dimText("$m ${formatTokenCount(totals[m]?.total() ?: 0L)}"))
    }
    add(Box.createHorizontalGlue())
}

private fun modelTable(
    models: List<String>,
    totals: Map<String, UsageModelTokens>,
    colors: Map<String, Color>,
    palette: UsagePalette,
): JComponent {
    val col = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    // 占比条的底轨：纸色混描边 —— 与砖、分段控件同一条「自己的纸」逻辑
    val track = mix(palette.bg, palette.border, 0.45)
    col.add(
        tableRow(
            name = dimText(CcoderText.text("usage.col.model")),
            share = null,
            numbers = listOf(
                CcoderText.text("usage.col.share") to SHARE_W,
                CcoderText.text("usage.col.tokens") to TOKENS_W,
                CcoderText.text("usage.col.cacheRead") to CACHE_W,
                CcoderText.text("usage.col.cost") to COST_W,
            ),
            dim = true,
            track = track,
        )
    )
    val grand = totals.values.sumOf { it.total() }.coerceAtLeast(1L)
    for (m in models) {
        val t = totals[m] ?: continue
        col.add(
            tableRow(
                name = JLabel(m).apply {
                    font = labelFont()
                    alignmentX = Component.LEFT_ALIGNMENT
                },
                share = (colors[m] ?: Color.GRAY) to (t.total().toDouble() / grand),
                numbers = listOf(
                    "${Math.round(t.total() * 100.0 / grand)}%" to SHARE_W,
                    formatTokenCount(t.total()) to TOKENS_W,
                    formatTokenCount(t.cacheRead) to CACHE_W,
                    "$" + formatUsageCost(t.costUsd) to COST_W,
                ),
                dim = false,
                track = track,
            )
        )
    }
    return col
}

private const val NAME_W = 170
private const val SHARE_W = 46
private const val TOKENS_W = 80
private const val CACHE_W = 80
private const val COST_W = 66

/** 一行：[name(=170px 含色点) | share 条 | 四个右对齐数字列]。表头与数据行**同一构造**。 */
private fun tableRow(
    name: JComponent,
    share: Pair<Color, Double>?,
    numbers: List<Pair<String, Int>>,
    dim: Boolean,
    /** 占比条的底轨（表头行用不上，同一构造一起过）。 */
    track: Color,
): JComponent {
    val west = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        val preferredWidth = JBUI.scale(NAME_W)
        if (share != null) {
            add(dot(share.first))
            add(Box.createHorizontalStrut(JBUI.scale(6)))
        }
        add(name)
        add(Box.createHorizontalGlue())
        preferredSize = Dimension(preferredWidth, JBUI.scale(18))
        minimumSize = preferredSize
        maximumSize = preferredSize
    }
    val east = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        for ((text, w) in numbers) {
            add(
                JLabel(text, SwingConstants.RIGHT).apply {
                    font = if (dim) labelFont().deriveFont(labelFont().size2D - 2f) else monoFont()
                    if (dim) foreground = UIUtil.getInactiveTextColor()
                    val size = Dimension(JBUI.scale(w), JBUI.scale(18))
                    preferredSize = size
                    minimumSize = size
                    maximumSize = size
                }
            )
        }
    }
    return JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(3, 0)
        add(west, BorderLayout.WEST)
        if (share != null) add(UsageShareBar(share.first, share.second, track), BorderLayout.CENTER)
        add(east, BorderLayout.EAST)
    }
}

/** 占比条：底轨 + 按比例的一段彩色。高度只有 5px，垂直居中画。 */
private class UsageShareBar(
    private val color: Color,
    private val ratio: Double,
    /** 底轨：纸色与描边混出来的一条淡轨（同选型台 color-mix(border 45%) 的算法）。 */
    private val track: Color,
) : JComponent() {
    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(120), JBUI.scale(18))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val trackH = JBUI.scale(5).toFloat()
            val y = height / 2f - trackH / 2
            val w = width.toFloat()
            g2.color = track
            g2.fill(RoundRectangle2D.Float(0f, y, w, trackH, trackH, trackH))
            if (ratio > 0.0) {
                g2.color = color
                g2.fill(RoundRectangle2D.Float(0f, y, (w * ratio).toFloat().coerceAtLeast(trackH), trackH, trackH, trackH))
            }
        } finally {
            g2.dispose()
        }
    }
}

// ------------------------------------------------------------------ 分段控件 / 口径 chip

/**
 * 三段分段控件（按日/按周/按月）。自绘 —— 三颗 JToggleButton 拼出来，那行的高度、
 * 圆角、选中底色都得跟平台搏斗；这里五十来行换它们全在手上。
 */
private class SegBar(
    private val items: List<String>,
    private val selected: Int,
    /** 选中格的底：accent 按 26% 混在这张纸上 —— 纸是自己的，不能拿主题面板色混。 */
    private val base: Color,
    /** 外框线：纸的描边色（同选型台 .seg 的 border）。 */
    private val line: Color,
    private val onPick: (Int) -> Unit,
) : JComponent() {

    private var hover = -1

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) { hover = -1; repaint() }
            override fun mouseClicked(e: MouseEvent) {
                val i = hit(e)
                if (i in items.indices) onPick(i)
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val i = hit(e)
                if (i != hover) { hover = i; repaint() }
            }
        })
    }

    private fun segFont(): Font = labelFont().deriveFont(labelFont().size2D - 1f)

    private fun hit(e: MouseEvent): Int {
        val fm = getFontMetrics(segFont())
        var x = JBUI.scale(1)
        items.forEachIndexed { i, s ->
            val w = fm.stringWidth(s) + JBUI.scale(22)
            if (e.x < x + w) return i
            x += w
        }
        return -1
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(segFont())
        val w = items.sumOf { fm.stringWidth(it) + JBUI.scale(22) } + JBUI.scale(2)
        return Dimension(w, JBUI.scale(24))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height
            val arc = JBUI.scale(6).toFloat()
            val shape = RoundRectangle2D.Float(0f, 0f, (width - 1).toFloat(), (h - 1).toFloat(), arc, arc)
            g2.color = line
            g2.draw(shape)

            g2.font = segFont()
            val fm = g2.fontMetrics
            var x = JBUI.scale(1)
            items.forEachIndexed { i, s ->
                val w = fm.stringWidth(s) + JBUI.scale(22)
                if (i == selected) {
                    g2.color = mix(base, UIUtil.getTreeSelectionBackground(true), 0.26)
                    g2.fill(Rectangle2D.Float(x.toFloat(), 1f, w.toFloat(), (h - 2).toFloat()))
                } else if (i == hover) {
                    g2.color = UIUtil.getListSelectionBackground(false)
                    g2.fill(Rectangle2D.Float(x.toFloat(), 1f, w.toFloat(), (h - 2).toFloat()))
                }
                g2.color = if (i == selected) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
                g2.drawString(s, x + (w - fm.stringWidth(s)) / 2, (h + fm.ascent - fm.descent) / 2)
                x += w
            }
        } finally {
            g2.dispose()
        }
    }
}

private fun scopeText(scope: String): String =
    CcoderText.text(if (scope == "all") "usage.scope.all" else "usage.scope.project")

/** 口径 chip（"本项目 ▾"）：点开两项的小浮层。 */
private fun scopeChip(scope: String, onPick: (String) -> Unit): JComponent {
    val chip = JLabel("${scopeText(scope)} ▾").apply {
        foreground = UIUtil.getInactiveTextColor()
        font = labelFont().deriveFont(labelFont().size2D - 1f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 6)
    }
    chip.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            var popup: JBPopup? = null
            val column = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(4, 0)
            }
            for (id in listOf("project", "all")) {
                column.add(
                    optionRow(scopeText(id), UIUtil.getLabelForeground(), description = null, selected = id == scope) {
                        onPick(id)
                        popup?.cancel()
                    }
                )
            }
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(column, null)
                .setRequestFocus(false)
                .setResizable(false)
                .setMovable(false)
                .setCancelOnClickOutside(true)
                .createPopup()
            popup.showUnderneathOf(chip)
        }
    })
    return chip
}
