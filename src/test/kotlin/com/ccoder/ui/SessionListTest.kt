package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
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

    /** 树里所有挂了鼠标点击响应的容器 —— 也就是"能被点的行"。 */
    private fun clickableRows(root: Container): List<Component> {
        val out = mutableListOf<Component>()
        fun walk(c: Container) {
            for (child in c.components) {
                if (child is Container && child.mouseListeners.isNotEmpty()) out += child
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
}
