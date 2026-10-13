package com.ccoder.ui

import com.intellij.openapi.ui.Divider
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * 渲染探针：把分隔条上的常驻握把画成 PNG —— 静止 / 悬停，外加两张 4× 放大。
 *
 * 没有断言：单测钉得住"挂上了、会进悬停态"，钉不住"3px 的短棒在一列近黑的
 * 界面里到底看不看得见"。选型台（web/tools/splitter-handle-view.mjs，方案 A）
 * 定的是这个形，这里守的是"实际画出来还是不是它"。
 *
 * **深浅各画一组**（照 [UsageRenderProbe] 记过的教训：只有浅色探针时，
 * "深色下整个看不见"这类问题一路绿灯，直到用户实机才现形）—— 用户实机是深色，
 * 而握把静止时就是一支灰棒，正是最容易被底色吞掉的那种东西。
 *
 * 产物在 `build/splitter-handle-probe*.png`。改了 [DividerGripView] 或
 * [DIVIDER_WIDTH] 就跑一下、看一眼。
 */
class SplitterHandleRenderProbe {

    @Test
    fun `握把画成图片（浅色）`() = SwingUtilities.invokeAndWait {
        renderPair(base = "build/splitter-handle-probe", dark = false)
    }

    @Test
    fun `握把画成图片（深色）`() = SwingUtilities.invokeAndWait {
        renderPair(base = "build/splitter-handle-probe-dark", dark = true)
    }

    private fun renderPair(base: String, dark: Boolean) {
        val restore = pushDarkTheme(dark)
        try {
            val (split, divider) = fixture()
            paint(split, "$base.png")
            paintZoom(split, divider, "$base-zoom.png")

            divider.dispatchEvent(
                MouseEvent(divider, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 5, 2, 0, false),
            )
            paint(split, "$base-hover.png")
            paintZoom(split, divider, "$base-zoom-hover.png")
        } finally {
            restore()
        }
    }

    /**
     * 把平台色换成深色版 —— 照 [UsageRenderProbe.pushDarkTheme] 同一套做法
     * （只按握把真正读的那几个键；`JBColor.setDark` 单独翻不动已解析的 UIManager 色）。
     * 面板色 #191A1C = 用户实机的 Islands 系主题实测值。
     */
    private fun pushDarkTheme(dark: Boolean): () -> Unit {
        if (!dark) return {}
        val keys = linkedMapOf(
            "Panel.background" to Color(0x19, 0x1a, 0x1c),
            "Tree.selectionBackground" to Color(0x2f, 0x65, 0xca),
        )
        val prev = keys.keys.associateWith { UIManager.getColor(it) }
        keys.forEach { (k, v) -> UIManager.put(k, v) }
        JBColor.setDark(true)
        return {
            prev.forEach { (k, v) -> UIManager.put(k, v) }
            JBColor.setDark(false)
        }
    }

    /** 一块 420×180 的窗片：上下两块同色面板 + 中间那条真分隔条（含握把）。 */
    private fun fixture(): Pair<JComponent, Divider> {
        val pane = { JPanel().apply { background = UIUtil.getPanelBackground() } }
        val split = buildTranscriptSplit(pane(), pane())
        val w = JBUI.scale(420)
        val h = JBUI.scale(180)
        split.setSize(w, h)
        layoutAll(split)
        val divider = split.components.filterIsInstance<Divider>().first()
        return split to divider
    }

    private fun paint(c: JComponent, path: String) {
        val img = render(c)
        ImageIO.write(img, "png", File(path))
    }

    /**
     * 分隔条中段裁出来放大 —— 6px 高的东西 1:1 在图上读不出形状，
     * 与选型台上的放大镜同一个道理。
     */
    private fun paintZoom(split: JComponent, divider: Divider, path: String) {
        val img = render(split)
        val scale = 4
        val cw = JBUI.scale(160)
        val ch = JBUI.scale(36)
        val cx = (split.width - cw) / 2
        val cy = (divider.y + divider.height / 2 - ch / 2).coerceIn(0, split.height - ch)
        val crop = img.getSubimage(cx, cy, cw, ch)
        val zoom = BufferedImage(cw * scale, ch * scale, BufferedImage.TYPE_INT_RGB)
        val g = zoom.createGraphics()
        g.drawImage(crop, 0, 0, zoom.width, zoom.height, null)
        g.dispose()
        ImageIO.write(zoom, "png", File(path))
    }

    private fun render(c: JComponent): BufferedImage {
        val img = BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        c.paint(g)
        g.dispose()
        return img
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        c.components.forEach { if (it is Container) layoutAll(it) }
    }
}
