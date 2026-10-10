package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import javax.swing.JComponent

/**
 * 堆叠柱状图（V1 仪表盘的主图，2026-10-10 用户从选型台选的）。
 *
 * **自绘** —— 同 StackBar / 状态卡那套取舍：百来行换来描边、圆角、抗锯齿全在
 * 手上。一根柱 = 一个 [UsageBucket]，段 = 模型（顺序与配色都由调用方给，
 * 与图例、分解表同一份 —— 三处必须一致，否则颜色会串）。
 *
 * 柱高按整个序列的最大值归一 —— 读的是"形状"，不是绝对值；绝对数在砖和表里。
 */
internal class UsageBars : JComponent() {

    var buckets: List<UsageBucket> = emptyList()

    /** 模型 → 颜色（[usageModelColors] 算好传进来，这层不碰主题）。 */
    var colors: Map<String, Color> = emptyMap()

    /** 每几根柱标一个日期（**从右边数** —— 最右是今天，标签从今天往回铺）。0 = 不标。 */
    var labelEvery: Int = 5

    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(592), JBUI.scale(126))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (buckets.isEmpty() || width <= 0) return

            val labelH = if (labelEvery > 0) JBUI.scale(14) else 0
            val chartH = (height - labelH).coerceAtLeast(1)
            val n = buckets.size
            val gap = JBUI.scale(4)
            val colW = ((width - gap * (n - 1)).toFloat() / n).coerceAtLeast(1f)
            val maxTotal = buckets.maxOf { it.tokens }.coerceAtLeast(1L)
            val dim = UIUtil.getInactiveTextColor()

            // 基线：柱子的落点。用边框色 —— 换主题自动跟着走
            g2.color = JBColor.border()
            g2.drawLine(0, chartH - 1, width, chartH - 1)

            val labelFont = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 3f)

            buckets.forEachIndexed { i, b ->
                val x = i * (colW + gap)
                if (b.tokens > 0) {
                    val h = (b.tokens.toDouble() / maxTotal * (chartH - JBUI.scale(6)))
                        .toFloat().coerceAtLeast(2f)
                    var y = (chartH - 1).toFloat()
                    for ((model, t) in b.models) {
                        val segH = (h * (t.total().toDouble() / b.tokens)).toFloat()
                        if (segH <= 0f) continue
                        y -= segH
                        g2.color = colors[model] ?: dim
                        g2.fill(Rectangle2D.Float(x, y, colW, segH))
                    }
                }
                if (labelEvery > 0 && (n - 1 - i) % labelEvery == 0) {
                    g2.font = labelFont
                    g2.color = dim
                    val text = b.label
                    val tw = g2.fontMetrics.stringWidth(text)
                    val tx = (x + colW / 2 - tw / 2f).toInt().coerceIn(0, (width - tw).coerceAtLeast(0))
                    g2.drawString(text, tx, chartH + g2.fontMetrics.ascent + JBUI.scale(2))
                }
            }
        } finally {
            g2.dispose()
        }
    }
}
