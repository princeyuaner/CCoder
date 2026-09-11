package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JPanel

/**
 * 转写区与输入区之间的可拖动分隔。
 *
 * 这里能起单测是因为实测确认平台的 JBSplitter 在无头环境里可以构造
 * （不像 PlatformTheme 那条路径需要真实主题）。
 */
class TranscriptSplitTest {

    private fun comp(name: String) = JPanel().apply { this.name = name }

    @Test
    fun `转写区在上、输入区在下`() {
        val transcript = comp("transcript")
        val input = comp("input")

        val split = buildTranscriptSplit(transcript, input)

        assertTrue(split.isVertical, "上下排列才是 vertical；写成 false 会变成左右分栏")
        assertSame(transcript, split.firstComponent, "第一个组件必须在上方")
        assertSame(input, split.secondComponent, "第二个组件必须在下方")
    }

    @Test
    fun `分隔条足够宽以便拖动`() {
        val split = buildTranscriptSplit(comp("a"), comp("b"))

        // 1px 的线好看但抓不住。用户要的是"自由拖动"
        assertTrue(split.dividerWidth >= 4, "实际 ${split.dividerWidth}px，太窄了拖不动")
    }

    @Test
    fun `分隔条上不挂多余控件`() {
        val split = buildTranscriptSplit(comp("a"), comp("b"))

        assertFalse(split.isShowDividerControls, "分隔条上的小箭头会干扰拖动")
    }

    @Test
    fun `尊重组件最小尺寸，避免把输入区拖没`() {
        val split = buildTranscriptSplit(comp("a"), comp("b"))

        assertTrue(split.isHonorMinimumSize, "不尊重最小尺寸时可以把输入区拖成 0 高")
    }

    @Test
    fun `持久化键是稳定的非空常量`() {
        // 拖动结果本身由 ClaudePanel 调 setAndLoadSplitterProportionKey 绑定
        // （那一步要应用级的 PropertiesComponent，无头环境拿不到，测不了）。
        // 这里只能守住常量本身：键一旦被改名，用户已保存的高度会静默丢失
        // —— 不报错、只是回到默认值，很难被发现。
        assertTrue(TRANSCRIPT_SPLIT_KEY.isNotBlank())
        assertEquals("ccoder.transcript.split.proportion", TRANSCRIPT_SPLIT_KEY)
    }
}
