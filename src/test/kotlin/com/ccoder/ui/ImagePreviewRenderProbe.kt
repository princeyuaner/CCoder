package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把「点开看大图」那个框画成 PNG。
 *
 * 没有断言，也不该有 —— 单测钉得住"4000 宽的图开框时被掐到 864 高"，钉不住
 * "四周的留白舒不舒服""小图的框是不是小得可笑"。后者只有**看**才知道。
 *
 * **画的是内容面板，不是整个框**：窗口装饰与按钮栏是平台画的，我们自己的东西
 * 全在 `ImagePreviewDialog.createCenterPanel()` 返回的那一块里
 * （同 `ChangelogDialogProbe` 的做法）。
 *
 * 尺寸是**问真屏幕**算出来的（框就是这么开在用户面前的），所以每张图都把那几行
 * 数字打进日志：换个屏幕跑，数会变、图也跟着变，这本身就是要看的东西。
 *
 * 产物在 `build/probe/image-preview-*.png`。改了画法与尺寸策略就跑一下看一眼。
 */
class ImagePreviewRenderProbe {

    @Test
    fun `大图：缩进框里`() = render("build/probe/image-preview-big.png", sizes = listOf(2400 to 1500))

    @Test
    fun `小图：一比一居中，不放大`() = render("build/probe/image-preview-small.png", sizes = listOf(320 to 200))

    @Test
    fun `四张：左下角那行提示`() =
        render("build/probe/image-preview-four.png", sizes = List(MAX_IMAGES) { 1600 to 1000 }, start = 1)

    /**
     * 真机 LAF（New UI 深色）底下画：底色、描边、字号都是它给的 —— 几何与观感的
     * 探针不许跑在测试 JVM 默认的 Metal 下（见 [IdeLaf] 的说明）。
     * `setLookAndFeel` 是全局的，所以**得在 EDT 之外**先换、再建组件。
     */
    private fun render(path: String, sizes: List<Pair<Int, Int>>, start: Int = 0) {
        IdeLaf.withRealLaf { renderUnder(path, sizes, start) }
    }

    private fun renderUnder(path: String, sizes: List<Pair<Int, Int>>, start: Int) {
        SwingUtilities.invokeAndWait {
            val images = sizes.mapIndexed { i, (w, h) ->
                prepareAttachment(fakeScreenshot(w, h, TINTS[i % TINTS.size]), i)
                    ?: error("夹具没做成（第 $i 张 $w×$h）")
            }
            val dialog = ImagePreviewDialog(project = null, images = images, startIndex = start)
            val content = dialog.contentPanel
            assertNotNull(content, "对话框没给出内容面板")

            // 框的骨架不套平台窗口：只把内容面板按它的首选尺寸排一遍再画。
            // 内容是 **NORTH** 不是 CENTER：BorderLayout 里同一个区域放两个组件时
            // 只有后加的那个会被排，先加内容再加弹簧的话内容会被摆成 0 尺寸
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty(10)
                add(content, BorderLayout.NORTH)
            }

            val w = content.preferredSize.width + JBUI.scale(20)
            val h = content.preferredSize.height + JBUI.scale(20)
            outer.setSize(w, h)
            layoutAll(outer)

            println(
                "[看图探针] ${File(path).name} 图=${images.map { "${it.bytes.size}B" }} " +
                    "放大区首选=${content.preferredSize} 出图=${w}x${h}",
            )

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            File(path).parentFile?.mkdirs()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    /**
     * 一张"像截图"的图：浅底、一条标题栏、几行深浅不一的字条、一块彩色。
     *
     * 画字条而不是真去渲染文字：这一屏要看的是**留白、比例、缩放糊不糊**，
     * 真文字反而会让人分不清"糊的是图"还是"框自己有毛病"。
     */
    private fun fakeScreenshot(w: Int, h: Int, tint: Color): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
            )
            g.color = Color(246, 246, 248)
            g.fillRect(0, 0, w, h)

            // 标题栏
            val bar = (h * 0.09).toInt().coerceAtLeast(8)
            g.color = Color(226, 228, 234)
            g.fillRect(0, 0, w, bar)
            g.color = tint
            g.fillOval((w * 0.02).toInt(), (bar * 0.3).toInt(), (bar * 0.4).toInt(), (bar * 0.4).toInt())

            // 一块"内容图"
            g.color = tint
            g.fillRoundRect(
                (w * 0.06).toInt(), (h * 0.2).toInt(),
                (w * 0.38).toInt(), (h * 0.36).toInt(),
                (h * 0.03).toInt(), (h * 0.03).toInt(),
            )

            // 几行字条：右边那一列
            g.color = Color(90, 94, 102)
            var y = (h * 0.22).toInt()
            var i = 0
            while (y + h * 0.05 < h * 0.62) {
                val lineH = (h * 0.025).toInt().coerceAtLeast(2)
                val lineW = (w * (0.44 - 0.07 * (i % 3))).toInt()
                g.fillRect((w * 0.5).toInt(), y, lineW, lineH)
                y += (h * 0.075).toInt().coerceAtLeast(lineH + 2)
                i++
            }

            // 底部一条浅色带：看缩放时细线会不会消失
            g.color = Color(214, 216, 222)
            g.fillRect(0, (h * 0.72).toInt(), w, 2)
            g.color = Color(90, 94, 102)
            g.fillRect((w * 0.06).toInt(), (h * 0.8).toInt(), (w * 0.3).toInt(), (h * 0.02).toInt())
        } finally {
            g.dispose()
        }
        return img
    }

    private companion object {
        /** 四张颜色分明：一排图扫过去时能一眼分清是哪张。 */
        val TINTS = listOf(
            Color(80, 130, 240),
            Color(230, 90, 70),
            Color(70, 170, 110),
            Color(210, 170, 50),
        )
    }
}
