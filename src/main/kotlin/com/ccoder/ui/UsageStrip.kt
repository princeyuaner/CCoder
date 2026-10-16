package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/** 常驻条的高度。工具窗口那一栏各处的间距都按 JBUI 缩放，这个数也跟着缩。 */
private val STRIP_HEIGHT = JBUI.scale(28)

/**
 * 「使用量」常驻条（丙「额度条」，2026-10-14 用户从选型台
 * `web/tools/usage-strip-view.mjs` 的四个排法里挑的；E1 那版是 2026-10-10 选的）。
 *
 * 一条 28px 的矮行，从左到右：
 *
 * - **路牌**：「使用量」退成 inactive 色，后接一根 1×14 的发丝竖线 ——
 *   行首是一块牌子，不再是灰字串里的三个字。**字号与数据段同号**：第一版把它
 *   缩了一号，2026-10-14 用户报"字变形了"（见下"字号一档别动"）；
 * - **数据段**：`今日 274.4M · ≈$185.4`，**词暗、数亮**（词和数从哪里劈、词后要不要
 *   空一格，全由词表格式决定，见 [appendWord]）—— 整行里只有数字要"扫"；
 * - **额度三轨**（有 opencode 额度时）：`5h ▬▬ 11% 周 ▬▬ 11% 月 ▬▬ 5%`。轨道**可伸**
 *   —— 吃掉剩余宽度（窗口越宽条越长），已用比例直接画出来；颜色沿用统计框那一支
 *   （[quotaBarColor]：≥70 琥珀、≥90 红）。**三级降级**：轨道放不下（会窄过 24）
 *   退成文字，文字也放不下整段不画 —— 就是老规矩"先丢走势、再丢额度段"的丙版；
 * - **实扣 / 余额**（dmx / deepseek 账）：接在数据段尾（同旧样）；这三家账互斥，
 *   正常只会命中一家（见 [UsageStripData] 各字段的注释）；
 * - **走势**：只在**没有轨道**时画（有轨道时信息够了，不重复）—— 14 根 4px 实条、
 *   最低 2px、脚下一条 1px 基线。旧版是 3px 条、最低 1.5px 的点阵，用户 2026-10-14
 *   报"远看就是一排点"（选型台里量的：条宽 739、文字只到 x≈330、走势孤悬在 650）；
 * - **›**：收进一枚 16px 小圆（描边 = 卡片那支 [lineColor]）—— "整条可点"终于有个
 *   可点的样子，而不是一枚裸字符。
 *
 * 点整条仍开统计框；悬停仍是整条换列表选中色。
 *
 * ## 字号一档别动（2026-10-14 用户报"字变形了，是不是该把高度设高一点"）
 *
 * 不是高度的事：28px 装 `labelFont` 从 E1 那版起就一直是舒服的。变形的是丙
 * 第一版自己压小的那三处（路牌 / 轨道标签 5h 周 月 / 百分比，全都 `labelFont - 1`）
 * —— 小一号的 CJK 在 Windows 上笔画糊成一团。**这套界面里没有"更小的字"这一档**；
 * 要层级用颜色（词暗数亮）和位置，丙 本来就是这么做的。
 *
 * ## 底 / 边 / 弧与上下两张卡同一套（2026-10-13 用户报「样式和其他的不一样，
 * 保持统一，四个角的弧度也是一样」）—— 丙 只重排"里面"，这三样一个字没动：
 *
 * - **弧**取 [CARD_CORNER_ARC] —— 与两张邻居卡**共用一个数**。第一版写死 7，
 *   那个数抄自选型台的 `border-radius: 7px`，但 `RoundRectangle2D` 的 arc 是
 *   **直径**不是半径（[SessionChips] 里记过同一个坑）：画出来半径只有 3.5，
 *   比上下两张卡（半径 8）方一倍，一排看过去就它一个尖角。16 = 直径，半径 8。
 * - **边**取 [lineColor] —— 同 [StatusCardView] 的 [RoundedLineBorder]、同
 *   [ComposerCard] 的常态描边。第一版用的 `JBColor.border()` 在本机主题里落在
 *   另一种灰上，左右两条边和工具窗口底色一个色，整条的边比邻居淡一档。
 * - **底**取 [UIUtil.getPanelBackground] —— 同 [StatusCardView]。
 *
 * 悬停换成列表选中色**不在此列**：那是"整条可点"的反馈，与卡片悬停时描边
 * 变强调色是两种语言，各留各的。
 */
internal class UsageStrip(private val onOpen: () -> Unit) : JComponent() {

    private var data: UsageStripData? = null
    private var hover = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = CcoderText.text("usage.strip.tooltip")
        isVisible = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) onOpen()
            }
        })
    }

    /** 收到一份数据（请求回升或 usageTick 推送）。第一次收到之前整条不可见。 */
    fun submit(d: UsageStripData) {
        data = d
        if (!isVisible) isVisible = true
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(200), STRIP_HEIGHT)

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(80), STRIP_HEIGHT)

    /** 铺满整行 —— BoxLayout 里靠 max 宽度拿满宽（同 queueStrip）。 */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, STRIP_HEIGHT)

    override fun paintComponent(g: Graphics) {
        val d = data ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(CARD_CORNER_ARC).toFloat()
            val shape = RoundRectangle2D.Float(
                0f, 0f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc,
            )
            g2.color = if (hover) UIUtil.getListSelectionBackground(false) else UIUtil.getPanelBackground()
            g2.fill(shape)
            g2.color = lineColor()
            g2.stroke = BasicStroke(1f)
            g2.draw(shape)

            val labelFont = UIUtil.getLabelFont()
            val fmLabel = g2.getFontMetrics(labelFont)
            val baseline = (height + fmLabel.ascent - fmLabel.descent) / 2
            val dim = UIUtil.getInactiveTextColor()
            val fg = UIUtil.getLabelForeground()

            // 右端的围栏：小圆 + 让白。内容不许盖进它
            val circleD = JBUI.scale(16)
            val circleX = width - JBUI.scale(9) - circleD
            val contentRight = circleX - JBUI.scale(8)

            var x = JBUI.scale(9)

            // 路牌：使用量 + 发丝竖线（字号与数据段**同号** —— 见类注释"字号一档别动"）
            g2.font = labelFont
            g2.color = dim
            val title = CcoderText.text("usage.strip.title")
            g2.drawString(title, x, baseline)
            x += fmLabel.stringWidth(title) + JBUI.scale(9)
            g2.color = lineColor()
            val railH = JBUI.scale(14)
            g2.fill(
                Rectangle2D.Float(
                    x.toFloat(), ((height - railH) / 2).toFloat(),
                    JBUI.scale(1).toFloat(), railH.toFloat(),
                )
            )
            x += JBUI.scale(10) // 1px 线 + 9px 空隙

            val content = stripContent(d)

            // 数据段：词暗、数亮
            x = drawPieces(g2, content.before, x, baseline, labelFont, limit = null)

            // 额度：轨道 → 文字 → 不画（三级；见类注释）
            var bars = false
            content.quota?.let { q ->
                val end = drawQuotaBars(g2, q, x, contentRight, baseline, fmLabel, labelFont)
                if (end != null) {
                    x = end
                    bars = true
                } else {
                    x = drawPieces(g2, quotaTextPieces(q), x, baseline, labelFont, limit = contentRight)
                }
            }

            // 额度之后还有的内容（余额；正常与额度互斥 —— 留着是为了"谁在画谁"不破顺序）
            x = drawPieces(g2, content.after, x, baseline, labelFont, limit = contentRight)

            // 走势：没有轨道时画（放不下照旧不画 —— 老规矩）
            if (!bars) drawSpark(g2, d.spark, x, circleX)

            // ›：小圆
            val circleY = (height - circleD) / 2
            g2.color = lineColor()
            g2.stroke = BasicStroke(1f)
            g2.draw(
                Ellipse2D.Float(
                    circleX + 0.5f, circleY + 0.5f,
                    (circleD - 1).toFloat(), (circleD - 1).toFloat(),
                )
            )
            g2.font = labelFont
            g2.color = dim
            val glyph = "›"
            val glyphW = fmLabel.stringWidth(glyph)
            val circleMid = circleY + circleD / 2
            g2.drawString(
                glyph,
                circleX + (circleD - glyphW) / 2 + JBUI.scale(1), // 1px：› 的字形偏左，同选型台
                circleMid + (fmLabel.ascent - fmLabel.descent) / 2,
            )
        } finally {
            g2.dispose()
        }
    }

    /**
     * 画一串 piece。返回画完之后的 x。
     *
     * [limit] 非空时先合总量：整串放不下就**不画**（返回原 x）—— 半截字比没有更糟。
     * 数据段自己传 null（它是核心内容，宁挤不丢；同旧版对整串字的处理）。
     */
    private fun drawPieces(
        g2: Graphics2D,
        pieces: List<StripPiece>,
        x0: Int,
        baseline: Int,
        font: java.awt.Font,
        limit: Int?,
    ): Int {
        if (pieces.isEmpty()) return x0
        val fm = g2.getFontMetrics(font)
        val total = pieces.sumOf { JBUI.scale(it.gapBefore) + fm.stringWidth(it.text) }
        if (limit != null && x0 + total > limit) return x0
        var x = x0
        g2.font = font
        for (p in pieces) {
            x += JBUI.scale(p.gapBefore)
            g2.color = if (p.dim) UIUtil.getInactiveTextColor() else UIUtil.getLabelForeground()
            g2.drawString(p.text, x, baseline)
            x += fm.stringWidth(p.text)
        }
        return x
    }

    /**
     * 三根可伸的轨道（丙 的主角）。返回画完后的 x；放不下返回 null，交给文字退路。
     *
     * 三根轨道**等长**（`(可用宽 - 各组固定宽) / 档数`）—— 条等长，三档才可比；
     * 选型台那版是"单元等宽"（轨道随标签长短微差），比着看反而浑。
     */
    private fun drawQuotaBars(
        g2: Graphics2D,
        q: OpencodeQuota,
        x0: Int,
        right: Int,
        baseline: Int,
        fm: java.awt.FontMetrics,
        font: java.awt.Font,
    ): Int? {
        if (q.windows.isEmpty()) return null
        val lead = JBUI.scale(14) // 与数据段之间的组间（比文字退路那 12 宽一点，同选型台）
        val gap = JBUI.scale(16) // 轨道组之间（没有点，距离就是分隔）
        var fixed = 0
        q.windows.forEach { w ->
            fixed += fm.stringWidth(quotaShortLabel(w.kind)) +
                JBUI.scale(10) + // 标签-轨道-百分比两侧各 5
                fm.stringWidth(formatQuotaPercent(w.percent))
        }
        val avail = right - x0 - lead
        val trackW = (avail - fixed - gap * (q.windows.size - 1)) / q.windows.size
        if (trackW < JBUI.scale(24)) return null

        val trackH = JBUI.scale(6)
        val trackY = (height - trackH) / 2
        // 轨道底 = 前景 10% 压面板上（同选型台的 --track）
        val trackBg = blend(UIUtil.getLabelForeground(), UIUtil.getPanelBackground(), 0.10)
        // 填色走统计框那支强调**文字**色（选中底色当条太淡，浅色下与轨道糊成一片）
        val accent = accentTextFor(UIUtil.getTreeSelectionBackground(true), UIUtil.getPanelBackground())

        var x = x0 + lead
        g2.font = font
        q.windows.forEachIndexed { i, w ->
            val label = quotaShortLabel(w.kind)
            val pct = formatQuotaPercent(w.percent)
            g2.color = UIUtil.getInactiveTextColor()
            g2.drawString(label, x, baseline)
            x += fm.stringWidth(label) + JBUI.scale(5)

            g2.color = trackBg
            g2.fill(
                RoundRectangle2D.Float(
                    x.toFloat(), trackY.toFloat(), trackW.toFloat(), trackH.toFloat(),
                    trackH.toFloat(), trackH.toFloat(),
                )
            )
            val fillW = Math.round(trackW * (w.percent / 100.0).coerceIn(0.0, 1.0)).toInt()
            if (fillW > 0) {
                // 弧随填宽收：5% 的小截配 6px 的弧会画成一颗怪逗号
                val fillArc = minOf(trackH.toFloat(), fillW.toFloat())
                g2.color = quotaBarColor(w.percent, accent)
                g2.fill(
                    RoundRectangle2D.Float(
                        x.toFloat(), trackY.toFloat(), fillW.toFloat(), trackH.toFloat(),
                        fillArc, fillArc,
                    )
                )
            }
            x += trackW + JBUI.scale(5)
            g2.color = UIUtil.getInactiveTextColor()
            g2.drawString(pct, x, baseline)
            x += fm.stringWidth(pct)
            if (i < q.windows.size - 1) x += gap
        }
        return x
    }

    /**
     * 14 天走势（实条 + 基线，2026-10-14 换掉 3px 点阵）。放不下就不画 —— 老规矩。
     *
     * 基线那根 1px 线（[lineColor] 的 65%）是选型台里试出来的：没有它，矮条浮在
     * 半空像脏点；有它，一排柱子才"站在地上"。
     */
    private fun drawSpark(g2: Graphics2D, spark: List<Long>, x: Int, circleX: Int) {
        if (spark.isEmpty()) return
        val barW = JBUI.scale(4)
        val barGap = JBUI.scale(2)
        val need = spark.size * barW + (spark.size - 1) * barGap
        val sparkRight = circleX - JBUI.scale(8)
        if (sparkRight - need <= x) return
        val maxV = spark.max().coerceAtLeast(1L)
        val baseY = height / 2 + JBUI.scale(8)
        val left = sparkRight - need

        val line = lineColor()
        g2.color = Color(line.red, line.green, line.blue, 166)
        g2.fill(Rectangle2D.Float(left.toFloat(), baseY.toFloat(), need.toFloat(), 1f))

        // 对比度按条的底色算 —— 条自己填什么，这里就传什么
        val accent = accentTextFor(
            UIUtil.getTreeSelectionBackground(true),
            UIUtil.getPanelBackground(),
        )
        var bx = left
        spark.forEachIndexed { i, v ->
            val bh = Math.round(v.toDouble() / maxV * JBUI.scale(15)).toInt().coerceAtLeast(JBUI.scale(2))
            g2.color = if (i == spark.size - 1) {
                accent
            } else {
                Color(accent.red, accent.green, accent.blue, 140)
            }
            g2.fill(Rectangle2D.Float(bx.toFloat(), (baseY - bh).toFloat(), barW.toFloat(), bh.toFloat()))
            bx += barW + barGap
        }
    }
}

/**
 * 一截内容。[dim] = 这一截是"词"（暗）还是"数"（亮）；[gapBefore] = 与前一截的
 * 间距（1x 像素：词数之间 3、点两侧各 6、组间 12 —— 绘制时按 JBUI 缩放）。
 */
internal data class StripPiece(val text: String, val dim: Boolean, val gapBefore: Int)

/**
 * 常驻条的内容，按绘制顺序分两段再单列额度：[before]（今日 / ≈ / 实扣）、[quota]
 * （有则画轨道、画不下退文字）、[after]（余额）。三家账互斥（见 [UsageStripData]），
 * 正常只会命中一家；分三段是为了"谁在画谁"不破顺序。
 */
internal data class StripContent(
    val before: List<StripPiece>,
    val quota: OpencodeQuota?,
    val after: List<StripPiece>,
)

/** 词与数的劈分哨兵：拿它当参数格式化，再在它处劈开。 */
private const val WORD_SENTINEL = '\u0000'

/**
 * 追加"词 + 数"两截。词的文本与**词后要不要空一格**都从词表格式来
 *（`今日 {0}` 有空格、`≈{0}` 没有；en 的 `charged {0}` 有、zh 的 `实扣{0}` 没有
 *  —— 两种语言各按各的）—— 不在代码里另立一套"哪些词后面要空"。
 * 词表里那个空格的宽度统一按 3px 落笔。
 */
private fun appendWord(parts: MutableList<StripPiece>, key: String, value: String, gapBefore: Int) {
    val raw = CcoderText.text(key, WORD_SENTINEL)
    val at = raw.indexOf(WORD_SENTINEL)
    val word = if (at < 0) raw else raw.substring(0, at) // 没占位符：整串当词（防呆）
    val spaced = word.endsWith(" ")
    parts += StripPiece(word.trimEnd(), dim = true, gapBefore = gapBefore)
    parts += StripPiece(value, dim = false, gapBefore = if (spaced) 3 else 0)
}

/** 中点：两侧各 6 —— 都退成"词"色（暗），分隔符不该抢数字。 */
private fun dotPiece() = StripPiece("·", dim = true, gapBefore = 6)

/**
 * 条的内容：今日、估算、实扣接在 [StripContent.before]；余额在 [StripContent.after]；
 * 额度单独给（它可能画成轨道）。估算 / 实扣 / 额度 / 余额各自缺席时省掉自己那截
 *（没成本 = 今天没会话；没账单/额度/余额 = 非对应供应商或没查到）。
 */
internal fun stripContent(d: UsageStripData): StripContent {
    val before = mutableListOf<StripPiece>()
    appendWord(before, "usage.strip.today", formatTokenCount(d.todayTokens), gapBefore = 0)
    d.todayCost?.let {
        before += dotPiece()
        appendWord(before, "usage.strip.cost", "$" + formatUsageCost(it), gapBefore = 6)
    }
    d.todayBillYuan?.let {
        before += dotPiece()
        appendWord(before, "usage.strip.bill", formatBillYuan(it), gapBefore = 6)
    }
    val after = mutableListOf<StripPiece>()
    d.deepseekBalance?.let {
        after += dotPiece()
        appendWord(after, "usage.strip.balance", formatBalanceAmount(it.currency, it.total), gapBefore = 6)
    }
    return StripContent(before, d.opencodeQuota, after)
}

/**
 * 额度的文字排法（轨道放不下时的退路）：「5h 12% · 周 4% · 月 2%」。前导 12px 是
 * 与数据段之间的组间（不点数 —— 组已经换了，点反而连成一条）；档与档之间用点。
 * 缺档跳过（接口没给的那档不编）。重置时间不上条 —— 条上只要"离满多远"，钟点留给统计框。
 */
internal fun quotaTextPieces(q: OpencodeQuota): List<StripPiece> {
    val out = mutableListOf<StripPiece>()
    q.windows.forEachIndexed { i, w ->
        if (i > 0) out += dotPiece()
        out += StripPiece(quotaShortLabel(w.kind), dim = true, gapBefore = if (i == 0) 12 else 6)
        out += StripPiece(formatQuotaPercent(w.percent), dim = false, gapBefore = 3)
    }
    return out
}
