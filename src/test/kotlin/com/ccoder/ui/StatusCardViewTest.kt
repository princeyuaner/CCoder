package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        label = "任务列表",
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
        assertTrue("任务列表" in texts, "少了标签：$texts")
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
        // 2026-09-14 用户明确要求：任务列表 / 子代理没数据时边框照常要有，
        // 四张卡看着是一排。quiet 只把值与图标压暗
        val card = StatusCardView()
        card.setModel(StatusCardModel(label = "任务列表", value = CARD_IDLE_TEXT, quiet = true))

        assertTrue(borderColorOf(card).alpha > 0, "收边的卡不该把边框收掉")
    }

    @Test
    fun `收边与否不改变 insets —— 否则四张卡会左右跳`() {
        val card = StatusCardView()
        card.setModel(busy)
        val busyInsets = card.border.getBorderInsets(card)

        card.setModel(StatusCardModel(label = "任务列表", value = CARD_IDLE_TEXT, quiet = true))

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
    // ---- 动作图标（2026-09-17）----

    /**
     * 悬停 / 移开，**只喂我们自己的监听器**。
     *
     * 不走 `dispatchEvent`：设过 tooltip 的组件会被 `ToolTipManager` 注册成一个
     * 监听器（`JComponent.setToolTipText` 的副作用），它一收到**移出**事件就起一个
     * `javax.swing.Timer`（dismiss 延迟），平台的测试夹具在收尾时把它判成
     * "Not disposed javax.swing.Timer" —— 2026-09-17 那两条"移开还原"的用例
     * 就是这么红的（只悬停进入、不移出的用例全绿，因为注册发生在进入那一刻之后）。
     *
     * 要验的是我们的监听器，平台那层 tooltip 定时器不该被拖进来。
     */
    private fun hoverOurListeners(c: Component, entered: Boolean) {
        val e = MouseEvent(
            c,
            if (entered) MouseEvent.MOUSE_ENTERED else MouseEvent.MOUSE_EXITED,
            System.currentTimeMillis(), 0, 5, 5, 0, false,
        )
        c.mouseListeners
            .filterNot { it.javaClass.name.startsWith("javax.swing.") }
            .forEach { if (entered) it.mouseEntered(e) else it.mouseExited(e) }
    }

    /**
     * 排版一次 —— 点击路由靠几何，没排版就没有 bounds。
     *
     * 97×50 是真实那一排里的格子尺寸（404px / 4，减间隙）。
     */
    private fun laidOut(card: StatusCardView): StatusCardView {
        card.setSize(97, 50)
        card.doLayout()
        card.components.filterIsInstance<Container>().forEach { it.doLayout() }
        return card
    }

    private fun clearAction(enabled: Boolean = true) =
        CardAction(CardActionKind.Clear, "清空会话", "提示", danger = true, enabled = enabled)

    private fun clickAt(card: StatusCardView, x: Int, y: Int) {
        card.dispatchEvent(
            MouseEvent(
                card, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, x, y, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    /** 点右上角那颗图标的正中心。 */
    private fun clickIcon(card: StatusCardView) {
        val b = card.actionIconBoundsForTest()
        clickAt(card, b.x + b.width / 2, b.y + b.height / 2)
    }

    /** 点卡片中下部 —— 离右上角那块远着，是"别处"。 */
    private fun clickElsewhere(card: StatusCardView) {
        clickAt(card, card.width / 2, card.height * 3 / 4)
    }

    /**
     * 把指针挪到某个点。
     *
     * 与 [hoverOurListeners] 同一个理由：只喂我们自己的监听器 ——
     * 走 `dispatchEvent` 会把事件送给 `ToolTipManager`，它随即起一个定时器，
     * 平台的测试夹具收尾时判成 "Not disposed javax.swing.Timer"。
     */
    private fun movePointer(card: StatusCardView, x: Int, y: Int) {
        val e = MouseEvent(card, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x, y, 0, false)
        card.mouseMotionListeners
            .filterNot { it.javaClass.name.startsWith("javax.swing.") }
            .forEach { it.mouseMoved(e) }
    }

    /** 卡片里的所有后代组件（含孙辈 —— 标签在行面板里）。 */
    private fun descendantsOf(root: Container): List<Component> {
        val out = mutableListOf<Component>()
        fun walk(c: Container) {
            for (child in c.components) {
                out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    @Test
    fun `值行永远显示模型的值 —— 动作不占它的位子`() {
        // "悬停时把值行换成按钮"是 2026-09-17 被用户否掉的那一版：
        // 指针往按钮去的路上，悬停就被子件偷走了（报回原文：「鼠标放在边框上
        // 按钮才出现，挪到中间就消失」）。现在动作常驻在右上角，值行不动
        val card = laidOut(StatusCardView(icon = CardIcon.Link, onAction = {}))
        card.setModel(connectionCardOf(ConnectionState.Connected))
        card.setAction(clearAction())

        hoverOurListeners(card, entered = true)

        assertEquals("已连接", card.valueTextForProbe(), "值行被动作顶掉了")
    }

    @Test
    fun `点中右上角那颗图标 → 执行动作`() {
        var cleared = 0
        val card = laidOut(StatusCardView(icon = CardIcon.Link, onAction = { cleared++ }))
        card.setModel(connectionCardOf(ConnectionState.Connected))
        card.setAction(clearAction())

        clickIcon(card)

        assertEquals(1, cleared)
    }

    @Test
    fun `点卡片别处 → 不执行动作`() {
        // 清空不设确认框的安全垫就是这条：误触得正好点中右上角那 16×16
        var cleared = 0
        val card = laidOut(StatusCardView(icon = CardIcon.Link, onAction = { cleared++ }))
        card.setModel(connectionCardOf(ConnectionState.Connected))
        card.setAction(clearAction())

        clickElsewhere(card)

        assertEquals(0, cleared)
    }

    @Test
    fun `灰着的动作点图标也不动`() {
        var cleared = 0
        val card = laidOut(StatusCardView(icon = CardIcon.Link, onAction = { cleared++ }))
        card.setModel(connectionCardOf(ConnectionState.Connected))
        card.setAction(clearAction(enabled = false))

        clickIcon(card)

        assertEquals(0, cleared, "灰着的图标不该能点 —— tooltip 里已经说清为什么")
    }

    @Test
    fun `有详情的卡：点图标是动作，点别处仍是详情`() {
        var compacted = 0
        var opened = 0
        val card = laidOut(
            StatusCardView(icon = CardIcon.Context, onOpen = { opened++ }, onAction = { compacted++ })
        )
        card.setModel(contextCardOf(ContextUsage(12300, 200000)))
        card.setAction(
            CardAction(CardActionKind.Compact, "压缩上下文", "提示", danger = false, enabled = true)
        )

        clickIcon(card)
        clickElsewhere(card)

        assertEquals(1, compacted, "图标那块该归动作")
        assertEquals(1, opened, "别处仍该开详情")
    }

    @Test
    fun `指针压在图标上才换成动作的 tooltip`() {
        val card = laidOut(StatusCardView(icon = CardIcon.Link, onAction = {}))
        card.setModel(connectionCardOf(ConnectionState.Connected).copy(sub = "12.3k / 200k"))
        card.setAction(clearAction())

        val icon = card.actionIconBoundsForTest()
        movePointer(card, icon.x + icon.width / 2, icon.y + icon.height / 2)
        assertTrue(card.isIconHotForProbe(), "指针在图标上，该判定为压着")
        assertEquals("提示", card.toolTipText, "压在图标上时该说动作的说明")

        movePointer(card, card.width / 2, card.height * 3 / 4)
        assertFalse(card.isIconHotForProbe())
        assertEquals("12.3k / 200k", card.toolTipText, "离开图标后该还原成副值")
    }

    @Test
    fun `后代组件一个 tooltip 都不许挂 —— 挂了就会把卡片的事件偷走`() {
        // 根因（2026-09-17 实测）：`JComponent.setToolTipText` 会把 `ToolTipManager`
        // 注册成**那个组件的鼠标监听器**，而 Swing 把事件派发给"最深的有监听器的
        // 组件"—— 子件一旦成为事件目标，指针移到卡片中间时卡片就收到 `mouseExited`。
        //
        // tooltip 本身照样弹：`ToolTipManager` 问的是 `event.getSource()`（那时正是
        // 卡片），不往父级找 —— 所以"只给卡片挂"既够用、又是唯一不打架的做法
        val card = laidOut(StatusCardView(icon = CardIcon.Link, onAction = {}))
        card.setModel(connectionCardOf(ConnectionState.Connected).copy(sub = "12.3k / 200k"))
        card.setAction(clearAction())

        descendantsOf(card).forEach { child ->
            assertNull(
                (child as javax.swing.JComponent).toolTipText,
                "子件挂了 tooltip，会偷走卡片的事件：${child.javaClass.simpleName}",
            )
            assertTrue(
                child.mouseListeners.isEmpty(),
                "子件成了鼠标事件目标：${child.javaClass.simpleName}",
            )
        }
    }

    // ---- 整卡水位（2026-09-17 用户从七个方案里挑的 B）----

    @Test
    fun `水位线的高度：0 是空、1 是满、出界也不画到卡片外面`() {
        assertEquals(56, waterTopY(0.0, 56), "空 = 水面就是底边")
        assertEquals(0, waterTopY(1.0, 56), "满 = 顶边")
        // 56 - 0.34×56 = 36.96 → 37
        assertEquals(37, waterTopY(0.34, 56))
        assertEquals(56, waterTopY(-1.0, 56), "负数不该把水面抬到卡片外")
        assertEquals(0, waterTopY(2.0, 56))
    }

    @Test
    fun `水面是正弦：一个波长走完峰谷各一次`() {
        val base = 20.0
        val a = 1.5
        val len = 20.0

        assertEquals(base, waterSurfaceY(0, base, a, len, 0.0), 1e-9, "起点在基准线上")
        assertEquals(base - a, waterSurfaceY(5, base, a, len, 0.0), 1e-9, "四分之一波长 = 波峰（y 往上）")
        assertEquals(base, waterSurfaceY(10, base, a, len, 0.0), 1e-9, "半波长回中线")
        assertEquals(base + a, waterSurfaceY(15, base, a, len, 0.0), 1e-9, "四分之三 = 波谷")
        assertEquals(
            base + a,
            waterSurfaceY(5, base, a, len, 0.5),
            1e-9,
            "相位推半个周期：原来的峰正好变成谷",
        )
    }

    @Test
    fun `步进朝目标走，一步都不许越过`() {
        assertEquals(0.34, stepTowards(0.0, 0.34, 0.5), 1e-9, "一步能跨过去就**停在目标上**，别过头")
        assertEquals(0.2, stepTowards(0.0, 0.34, 0.2), 1e-9)
        assertEquals(0.7, stepTowards(0.9, 0.34, 0.2), 1e-9, "往下走同理")
        assertEquals(0.34, stepTowards(0.4, 0.34, 0.2), 1e-9, "差一点点就直接落到目标上")
        assertEquals(0.34, stepTowards(0.34, 0.34, 0.2), 1e-9, "已经在目标上就别动")
    }

    @Test
    fun `相位回绕在 0 到 1 之间`() {
        assertEquals(0.25, nextPhase(0.0, 0.25), 1e-9)
        assertEquals(0.05, nextPhase(0.95, 0.1), 1e-9, "越过 1 要绕回来")
        assertEquals(0.75, nextPhase(1.0, 0.75), 1e-9, "恰好整圈也是 0 起点")
    }

    @Test
    fun `上下文卡画出来：底部是水、顶部是卡底、左下角被圆角切掉`() {
        // 量像素而不是量属性 —— "水有没有漫出圆角""有没有画反方向"只有像素知道
        val card = StatusCardView(icon = CardIcon.Context)
        // **不调 settleWater**：这张卡还没上屏，第一帧就该是 34% ——
        // "没上屏时直接落到目标值"那条规矩就靠这一步量出来（测试 JVM 里
        // 卡片永远不是 showing，动画本来也不会起）
        card.setModel(contextCardOf(ContextUsage(usedTokens = 68_000, windowTokens = 200_000)))
        card.setSize(97, 56)

        val img = java.awt.image.BufferedImage(97, 56, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = UIUtil.getPanelBackground()
        g.fillRect(0, 0, 97, 56)
        card.paint(g)
        g.dispose()

        val water = mix(UIUtil.getPanelBackground(), focusColor(), 0.16)
        assertEquals(water, java.awt.Color(img.getRGB(48, 53)), "底部该是水")
        assertEquals(
            UIUtil.getPanelBackground(),
            java.awt.Color(img.getRGB(48, 5)),
            "水位在 34%，顶上不该有水",
        )
        // 圆角半径是 8（`drawRoundRect` 那对参数是**直径**，不是半径）——
        // 于是"角上被切掉"的那一块只有几个像素，取样点得贴着最角上那一个
        assertEquals(
            UIUtil.getPanelBackground(),
            java.awt.Color(img.getRGB(0, 55)),
            "左下角落在圆角之外 —— 水位该被裁掉",
        )
    }

    @Test
    fun `带水位的卡标签用正文色，其余三张照旧是灰的`() {
        // 卡片被染色之后，灰标签在浅色主题下只有 3.5–3.9:1（选型台量过，spec §3）
        val context = StatusCardView(icon = CardIcon.Context)
        context.setModel(contextCardOf(ContextUsage(usedTokens = 68_000, windowTokens = 200_000)))
        val tasks = StatusCardView(icon = CardIcon.Tasks)
        tasks.setModel(todoCardOf(TaskList(emptyList())))

        val contextLabel = descendantsOf(context).filterIsInstance<JLabel>().first { it.text == "上下文" }
        val tasksLabel = descendantsOf(tasks).filterIsInstance<JLabel>().first { it.text == "任务列表" }

        assertEquals(UIUtil.getLabelForeground(), contextLabel.foreground)
        assertEquals(UIUtil.getInactiveTextColor(), tasksLabel.foreground)
    }
}
