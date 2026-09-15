package com.ccoder.ui

import com.ccoder.settings.EffortSetting
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.PermissionModeSetting
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
 * 渲染探针：附件带的四个状态，出图给人眼。
 *
 * 没有断言，也不该有 —— 单测钉得住"空了就收起来""满了会说话"，钉不住
 * "56px 的缩略图摆在输入框上沿好不好看"。而这套做法（方案甲）当初就是靠
 * 出图选的：用户在 `docs/design/image-attach.html` 里比过四套。
 *
 * **要盯的两件事**：
 * 1. **空的时候卡片高度不变** —— 附件带是 BorderLayout 的 NORTH，而"不可见的
 *    子项到底占不占位"这件事不同布局管理器不一样（BoxLayout 会跳过、BorderLayout
 *    不一定）。所以这张图同时打出空/1 张/4 张的**首选高度**，数字在日志里。
 * 2. 一排缩略图之间的关系：等大、有间隙、✕ 压得住浅色截图。
 *
 * 在真机 LAF（New UI）下画 —— 图是**深色**的那才是真机（见 [IdeLaf]）。
 * 产物在 `build/attach-probe-*.png`。
 */
class AttachmentStripRenderProbe {

    /** 四张颜色分明的图：一排缩略图看不出区别就等于没画。 */
    private val COLORS = listOf(
        Color(30, 60, 220),
        Color(220, 60, 40),
        Color(60, 160, 90),
        Color(200, 170, 40),
    )

    @Test
    fun `空附件带 —— 输入卡不该多出高度`() = render("build/attach-probe-empty.png", images = 0)

    @Test
    fun `一张图`() = render("build/attach-probe-1.png", images = 1)

    @Test
    fun `四张图，到上限`() = render("build/attach-probe-4.png", images = MAX_IMAGES)

    @Test
    fun `满了再来一张 —— 理由写在带子上`() =
        render("build/attach-probe-full.png", images = MAX_IMAGES, overflow = true)

    private fun attachment(index: Int): AttachedImage {
        val color = COLORS[index % COLORS.size]
        val img = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply {
                // this.color 全都要写全：外层那个同名局部变量会把它盖住
                this.color = color
                fillRect(0, 0, 320, 200)
                this.color = Color(255, 255, 255)
                fillRect(12, 12, 120, 16)
                fillRect(12, 40, 200, 8)
                fillRect(12, 56, 160, 8)
                dispose()
            }
        }
        return prepareAttachment(img, index) ?: error("夹具没做成")
    }

    private fun render(path: String, images: Int, overflow: Boolean = false) {
        IdeLaf.withRealLaf {
            SwingUtilities.invokeAndWait {
                val strip = AttachmentStrip()
                repeat(images) { strip.add(attachment(it)) }
                if (overflow) strip.add(attachment(99))

                val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
                    lineWrap = true
                    styleComposerInput(this)
                    text = ""
                }
                val inputScroll = JBScrollPane(input).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                }
                val toolbar = buildComposerToolbar(
                    ModelLabel {}.apply { setProfile(ModelProfile(name = "Sonnet 4.5")) },
                    ModeLabel {}.apply { setMode(PermissionModeSetting.DEFAULT) },
                    EffortLabel {}.apply { setEffort(EffortSetting.HIGH) },
                    RoundSendButton().apply {
                        setState(mainButtonState(ready = true, busy = false, disconnected = false))
                    },
                )
                val card = buildComposerCard(inputScroll, toolbar, strip)
                val outer = JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(6, 8, 8, 8)
                    background = UIUtil.getPanelBackground()
                    add(card, BorderLayout.CENTER)
                }

                val w = 430
                val h = 300
                outer.setSize(w, h)
                layoutAll(outer)

                // 这一行是图的**数字版**：附件带到底有没有让卡片长高
                println(
                    "[attach-probe] images=$images overflow=$overflow " +
                        "卡片首选高=${card.preferredSize.height} 带子可见=${strip.isVisible}"
                )
                // ✕ 有没有真的画上去，光看图会怀疑是"颜色不显眼"还是"根本没加进来"。
                // 只打一张的那次：它能回答那个问题，而四次都打就成了噪音
                // （2026-09-15 就是靠它定位到"组件都在、只是被兄弟盖住"）
                if (images == 1) dump(strip, 0)

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
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }

    /** 把一棵树的组件、位置、可见性打出来 —— 出图看不出"没加进来"还是"被盖住了"的时候用。 */
    private fun dump(c: Container, depth: Int) {
        for (child in c.components) {
            val pad = "  ".repeat(depth)
            println(
                "[attach-tree] $pad${child.javaClass.simpleName} " +
                    "bounds=${child.bounds.x},${child.bounds.y},${child.width}x${child.height} " +
                    "visible=${child.isVisible} showing=${child.isShowing}"
            )
            if (child is Container) dump(child, depth + 1)
        }
    }
}
