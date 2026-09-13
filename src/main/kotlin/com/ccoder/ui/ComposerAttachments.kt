package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 缩略图高度（未缩放 px）。 */
internal const val THUMB_BOX = 64

/** 提示行文字。被拒了就明说拒了几张、为什么。 */
internal fun attachmentNotice(intake: ImageIntake): String? =
    intake.reason?.let { "已跳过 ${intake.rejected} 张：$it" }

/** 等比缩进 [box]×[box]，不改长宽比（拉伸的缩略图会让人以为粘错了图）。 */
internal fun thumbSize(w: Int, h: Int, box: Int): Dimension {
    if (w <= 0 || h <= 0) return Dimension(1, 1)
    val ratio = box.toDouble() / maxOf(w, h)
    return Dimension((w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1))
}

/**
 * 输入框上方的附件条。
 *
 * **无图时整条隐藏**：不隐藏的话输入区永远挂着一条空白，而且它的最小高度会
 * 通过 preferredSize 顶住分隔条的默认比例（同 COMPOSER_MIN_ROWS 那个坑）。
 */
internal class ComposerAttachments(
    private val onRemove: (Int) -> Unit,
) : JPanel() {

    private val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        // BoxLayout 按 alignmentX 横向摆子组件，默认 0.5（居中）—— 不钉成左对齐，
        // 缩略图那排和提示行会各自飘到中间去，看着像没对齐的错版
        alignmentX = LEFT_ALIGNMENT
    }
    private val notice = JLabel().apply {
        foreground = UIUtil.getInactiveTextColor()
        alignmentX = LEFT_ALIGNMENT
        // 缩略图那排的首图左边距来自 FlowLayout 的 hgap，这里手动补上，两行才对得齐
        border = JBUI.Borders.emptyLeft(JBUI.scale(6))
        isVisible = false
    }
    private var images: List<ImageAttachment> = emptyList()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(row)
        add(Box.createVerticalStrut(JBUI.scale(2)))
        add(notice)
        isVisible = false
    }

    fun imageCount(): Int = images.size

    fun setImages(next: List<ImageAttachment>) {
        images = next
        row.removeAll()
        next.forEachIndexed { index, image ->
            row.add(thumb(image, index))
        }
        row.revalidate()
        row.repaint()
        refreshVisibility()
    }

    fun setNotice(text: String?) {
        notice.text = text
        notice.isVisible = text != null
        refreshVisibility()
    }

    fun clear() {
        setImages(emptyList())
        setNotice(null)
    }

    private fun refreshVisibility() {
        isVisible = images.isNotEmpty() || notice.isVisible
    }

    /** 缩略图 + 右侧的 ✕。摆在一旁而不是压在图上 —— 压上去就会盖住图的一角，而这张图正是用户用来看"没粘错"的。 */
    private fun thumb(image: ImageAttachment, index: Int): JComponent {
        val pane = JPanel(BorderLayout()).apply { isOpaque = false }
        val icon = runCatching {
            val bytes = Base64.getDecoder().decode(image.base64)
            val src = ImageIO.read(bytes.inputStream())
            val size = thumbSize(src.width, src.height, JBUI.scale(THUMB_BOX))
            val scaled = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, size.width, size.height, null)
            g.dispose()
            ImageIcon(scaled).also {
                pane.toolTipText = "${src.width}×${src.height} · ${bytes.size / 1024}KB"
            }
        }.getOrNull()

        val label = JLabel(icon).apply { border = JBUI.Borders.empty(0, 0, 0, JBUI.scale(2)) }
        pane.add(label, BorderLayout.CENTER)

        val remove = JLabel("✕").apply {
            foreground = UIUtil.getInactiveTextColor()
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onRemove(index)
            })
        }
        pane.add(remove, BorderLayout.EAST)
        return pane
    }
}
