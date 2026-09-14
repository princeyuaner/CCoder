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
 * 2px"，钉不住"这两个字形挨在一起好不好看"。而后者正是 2026-09-14 那次改动
 * （间隔太大 → 收紧、顺序对调）的**全部内容**：改之前出了五张候选图并排看，
 * 才敢说 2px 比 4px 对。
 *
 * 画的是 [buildTopRow] **本身**，不是画一份长得像的 —— 探针与真界面分叉的话，
 * 它好看也没用。
 *
 * 产物在 `build/top-row-probe*.png`（3 倍画，因为字形小，原尺寸看不准）。
 */
class TopRowRenderProbe {

    @Test
    fun `把顶部那一行画成图片`() {
        render("build/top-row-probe.png", title = "重构 extractor 的指纹计算", enabled = true)
    }

    @Test
    fun `把长标题的顶部那一行画成图片`() {
        // 全列表最长的那个标题 —— 挤掉按钮的嫌疑就落在它身上
        render("build/top-row-probe-long.png", title = "PyCharm插件调用Claude Code", enabled = true)
    }

    @Test
    fun `把超长标题的顶部那一行画成图片`() {
        // 会话摘要可能是整段首问，比真实列表里任何一条都长。
        // spec §2.3 要求过：这一行不能因为长标题把按钮挤掉
        render(
            "build/top-row-probe-verylong.png",
            title = "帮我看看这个插件为什么在恢复历史会话之后模型名标签没有更新，顺便确认一下子代理的记录是不是也一起没了",
            enabled = true,
        )
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
