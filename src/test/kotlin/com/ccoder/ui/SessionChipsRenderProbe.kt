package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 会话胶囊行的离屏出图。
 *
 * 单测钉得住"宽度怎么分、标题怎么截"（[SessionChipsTest]），钉不住
 * "那一行看着对不对" —— 圆角、点的位置、✕ 有没有把标题挤没，只有看图。
 * 这一条是本仓库反复踩出来的（239 条全绿时那个圆点还在）。
 *
 * 出图前换真机 LAF（[IdeLaf]）：Metal 下量出来的间距与真机差着一个 JButton 的
 * 最小宽度（见 TopRow 那段注释）。
 *
 * 产物：`build/session-chips-probe-*.png`。
 */
class SessionChipsRenderProbe {

    @Test
    fun `把只有一个会话的胶囊行画成图片`() =
        render(
            "build/session-chips-probe-one.png",
            listOf(chip(title = null, state = TabState.Idle, current = true)),
        )

    /** 三种状态同时在：在跑 / 等你批准 / 空闲 —— 这一排点就是选 B 的全部理由。 */
    @Test
    fun `把三条会话的胶囊行画成图片`() =
        render(
            "build/session-chips-probe-three.png",
            listOf(
                chip(title = "重构 extractor 的指纹计算", state = TabState.Running, current = false),
                chip(title = "查一下线上日志", state = TabState.WaitingPermission, current = true),
                chip(title = null, state = TabState.Idle, current = false),
            ),
        )

    /** 到上限（5 个）：标题会被截到多短，看这一张。 */
    @Test
    fun `把五个会话的胶囊行画成图片`() =
        render(
            "build/session-chips-probe-five.png",
            listOf(
                chip("重构 extractor 的指纹计算", TabState.Running, current = true),
                chip("查一下线上日志里那条 500", TabState.Idle, current = false),
                chip("把 setting 页的七页签画完", TabState.WaitingPermission, current = false),
                chip("给 sidecar 补三个用例", TabState.Running, current = false),
                chip(null, TabState.Idle, current = false),
            ),
        )

    private fun chip(title: String?, state: TabState, current: Boolean) =
        TabChip(owner = JPanel(), title = title, state = state, current = current, canClose = true)

    private fun render(path: String, chips: List<TabChip>) {
        IdeLaf.withRealLaf {
            SwingUtilities.invokeAndWait {
                val row = SessionChips({}, {}).apply { render(chips) }

                val outer = JPanel(BorderLayout()).apply {
                    isOpaque = true
                    background = UIUtil.getPanelBackground()
                    border = JBUI.Borders.empty(6, 8)
                    add(row, BorderLayout.NORTH)
                    add(Box.createVerticalGlue(), BorderLayout.CENTER)
                }

                val w = 420
                val h = outer.preferredSize.height
                outer.setSize(w, h)
                layoutAll(outer)

                println("[会话胶囊探针] ${File(path).name} 行首选=${row.preferredSize}")
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                outer.paint(g)
                g.dispose()
                ImageIO.write(img, "png", File(path))
            }
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutAll(child)
    }
}
