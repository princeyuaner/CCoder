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
 * 渲染探针：输入卡**下半格那层底带**（2026-09-17 用户："最下面这里我想加个背景，
 * 让他们感觉是一体的"）。
 *
 * 没有断言，也不该有 —— 单测钉得住"底带的上沿等于工具栏的上沿"（见
 * `ComposerRulesTest` 里那条量像素的），钉不住"6% 这个深浅是不是刚刚好"。
 *
 * 画三张：**没有底带**（改之前的样子）、**现在的深浅**、**再深一档** ——
 * 深浅这种事看并排才判断得出来，而它就是 [FOOTER_TINT] 一个数。
 * 产物在 `build/composer-footer-*.png`。
 */
class ComposerFooterRenderProbe {

    @Test
    fun `没有底带 —— 改之前的样子`() = render("build/composer-footer-none.png", tint = 0.0)

    @Test
    fun `现在的深浅`() = render("build/composer-footer.png", tint = FOOTER_TINT, real = true)

    @Test
    fun `再深一档`() = render("build/composer-footer-strong.png", tint = 0.15)

    private fun render(path: String, tint: Double, real: Boolean = false) {
        IdeLaf.withRealLaf {
            SwingUtilities.invokeAndWait {
                val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
                    lineWrap = true
                    styleComposerInput(this)
                    text = "输入消息，Enter 发送"
                }
                val inputScroll = JBScrollPane(input).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                }
                val toolbar = buildComposerToolbar(
                    AttachButton(),
                    ModelLabel {}.apply { setProfile(ModelProfile(name = "Sonnet 4.5")) },
                    ModeLabel {}.apply { setMode(PermissionModeSetting.DEFAULT) },
                    EffortLabel {}.apply { setEffort(EffortSetting.HIGH) },
                    RoundSendButton().apply {
                        setState(mainButtonState(ready = true, busy = false, disconnected = false))
                    },
                )
                val card = buildComposerCard(inputScroll, toolbar)

                val outer = JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.empty(8)
                    background = UIUtil.getPanelBackground()
                    add(card, BorderLayout.CENTER)
                }
                val w = 430
                val h = 150
                outer.setSize(w, h)
                layoutAll(outer)

                // 深浅三档：卡片自己只会画 [FOOTER_TINT] 那一档，探针要的是并排比 ——
                // 于是把底带那一片**先擦回面板色、再按这档重画**。擦的矩形正是
                // 工具栏占的那一条（上沿问布局要，和卡片里那位是同一个数）
                val bandTop = toolbar.bounds.y
                println("[底带探针] ${File(path).name} tint=$tint 卡片=${card.width}x${card.height} 工具栏上沿=$bandTop")

                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = img.createGraphics()
                outer.paint(g)
                if (!real) {
                    // 擦回面板色：只擦卡片内沿以内，最外那 1px 留给描边线
                    g.color = UIUtil.getPanelBackground()
                    g.fillRect(8 + 1, 8 + bandTop, card.width - 2, card.height - 1 - bandTop)
                    // 后面两笔都在**卡片坐标系**里画，所以借一个平移过的 Graphics
                    if (tint > 0) {
                        val band = g.create(8, 8, card.width, card.height)
                        paintComposerFooter(
                            band, card.width, card.height, bandTop,
                            mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), tint),
                        )
                        band.dispose()
                    }
                    // 工具栏那行控件刚才被擦掉了，重画一遍 —— 它们是画在底带**上面**的。
                    // 原点要用**它自己的 bounds**（卡片内边距 6 与 bandTop），
                    // 画在 (0,0) 会跑到卡片顶上去（第一次就是这么画错的）
                    val controls = g.create(
                        8 + toolbar.bounds.x,
                        8 + toolbar.bounds.y,
                        toolbar.width,
                        toolbar.height,
                    )
                    toolbar.paint(controls)
                    controls.dispose()
                }
                g.dispose()
                File(path).parentFile?.mkdirs()
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
}
