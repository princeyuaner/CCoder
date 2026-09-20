package com.ccoder.settings

import com.ccoder.ui.CARD_CORNER_ARC
import com.ccoder.ui.RoundedLineBorder
import com.ccoder.ui.lineColor
import com.ccoder.ui.mix
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 卡片式设置页的三件套：**卡片壳 / 一行 / 一组行**（2026-09-20，用户从四个方案里选的 B）。
 *
 * ## 为什么卡片要自己画底
 *
 * 底由 [CardPanel.paintComponent] 里的 `fillRoundRect` 画、边由 [RoundedLineBorder] 的
 * `drawRoundRect` 画 —— **两者必须同一个 arc**，否则四个角上会露出"底比边圆"的一圈毛刺
 * （`StatusCardView` 与 `ComposerCard` 各踩过一次，两边注释都记着）。所以这里不另创几何：
 * arc 取 `CARD_CORNER_ARC`，与输入卡、状态卡**共用一个数**；描边色取 [lineColor]，
 * 与转写区那些分界线同一支灰。
 *
 * 卡片底取 [cardFill]：面板底往 LabelForeground 混 [CARD_TINT]。**方向不用判明暗** ——
 * 深色主题的前景色是亮的（卡比页底亮一档）、浅色是暗的（卡比页底暗一档），两边都成立
 * （同 [com.ccoder.ui.FOOTER_TINT] 那条道理）。页底与卡底只差这一点点，卡片靠的是
 * **边 + 圆角**读出来，不是靠色差堆。
 */

/** 卡内左右内边距。 */
internal const val CARD_PAD_H = 14

/** 一行里标签列的宽度（默认值；标签长的那几页用 [alignLabelColumns] 统一放宽）。 */
internal const val CARD_LABEL_WIDTH = 176

/** 两栏页里卡沿与栏沿之间留的缝 —— 两张卡之间看起来就是它的两倍。 */
internal const val CARD_INSET = 6

/**
 * 卡片底比面板底偏多少。
 *
 * 0.05 是"看得出这是一层，但不像另一块面板"：0.03 几乎看不见，0.09（输入卡底带那个数）
 * 在卡片这么大面积上就开始抢了。改它要重新出图看（`SettingsDialogProbe`）。
 */
internal const val CARD_TINT = 0.05

/** 卡片底。 */
internal fun cardFill(): Color =
    mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), CARD_TINT)

/**
 * 一张卡。
 *
 * **高度永远等于当前的 `preferredSize`**（见 [getMaximumSize]）：放进 `BoxLayout` 的页里，
 * 给 `Int.MAX_VALUE` 它就变成一根弹簧，把后面的卡顶出可视区 —— `modelListBox` /
 * `tableBox` / `logBox` 各踩过一次，那三处都是"加完子件之后再量、再封顶"。
 * 这里把封顶写成**动态的**：页里的卡会随内容重建（列表加一条、状态刷新），
 * 一次性封顶会留下一个陈旧的高度。
 */
internal class CardPanel : JPanel(BorderLayout()) {

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(CARD_CORNER_ARC)
            g2.color = cardFill()
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    init {
        isOpaque = false
        border = RoundedLineBorder(::lineColor, JBUI.scale(CARD_CORNER_ARC))
        // 页里的列是 BoxLayout（Y），交叉轴按 alignmentX 摆 —— 不压到 0，卡会各自居中
        alignmentX = Component.LEFT_ALIGNMENT
    }
}

/**
 * 行里那一列标签。取它只为 [alignLabelColumns] 对齐用。
 *
 * 存在 client property 里而不是问 `GridBagLayout.getLayoutComponent(...)`：后者按
 * 约束对象反查组件，语义（比什么、怎么比）随 JDK 版本有出入 —— 一列宽度而已，
 * 不值得把版面押在它上面。
 */
private const val LABEL_COLUMN_PROP = "ccoder.settings.labelColumn"

/**
 * 拼一张卡：卡头（可选）+ 卡身 + 卡脚（可选）。
 *
 * 三段各自带自己的内边距，卡身**不加** —— 行自己带（见 [settingsRow]），
 * 因为"列表卡"那种身子要的是另一种留白。
 */
internal fun settingsCard(title: String?, body: JComponent, footer: JComponent? = null): CardPanel =
    CardPanel().apply {
        if (title != null) {
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = BorderFactory.createCompoundBorder(
                        hairlineBottom(),
                        JBUI.Borders.empty(7, CARD_PAD_H),
                    )
                    add(JBLabel(title).apply { foreground = UIUtil.getLabelForeground() }, BorderLayout.WEST)
                },
                BorderLayout.NORTH,
            )
        }
        add(body, BorderLayout.CENTER)
        if (footer != null) {
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = BorderFactory.createCompoundBorder(
                        hairlineTop(),
                        JBUI.Borders.empty(6, CARD_PAD_H),
                    )
                    add(footer, BorderLayout.CENTER)
                },
                BorderLayout.SOUTH,
            )
        }
    }

/**
 * 卡里的一行：**标签（说明挂在它下面）在左、控件在右**。
 *
 * ## 控件必须是这一行的直接子件
 *
 * `McpSettingsPageTest` 有一条断的是 `inputOf(...).parent.isVisible` —— 页面切「形状」时
 * 收起/放出的是**这一行**（[settingsRow] 的返回值），所以控件不能塞进中间那层壳里，
 * 否则"收起来了"这件事在那个断言上永远看不到。定位器（`FieldLookup.kt`）也是靠
 * 这一条从标签上溯到行、再取行里的输入件。
 *
 * ## 为什么用 GridBagLayout
 *
 * 只要两件事：标签列**定宽**、控件**横向占满剩下的**、两者**垂直居中**。
 * `BoxLayout` 在交叉轴上按 alignment 摆、还会把子件往 maximumSize 撑（仓库里几处
 * `alignmentX` 的教训都是它），这里用 GridBag 把这三件事一次说清，不留走样的余地。
 *
 * @param labelWidth 标签列宽度。同一张卡里的几行要用同一个数，否则控件不成一条竖线 ——
 *   标签长短不齐时先用 [alignLabelColumns] 统一，再交给 [cardRows]。
 */
internal fun settingsRow(
    label: String,
    control: JComponent,
    hint: String? = null,
    labelWidth: Int = CARD_LABEL_WIDTH,
): JPanel {
    val labelCol = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(JBLabel(label).apply { alignmentX = Component.LEFT_ALIGNMENT })
        if (hint != null) {
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(wrappedHint(hint, labelWidth))
        }
        // 宽度定死：GridBag 里 weightx = 0 的那一格就按 preferredSize 摆
        preferredSize = Dimension(JBUI.scale(labelWidth), preferredSize.height)
        maximumSize = Dimension(JBUI.scale(labelWidth), preferredSize.height)
    }

    return JPanel(GridBagLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(7, CARD_PAD_H)
        putClientProperty(LABEL_COLUMN_PROP, labelCol)
        add(
            labelCol,
            GridBagConstraints().apply {
                gridx = 0
                weightx = 0.0
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, 0, JBUI.scale(12))
            },
        )
        add(
            control,
            GridBagConstraints().apply {
                gridx = 1
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            },
        )
    }
}

/**
 * 把一组行的标签列统一到**其中最宽的那条**。
 *
 * 不统一的话：`claude 可执行文件`（7 字）与 `没有选中配置时用的模型`（12 字）两行
 * 的控件会各自起步，一列里控件左沿参差 —— 而标签**不许截断**（截了就是丢字，
 * 比参差更糟）。所以宽度按最长的那条量，不是按一个拍脑袋的常数。
 */
internal fun alignLabelColumns(vararg rows: JComponent) {
    val cols = rows.mapNotNull { it.getClientProperty(LABEL_COLUMN_PROP) as? JComponent }
    val widest = cols.maxOfOrNull { it.preferredSize.width } ?: return
    cols.forEach { it.preferredSize = Dimension(widest, it.preferredSize.height) }
}

/**
 * 把几行拼成卡身：行与行之间一条发丝线（第一行不画 —— 上面就是卡头那条）。
 *
 * 行自己的内边距照旧留着，这里只是**再包一层线**。
 */
internal fun cardRows(vararg rows: JComponent): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    rows.forEachIndexed { index, row ->
        if (index > 0) {
            row.border = BorderFactory.createCompoundBorder(hairlineTop(), row.border)
        }
        add(row)
    }
}

/**
 * 两栏页里的**一栏**：把卡摆在栏内、四周留 [CARD_INSET] 的缝。
 *
 * 栏宽（`MODEL_LIST_WIDTH` 那类常量）与栏的 x 一个都不动 —— 卡片之间的间隙是
 * **在栏内缩进**换来的，于是"两栏各占自己的宽度、谁也不压谁"那条几何断言
 * （`SettingsDialogTest`）照旧成立。
 */
internal fun settingsCardColumn(card: JComponent): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(CARD_INSET)
    add(card, BorderLayout.CENTER)
}
