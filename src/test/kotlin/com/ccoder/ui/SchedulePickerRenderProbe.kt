package com.ccoder.ui

import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 渲染探针：把**选时弹层**与那颗时钟键画成 PNG，好让人眼看一眼。
 *
 * 要看的就三点（选型台 `web/tools/scheduled-send-view.mjs` 出的图是设计稿，
 * 这份是真 Swing 画出来的）：
 *  - 弹层宽度钉在 300px 上有没有兑现、两条快捷行的右侧钟点有没有对齐；
 *  - "指定今天时刻"那一行里，输入框与判词挨得对不对、判词换行没有；
 *  - 时钟键和附件键（回形针）**笔画的轻重是不是同一档** —— 两颗键并排站，
 *    一粗一细一眼就能看出来。
 *
 * 深浅两套都画：时钟键常态取的是 `InactiveTextColor`，两套主题里差得远。
 * 产物在 `build/probe/schedule-picker.png` 与 `-dark.png`。改了文案或几何就跑一眼。
 */
class SchedulePickerRenderProbe {

    @Test
    fun `把选时弹层与时钟键画成图片`() {
        render("build/probe/schedule-picker.png")
    }

    /** 深色主题下再画一遍（同 ComposerModeRenderProbe 那条：弹层多数时候出现在深色里）。 */
    @Test
    fun `把选时弹层与时钟键画成图片（深色）`() {
        val before = UIManager.getLookAndFeel()
        if (runCatching { UIManager.setLookAndFeel(DarculaLaf()) }.isFailure) return
        try {
            render("build/probe/schedule-picker-dark.png")
        } finally {
            runCatching { UIManager.setLookAndFeel(before) }
        }
    }

    private fun render(path: String) {
        SwingUtilities.invokeAndWait {
            // 钉子一刻的"现在"：14:22，与选型台出图时同一个钟。
            // 判词的钟也钉成它 —— 走真钟的话，我们这张 2026-10-13 的图会把
            // 预填的 14:52 判成"已经过了"，图上自相矛盾
            val now = ZonedDateTime.of(2026, 10, 13, 14, 22, 0, 0, ZoneId.of("Asia/Shanghai"))
            val popup = buildSchedulePicker(now, clockNow = { now }) {}

            // 三颗键并排：附件键打样、定时键的亮态与灰态
            val enabled = ScheduleButton {}
            val disabled = ScheduleButton {}.apply { isEnabled = false }
            val glyphs = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(AttachButton())
                add(Box.createHorizontalStrut(JBUI.scale(18)))
                add(enabled)
                add(Box.createHorizontalStrut(JBUI.scale(18)))
                add(disabled)
            }

            // 用 BorderLayout 而不是 BoxLayout 装这两块：BoxLayout 的 Y 轴会把
            // 子件按对齐点摆、宽度按它自己的算法给（2026-10-13 实测把 300 宽的
            // 弹层摆成了 217 —— 那层是探针的摆法问题，不是弹层自己的），
            // 而这张图的全部意义就是"量真几何"，摆法必须死板：
            // CENTER 一律铺满，量出来的宽度才等于真弹层里会发生的事
            val column = JPanel(BorderLayout(0, JBUI.scale(14))).apply {
                isOpaque = false
                add(glyphs, BorderLayout.NORTH)
                add(popup, BorderLayout.CENTER)
            }

            val pad = JBUI.scale(12)
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(pad)
                add(column, BorderLayout.CENTER)
            }

            val w = maxOf(popup.preferredSize.width, glyphs.preferredSize.width) + pad * 2
            val h = popup.preferredSize.height + glyphs.preferredSize.height +
                JBUI.scale(14) + pad * 2
            // 长成什么样子是给人看的，宽度是给报告用的 —— 工具窗口 560，弹层别顶格
            println(
                "probe: 选时弹层首选尺寸 ${popup.preferredSize.width} x ${popup.preferredSize.height}" +
                    "（工具窗口 560；模型弹层当初被一条长主机名撑到 442px）"
            )
            outer.setSize(w, h)
            layoutAll(outer)
            // 几何用文件留痕：stdout 被 gradle 吞掉，而"宽度钉没钉死"正是这张图
            // 2026-10-13 第一次出图时量歪过的地方（卡片 204 而不是 288）
            val lines = mutableListOf(
                "aim=$w x $h  outer=${outer.bounds}  column=${column.bounds}",
                "glyphs=${glyphs.bounds} alignmentX=${glyphs.alignmentX}",
                "popup=${popup.bounds} alignmentX=${popup.alignmentX}",
                "popup.pref=${popup.preferredSize} popup.max=${popup.maximumSize} insets=${popup.insets}",
            )
            for (i in 0 until popup.componentCount) {
                val c = popup.components[i]
                lines += "  popup[$i] ${c.javaClass.simpleName} bounds=${c.bounds}"
            }
            File(path.replace(".png", ".txt")).writeText(lines.joinToString("\n") + "\n")
            write(outer, w, h, path)
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
        println("probe: 已写出 $path")
    }
}
