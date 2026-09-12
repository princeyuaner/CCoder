package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JLabel
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JPanel

/**
 * 条上文字的组装与截断。
 *
 * 宽度函数是注入的（每字 10px），所以这些断言不依赖真实字体 ——
 * 换台机器、换个 IDE 字体，结论不变。
 */
class ComposerStripTest {

    private val ten = { s: String -> s.length * 10 }

    /**
     * 递归布局。
     *
     * `doLayout()` **只管直接子项、不递归** —— 而生产里走的是 `validate()`，
     * 它递归。组件树一旦嵌了一层（比如上下文行左边现在是"状态+用量"一个组），
     * 只调最外层就会让里层的组件宽度恒为 0，断言看起来像功能坏了。
     */
    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    private fun strip(todo: String? = null, task: String? = null, running: Int = 0) =
        RunStrip(todoProgress = todo, currentTask = task, runningCount = running)

    // ---- 组装 ----

    @Test
    fun `只有清单时给进度和当前任务`() {
        assertEquals("◐ 3/7 修复 extractor 指纹", runStripText(strip("3/7", "修复 extractor 指纹"), 999, ten))
    }

    @Test
    fun `只有运行中时给计数`() {
        assertEquals("● 2", runStripText(strip(running = 2), 999, ten))
    }

    @Test
    fun `两者都有时用竖线分开`() {
        val text = runStripText(strip("3/7", "修复比对", 2), 999, ten)

        assertTrue(text.startsWith("◐ 3/7 修复比对"), text)
        assertTrue(text.endsWith("│ ● 2"), text)
    }

    // ---- 截断：宽度不够时谁先让位 ----

    @Test
    fun `宽度不够时先截任务名`() {
        // 条存在的理由是那两个计数。名字放不下就截名字
        val full = runStripText(strip("3/7", "修复 extractor 指纹比对", 2), 999, ten)
        val tight = runStripText(strip("3/7", "修复 extractor 指纹比对", 2), 24, ten)

        assertTrue(tight.contains("3/7"), "计数掉了：$tight")
        assertTrue(tight.contains("● 2"), "运行计数掉了：$tight")
        assertTrue(tight.length < full.length, "没有截短：$tight")
    }

    @Test
    fun `挤到极限也只截名字，两个计数都还在`() {
        val tight = runStripText(strip("3/7", "修复 extractor 指纹比对", 2), 1, ten)

        assertTrue(tight.contains("3/7"), "计数掉了：$tight")
        assertTrue(tight.contains("● 2"), "运行计数掉了：$tight")
    }

    @Test
    fun `名字短到放不下时整段消失，而不是留个残缺的词`() {
        // 留 "修..." 不如不留 —— 省略号传达不了任何东西，
        // 而计数已经说明了全部关键信息
        val tight = runStripText(strip("3/7", "修复 extractor 指纹比对", 2), 22, ten)

        assertFalse(tight.contains("修"), "留了残缺的名字：$tight")
        assertTrue(tight.contains("3/7"))
    }

    // ---- ellipsize 本体 ----

    @Test
    fun `放得下就原样返回`() {
        assertEquals("abcdef", ellipsize("abcdef", 60, ten))
    }

    @Test
    fun `放不下就截断并以省略号结尾`() {
        assertEquals("ab…", ellipsize("abcdef", 30, ten))
    }

    @Test
    fun `连省略号都放不下时给空串`() {
        // 与其画半个省略号，不如什么都不画
        assertEquals("", ellipsize("abcdef", 5, ten))
    }

    @Test
    fun `预算为零或负数时给空串而不是抛错`() {
        assertEquals("", ellipsize("abcdef", 0, ten))
        assertEquals("", ellipsize("abcdef", -20, ten))
    }

    // ---- 组件（回归：条曾经塌成一个点）----

    @Test
    fun `放进一行里布局之后，条不会被压成一个点`() {
        // 曾经的 bug：文字靠宽度算、宽度又靠文字算。初始化时文字为空，
        // 首选宽度只剩内边距，于是布局只分给它那么宽，再按这个宽度去算文字
        // —— 再也回不来了。界面上表现为右上角一个空的小圆点。
        //
        // 所以必须**布局之后**看它实际拿到多宽。只测 preferredSize 是测不出来的：
        // 独立组件的 width 是 0，两条实现路径都会返回完整文字宽度，测试恒过。
        val usage = buildUsageLabel().apply {
            text = "上下文  12.3k / 200k · 6%"
            isVisible = true
        }
        val strip = RunStripView {}
        strip.setStrip(RunStrip(todoProgress = "3/7", currentTask = "修复 extractor 指纹", runningCount = 2))

        val row = buildContextRow(JLabel("已连接"), usage, strip)
        row.setSize(430, 30)
        layoutAll(row)

        assertTrue(strip.width > 80, "条在布局里被压成了一个点：宽 ${strip.width}")
        assertTrue(usage.width > 0, "用量那一半也应该还在")
    }

    @Test
    fun `没有任务时条整个不可见`() {
        val view = RunStripView {}
        view.setStrip(RunStrip(todoProgress = "3/7", currentTask = null, runningCount = 0))
        assertTrue(view.isVisible, "有清单时应该可见")

        view.setStrip(null)
        assertFalse(view.isVisible, "没有任务时应该整条隐藏，而不是显示空条")
    }

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
    fun `浮层居中于面板`() {
        // 面板在 x=1000、宽 400，浮层宽 200 → 左边缘 = 1000 + (400-200)/2 = 1100
        assertEquals(
            1100,
            popupCenteredX(panelLeft = 1000, panelWidth = 400, popupWidth = 200,
                screenLeft = 0, screenRight = 3000),
        )
    }

    @Test
    fun `面板贴屏幕右边时浮层被钳回屏幕内`() {
        // 这一条正是原来的 bug：锚点（会话标签）右对齐，标题短时它缩到最右，
        // 弹层跟着跑到屏幕外。X 方向原先**完全没有**边界钳制
        val x = popupCenteredX(panelLeft = 1900, panelWidth = 400, popupWidth = 200,
            screenLeft = 0, screenRight = 1920)

        assertTrue(x + 200 <= 1920, "溢出屏幕右边：x=$x, 右边缘=${x + 200}")
    }

    @Test
    fun `面板贴屏幕左边时浮层不会越过左边界`() {
        val x = popupCenteredX(panelLeft = 0, panelWidth = 100, popupWidth = 200,
            screenLeft = 0, screenRight = 1920)

        assertTrue(x >= 0, "越过屏幕左边：$x")
    }
}
