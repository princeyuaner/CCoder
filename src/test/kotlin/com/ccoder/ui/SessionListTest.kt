package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * 会话列表的表层。
 *
 * 这里断言的是**用户能看见什么** —— 标题、时间、当前会话的勾、忙时的说明行。
 * 布局细节（间距、颜色）不在单测范围内，那属于设计稿与手工冒烟。
 */
class SessionListTest {

    private val now = 1_700_000_000_000L

    private val sessions = listOf(
        SessionInfo("s1", "还可以做什么功能", "这是什么项目", now - 30_000),
        SessionInfo("s2", "PyCharm插件调用Claude Code", null, now - 3_600_000),
        SessionInfo("s3", null, "你好", now - 90_000_000),
    )

    private fun textsIn(root: Container): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JLabel) out += child.text
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    /**
     * 列表里挂了鼠标点击响应的**行**。
     *
     * 只看列表的直接子项：行内部还有别的交互子件（悬停用的 ✕），
     * 递归扫会把它们也数进来，"哪几行可点"就测不准了。
     */
    private fun clickableRows(root: Container): List<Component> =
        root.components.filter { it is Container && it.mouseListeners.isNotEmpty() }

    /** 树里所有的 JLabel —— 要按文字找某个具体标签时用。 */
    private fun textsAndComponents(root: Container): List<JLabel> {
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

    private fun click(component: Component) {
        component.dispatchEvent(
            MouseEvent(
                component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 5, 5, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    // ---- 内容 ----

    @Test
    fun `标题缺失时回退到首个提问`() {
        val texts = textsIn(buildSessionList(sessions, "s1", SwitchBlock.None) {})
        assertTrue(texts.any { it.contains("PyCharm插件调用Claude Code") })
        assertTrue(texts.any { it.contains("你好") }, "s3 没有 summary，该退回 firstPrompt")
    }

    @Test
    fun `当前会话有勾`() {
        val texts = textsIn(buildSessionList(sessions, "s1", SwitchBlock.None) {})
        assertEquals(1, texts.count { it.startsWith("✓") }, "只该有一条被标记为当前")
    }

    @Test
    fun `摘要与首个提问都为空时显示占位而非空白行`() {
        val blank = listOf(SessionInfo("s9", null, null, now))
        val texts = textsIn(buildSessionList(blank, null, SwitchBlock.None) {})
        assertTrue(texts.any { it.contains("无标题") }, "空白行看起来像渲染坏了")
    }

    @Test
    fun `空列表给一行说明`() {
        val texts = textsIn(buildSessionList(emptyList(), null, SwitchBlock.None) {})
        assertTrue(texts.any { it.contains("没有") }, "实际：$texts")
    }

    // ---- 交互 ----

    @Test
    fun `空闲时每行都可点，点第一行回传它`() {
        val picked = mutableListOf<String>()
        val root = buildSessionList(sessions, "s1", SwitchBlock.None) { picked += it.sessionId }

        val rows = clickableRows(root)
        assertEquals(3, rows.size, "三行都该可点")
        click(rows[0])
        assertEquals(listOf("s1"), picked)
    }

    @Test
    fun `点标题也算点这一行`() {
        // 真鼠标点下去时，事件落在鼠标底下**最深的有监听器的组件**上，
        // 而不是你想的那个容器。标题上有悬停用的监听器，所以点行中间
        // （标题上）时事件是发给标题的 —— 不转发的话，点会话毫无反应。
        //
        // 实测就栽在这儿：既有用例全都直接往"行"上派发事件，绕过了真实
        // 命中路径，所以全绿。
        val picked = mutableListOf<String>()
        val list = buildSessionList(sessions, "s1", SwitchBlock.None) { picked += it.sessionId }
        val row = list.components.filterIsInstance<JComponent>()[0]
        val title = textsAndComponents(row).first { it.text.contains("还可以做什么功能") }

        click(title)

        assertEquals(listOf("s1"), picked, "点在标题上没当成点这一行")
    }

    @Test
    fun `忙时行根本不挂点击响应`() {
        // 断言的是"没有监听器"而不是"点了没反应"：后者在前者为真时恒成立，
        // 是个永远绿的假测试
        val root = buildSessionList(sessions, "s1", SwitchBlock.TurnRunning) {}
        assertTrue(clickableRows(root).isEmpty(), "忙时不能让它切过去 —— 正在跑的回合会被腰斩")
    }

    @Test
    fun `忙时显示拦住的说明`() {
        val texts = textsIn(buildSessionList(sessions, "s1", SwitchBlock.PermissionPending) {})
        assertTrue(texts.any { it.contains("权限") }, "实际：$texts")
    }

    @Test
    fun `空闲时不显示说明行`() {
        val texts = textsIn(buildSessionList(sessions, "s1", SwitchBlock.None) {})
        assertFalse(texts.any { it.contains("先按") })
    }

    // ---- 相对时间 ----

    @Test
    fun `相对时间的分档`() {
        assertEquals("刚刚", relativeTime(now, now - 5_000))
        assertEquals("3 分钟前", relativeTime(now, now - 3 * 60_000))
        assertEquals("2 小时前", relativeTime(now, now - 2 * 3_600_000))
        assertEquals("昨天", relativeTime(now, now - 26 * 3_600_000))
        assertEquals("5 天前", relativeTime(now, now - 5 * 86_400_000))
    }

    @Test
    fun `未来时间不显示成负数`() {
        // 时钟回拨或时区问题都可能造出未来时间戳，不该显示"-3 分钟前"
        assertEquals("刚刚", relativeTime(now, now + 60_000))
    }

    // ---- 删除（Task 4）----

    private val twoSessions = listOf(
        SessionInfo("s1", "还可以做什么功能", null, 1_000L),
        SessionInfo("s2", "这是什么项目", null, 2_000L),
    )

    /** 找一棵组件树里所有 JButton。 */
    private fun buttonsIn(root: Container): List<JButton> {
        val out = mutableListOf<JButton>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is JButton) out += child
                if (child is Container) walk(child)
            }
        }
        walk(root)
        return out
    }

    /** 一行的 ✕。列表里每行一个，按顺序取。 */
    private fun deleteButtonOf(list: JComponent, index: Int) =
        buttonsIn(list).filter { it.text == DELETE_MARK }[index]

    private fun hover(component: Component, entered: Boolean) {
        component.dispatchEvent(
            MouseEvent(
                component,
                if (entered) MouseEvent.MOUSE_ENTERED else MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(), 0, 5, 5, 0, false,
            )
        )
    }

    @Test
    fun `✕ 常驻可见，悬停时提亮`() {
        // 设计稿 §二 A 选的是"悬停才出现"（列表最干净），但实测反馈**两轮**
        // "看不清楚" —— 悬停才出来的东西，人根本没机会看清它是什么。
        // 所以改成常驻。这条守的就是"不悬停也看得见"，别再改回去。
        val list = buildSessionList(twoSessions, currentSessionId = null, block = SwitchBlock.None)
        val row = list.components.filterIsInstance<JComponent>()[0]
        val x = deleteButtonOf(list, 0)

        assertTrue(x.isVisible, "没悬停时也该看得见 ✕")

        val calm = x.foreground
        hover(row, entered = true)
        assertNotEquals(calm, x.foreground, "悬停后该提亮")

        hover(row, entered = false)
        assertEquals(calm, x.foreground, "移开后该回到安静的次要色")
    }

    @Test
    fun `忙时不露删除入口`() {
        // 忙时整列不可点，删除自然也不该露出入口
        val list = buildSessionList(twoSessions, currentSessionId = null, block = SwitchBlock.TurnRunning)

        assertTrue(
            buttonsIn(list).none { it.text == DELETE_MARK && it.isVisible },
            "忙时不该有看得见、点得动的删除入口",
        )
    }

    @Test
    fun `悬停不会让时间标签左右跳`() {
        // ✕ 藏在固定宽度的槽里。若直接把它从布局里拿掉拿进，
        // 时间标签会左右跳一下 —— 那是能看见的抖动
        val list = buildSessionList(twoSessions, currentSessionId = null, block = SwitchBlock.None)
        val row = list.components.filterIsInstance<JComponent>()[0]
        val before = row.preferredSize.width

        hover(row, entered = true)

        assertEquals(before, row.preferredSize.width, "悬停后行宽变了")
    }

    @Test
    fun `点 ✕ 进入确认态，而且不触发切换`() {
        // 这是本次唯一一个"写错了会误删"的点：整行可点、✕ 在行内，
        // 事件冒泡上去就会先切过去，然后你可能正在删一个刚被激活的会话
        var picked: SessionInfo? = null
        var deleted: SessionInfo? = null
        val list = buildSessionList(
            twoSessions, currentSessionId = null, block = SwitchBlock.None,
            onPick = { picked = it },
            onDelete = { deleted = it },
        )

        deleteButtonOf(list, 0).doClick()

        assertNull(picked, "点 ✕ 竟然触发了切换会话")
        assertNull(deleted, "确认之前不该真的删")
        assertTrue(
            textsIn(list).any { it.contains("删除") },
            "没有进入确认态：${textsIn(list)}",
        )
    }

    @Test
    fun `确认后才回调 onDelete`() {
        var deleted: SessionInfo? = null
        val list = buildSessionList(
            twoSessions, currentSessionId = null, block = SwitchBlock.None,
            onDelete = { deleted = it },
        )

        deleteButtonOf(list, 0).doClick()
        // 确认行上的"删除"按钮：确认态那两个按钮之一，文字是"删除"
        buttonsIn(list).first { it.text == "删除" }.doClick()

        assertEquals("s1", deleted?.sessionId)
    }

    @Test
    fun `同一时刻只有一行处于确认态`() {
        // 点了 A 行的 ✕ 又去点 B 行的 ✕，A 行必须收回原样 ——
        // 否则界面上同时挂着两个待确认的删除
        val list = buildSessionList(twoSessions, currentSessionId = null, block = SwitchBlock.None)

        deleteButtonOf(list, 0).doClick()
        // A 行进了确认态，它自己的 ✕ 已经不在树里了 —— 现在只剩 B 行那一个
        deleteButtonOf(list, 0).doClick()

        val confirmRows = textsIn(list).count { it.contains("删除「") }
        assertEquals(1, confirmRows, "同时存在多个确认态：${textsIn(list)}")
    }

    @Test
    fun `取消后回到原样，且没有回调`() {
        var deleted: SessionInfo? = null
        val list = buildSessionList(
            twoSessions, currentSessionId = null, block = SwitchBlock.None,
            onDelete = { deleted = it },
        )

        deleteButtonOf(list, 0).doClick()
        buttonsIn(list).first { it.text == "取消" }.doClick()

        assertNull(deleted)
        assertTrue(
            textsIn(list).any { it.contains("还可以做什么功能") },
            "取消后标题没回来：${textsIn(list)}",
        )
    }

    @Test
    fun `删当前会话时确认语说的是后果`() {
        val list = buildSessionList(twoSessions, currentSessionId = "s1", block = SwitchBlock.None)

        deleteButtonOf(list, 0).doClick()

        assertTrue(
            textsIn(list).any { it.contains("清空") },
            "删当前会话没说明后果：${textsIn(list)}",
        )
    }
}
