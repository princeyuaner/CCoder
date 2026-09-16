package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「＋」与齿轮之间的**最小**间隔。
 *
 * 排满 5 个标签时，胶囊会把这一行吃到只剩这点地方 —— 不给它的话两个按钮会
 * **贴在一起**（2026-09-16 在 `top-row-probe-five.png` 上一眼看见：五颗胶囊
 * 吞掉整行，弹簧被挤成 0，「＋」直接顶住齿轮，点错率高）。富余的时候弹簧会把
 * 它们拉得比这远得多，所以平时看不出这个数在起作用。
 *
 * 它与胶囊之间那个 [CHIP_GAP] 不是一回事：那个是"两颗胶囊之间挨多近"，
 * 这个是"两个按钮再怎么挤也不许贴上"。
 */
internal val MIN_BUTTON_SEPARATION = JBUI.scale(6)

/** 图标盒边长。与字体无关 —— 它就是个固定的框。 */
private const val ICON_SIDE = 12

/** 图标描边宽度。与 [RoundSendButton] 那几个自绘图形同一档，粗细才一致。 */
private const val ICON_STROKE = 1.5f

/**
 * 顶部一行：**会话胶囊在开头，「＋」紧跟在最后一个胶囊后面**，齿轮占最右角，
 * 中间那一段富余归弹簧。
 *
 * 抽成独立函数是为了可测 —— [ClaudePanel] 依赖 `Project`，起不了单测，而
 * "谁挨着谁"恰好是这里改过几次的事。探针 [TopRowRenderProbe] 画的也是这一份，
 * 不是画一份长得像的。
 *
 * ## 「＋」为什么从齿轮旁边挪到了胶囊后面（2026-09-16）
 *
 * 用户原话：「新建会话的按钮应该时刻跟随在最后一个胶囊的后面」。
 * 设计稿 `docs/design/session-tabs.html` 里给方案 B 列的那条"什么在哪儿"
 * 也正是这么写的：「『＋』跟在最后一个胶囊后面」—— 同时写着齿轮"挪到右上角"。
 *
 * 齿轮**挪不进平台的标题栏**（那一行不归我们画），所以它留在这一行的最右角，
 * 中间的空档交给 `Box.createHorizontalGlue()`。这样"＋ 咬住胶囊"与"齿轮贴住
 * 右边缘"两件事由同一个弹簧保证，不用各写各的。
 *
 * 随之作废的是原来那个 `TOP_ROW_GAP` —— 它管的是"两个图标之间隔多远"，
 * 而这两个图标已经不挨着了。但它换来的那条规矩仍然有效、也仍被用例钉着：
 * **图标的盒子必须等于图标本身**。New UI 给每个 `JButton` 兜的 72px 最小宽度
 * 一旦插进来，屏幕上就是中间空着一大截（见 [TopRowIconButton] 里那张对照表）。
 *
 * 这一行的白只有两处出处：胶囊与「＋」之间的 [CHIP_GAP]（与"两颗胶囊之间"
 * 是同一个数），以及那个弹簧。别在按钮内边距或别处再添白。
 */
internal fun buildTopRow(
    sessionLabel: JComponent,
    settingsButton: JComponent,
    newSessionButton: JComponent,
): JPanel = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(4, 8)
    add(
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(sessionLabel)
            // 与胶囊之间用同一个间距 —— "胶囊与＋"和"两颗胶囊之间"本来就是一回事
            add(Box.createHorizontalStrut(CHIP_GAP))
            add(newSessionButton)
            // 排满时这里是弹簧被挤成 0 之后的**唯一**退路，见 [MIN_BUTTON_SEPARATION]
            add(Box.createHorizontalStrut(MIN_BUTTON_SEPARATION))
            // 富余**全给这一段**：它同时把「＋」顶到胶囊后面、把齿轮顶到最右角。
            // （换成一个居中的容器就做不到 —— 那样"＋ 在哪儿"会随窗口宽度漂）
            add(Box.createHorizontalGlue())
        },
        BorderLayout.CENTER,
    )
    add(settingsButton, BorderLayout.EAST)
}

/** 自绘图标的两种形状。 */
internal enum class TopRowGlyph { Plus, Gear }

/**
 * 两个按钮上画的图形。**自绘，不用字形**。
 *
 * 用字符（「＋」U+FF0B / 「⚙」U+2699）的代价写在 [buildTopRow] 顶上：
 * 字形盒的边距不可控、随字体与 DPI 变，压得紧了还会被 LAF 判成"放不下"
 * 而画成省略号。自绘之后，**这个盒子的边界就是墨迹** —— 间距想给多少给多少。
 *
 * 颜色取 `foreground`：两个按钮都会按忙闲改前景色（置灰），图标跟着走。
 */
internal class TopRowIcon(private val glyph: TopRowGlyph) : Icon {

    override fun getIconWidth(): Int = JBUI.scale(ICON_SIDE)

    override fun getIconHeight(): Int = JBUI.scale(ICON_SIDE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = c?.foreground ?: Color.GRAY
            val stroke = BasicStroke(
                JBUI.scale(ICON_STROKE),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )
            g2.stroke = stroke
            g2.translate(x, y)
            // 按 12×12 的模子画，再整体缩到真实尺寸 —— 与 [CardIconView] 同一个理由：
            // 高 DPI 与"没被布局过"的 0 尺寸调用都不该画出畸形的图标
            val side = minOf(iconWidth, iconHeight).toDouble()
            if (side <= 0) return
            g2.scale(side / ICON_SIDE, side / ICON_SIDE)

            // 墨迹顶到盒子边缘（只让出半个描边），这样"盒子的边界就是墨迹"
            val m = (ICON_STROKE / 2).toDouble()
            val far = ICON_SIDE - m
            val mid = ICON_SIDE / 2.0

            when (glyph) {
                TopRowGlyph.Plus -> {
                    g2.draw(Line2D.Double(mid, m, mid, far))
                    g2.draw(Line2D.Double(m, mid, far, mid))
                }

                TopRowGlyph.Gear -> {
                    // 一个圆加六颗齿。齿从圆上长出来一点，别画成太阳
                    val r = 3.1
                    val tooth = 5.4
                    g2.draw(Ellipse2D.Double(mid - r, mid - r, r * 2, r * 2))
                    for (i in 0 until 6) {
                        val a = Math.toRadians(i * 60.0)
                        g2.draw(
                            Line2D.Double(
                                mid + cos(a) * r, mid + sin(a) * r,
                                mid + cos(a) * tooth, mid + sin(a) * tooth,
                            )
                        )
                    }
                }
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 右上角那两个图标按钮（「＋」与「⚙」）。**只写一遍** ——
 * [SessionNewButton] 与 [settingsGearButton] 各用一次。
 *
 * 理由与 [SessionNewButton] 最初的注释同源：同一行里两个按钮，一个有边框一个
 * 没有、字号差半号，并排放在一起都扎眼。分开写就迟早会分叉。
 *
 * 有一处不是随手写的：那个 `border`。默认边框的 3px 内边距**两个方向待遇不同**：
 *  - **横向**：纯粹是空隙，**归零** —— 图标自己那 12px 盒子的边已经是墨迹了，
 *    再留一点就是图标旁边白多出来的一截（那正是 2026-09-15 前后五轮"间隔太大"
 *    的根子）。归零还有个好处：可点矩形一直顶到图标墨迹，不留点不着的缝；
 *  - **竖向**：那是**点击区域的高度**，留着。一起去掉的话按钮会比这一行矮
 *    6px，可点的地方明显变小 —— 而这件事在截图上看不出来。
 *
 * 3px 是平台边框给的，不同 LAF、不同缩放下未必是这个数，所以**读出来再用**，
 * 不写死。
 *
 * ## 尺寸为什么必须自己钉死（2026-09-15 第五轮，也是前四轮全部落空的原因）
 *
 * New UI 的 LAF 给**每一个 `JButton`** 兜了一个 72px 的最小宽度。前四轮改的都是
 * "图标之间的白"（内边距、字形盒、间距常量），而这 72px 是**盒子的最小宽度** ——
 * 改多少轮都没用：两个图标各自待在一个 72px 宽的透明盒子里居中，屏幕上就是
 * **中间空着 62px**。用户量的那个数（＋ 的墨迹 1763..1774、⚙ 的 1837..1848）
 * 只有按"盒子 72px、图标居中"才解释得通。
 *
 * 量出来的对照表（测试侧 `IdeLaf` 的注释里也抄了一份）：
 *
 * | LAF | 本类的 preferred.width |
 * |---|---|
 * | Metal（**测试 JVM 默认**） | 12 |
 * | Darcula / IntelliJ（New UI = 真机） | **72** |
 *
 * 所以前四轮的探头图一直是 2px、全绿，而真机是 62px —— **探头跑在一个哪个 IDE
 * 都不用的 LAF 下**。现在探针与几何单测都改用真机那一套（测试侧 `IdeLaf`）。
 */
internal open class TopRowIconButton(glyph: TopRowGlyph) : JButton() {

    init {
        icon = TopRowIcon(glyph)
        iconTextGap = 0
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // 先读后改：改完再读就是 0 了
        val vPad = insets.top
        // 横向 0：按钮的左右边界就落在图标墨迹上 —— 于是"图标旁边有多白"只由
        // 布局那一层的间距决定（见 buildTopRow），不在这里凭空长出来
        border = JBUI.Borders.empty(vPad, 0)
    }

    /**
     * 宽度就是图标宽度 —— **LAF 说什么都不算**。
     *
     * 不覆盖的话 New UI 会给它 72px（见类注释那张表），而图标是画满整格的，
     * 于是两个图标之间白出 62px。横向内边距已经是 0，所以这里只要把宽度钉在
     * 图标上就行；高度仍按内边距给足，那是**可点区域**，不能一起削掉。
     */
    override fun getPreferredSize(): Dimension {
        val glyphIcon = icon ?: return super.getPreferredSize()
        return Dimension(glyphIcon.iconWidth, glyphIcon.iconHeight + insets.top + insets.bottom)
    }

    /** 最小 = 首选：被挤也不缩，两个图标才始终一样宽。 */
    override fun getMinimumSize(): Dimension = preferredSize

    /** 最大 = 首选：BoxLayout 里有富余时不许把它拉宽 —— 拉宽就是把图标推离邻居。 */
    override fun getMaximumSize(): Dimension = preferredSize
}

/**
 * 右上角的齿轮。
 *
 * 与「＋」的唯一差别是它**不随忙闲置灰** —— 它开的是设置对话框，而对话框只
 * 读写配置、不碰会话（见 `ClaudePanel.openModelSettings`），会话进行中
 * 也该能开。
 */
internal fun settingsGearButton(onClick: () -> Unit): JButton =
    TopRowIconButton(TopRowGlyph.Gear).apply {
        foreground = UIUtil.getLabelForeground()
        toolTipText = "设置"
        addActionListener { onClick() }
    }
