package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 会话胶囊行里那几件"不画也能判"的事：状态怎么判、宽度怎么分、标题怎么截。
 *
 * 画的那一半（圆角、点、✕）看 [SessionChipsRenderProbe] 出的图 ——
 * 单测钉不住"好不好看"（这一条是本仓库反复踩出来的）。
 */
class SessionChipsTest {

    private fun metrics(): FontMetrics =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().getFontMetrics(JLabel().font)

    // ---- 状态 ----

    @Test
    fun `空闲、在跑、等你批准`() {
        assertEquals(TabState.Idle, tabStateOf(0, busy = false, starting = false))
        assertEquals(TabState.Running, tabStateOf(0, busy = true, starting = false))
        assertEquals(TabState.Running, tabStateOf(0, busy = false, starting = true), "正在启动也是在动")
        assertEquals(TabState.WaitingPermission, tabStateOf(2, busy = false, starting = false))
    }

    @Test
    fun `忙优先于等待`() {
        // 正在跑的回合里也可能挂着权限询问，那时说"在跑"更贴切 ——
        // 说"等你"会让人以为它卡住了
        assertEquals(TabState.Running, tabStateOf(3, busy = true, starting = false))
    }

    // ---- 宽度 ----

    @Test
    fun `按份分宽，且夹在上下限之间`() {
        // 420 的工具窗口减掉两个图标按钮与内边距，大约剩 340
        assertEquals(CHIP_MAX_WIDTH, chipWidthFor(340, 1), "只有一个时不必占满，超上限就停")
        assertEquals((340 - 2 * CHIP_GAP) / 3, chipWidthFor(340, 3))
        assertEquals(CHIP_MIN_WIDTH, chipWidthFor(200, 5), "再窄也不能窄过下限")
    }

    @Test
    fun `一个都没有时给下限，不除零`() {
        assertEquals(CHIP_MIN_WIDTH, chipWidthFor(340, 0))
        assertEquals(CHIP_MIN_WIDTH, chipWidthFor(340, -1))
    }

    // ---- 标题 ----

    @Test
    fun `短标题原样，未命名给占位`() {
        val fm = metrics()
        assertEquals("重构 extractor", chipTitleFor("重构 extractor", fm, 400))
        assertEquals("新会话", chipTitleFor(null, fm, 400))
        assertEquals("新会话", chipTitleFor("   ", fm, 400))
    }

    @Test
    fun `长标题按像素截断，尾巴是省略号`() {
        val fm = metrics()
        val long = "帮我看看这个插件为什么在恢复历史会话之后模型名标签没有更新"

        val cut = chipTitleFor(long, fm, 80)

        assertTrue(cut.endsWith("…"), "实际：$cut")
        assertTrue(fm.stringWidth(cut) <= 80, "截完还超宽：${fm.stringWidth(cut)}")
        assertTrue(cut.length < long.length)
    }

    @Test
    fun `量的是真字体，不是字符数`() {
        // 同样 10 个字符，全角与半角宽度差一倍 —— 按字符数截会在长英文标题上
        // 露出半个词（同 CompletionPopup.rowTextFor 那条理由）
        val fm = metrics()
        val cjk = chipTitleFor("重构指纹计算的实现细节", fm, 60)
        val ascii = chipTitleFor("refactorfingerprint", fm, 60)

        assertTrue(fm.stringWidth(cjk) <= 60 && fm.stringWidth(ascii) <= 60)
        assertTrue(
            ascii.length >= cjk.length,
            "同一宽度下能放下的半角字符不该比全角少：ascii=${ascii.length} cjk=${cjk.length}",
        )
    }

    @Test
    fun `窄到一个省略号都放不下时，只给省略号`() {
        val fm = metrics()
        assertEquals("…", chipTitleFor("重构指纹计算", fm, 0))
    }

    /** 造一个胶囊要的 owner —— 只要是个 JComponent，探针与用例共用。 */
    internal fun owner(): JPanel = JPanel()
}
