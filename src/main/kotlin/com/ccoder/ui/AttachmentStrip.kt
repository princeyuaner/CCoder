package com.ccoder.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 附件带：待发的那几张图（设计稿 `docs/design/image-attach.html` 方案甲）。
 *
 * 位置在输入框**上沿**、卡片内部 —— 它是"这条消息的一部分"，不是状态卡那种常驻控件。
 * **空的时候整个收起来**（与 [QueueStrip] 同一个做法：出生即不可见，可见性由
 * [add] / [remove] / [clear] 自己管）—— 一条没有任何附件的输入框不该多出 60px 的空白。
 *
 * 为什么是 56px 的缩略图而不是"一个「2 张图」的徽标"：截图这条路上**粘错**比粘不上
 * 更常见，看不清内容的方案要用户点开才能确认自己粘的是哪张。66px 高换一眼确认，值。
 * （用户 2026-09-15 在四套做法里选了这套。）
 *
 * 上限与缩放都在 [AttachedImage] 那一层，这里只管摆。
 */
internal class AttachmentStrip(
    /**
     * 列表有任何变化就叫一声 —— 面板据此重新布局（卡片高度会变）。
     *
     * 是 `var` 而不是构造参数：面板在**建这个带子的时候**还不知道输入卡在哪
     * （卡片是后建的），只能等建好了再把回调接上。
     */
    var onChanged: () -> Unit = {},
) : JPanel() {

    private val items = mutableListOf<AttachedImage>()
    private val row = JPanel()
    private val hint = JLabel()

    /**
     * 现在摆着的缩略图，与 [items] 一一对应（[rebuild] 时重建）。
     *
     * 留着它是为了 [thumbViewAt] —— 用例要派发真事件，就得先拿到那个组件。
     */
    private val thumbViews = mutableListOf<ThumbView>()

    /**
     * 点缩略图**正文**（不是右上角的 ✕）—— 面板据此打开放大查看。
     *
     * 写成属性而不是构造参数，两个理由：一是与 [onChanged] 同一条（建带子的时候
     * 面板还没拿到输入卡）；二是构造参数加在末尾会把既有用例那句
     * `AttachmentStrip { changes++ }` 的尾随 lambda 抢走 —— 尾随 lambda 绑的
     * 永远是**最后一个**参数，加参数等于悄悄改了所有调用点。
     */
    var onPreview: (List<AttachedImage>, Int) -> Unit = { _, _ -> }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        // 与排队条同源的两个理由：BoxLayout 里不拉伸的子项按 alignmentX 摆位，
        // 默认 0.5（居中）会把它推到中间去
        alignmentX = LEFT_ALIGNMENT
        // 出生即隐藏，等 [add] 来点亮（空队列那条同理）
        isVisible = false

        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.alignmentX = LEFT_ALIGNMENT

        hint.font = JBUI.Fonts.smallFont()
        hint.foreground = dangerColor()
        hint.alignmentX = LEFT_ALIGNMENT
        hint.isVisible = false

        add(row)
        add(hint)
    }

    val images: List<AttachedImage> get() = items.toList()

    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * 收下一张图。**满了就不收**，并把原因写在带子上（[MAX_IMAGES] 条）。
     *
     * 不加到上限就静默丢弃 —— 用户刚按了 Ctrl+V，界面上什么都没发生，那看起来就是坏了。
     */
    fun add(image: AttachedImage): Boolean {
        if (items.size >= MAX_IMAGES) {
            show("一条消息最多 $MAX_IMAGES 张图")
            return false
        }
        items += image
        clearHint()
        rebuild()
        return true
    }

    /** 这张为什么没收下（太大 / 解不开）。理由直接显示在带子上。 */
    fun reject(reason: String) {
        show(reason)
    }

    fun remove(image: AttachedImage) {
        if (!items.remove(image)) return
        clearHint()
        rebuild()
    }

    /** 发出去之后清空。**可见性也在这里归位** —— 谁改列表谁管显示。 */
    fun clear() {
        if (items.isEmpty() && !isVisible) return
        items.clear()
        clearHint()
        rebuild()
    }

    private fun rebuild() {
        row.removeAll()
        thumbViews.clear()
        items.forEachIndexed { index, image -> row.add(buildItem(image, index)) }
        isVisible = items.isNotEmpty()
        revalidate()
        repaint()
        onChanged()
    }

    /**
     * 第 [index] 张的缩略图。**给用例**：拿到它才谈得上"派发一次真的点击"
     * （见 `AttachmentStripTest` 里那三条）。越界返回 null。
     */
    internal fun thumbViewAt(index: Int): ThumbView? = thumbViews.getOrNull(index)

    private fun show(reason: String) {
        hint.text = reason
        hint.isVisible = true
        // 理由本身要占地方（它可能就是用户唯一能看到的反馈），所以这里**不**改
        // 整个带子的可见性：只显示一行字，不带缩略图
        isVisible = true
        revalidate()
        repaint()
        onChanged()
    }

    private fun clearHint() {
        hint.isVisible = false
        hint.text = ""
    }

    /** 一张图 = 缩略图（右上角压着 ✕）+ 底下一行文件名。 */
    private fun buildItem(image: AttachedImage, index: Int): JComponent {
        val box = JPanel(null).apply {
            isOpaque = false
            // 名字那一行也要算进来，否则一排缩略图的长短会各不相同
            preferredSize = Dimension(JBUI.scale(THUMB_W), JBUI.scale(THUMB_H) + JBUI.scale(14))
            maximumSize = preferredSize
            alignmentY = TOP_ALIGNMENT
        }
        // 两个回调里捕获的 index 不会过期：列表一变 [rebuild] 就把这排视图整个重建了
        val thumb = ThumbView(
            image.thumb,
            onRemove = { remove(image) },
            onOpen = { onPreview(images, index) },
        ).apply {
            setBounds(0, 0, JBUI.scale(THUMB_W), JBUI.scale(THUMB_H))
        }
        thumbViews += thumb
        box.add(thumb)
        box.add(
            JLabel(image.name).apply {
                font = JBUI.Fonts.smallFont()
                foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
                toolTipText = image.name
                setBounds(0, JBUI.scale(THUMB_H) + JBUI.scale(1), JBUI.scale(THUMB_W), JBUI.scale(13))
            }
        )
        // 每张右边留 6px：一排缩略图粘成一坨就分不出是两张还是一张
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentY = TOP_ALIGNMENT
            add(box)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)))
        }
    }
}

/**
 * 缩略图本身：圆角裁切 + 一圈描边 + **右上角那个 ✕**。
 *
 * ✕ 是**画在同一层里**的，不是叠上去的兄弟组件 —— 2026-09-15 试过"缩略图一个组件、
 * ✕ 一个组件"的做法：组件树里两者都在、位置也对，出图却只有缩略图。原因是重叠的
 * 兄弟组件谁盖谁由 z 序决定，而"先 add 的反而在上面"，与直觉相反。
 * 画进同一层没有这个问题，也省掉了 hit-test 之外的同步。
 *
 * ✕ 自己画圆底（不用字形）——它压在缩略图上，没有底色的话在浅色截图
 * （白底的文档）上会整个看不见。圆的形状是设计稿里定的：方角块像补丁。
 *
 * 一击分两处：**✕ 删图、正文放大看**（2026-09-17 起，见 [thumbHit]）——
 * 56px 认不出是哪张，而粘错比粘不上常见。
 */
internal class ThumbView(
    private val image: BufferedImage,
    private val onRemove: () -> Unit,
    /** 点了正文 —— 2026-09-17 起是"放大看"，从前这一块什么都不做。 */
    private val onOpen: () -> Unit = {},
) : JComponent() {

    /** ✕ 的边长。与 [THUMB_W] 同单位，命中判定也用它 —— 画在哪就能点在哪。 */
    private val badgeSide: Int get() = JBUI.scale(BADGE_SIDE)

    init {
        preferredSize = Dimension(JBUI.scale(THUMB_W), JBUI.scale(THUMB_H))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "点图放大看，点右上角的 ✕ 移除"
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // 只认左键：右键点 ✕ 也把图删掉是说不通的（那半边留给以后的右键菜单）
                    if (e.button != MouseEvent.BUTTON1) return
                    when (thumbHit(e.x, e.y, width, badgeSide)) {
                        // ✕ 与正文分开判：缩略图占 56×42，整块都能删的话，
                        // 只是想点开看一眼的人会误删
                        ThumbHit.REMOVE -> onRemove()
                        ThumbHit.OPEN -> onOpen()
                    }
                }
            }
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(4).toDouble()
            val shape = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc)
            // 图先铺进去、再包一层描边：不裁的话圆角外面会露出直角的图角
            val clipped = g2.create()
            try {
                clipped.clip = shape
                clipped.drawImage(image, 0, 0, width, height, null)
            } finally {
                clipped.dispose()
            }
            g2.color = lineColor()
            g2.draw(shape)

            // ✕：实心圆 + 两道白杠，压在右上角
            val d = badgeSide.toDouble()
            val left = width - badgeSide - JBUI.scale(1).toDouble()
            g2.color = dangerColor()
            g2.fill(Ellipse2D.Double(left, 1.0, d, d))
            g2.color = Color.WHITE
            g2.stroke = BasicStroke(
                JBUI.scale(1.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            )
            val m = d * 0.32
            g2.draw(Line2D.Double(left + m, 1 + m, left + d - m, 1 + d - m))
            g2.draw(Line2D.Double(left + d - m, 1 + m, left + m, 1 + d - m))
        } finally {
            g2.dispose()
        }
    }
}

/** 缩略图上那一击落在哪儿。 */
internal enum class ThumbHit { REMOVE, OPEN }

/**
 * 这一点算点 ✕ 还是点正文。
 *
 * 与 [ThumbView] 的 `paintComponent` **用同一组数字**（`width` 与 `badgeSide`
 * 就是画的时候用的那两个）—— 画在哪就能点在哪。**不要 `height`**：✕ 只占
 * 右上角那一块（x 靠右、y 靠上），高的信息在判定里用不上。
 *
 * 抽成顶层纯函数是为了可测：原来是私有类里的 `internal fun inBadge`，
 * `private` 类的成员对测试文件根本不可见，那条"想看一眼却误删"的规矩
 * 一直没被钉住（2026-09-17 这版顺手修掉）。
 */
internal fun thumbHit(x: Int, y: Int, width: Int, badgeSide: Int): ThumbHit =
    if (x >= width - badgeSide && y <= badgeSide) ThumbHit.REMOVE else ThumbHit.OPEN
