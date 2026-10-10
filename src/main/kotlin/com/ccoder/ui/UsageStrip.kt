package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.ui.JBColor
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
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/** 常驻条的高度。工具窗口那一栏各处的间距都按 JBUI 缩放，这个数也跟着缩。 */
private val STRIP_HEIGHT = JBUI.scale(28)

/**
 * 「使用量」常驻条（E1，2026-10-10 用户从选型台选的）。
 *
 * 一条 28px 的矮行：左「使用量」，中「今日 123M · ≈$12.4」，右 14 天走势，
 * 点整条开统计框。**自绘** —— 它比 JLabel 多三件事（圆角描边、走势条、悬停
 * 底色），而三个 JLabel 拼的内边距在这个高度里挤不下（同 ChipView 的理由）。
 *
 * 没有数据时**整条不显示**（isVisible=false）：空框比没有更糟；BoxLayout 会
 * 跳过不可见子项，所以它也不占高度（同 queueStrip 那条注释）。
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
            val shape = RoundRectangle2D.Float(
                0f, 0f, (width - 1).toFloat(), (height - 1).toFloat(),
                JBUI.scale(7).toFloat(), JBUI.scale(7).toFloat(),
            )
            g2.color = if (hover) UIUtil.getListSelectionBackground(false) else UIUtil.getTextFieldBackground()
            g2.fill(shape)
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f)
            g2.draw(shape)

            val labelFont = UIUtil.getLabelFont()
            g2.font = labelFont
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2
            var x = JBUI.scale(9)

            g2.color = UIUtil.getInactiveTextColor()
            val title = CcoderText.text("usage.strip.title")
            g2.drawString(title, x, textY)
            x += fm.stringWidth(title) + JBUI.scale(8)

            g2.color = UIUtil.getLabelForeground()
            val value = usageStripText(d)
            g2.drawString(value, x, textY)
            x += fm.stringWidth(value) + JBUI.scale(8)

            // 右端：14 天走势 + ›。地方不够就直接不画走势（窄窗口不硬塞）
            val chev = "›"
            val chevW = fm.stringWidth(chev)
            val chevX = width - JBUI.scale(9) - chevW
            val sparkRight = chevX - JBUI.scale(6)
            val spark = d.spark
            if (spark.isNotEmpty()) {
                val barW = JBUI.scale(3)
                val barGap = JBUI.scale(2)
                val need = spark.size * barW + (spark.size - 1) * barGap
                if (sparkRight - need > x) {
                    val maxV = spark.max().coerceAtLeast(1L)
                    val baseY = height / 2f + JBUI.scale(8)
                    val accentText = accentTextFor(UIUtil.getTreeSelectionBackground(true), UIUtil.getTextFieldBackground())
                    var bx = sparkRight - need
                    spark.forEachIndexed { i, v ->
                        val bh = (v.toDouble() / maxV * JBUI.scale(15)).toFloat().coerceAtLeast(1.5f)
                        g2.color = if (i == spark.size - 1) {
                            accentText
                        } else {
                            Color(accentText.red, accentText.green, accentText.blue, 140)
                        }
                        g2.fill(Rectangle2D.Float(bx.toFloat(), baseY - bh, barW.toFloat(), bh))
                        bx += barW + barGap
                    }
                }
            }
            g2.color = UIUtil.getInactiveTextColor()
            g2.drawString(chev, chevX, textY)
        } finally {
            g2.dispose()
        }
    }
}

/** 常驻条中间那串字："今日 123M · ≈$12.4"（没有成本数据时省掉后半截）。 */
internal fun usageStripText(d: UsageStripData): String {
    val today = CcoderText.text("usage.strip.today", formatTokenCount(d.todayTokens))
    val cost = d.todayCost
    return if (cost == null) today
    else "$today · " + CcoderText.text("usage.strip.cost", "$" + formatUsageCost(cost))
}
