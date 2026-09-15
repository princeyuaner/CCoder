package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.JButton

/**
 * 把文字当按钮用的那种：队列条上的 ✕、会话行里的「删除」。
 *
 * ## 尺寸必须自己钉死
 *
 * New UI 的 LAF 给**每一个 `JButton`** 兜了一个 72px 的最小宽度。这些按钮是
 * "只想占字宽"的，于是各自坐在一个 72px 宽的透明盒子里居中 —— 摆在 EAST 也贴不到边。
 * 2026-09-15 在真机 LAF（New UI）下量到：排队条那一列 ✕ 浮在离右边缘 35px 的地方，
 * 而 [QueueStrip] 的注释里写着"一律贴右边缘"。
 *
 * 同一个坑在顶行那两个图标上是 62px（见 [TopRowIconButton]，那里有完整推导）。
 * 它是**布局里的隐形盒子**：盒子画都不画，尺寸却照样占着 —— 只看截图会以为是
 * "端末没对齐"，改对齐方式只会白改。
 *
 * 宽度取"字宽 + 两侧各 6px"：够点，又不会把自己撑成一块砖。高度与顶行那两个
 * 图标按钮同档（18），一行里不会凹凸。
 *
 * 文字按钮（「取消」「删除」「提交」这些）**不要**用它 —— 那里 72px 的最小宽度
 * 正是 New UI 想要的对话框按钮规格。
 */
internal class LinkButton(text: String) : JButton(text) {

    override fun getPreferredSize(): Dimension {
        // 没有父组件时 font 可能是 null（旧 RunStripView 上踩过），退回标签字体
        val fm = getFontMetrics(font ?: UIUtil.getLabelFont())
        return Dimension(
            fm.stringWidth(text) + JBUI.scale(12),
            maxOf(fm.height, JBUI.scale(18)),
        )
    }

    /** 最小 = 首选：被挤也不缩，✕ 不会忽大忽小。 */
    override fun getMinimumSize(): Dimension = preferredSize

    /** 最大 = 首选：不许被拉宽 —— 拉宽就又把内容推到盒子中间去了。 */
    override fun getMaximumSize(): Dimension = preferredSize
}
