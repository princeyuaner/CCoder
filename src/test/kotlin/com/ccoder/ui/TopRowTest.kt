package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Cursor
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 顶部那一行的排布：谁在左、挨多近、最右角归谁。
 *
 * 2026-09-14 用户改了这里的两件事（间隔太大 → 收紧；顺序对调）。两件都属于
 * "眼睛看得出来、单测看不懂"的那一类 —— 所以这里不测好看，只测**可测的那几件**：
 * 顺序、间距、以及"有没有藏起来的边距"（当初间隔变大，根子正是那个）。
 * 好不好看交给 [TopRowRenderProbe] 出图，人看一眼。
 */
class TopRowTest {

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
    fun `按钮自己不带任何横向内边距`() {
        val r = laidOut()

        for ((name, b) in listOf("齿轮" to r.gear, "「＋」" to r.plus)) {
            assertEquals(0, b.insets.left, "$name 左边还留着内边距")
            assertEquals(0, b.insets.right, "$name 右边还留着内边距")
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
