package com.ccoder.ui

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.LookAndFeel
import javax.swing.SwingUtilities

/**
 * 顶部那一行的排布：谁在左、挨多近、最右角归谁。
 *
 * 2026-09-14 用户改了这里的两件事（间隔太大 → 收紧；顺序对调）。两件都属于
 * "眼睛看得出来、单测看不懂"的那一类 —— 所以这里不测好看，只测**可测的那几件**：
 * 顺序、间距、以及"有没有藏起来的边距"（当初间隔变大，根子正是那个）。
 * 好不好看交给 [TopRowRenderProbe] 出图，人看一眼。
 *
 * ## 整个类跑在真机 LAF 下（2026-09-15 加）
 *
 * 前四轮"间隔还是宽"之所以一直没被这里挡住，是因为这些用例跑在测试 JVM 默认的
 * Metal LAF 下 —— 那里按钮首选宽就是图标宽，怎么量都是对的。**真机是 New UI**，
 * 它给所有 `JButton` 兜了一个 72px 的最小宽度：盒子被撑到 72px、图标居中，
 * 中间白出 62px，而上面这些断言一条都不会红。
 * 详见 [IdeLaf]（那里有 Metal / New UI 的对照表）。
 */
class TopRowTest {

    companion object {
        private var previous: LookAndFeel? = null

        @JvmStatic
        @BeforeAll
        fun useRealLaf() {
            previous = IdeLaf.install()
        }

        @JvmStatic
        @AfterAll
        fun restoreLaf() {
            IdeLaf.restore(previous)
        }
    }

    private class Laid(
        val row: JPanel,
        /** 那一排会话胶囊（2026-09-16 起顶行的开头那一格）。 */
        val chips: JComponent,
        val gear: JButton,
        val plus: JButton,
        /** 胶囊行 + 「＋」 + 弹簧 那一段。**齿轮不在里面**（它在 row 的 EAST 槽）。 */
        val left: JPanel,
    )

    /**
     * 建出真那一行并**排好版**。
     *
     * 不给尺寸就直接量 bounds 的话全是 0 —— 一条永远通过的断言比没有断言更糟。
     * 所以这里撑到真实宽度（工具窗口默认 420）再读坐标。
     */
    private fun laidOut(
        width: Int = 420,
        title: String? = "重构 extractor 的指纹计算",
        tabs: Int = 1,
    ): Laid {
        lateinit var out: Laid
        onEdt {
            val chips = SessionChips({}, {}).apply {
                render(
                    (0 until tabs).map { i ->
                        TabChip(
                            owner = JPanel(),
                            title = title,
                            state = TabState.Idle,
                            current = i == 0,
                            canClose = tabs > 1,
                        )
                    }
                )
            }
            val gear = settingsGearButton {}
            val plus = SessionNewButton {}
            val row = buildTopRow(chips, gear, plus)

            val outer = JPanel(BorderLayout()).apply { isOpaque = true }
            outer.add(row)
            outer.setSize(width, 200)
            // 排两遍：第一遍把 row 的首选高度定下来（它挂在 NORTH，
            // 高度取自 preferred），第二遍各子项才拿到真实尺寸
            layoutAll(outer)
            layoutAll(outer)

            out = Laid(row, chips, gear, plus, row.getComponent(0) as JPanel)
        }
        return out
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutAll(child)
    }

    /**
     * 组件在 row 坐标系里的左右边界。
     *
     * 不能写死一层偏移：胶囊与「＋」在 `left` 里（差一层），齿轮直接在 `row` 里。
     * `parent.x + x` 两种情况都对，而所有比较都发生在 row 内部，不用管 row 自己
     * 在 outer 里的位置。
     */
    private fun Laid.leftOf(c: Component) = (c.parent as Component).x + c.x
    private fun Laid.rightOf(c: Component) = leftOf(c) + c.width

    // ---- 顺序 ----

    @Test
    fun `「＋」紧跟在最后一个胶囊后面`() {
        // 用户原话（2026-09-16）：「新建会话的按钮应该时刻跟随在最后一个胶囊的后面」。
        // 间距用 CHIP_GAP —— 与"两颗胶囊之间"是同一个数（设计稿里那个 .chips 的 gap）
        val r = laidOut()

        assertEquals(CHIP_GAP, r.leftOf(r.plus) - r.rightOf(r.chips))
    }

    @Test
    fun `齿轮占着最右角`() {
        val r = laidOut()

        assertEquals(r.row.width - r.row.insets.right, r.rightOf(r.gear), "最右角空着一块")
    }

    // ---- 间距 ----

    @Test
    fun `富余的空白全落在「＋」与齿轮之间`() {
        // 这三者的位置由**同一个**弹簧决定：富余归它 → 「＋」咬住胶囊、齿轮贴住
        // 右边缘。挪成居中容器之类的话，"＋ 在哪儿"会随窗口宽度漂
        val r = laidOut()

        assertTrue(
            r.rightOf(r.plus) < r.leftOf(r.gear),
            "「＋」顶到齿轮上了，「＋」应当在最后一个胶囊后面",
        )
    }

    @Test
    fun `胶囊从这一行最左边开始，前面没有藏起来的白`() {
        // 当初"间隔太大"的根子就在这类地方：按钮默认边框的 3px 横向内边距
        // 画都不画，但照样占地方。这条钉的是"左边那一段白只有 row 自己的边距"
        val r = laidOut()

        assertEquals(r.row.insets.left, r.leftOf(r.chips))
    }

    @Test
    fun `横向内边距只剩图标那一点，不是 LAF 默认的那一圈`() {
        // 2026-09-14 那次"间隔太大"，6px 里有一半是**按钮默认边框**的横向内边距
        // （边框画都不画，内边距却占着）。现在留的是自绘图标的那一点，
        // 比 LAF 默认小得多 —— 这条钉的是"别又长回去"
        val r = laidOut()

        for ((name, b) in listOf("齿轮" to r.gear, "「＋」" to r.plus)) {
            assertTrue(b.insets.left <= JBUI.scale(2), "$name 左边内边距又长回去了：${b.insets.left}")
            assertTrue(b.insets.right <= JBUI.scale(2), "$name 右边内边距又长回去了：${b.insets.right}")
        }
    }

    @Test
    fun `竖向内边距留着 —— 点击区域不跟着缩水`() {
        // 横向那部分只产生空隙，竖向那部分是**可点的高度**。
        // 一起删掉的话按钮会比这一行矮 6px，而截图上看不出来
        val r = laidOut()

        for ((name, b) in listOf("齿轮" to r.gear, "「＋」" to r.plus)) {
            assertTrue(b.insets.top > 0, "$name 的竖向内边距被一起删掉了")
            assertTrue(
                b.preferredSize.height > b.getFontMetrics(b.font).height,
                "$name 只比字形高 0 —— 高度没了，点击区域缩水",
            )
        }
    }

    // ---- 两个按钮得长得一样 ----

    @Test
    fun `造型一模一样`() {
        // 同一行里一个有边框一个没有、字号差半号，并排放着都扎眼 ——
        // 这条钉的是"共用一个造型函数"这件事，不是某一个具体数值
        val r = laidOut()

        assertEquals(r.gear.insets, r.plus.insets, "内边距分叉了")
        assertEquals(r.gear.font.size, r.plus.font.size, "字号分叉了")
        for ((name, b) in listOf("齿轮" to r.gear, "「＋」" to r.plus)) {
            val margins = b.margin.top + b.margin.left + b.margin.bottom + b.margin.right
            assertEquals(0, margins, "$name 还带着 margin")
            assertFalse(b.isFocusable, "$name 会抢焦点")
            assertFalse(b.isContentAreaFilled, "$name 会画出按钮底")
            assertFalse(b.isBorderPainted, "$name 会画出边框")
            assertEquals(Cursor.HAND_CURSOR, b.cursor.type, "$name 不是手型光标")
        }
    }

    // ---- 可点的高度 ----

    /**
     * **这一行只有胶囊可点，所以行高必须就是胶囊高 + 内边距。**
     *
     * 2026-09-16 用户报「这一行的高度拉高一点，现在太矮了点击不方便」—— 原来胶囊
     * 22px。这条钉的是竖直方向的那条规矩（"白只有标出来的那几处出处"的竖直版）：
     * 哪天有人在别处再补一层竖直内边距，多出来的那几像素**看着是这一行的一部分**，
     * 点下去却什么都不会发生 —— 而截图上看不出区别。
     *
     * 顺带也钉住"图标按钮没有反超胶囊"：簇比胶囊高的话，行高就不再由胶囊决定，
     * 胶囊上下会各空出一截点不着的白。
     */
    @Test
    fun `这一行的高度就是胶囊的高度 —— 多出来的都是点不着的白`() {
        val r = laidOut()

        assertEquals(
            CHIP_HEIGHT + r.row.insets.top + r.row.insets.bottom,
            r.row.preferredSize.height,
            "行高 ≠ 胶囊高 + 内边距 —— 多出来的是点不着的白",
        )
        assertEquals(CHIP_HEIGHT, r.chips.preferredSize.height, "胶囊没占满这一格")
    }

    @Test
    fun `胶囊比那一簇图标高 —— 行高由它说了算`() {
        // 反过来的话，上面那条会红在"行高不等于胶囊高"，但原因看着像胶囊写错了
        val r = laidOut()

        assertTrue(
            CHIP_HEIGHT > r.plus.preferredSize.height,
            "图标那一簇比胶囊还高（${r.plus.preferredSize.height}）—— 行高不再由胶囊决定",
        )
    }

    // ---- 挤不挤得掉 ----

    @Test
    fun `超长标题不会把这一簇挤出这一行`() {
        val r = laidOut(
            title = "帮我看看这个插件为什么在恢复历史会话之后模型名标签没有更新，" +
                "顺便确认一下子代理的记录是不是也一起没了",
        )

        assertTrue(r.rightOf(r.gear) <= r.row.width - r.row.insets.right, "齿轮被挤出这一行了")
        assertTrue(r.rightOf(r.chips) <= r.leftOf(r.plus), "胶囊行压到「＋」上了")
    }

    @Test
    fun `排满 5 个标签时「＋」与齿轮也不许贴在一起`() {
        // 2026-09-16 探头图 `top-row-probe-five.png` 上抓到的：五颗胶囊把这一行
        // 吃到只剩按钮的位置，弹簧被挤成 0，「＋」直接顶住齿轮 —— 两个都点不准。
        // 这条钉的是那道**最小**间隔，它在富余的时候看不出来（归弹簧管）
        val r = laidOut(tabs = MAX_SESSION_TABS)

        assertTrue(
            r.leftOf(r.gear) - r.rightOf(r.plus) >= MIN_BUTTON_SEPARATION,
            "「＋」与齿轮贴上了：间隔只有 ${r.leftOf(r.gear) - r.rightOf(r.plus)}px",
        )
        assertTrue(r.rightOf(r.chips) <= r.leftOf(r.plus), "胶囊压到「＋」上了")
    }

    @Test
    fun `窄到 260px 也还站得住`() {
        // 工具窗口能拖得很窄。这一行的规矩是"先挤标签，不动按钮"
        val r = laidOut(width = 260)

        assertEquals(r.plus.preferredSize.width, r.plus.width, "「＋」被压缩了")
        assertEquals(r.gear.preferredSize.width, r.gear.width, "齿轮被压缩了")
        assertEquals(260 - r.row.insets.right, r.rightOf(r.gear))
        assertTrue(r.rightOf(r.chips) <= r.leftOf(r.plus), "挤到「＋」压住胶囊了")
    }

    @Test
    fun `图标按钮的宽度与字体无关 —— 图形是自绘的，没有字形盒`() {
        // 这条是 2026-09-15 那次事故的教训：间距一度想靠"按墨迹裁按钮宽度"
        // 来收，结果 JButton 的判据是**文字排版宽度**而不是墨迹，裁过头之后
        // 它把「＋」画成了「…」（探头图上看得清清楚楚）。
        // 现在图形是自绘的，字号怎么变都不该影响按钮宽度 —— 变了就说明
        // 又有人在量字形了
        for ((name, b) in listOf("齿轮" to settingsGearButton {}, "＋" to SessionNewButton {})) {
            val before = b.preferredSize.width
            b.font = b.font.deriveFont(b.font.size2D + 8f)
            assertEquals(before, b.preferredSize.width, "$name 的宽度跟着字体变了")
        }
    }

    @Test
    fun `按钮盒子就是图标盒子 —— New UI 的 72px 最小宽度不许插进来`() {
        // 2026-09-15 用户报「上门那个「＋」离齿轮隔着老远」的真正根子：New UI 给
        // **每一个 JButton** 兜了 72px 的最小宽度，两个图标于是各坐在一个 72px 宽的
        // 透明盒子里居中 —— 屏幕上中间白出 62px。前四轮改的都是"图标之间的白"
        // （内边距、字形盒、间距常量），颗粒度根本不对：改多少轮，这 72px 都在。
        //
        // 这条断言只在 New UI 下有意义（Metal 里本来就是 12），而整个类正跑在
        // New UI 下（见类注释）。把 TopRowIconButton 里那三个尺寸覆写拿掉，它当场红。
        val r = laidOut()

        for ((name, b) in listOf("齿轮" to r.gear, "「＋」" to r.plus)) {
            assertEquals(
                b.icon.iconWidth,
                b.preferredSize.width,
                "$name 的盒子不等于图标 —— LAF 的最小宽度又插进来了",
            )
        }
    }
}

private fun onEdt(block: () -> Unit) {
    var thrown: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            block()
        } catch (t: Throwable) {
            thrown = t
        }
    }
    thrown?.let { throw it }
}
