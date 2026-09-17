package com.ccoder.settings

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.InputStream
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 「群交流」页（2026-09-17）：一页只放一张二维码。
 *
 * ## 为什么是页签而不是"关于"里的一个链接
 *
 * 用户的原话就是"设置页面增加一个页签：群交流"。这一页不读写任何配置，
 * 也**不联网** —— 图片是打包进插件的资源，扫码是用户自己拿手机做的事。
 *
 * ## 图片为什么按原始字节打包（没转 PNG、没压缩）
 *
 * 核过数字：展示宽度约 [QR_MAX_WIDTH] px、这个码 41 个模块，每个模块≈9px ——
 * JPEG 的块边缘噪声远小于一个模块，**不需要为了"干净"再重编码一次**
 * （重编码只会引入一次额外的损失，还把"给你的那张图"换成了另一张）。
 *
 * 也**不做深浅色适配**：二维码必须是深色压浅底，任何主题下都保持原样 ——
 * 反色或调暗会让手机扫不出来。图片自带白底，看起来像一张卡片，这是对的。
 */
internal class GroupChatSettingsPage : SettingsPage {

    override val title: String = "群交流"

    private var built: JComponent? = null

    override fun component(): JComponent = built ?: build().also { built = it }

    /** 这一页不读任何配置，没东西可重读 —— 但接口要求它，留个空的并写清原因。 */
    override fun reload() = Unit

    private fun build(): JComponent = settingsPageBody(
        settingsColumn().apply {
            add(
                JBLabel("群交流").apply {
                    foreground = UIUtil.getLabelForeground()
                    border = JBUI.Borders.emptyBottom(8)
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            add(qrBox())
            // wrappedHint 自己带下间距（见它那条注释），这里不要再补 strut
            add(
                wrappedHint(
                    "有问题、建议，或者想聊聊怎么用，扫码加微信找我。",
                    PAGE_CONTENT_WIDTH,
                ),
            )
        },
    )

    private fun qrBox(): JComponent {
        val qr = loadScaledQr() ?: return wrappedHint(
            "二维码图片没能加载（${QR_RESOURCE}）。这不该发生 —— 它随插件一起打包。",
            PAGE_CONTENT_WIDTH,
        )
        return JPanel().apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(qr.width, qr.height)
            // 高度必须**等于**定死的那个：给 Int.MAX_VALUE 它会变成页里的弹簧
            // （tableBox / modelListBox / logBox 各踩过一次）
            maximumSize = Dimension(Int.MAX_VALUE, qr.height)
            // **居中是刻意的**（默认的 FlowLayout 就居中）：这一页只有一张卡片，
            // 左对齐会让它看起来像没摆完；出图看过，居中的更像一张卡片。
            // 别的页都是左边那一列 —— 那些是表单，这一页不是
            add(JBLabel(ImageIcon(qr)))
        }
    }

    /** 按 [QR_MAX_WIDTH] / [QR_MAX_HEIGHT] 等比缩到装得下。纯函数（喂 InputStream），可测。 */
    internal fun loadScaledQr(stream: InputStream? = javaClass.getResourceAsStream(QR_RESOURCE)): BufferedImage? {
        val src = runCatching { stream?.use { ImageIO.read(it) } }.getOrNull() ?: return null
        val scale = minOf(
            QR_MAX_WIDTH.toDouble() / src.width,
            QR_MAX_HEIGHT.toDouble() / src.height,
            1.0, // 只缩不放
        )
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())

        // 不用 `getScaledInstance`：它返回的 Image 是**异步**装填的，
        // 离屏渲染探针 `paint()` 那一刻可能还没画完 —— 图上就是一片空白。
        // 自己用 Graphics2D 画一遍是同步的，探针看到的与真机一致
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private companion object {
        /** 打包路径（`src/main/resources/images/wechat-qr.jpg`）。 */
        const val QR_RESOURCE = "/images/wechat-qr.jpg"

        /**
         * 展示尺寸的上限。高度受对话框限制（内容区不到 500px，还要留标题与说明那两行），
         * 宽度受页宽限制 —— 取小者等比缩。
         */
        const val QR_MAX_WIDTH = 360
        const val QR_MAX_HEIGHT = 360
    }
}
