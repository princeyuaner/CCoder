package com.ccoder.ui

import com.ccoder.sync.SyncSnapshot
import com.ccoder.text.CcoderText
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.Font
import java.awt.FontMetrics
import java.awt.image.BufferedImage
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 同步气泡（2026-09-24，用户从 `docs/design/sync-bubble.html` 里挑的**甲**）。
 *
 * 这一屏全是纯逻辑：说什么（[syncBubbleModelOf]）、什么时候说（[shouldAnnounceSyncRound]）、
 * 文件名怎么截（[middleTruncate]）。**弹出那一下不在这里** —— 那是个浮层窗口，
 * 无头跑不起来，靠真机冒烟看（观感另有 `SyncBubbleRenderProbe` 出图）。
 *
 * 三条最容易写错的：**拿计划当实际**（没复制过去的也报成过去了）、
 * **一轮报两次**（跑完一次会连着推几次快照）、**首轮全量的计数**（一万多个文件，
 * 列表里只有三条，而"还有几个"必须是真的）。
 */
class SyncBubbleTest {

    private fun snapshot(
        copied: Int = 0,
        deleted: Int = 0,
        failed: Int = 0,
        copiedNames: List<String> = emptyList(),
        deletedNames: List<String> = emptyList(),
        costMs: Long = 480L,
        at: Long? = 1L,
    ) = SyncSnapshot(
        run = com.ccoder.sync.SyncRun.RUNNING,
        lastRoundAtMs = at,
        lastCostMs = costMs,
        copied = copied,
        deleted = deleted,
        failed = List(failed) { com.ccoder.sync.Failed("f$it", "被占用") },
        copiedNames = copiedNames,
        deletedNames = deletedNames,
    )

    // ---------------------------------------------------------------- 说什么

    @Test
    fun `什么动静都没有就不冒泡`() {
        // 稳态下的大多数轮都是这样（只有"已核实一致"）—— 每轮都冒一下就是噪音
        assertNull(syncBubbleModelOf(snapshot()))
    }

    @Test
    fun `只复制了文件`() {
        val model = syncBubbleModelOf(snapshot(copied = 3, copiedNames = listOf("trunk/a.kt")))!!

        assertEquals(
            CcoderText.text("sync.bubble.copied", 3) + " · " + CcoderText.text("sync.bubble.took", 480L),
            model.title,
        )
        assertNull(model.failed)
        assertEquals(listOf("trunk/a.kt"), model.rows.map { it.text })
    }

    @Test
    fun `又复制又删除时，删除单独报一段`() {
        val model = syncBubbleModelOf(snapshot(copied = 2, deleted = 1))!!

        assertEquals(
            CcoderText.text("sync.bubble.copied", 2) + " · " +
                CcoderText.text("sync.bubble.deleted", 1) + " · " +
                CcoderText.text("sync.bubble.took", 480L),
            model.title,
        )
    }

    @Test
    fun `只有删除时不说「已同步 0 个文件」`() {
        val model = syncBubbleModelOf(snapshot(deleted = 2))!!

        assertEquals(
            CcoderText.text("sync.bubble.deletedOnly", 2) + " · " + CcoderText.text("sync.bubble.took", 480L),
            model.title,
        )
    }

    @Test
    fun `有失败就多一句 —— 那是「我改的东西没过去」的唯一现场提示`() {
        val model = syncBubbleModelOf(snapshot(copied = 1, failed = 2))!!

        assertEquals(CcoderText.text("sync.bubble.failed", 2), model.failed)
    }

    @Test
    fun `只有失败、一个都没动过，也冒`() {
        // 复制全失败那一轮：copied 是 0、deleted 是 0，而这恰恰是最该说话的一轮
        val model = syncBubbleModelOf(snapshot(failed = 1))!!

        assertNull(model.rows.firstOrNull())
        assertEquals(CcoderText.text("sync.bubble.failed", 1), model.failed)
    }

    // ---------------------------------------------------------------- 列几行

    @Test
    fun `最多三行，多的折成「还有 N 个」`() {
        val model = syncBubbleModelOf(
            snapshot(
                copied = 5, deleted = 5,
                copiedNames = listOf("a", "b", "c"), deletedNames = listOf("d", "e", "f"),
            )
        )!!

        assertEquals(3, model.rows.size)
        assertEquals(CcoderText.text("sync.bubble.more", 7), model.more, "5+5 个里只列了 3 行")
    }

    @Test
    fun `复制在前，删除补位`() {
        val model = syncBubbleModelOf(
            snapshot(
                copied = 2, deleted = 3,
                copiedNames = listOf("a.kt", "b.kt"), deletedNames = listOf("gone.kt", "also.kt"),
            )
        )!!

        assertEquals(listOf("a.kt", "b.kt", "gone.kt"), model.rows.map { it.text })
        assertFalse(model.rows[0].deleted)
        assertFalse(model.rows[1].deleted)
        assertTrue(model.rows[2].deleted, "第三行是删除补上来的，画的时候要带减号")
    }

    @Test
    fun `一个都不多时不说「还有 0 个」`() {
        assertNull(syncBubbleModelOf(snapshot(copied = 1, copiedNames = listOf("a")))!!.more)
    }

    @Test
    fun `首轮全量：计数是真的，列表只有三条，用时报秒`() {
        // 一万多个文件的那一轮 —— 气泡其实在说"我刚把整个目录搬过去了"，
        // 而"还有几个"必须是**计数减列表**，不是列表自己的长度。
        // 用时也是一样：一分钟的活儿不该写成"61000 毫秒"
        val model = syncBubbleModelOf(
            snapshot(copied = 412, copiedNames = listOf("a", "b", "c"), costMs = 61_000L)
        )!!

        assertEquals(
            CcoderText.text("sync.bubble.copied", 412) + " · " + elapsedText(61),
            model.title,
        )
        assertEquals(3, model.rows.size)
        assertEquals(CcoderText.text("sync.bubble.more", 409), model.more)
    }

    @Test
    fun `快到不足一秒时才用毫秒`() {
        assertEquals(
            CcoderText.text("sync.bubble.copied", 1) + " · " + CcoderText.text("sync.bubble.took", 999L),
            syncBubbleModelOf(snapshot(copied = 1, costMs = 999L))!!.title,
        )
    }

    // ---------------------------------------------------------------- 什么时候说

    @Test
    fun `还没跑完一轮就不冒`() {
        assertFalse(shouldAnnounceSyncRound(snapshot(at = null), announcedAtMs = null))
    }

    @Test
    fun `同一轮只报一次`() {
        val s = snapshot(copied = 3, at = 100L)

        assertTrue(shouldAnnounceSyncRound(s, announcedAtMs = null))
        assertFalse(shouldAnnounceSyncRound(s, announcedAtMs = 100L), "已经报过这一轮了")
        assertTrue(shouldAnnounceSyncRound(s, announcedAtMs = 99L), "换了一轮就该再报")
    }

    @Test
    fun `什么都没动的那一轮不冒（哪怕它是新的一轮）`() {
        assertFalse(shouldAnnounceSyncRound(snapshot(at = 200L), announcedAtMs = 100L))
    }

    // ---------------------------------------------------------------- 文件名怎么截

    private fun metrics(): FontMetrics =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().getFontMetrics(JLabel().font)

    @Test
    fun `放得下就原样`() {
        val m = metrics()
        assertEquals("trunk/a.kt", middleTruncate("trunk/a.kt", m, m.stringWidth("trunk/a.kt") + 4))
    }

    @Test
    fun `放不下时从中间省略 —— 尾巴（文件名）要留住`() {
        val m = metrics()
        val path = "trunk/src/main/kotlin/com/ccoder/sync/very/LongFileName.kt"
        val out = middleTruncate(path, m, m.stringWidth(path) / 2)

        assertTrue(out.contains("…"), "该有省略号：$out")
        assertTrue(out.endsWith("LongFileName.kt"), "文件名不能被截掉：$out")
        assertTrue(out.startsWith("trunk/"), "开头那截也该留着：$out")
        assertTrue(m.stringWidth(out) <= m.stringWidth(path) / 2, "截完还得放得下：$out")
    }

    @Test
    fun `文件名自己都放不下时才从头截它 —— 尾巴永远留着`() {
        val m = metrics()
        val long = "trunk/" + "A".repeat(200) + ".kt"

        val out = middleTruncate(long, m, 60)

        assertTrue(out.startsWith("…"), "头没了才对：$out")
        assertTrue(out.endsWith(".kt"), "扩展名是最后一点线索：$out")
    }

    @Test
    fun `窄到极限就只剩一个省略号，不是空串也不是半截字`() {
        val m = metrics()

        assertEquals("…", middleTruncate("trunk/a.kt", m, 1))
        assertEquals("", middleTruncate("trunk/a.kt", m, 0))
    }

    // ---------------------------------------------------------------- 画出来的字

    /**
     * 2026-09-28 用户报："弹框里的字体颜色太暗了"。
     *
     * 那几行路径原先取的是主题的次要文字色 —— 探针打点量出来，在这块底上只有 1.9:1。
     * 三张探针图我**看过**，还觉得"行，灰的那几行是次要信息"；可这个气泡要说的正是
     * "过去的是哪几个文件"，用户得逐条认。
     *
     * 这里钉两条：**要读的字用正文色**（三行路径）、**强调色不比主题自己给的差**
     * （"还有 N 个 ›"）。红那行不在此列：色相本身就是那句话的意思（失败），
     * 亮一档的红会变成粉色、告警反而弱了 —— 取舍，见 [syncBubbleModelOf] 里"失败也报"。
     */
    @Test
    fun `气泡里要读的那些字，都不比主题自己的字暗`() = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val model = syncBubbleModelOf(
                snapshot(
                    copied = 5, deleted = 1, failed = 1,
                    copiedNames = listOf("trunk/a.kt", "trunk/b.kt", "trunk/c.kt"),
                    deletedNames = listOf("trunk/gone.kt"),
                ),
            )!!
            val root = bubbleContent(model) {}
            val bg = UIUtil.getToolTipBackground()

            // 三行路径：这是内容，用正文色
            val rows = labelsOf(root).filter { it.font.family == Font.MONOSPACED }
            assertEquals(3, rows.size, "三行路径 —— 数对不上说明这一屏变了")
            rows.forEach {
                assertEquals(UIUtil.getLabelForeground(), it.foreground, "路径行不是正文色：${it.text}")
            }

            // "还有 N 个 ›"：小一号的强调色，不许比主题自己那个 focusColor 还暗
            val more = labelsOf(root).single { it.text == CcoderText.text("sync.bubble.more", 3) }
            val ratio = contrastRatio(more.foreground, bg)
            assertTrue(ratio >= contrastRatio(focusColor(), bg), "强调色比主题自己给的还暗：%.2f:1".format(ratio))

            // 一处都不许再退回主题那个"次要文字色"
            labelsOf(root)
                .filter { it.foreground != dangerColor() }
                .forEach {
                    assertNotEquals(UIUtil.getInactiveTextColor(), it.foreground, "「${it.text}」又用回次要色了")
                }
        }
    }

    /** 组件树里所有的标签 —— 字色只有真画出来之后才验得到。 */
    private fun labelsOf(root: Container): List<JLabel> {
        val out = mutableListOf<JLabel>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JLabel) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }
}
