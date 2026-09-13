package com.ccoder.ui

import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把附件条的四种形态各画一张 PNG。
 *
 * 没有断言，也不该有 —— 单测能钉住"等比缩到框内""没被拒就没有提示"，
 * 钉不住"缩略图糊不糊、✕ 是不是压在图上了"。而后者只有看才知道。
 *
 * 产物在 `build/attachments-probe-*.png`。改了附件条就跑一下看一眼。
 */
class ComposerAttachmentsRenderProbe {

    @Test
    fun `四种形态各画一张`() = render()

    private fun render() = SwingUtilities.invokeAndWait {
        val cases = listOf(
            "空 —— 整条不占高度" to listOf<ImageAttachment>(),
            "一张" to listOf(img(400, 300)),
            "三张（含竖图与长截图）" to listOf(img(400, 300), img(300, 500), img(1200, 200)),
        )
        cases.forEachIndexed { i, (name, images) ->
            write(strip(images), "build/attachments-probe-$i.png", name)
        }

        // 带提示行的单独一张：提示行与缩略图同时在场时才看得出高度对不对
        val withNotice = strip(listOf(img(400, 300))).apply {
            setNotice(attachmentNotice(ImageIntake(emptyList(), 2, "单张超过 5MB")))
        }
        write(withNotice, "build/attachments-probe-notice.png", "带超限提示")
    }

    private fun strip(images: List<ImageAttachment>) =
        ComposerAttachments(onRemove = {}).apply { setImages(images) }

    /** 内存里的假图，走和真实路径同一套 base64 编码。 */
    private fun img(w: Int, h: Int): ImageAttachment {
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        buffered.createGraphics().apply {
            color = Color(0x33, 0x66, 0x99)
            fillRect(0, 0, w, h)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(buffered, "png", out)
        return ImageAttachment("image/png", Base64.getEncoder().encodeToString(out.toByteArray()))
    }

    private fun write(strip: ComposerAttachments, path: String, caption: String) {
        val root = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            // 前景色交给 LAF 默认值：探针要看的是附件条，不是标题行的配色
            add(JLabel("  $caption"), BorderLayout.NORTH)
            add(strip, BorderLayout.CENTER)
        }
        root.setSize(460, 200)
        layoutAll(root)
        val height = root.preferredSize.height.coerceAtLeast(40)
        root.setSize(460, height)
        // 必须递归：doLayout() 只管直接子组件，附件条内部的 row / 缩略图拿不到尺寸，
        // 画出来就是一张空图 —— 而"空图"和"整条隐藏"长得一模一样，最容易骗过眼睛
        layoutAll(root)

        val image = BufferedImage(460, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = UIUtil.getPanelBackground()
            fillRect(0, 0, image.width, image.height)
            root.paint(this)
            dispose()
        }
        File(path).parentFile?.mkdirs()
        ImageIO.write(image, "png", File(path))
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
