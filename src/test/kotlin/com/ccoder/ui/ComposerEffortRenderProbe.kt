package com.ccoder.ui

import com.ccoder.settings.EffortSetting
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
 * 渲染探针：把思考深度的切换弹层画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"六档都列了""说明不为空"，
 * 钉不住"这个弹层会不会宽得离谱"。而这条只能看。
 *
 * **这一条不是空担心**：弹层的宽度是**内容撑开的**，而这里每档的第二行
 * 说明是几个标签里最长的文案（「比「高」更深；仅部分模型认，其余降级为「高」」）。
 * 模型弹层当初就被一条长主机名撑到 442px —— 比工具窗口的 420px 还宽。
 * 同样的坑，思考深度这边有一模一样的形状。
 *
 * 产物在 `build/probe/composer-effort-popup.png`。改了说明文字就跑一下看一眼。
 */
class ComposerEffortRenderProbe {

    @Test
    fun `把档位弹层画成图片`() {
        SwingUtilities.invokeAndWait {
            val list = buildEffortList(EffortSetting.XHIGH) {}
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
            println("probe: 档位弹层首选尺寸 ${list.preferredSize.width} x ${list.preferredSize.height}")
            outer.setSize(w, h)
            layoutAll(outer)
            write(outer, w, h, "build/probe/composer-effort-popup.png")
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
