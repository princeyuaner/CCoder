package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import com.intellij.util.ui.UIUtil
import java.awt.event.MouseEvent
import javax.swing.JLabel

/** 一张卡的外观与交互。 */
class StatusCardViewTest {

    private fun labelsIn(root: Component): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is JLabel) out += c.text
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out
    }

    /**
     * 描边色。卡片的 border 是 **CompoundBorder**（圆角描边 + 内边距），
     * 所以要先剥一层 —— 与 `ComposerRulesTest` 里读输入卡描边同一套写法。
     */
    private fun borderColorOf(card: StatusCardView) =
        ((card.border as javax.swing.border.CompoundBorder).outsideBorder as RoundedLineBorder).color()

    private fun hover(c: Component, entered: Boolean) {
        c.dispatchEvent(
            MouseEvent(
                c,
                if (entered) MouseEvent.MOUSE_ENTERED else MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(), 0, 5, 5, 0, false,
            )
        )
    }

    private val busy = StatusCardModel(
        label = "子任务",
        value = "3/7",
        indicator = Indicator.Segments(done = 3, total = 7),
    )

    // ---- 内容 ----

    @Test
    fun `标签、值、副值都画出来`() {
        val card = StatusCardView()
        card.setModel(busy.copy(sub = "12.3k / 200k"))

        val texts = labelsIn(card)
        assertTrue("子任务" in texts, "少了标签：$texts")
        assertTrue("3/7" in texts, "少了值：$texts")
        assertTrue("12.3k / 200k" in texts, "少了副值：$texts")
    }

    @Test
    fun `没有副值时不留空行`() {
        val card = StatusCardView()
        card.setModel(busy)

        assertTrue(
            card.components.none { it is JLabel && it.text.isEmpty() && it.isVisible },
            "空的副值标签该整个不可见，否则会撑出一行空气",
        )
    }

    // ---- 收边 ----

    @Test
    fun `收边的卡不画边框`() {
        // quiet 不是"隐藏"，是"退到背景里"。用全透明描边而不是换 border 对象 ——
        // 换了对象 insets 会变，四张卡的宽度就会跟着跳
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "子任务", value = CARD_IDLE_TEXT, quiet = true))

        assertEquals(0, borderColorOf(card).alpha, "收边的卡仍然画了边框")
    }

    @Test
    fun `收边与否不改变 insets —— 否则四张卡会左右跳`() {
        val card = StatusCardView()
        card.setModel(busy)
        val busyInsets = card.border.getBorderInsets(card)

        card.setModel(StatusCardModel(label = "子任务", value = CARD_IDLE_TEXT, quiet = true))

        assertEquals(busyInsets, card.border.getBorderInsets(card), "收边改了 insets，布局会跳")
    }

    @Test
    fun `平常的卡画得出边框`() {
        val card = StatusCardView()
        card.setModel(busy)

        assertTrue(borderColorOf(card).alpha > 0, "有内容的卡该看得见边框")
    }

    // ---- 交互 ----

    @Test
    fun `可点的卡悬停时提亮`() {
        val card = StatusCardView(onOpen = {})
        card.setModel(busy)
        val calm = borderColorOf(card)

        hover(card, entered = true)

        assertNotEquals(calm, borderColorOf(card), "可点的卡悬停后该提亮")
    }

    @Test
    fun `不可点的卡悬停不动声色`() {
        // 给了悬停反馈却点不动，是在骗人
        val card = StatusCardView(onOpen = null)
        card.setModel(busy)
        val calm = borderColorOf(card)

        hover(card, entered = true)

        assertEquals(calm, borderColorOf(card), "点不动的卡不该有悬停反馈")
    }

    @Test
    fun `点可点的卡触发回调`() {
        var opened = 0
        val card = StatusCardView(onOpen = { opened++ })
        card.setModel(busy)

        card.dispatchEvent(
            MouseEvent(card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
        )

        assertEquals(1, opened)
    }

    @Test
    fun `展开态即使移开鼠标也保持提亮`() {
        val card = StatusCardView(onOpen = {})
        card.setModel(busy)
        card.setOpen(true)

        hover(card, entered = true)
        hover(card, entered = false)

        assertTrue(card.isOpen())
        assertNotEquals(lineColor(), borderColorOf(card), "展开态该保持提亮")
    }

    @Test
    fun `不可点的卡点下去没有反应`() {
        val card = StatusCardView(onOpen = null)
        card.setModel(busy)

        // 不抛异常就是通过 —— 这条守的是"别为了好写而给个空 lambda"
        card.dispatchEvent(
            MouseEvent(card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false)
        )
    }

    // ---- 指示器 ----

    @Test
    fun `换了指示器类型之后仍然只有一个指示器`() {
        val card = StatusCardView()
        card.setModel(busy)
        assertEquals(1, indicatorCount(card))

        card.setModel(busy.copy(indicator = Indicator.Dots(2)))
        assertEquals(1, indicatorCount(card), "换了指示器类型后多出来一个")
    }

    @Test
    fun `没有指示器时那一块不可见`() {
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "连接", value = "已连接"))

        // 直接找 IndicatorView。写成 filterIsInstance<JPanel> 会永远为真 ——
        // IndicatorView 继承 JComponent 而不是 JPanel，那种写法是条假测试
        val indicator = card.components.filterIsInstance<IndicatorView>().single()
        assertFalse(indicator.isVisible, "没有指示器时不该占着一块地方")
    }

    @Test
    fun `带状态点的卡把点画出来，不带的不画`() {
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "连接", value = "已连接", leadingDot = true))
        assertTrue(dotOf(card).isVisible, "连接卡该有状态点")

        card.setModel(busy)
        assertFalse(dotOf(card).isVisible, "子任务卡不该有状态点")
    }

    @Test
    fun `状态点的颜色跟着色调走`() {
        // 点存在的**唯一**理由就是让 Tone 看得见。颜色不跟着变的话，
        // "已连接"和"会话已断开"在界面上还是一模一样
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "连接", value = "已连接", tone = Tone.Ok, leadingDot = true))
        val ok = dotOf(card).color()

        card.setModel(
            StatusCardModel(label = "连接", value = "会话已断开", tone = Tone.Danger, leadingDot = true)
        )
        val danger = dotOf(card).color()

        assertNotEquals(ok, danger, "换了色调，点的颜色没变")
        assertNotEquals(ok, UIUtil.getInactiveTextColor(), "Ok 不该是灰的")
    }

    /** 点嵌在"值那一行"里面，所以要递归找。 */
    private fun dotOf(c: Container): ToneDotView {
        c.components.filterIsInstance<ToneDotView>().firstOrNull()?.let { return it }
        for (child in c.components) {
            if (child is Container) {
                val found = runCatching { dotOf(child) }.getOrNull()
                if (found != null) return found
            }
        }
        error("这棵树里没有状态点")
    }

    private fun indicatorCount(c: Container): Int {
        var n = 0
        for (child in c.components) {
            if (child is IndicatorView) n++
            if (child is Container) n += indicatorCount(child)
        }
        return n
    }
}
