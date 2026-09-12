package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JPanel

/** 上下文行、详情浮层的内容、以及时长格式。 */
class RunStripViewTest {

    private fun ev(json: String) = JsonParser.parseString(json).asJsonObject

    private fun tracker(vararg events: String) = RunStatusTracker().apply {
        events.forEach { consume(ev(it)) }
    }

    private fun todosLabel(vararg pairs: Pair<String, String>) =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"TodoWrite","input":{"todos":[${
            pairs.joinToString(",") { (t, s) -> """{"content":"$t","status":"$s"}""" }
        }]}}]}}"""

    private fun started(id: String, desc: String) =
        """{"type":"system","subtype":"task_started","task_id":"$id","description":"$desc","subagent_type":"explore"}"""

    /** 深度优先收集所有 JLabel 的文字 —— 结构断言够用了，不必去比对像素。 */
    private fun labelsIn(root: Component): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is JLabel) out += c.text
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out
    }

    // ---- 上下文行 ----

    @Test
    fun `上下文行里状态与用量在左、条在右`() {
        // 用户明确要的就是这个位置关系：条挂在上下文右边；
        // 连接状态也挪到这一行（它原先独占顶部一行，那一行只为了它撑高度）
        val status = JLabel()
        val usage = JLabel()
        val strip = JPanel()

        val row = buildContextRow(status, usage, strip)
        val layout = row.layout as BorderLayout
        val left = layout.getLayoutComponent(BorderLayout.WEST) as JPanel

        assertSame(strip, layout.getLayoutComponent(BorderLayout.EAST), "条在右")
        assertTrue(left.components.contains(status), "状态该在左边那一组里")
        assertTrue(left.components.contains(usage), "用量该在左边那一组里")
        assertTrue(
            left.components.indexOf(status) < left.components.indexOf(usage),
            "状态该排在用量前面",
        )
    }

    // ---- 详情 ----

    @Test
    fun `详情分两段，各自带上计数`() {
        val labels = labelsIn(
            buildRunDetail(
                tracker(
                    todosLabel("甲" to "completed", "乙" to "in_progress"),
                    started("t1", "查找 sidecar 启动路径"),
                    started("t2", "核对 SDK 类型"),
                )
            )
        )

        assertTrue("任务清单" in labels, "要有清单段的标题")
        assertTrue("1/2" in labels, "清单段要带进度计数")
        assertTrue("运行中" in labels, "要有运行段的标题")
        assertTrue("2" in labels, "运行段要带计数")
    }

    @Test
    fun `清单里每条一行，三种状态各有字形`() {
        val labels = labelsIn(
            buildRunDetail(tracker(todosLabel("甲" to "completed", "乙" to "in_progress", "丙" to "pending")))
        )

        assertTrue(labels.any { it.startsWith("✓") && it.endsWith("甲") }, "已完成：$labels")
        assertTrue(labels.any { it.startsWith("◐") && it.endsWith("乙") }, "进行中：$labels")
        assertTrue(labels.any { it.startsWith("○") && it.endsWith("丙") }, "待办：$labels")
    }

    @Test
    fun `运行中的任务带上 token 与时长`() {
        val labels = labelsIn(
            buildRunDetail(
                tracker(
                    started("t1", "查找 sidecar 启动路径"),
                    """{"type":"system","subtype":"task_progress","task_id":"t1",
                        "usage":{"total_tokens":12400,"duration_ms":8000}}""",
                )
            )
        )

        assertTrue(labels.any { it == "12.4k tok · 8s" }, "实际：$labels")
    }

    @Test
    fun `只有运行中时不出现清单段`() {
        // 空段落标题比没有段落更糟：它让人以为清单是空的，而实际是模型还没拆活
        val labels = labelsIn(buildRunDetail(tracker(started("t1", "甲"))))

        assertTrue("运行中" in labels)
        assertTrue("任务清单" !in labels, "没有清单时不该出现清单标题：$labels")
    }

    @Test
    fun `只有清单时不出现运行段`() {
        val labels = labelsIn(buildRunDetail(tracker(todosLabel("甲" to "pending"))))

        assertTrue("任务清单" in labels)
        assertTrue("运行中" !in labels)
    }

    @Test
    fun `两段都空时给出实话，而不是一个空框`() {
        val labels = labelsIn(buildRunDetail(tracker()))

        assertEquals(listOf("当前没有任务"), labels)
    }

    // ---- 时长 ----

    @Test
    fun `时长按量级给不同精度`() {
        assertEquals("0s", formatDuration(400))
        assertEquals("8s", formatDuration(8_000))
        assertEquals("59s", formatDuration(59_999))
        assertEquals("1m35s", formatDuration(95_000))
        assertEquals("1h02m", formatDuration(3_720_000))
    }

    @Test
    fun `时长用 ROOT locale，不随系统语言变成逗号`() {
        // 某些语言下 %.2f 之类会输出逗号，这里虽全是整数格式，
        // 但整条链路统一 locale 才不会被将来的改动咬到
        assertEquals("1m05s", formatDuration(65_000))
    }
}
