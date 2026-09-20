package com.ccoder.settings

import com.ccoder.ui.focusColor
import com.ccoder.ui.mix
import com.ccoder.ui.pickReadable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 左栏导航：八枚图标 + 一枚**实心强调色胶囊**（2026-09-20，方案 B）。
 *
 * ## 为什么图标是自绘的
 *
 * 全仓零文件图标 IO：状态卡那四枚（`CardIconView`）与顶栏那两枚（`TopRowIcon`）
 * 都是"16×16 模子 + `g2.scale(side/16)` + 描边"自己画的，理由记在
 * `CardIconView` 上（平台 `AllIcons` 里没有对应的语义、画风也会混）。这里照同一个
 * 模子画：**颜色取标签的前景色**，于是选中（白字压蓝底）与未选中（灰字）都自动跟着走，
 * 不需要 `_dark` 双份资源，也不需要 `IconLoader`。
 *
 * ## 为什么点击监听器挂在 `JBLabel` 上
 *
 * 用例与渲染探针都是"按文本找到那个标签、直接喊它身上的监听器"
 * （`SettingsDialogProbe.clickTab`、`SettingsDialogTest.clickTab`）。监听器挪到
 * 外边这层面板上，它们会**静默失效** —— 症状是出的图全变成第一页。
 * 面板上也挂一份：点图标那半边（不在标签上）也得能切页；Swing 的事件不冒泡，
 * 两处的 `mouseClicked` 不会各响一次。
 */

/** 左栏那八枚。与页一一对应。 */
internal enum class NavIcon { Models, Presets, General, Permission, Environment, Mcp, Hooks, GroupChat }

/** 图标边长（dp）。 */
internal const val NAV_ICON_SIDE = 16

/** 胶囊的圆角（`fillRoundRect` 的 arc，是**直径**：12 → 半径 6，与设计稿的 6px 一致）。 */
internal const val NAV_CORNER_ARC = 12

/** 悬停底色比面板底偏多少 —— 与卡片底同一个配方（[com.ccoder.settings.cardFill] 的 tint 是 0.05）。 */
private const val NAV_HOVER_TINT = 0.06

/** 一枚自绘图标。颜色取**函数**：选中态一变，`repaint` 一次就够了，不用重建图标。 */
internal class NavIconImpl(private val kind: NavIcon, private val color: () -> Color) : Icon {

    override fun getIconWidth(): Int = JBUI.scale(NAV_ICON_SIDE)

    override fun getIconHeight(): Int = JBUI.scale(NAV_ICON_SIDE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val side = getIconWidth().toDouble()
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(x, y)
            g2.scale(side / 16.0, side / 16.0)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color()
            // 与 CardIconView 同一个比例：16 的模子缩到 14 时，1.3 差不多就是一个像素
            g2.stroke = BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            glyph(g2, kind)
        } finally {
            g2.dispose()
        }
    }
}

/** 八条路径，坐标全在 16×16 的模子里（与设计稿 `settings-v3.html` 里那组 `<symbol>` 同一个画法）。 */
private fun glyph(g: Graphics2D, kind: NavIcon) = when (kind) {
    // 模型：一颗芯片（方框 + 八条引脚）
    NavIcon.Models -> {
        g.draw(RoundRectangle2D.Double(4.2, 4.2, 7.6, 7.6, 2.8, 2.8))
        val pins = Path2D.Double()
        pins.moveTo(6.2, 1.8); pins.lineTo(6.2, 4.2)
        pins.moveTo(9.8, 1.8); pins.lineTo(9.8, 4.2)
        pins.moveTo(6.2, 11.8); pins.lineTo(6.2, 14.2)
        pins.moveTo(9.8, 11.8); pins.lineTo(9.8, 14.2)
        pins.moveTo(1.8, 6.2); pins.lineTo(4.2, 6.2)
        pins.moveTo(1.8, 9.8); pins.lineTo(4.2, 9.8)
        pins.moveTo(11.8, 6.2); pins.lineTo(14.2, 6.2)
        pins.moveTo(11.8, 9.8); pins.lineTo(14.2, 9.8)
        g.draw(pins)
    }

    // 预置：一枚书签
    NavIcon.Presets -> {
        val p = Path2D.Double()
        p.moveTo(4.0, 1.9); p.lineTo(12.0, 1.9); p.lineTo(12.0, 14.1)
        p.lineTo(8.0, 11.2); p.lineTo(4.0, 14.1); p.closePath()
        g.draw(p)
    }

    // 通用：两根滑杆
    NavIcon.General -> {
        val p = Path2D.Double()
        p.moveTo(2.4, 5.2); p.lineTo(13.6, 5.2)
        p.moveTo(2.4, 10.8); p.lineTo(13.6, 10.8)
        g.draw(p)
        g.draw(Ellipse2D.Double(6.1 - 1.7, 5.2 - 1.7, 3.4, 3.4))
        g.draw(Ellipse2D.Double(9.9 - 1.7, 10.8 - 1.7, 3.4, 3.4))
    }

    // 权限：一面盾
    NavIcon.Permission -> {
        val p = Path2D.Double()
        p.moveTo(8.0, 1.9); p.lineTo(13.0, 3.8); p.lineTo(13.0, 7.9)
        p.curveTo(13.0, 10.9, 10.9, 13.1, 8.0, 14.1)
        p.curveTo(5.1, 13.1, 3.0, 10.9, 3.0, 7.9)
        p.lineTo(3.0, 3.8); p.closePath()
        g.draw(p)
    }

    // 环境：一个终端窗口
    NavIcon.Environment -> {
        g.draw(RoundRectangle2D.Double(1.8, 2.6, 12.4, 10.8, 3.2, 3.2))
        val p = Path2D.Double()
        p.moveTo(4.4, 6.1); p.lineTo(6.3, 7.9); p.lineTo(4.4, 9.7)
        p.moveTo(8.3, 9.9); p.lineTo(11.5, 9.9)
        g.draw(p)
    }

    // MCP：一只插头
    NavIcon.Mcp -> {
        val p = Path2D.Double()
        p.moveTo(6.0, 1.9); p.lineTo(6.0, 4.8)
        p.moveTo(10.0, 1.9); p.lineTo(10.0, 4.8)
        p.moveTo(4.2, 4.8); p.lineTo(11.8, 4.8); p.lineTo(11.8, 7.3)
        p.curveTo(11.8, 9.4, 10.1, 11.1, 8.0, 11.1)
        p.curveTo(5.9, 11.1, 4.2, 9.4, 4.2, 7.3)
        p.closePath()
        p.moveTo(8.0, 11.1); p.lineTo(8.0, 14.1)
        g.draw(p)
    }

    // hooks：一道闪电
    NavIcon.Hooks -> {
        val p = Path2D.Double()
        p.moveTo(9.0, 1.9); p.lineTo(3.7, 9.0); p.lineTo(7.1, 9.0)
        p.lineTo(6.2, 14.1); p.lineTo(11.5, 7.0); p.lineTo(8.2, 7.0)
        p.closePath()
        g.draw(p)
    }

    // 群交流：两个人（"群"比"一个气泡"更贴这一页）
    NavIcon.GroupChat -> {
        g.draw(Ellipse2D.Double(3.4, 3.4, 4.4, 4.4))
        val body = Path2D.Double()
        body.moveTo(1.8, 13.2)
        body.curveTo(1.8, 10.3, 3.5, 9.2, 5.6, 9.2)
        body.curveTo(7.7, 9.2, 9.4, 10.3, 9.4, 13.2)
        g.draw(body)
        g.draw(Ellipse2D.Double(9.6, 4.2, 3.4, 3.4))
        val arm = Path2D.Double()
        arm.moveTo(11.4, 13.2)
        arm.curveTo(11.4, 11.0, 10.6, 10.0, 9.4, 9.9)
        g.draw(arm)
    }
}

/**
 * 左栏里的一项：图标 + 标题 + 选中/悬停的胶囊底。
 *
 * 选中态由 [setSelected] 一处决定（底色 + 文字色 + 图标色一起翻）——
 * 与 `refreshModeLabel` / `refreshEffortLabel` 那类"标签与值一处决定"的规矩一样：
 * 分开设迟早出现"底选了、字没选"。
 */
internal class SettingsNavItem(
    val page: SettingsPage,
    icon: NavIcon,
    title: String,
    onClick: () -> Unit,
) : JPanel(BorderLayout()) {

    /** 监听器挂在这个标签上；用例与探针都按文本找它。 */
    internal val label: JBLabel = JBLabel(title)

    private var selected = false
    private var hovered = false

    private val iconView = JLabel(NavIconImpl(icon) { label.foreground })

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 8)
        label.isOpaque = false
        iconView.border = JBUI.Borders.emptyRight(9)

        val watcher = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
            override fun mouseEntered(e: MouseEvent) = setHovered(true)
            override fun mouseExited(e: MouseEvent) = setHovered(false)
        }
        addMouseListener(watcher)
        label.addMouseListener(watcher)

        add(iconView, BorderLayout.WEST)
        add(label, BorderLayout.CENTER)
        setSelected(false)
    }

    /**
     * 选中：底色 + 文字色 + 图标色一起翻（图标取 `label.foreground`，所以它跟着走）。
     *
     * 颜色**每次都设**，不做"没变就跳过"：构造时也要走一遍（未选中的初始文字色就是
     * 从这儿来的），跳过的话它会停在 `JBLabel` 的默认前景色上。
     */
    internal fun setSelected(value: Boolean) {
        val changed = selected != value
        selected = value
        label.foreground = if (value) {
            pickReadable(listOf(Color.WHITE, UIUtil.getLabelForeground()), focusColor())
        } else {
            UIUtil.getInactiveTextColor()
        }
        if (changed) repaint()
    }

    private fun setHovered(value: Boolean) {
        if (hovered == value) return
        hovered = value
        repaint()
    }

    /** 选中是实心强调色胶囊、悬停是一层很淡的底。没选中也不悬停时什么都不画。 */
    override fun paintComponent(g: Graphics) {
        if (!selected && !hovered) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (selected) {
                focusColor()
            } else {
                mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), NAV_HOVER_TINT)
            }
            val arc = JBUI.scale(NAV_CORNER_ARC)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}
