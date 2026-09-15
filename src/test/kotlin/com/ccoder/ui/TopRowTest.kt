package com.ccoder.ui

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
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
        val label: JComponent,
        val gear: JButton,
        val plus: JButton,
        val cluster: JPanel,
    )

    /**
     * 建出真那一行并**排好版**。
     *
     * 不给尺寸就直接量 bounds 的话全是 0 —— 一条永远通过的断言比没有断言更糟。
     * 所以这里撑到真实宽度（工具窗口默认 420）再读坐标。
     */
    private fun laidOut(width: Int = 420, title: String? = "重构 extractor 的指纹计算"): Laid {
        lateinit var out: Laid
        onEdt {
            val label = SessionLabel {}.apply { setTitle(title, enabled = true) }
            val gear = settingsGearButton {}
            val plus = SessionNewButton {}
            val row = buildTopRow(label, gear, plus)

            val outer = JPanel(BorderLayout()).apply { isOpaque = true }
            outer.add(row)
            outer.setSize(width, 200)
            layoutAll(outer)

            out = Laid(row, label, gear, plus, row.getComponent(1) as JPanel)
        }
        return out
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutAll(child)
    }

    /** 按钮在 row 坐标系里的左右边界（两个按钮各在 cluster 里，差一层偏移）。 */
    private fun Laid.leftOf(b: JButton) = cluster.x + b.x
    private fun Laid.rightOf(b: JButton) = cluster.x + b.x + b.width

    // ---- 顺序 ----

    @Test
    fun `「＋」在齿轮左边`() {
        val r = laidOut()

        assertTrue(r.leftOf(r.plus) < r.leftOf(r.gear), "「＋」应该在齿轮左边")
    }

    @Test
    fun `齿轮占着最右角`() {
        val r = laidOut()

        assertEquals(r.row.width - r.row.insets.right, r.rightOf(r.gear), "最右角空着一块")
    }

    // ---- 间距 ----

    @Test
    fun `两个按钮之间只隔 TOP_ROW_GAP`() {
        // 量的是**盒子**之间。所以它单独并不能保证"眼睛看到的就是 TOP_ROW_GAP"——
        // 盒子被 LAF 撑宽时它照样绿（撑宽的是盒子内部）。下面那条
        // `按钮盒子就是图标盒子` 才是配对的另一半
        val r = laidOut()

        assertEquals(TOP_ROW_GAP, r.gear.x - (r.plus.x + r.plus.width))
    }

    @Test
    fun `这一簇没有藏起来的横向边距`() {
        // 当初"间隔太大"的根子就在这里：按钮默认边框的 3px 横向内边距画都不画，
        // 但照样占地方 —— 两个字形之间于是白多出 6px
        val r = laidOut()

        assertEquals(
            r.plus.preferredSize.width + TOP_ROW_GAP + r.gear.preferredSize.width,
            r.cluster.width,
        )
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

    // ---- 挤不挤得掉 ----

    @Test
    fun `超长标题不会把这一簇挤出这一行`() {
        val r = laidOut(
            title = "帮我看看这个插件为什么在恢复历史会话之后模型名标签没有更新，" +
                "顺便确认一下子代理的记录是不是也一起没了",
        )

        assertTrue(r.rightOf(r.gear) <= r.row.width - r.row.insets.right, "齿轮被挤出这一行了")
        assertTrue(r.label.x + r.label.width <= r.leftOf(r.plus), "标题压到按钮上了")
    }

    @Test
    fun `窄到 260px 也还站得住`() {
        // 工具窗口能拖得很窄。这一行的规矩是"先挤标签，不动按钮"
        val r = laidOut(width = 260)

        assertEquals(
            r.plus.preferredSize.width + TOP_ROW_GAP + r.gear.preferredSize.width,
            r.cluster.width,
            "两个按钮被压缩了 —— 那样它们就不一样宽了",
        )
        assertEquals(260 - r.row.insets.right, r.rightOf(r.gear))
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
