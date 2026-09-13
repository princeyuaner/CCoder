package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

/** 详情浮层的内容，以及时长格式。 */
class RunDetailTest {

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

    // ---- 清单段 ----

    @Test
    fun `清单段带上进度计数`() {
        val todos = tracker(todosLabel("甲" to "completed", "乙" to "in_progress")).todos!!
        val labels = labelsIn(buildTodoDetail(todos))

        assertTrue("任务清单" in labels, "要有段标题")
        assertTrue("1/2" in labels, "要带进度计数：$labels")
    }

    @Test
    fun `清单里每条一行，三种状态各有字形`() {
        val todos = tracker(
            todosLabel("甲" to "completed", "乙" to "in_progress", "丙" to "pending")
        ).todos!!
        val labels = labelsIn(buildTodoDetail(todos))

        assertTrue(labels.any { it.startsWith("✓") && it.endsWith("甲") }, "已完成：$labels")
        assertTrue(labels.any { it.startsWith("◐") && it.endsWith("乙") }, "进行中：$labels")
        assertTrue(labels.any { it.startsWith("○") && it.endsWith("丙") }, "待办：$labels")
    }

    // ---- 运行段 ----

    @Test
    fun `运行段带上计数`() {
        val running = tracker(started("t1", "查找 sidecar 启动路径"), started("t2", "核对 SDK 类型")).running
        val labels = labelsIn(buildRunningDetail(running))

        assertTrue("运行中" in labels, "要有段标题")
        assertTrue("2" in labels, "要带计数：$labels")
    }

    @Test
    fun `运行中的任务带上 token 与时长`() {
        val running = tracker(
            started("t1", "查找 sidecar 启动路径"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "usage":{"total_tokens":12400,"duration_ms":8000}}""",
        ).running
        val labels = labelsIn(buildRunningDetail(running))

        assertTrue(labels.any { it == "12.4k tok · 8s" }, "实际：$labels")
    }

    @Test
    fun `运行段空着时说实话，而不是给一个空框`() {
        // 卡上写"空闲"时不该弹得出来，但真弹出来了就得说实话
        assertEquals(listOf("当前没有任务"), labelsIn(buildRunningDetail(emptyList())))
    }

    // ---- 两段分家 ----

    @Test
    fun `点哪张卡只看哪一段，不再有合并浮层`() {
        // 旧版是一个浮层里两段。拆卡之后点哪张卡就该只看哪一段 ——
        // 点"子任务"却弹出"运行中"会让人以为两边是一回事
        val t = tracker(todosLabel("甲" to "pending"), started("t1", "甲"))

        val todoLabels = labelsIn(buildTodoDetail(t.todos!!))
        val runningLabels = labelsIn(buildRunningDetail(t.running))

        assertTrue("任务清单" in todoLabels)
        assertTrue("运行中" !in todoLabels, "清单浮层里不该出现运行段：$todoLabels")
        assertTrue("运行中" in runningLabels)
        assertTrue("任务清单" !in runningLabels, "运行浮层里不该出现清单段：$runningLabels")
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
