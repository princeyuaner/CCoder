package com.ccoder.ui

import com.intellij.openapi.ui.Divider
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.event.MouseEvent
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
    fun `平台的分隔控件保持关闭`() {
        val split = buildTranscriptSplit(comp("a"), comp("b"))

        // 历史结论（实测）：平台那两个开关的小箭头会干扰拖动，一直关着。
        // 注意与握把区分：握把是我们自绘的常驻记号（下一组用例），不是这两颗。
        assertFalse(split.isShowDividerControls, "分隔条上的小箭头会干扰拖动")
        assertFalse(split.isShowDividerIcon, "平台的图标同样关着 —— 记号是我们自己画的")
    }

    @Test
    fun `分隔条中段挂着一颗常驻握把`() {
        val split = buildTranscriptSplit(comp("a"), comp("b"))

        val divider = split.components.filterIsInstance<Divider>().firstOrNull()
        assertNotNull(divider, "平台的 Divider 不在 children 里 —— 握把会跟着静默消失")
        val grip = divider!!.components.filterIsInstance<DividerGripView>().singleOrNull()
        assertNotNull(grip, "握把没挂到分隔条上")
        assertEquals(JBUI.scale(32), grip!!.preferredSize.width, "握把宽度（未缩放 32）")
        assertEquals(JBUI.scale(3), grip.preferredSize.height, "握把高度 —— 与 6px 分隔条成 1:2")
    }

    @Test
    fun `指针压上分隔条，握把进悬停态`() {
        val split = buildTranscriptSplit(comp("a"), comp("b"))
        val divider = split.components.filterIsInstance<Divider>().first()
        val grip = divider.components.filterIsInstance<DividerGripView>().first()

        assertFalse(grip.isHotForTest(), "起步该是静止态")
        divider.dispatchEvent(mouseEvent(divider, MouseEvent.MOUSE_ENTERED))
        assertTrue(grip.isHotForTest(), "指针进来要变悬停态（否则握把永远不会变色）")
        divider.dispatchEvent(mouseEvent(divider, MouseEvent.MOUSE_EXITED))
        assertFalse(grip.isHotForTest(), "离开要回静止态")
    }

    /** 直接派发进 divider —— 与真实路径一致：握把没有监听器，指针事件本来就落在分隔条身上。 */
    private fun mouseEvent(target: Component, id: Int) = MouseEvent(
        target, id, System.currentTimeMillis(), 0, 1, 1, 0, false,
    )

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
