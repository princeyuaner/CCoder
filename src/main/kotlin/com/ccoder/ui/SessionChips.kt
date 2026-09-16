package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 一条会话此刻处在什么状态 —— 就是胶囊上那个小圆点的颜色。
 *
 * 这是多标签最需要被看见的东西：**另一条会话在等我，而我在这一条里**。
 * 平台那条原生标签条表达不了它（那是选 B 的主要理由，见
 * `docs/design/session-tabs.html`）。
 */
internal enum class TabState { Running, WaitingPermission, Idle }

/**
 * 状态的判据。**忙优先于等待**：正在跑的回合里也可能挂着权限询问，那时说"在跑"
 * 更贴切（它确实在动），而"等你"会让人以为卡住了。
 *
 * 抽成纯函数是为了能在纯 JVM 单测里钉住这张真值表 —— 画点的那一半在
 * [SessionChips] 里（要 Swing，测不了）。
 */
internal fun tabStateOf(pendingPermissions: Int, busy: Boolean, starting: Boolean): TabState = when {
    busy || starting -> TabState.Running
    pendingPermissions > 0 -> TabState.WaitingPermission
    else -> TabState.Idle
}

/** 一个胶囊要显示的全部信息。 */
internal data class TabChip(
    val owner: JComponent,
    /** 会话标题；null = 还没起名（画成斜体「新会话」）。 */
    val title: String?,
    val state: TabState,
    /** 是不是当前这一条。 */
    val current: Boolean,
    /** 能不能关（只剩一条时不能 —— 平台不会重建面板，见 [SessionTabs]）。 */
    val canClose: Boolean,
)

/**
 * 胶囊的几何。这几个数在这里定，别处不许再写。
 *
 * ## 高度就是**可点区域**的高度，它改过一次
 *
 * 2026-09-16 用户报「这一行的高度拉高一点，现在太矮了点击不方便」—— 原来 22。
 * 这颗胶囊整块都是可点的（点它 = 切过去 / 开会话列表），所以它多高，
 * 可点的地方就有多高；而这一行**只有**这颗胶囊可点，那圈内边距是点不着的。
 *
 * 28 是照着平台自己的工具窗口标签条取的（New UI 那一行差不多就是这个高），
 * 落在这个数上不是随手写的：整行随之从 30 长到 36（`buildTopRow` 上下各 4px
 * 内边距），转写区少 6px —— 换来的是可点高度 **+27%**。
 *
 * 改这个数会让整行一起长，**别在别处再补竖直内边距**：白只有 `buildTopRow`
 * 那一处出处（见 [buildTopRow] 里"白只有两处出处"那段）。观感看
 * `SessionChipsRenderProbe` / `TopRowRenderProbe` 出的图。
 */
internal val CHIP_HEIGHT = JBUI.scale(28)
internal val CHIP_GAP = JBUI.scale(6)
private val CHIP_PAD_H = JBUI.scale(8)

/**
 * 圆角**直径 = 高度** —— 这才是设计稿里那颗胶囊（`docs/design/session-tabs.html:108`
 * 的 `border-radius: 999px`）。两端各是一个半圆，半径正好是高度的一半。
 *
 * ## 这里踩过一个坑：arc 参数是直径，不是半径
 *
 * `RoundRectangle2D` 的 arc 参数是**圆角的直径**，所以
 * `arc = HEIGHT / 2` 得到的是**半径 h/4** —— 一个圆角矩形，不是胶囊。
 * 这个错从第一版就在（22px 配 11，半径 5.5），22px 时看着还不明显；
 * 2026-09-16 拉到 28px 之后半径只有 7，用户一眼看出「不像胶囊，圆角太尖」。
 *
 * 写 `HEIGHT` 而不是 `HEIGHT / 2` 不是笔误（下面那条用例钉着）。
 */
internal val CHIP_ARC = CHIP_HEIGHT
private val DOT_SIDE = JBUI.scale(8)
private val CLOSE_SIDE = JBUI.scale(12)

/** 胶囊宽度被夹在这两个数之间：太窄认不出，太宽挤掉邻居。 */
internal val CHIP_MIN_WIDTH = JBUI.scale(56)
internal val CHIP_MAX_WIDTH = JBUI.scale(140)

/**
 * 每个胶囊分到多宽 —— 纯算术，可单测。
 *
 * 为什么"按份分"而不是"谁长谁宽"：宽度不均时，短标题那颗会比长的矮一截感
 * （`BoxLayout` 里高度一致、宽度参差，看着像没对齐）。等宽之后只有标题在截断，
 * 那一行是齐的。
 */
internal fun chipWidthFor(available: Int, count: Int, gap: Int = CHIP_GAP): Int {
    if (count <= 0) return CHIP_MIN_WIDTH
    val usable = available - gap * (count - 1)
    return (usable / count).coerceIn(CHIP_MIN_WIDTH, CHIP_MAX_WIDTH)
}

/**
 * 标题按像素截断，尾巴给省略号。
 *
 * 与 [rowTextFor]（补全弹层）同一套做法：**量真实字体**而不是数数字符 ——
 * 中文、ASCII、`[1m]` 这种后缀的宽度差得多，按字符数截会在长英文标题上露出半个词。
 *
 * @param unnamed 还没起名的会话显示什么（与面板里那个斜体占位同一个词）
 */
internal fun chipTitleFor(
    title: String?,
    metrics: FontMetrics,
    maxWidth: Int,
    unnamed: String = "新会话",
): String {
    val text = title?.takeIf { it.isNotBlank() } ?: return unnamed
    if (metrics.stringWidth(text) <= maxWidth) return text
    val ellipsis = "…"
    val budget = maxWidth - metrics.stringWidth(ellipsis)
    if (budget <= 0) return ellipsis
    var end = text.length
    while (end > 0 && metrics.stringWidth(text.substring(0, end)) > budget) end--
    return if (end <= 0) ellipsis else text.substring(0, end) + ellipsis
}

/**
 * 会话胶囊行 —— 顶行的**开头**那一格（原来是个单会话标签），「＋」紧跟在它后面
 * （位置由 [buildTopRow] 决定）。
 *
 * ## 为什么自己画
 *
 * 平台那条标签条只能开关、改不动样式，而且**表达不了状态**（见 [TabState]）。
 * 四个方案比对与选择在设计稿 `docs/design/session-tabs.html`，用户 2026-09-16 选 B。
 * 平台标签条随之关掉（`canCloseContents` 撤回 false），关闭逻辑一个字没变 ——
 * 只是从平台的 ✕ 挪到我们自己的 ✕ 上。
 *
 * ## 交互
 *
 * - 点**别的**胶囊 → 切到那条会话
 * - 点**当前**胶囊 → 开历史会话列表（与原会话标签同一个入口，行为不变）
 * - 悬停时右端出现 ✕（当前那颗**常驻**）；点了交给 [SessionTabs] 的关闭闸 ——
 *   忙时会先问一句，这里不管
 */
internal class SessionChips(
    private val onPick: (TabChip) -> Unit,
    private val onClose: (TabChip) -> Unit,
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }

    /** 上一次渲染用的模型 —— 宽度变了要原样重画一遍。 */
    private var chips: List<TabChip> = emptyList()

    /** 已排版的宽度。0 = 还没上过屏，用兜底值分宽。 */
    private var laidOutWidth = 0

    fun render(chips: List<TabChip>) {
        this.chips = chips
        removeAll()
        val width = chipWidthFor(laidOutWidth.takeIf { it > 0 } ?: DEFAULT_ROW_WIDTH, chips.size)
        chips.forEachIndexed { index, chip ->
            if (index > 0) add(Box.createHorizontalStrut(CHIP_GAP))
            add(ChipView(chip, width, onPick, onClose))
        }
        revalidate()
        repaint()
    }

    override fun doLayout() {
        // 宽度变了要重分：多一个胶囊、面板被拖窄、工具窗口被拖宽都走这儿
        if (width > 0 && width != laidOutWidth) {
            laidOutWidth = width
            render(chips)
        }
        super.doLayout()
    }

    /**
     * 这一排要占多宽 —— **只由胶囊个数算出来**，不看自己被排了多宽。
     *
     * 这是「＋」能紧跟最后一个胶囊的关键（2026-09-16）：`buildTopRow` 把这一排、
     * 「＋」与弹簧放进同一个横向 `BoxLayout`，BoxLayout 按**首选宽**分配、富余
     * 全归弹簧 —— 所以"这一排多宽"**必须在排版之前就答得出来**。
     *
     * 交给 BoxLayout 默认那一套（= 各子项首选宽之和）不行：子项的首选宽是上一次
     * `render` 按**当时**的宽度定的，与"这一排多宽"互为因果 —— 窗口拖宽之后
     * 它会一直沿用上一次的宽度，胶囊不跟着长（而 `doLayout` 又会因为宽度没变
     * 而不再重算，正好卡住）。两边都只看个数就没有这个循环。
     */
    override fun getPreferredSize(): Dimension = Dimension(rowWidth(CHIP_MAX_WIDTH), CHIP_HEIGHT)

    /** 最小 = 每颗都挤到下限。再窄就压到这儿为止 —— 规矩仍是"先挤标签，不动按钮"。 */
    override fun getMinimumSize(): Dimension = Dimension(rowWidth(CHIP_MIN_WIDTH), CHIP_HEIGHT)

    /** 最大 = 首选：每颗都到上限了，再长就是胶囊右边多出一截点不着的白。 */
    override fun getMaximumSize(): Dimension = preferredSize

    /** 一排 n 颗、每颗 [perChip] 宽时的总宽（含之间的间距）。 */
    private fun rowWidth(perChip: Int): Int =
        if (chips.isEmpty()) 0 else chips.size * perChip + CHIP_GAP * (chips.size - 1)

    private companion object {
        /**
         * 还没上屏时的兜底：第一次 `render` 排在 `doLayout` 之前，那时 `width` 还是 0。
         * 它只影响"第一次render 时每颗分多宽"，而 `doLayout` 一上屏就会按真实宽度
         * 重算一遍，所以这个数不必准，取个"420 的工具窗口减掉按钮那一段"即可。
         */
        val DEFAULT_ROW_WIDTH = JBUI.scale(340)
    }
}

/**
 * 一个胶囊。自绘 —— 因为它比 `JLabel` 多三件事：底与边随选中/悬停变、左端那颗
 * 状态点与文字不同色、右端 ✕ 只在该出现的时候出现。用三个 `JLabel` 拼也能做，
 * 但那一行只有 [CHIP_HEIGHT] 这么高，三个盒子各自的内边距挤不下。
 */
private class ChipView(
    private val chip: TabChip,
    private val chipWidth: Int,
    private val onPick: (TabChip) -> Unit,
    private val onClose: (TabChip) -> Unit,
) : JComponent() {

    private var hover = false
    private var overClose = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = buildString {
            append(chip.title?.takeIf { it.isNotBlank() } ?: NEW_TAB_TITLE)
            append(
                when (chip.state) {
                    TabState.Running -> " · 正在跑"
                    TabState.WaitingPermission -> " · 等你批准"
                    TabState.Idle -> " · 空闲"
                }
            )
            append(if (chip.current) "（当前）" else " —— 点一下切过去")
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                overClose = false
                repaint()
            }

            override fun mouseMoved(e: MouseEvent) {
                val was = overClose
                overClose = showsClose() && e.x >= closeLeft()
                if (was != overClose) repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (showsClose() && e.x >= closeLeft()) onClose(chip) else onPick(chip)
            }
        })
    }

    override fun getPreferredSize(): Dimension = Dimension(chipWidth, CHIP_HEIGHT)

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    /** 当前那颗常驻 ✕，其余的悬停才出现。 */
    private fun showsClose(): Boolean = chip.canClose && (chip.current || hover)

    /** ✕ 那一段的左边界 —— 点哪儿算点了 ✕，就是它说了算。 */
    private fun closeLeft(): Int = width - CHIP_PAD_H - CLOSE_SIDE

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 底与边：当前那颗用强调色的浅底 + 强调色边，其余中性
            val accent = chipAccent()
            g2.color = when {
                chip.current -> accentTint(accent)
                hover -> UIUtil.getListSelectionBackground(false)
                else -> UIUtil.getPanelBackground()
            }
            g2.fill(roundRect(0f, 0f, width.toFloat(), height.toFloat()))
            g2.color = if (chip.current) accent else JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            g2.draw(roundRect(0.5f, 0.5f, width - 1f, height - 1f))

            // 状态点
            g2.color = when (chip.state) {
                TabState.Running -> accent
                TabState.WaitingPermission -> CHIP_WARN
                TabState.Idle -> UIUtil.getInactiveTextColor()
            }
            g2.fill(
                Ellipse2D.Float(
                    CHIP_PAD_H.toFloat(),
                    ((height - DOT_SIDE) / 2f) + 0f,
                    DOT_SIDE.toFloat(),
                    DOT_SIDE.toFloat(),
                )
            )

            // 标题
            g2.font = UIUtil.getLabelFont().deriveFont(if (chip.title.isNullOrBlank()) Font.ITALIC else Font.PLAIN)
            val metrics = g2.fontMetrics
            val textLeft = CHIP_PAD_H + DOT_SIDE + JBUI.scale(6)
            val textRight = if (showsClose()) closeLeft() - JBUI.scale(4) else width - CHIP_PAD_H
            g2.color = if (chip.current) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
            g2.drawString(
                chipTitleFor(chip.title, metrics, textRight - textLeft),
                textLeft,
                (height + metrics.ascent - metrics.descent) / 2,
            )

            // ✕
            if (showsClose()) {
                g2.color = if (overClose) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
                g2.stroke = BasicStroke(JBUI.scale(1.2f).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val cx = closeLeft() + CLOSE_SIDE / 2f
                val cy = height / 2f
                val arm = CLOSE_SIDE / 2f - JBUI.scale(2f)
                g2.draw(Line2D.Float(cx - arm, cy - arm, cx + arm, cy + arm))
                g2.draw(Line2D.Float(cx - arm, cy + arm, cx + arm, cy + arm - 2 * arm))
            }
        } finally {
            g2.dispose()
        }
    }

    private fun roundRect(x: Float, y: Float, w: Float, h: Float) =
        RoundRectangle2D.Float(x, y, w, h, CHIP_ARC.toFloat(), CHIP_ARC.toFloat())

    private companion object {
        val CHIP_WARN: Color = warningColor()
    }
}

/**
 * 强调色。**只在这一处取** —— 别处不许再写死一个蓝色（这一版换主题全靠它）。
 */
private fun chipAccent(): Color = JBUI.CurrentTheme.Focus.focusColor()

/** 当前胶囊的浅底：强调色往面板底上压一档。纯强调色是描边的活，铺底会太吵。 */
private fun accentTint(accent: Color): Color = mix(accent, UIUtil.getPanelBackground(), 0.82)
