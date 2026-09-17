package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 卡片左上角那个图标。一排卡各一个。
 *
 * **自绘，不用平台图标**（[CardIconView] 里有详细理由）。
 */
internal enum class CardIcon { Link, Context, Tasks, Agents }

/** 右上角那颗动作图标的圆角（悬停底色用的）。 */
private const val ACTION_CORNER_ARC = 4

/**
 * 动作图标悬停底色的透明度（0..255）。
 *
 * 只画图标不画底的话，"这里能点"全靠用户猜；填死色又会盖住卡片底色。
 * 0x2E 是"看得出是一块可点的区域、又不喧宾夺主"的那一档 —— 两个主题都看过。
 */
private const val ACTION_FILL_ALPHA = 0x2E

/** 动作图标的命中区边长（含内边距）。图标本身画 ~12px，命中区放大到 16px 才好点。 */
private const val ACTION_HIT = 16

/** 命中区离卡片内边距右上角的距离。 */
private const val ACTION_INSET = 2

/**
 * 一张状态卡（设计稿 docs/design/status-cards-v2.html 方案甲）。
 *
 * ## 两行居中
 *
 * 图标 + 标签一行、值一行，两行都水平居中。改版前是四层（标签 / 值 / 副值 /
 * 指示器）各自靠左或靠右，那一排里三种对齐、三种字号，整排 80px 高。
 * 副值（`12.3k / 200k`）让出去挂 tooltip，卡面才收得下来。
 *
 * ## 收边为什么用"透明描边"而不是"不设 border"
 *
 * 四张卡等宽并排。若收边时换掉 border 对象，`getBorderInsets` 会从 1 变成 0，
 * 这一格就宽出去 2px —— 旁边三张跟着挪。用全透明色画同一条边，insets 恒定，
 * 布局一动不动。
 *
 * ## 为什么用 JPanel 而不是像旧的胶囊那样自画
 *
 * 卡里放的是 `3/7`、`2` 这种定宽短值，不需要按宽度截断，用 JLabel 反而能
 * 免费拿到平台的字体、高 DPI 与主题色。
 *
 * ## 动作图标：**常驻在右上角，自绘，不新增组件**（2026-09-17）
 *
 * 连接卡右上角一把扫把（清空会话）、上下文卡右上角一个压缩记号（压缩上下文）。
 * 三条设计约束叠在一起得出这个形态：
 *
 * 1. **必须自绘**（与 [CardIconView] 同一套路数）：平台图标是固定颜色，
 *    而这一颗要跟着"可不可点 / 悬停"变；何况扫把与压缩记号平台里根本没有。
 * 2. **常驻，不做悬停才出现**。做过一版"悬停时把值那一行换成按钮"，实测
 *    （2026-09-17 用户报回）**指针一动到卡片中间按钮就消失**：`JComponent.setToolTipText`
 *    会把 `ToolTipManager` 注册成那个组件的鼠标监听器，子件于是成了事件目标，
 *    卡片收到 `mouseExited`、悬停态结束 —— 而指针正朝按钮去。常驻没有这个问题：
 *    按钮的存在不依赖任何悬停状态。
 * 3. **不新增组件**：多一个 `JComponent` 就多一个事件目标，还要给它挂 tooltip
 *    （见 [refreshActionLook] 里那条）。画出来 + 按几何判点击（[cardClickTargetOf]）
 *    则**画出来的和能点的天然是同一块**。
 *
 * @param icon 左上角画哪个图标。四张卡固定，所以由**构造参数**而不是模型决定：
 *   模型是"这一刻的数据"，图标是"这一格是什么"，两者寿命不同。
 * @param onOpen 点卡片（动作图标之外）做什么。null = 这张卡没有详情（连接卡）。
 * @param onAction 右上角那颗图标做什么。null = 这张卡没有动作（默认；子任务/子代理就是）。
 *   **不给空 lambda** —— 那会画出可点的东西却点不动，是在骗人。
 */
internal class StatusCardView(
    private val icon: CardIcon = CardIcon.Link,
    private val onOpen: (() -> Unit)? = null,
    private val onAction: (() -> Unit)? = null,
) : JPanel() {

    private var model: StatusCardModel? = null
    private var action: CardAction? = null
    private var open = false
    private var hovered = false

    /** 指针此刻压在那颗动作图标上（只用来画悬停底色，与"能不能点"无关）。 */
    private var iconHot = false

    private val labelView = JBLabel()
    private val valueView = JBLabel()
    private val iconView = CardIconView(icon)
    private val indicatorView = IndicatorView()

    /**
     * 指示器那一行：**用 BorderLayout 让它铺满整行**。
     *
     * 直接把指示器放进 BoxLayout 是不行的 —— 比例条会拿到一个又短又偏的宽度
     * （实测 83px 的行里它只占 42px，还贴在最右边）。BoxLayout 只把**面板**拉满，
     * 与值那一行是同一个坑。
     */
    private val indicatorRow = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(indicatorView, BorderLayout.CENTER)
    }

    /**
     * 值那一行。
     *
     * 用 [BorderLayout] 把标签**拉满一行**，文字靠 [SwingConstants.CENTER] 居中。
     * 直接把标签放进 BoxLayout 是不行的：实测（ScratchMeasureTest）标签拿到的
     * 宽度既不是卡片宽度、也不会随文字变化 —— 5 个汉字会被省略号截掉。
     * BoxLayout 只把**面板**拉满，标签不在其列。
     */
    private val valueRow = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(valueView, BorderLayout.CENTER)
    }

    /**
     * 图标 + 标签那一行。整行居中：方案甲要的就是"四张卡里不再有三种对齐"。
     *
     * 用 [FlowLayout] 而不是 BoxLayout X：FlowLayout 天生按内容居中，
     * 而 BoxLayout 在容器比内容宽时会把富余空间**分给子件**（标签会被撑开、
     * 图标被挤到左边）。hgap = 图标与标签之间的距离，vgap = 0。
     */
    private val topRow = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(iconView)
        add(labelView)
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(colorProvider = ::strokeColor, arc = JBUI.scale(CARD_CORNER_ARC)),
            JBUI.Borders.empty(5, 8, 6, 8),
        )

        labelView.font = labelView.font.deriveFont(labelView.font.size2D - 2f)
        labelView.foreground = UIUtil.getInactiveTextColor()
        // 值统一大一号。改版前是 11/12/15 三种字号按 bigValue 二选一，
        // 扫一眼分不出哪个是重点 —— 现在四张卡一个字号
        valueView.font = valueView.font.deriveFont(valueView.font.size2D + 1f)
        // 值在它自己那一行里居中；那一行由 [valueRow] 负责拉满卡片宽度
        valueView.horizontalAlignment = SwingConstants.CENTER

        add(topRow)
        add(valueRow)
        // 竖直弹簧：GridLayout 把每张卡拉到同一高度，弹簧让**指示器贴底**。
        // 没有它的话上下文卡的条会比子任务卡的点阵低一截，四张卡底边参差
        add(Box.createVerticalGlue())
        add(indicatorRow)

        // 有动作的卡也要挂监听器 —— 否则连接卡（没有 onOpen）那颗图标点不动
        if (onOpen != null || onAction != null) {
            if (onOpen != null) cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val adapter = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // 去向按几何分（纯函数，有测试）：**落在动作图标那一块才算动作**。
                    // 那道闸是"清空不设确认框"的安全垫 —— 误触得正好点中那 16×16 的格子
                    when (
                        cardClickTargetOf(
                            onActionIcon = actionIconBounds().contains(e.point),
                            hasAction = action != null,
                            hasDetail = onOpen != null,
                        )
                    ) {
                        // 灰着的动作点了什么都不做 —— tooltip 里已经说清了为什么（spec §3.5）
                        CardClickTarget.Action -> if (action?.enabled == true) onAction?.invoke()
                        CardClickTarget.OpenDetail -> onOpen?.invoke()
                        CardClickTarget.None -> Unit
                    }
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    refreshActionLook()
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    iconHot = false
                    refreshActionLook()
                    repaint()
                }

                /**
                 * 指针压在动作图标上时：底色亮起来、光标变手型、tooltip 换成动作的说明。
                 *
                 * 三者都跟着**指针所在的那一块**走，而不是跟着"悬停在卡上"走 ——
                 * 卡片其余部分的通知（详情、副值 tooltip）不该被这颗图标顶掉。
                 */
                override fun mouseMoved(e: MouseEvent) {
                    val hot = action != null && actionIconBounds().contains(e.point)
                    val changed = hot != iconHot
                    iconHot = hot
                    if (onOpen == null) {
                        cursor = if (hot) {
                            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        } else {
                            Cursor.getDefaultCursor()
                        }
                    }
                    if (changed) {
                        refreshActionLook()
                        repaint()
                    }
                }
            }
            addMouseListener(adapter)
            // mouseMoved 走的是另一个监听器接口 —— 只挂 addMouseListener 收不到
            addMouseMotionListener(adapter)
        }
    }

    /**
     * 右上角那颗动作图标的命中区（卡片坐标系）。
     *
     * **画出来的和能点的就是这一块**（[paintActionIcon] 用的是同一个矩形）——
     * 两者一旦分家，用户会点到"看着在图标外、其实会清空会话"的地方。
     *
     * 位置按内边距右上角算，允许它**压进右内边距**：卡只有 97px 宽，16px 的
     * 命中区若全从内容区里抠，四字的标签（「等待响应」）就没地方站了。
     */
    private fun actionIconBounds(): java.awt.Rectangle {
        val size = JBUI.scale(ACTION_HIT)
        val inset = JBUI.scale(ACTION_INSET)
        return java.awt.Rectangle(
            width - insets.right - size + inset,
            insets.top + inset,
            size,
            size,
        )
    }

    /**
     * 画卡片的底。
     *
     * **自己画一层，不让父容器透出来。** 顶部这排坐在哪块容器上、那块容器是什么
     * 底色，是别的代码说了算 —— 实测在真实 IDE 里那块底是暗的，透出来之后四张卡
     * 跟背景糊成一片，设计稿里那种"灰卡片"就没了（探针里看不出来：它恰好把
     * 面板色当背景）。
     *
     * 顺带：自绘圆角矩形也避免了"方角填充 + 圆角描边"在四个角上打架。
     *
     * 圆角用的是 [CARD_CORNER_ARC]（在 Composer 那边，与输入卡**共用一个数**）：
     * 底由这里的 `fillRoundRect` 画、边由 [RoundedLineBorder] 的 `drawRoundRect`
     * 画 —— 两个数一旦分叉，四个角上就会露出"底比边圆"或者反过来的一圈毛刺。
     *
     * **收边（quiet）也照画**：那是"这格现在没数据"（值变灰），不是"这格不存在"。
     * 2026-09-14 用户明确要求过：没数据时边框与底照常要有，四张卡看着是一排。
     */
    override fun paintComponent(g: Graphics) {
        // 动作也算"有东西要画"—— 探针会只给动作不给模型地画一张
        if (model == null && action == null) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = UIUtil.getPanelBackground()
            val arc = JBUI.scale(CARD_CORNER_ARC)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            paintActionIcon(g2)
        } finally {
            g2.dispose()
        }
    }

    /** 不叫 update：那会与 [java.awt.Component.update] 撞名。 */
    fun setModel(next: StatusCardModel) {
        model = next
        labelView.text = next.label
        // 值那一行与 tooltip 归 [refreshActionLook] 管 —— 两处都写的话，
        // 悬停中刷新（连接卡的动作文字在跳）会把动作按钮又盖回成值
        refreshActionLook()

        iconView.set(next.tone, next.quiet)

        // 两行的最大宽放开、高度锁在首选值：BoxLayout 才会把它们拉满卡片宽度
        // （文字靠 FlowLayout / horizontalAlignment 居中），又不会被竖直拉伸 ——
        // 竖直的富余要留给下面那个弹簧，指示器才贴得住底
        topRow.maximumSize = Dimension(Int.MAX_VALUE, topRow.preferredSize.height)
        valueRow.maximumSize = Dimension(Int.MAX_VALUE, valueRow.preferredSize.height)
        // 指示器那一行同理。高度在"没有指示器"时是 0 —— 那时它整个不可见
        indicatorRow.maximumSize =
            Dimension(Int.MAX_VALUE, indicatorRow.preferredSize.height)

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
     * 换动作。null = 这张卡现在没有动作（回到今天的样子）。
     *
     * **不动布局**：值那一行两种状态下都是"居中一行字"，宽度一样，
     * 所以四张卡的位置一动不动 —— 这也是方案甲敢往 97×50 里塞按钮的全部底气。
     */
    fun setAction(next: CardAction?) {
        action = next
        refreshActionLook()
        repaint()
    }

    /** 探针与测试用：动作图标此刻高没高亮（指针压在上面）。 */
    fun isIconHotForProbe(): Boolean = iconHot

    /** 测试用：动作图标的命中区（画出来的和能点的都是它，猜坐标会随字体与 DPI 漂移）。 */
    internal fun actionIconBoundsForTest(): java.awt.Rectangle = actionIconBounds()

    /** 探针用：值行现在显示的是什么（应当永远等于模型的值）。 */
    fun valueTextForProbe(): String = valueView.text

    /**
     * 值行与 tooltip 的**唯一出口**。
     *
     * 值那一行**永远**显示模型的值（动作不占它的位子，那是"悬停换值"那一版
     * 的做法，已被用户否掉）；变的只有 tooltip —— 指针压在动作图标上时给动作的
     * 说明，否则给副值。
     *
     * ## tooltip 只挂卡片自己 —— 子件一个都不挂
     *
     * `JComponent.setToolTipText` 会把 `ToolTipManager` 注册成**那个组件的
     * 鼠标监听器**（JDK 实测：挂完 `getMouseListeners().length == 1`）。而 Swing
     * 把鼠标事件派发给"最深的有监听器的组件"—— 于是给子件挂 tooltip 就等于
     * **让子件把卡片的事件偷走**：指针从卡片边缘移到卡片中间（标签/值行上），
     * 卡片立刻收到 `mouseExited`。2026-09-17 用户实测报回的就是它
     * （「鼠标放在边框上按钮才出现，挪到中间就消失」）。
     *
     * 子件一个都不挂之后，整张卡都是卡片自己的事件目标；而 tooltip **照样弹** ——
     * `ToolTipManager` 问的是 `event.getSource()`（JDK 源码 `ToolTipManager.mouseMoved`
     * 与 `insideTimerAction`：`insideComponent = event.getSource()`，**不往父级找**），
     * 那时事件源正是卡片。
     *
     * 从前那句"四个子件都要挂，只给卡片挂的话十有八九弹不出来"是错的 ——
     * 错在把"子件挂了自己弹不出父级的"（确实如此，JDK 不回溯）当成了
     * "父件挂了也弹不出来"（恰恰相反）。
     */
    private fun refreshActionLook() {
        val m = model
        valueView.text = m?.value ?: ""
        valueView.foreground =
            if (m == null || m.quiet) UIUtil.getInactiveTextColor() else UIUtil.getLabelForeground()

        val current = action
        toolTipText = if (iconHot && current != null) current.tooltip else m?.sub
    }

    /** 危险动作用危险色，其余用强调色 —— 与指示器同一条规矩。 */
    private fun actionColor(a: CardAction): Color = if (a.danger) dangerColor() else focusColor()

    /**
     * 右上角那颗动作图标。
     *
     * **常驻**（有动作就画，不看悬停）—— 它可不可点取决于 `enabled`，与"鼠标
     * 在不在卡上"无关；指针压在它上面时加一层底色，那是纯反馈。
     *
     * 命中区就是 [actionIconBounds]，**画的与点的是同一块**；两者一旦分家，
     * 用户会点到"看着在图标外、其实会清空会话"的地方。
     *
     * 图形按 16×16 的方格画再整体缩放 —— 与 [CardIconView] 同一套路数，
     * 高 DPI 下不会画出畸形。
     */
    private fun paintActionIcon(g2: Graphics2D) {
        val a = action ?: return
        val b = actionIconBounds()
        if (b.width <= 0) return

        val color = if (a.enabled) actionColor(a) else UIUtil.getInactiveTextColor()
        val arc = JBUI.scale(ACTION_CORNER_ARC)

        if (iconHot && a.enabled) {
            g2.color = Color(color.red, color.green, color.blue, ACTION_FILL_ALPHA)
            g2.fillRoundRect(b.x, b.y, b.width, b.height, arc, arc)
        }

        // 图形本身比命中区小一圈：命中区要够大才点得中，墨迹太大则四张卡都吵。
        // **0.88 是看图定的**：第一版画到 0.78（约 12px 里再留白，墨迹只剩 7px），
        // 扫把缩成一道斜杠、压缩缩成一个小叉 —— 两颗都认不出来
        val side = (minOf(b.width, b.height) * 0.88).toInt()
        if (side <= 4) return
        val g3 = g2.create() as Graphics2D
        try {
            g3.color = color
            g3.stroke = BasicStroke(side / 16f * 1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g3.translate(b.x + (b.width - side) / 2.0, b.y + (b.height - side) / 2.0)
            g3.scale(side / 16.0, side / 16.0)
            when (a.kind) {
                CardActionKind.Clear -> paintBroom(g3)
                CardActionKind.Compact -> paintCompress(g3)
            }
        } finally {
            g3.dispose()
        }
    }

    /**
     * 扫把（清空会话）：斜手柄 + 四根扇形鬃毛。
     *
     * 选扫把而不是垃圾桶：垃圾桶说的是"删掉"，而这个动作**不删任何东西** ——
     * 旧会话留在磁盘上、列表里能切回来，它只是把台面扫干净（spec §3.6）。
     *
     * 鬃毛画成四根散开的线，不是一块实心梯形：14px 上实心块与手柄同宽，
     * 合起来就是一片刀刃（第一版看图就是这个问题）。四根线自带"毛"的意思。
     */
    private fun paintBroom(g2: Graphics2D) {
        // 手柄（细）
        g2.draw(Line2D.Double(13.6, 2.4, 10.4, 5.6))
        // 帚头（粗）：脖子宽 4、帚口宽 6，方向与手柄垂直
        g2.fill(
            Path2D.Double().apply {
                moveTo(9.0, 4.9)
                lineTo(11.8, 7.7)
                lineTo(9.7, 11.2)
                lineTo(5.1, 6.6)
                closePath()
            }
        )
    }

    /**
     * 压缩记号（压缩上下文）：上下两支**对射的箭头**，中间留一道缝。
     *
     * 每支都带杆 —— 光有箭镞的话 14px 上两个 V 并起来就是一个小叉
     * （第一版就是这样，看图才发现）；有了杆，"往里挤"的方向才读得出来。
     *
     * 与"展开"是一对反义图形，用户不用读文字就知道哪个方向是省空间。
     */
    private fun paintCompress(g2: Graphics2D) {
        // 压下去的那支箭头：杆 + 箭镞
        g2.draw(Line2D.Double(8.0, 2.4, 8.0, 7.8))
        g2.draw(
            Path2D.Double().apply {
                moveTo(5.6, 5.4)
                lineTo(8.0, 7.8)
                lineTo(10.4, 5.4)
            }
        )
        // 托盘：一道口沿 + 一个浅斗（"往下收进盒子"）
        g2.draw(Line2D.Double(3.0, 11.4, 13.0, 11.4))
        g2.draw(
            Path2D.Double().apply {
                moveTo(3.0, 11.4)
                lineTo(3.0, 13.4)
                lineTo(13.0, 13.4)
                lineTo(13.0, 11.4)
            }
        )
    }

    /**
     * 最小高度 = 首选高度：**卡不能被压扁**，压扁就是几行字叠在一起。
     *
     * 不覆写的话这里会报出 15px —— `JLabel` 没有布局管理器，它的
     * `getMinimumSize()` 落到 `Component.size()`，也就是 0，于是 BoxLayout
     * 累出来的最小高度只剩边框那几像素。分隔条设了
     * `honorComponentsMinimumSize`，拿这个值当底线，用户就能把状态行
     * 拖成一条缝。
     */
    override fun getMinimumSize(): Dimension =
        Dimension(super.getMinimumSize().width, preferredSize.height)

    /**
     * 卡片高度**固定**（2026-09-15 用户报"子代理有任务时高度会自己变高"）。
     *
     * 四张卡的高度由 GridLayout 拉平到最高的那张（见 [StatusCardsRow]），而指示器
     * 那一行的高度是**随类型变**的：无 0px / 比例条 2px / 分段 4px / 点阵 5px。
     * 于是"子代理从空闲变成 1"会让**整排**长高 5px，下面的转写区跟着跳一下。
     *
     * 做法：高度按**最高的那个指示器槽**（点阵的 5px）算死，差出来的空间由竖直
     * 弹簧吸收 —— 指示器因此照旧贴底、四张卡底边齐，而卡片高度一动都不动。
     * 代价是空闲时卡片底部多 5px 空档 —— 那正是"固定"的意思。
     */
    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        val reserved = topRow.preferredSize.height + valueRow.preferredSize.height +
            insets.top + insets.bottom + IndicatorView.slotHeight()
        return Dimension(base.width, maxOf(base.height, reserved))
    }

    /**
     * 描边色。
     *
     * **收边（quiet）也给正常的边** —— 那是"这格没数据"，不是"这格不存在"。
     * 只有 `setModel` 一次都没调过（model == null，生产里不会出现）时才全透明。
     *
     * 无论哪种情况都返回颜色而不换 border 对象：`getBorderInsets` 因此恒为 1，
     * 收边与否都不会让四张卡左右跳。
     */
    private fun strokeColor(): Color = when {
        model == null -> Color(0, 0, 0, 0)
        open || hovered -> focusColor()
        else -> lineColor()
    }
}

/**
 * 卡上那个 14px 图标。**自绘。**
 *
 * 为什么不用平台图标（`AllIcons.*`）：平台里根本没有"连接""上下文用量"这两种
 * 语义的图标，混着用会在同一行里露出两种画风 —— 一边是系统色图标、一边是
 * 跟 [Tone] 变色的自绘。而**颜色必须跟着色调走**：连接卡的"已连接 / 正在载入 /
 * 启动失败"就靠这一处区分（原来是一个前置状态点，并进图标了）。
 *
 * 四个形状各三五笔，缩放按组件实际宽度算，高 DPI 下画出来还是清楚的。
 */
internal class CardIconView(val icon: CardIcon) : JComponent() {

    private var tone: Tone = Tone.Idle
    private var quiet = false

    init {
        isOpaque = false
    }

    fun set(nextTone: Tone, nextQuiet: Boolean) {
        tone = nextTone
        quiet = nextQuiet
        repaint()
    }

    /** 当前该画的颜色。暴露出来是为了让"图标跟着色调走"能被测试钉住。 */
    fun color(): Color = if (quiet) UIUtil.getInactiveTextColor() else toneColor(tone)

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(14), JBUI.scale(14))

    override fun getMaximumSize(): Dimension = preferredSize

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // 图形按 16×16 的方格画，再整体缩到组件尺寸 —— 这样高 DPI 与
            // 夹具里那些"没被布局过"的 0 尺寸调用都不会画出畸形的图标
            val side = minOf(width, height)
            if (side <= 0) return
            g2.color = color()
            g2.stroke = BasicStroke(side / 16f * 1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.scale(side / 16.0, side / 16.0)
            when (icon) {
                CardIcon.Link -> {
                    // 地球：一圈加一条横线。连接/会话这一类语义最不容易被误读
                    g2.draw(Ellipse2D.Double(2.4, 2.4, 11.2, 11.2))
                    g2.draw(Line2D.Double(2.6, 8.0, 13.4, 8.0))
                }

                CardIcon.Context -> {
                    // 四分之一饼：**是符号不是刻度**（6% 按比例画只有 2px 弧，
                    // 看不见）。真实比例交给数字 —— 见设计稿里的说明
                    g2.draw(Ellipse2D.Double(2.4, 2.4, 11.2, 11.2))
                    g2.fill(Arc2D.Double(2.4, 2.4, 11.2, 11.2, 90.0, -90.0, Arc2D.PIE))
                }

                CardIcon.Tasks -> {
                    g2.draw(RoundRectangle2D.Double(2.4, 2.8, 11.2, 10.4, 3.0, 3.0))
                    g2.draw(
                        Path2D.Double().apply {
                            moveTo(4.8, 8.2)
                            lineTo(6.4, 9.8)
                            lineTo(9.6, 6.4)
                        }
                    )
                }

                CardIcon.Agents -> {
                    // 两个人：前面一个整的，后面一个错开半头
                    g2.draw(Ellipse2D.Double(3.6, 3.2, 4.6, 4.6))
                    g2.draw(Arc2D.Double(2.2, 7.6, 7.4, 7.0, 0.0, 180.0, Arc2D.OPEN))
                    g2.draw(Ellipse2D.Double(9.8, 4.6, 3.4, 3.4))
                    g2.draw(Arc2D.Double(8.6, 8.2, 5.4, 5.4, 0.0, 180.0, Arc2D.OPEN))
                }
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 卡片底部那条微指示。
 *
 * 自画而不是拼组件：它有三种形状（条 / 分段 / 点），每种都只有几行绘制代码，
 * 拼出来反而要维护三套子组件。
 *
 * 2026-09-14 改版：比例条保留**通长一条**（贴底，所以它必须是铺满的那种），
 * 分段与点数收成**居中的小方块 / 小圆点** —— 改版前它们是一条 60px 宽的横条，
 * 在收成两行的卡上显得又长又偏。
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

    override fun getPreferredSize(): Dimension {
        // 先接到局部变量：`indicator` 是可变属性，不接的话 when 里智能转换不了
        val current = indicator
        return when (current) {
            Indicator.None -> Dimension(0, 0)
            // 宽度交给布局（Meter 铺满），高度 2px
            is Indicator.Meter -> Dimension(0, JBUI.scale(2))
            is Indicator.Segments -> Dimension(pipsWidth(current.total, PIP), JBUI.scale(PIP))
            is Indicator.Dots -> Dimension(pipsWidth(current.count, DOT), JBUI.scale(DOT))
        }
    }

    override fun getMaximumSize(): Dimension =
        if (indicator is Indicator.Meter) {
            Dimension(Int.MAX_VALUE, JBUI.scale(2))
        } else {
            preferredSize
        }

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
        val metrics = pipsMetrics(PIP)
        var x = startX(pipsWidth(segments.total, PIP))
        for (index in 0 until segments.total) {
            g2.color = when {
                index < segments.done -> okColor()
                index == segments.done -> focusColor()
                else -> trackColor()
            }
            g2.fillRoundRect(x, metrics.y, metrics.size, metrics.size, JBUI.scale(1), JBUI.scale(1))
            x += metrics.size + metrics.gap
        }
    }

    private fun paintDots(g2: Graphics2D, dots: Indicator.Dots) {
        val metrics = pipsMetrics(DOT)
        g2.color = alertColor() ?: focusColor()
        var x = startX(pipsWidth(dots.count, DOT))
        for (index in 0 until dots.count) {
            if (x + metrics.size > width) break
            g2.fillOval(x, metrics.y, metrics.size, metrics.size)
            x += metrics.size + metrics.gap
        }
    }

    private fun startX(contentWidth: Int): Int = pipsStartX(width, contentWidth)

    /** 只有警示色调才覆盖指示器颜色。其余一律强调色 —— 见 [Tone] 的说明。 */
    private fun alertColor(): Color? = when (tone) {
        Tone.Warn -> warningColor()
        Tone.Danger -> dangerColor()
        else -> null
    }

    /** 一行 pips 的总宽（含间隙），用来报首选宽度。 */
    private fun pipsWidth(count: Int, unit: Int): Int {
        if (count <= 0) return 0
        val m = pipsMetrics(unit)
        return count * m.size + (count - 1) * m.gap
    }

    /** pips 的尺寸与居中位置。两种点数共用同一套算法，免得一个偏左一个偏右。 */
    private fun pipsMetrics(unit: Int): Pips {
        val size = JBUI.scale(unit)
        val gap = JBUI.scale(3)
        return Pips(size = size, gap = gap, y = (height - size) / 2)
    }

    private data class Pips(val size: Int, val gap: Int, val y: Int)

    internal companion object {
        /** 分段那一排的方块边长（未缩放）。 */
        private const val PIP = 4

        /** 计数那一排的圆点直径（未缩放）。 */
        private const val DOT = 5

        /**
         * 指示器槽位的固定高度 —— 取最高的那个（点阵）。
         *
         * 卡片按它**算死**自己的高度，好让"有指示器/没有指示器"不再改变整排的
         * 高度（见 `StatusCardView.getPreferredSize`）。放在这里而不是在卡片里
         * 写一个 5：两者一旦脱钩，点阵就会画到卡片外面去。
         */
        fun slotHeight(): Int = JBUI.scale(DOT)
    }
}

// ---- 色调 → 颜色 ----

/**
 * 色调 → 实际颜色。**状态图标**用它。
 *
 * 指示器不走这里：指示器的规则是"只有警示色调才改色，其余一律强调色"
 * （见 [Tone]），而图标的四种色调各有各的颜色。
 */
internal fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Ok -> okColor()
    Tone.Warn -> warningColor()
    Tone.Danger -> dangerColor()
    Tone.Idle -> UIUtil.getInactiveTextColor()
}

/**
 * 一排点 / 分段的起点：在整行里**居中**。
 *
 * 那一行是铺满的（见 `StatusCardView.indicatorRow`），所以左对齐会让它们贴在
 * 卡片左边 —— 居中得自己算。比例条不走这里：它本来就该通长。
 *
 * 单独抽成一个函数是因为它**在绘制里**：组件自身的 x/width 永远是 0/整行宽，
 * 从外面量不到，只有把这段算术拿出来才钉得住。
 */
internal fun pipsStartX(rowWidth: Int, contentWidth: Int): Int =
    ((rowWidth - contentWidth) / 2).coerceAtLeast(0)

/** 成功色。平台不给就退回 New UI 的一对绿。 */
internal fun okColor(): Color = JBColor.namedColor(
    "Component.successColor",
    JBColor(Color(0x3D, 0x8B, 0x43), Color(0x5F, 0xAD, 0x65)),
)

/** 指示条的底槽。 */
private fun trackColor(): Color = JBColor.namedColor(
    "Component.borderColor",
    JBColor(Color(0x33, 0x36, 0x3B), Color(0xE0, 0xE2, 0xE7)),
)
