package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把会话列表画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"标题回退到 firstPrompt""忙时没有监听器"，
 * 钉不住"420px 下右侧的时间会不会被长标题挤掉"。
 *
 * 那一句计划里原本写的是"理论上不会，但理论上不等于测过"。
 * 这个探针就是把它变成"看过"。
 *
 * 产物在 `build/session-list-probe.png`。改了列表观感就跑一下看一眼。
 */
class SessionListRenderProbe {

    private val now = System.currentTimeMillis()

    @Test
    fun `把会话列表画成图片`() = render("build/session-list-probe.png", SwitchBlock.None)

    @Test
    fun `把忙时的会话列表画成图片`() =
        render("build/session-list-probe-blocked.png", SwitchBlock.PermissionPending)

    @Test
    fun `把悬停出删除按钮的那一行画成图片`() =
        render("build/session-list-probe-hover.png", SwitchBlock.None, hoverRow = 1)

    private fun render(path: String, block: SwitchBlock, hoverRow: Int = -1) {
        SwingUtilities.invokeAndWait {
            val sessions = listOf(
                SessionInfo("s1", "还可以做什么功能", null, now - 30_000),
                // 这一条是全列表最长的标题 —— 挤掉时间的嫌疑就落在它身上
                SessionInfo("s2", "PyCharm插件调用Claude Code", null, now - 3_600_000),
                SessionInfo("s3", null, "你好", now - 90_000_000),
                SessionInfo("s4", "重构 extractor 的指纹计算，顺便把跨块的 CR 边界也一起处理掉", null, now - 5 * 86_400_000),
            )

            val list = buildSessionList(sessions, currentSessionId = "s2", block = block)

            // 悬停态：往那一行派发 MOUSE_ENTERED，✕ 就会露出来
            if (hoverRow >= 0) {
                val row = list.components.filterIsInstance<java.awt.Component>()[hoverRow]
                row.dispatchEvent(
                    java.awt.event.MouseEvent(
                        row, java.awt.event.MouseEvent.MOUSE_ENTERED,
                        System.currentTimeMillis(), 0, 5, 5, 0, false,
                    )
                )
            }

            // 420px 是工具窗口的真实宽度
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(8)
                add(list, BorderLayout.NORTH)
                add(Box.createVerticalGlue(), BorderLayout.CENTER)
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

    private fun layoutAll(c: java.awt.Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is java.awt.Container) layoutAll(child)
        }
    }
}
