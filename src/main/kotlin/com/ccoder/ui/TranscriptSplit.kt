package com.ccoder.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Divider
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * 拖动位置的持久化键。JBSplitter 按这个键读写用户的拖动结果，
 * 所以拖出来的高度在重开工具窗口、甚至重启 IDE 之后都还在。
 */
internal const val TRANSCRIPT_SPLIT_KEY = "ccoder.transcript.split.proportion"

/**
 * 转写区默认占比 —— 底部（权限卡片 + 输入区）拿其余的 0.11。
 *
 * 0.89 而不是 0.78：用户要求默认高度减半（2026-09-11）。
 * 光调这个数不够 —— 底部的最小高度会把它顶回来，见 [COMPOSER_MIN_ROWS]。
 */
private const val DEFAULT_PROPORTION = 0.89f

/**
 * 分隔条的抓取宽度（未缩放 px）。
 *
 * 不用 1：1px 的线好看，但鼠标很难压准，与"自由拖动"的诉求相悖。
 */
private const val DIVIDER_WIDTH = 6

/** 握把的宽（未缩放 px）。32 约等于两个汉字宽 —— 看得见，又不横贯整条。 */
private const val GRIP_WIDTH = 32

/** 握把的高（未缩放 px）。3 + 两边的 1.5 留白正好填满 6px 的分隔条。 */
private const val GRIP_HEIGHT = 3

private val LOG = Logger.getInstance("com.ccoder.ui.TranscriptSplit")

/**
 * 转写区（上）与底部区域（下）之间的可拖动分隔。
 *
 * 用平台的 [JBSplitter] 而不是裸 `JSplitPane`：它的分隔线跟随 IDE 主题、
 * 支持高 DPI，而且 [JBSplitter.setAndLoadSplitterProportionKey] 自带拖动
 * 结果的持久化 —— 不必自己接一套设置存储。
 *
 * `vertical = true` 表示两个组件**上下**排列（分隔条是水平的）。
 * 写成 false 会变成左右分栏，这是个很容易搞反的参数。
 *
 * ## 分隔条上的握把（2026-10-13 用户从选型台选的方案 A）
 *
 * 正中挂一颗 32×3 的常驻圆头短棒，悬停变强调蓝 —— 见 [DividerGripView]。
 * 之前的说法是"分隔条上不挂多余控件"，指的是**平台的**控件/图标开关
 * （历史结论：那两颗小箭头会干扰拖动，测试钉着，这里保持关闭）；记号是
 * 我们自己画的，挂在平台的 divider 里（见 [installDividerGrip]）。
 *
 * **刻意不在这里调 `setAndLoadSplitterProportionKey`**：那一步要读应用级的
 * `PropertiesComponent`，无头单测里拿不到（会 NPE）。挪到 ClaudePanel 里
 * 单独一行，换来的是本函数的"上下左右有没有接反"能被测试钉住。
 */
internal fun buildTranscriptSplit(
    transcript: JComponent,
    bottom: JComponent,
): JBSplitter = JBSplitter(true, DEFAULT_PROPORTION).apply {
    setShowDividerControls(false)
    setShowDividerIcon(false)
    // 不尊重最小尺寸的话，可以把输入区拖成 0 高、再也拖不回来
    setHonorComponentsMinimumSize(true)
    setDividerWidth(JBUI.scale(DIVIDER_WIDTH))
    setFirstComponent(transcript)
    setSecondComponent(bottom)
    // 必须在上面那两个开关**之后** —— 理由见 installDividerGrip 的注释
    installDividerGrip()
}

/**
 * 分隔条正中那颗常驻握把。
 *
 * 一格 32×3 的圆头短棒：静止 [lineColor]（与卡片描边同一支灰），鼠标压上
 * 分隔条时变强调蓝。"不用碰到就知道这儿能拖"是选它的全部理由 —— 悬停才出现
 * 的形态（选型台的 B/C）解决不了这个问题。
 *
 * ## 为什么自己画
 *
 * 平台的 `setShowDividerControls` / `setShowDividerIcon` 在本插件编译的那档
 * 平台上（PyCharm 2026.1，对应 intellij-community 的 261 分支）已是**空实现**：
 * `Splitter.DividerImpl` 只落一个上下拖动的游标，不挂子件也不自绘。拿不到
 * "平台原装握把"，只能自己画（调查记录在选型台 web/tools/splitter-handle-view.mjs）。
 *
 * ## 悬停色为什么不是 [focusColor]
 *
 * 走 [accentTextFor]（与使用量条那排走势条同一个配方：主题的强调色够读就用它，
 * 不够退到兜底亮蓝）—— [focusColor] 是"卡片被指向"的描边色，在这块近黑的底上
 * 会闷一档；选型台上定的就是这一支亮蓝。
 *
 * ## 几何与命中
 *
 * 命中区就是整条 6px 分隔条：这个组件没有任何监听器，指针事件自然落到分隔条上
 * （悬停态由分隔条的进入/离开事件转发，见 [installDividerGrip]）。游标、拖动、
 * 双击复位（平台行为）一个都没动。
 */
internal class DividerGripView : JComponent() {

    private var hot = false

    init {
        isOpaque = false
    }

    /** 指针在不在分隔条上。 */
    fun setHot(value: Boolean) {
        if (hot == value) return
        hot = value
        repaint()
    }

    /** 测试与探针用：此刻显示的是不是"悬停态"那一支。 */
    internal fun isHotForTest(): Boolean = hot

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(GRIP_WIDTH), JBUI.scale(GRIP_HEIGHT))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (hot) {
                accentTextFor(UIUtil.getTreeSelectionBackground(true), UIUtil.getPanelBackground())
            } else {
                lineColor()
            }
            // arc 是**直径**、不是半径（SessionChips 里记过这个坑）：取满高度
            // 正好两端全圆 —— 选型台上那条圆头短棒。
            val arc = JBUI.scale(GRIP_HEIGHT).toFloat()
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 把握把挂到平台的分隔条上。
 *
 * 分隔条是 `Splitter.DividerImpl` —— 一个 [java.awt.GridBagLayout] 的 JPanel，
 * 单个子件正好被摆到正中，所以握把自己不需要知道任何坐标。
 *
 * **这套接线必须发生在 [JBSplitter.setShowDividerControls] 那类调用之后**：
 * `DividerImpl.setOrientation()` 的第一句是 `removeAll()`，之后再碰平台的
 * 分隔控件开关，握把会被静静清掉（单测钉着它挂在 divider 上）。
 *
 * 找不到 divider 只记一条 warn、不抛：那是平台内部结构变了，界面该照常能用
 * —— 只是退回"没有握把"的从前（就是今天的样子）。
 */
private fun JBSplitter.installDividerGrip() {
    val divider = components.filterIsInstance<Divider>().firstOrNull()
    if (divider == null) {
        LOG.warn("CCoder 分隔条：没找到平台的 Divider，握把没挂上（Splitter 的内部结构变了？）")
        return
    }
    val grip = DividerGripView()
    divider.addMouseListener(object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            grip.setHot(true)
        }

        override fun mouseExited(e: MouseEvent) {
            grip.setHot(false)
        }
    })
    divider.add(grip)
}
