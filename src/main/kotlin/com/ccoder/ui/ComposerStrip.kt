package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** 段与段之间的分隔。带空格是为了让截断后的尾部仍然以"│ ● 2"结尾。 */
private const val SEPARATOR = " │ "

/** 展开指示符。它不在预算之内 —— 挤掉它这条就看不出来能点了。 */
private const val CARET = " ▾"

/**
 * 组装条上的文字。
 *
 * **宽度函数是注入的**：单测里用"每字 10px"的假函数，结论就不依赖运行环境
 * 装了什么字体。生产路径传 `FontMetrics::stringWidth`。
 *
 * 让位顺序是这条的核心：**计数绝不掉，名字先截，截不下就整段消失。**
 * 留一个"修…"比不留更糟 —— 省略号传达不了任何信息，而计数已经说清了
 * 关键的部分。
 */
internal fun runStripText(
    strip: RunStrip,
    budgetPx: Int,
    widthOf: (String) -> Int,
): String {
    val head = strip.todoProgress?.let { "◐ $it" }
    val tail = strip.runningCount.takeIf { it > 0 }?.let { "● $it" }

    // 不含名字的骨架：无论如何都要留住的部分
    val skeleton = listOfNotNull(head, tail).joinToString(SEPARATOR)
    if (head == null) return skeleton

    val name = strip.currentTask ?: return skeleton
    val withName = listOfNotNull("$head $name", tail).joinToString(SEPARATOR)
    if (widthOf(withName) <= budgetPx) return withName

    val nameBudget = budgetPx - widthOf(skeleton) - widthOf(" ")
    val shortened = ellipsize(name, nameBudget, widthOf)
    if (shortened.isEmpty()) return skeleton

    return listOfNotNull("$head $shortened", tail).joinToString(SEPARATOR)
}

/**
 * 按像素预算截断，放不下时以省略号结尾。
 *
 * 连省略号都放不下就返回空串：画半个省略号比什么都不画更难看，
 * 而且它同样传达不了"这里还有内容"。
 */
internal fun ellipsize(text: String, budgetPx: Int, widthOf: (String) -> Int): String {
    if (budgetPx <= 0) return ""
    if (widthOf(text) <= budgetPx) return text

    val ellipsis = "…"
    val ellipsisWidth = widthOf(ellipsis)
    if (ellipsisWidth > budgetPx) return ""

    val out = StringBuilder()
    var used = 0
    for (ch in text) {
        val w = widthOf(ch.toString())
        if (used + w + ellipsisWidth > budgetPx) break
        out.append(ch)
        used += w
    }
    return out.append(ellipsis).toString()
}

/**
 * 详情浮层该出现在哪个 y（屏幕坐标）。
 *
 * 锚点（这条）本来就在工具窗口最底部 —— 它下面就是窗口边缘，再往下是屏幕边缘，
 * 所以**向上弹是常态而不是边角情况**。
 *
 * 自己算而不用平台的 `showUnderneathOf`：那个方法会不会在下方没空间时自动
 * 翻转，没有验证过。算错的后果是浮层跑到屏幕外，与"希望平台帮忙"相比，
 * 自己算至少是看得见的。
 */
internal fun popupAnchorY(
    anchorTop: Int,
    anchorHeight: Int,
    popupHeight: Int,
    screenTop: Int,
    screenBottom: Int,
    gap: Int,
): Int {
    val below = anchorTop + anchorHeight + gap
    if (below + popupHeight <= screenBottom) return below

    val above = anchorTop - gap - popupHeight
    if (above >= screenTop) return above

    // 上下都放不下（任务很多时浮层可以比屏幕还高）：贴屏幕顶。
    // 贴底会让标题看不见，而标题是"这是什么"的唯一线索
    return screenTop
}

/**
 * 上下文右边那一条。点开看详情。
 *
 * ## 为什么自己画而不是用 JLabel
 *
 * 条的文字要按可用宽度截断，而 **JLabel 的首选宽度又由文字决定** ——
 * 让它自己算就成了"文字靠宽度、宽度靠文字"的循环。第一版正是这么写的，
 * 结果是初始化时文字为空、首选宽度只剩内边距，再按这个宽度算文字，
 * 界面上表现为右上角一个空的小圆点。
 *
 * 拆开的办法是：**[getPreferredSize] 永远按完整文字给，截断只发生在
 * [paintComponent]**。宽度不再参与自己的计算，循环就断了。
 *
 * 自己画的另一个好处是胶囊描边和悬停态都在手上，不必去和 LookAndFeel 搏斗。
 *
 * 可见性由 [setStrip] 决定：没有任务时**整条不出现**，而不是显示一个
 * "0/0"的常驻空条 —— 那会占着位置还说假话。
 */
internal class RunStripView(private val onOpen: () -> Unit) : JComponent() {

    private var strip: RunStrip? = null
    private var open = false
    private var hovered = false

    init {
        isVisible = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // 显式设字体。不设的话 getFont() 在无父组件时返回 null（首选宽度直接 NPE），
        // 而且 getPreferredSize 与 paintComponent 会用两个不同的字体去量同一段文字 ——
        // 量出来和画出来的不一样，截断位置就会跳
        font = UIUtil.getLabelFont()
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (isVisible) onOpen()
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

    /** 不叫 update：那会与 [java.awt.Component.update] 撞名，传 null 时无法解析。 */
    fun setStrip(next: RunStrip?) {
        strip = next
        isVisible = next != null
        revalidate()
        repaint()
    }

    fun setOpen(value: Boolean) {
        if (open == value) return
        open = value
        repaint()
    }

    /**
     * **只看完整文字，不看自己的实际宽度。** 这是打断循环的那一刀。
     *
     * 上限取父容器宽度的一个比例，是为了让条在窄栏里不会一路顶到左边 ——
     * 到那时截断交给绘制去做，而计数在任何宽度下都留着。
     */
    override fun getPreferredSize(): Dimension {
        val s = strip ?: return Dimension(0, 0)
        val fm = getFontMetrics(font)
        val natural = fm.stringWidth(fullText(s, fm)) + PAD_LEFT + PAD_RIGHT
        val cap = parent?.width?.takeIf { it > 0 }?.let { (it * MAX_WIDTH_FRACTION).toInt() }
        return Dimension(if (cap != null) minOf(natural, cap) else natural, fm.height + PAD_V * 2)
    }

    override fun paintComponent(g: Graphics) {
        val s = strip ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val lit = open || hovered
            g2.color = if (lit) focusColor() else lineColor()
            g2.stroke = BasicStroke(1f)
            // 圆角半径取高度的一半 = 胶囊
            g2.drawRoundRect(0, 0, width - 1, height - 1, height, height)

            val fm = g2.fontMetrics
            val shown = runStripText(s, width - PAD_LEFT - PAD_RIGHT - fm.stringWidth(CARET), fm::stringWidth) + CARET
            g2.color = if (lit) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
            g2.drawString(shown, PAD_LEFT, (height - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }

    private fun fullText(s: RunStrip, fm: FontMetrics): String =
        runStripText(s, Int.MAX_VALUE - fm.stringWidth(CARET), fm::stringWidth) + CARET

    private companion object {
        val PAD_LEFT: Int get() = JBUI.scale(8)
        val PAD_RIGHT: Int get() = JBUI.scale(8)
        val PAD_V: Int get() = JBUI.scale(2)

        /** 条最多占这一行的这个比例，剩下的留给用量。 */
        const val MAX_WIDTH_FRACTION = 0.72
    }
}
