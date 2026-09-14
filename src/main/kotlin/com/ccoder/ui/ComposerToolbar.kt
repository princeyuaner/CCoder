package com.ccoder.ui

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
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * 工具栏上的发送键。
 *
 * 自绘而不用 `JButton`：原生按钮是方的、带 LookAndFeel 的渐变与边框，
 * 放在这张卡片里，**整个输入区就它最不像是设计过的**。改 LookAndFeel 的
 * 代价是影响全局，所以只画这一个。
 *
 * 它只负责**画**，该显示什么状态由 [mainButtonState] 那个纯函数决定 ——
 * 那边已经有测试钉着"忙的时候是停止、断开的时候是重启"这些规则。
 */
internal class RoundSendButton : JComponent() {

    var onClick: (() -> Unit)? = null

    var action: MainAction = MainAction.Send
        private set

    private var hovered = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val d = JBUI.scale(DIAMETER)
        preferredSize = Dimension(d, d)
        minimumSize = preferredSize
        maximumSize = preferredSize
        addMouseListener(
            object : MouseAdapter() {
                // 普通 JComponent 不像 AbstractButton 那样自己吞掉禁用后的点击，
                // 必须显式拦住 —— 否则"启动中…"时点一下会走一遍发送逻辑
                override fun mouseClicked(e: MouseEvent) {
                    if (isEnabled) onClick?.invoke()
                }

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

    fun setState(next: MainButton) {
        action = next.action
        isEnabled = next.enabled
        toolTipText = next.text
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val d = minOf(width, height)
            val inset = JBUI.scale(1)
            val box = d - inset * 2 - 1
            val solid = isEnabled && action != MainAction.Disabled

            if (solid) {
                g2.color = if (hovered) darken(focusColor()) else focusColor()
                g2.fillOval(inset, inset, box, box)
            } else {
                g2.color = lineColor()
                g2.stroke = BasicStroke(1f)
                g2.drawOval(inset, inset, box, box)
            }

            g2.color = if (solid) Color.WHITE else UIUtil.getInactiveTextColor()
            val c = d / 2
            val r = JBUI.scale(4)
            when (action) {
                MainAction.Interrupt -> g2.fillRoundRect(
                    c - r, c - r, r * 2, r * 2, JBUI.scale(2), JBUI.scale(2),
                )

                // 重启：一个实心三角。不用文字字形（↻ 之类）—— 那取决于系统字体装没装
                MainAction.Restart -> g2.fillPolygon(
                    intArrayOf(c - r, c - r, c + r),
                    intArrayOf(c - r, c + r, c),
                    3,
                )

                else -> drawUpArrow(g2, cx = c, cy = c, r = r)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun drawUpArrow(g2: Graphics2D, cx: Int, cy: Int, r: Int) {
        g2.stroke = BasicStroke(
            JBUI.scale(2f).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
        )
        g2.drawLine(cx, cy + r, cx, cy - r)
        g2.drawLine(cx, cy - r, cx - r, cy)
        g2.drawLine(cx, cy - r, cx + r, cy)
    }

    internal companion object {
        /** 直径（未缩放）。取 29 而不是整数 30：奇数直径的圆在奇数像素下更好看。 */
        const val DIAMETER = 29
    }
}

/**
 * 控件工具栏：左边状态（模型、权限模式、思考深度），右边发送键。
 *
 * 左侧刻意留宽 —— 以后要加的模型切换等控件都往这儿添，不必再动结构。
 */
internal fun buildComposerToolbar(
    model: JComponent,
    mode: JComponent,
    effort: JComponent,
    send: JComponent,
): JPanel =
    JPanel(BorderLayout()).apply {
        // 不透明会把卡片底色盖住，工具栏就成了卡片里嵌的另一块
        isOpaque = false
        border = JBUI.Borders.emptyTop(2)
        add(buildStatusRow(model, mode, effort), BorderLayout.WEST)
        add(send, BorderLayout.EAST)
    }

/**
 * 工具栏左侧的状态区。
 *
 * 这几个都是"现在是什么"的显示，不是按钮 —— 所以用安静的次要文字，
 * 让发送键保持唯一的视觉重点。（权限模式切到绕过时会自己跳成警示色，
 * 那是它应得的例外。）
 */
internal fun buildStatusRow(
    model: JComponent,
    mode: JComponent,
    effort: JComponent,
): JPanel =
    JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
        isOpaque = false
        add(model)
        add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)))
        add(mode)
        add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)))
        // 新的往最右添，不动既有两个的位置 —— 加个控件就让前面两个
        // 各挪一格，用户会以为整个工具栏换了套布局
        add(effort)
    }

/** 悬停时压暗一档。没有做完整的调色板，够用即可。 */
private fun darken(c: Color): Color = Color(
    (c.red * 0.88).toInt().coerceIn(0, 255),
    (c.green * 0.88).toInt().coerceIn(0, 255),
    (c.blue * 0.88).toInt().coerceIn(0, 255),
)
