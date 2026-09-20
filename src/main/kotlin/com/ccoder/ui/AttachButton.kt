package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.io.File
import javax.swing.JComponent
import com.ccoder.text.CcoderText

/**
 * 工具栏最左的**附件按钮**（回形针）。点开是文件选择器，选中的文件加进输入框。
 *
 * ## 为什么自绘
 *
 * 与 [RoundSendButton] 同一条理由：原生 `JButton` 是方的、带 LookAndFeel 的
 * 渐变与边框，摆在这张卡片里**就它最不像是设计过的**。而 `JComponent` 还额外
 * 躲开了一个坑：New UI 会给每个 `JButton` 至少 72px 宽（见 [TopRowIconButton]
 * 那张表），用 `JComponent` 就没有这条规矩，尺寸说什么就是什么。
 *
 * 颜色也跟着工具栏的语气走：常态是次要文字色，悬停才亮到正文色 —— 这是
 * "添加上下文"的入口，不是主操作，主操作永远是右边那个发送键。
 */
internal class AttachButton : JComponent() {

    var onClick: (() -> Unit)? = null

    private var hovered = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val box = JBUI.scale(BOX_SIDE)
        preferredSize = Dimension(box, box)
        minimumSize = preferredSize
        maximumSize = preferredSize
        toolTipText = CcoderText.text("composer.attach.tip")
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (isEnabled) onClick?.invoke()
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            }
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when {
                !isEnabled -> UIUtil.getInactiveTextColor()
                hovered -> UIUtil.getLabelForeground()
                else -> UIUtil.getInactiveTextColor()
            }
            g2.stroke = BasicStroke(
                JBUI.scale(1.35f),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
            )

            // 按 12×12 的模子画，再整体缩到真实尺寸 —— 与 [TopRowIcon] 同一个理由：
            // 高 DPI 与"没被布局过"的 0 尺寸调用都不该画出畸形的图标
            val side = minOf(width, height).toDouble()
            if (side <= 0) return
            val glyph = side * GLYPH_RATIO
            g2.translate((width - glyph) / 2.0, (height - glyph) / 2.0)
            g2.scale(glyph / GRID, glyph / GRID)
            // 斜着放：竖版的别针在 16px 上又窄又长，像根别针也像个 "0"；
            // 45° 是各家聊天框里那个形状，同一个方格里也更能用满对角线
            g2.rotate(-Math.PI / 4, GRID / 2, GRID / 2)
            g2.draw(paperclip())
        } finally {
            g2.dispose()
        }
    }

    /**
     * 回形针。一格 12×12，整体居中的竖版别针，与各家聊天框里那个形状同源。
     *
     * 一笔画完（真实回形针就是一根钢丝）：右线下来 → 底部半圆 → 左线上去 →
     * 顶部小半圆翻回来 → 中间那条短线。断成两段的画法（外圈 + 内圈）在 16px
     * 上会看出一处多余的线头。
     */
    private fun paperclip(): Path2D {
        val p = Path2D.Double()
        // 外圈：右上角的线头 → 右线 → 底部半圆 → 左线
        p.moveTo(8.4, 2.8)
        p.lineTo(8.4, 7.6)
        // **负的扫掠角**才是往下绕：Java2D 的角度逆时针为正（0° 在右、90° 在上），
        // 底部那半圈是 0° → -90°（下）→ -180°（左）。写成 +180 会绕到顶上去，
        // 整个形状塌成一个 "M" —— 探针图里当场看出来的
        p.append(Arc2D.Double(3.6, 5.2, 4.8, 4.8, 0.0, -180.0, Arc2D.OPEN), false)
        p.lineTo(3.6, 3.4)
        // 顶部翻回来，落到中间那条短线上
        p.append(Arc2D.Double(3.6, 2.0, 2.8, 2.8, 180.0, -180.0, Arc2D.OPEN), false)
        p.lineTo(6.4, 6.5)
        return p
    }

    internal companion object {
        /** 按钮的方形边长（未缩放）。够得着，又不比旁边那排 12px 的文字抢眼。 */
        const val BOX_SIDE = 22

        /** 图标占按钮的比例。留白是为了别让墨迹顶到边界上。 */
        const val GLYPH_RATIO = 0.78

        /** 画图用的模子尺寸，与 [TopRowIcon] 一致。 */
        const val GRID = 12.0
    }
}

/**
 * 选中的文件怎么进输入框。
 *
 * 两条路都是**已经有**的，不新造第三条：
 * - 图 → 附件带（与拖一张 `.png` 进来完全同一条路，判据也同一个 [looksLikeImageFile]）
 * - 其余 → 输入框里插一个 `@相对路径`（与右键「加文件」同一个 [fileMention]，
 *   内容由 CLI 自己展开，插件不读它）
 *
 * 抽成纯函数是为了可测：`FileChooser` 要 Project，单测里起不来，而"选中的到底是
 * 哪种"这条一旦写反，用户要么拿到一个二进制文件的 `@` 引用、要么看着图没进来。
 */
internal data class ChosenFiles(val pictures: List<String>, val mentions: List<String>)

/** [absolutePaths] 是**绝对**路径（图要按它去读盘），分流只看文件名。 */
internal fun splitChosenFiles(absolutePaths: List<String>): ChosenFiles {
    val (pics, rest) = absolutePaths.partition { looksLikeImageFile(File(it).name) }
    return ChosenFiles(pictures = pics, mentions = rest)
}
