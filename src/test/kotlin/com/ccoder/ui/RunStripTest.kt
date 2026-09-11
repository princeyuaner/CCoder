package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 条上显示什么。
 *
 * 核心规则：**计数永远留着，任务名是锦上添花**。"3/7"和"2"是这条存在的理由；
 * 名字在窄栏里可以被截断，计数不行。所以模型这一层就必须把两者分开 ——
 * 让视图层去"从字符串里抠出计数"是不可能的。
 */
class RunStripTest {

    private fun ev(json: String) = JsonParser.parseString(json).asJsonObject

    private fun status(vararg events: String) = RunStatusTracker().let { t ->
        events.forEach { t.consume(ev(it)) }
        t
    }

    private fun todos(vararg pairs: Pair<String, String>) = buildString {
        append("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"TodoWrite","input":{"todos":[""")
        append(
            pairs.joinToString(",") { (text, st) ->
                """{"content":"$text","status":"$st"}"""
            }
        )
        append("]}}]}}")
    }

    @Test
    fun `没有清单也没有运行中的任务时整条隐藏`() {
        val s = status()

        assertNull(runStripOf(s))
    }

    @Test
    fun `有清单就一定有进度计数，哪怕没有进行中的项`() {
        // 模型声明了 2 条就都在待办，这时"0/2"是真信息 —— 说明它拆了活还没动手。
        // 不能因为"没有进行中的项"就把计数也一起省掉
        val strip = runStripOf(status(todos("甲" to "pending", "乙" to "pending")))!!

        assertEquals("0/2", strip.todoProgress)
        assertNull(strip.currentTask)
    }

    @Test
    fun `进行中的项单独取出来当标题`() {
        val strip = runStripOf(
            status(todos("甲" to "completed", "乙" to "completed", "丙" to "in_progress"))
        )!!

        assertEquals("2/3", strip.todoProgress)
        assertEquals("丙", strip.currentTask)
    }

    @Test
    fun `只有运行中的任务时只有计数`() {
        val strip = runStripOf(
            status(
                """{"type":"system","subtype":"task_started","task_id":"t1","description":"甲","subagent_type":"explore"}"""
            )
        )!!

        assertNull(strip.todoProgress)
        assertNull(strip.currentTask)
        assertEquals(1, strip.runningCount)
    }

    @Test
    fun `清单与运行计数可以同时存在`() {
        val strip = runStripOf(
            status(
                todos("甲" to "in_progress"),
                """{"type":"system","subtype":"task_started","task_id":"t1","description":"甲","subagent_type":"explore"}""",
                """{"type":"system","subtype":"task_started","task_id":"t2","description":"乙","subagent_type":"explore"}""",
            )
        )!!

        assertEquals("0/1", strip.todoProgress)
        assertEquals("甲", strip.currentTask)
        assertEquals(2, strip.runningCount)
    }

    @Test
    fun `ambient 的任务不撑起这条`() {
        // 单独一条：只有监视类任务在跑时这条不该出现，否则它永远挂在那儿
        val s = status(
            """{"type":"system","subtype":"task_started","task_id":"w1","description":"监视","ambient":true}"""
        )

        assertNull(runStripOf(s))
    }

    @Test
    fun `模型清空清单后计数一起消失`() {
        val s = status(
            todos("甲" to "in_progress"),
            todos(), // 空清单 = 清空
        )

        assertNull(runStripOf(s))
    }

    @Test
    fun `空清单的判定与清单本身一致 —— 都不造零值`() {
        // runStripOf 与 todoListOf 是同一条规则的两处落点：取不到就不显示
        val strip = runStripOf(status(todos("甲" to "completed")))!!
        assertTrue(strip.todoProgress == "1/1")
        assertEquals(0, strip.runningCount)
    }
}
