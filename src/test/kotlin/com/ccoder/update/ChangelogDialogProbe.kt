package com.ccoder.update

import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把「更新日志」那个框画成 PNG，好让人眼看一眼。
 *
 * 为什么值得单独出一张：这一屏**全是 HTML**，而 `JEditorPane` 认得的是一个很老的
 * HTML 子集 —— change-notes 里那些 `<h3>` / `<ul>` / `<code>` 到底渲染成什么样，
 * 单测断言不出来（断言得住"读到了内容"，断言不住"像一篇文章"）。
 *
 * 产物在 `build/probe/changelog-dialog.png`。改了 change-notes 的写法或这个框的
 * 排版就跑一下看一眼。
 */
class ChangelogDialogProbe {

    /**
     * 拿**真实的**那一份 change-notes 画（从打包后的描述符里读）。
     *
     * 读不到（只挂了源资源目录、或者文案还是空的）就退回一份合成样本 ——
     * 探针的活是"让这张图总能出"，而不是"证明文案写好了"。
     */
    private fun notesHtml(): String =
        loadOwnChangelog()?.notesHtml?.takeIf { it.isNotBlank() } ?: SAMPLE_NOTES

    @Test
    fun `把更新日志框画成图片`() = render("build/probe/changelog-dialog.png", notesHtml())

    /** 特别长的一版：多条 + 长行 + `code`，看它会不会挤出横向滚动条。 */
    @Test
    fun `把很长的那一版画成图片`() = render("build/probe/changelog-dialog-long.png", LONG_NOTES)

    private fun render(path: String, notes: String) {
        SwingUtilities.invokeAndWait {
            val dialog = ChangelogDialog(project = null, changelog = PluginChangelog("0.2.19", notes))
            val center = dialog.contentPanel
            assertNotNull(center, "对话框没给出内容面板")

            // 框的骨架不套平台窗口：只把内容面板按它的首选尺寸排一遍再画。
            // 探针要看的正是"内容那一块"，窗口装饰与按钮栏是平台的事
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                border = com.intellij.util.ui.JBUI.Borders.empty(10)
                // 内容是 **NORTH** 不是 CENTER：BorderLayout 里同一个区域放两个组件
                // 时只有后加的那个会被排 —— 先加内容再加弹簧，内容会被摆成 0 尺寸，
                // 出图就是一片空白（第一次就是这么画出来的）
                add(center, BorderLayout.NORTH)
            }

            val w = center.preferredSize.width + 20
            val h = center.preferredSize.height + 20
            outer.setSize(w, h)
            layoutAll(outer)

            println("[更新日志探针] ${File(path).name} 内容首选=${center.preferredSize} 出图=${w}x${h}")

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

    private companion object {
        /** 描述符读不到时的替身：形状与真实那段一样（h3 + 中英两份 + ul + code）。 */
        val SAMPLE_NOTES = """
            <h3>0.2.19</h3>
            <ul>
              <li>The Environment tab now detects Node.js and the <code>claude</code> CLI,
                  and can install whichever is missing.</li>
              <li>Sessions can be cleared in one go from the session list.</li>
            </ul>
            <h4>中文</h4>
            <ul>
              <li>环境页能检测 Node.js 与 <code>claude</code> CLI，缺哪个就能就地装哪个。</li>
              <li>会话列表右上角可以一键清空这个项目的历史会话。</li>
            </ul>
        """.trimIndent()

        /** 很长的那一版：看长行会不会把横向滚动条逼出来。 */
        val LONG_NOTES = """
            <h3>0.2.20</h3>
            <ul>
              <li>${"这一条特别长，长到一行放不下，用来看看它在 560 宽的框里会不会被切掉、会不会逼出横向滚动条 —— 中文长行与英文长行都试一遍。"}</li>
              <li>Every file edit is shown as a diff before it is applied, and commands that touch your machine wait for your Allow or Deny, whatever the permission mode says.</li>
              <li>短的一条。</li>
            </ul>
        """.trimIndent()
    }
}
