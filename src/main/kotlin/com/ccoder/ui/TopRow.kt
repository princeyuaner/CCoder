package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
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
 * 右上角两个图标按钮之间的间距 —— **就是看得见的那一段白**。
 *
 * 两个按钮都不画边框底、横向内边距为 0，图标又是自绘的（见 [TopRowIcon]），
 * 盒子的边界即墨迹。于是"两个图标隔多远"与"两个按钮的矩形隔多远"成了同一件事，
 * 全由这一个常量说了算：单测里那句
 * `gear.x - (plus.x + plus.width) == TOP_ROW_GAP` 量的就是用户眼睛看到的那一段。
 * **想再调，只改这一个数**；别在按钮内边距或别处再添白。
 *
 * ## 这段间距改过四次，每次的教训都不一样
 *
 * - **2026-09-14**：用户说"间隔太大"。那 6px 不是这里给的 —— 两个按钮各自
 *   默认边框带着 3px 内边距，边框画都不画（`isBorderPainted = false`），
 *   内边距却照样占着。横向那部分去掉，改由这个常量显式给。
 * - **2026-09-15 上午**：还是"太宽"，于是收到 0。仍然宽 —— 因为剩下的白在
 *   **字体**里：全角 U+FF0B 的「＋」字形盒 15.5px，真画出来的十字只有 11.7px，
 *   两侧各 1.9px，而且**跟着字体与 DPI 放大**。
 * - **2026-09-15 下午**：按墨迹去裁按钮宽度 —— **错了**。JButton 判"放不下"
 *   用的是**文字排版宽度**（advance），不是墨迹；裁到 13.7px 之后它把「＋」
 *   画成了「…」。探头图上看得清清楚楚，可惜当时只量了数字没看图。
 * - **自绘落地之后**：用户仍说"宽" —— 自绘确实把字体那层不可控的白拿掉了，
 *   可按钮自己还留着 1px 横向内边距**×2**，加上这 2px，墨迹之间仍是 4px。
 *   于是把内边距也归零：白只剩这一处出处，从 4 收到 **2**（量过 0/1/2/3/4
 *   五张候选：0 两个图形粘成一团，1 偏挤，2 还认得出是并排的两个图标）。
 */
internal val TOP_ROW_GAP: Int = JBUI.scale(2)

/** 图标盒边长。与字体无关 —— 它就是个固定的框。 */
private const val ICON_SIDE = 12

/** 图标描边宽度。与 [RoundSendButton] 那几个自绘图形同一档，粗细才一致。 */
private const val ICON_STROKE = 1.5f

/**
 * 顶部一行：会话标签占满中间（长标题自己打省略号），两个图标按钮在最右。
 *
 * 抽成独立函数是为了可测 —— [ClaudePanel] 依赖 `Project`，起不了单测，
 * 而"这两个按钮挨多近""谁在左"恰好是这里改过三次的两件事。探针
 * [TopRowRenderProbe] 画的也是这一份，不是画一份长得像的。
 *
 * **顺序：「＋」在左、齿轮占最右角**（2026-09-14 用户要求对调，原为齿轮在左）。
 * 顺序写进这个函数而不是留给调用处，是为了让上面那句话**能被测到** ——
 * 调用处只有一行，测不着。
 */
internal fun buildTopRow(
    sessionLabel: JComponent,
    settingsButton: JComponent,
    newSessionButton: JComponent,
): JPanel = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(4, 8)
    add(sessionLabel, BorderLayout.CENTER)
    add(
        JPanel().apply {
            // X_AXIS 且容器宽度就给首选宽（BorderLayout 的 EAST 槽）：
            // 两个按钮不会被拉伸，所以"挨多近"完全由下面的间距说了算
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(newSessionButton)
            add(Box.createHorizontalStrut(TOP_ROW_GAP))
            add(settingsButton)
        },
        BorderLayout.EAST,
    )
}

/** 自绘图标的两种形状。 */
internal enum class TopRowGlyph { Plus, Gear }

/**
 * 两个按钮上画的图形。**自绘，不用字形**。
 *
 * 用字符（「＋」U+FF0B / 「⚙」U+2699）的代价在 [TOP_ROW_GAP] 顶上写着：
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
 *    再留一点就是两个图标之间白多出来的那一截（见 [TOP_ROW_GAP] 第四轮）。
 *    归零还有个好处：两个按钮的可点矩形**紧挨着**，中间不留一条点不着的缝；
 *  - **竖向**：那是**点击区域的高度**，留着。一起去掉的话按钮会比这一行矮
 *    6px，可点的地方明显变小 —— 而这件事在截图上看不出来。
 *
 * 3px 是平台边框给的，不同 LAF、不同缩放下未必是这个数，所以**读出来再用**，
 * 不写死。
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
        // 横向 0：按钮的左右边界就落在图标墨迹上，图标之间的白只剩 TOP_ROW_GAP
        border = JBUI.Borders.empty(vPad, 0)
    }
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
