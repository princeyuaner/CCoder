package com.ccoder.ui

import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * 计划审批框那两处取色的依据：**对比度**。
 *
 * 2026-09-16 用户截图："里面的深蓝色很难看清楚"。根因是行内代码色取自
 * `UIUtil.getTreeSelectionBackground` —— 那是**选中块的底色**，深色主题下天生是深的。
 * 这一组测试盯的就是"别再拿底色当文字色"这件事的下限。
 */
class ContrastTest {

    private val darkCard = Color(0x2B, 0x2D, 0x30)
    private val lightCard = Color(0xFF, 0xFF, 0xFF)

    @Test
    fun `黑白是 21 比 1，同色是 1 比 1`() {
        assertEquals(21.0, contrastRatio(Color.BLACK, Color.WHITE), 0.01)
        assertEquals(1.0, contrastRatio(darkCard, darkCard), 0.001)
    }

    @Test
    fun `深色主题下那个"选中块底色"确实不够 —— 这就是用户报的那一条`() {
        // Material 深色主题的选中块底色大致是这个量级的深蓝
        val treeSelection = Color(0x2E, 0x43, 0x6E)

        assertTrue(
            contrastRatio(treeSelection, darkCard) < CONTRAST_AA,
            "选中块底色如果够对比，这一整套取色就没必要了 —— 这条测试的前提变了",
        )
    }

    @Test
    fun `挑色：主题色够用就用主题色，不够就往后走`() {
        val theme = Color(0x88, 0xFF, 0x88) // 亮绿，在深底上够
        val picked = pickReadable(listOf(theme, Color.WHITE), darkCard)

        assertEquals(theme, picked)
    }

    @Test
    fun `挑色：一个都不够时给对比度最高的那个，而不是最差的`() {
        val nearBlack = Color(0x20, 0x20, 0x20)
        val midGray = Color(0x80, 0x80, 0x80)
        val picked = pickReadable(listOf(nearBlack, midGray), darkCard)

        assertEquals(midGray, picked)
    }

    @Test
    fun `兜底那两个蓝，深色下亮蓝赢、浅色下深蓝赢`() {
        val bright = Color(0x7F, 0xB0, 0xFF)
        val deep = Color(0x1F, 0x6F, 0xEB)
        val candidates = listOf(bright, deep)

        val onDark = pickReadable(candidates, darkCard)
        val onLight = pickReadable(candidates, lightCard)

        assertEquals(bright, onDark, "深色底该挑亮的那档")
        assertEquals(deep, onLight, "浅色底该挑深的那档")
        assertTrue(contrastRatio(onDark, darkCard) >= CONTRAST_AA)
        assertTrue(contrastRatio(onLight, lightCard) >= CONTRAST_AA)
    }

    @Test
    fun `往底色方向压一档：keep=1 原样，压过之后落在两者之间`() {
        val fg = Color(0xE6, 0xE8, 0xEC)
        val bg = darkCard

        assertEquals(fg, blend(fg, bg, keep = 1.0))
        val dimmed = blend(fg, bg, keep = 0.85)
        assertTrue(dimmed.red in bg.red..fg.red, "压过头或压反了：$dimmed")
        // 压一档仍要比"主题的次要文字色"清楚（次要色在深色下约 3.4:1）
        assertTrue(contrastRatio(dimmed, bg) > CONTRAST_AA, "压完还不到 4.5:1 就没意义了")
    }

    /**
     * 2026-09-28 用户报："弹框里的字体颜色太暗了"。
     *
     * 这条量的是那件事**为什么发生**：弹层那种底上，主题的"次要文字色"
     * （气泡里那几行、"日志浮层"的小标题原先用的就是它）连正文色的一半都不到 ——
     * 它的语义本来就是"可以看不清"（禁用、水印、句尾补充）。
     *
     * 所以那两处最后都直接用了正文色，**没有再压一档**：深色底上根本没有余量可压
     * （Darcula 量出来：正文色 4.43、压 15% 只剩 3.66、主题的次要色 1.93）。
     *
     * 钉的是**前提**：哪天主题的次要色自己就够清楚了，说明前提变了，
     * 回那两处看一眼要不要改回去（写法同上面那条"选中块底色确实不够"）。
     */
    @Test
    fun `弹层那种底上，主题的次要色连正文色的一半都不到`() = IdeLaf.withRealLaf {
        val bg = UIUtil.getToolTipBackground()
        val full = contrastRatio(UIUtil.getLabelForeground(), bg)
        val inactive = contrastRatio(UIUtil.getInactiveTextColor(), bg)

        assertTrue(
            inactive < full * 0.6,
            "次要色没比正文暗多少（%.2f:1 vs %.2f:1）—— 前提变了，回那两处看一眼".format(inactive, full),
        )
    }

    @Test
    fun `计划区高度按屏幕算，并夹在下限上限之间`() {
        assertEquals(486, planAreaHeight(1080), "1080 的 45%")
        assertEquals(270, planAreaHeight(600), "600 的 45% 还在区间里，别顺手夹了")
        assertEquals(PLAN_AREA_MAX, planAreaHeight(2160), "4K 屏不该顶到 972")
        assertEquals(PLAN_AREA_MIN, planAreaHeight(400), "再小也得留得下正文")
        assertEquals(260, PLAN_AREA_MIN)
        assertEquals(520, PLAN_AREA_MAX)
    }
}
