package com.ccoder.ui

import com.intellij.util.ui.JBUI
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
 * 没有断言，也不该有 —— 单测钉得住"标签显示标题""「＋」忙时置灰"，
 * 钉不住"这俩挤在 420px 里会怎样"。而后者正是设计稿选图标而非文字的
 * **全部理由**（§一 A：「420px 那一栏真的挤」）—— 那条理由不该只靠信念。
 *
 * 这一行现在只有会话标签与「＋」：连接状态已经和另外三样一起，成为输入框
 * 上方那四张独立的卡（见 `ComposerRenderProbe`）。
 *
 * 产物在 `build/top-row-probe*.png`。
 */
class TopRowRenderProbe {

    @Test
    fun `把顶部那一行画成图片`() {
        render("build/top-row-probe.png", title = "重构 extractor 的指纹计算", enabled = true)
    }

    @Test
    fun `把长标题的顶部那一行画成图片`() {
        // 全列表最长的那个标题 —— 挤掉「＋」的嫌疑就落在它身上
        render("build/top-row-probe-long.png", title = "PyCharm插件调用Claude Code", enabled = true)
    }

    @Test
    fun `把超长标题的顶部那一行画成图片`() {
        // 会话摘要可能是整段首问，比真实列表里任何一条都长。
        // spec §2.3 要求过：这一行不能因为长标题把「＋」挤掉
        render(
            "build/top-row-probe-verylong.png",
            title = "帮我看看这个插件为什么在恢复历史会话之后模型名标签没有更新，顺便确认一下子代理的记录是不是也一起没了",
            enabled = true,
        )
    }

    private fun render(path: String, title: String?, enabled: Boolean) {
        SwingUtilities.invokeAndWait {
            val sessionLabel = SessionLabel {}.apply { setTitle(title, enabled = enabled) }
            val newButton = SessionNewButton {}.apply {
                setBlock(if (enabled) SwitchBlock.None else SwitchBlock.TurnRunning)
            }

            // 与 ClaudePanel 的 top 同构：标签靠左、「＋」在最右
            val top = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                add(sessionLabel, BorderLayout.CENTER)
                add(newButton, BorderLayout.EAST)
            }

            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                add(top, BorderLayout.NORTH)
            }

            val w = 420
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
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
