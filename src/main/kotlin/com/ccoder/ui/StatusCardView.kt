package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 描边圆角半径。 */
private const val CARD_ARC = 9

/**
 * 一张状态卡。
 *
 * ## 收边为什么用"透明描边"而不是"不设 border"
 *
 * 四张卡等宽并排。若收边时换掉 border 对象，`getBorderInsets` 会从 1 变成 0，
 * 这一格就宽出去 2px —— 旁边三张跟着挪。用全透明色画同一条边，insets 恒定，
 * 布局一动不动。
 *
 * ## 为什么用 JPanel 而不是像旧的胶囊那样自画
 *
 * 旧的 `RunStripView` 自画是因为它的文字要按可用宽度截断。卡里放的是
 * `3/7`、`2` 这种定宽短值，不需要截断，用 JLabel 反而能免费拿到平台的
 * 字体、高 DPI 与主题色。
 *
 * 描边复用 [RoundedLineBorder]：它本来就收一个 `colorProvider` 函数，
 * 换色只需 repaint，不必重建 border。
 *
 * @param onOpen null 表示这张卡不可点（连接卡、上下文卡）。**不给空 lambda** ——
 *   那会画出悬停反馈却点不动，是在骗人。
 */
internal class StatusCardView(
    private val onOpen: (() -> Unit)? = null,
) : JPanel() {

    private var model: StatusCardModel? = null
    private var open = false
    private var hovered = false

    private val labelView = JBLabel()
    private val valueView = JBLabel()
    private val subView = JBLabel()
    private val indicatorView = IndicatorView()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(colorProvider = ::strokeColor, arc = JBUI.scale(CARD_ARC)),
            JBUI.Borders.empty(6, 9, 7, 9),
        )

        labelView.font = labelView.font.deriveFont(labelView.font.size2D - 2f)
        labelView.foreground = UIUtil.getInactiveTextColor()
        valueView.font = valueView.font.deriveFont(valueView.font.size2D + 2f)
        subView.font = subView.font.deriveFont(subView.font.size2D - 2f)
        subView.foreground = UIUtil.getInactiveTextColor()

        add(labelView)
        add(valueView)
        add(subView)
        add(indicatorView)

        if (onOpen != null) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onOpen.invoke()

                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        repaint()
                    }
                }
            )
        }
    }

    /** 不叫 update：那会与 [java.awt.Component.update] 撞名。 */
    fun setModel(next: StatusCardModel) {
        model = next
        labelView.text = next.label
        valueView.text = next.value
        valueView.foreground =
            if (next.quiet) UIUtil.getInactiveTextColor() else UIUtil.getLabelForeground()

        subView.text = next.sub.orEmpty()
        subView.isVisible = !next.sub.isNullOrEmpty()

        indicatorView.set(next.indicator, next.tone)
        indicatorView.isVisible = next.indicator != Indicator.None

        revalidate()
        repaint()
    }

    fun setOpen(value: Boolean) {
        if (open == value) return
        open = value
        repaint()
    }

    fun isOpen(): Boolean = open

    /**
     * 描边色。**收边时给全透明** —— 不是去掉 border，那样 insets 会变。
     */
    private fun strokeColor(): Color = when {
        model?.quiet == true -> Color(0, 0, 0, 0)
        open || hovered -> focusColor()
        else -> lineColor()
    }
}

/**
 * 卡片底部那条微指示。
 *
 * 自画而不是拼组件：它有三种形状（条 / 分段 / 点），每种都只有几行绘制代码，
 * 拼出来反而要维护三套子组件。
 */
internal class IndicatorView : JComponent() {

    private var indicator: Indicator = Indicator.None
    private var tone: Tone = Tone.Idle

    init {
        isOpaque = false
        // 显式设字体。不设的话 getFont() 在无父组件时返回 null，
        // 高度计算会 NPE —— 旧 RunStripView 上踩过同一个坑
        font = UIUtil.getLabelFont()
    }

    fun set(next: Indicator, nextTone: Tone) {
        indicator = next
        tone = nextTone
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension = when (indicator) {
        Indicator.None -> Dimension(0, 0)
        is Indicator.Meter, is Indicator.Segments -> Dimension(JBUI.scale(60), JBUI.scale(4))
        is Indicator.Dots -> Dimension(JBUI.scale(60), JBUI.scale(6))
    }

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            when (val i = indicator) {
                Indicator.None -> Unit
                is Indicator.Meter -> paintMeter(g2, i)
                is Indicator.Segments -> paintSegments(g2, i)
                is Indicator.Dots -> paintDots(g2, i)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun paintMeter(g2: Graphics2D, meter: Indicator.Meter) {
        val h = height
        g2.color = trackColor()
        g2.fillRoundRect(0, 0, width, h, h, h)

        val filled = (width * meter.fraction).toInt().coerceIn(0, width)
        if (filled <= 0) return
        g2.color = alertColor() ?: focusColor()
        g2.fillRoundRect(0, 0, filled, h, h, h)
    }

    private fun paintSegments(g2: Graphics2D, segments: Indicator.Segments) {
        if (segments.total <= 0) return
        val gap = JBUI.scale(2)
        // 均匀分：总宽减去所有间隙，再等分
        val segW = ((width - gap * (segments.total - 1)).toDouble() / segments.total).toInt()
        if (segW <= 0) return

        var x = 0
        for (index in 0 until segments.total) {
            g2.color = when {
                index < segments.done -> okColor()
                index == segments.done -> focusColor()
                else -> trackColor()
            }
            g2.fillRoundRect(x, 0, segW, height, JBUI.scale(1), JBUI.scale(1))
            x += segW + gap
        }
    }

    private fun paintDots(g2: Graphics2D, dots: Indicator.Dots) {
        val size = JBUI.scale(5)
        val gap = JBUI.scale(3)
        val y = (height - size) / 2
        g2.color = alertColor() ?: focusColor()
        for (index in 0 until dots.count) {
            val x = index * (size + gap)
            if (x + size > width) break
            g2.fillOval(x, y, size, size)
        }
    }

    /** 只有警示色调才覆盖指示器颜色。其余一律强调色 —— 见 [Tone] 的说明。 */
    private fun alertColor(): Color? = when (tone) {
        Tone.Warn -> warningColor()
        Tone.Danger -> dangerColor()
        else -> null
    }

    private fun trackColor(): Color = JBColor.namedColor(
        "Component.borderColor",
        JBColor(Color(0x33, 0x36, 0x3B), Color(0xE0, 0xE2, 0xE7)),
    )

    private fun okColor(): Color = JBColor.namedColor(
        "Component.successColor",
        JBColor(Color(0x3D, 0x8B, 0x43), Color(0x5F, 0xAD, 0x65)),
    )
}
