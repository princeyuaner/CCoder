package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 输入框左下角那三个切换弹层（**模型 / 权限模式 / 思考深度**）共用的一套"卡与行"。
 *
 * 抽出来的时机是第三个弹层也要这套行的时候。这三个弹层是并排着的三个标签，
 * 会被连着点开 —— 行高、勾位、悬停、选中的底色只要有一处不一样，就会立刻
 * 看出"这不是一个控件"。所以样式**只在这里写一遍**，三处都从这里拿。
 *
 * ## 一条规矩：鼠标事件不冒泡
 *
 * Swing 的鼠标事件落在哪个组件上就只发给哪个，**父组件收不到**。而一行里
 * 几乎每一寸都被子组件盖着（名字标签、说明标签、勾位那块面板），所以
 * "整行可点""整行会亮"这类效果必须**挂到整棵子树上**，见 [handlerInto] 与
 * [RoundedRow] 里那个 `ContainerListener`。
 *
 * 这一条踩过两次坑：先是点到行内空白没反应（只挂了行自己），后是悬停时
 * 只有鼠标压在行边那几像素上才亮（同样只挂了行自己）。**渲染探针看不出
 * 这一类问题** —— 它是直接喊监听器的，不走真实的命中测试。
 */

/**
 * 一组卡片的根：纵向排列，卡片之间留缝。
 *
 * 三个弹层都从这里开头，再往 `addCard` 里塞卡。
 */
internal fun cardColumn(): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(ROOT_PAD)
}

/**
 * 往一列卡片里加一张卡，顺带补上它与上一张之间的那道缝。
 *
 * 缝只留在卡**之间** —— 第一张卡上面不留，否则弹层顶上会空一条。
 */
internal fun JPanel.addCard(card: JComponent) {
    if (componentCount > 0) addLeftAligned(gap(CARD_GAP))
    addLeftAligned(card)
}

/**
 * 一张卡：一个卡头（可选）+ 若干行。
 *
 * 底用的是**面板色**，与弹层自己是同一个色 —— 所以卡是靠**边和圆角**立起来的，
 * 不是靠色差。设计稿里卡比背景亮一档，那是浏览器里弹层底透明的画法；Swing 的
 * 弹层自带底色，做成半透明要跟平台的弹层边框打架，不值当。
 *
 * [warn] 那档连**边**都是琥珀的：一组的分量不该只写在小字标题上（设计稿的 C 案）。
 */
internal fun optionCard(
    header: JComponent?,
    rows: List<JComponent>,
    warn: Boolean = false,
): JComponent {
    val card = CardPanel(warn).apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(CARD_PAD)
    }
    header?.let { card.addLeftAligned(it) }
    rows.forEach { card.addLeftAligned(it) }
    return card
}

/**
 * 一组：卡头 + 若干行。
 *
 * 权限模式与思考深度两个列表都长这样（一组 = 一张卡），所以直接给一个成套的。
 */
internal fun optionGroup(title: String, warn: Boolean, rows: List<JComponent>): JComponent =
    optionCard(header = cardTitle(title, warn = warn), rows = rows, warn = warn)

/**
 * 卡头：一句话说清"这一组是什么"。
 *
 * [warn] 那档用琥珀色 —— 留给"选之前得知道"的那一组（不再问你 / 仅部分模型认）。
 */
internal fun cardTitle(text: String, warn: Boolean = false): JComponent {
    val base = UIUtil.getLabelFont()
    return JLabel(text).apply {
        foreground = if (warn) warningColor() else UIUtil.getInactiveTextColor()
        // 小两号：它是这一组的名字，不该跟组里的选项一样响
        font = base.deriveFont(base.size2D - 2f)
        // 卡头属于下面那组行，不属于上一张卡的最后一个选项
        border = JBUI.Borders.empty(7, 9, 3, 9)
    }
}

/**
 * 最后那行"去别处改"的动作行。
 *
 * 它不是可选项（没有勾位、没有选中态），但**行高与悬停跟可选项一样** ——
 * 挨在同一个弹层里，手感不一样会很扎眼。缩进也保持一致，看起来才是这一列
 * 的最后一项，而不是一个按钮。
 */
internal fun actionRow(text: String, onClick: () -> Unit): JComponent {
    val row = RoundedRow(selected = false, fixedHeight = ROW_HEIGHT).apply {
        add(
            JLabel(text).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = UIUtil.getLabelFont()
            },
            BorderLayout.CENTER,
        )
    }
    handlerInto(
        row,
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick()
            }
        },
    )
    return row
}

/**
 * 弹层里的一行。
 *
 * [nameColor] 由调用方给：权限模式那边要传达安全性（绕过是警示色），
 * 模型与档位那边名字就是常规文字色。
 *
 * 说明**缩进到与名字对齐**（空出勾位那一段）—— 说明是名字的补充，
 * 不缩进的话两行左边界齐平，读起来像两件并列的事。
 */
internal fun optionRow(
    name: String,
    nameColor: Color,
    description: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，
    // deriveFont 会直接 NPE（RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    val nameLine = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(tickGutter(selected))
        add(JLabel(name).apply { foreground = nameColor; font = base })
    }

    val body = JPanel(BorderLayout()).apply {
        isOpaque = false
        if (description == null) {
            // 一行的那种（模型名）：居中，让它跟固定行高对齐
            add(nameLine, BorderLayout.CENTER)
        } else {
            add(nameLine, BorderLayout.NORTH)
            add(
                JLabel(description).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    font = base.deriveFont(base.size2D - 1f)
                    border = JBUI.Borders.emptyLeft(TICK_WIDTH)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    // 两行的行靠内容撑高（说明长短不一），一行的行由调用方给固定高
    val row = RoundedRow(
        selected = selected,
        fixedHeight = if (description == null) ROW_HEIGHT else null,
        padV = ROW_PAD_V,
    ).apply { add(body, BorderLayout.CENTER) }

    handlerInto(
        row,
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick()
            }
        },
    )
    return row
}

/**
 * 勾位：固定宽度，选中与否都占着。
 *
 * 宽度做在一个 `JPanel` 上而不是给 `JLabel` 设 `preferredSize` —— `JLabel`
 * 的 `preferredSize` 是按内容算的，设了不生效（`ModelProfilesPage.modelIdRow`
 * 上量过一次：两行的 ✕ 会落在 36px 和 0px 上）。同样的坑别踩第二遍。
 *
 * 勾单独一个标签（而不是把 `MARK` 缀在名字前面）是为了让它能用强调色：
 * 一个标签只有一个前景色，缀在一起的话勾和名字只能同色。
 */
internal fun tickGutter(selected: Boolean): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    preferredSize = Dimension(JBUI.scale(TICK_WIDTH), 0)
    // 宽度**必须三样一起钉**：`BoxLayout` 会把富余的地方分给还能长的子件，
    // 而勾位就在一个横向 `BoxLayout` 里（名字那一行）。只设 `preferredSize`
    // 的话它会把剩下的宽度全吃掉，把名字顶到最右边去 —— 探针里一眼就看见
    // 了（名字全跑到行右端）。高度不钉，让它跟着行高走，勾才居中。
    minimumSize = Dimension(JBUI.scale(TICK_WIDTH), 0)
    maximumSize = Dimension(JBUI.scale(TICK_WIDTH), Int.MAX_VALUE)
    if (selected) {
        add(JLabel(MARK).apply { foreground = accentColor() }, BorderLayout.WEST)
    }
}

/**
 * 把点击处理器挂到**这一棵子树里每一个组件**上。
 *
 * 不是多此一举：Swing 的鼠标事件**不冒泡**，落在哪个组件上就只发给哪个，
 * 父组件收不到。所以"整行可点"必须逐层挂，否则点到名字或说明那一片
 * （它们各自是别的组件）一点反应都没有，而那一块看着明明也是这一行。
 */
internal fun handlerInto(c: Container, l: MouseListener) {
    c.addMouseListener(l)
    for (child in c.components) {
        if (child is Container) handlerInto(child, l)
    }
}

/** 统一按左边界排。`JLabel` 左对齐、`JPanel` 居中，混在 `BoxLayout` 里会各自往不同的地方站。 */
internal fun JPanel.addLeftAligned(c: JComponent) {
    c.alignmentX = Component.LEFT_ALIGNMENT
    add(c)
}

/**
 * 一条竖缝。
 *
 * `Box.createVerticalStrut` 的返回类型写的是 `java.awt.Component`，而它造出来的
 * 其实是个 `Box.Filler`（`JComponent`）—— 这里把类型说出来，好让它跟别的子件
 * 一样过 [addLeftAligned]：`BoxLayout` 是按各子件自己的 `alignmentX` 摆的。
 */
internal fun gap(height: Int): JComponent =
    Box.createVerticalStrut(JBUI.scale(height)) as JComponent

/** 一张卡的底与边。 */
private class CardPanel(private val warn: Boolean) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getPanelBackground()
            g2.fill(roundRect(width.toFloat(), height.toFloat(), JBUI.scale(CARD_ARC)))
            g2.color = if (warn) {
                blend(warningColor(), UIUtil.getPanelBackground(), 0.42)
            } else {
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            }
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            // 半像素：1px 的线落在整数坐标上会糊成 2px（同 SessionChips 的胶囊）
            g2.draw(roundRect(width - 1f, height - 1f, JBUI.scale(CARD_ARC), 0.5f))
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 一行可点的横条：悬停铺一层浅底，选中再换一档。
 *
 * 自绘而不是用 `JPanel` 铺色块：底色要圆角，而 Swing 里没有圆角面板。
 * 三档颜色都取自 `UIUtil`（列表选中/悬停），没有自己挑的色 ——
 * 换主题时这一行跟着主题走，不用改这里。
 *
 * ## 悬停挂在整棵子树上（`ContainerListener` 那一段）
 *
 * 一行的面积几乎全被名字/说明那两个标签盖着，而鼠标事件只发给被压在下面
 * 的那一个。只挂行自己的话，**鼠标压在文字上时这一行根本不亮**，只有压在
 * 行边那几像素上才亮 —— 那看起来就像悬停是坏的（而且时灵时不灵）。
 *
 * 所以：加进来的每个子件都自动挂上同一个悬停监听器，包括后来才加进去的。
 * 从行移到它自己的标签上时会先 exit 再 enter，最终状态仍是"悬停中"。
 */
internal class RoundedRow(
    private val selected: Boolean,
    /** 固定行高；null = 按内容撑（带说明的两行行就是这种）。 */
    private val fixedHeight: Int? = null,
    padV: Int = 0,
) : JPanel(BorderLayout()) {

    private var hover = false

    private val hoverListener = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            hover = true
            repaint()
        }

        override fun mouseExited(e: MouseEvent) {
            hover = false
            repaint()
        }
    }

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // 左右留边：底色是一块有圆角的横条，字贴到边上看不出圆角
        border = JBUI.Borders.empty(padV, 6)
        addMouseListener(hoverListener)
        addContainerListener(
            object : ContainerAdapter() {
                override fun componentAdded(e: ContainerEvent) {
                    hoverInto(e.child)
                }
            }
        )
    }

    private fun hoverInto(c: Component) {
        c.addMouseListener(hoverListener)
        if (c is Container) c.components.forEach { hoverInto(it) }
    }

    /**
     * 悬停中吗。
     *
     * 只读暴露给测试。这件事**没有别的窗口看得见**：探针是直接喊监听器的，
     * 绕过了真实的命中测试，所以"鼠标压在名字上这行亮不亮"这条只能靠单测钉住。
     */
    internal val isHovered: Boolean get() = hover

    /**
     * 行高。
     *
     * 固定那种是给"一行的行"用的：卡头与行、管理行三样一样高，一列看着才齐
     * （`JLabel` 的首选高度会差一两像素）。带说明的行则**不能**固定 ——
     * 字号跟着主题走，写死一个数迟早夹住文字。
     */
    override fun getPreferredSize(): Dimension {
        val natural = super.getPreferredSize()
        return Dimension(natural.width, fixedHeight?.let { JBUI.scale(it) } ?: natural.height)
    }

    override fun getMaximumSize(): Dimension =
        Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val fill = when {
            selected -> UIUtil.getListSelectionBackground(true)
            hover -> UIUtil.getListSelectionBackground(false)
            else -> null
        } ?: return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fill(roundRect(width.toFloat(), height.toFloat(), JBUI.scale(ROW_ARC)))
            if (selected) {
                // 选中再压一道强调色的边：只靠底色的话，浅色主题里那一档
                // 跟悬停很接近，光标划过时看不出哪个是"当前"
                g2.color = blend(accentColor(), fill, 0.5)
                g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
                g2.draw(roundRect(width - 1f, height - 1f, JBUI.scale(ROW_ARC), 0.5f))
            }
        } finally {
            g2.dispose()
        }
    }
}

/** 强调色。**只在这一处取** —— 别处不许再写死一个蓝色（同 [SessionChips]）。 */
internal fun accentColor(): Color = JBUI.CurrentTheme.Focus.focusColor()

private fun roundRect(w: Float, h: Float, arc: Int, inset: Float = 0f) =
    RoundRectangle2D.Float(inset, inset, w, h, arc.toFloat(), arc.toFloat())

/** 弹层内容四周的留白。卡片自己还有一圈，别叠得太厚。 */
private const val ROOT_PAD = 6

/** 卡与卡之间的缝 —— 就是这一条把"一组"和"两组"分开的。 */
private const val CARD_GAP = 8

/** 卡的内边距。 */
private const val CARD_PAD = 5

/**
 * 卡片的圆角。
 *
 * 比弹层自己的圆角小一档：两层一样大的圆角套在一起，边上会出现一条
 * 粗细不匀的缝（同 ModelProfilesPage 里那排卡的做法）。
 */
private const val CARD_ARC = 12

/** 行底色的圆角。 */
private const val ROW_ARC = 8

/** 一行的行高。卡头、模型行、管理行都用它，一列看着才齐。 */
private const val ROW_HEIGHT = 30

/** 带说明的行上下留的气。 */
private const val ROW_PAD_V = 6

/** 勾位宽度。选中与不选中都占着，勾出现或消失时名字不会左右跳。 */
private const val TICK_WIDTH = 16
