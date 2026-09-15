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
    fun `标签与值画在卡面上，副值改挂 tooltip`() {
        // 2026-09-14 改版：卡面从四层收到两行，让出去的就是副值那一行。
        // 搬走不等于弄丢 —— 悬停能看到
        val card = StatusCardView()
        card.setModel(busy.copy(sub = "12.3k / 200k"))

        val texts = labelsIn(card)
        assertTrue("子任务" in texts, "少了标签：$texts")
        assertTrue("3/7" in texts, "少了值：$texts")
        assertFalse("12.3k / 200k" in texts, "副值不该再占卡面一行")
        assertEquals("12.3k / 200k", card.toolTipText, "副值该挂在悬停提示上")
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
    fun `收边的卡也画边框与底 —— 那是"没数据"，不是"没有这格"`() {
        // 2026-09-14 用户明确要求：子任务 / 子代理没数据时边框照常要有，
        // 四张卡看着是一排。quiet 只把值与图标压暗
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "子任务", value = CARD_IDLE_TEXT, quiet = true))

        assertTrue(borderColorOf(card).alpha > 0, "收边的卡不该把边框收掉")
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

        // 递归找 IndicatorView。写成 filterIsInstance<JPanel> 会永远为真 ——
        // IndicatorView 继承 JComponent 而不是 JPanel，那种写法是条假测试。
        // （2026-09-14 起指示器外面套了一层 indicatorRow，直接子件里找不到它了）
        assertFalse(indicatorOf(card).isVisible, "没有指示器时不该占着一块地方")
    }

    @Test
    fun `指示器从无到有，卡片高度一动不动`() {
        // 2026-09-15 用户报"子代理有任务时高度会自己变高，把高度算好固定死"。
        // 四张卡的高度由 GridLayout 拉平到最高的那张，而指示器那一行的高度随类型
        // 变（无 0 / 比例条 2 / 分段 4 / 点阵 5）—— 于是"子代理从空闲变成 1"
        // 会让整排长高 5px，下面的转写区跟着跳。
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "子代理", value = CARD_IDLE_TEXT, quiet = true))
        val fixed = card.preferredSize.height

        for (indicator in listOf(
            Indicator.Meter(0.5),
            Indicator.Segments(done = 1, total = 3),
            Indicator.Dots(2),
        )) {
            card.setModel(busy.copy(indicator = indicator))
            assertEquals(fixed, card.preferredSize.height, "换成 $indicator 之后卡片高度变了")
        }

        // 反过来也要成立：从忙回到空闲，高度一样
        card.setModel(StatusCardModel(label = "子代理", value = CARD_IDLE_TEXT, quiet = true))
        assertEquals(fixed, card.preferredSize.height, "回到空闲之后高度变了")
    }

    @Test
    fun `一排点在自己那行里居中`() {
        // 这一行是铺满的（BoxLayout 只拉得满面板），所以居中靠绘制时的偏移。
        // 它在绘制里，从外面量不到 —— 算术抽成 pipsStartX 才钉得住
        assertEquals(40, pipsStartX(rowWidth = 100, contentWidth = 20))
        assertEquals(0, pipsStartX(rowWidth = 20, contentWidth = 20), "正好放满时不偏")
        assertEquals(0, pipsStartX(rowWidth = 10, contentWidth = 20), "内容比行宽时不许画到左边去")
    }

    /** 指示器嵌在它自己那一行里（[indicatorRow]），所以要递归找。 */
    private fun indicatorOf(c: Container): IndicatorView {
        c.components.filterIsInstance<IndicatorView>().firstOrNull()?.let { return it }
        for (child in c.components) {
            if (child is Container) {
                val found = runCatching { indicatorOf(child) }.getOrNull()
                if (found != null) return found
            }
        }
        error("这棵树里没有指示器")
    }

    @Test
    fun `图标的颜色跟着色调走 —— 三种连接状态就靠它区分`() {
        // 改版前这是一个前置状态点，现在并进了图标。颜色不跟着变的话，
        // "已连接""正在载入…""启动失败"在界面上还是一模一样
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "连接", value = "已连接", tone = Tone.Ok))
        val ok = iconOf(card).color()

        card.setModel(StatusCardModel(label = "连接", value = "会话已断开", tone = Tone.Danger))
        val danger = iconOf(card).color()

        assertNotEquals(ok, danger, "换了色调，图标的颜色没变")
        assertNotEquals(ok, UIUtil.getInactiveTextColor(), "Ok 不该是灰的")
    }

    @Test
    fun `收边的卡图标也变灰`() {
        val card = StatusCardView()
        card.setModel(busy.copy(quiet = true))

        assertEquals(UIUtil.getInactiveTextColor(), iconOf(card).color())
    }

    @Test
    fun `图标按构造参数画，不看数据`() {
        // 模型是"这一刻的数据"，图标是"这一格是什么"，两者寿命不同 ——
        // 所以图标由构造参数决定，换模型不会换图标
        val card = StatusCardView(icon = CardIcon.Context)
        card.setModel(busy)

        assertEquals(CardIcon.Context, iconOf(card).icon)
    }

    /** 图标嵌在"图标 + 标签"那一行里，所以要递归找。 */
    private fun iconOf(c: Container): CardIconView {
        c.components.filterIsInstance<CardIconView>().firstOrNull()?.let { return it }
        for (child in c.components) {
            if (child is Container) {
                val found = runCatching { iconOf(child) }.getOrNull()
                if (found != null) return found
            }
        }
        error("这棵树里没有图标")
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
