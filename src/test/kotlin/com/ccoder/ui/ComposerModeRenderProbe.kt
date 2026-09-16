package com.ccoder.ui

import com.ccoder.settings.PermissionModeSetting
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
 * 渲染探针：把权限模式的切换弹层画成 PNG，好让人眼看一眼。
 *
 * 同 ComposerEffortRenderProbe 的理由：弹层宽度是**内容撑开的**，
 * 而每行的第二行说明长短差得多（最短 9 个字，最长 20 个字）。
 * 2026-09-16 接上「自动判定」时新写的那句
 * （「模型逐条判定放不放行；拿不准的仍会询问」）是六行里**最长**的，
 * 所以得看一眼它有没有把弹层撑过工具窗口那条 420px 的线 ——
 * 模型弹层当初就被一条长主机名撑到 442px。
 *
 * 产物在 `build/probe/composer-mode-popup.png`。改了说明文字就跑一下看一眼。
 */
class ComposerModeRenderProbe {

    @Test
    fun `把模式弹层画成图片`() {
        SwingUtilities.invokeAndWait {
            val list = buildModeList(PermissionModeSetting.DEFAULT) {}
            val pad = JBUI.scale(10)
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(pad)
                add(list, BorderLayout.CENTER)
            }

            val w = list.preferredSize.width + pad * 2
            val h = list.preferredSize.height + pad * 2
            // 长成什么样子是给人看的，宽度是给报告用的 ——
            // 弹层最宽不能超过它要去的那块地方（工具窗口 420px）
            println("probe: 模式弹层首选尺寸 ${list.preferredSize.width} x ${list.preferredSize.height}")
            outer.setSize(w, h)
            layoutAll(outer)
            write(outer, w, h, "build/probe/composer-mode-popup.png")
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    private fun write(c: Container, w: Int, h: Int, path: String) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        c.paint(g)
        g.dispose()
        val f = File(path)
        f.parentFile?.mkdirs()
        ImageIO.write(img, "png", f)
    }
}
