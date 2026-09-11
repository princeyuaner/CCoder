package com.ccoder.ui

import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * 拖动位置的持久化键。JBSplitter 按这个键读写用户的拖动结果，
 * 所以拖出来的高度在重开工具窗口、甚至重启 IDE 之后都还在。
 */
internal const val TRANSCRIPT_SPLIT_KEY = "ccoder.transcript.split.proportion"

/** 输入区默认占比（转写区占其余的 0.78）。 */
private const val DEFAULT_PROPORTION = 0.78f

/**
 * 分隔条的抓取宽度（未缩放 px）。
 *
 * 不用 1：1px 的线好看，但鼠标很难压准，与"自由拖动"的诉求相悖。
 */
private const val DIVIDER_WIDTH = 6

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
}
