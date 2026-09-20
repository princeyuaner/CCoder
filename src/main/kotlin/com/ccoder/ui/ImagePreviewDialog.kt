package com.ccoder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.ccoder.text.CcoderText

/**
 * 「点开看大图」那个框（2026-09-17）。输入框附件带里点缩略图正文就走到这儿。
 *
 * ## 为什么值得有
 *
 * 56px 的缩略图只够"认出是哪张"，而这条路上**粘错比粘不上常见**（附件带的注释里
 * 写着同一句话）。放大同时就是**发送前的最后一眼** —— 所以看的是
 * [previewImageOf] 解出来的那一份，也就是**正要发给 Claude 的字节**，不是缩略图
 * 放大、也不是转写区那份 JPEG q85。
 *
 * ## 为什么是个框（而不是编辑器标签页）
 *
 * 转写区那个浮层的注释里已经写过：落成临时文件就要管清理，而此刻的问题只是
 * "这是不是那张"。所以：`DialogWrapper`、可拉伸、Esc 关、按钮栏一颗「关闭」。
 *
 * ## 尺寸：开框时按第一张定一次，之后翻页不改窗口大小
 *
 * 四张图尺寸可能各不相同，每翻一张就换一次窗口大小是跳的（而且模态框缩放会闪）。
 * 图**跟着框缩放但永不超过 1:1** —— 放大超过原图只是把像素摊开，越看越糊。
 *
 * 屏幕尺寸是**问来的**（[screenSize]），不是写死的；纯函数 [previewImageSize] /
 * [fitScale] 收的是数字，所以用例直接打它们。
 */
internal class ImagePreviewDialog(
    project: Project?,
    private val images: List<AttachedImage>,
    startIndex: Int,
) : DialogWrapper(project) {

    /** 现在看的是第几张。翻页只改它。 */
    private var index: Int = startIndex.coerceIn(0, max(0, images.size - 1))

    /**
     * 解出来的图，按张缓存 —— 翻回来不该再解一次。
     *
     * 只活在这个框里（关框一起没），值可能是 null（解不开）。**不留进
     * [AttachedImage]**：1568px 一张 ≈5.5MB，常驻四张、五个标签页就是上百 MB，
     * 为一个"可能点"的动作不值。
     */
    private val decoded = HashMap<Int, BufferedImage?>()

    /**
     * 左下角那行字。
     *
     * 文案与可见性**在 `init()` 之前就摆好**：开框尺寸由平台的 pack 算，而那一步
     * 在 `init()` 之后还要走它自己一整套 —— 事后再点亮这一行，19px 就有没被算进去的
     * 风险（行会被压扁）。不压这个注：把该摆的都排在 `init()` 前面，翻页时
     * [showImage] 再改它。
     */
    private val hint = JBLabel(previewHintText(images.size, index)).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getInactiveTextColor()
        // 左边留 6：图铺满放大区时，这行字会紧贴画面的左缘，看着像被裁掉半截
        // （出图之后才看出来的）
        border = JBUI.Borders.empty(4, 6, 0, 6)
        isVisible = images.size > 1
    }

    /** 第一张解出来的图 —— 也是开框尺寸的依据。 */
    private val firstImage: BufferedImage? = if (index in images.indices) decode(index) else null

    /** 开框时放大区多大。翻页不动它，见类注释。 */
    private val initialSize: Dimension = previewImageSize(
        firstImage?.width ?: 0,
        firstImage?.height ?: 0,
        screenSize().width,
        screenSize().height,
    )

    private val canvas = PreviewCanvas().apply {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        // 尺寸在 init() 之前就摆好：pack 是平台做的，事后再改有"框先按空内容
        // 开出来"的风险（同 [hint] 那条）
        preferredSize = initialSize
    }

    /**
     * 正文那一块。
     *
     * 做成属性（而不是只在 [createCenterPanel] 里现 new 一个）是为了**渲染探针**：
     * 平台把 `createCenterPanel` 声明成了 `protected`，探针够不着 ——
     * 本仓 `SettingsDialog` / `ChangelogDialog` 是同一个写法。
     *
     * 必须声明在 [init] **之前**：属性初始化器与 `init` 块按书写顺序执行，
     * 而 `init()` 里就会走到 `createCenterPanel`。
     */
    internal val contentPanel: JComponent = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        add(canvas, BorderLayout.CENTER)
        add(hint, BorderLayout.SOUTH)
    }

    /** 唯一那颗按钮。也声明在 `init` 之前 —— `createActions()` 什么时候被叫不由我们定。 */
    private val closeAction = object : DialogWrapper.DialogWrapperAction(CLOSE_BUTTON_TEXT) {
        override fun doAction(e: ActionEvent) {
            close(OK_EXIT_CODE)
        }
    }

    init {
        isResizable = true
        init()
        showImage(index)
        bindKeys()
    }

    override fun createActions(): Array<Action> = arrayOf(closeAction)

    override fun createCenterPanel(): JComponent = contentPanel

    /**
     * 翻到第 [i] 张。越界就**什么都不做** —— 到头了再按一下不绕回第一张
     * （转写区那个浮层也是这个规矩，绕回去会让人以为自己按错了）。
     *
     * 尺寸不跟着变：窗口大小开框时已定，这里只换画的东西。
     */
    private fun showImage(i: Int) {
        if (i !in images.indices) return
        index = i
        val image = decode(i)
        canvas.image = image
        canvas.message = if (image == null) NO_IMAGE_TEXT else null
        title = images[i].name
        hint.text = previewHintText(images.size, i)
        hint.isVisible = images.size > 1
    }

    private fun decode(i: Int): BufferedImage? =
        decoded.getOrPut(i) { previewImageOf(images[i].bytes) }

    /**
     * ←/→ 翻页、数字键直跳。
     *
     * 绑在 [rootPane] 的 `WHEN_IN_FOCUSED_WINDOW` 上：框里焦点多半在「关闭」那颗按钮上，
     * 挂 `WHEN_FOCUSED` 就永远收不到；而 `WHEN_IN_FOCUSED_WINDOW` 这张表是按**窗口**
     * 查的，挂在窗口的根上最稳（挂内容面板也查得到，但那是靠 Swing 去遍历整棵
     * 窗口树，没必要压这个注）。
     *
     * **数字键不是装饰**：Swing 的焦点系统在某些 LAF 下会吃掉左右键（它们本来可能
     * 是焦点遍历键），数字键不受影响 —— 万一真被吃掉，翻页还有这条路。
     */
    private fun bindKeys() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), KEY_PREV) { showImage(index - 1) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), KEY_NEXT) { showImage(index + 1) }
        DIGIT_KEYS.take(min(images.size, DIGIT_KEYS.size)).forEachIndexed { i, key ->
            bind(KeyStroke.getKeyStroke(key, 0), "ccoder.preview.goto${i + 1}") { showImage(i) }
        }
    }

    private fun bind(key: KeyStroke, name: String, body: () -> Unit) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name)
        rootPane.getActionMap().put(
            name,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = body()
            },
        )
    }

    private companion object {
        val CLOSE_BUTTON_TEXT: String get() = CcoderText.text("common.close")
        const val KEY_PREV = "ccoder.preview.prev"
        const val KEY_NEXT = "ccoder.preview.next"
        val NO_IMAGE_TEXT: String get() = CcoderText.text("composer.imagePreview.gone")

        /** 数字键直跳用的那排键。VK_1..VK_9 是连着的，但**不拿加法去凑**（读的人得查表）。 */
        val DIGIT_KEYS = listOf(
            KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5,
            KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9,
        )
    }
}

/** 打开放大查看。生产路径给 [ClaudePanel] 用；探针与用例能换掉（同 `showChangelogDialog`）。 */
internal fun showImagePreview(project: Project?, images: List<AttachedImage>, index: Int) {
    if (images.isEmpty()) return
    ImagePreviewDialog(project, images, index).show()
}

/**
 * 画布：把图按当前尺寸缩放居中，**永不超过 1:1**。
 *
 * 插值用 BICUBIC 而不是缩略图那边（[thumbOf]）的 BILINEAR：那边一次要画四张、
 * 而且只有 56px；这边是"点开看字"，缩小的质量比那几十毫秒值钱。
 */
private class PreviewCanvas : JComponent() {

    var image: BufferedImage? = null

    /** 没有图可画时中间那一行字（解不开）。 */
    var message: String? = null

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            val img = image
            if (img == null || width <= 0 || height <= 0) {
                message?.let {
                    g2.color = UIUtil.getInactiveTextColor()
                    g2.font = UIUtil.getLabelFont()
                    val fm = g2.fontMetrics
                    g2.drawString(it, (width - fm.stringWidth(it)) / 2, height / 2)
                }
                return
            }
            val s = fitScale(img.width, img.height, width, height)
            val w = max(1, (img.width * s).roundToInt())
            val h = max(1, (img.height * s).roundToInt())
            g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC,
            )
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.drawImage(img, (width - w) / 2, (height - h) / 2, w, h, null)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 图缩进框的倍数。
 *
 * **上限 1.0**：放大超过原图只是把像素摊开，越看越糊 —— "点开放大"要的是
 * 分辨率原样、地方变大，不是插值。边长非正的输入一律当 1.0（画的时候反正不可见，
 * 让调用方少写一处判断）。
 */
internal fun fitScale(imgW: Int, imgH: Int, boxW: Int, boxH: Int): Double {
    if (imgW <= 0 || imgH <= 0 || boxW <= 0 || boxH <= 0) return 1.0
    return min(1.0, min(boxW.toDouble() / imgW, boxH.toDouble() / imgH))
}

/**
 * 放大区开多大：图按 [fitScale] 缩进"屏幕可用区的 [SCREEN_SHARE]"里，
 * 但不小于 [PREVIEW_MIN_W] × [PREVIEW_MIN_H]（小图开出来不该是一个巴掌大的方块）。
 *
 * 屏幕尺寸由调用方去问 —— 这是纯函数，不该知道屏幕在哪；无头环境里
 * `Toolkit.getScreenSize()` 会抛，见 [screenSize]。
 */
internal fun previewImageSize(imgW: Int, imgH: Int, screenW: Int, screenH: Int): Dimension {
    val s = fitScale(imgW, imgH, (screenW * SCREEN_SHARE).toInt(), (screenH * SCREEN_SHARE).toInt())
    return Dimension(
        max(PREVIEW_MIN_W, (imgW * s).roundToInt()),
        max(PREVIEW_MIN_H, (imgH * s).roundToInt()),
    )
}

/** 放大区的最小边长。 */
internal const val PREVIEW_MIN_W = 360
internal const val PREVIEW_MIN_H = 260

/**
 * 左下角那行小字：第几张 / 一共几张 + 怎么翻。
 *
 * 只有一张时是**空串**（那一行整条不显示）：一个"1 / 1"既没用，又白占一行高度。
 */
internal fun previewHintText(count: Int, index: Int): String =
    if (count > 1) "${index + 1} / $count · " + CcoderText.text("composer.imagePreview.paging") else ""

/** 屏幕可用区里留给图的那一份。剩下的给窗口装饰与四周的空白。 */
private const val SCREEN_SHARE = 0.8

/**
 * 屏幕可用区。
 *
 * 取不到就兜底：**无头环境（单测、CI）与多屏的某些配置下会抛**，而"开个看图框"
 * 不该因为问不到屏幕就崩。兜底值不需要准 —— 它只影响开框大小，框可以拉。
 */
private fun screenSize(): Dimension =
    runCatching { Toolkit.getDefaultToolkit().screenSize }
        .getOrNull()
        ?: Dimension(1280, 800)
