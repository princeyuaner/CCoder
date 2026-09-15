package com.ccoder.ui

import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把顶部那一行画成 PNG。
 *
 * 没有断言，也不该有 —— 单测钉得住"标签显示标题""「＋」忙时置灰""两个按钮隔
 * [TOP_ROW_GAP]"，钉不住"这两个字形挨在一起好不好看"。而后者正是 2026-09-14
 * 那次改动（间隔太大 → 收紧、顺序对调）的**全部内容**：改之前出了五张候选图
 * 并排看，才敢说 2px 比 4px 对。
 *
 * **2026-09-15 前后收过四次**，教训都记在 [TOP_ROW_GAP] 顶上。最后落到
 * 自绘图标：字形盒那片随字体与 DPI 变的边距没法用布局收，而按墨迹去裁按钮
 * 宽度又会被 LAF 判成"放不下"、把「＋」画成「…」——**那一次正是这张图
 * 抓到的，可惜当时只量了数字没看图**。现在这张图上两个图标之间是固定的 2px。
 *
 * 图是整行 420px 宽画出来的，两个图标只占最右角那 30px —— 要判断"挨得够不够紧"，
 * 得把右边缘裁下来放大看，盯着整行看不出名堂。
 *
 * 画的是 [buildTopRow] **本身**，不是画一份长得像的 —— 探针与真界面分叉的话，
 * 它好看也没用。
 *
 * 产物在 `build/top-row-probe*.png`（3 倍画，因为字形小，原尺寸看不准）。
 */
class TopRowRenderProbe {

    /** 一条很长的首问。它既是"截断后"那张图的输入，也是"没截断"那张的对照。 */
    private val LONG_FIRST_MESSAGE =
        "帮我看看这个插件为什么在恢复历史会话之后模型名标签没有更新，顺便确认一下子代理的记录是不是也一起没了"

    @Test
    fun `把顶部那一行画成图片`() {
        render("build/top-row-probe.png", title = "重构 extractor 的指纹计算", enabled = true)
    }

    @Test
    fun `把长标题的顶部那一行画成图片`() {
        // 全列表最长的那个标题 —— 挤掉按钮的嫌疑就落在它身上
        render("build/top-row-probe-long.png", title = "PyCharm插件调用Claude Code", enabled = true)
    }

    /**
     * **现实里最长的那一档**：2026-09-15 起标题由 [titleSnippet] 截到 20 个字，
     * 所以这张图上画的就是上限本身。
     *
     * spec §2.3 要求过：这一行不能因为长标题把按钮挤掉。前半句保留原样，
     * 后半句（没截断的）另外画一张 —— 那张是"万一哪天不截了"的对照物，
     * 也是 20 这个数**量出来**的依据。
     */
    @Test
    fun `把截断后的最长标题画成图片`() {
        render(
            "build/top-row-probe-verylong.png",
            title = titleSnippet(LONG_FIRST_MESSAGE),
            enabled = true,
        )
    }

    /** 没截断的那一版。它要是也不挤，那 20 这个数就是白截的 —— 留着当对照物。 */
    @Test
    fun `把没截断的超长标题画成图片`() {
        render("build/top-row-probe-untitled-limit.png", title = LONG_FIRST_MESSAGE, enabled = true)
    }

    @Test
    fun `把忙时的顶部那一行画成图片`() {
        // 「＋」置灰时的那一档颜色
        render("build/top-row-probe-busy.png", title = "重构 extractor 的指纹计算", enabled = false)
    }

    private fun render(path: String, title: String?, enabled: Boolean) {
        SwingUtilities.invokeAndWait {
            val sessionLabel = SessionLabel {}.apply { setTitle(title, enabled = enabled) }
            val newButton = SessionNewButton {}.apply {
                setBlock(if (enabled) SwitchBlock.None else SwitchBlock.TurnRunning)
            }

            val top = buildTopRow(sessionLabel, settingsGearButton {}, newButton)

            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                add(top, BorderLayout.NORTH)
            }

            val w = 420
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            val s = 3.0
            val img = BufferedImage((w * s).toInt(), (h * s).toInt(), BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.scale(s, s)
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
