package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.sqrt

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

    // ---- 形状：它得真的是个胶囊 ----

    /**
     * **圆角是半圆，不是一道小圆角。**
     *
     * 2026-09-16 用户报「为什么这胶囊不像胶囊，圆角太尖了」。根子是
     * `RoundRectangle2D` 的 arc 参数是圆角的**直径**而不是半径 ——
     * 写成 `CHIP_HEIGHT / 2` 得到的是半径 h/4 的圆角矩形。这个错从第一版就在
     * （22px 配 11），22px 时看着还像样，拉到 28px 之后一眼就露。
     *
     * 之所以不直接断言常量，是因为要钉的是**画出来的形状**：真胶囊的两端各是
     * 一个半圆，于是
     *  - 腰线（垂直中线）那一行必须顶到最左边（inset = 0）
     *  - 顶边那一行的起笔点离左边 ≈ 半径 = 高度的一半
     *
     * 这两条只有"半径 = h/2"才同时成立；圆角矩形那条会红在第二条（inset ≈ h/4）。
     */
    /**
     * 半圆的左边界在 y 那一行离最左边的距离。
     *
     * 圆心在 `(r, r)`、半径 `r`。取的是**像素中心 y + 0.5** —— 第 y 行采样的
     * 是这条线，不是 y 本身。不校正这半个像素的话，`y = 0` 那一行上
     * 14（半径）会算成 10.3，而真胶囊量出来是 9.8 上下 —— 差这一点就会把
     * 对的判成错的（这条正是先写错过一次的地方）。
     */
    private fun semicircleInset(r: Int, y: Int): Double {
        val dy = r - (y + 0.5)
        return r - sqrt((r * r - dy * dy).coerceAtLeast(0.0))
    }

    @Test
    fun `画出来的两端是半圆，不是一道小圆角`() {
        val w = 200
        val h = CHIP_HEIGHT
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)

        val row = SessionChips({}, {}).apply {
            render(
                listOf(
                    TabChip(
                        owner = owner(),
                        title = "帮我看下这个",
                        state = TabState.Idle,
                        // 当前那颗的底是强调色混面板底 —— 一定不是纯白，于是下面
                        // "这一像素画了没有"可以只看它等不等于白，与主题无关
                        current = true,
                        canClose = false,
                    )
                )
            )
            setSize(w, h)
            doLayout()
        }

        SwingUtilities.invokeAndWait {
            val g = img.createGraphics()
            g.color = Color.WHITE
            g.fillRect(0, 0, w, h)
            row.paint(g)
            g.dispose()
        }

        val outside = img.getRGB(0, 0)
        fun inset(y: Int) = (0 until w).firstOrNull { img.getRGB(it, y) != outside } ?: -1

        // **量的是两个数之间的差**，不是绝对值：胶囊在这一格里被 BoxLayout 摆
        // 在哪边无所谓，要钉的是形状本身 —— 顶边那一行的起笔点，比腰线
        // （两端弧的切点所在那一行）右移多少
        val waist = inset(h / 2)
        val shift = inset(0) - waist

        // 两种画法的预测值差着一倍，不会含糊：
        //   真胶囊（半径 = 高度的一半 = 14）→ 顶边起笔点在 10.3
        //   圆角矩形（半径 = 高度/4 = 7）    → 只有 4.4
        val capsule = semicircleInset(h / 2, 0)
        val roundedRect = semicircleInset(h / 4, 0)

        assertTrue(waist >= 0, "腰线那一行一个像素都没画 —— 尺寸不对")
        assertTrue(
            abs(shift - capsule) <= 2,
            "顶边起笔点比腰线右移 ${shift}px：真胶囊应当是 ${"%.1f".format(capsule)}，" +
                "而 ${"%.1f".format(roundedRect)} 是圆角矩形（那正是被用户说" +
                "「不像胶囊，圆角太尖」的那一版）",
        )
    }

    /** 造一个胶囊要的 owner —— 只要是个 JComponent，探针与用例共用。 */
    internal fun owner(): JPanel = JPanel()
}
