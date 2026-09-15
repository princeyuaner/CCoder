package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension

/**
 * 浮层定位。
 *
 * 宽度函数那一批用例（`runStripText` / `ellipsize`）随"上下文右边那条胶囊"
 * 一起下线了 —— 它们存在的理由是胶囊要按可用宽度截断文字，而四张状态卡里
 * 放的是 `3/7`、`2` 这种定宽短值，不需要截断。
 */
class ComposerStripTest {

    // ---- 浮层多高 ----

    @Test
    fun `浮层还没显示时用内容的首选高度，而不是 null`() {
        // JBPopup.getSize() 给的是弹窗窗口的尺寸，而窗口要等 show 之后才建出来
        // —— 在那之前恒为 null。上一版直接读 popup.size.height，
        // 结果每次点开浮层都 NPE。
        //
        // 退回内容的首选高度：那正是浮层将要采用的尺寸
        assertEquals(220, popupHeightOf(null, Dimension(100, 220)))
    }

    @Test
    fun `已经量过的尺寸优先于内容首选高度`() {
        assertEquals(50, popupHeightOf(Dimension(100, 50), Dimension(100, 220)))
    }

    @Test
    fun `两个都没有时给 0 而不是抛错`() {
        // 0 在 popupAnchorY 里会退化成"贴着锚点上方"，可接受；
        // 抛错则整个点击都废掉
        assertEquals(0, popupHeightOf(null, null))
    }

    // ---- 浮层往上还是往下 ----

    @Test
    fun `下方放得下就向下弹`() {
        // 锚点在屏幕中间，浮层 100 高，屏幕底 1000
        assertEquals(200, popupAnchorY(anchorTop = 150, anchorHeight = 20, popupHeight = 100,
            screenTop = 0, screenBottom = 1000, gap = 30))
    }

    @Test
    fun `下方放不下就向上弹`() {
        // 这条本来就在工具窗口最底部，这是常态而不是边角情况。
        // 浮层顶 = 锚点顶 − 间距 − 浮层高 = 950 − 30 − 100 = 820，
        // 底 920 落在锚点（950）之上，中间正好留出 30 的间距
        assertEquals(820, popupAnchorY(anchorTop = 950, anchorHeight = 20, popupHeight = 100,
            screenTop = 0, screenBottom = 1000, gap = 30))
    }

    @Test
    fun `向上也不越过屏幕顶`() {
        val y = popupAnchorY(anchorTop = 40, anchorHeight = 20, popupHeight = 100,
            screenTop = 0, screenBottom = 100, gap = 30)

        assertTrue(y >= 0, "越过了屏幕顶：$y")
    }

    @Test
    fun `上下都放不下时贴屏幕顶，至少顶部内容可见`() {
        // 任务很多时浮层可以比屏幕还高。这时贴顶比贴底好 ——
        // 贴底会连标题都看不见，而那正是"这是什么"的唯一线索
        assertEquals(0, popupAnchorY(anchorTop = 300, anchorHeight = 20, popupHeight = 900,
            screenTop = 0, screenBottom = 500, gap = 30))
    }

    // ---- 浮层水平居中于面板 ----

    @Test
    fun `宽度退回内容首选宽度`() {
        assertEquals(300, popupWidthOf(null, Dimension(300, 100)))
    }

    @Test
    fun `已经量过的宽度优先于内容首选宽度`() {
        assertEquals(120, popupWidthOf(Dimension(120, 50), Dimension(300, 100)))
    }

    @Test
    fun `宽度两个都没有时也给 0`() {
        assertEquals(0, popupWidthOf(null, null))
    }

    @Test
    fun `浮层贴面板左边缘，不再居中`() {
        // 面板在 x=1000、宽 400，浮层宽 200：
        // 居中会给 1100，贴左边缘给 1000。这一条钉的就是「贴左」而不是「居中」
        assertEquals(
            1000,
            popupLeftX(panelLeft = 1000, popupWidth = 200, screenLeft = 0, screenRight = 3000),
        )
    }

    @Test
    fun `面板贴屏幕右边时浮层被钳回屏幕内`() {
        // 会话列表的锚点是右对齐的标签 —— 贴锚点会跟着跑到屏幕外。
        // 贴面板左边缘同时解掉这件事，而钳制是最后一道保险
        val x = popupLeftX(panelLeft = 1900, popupWidth = 200, screenLeft = 0, screenRight = 1920)

        assertTrue(x + 200 <= 1920, "溢出屏幕右边：x=$x, 右边缘=${x + 200}")
    }

    @Test
    fun `面板贴屏幕左边时浮层不会越过左边界`() {
        val x = popupLeftX(panelLeft = 0, popupWidth = 200, screenLeft = 0, screenRight = 1920)

        assertTrue(x >= 0, "越过屏幕左边：$x")
    }

    @Test
    fun `浮层比屏幕还宽时不抛，退化成贴屏幕左边`() {
        // coerceIn 遇到空区间会抛 —— lo <= hi 那一句就是为这个写的
        val x = popupLeftX(panelLeft = 500, popupWidth = 2400, screenLeft = 0, screenRight = 1920)

        assertEquals(0, x)
    }
}
