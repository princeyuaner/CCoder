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
 * 渲染探针：把同步气泡**离屏画成 PNG**（2026-09-24）。
 *
 * 真机上它是个浮层窗口（`Balloon`），无头跑不起来 —— 而"这一屏好不好看"恰恰只有看图
 * 才知道（`grep` 与断言都看不出"红字会不会太吵""等宽那几行挤不挤"）。所以画的是
 * **气泡里那一块内容** [bubbleContent] 本身，底取 `bubbleBackdrop()` 那一个
 * （提示层那一族）—— 图上的字色就是真机上那个。
 *
 * 三张图对应三种真实的一轮：
 *
 * 1. **稳态**：复制三个文件（最常见的一屏）
 * 2. **有删除、有失败**：减号那行 + 红字那句（最需要看清的一屏）
 * 3. **首轮全量**：四百多个文件，列表只有三行 + "还有 409 个"（第一印象那一屏）
 *
 * **2026-09-28 用户报"弹框里的字体颜色太暗了"** —— 就是在这三张图上没看出来的东西：
 * 几行等宽路径原先取的是主题的次要文字色（深色下 3.4:1）。图上当时"看着还行"，
 * 因为我把它们当成了"次要的目录信息"；而用户要看的是**那是哪几个文件**。
 * 现在有 `SyncBubbleTest` 里那条对比度断言盯着，图画完照样要人眼再过一遍。
 *
 * 产物在 `build/probe/sync-bubble*.png`。改了气泡的样子就跑一下看一眼。
 */
class SyncBubbleRenderProbe {

    @Test
    fun `把气泡的三种样子画成图片`() = IdeLaf.withRealLaf {
        render(
            "build/probe/sync-bubble-copy.png",
            model(
                copied = 3,
                copiedNames = listOf(
                    "trunk/src/net/battle.kt",
                    "trunk/config/gateway.json",
                    "trunk/src/main/kotlin/com/ccoder/sync/very/LongFileName.kt",
                ),
            ),
        )

        render(
            "build/probe/sync-bubble-mixed.png",
            model(
                copied = 2,
                deleted = 1,
                failed = 1,
                copiedNames = listOf("trunk/src/net/battle.kt", "trunk/config/gateway.json"),
                deletedNames = listOf("trunk/src/net/old_gateway.kt"),
            ),
        )

        render(
            "build/probe/sync-bubble-firstrun.png",
            model(
                copied = 412,
                copiedNames = listOf("trunk/src/net/battle.kt", "trunk/a.kt", "trunk/b.kt"),
                costMs = 61_000L,
            ),
        )
    }

    /**
     * **量尺**：把这一屏用到的字色与底都打出来，连对比度一起。
     *
     * 2026-09-28 加。挑颜色不能靠眼睛 —— 那三张图上"看着还行"的那几行灰，
     * 在这块底上只有 1.9:1（用户就是这么报上来的）。这行数还改了一次决定：
     * 本打算"从正文色往下压一档"，量出来正文自己才 4.4:1，压一档就掉到 3.7 ——
     * 深色底上根本没有余量，于是那几行直接用正文色。
     *
     * 真机（New UI 深色）的具体值不一样，但同一族的取值总在同一档。
     */
    @Test
    fun `量一遍气泡的字色与底`() = IdeLaf.withRealLaf {
        val bg = UIUtil.getToolTipBackground()
        fun hex(c: java.awt.Color) = "#%06x".format(c.rgb and 0xFFFFFF)
        fun ratio(c: java.awt.Color) = "%.2f:1".format(contrastRatio(c, bg))

        val rows = UIUtil.getLabelForeground()
        val accent = accentTextOn(bg)
        val inactive = UIUtil.getInactiveTextColor()

        println(
            "[气泡量尺] 底 ${hex(bg)} ｜ 路径行=正文色 ${hex(rows)} ${ratio(rows)}" +
                " ｜ 还有N个 ${hex(accent)} ${ratio(accent)}" +
                " ｜ 主题强调色 ${hex(focusColor())} ${ratio(focusColor())}" +
                " ｜ 故障红 ${hex(dangerColor())} ${ratio(dangerColor())}" +
                " ｜ 主题次要色 ${hex(inactive)} ${ratio(inactive)}（改之前用的就是它）",
        )
    }

    private fun model(
        copied: Int,
        deleted: Int = 0,
        failed: Int = 0,
        copiedNames: List<String> = emptyList(),
        deletedNames: List<String> = emptyList(),
        costMs: Long = 480L,
    ) = syncBubbleModelOf(
        com.ccoder.sync.SyncSnapshot(
            run = com.ccoder.sync.SyncRun.RUNNING,
            lastRoundAtMs = 1L,
            lastCostMs = costMs,
            copied = copied,
            deleted = deleted,
            failed = List(failed) { com.ccoder.sync.Failed("trunk/x.kt", "被占用") },
            copiedNames = copiedNames,
            deletedNames = deletedNames,
        )
    )!!

    private fun render(path: String, model: SyncBubbleModel) = SwingUtilities.invokeAndWait {
        val content = bubbleContent(model) {}

        val outer = JPanel(BorderLayout()).apply {
            isOpaque = true
            // 照实画在**气球自己的底**上：字色就是按它挑的（见 SyncBubble.kt 的
            // `bubbleBackdrop`），底与字取同一处，图上才是真机那一屏
            background = UIUtil.getToolTipBackground()
            border = JBUI.Borders.empty(10)
            add(content, BorderLayout.CENTER)
        }

        // **不设固定宽度**：真机上气泡就是按内容的自然宽度长的 ——
        // 图上量到的宽度正是它会用的那个宽度
        val w = outer.preferredSize.width
        val h = outer.preferredSize.height
        outer.setSize(w, h)
        layoutAll(outer)

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        outer.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File(path))
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
