package com.ccoder.ui

import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncSnapshot
import com.ccoder.sync.SyncTexts
import com.ccoder.text.CcoderText
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * 点同步卡弹出来的那一屏（2026-09-24）。
 *
 * 它是个**有状态**的视图（不像另外两屏是一次成形的），因为同步每跑完一轮就多几行、
 * 而用户点开它多半正是为了盯着看 —— 所以这里钉三件事：**空日志给一句话**、
 * **状态词跟着快照走**、**再灌一份不重建**（就地更新，那正是它做成类的理由）。
 */
class SyncLogDetailTest {

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    @Test
    fun `空日志给一句话，不留空框`() = onEdt {
        val view = SyncLogDetail()

        view.setSnapshot(SyncSnapshot(run = SyncRun.RUNNING, log = emptyList()))

        assertEquals(
            CcoderText.text("sync.page.logEmpty"),
            view.textForTest(),
            "空框会让用户分不清是「还没有」还是「坏了」",
        )
    }

    @Test
    fun `有日志就照抄，最近的在最后`() = onEdt {
        val view = SyncLogDetail()

        view.setSnapshot(
            SyncSnapshot(run = SyncRun.RUNNING, log = listOf("[开始] 基线轮", "[完成] 复制 2 项")),
        )

        assertEquals("[开始] 基线轮\n[完成] 复制 2 项", view.textForTest())
    }

    @Test
    fun `状态词跟着快照走`() = onEdt {
        val view = SyncLogDetail()

        view.setSnapshot(SyncSnapshot(run = SyncRun.RUNNING))
        assertEquals(CcoderText.text(SyncTexts.runKey(SyncRun.RUNNING)), view.stateForTest())

        view.setSnapshot(SyncSnapshot(run = SyncRun.FAILED))
        assertEquals(CcoderText.text(SyncTexts.runKey(SyncRun.FAILED)), view.stateForTest())
    }

    @Test
    fun `再灌一份是就地更新，不重建那一屏`() = onEdt {
        // 就地更新是它做成类（而不是 `buildXxx` 函数）的全部理由：用户正盯着它看，
        // 每跑完一轮就拆窗重建的话，滚动位置与眼睛的位置都会跳
        val view = SyncLogDetail()
        view.setSnapshot(SyncSnapshot(run = SyncRun.RUNNING, log = listOf("第一轮")))
        val componentCount = view.componentCount

        view.setSnapshot(SyncSnapshot(run = SyncRun.RUNNING, log = listOf("第一轮", "第二轮")))

        assertEquals(componentCount, view.componentCount, "那一屏被重建了")
        assertTrue(view.textForTest().contains("第二轮"), "新的那行得进去")
    }

    /**
     * 2026-09-28 用户报："弹框里的字体颜色太暗了"。
     *
     * 这一屏从做出来到现在**一张图都没出过**（探针是跟着这条一起补的），发闷的是那句
     * 小标题（取的是主题的次要文字色，在这块底上只有 1.9:1）与右边那个状态词
     * （`focusColor`，小一号的字压在门槛下）。现在：正文与小标题用**正文色**，
     * 状态词**不比主题自己给的强调色差**，一处都不许再退回次要色。
     */
    @Test
    fun `那一屏的字，都不比主题自己的字暗`() = IdeLaf.withRealLaf {
        onEdt {
            val view = SyncLogDetail().apply {
                setSnapshot(
                    SyncSnapshot(run = SyncRun.RUNNING, log = listOf("[完成] 复制 12/12，删除 3 项（用时 480 ms）")),
                )
            }

            val bg = UIUtil.getListBackground()
            val texts = textViewsOf(view)
            assertTrue(texts.size >= 3, "小标题 + 状态词 + 正文，捞到 ${texts.size} 个，这断言就废了")

            // 小标题与正文：正文色（层级由小一号的字承担，不由颜色）
            val header = texts.single { textOf(it) == CARD_SYNC }
            assertEquals(UIUtil.getLabelForeground(), header.foreground, "小标题没走正文色")
            val body = texts.filterIsInstance<JTextComponent>().single()
            assertEquals(UIUtil.getLabelForeground(), body.foreground, "日志正文没走正文色")

            // 状态词：小一号的强调色，不许比主题自己那个 focusColor 还暗
            val state = texts.single { textOf(it) == view.stateForTest() }
            val ratio = contrastRatio(state.foreground, bg)
            assertTrue(ratio >= contrastRatio(focusColor(), bg), "状态词比主题自己给的还暗：%.2f:1".format(ratio))

            // 一处都不许再退回主题那个"次要文字色"
            texts.forEach {
                assertNotEquals(UIUtil.getInactiveTextColor(), it.foreground, "「${textOf(it).take(24)}」又用回次要色了")
            }
        }
    }

    private fun textOf(c: JComponent): String = when (c) {
        is JLabel -> c.text
        is JTextComponent -> c.text
        else -> ""
    }

    /** 组件树里所有会画字的件（标签 + 文本框）—— 字色只有真画出来之后才验得到。 */
    private fun textViewsOf(root: Container): List<JComponent> {
        val out = mutableListOf<JComponent>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JLabel || child is JTextComponent) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    @Test
    fun `日志再长也只倒末尾那些`() = onEdt {
        // 快照本身就是有界的（`SyncService.LOG_KEEP`），但界面这层也得有底 ——
        // 一屏 380×280 塞不下六十行，多出来的部分是看不见的
        val view = SyncLogDetail()
        val many = (1..60).map { "第 $it 行" }

        view.setSnapshot(SyncSnapshot(run = SyncRun.RUNNING, log = many))

        val lines = view.textForTest().lines()
        assertTrue(lines.size <= 40, "倒了 ${lines.size} 行")
        assertEquals("第 60 行", lines.last(), "要留最近的那些")
    }
}
