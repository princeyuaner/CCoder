package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 运行中的任务（子代理、后台命令等）。
 *
 * 这一个类存在的理由是 [背景任务集合是电平信号]：SDK 专门警告过，
 * 靠 started 加一、notification 减一来维护"N 个运行中"，漏掉任何一个事件
 * 就会永远卡在错误的数字上，而且没有任何迹象说明它卡住了。
 */
class RunStatusTrackerTest {

    private fun ev(json: String) = JsonParser.parseString(json).asJsonObject

    private fun started(id: String, extra: String = "") =
        """{"type":"system","subtype":"task_started","task_id":"$id","description":"任务 $id"$extra}"""

    private fun trackerAfter(vararg events: String) =
        RunStatusTracker().apply { events.forEach { consume(ev(it)) } }

    // ---- 边沿信号 ----

    @Test
    fun `task_started 让任务出现，类型取 subagent_type`() {
        val t = trackerAfter(
            """{"type":"system","subtype":"task_started","task_id":"t1",
                "description":"查找 sidecar 启动路径","subagent_type":"explore"}"""
        )

        assertEquals(1, t.running.size)
        assertEquals("explore", t.running[0].kind)
        assertEquals("查找 sidecar 启动路径", t.running[0].label)
    }

    @Test
    fun `类型缺失时退到 task_type`() {
        // 后台命令没有 subagent_type，只有 task_type（如 local_bash）
        val t = trackerAfter(
            """{"type":"system","subtype":"task_started","task_id":"t1",
                "description":"npm run build","task_type":"local_bash"}"""
        )

        assertEquals("local_bash", t.running[0].kind)
    }

    @Test
    fun `task_notification 把任务移走`() {
        val t = trackerAfter(
            started("t1"),
            """{"type":"system","subtype":"task_notification","task_id":"t1","status":"completed"}""",
        )

        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `task_updated 的终态把任务移走，非终态不动`() {
        val t = trackerAfter(
            started("t1"), started("t2"),
            """{"type":"system","subtype":"task_updated","task_id":"t1","patch":{"status":"completed"}}""",
            """{"type":"system","subtype":"task_updated","task_id":"t2","patch":{"status":"running"}}""",
        )

        assertEquals(listOf("t2"), t.running.map { it.id })
    }

    // ---- 电平信号（核心）----

    @Test
    fun `background_tasks_changed 是整集替换，不是加减`() {
        // 这是整个类存在的理由。若实现成"started 加一、notification 减一"，
        // 漏掉 t2 的结束通知就会永远显示 2 个在跑 —— 而且看不出它错了。
        val t = trackerAfter(
            started("t1"), started("t2"),
            """{"type":"system","subtype":"background_tasks_changed","tasks":[
                {"task_id":"t1","task_type":"local_agent","description":"甲"}
            ]}""",
        )

        assertEquals(listOf("t1"), t.running.map { it.id })
    }

    @Test
    fun `只靠电平信号也能建出列表，它自带描述`() {
        // SDK 文档说电平可能先于 started 到达，所以它必须能独立成事
        val t = trackerAfter(
            """{"type":"system","subtype":"background_tasks_changed","tasks":[
                {"task_id":"t1","task_type":"local_agent","description":"甲"},
                {"task_id":"t2","task_type":"local_bash","description":"npm run build"}
            ]}"""
        )

        assertEquals(listOf("t1", "t2"), t.running.map { it.id })
        assertEquals("npm run build", t.running[1].label)
    }

    @Test
    fun `电平信号里的空集把列表清空`() {
        // 会话重启后 SDK 会发一个空快照，正是为了让指示器归零
        val t = trackerAfter(
            started("t1"),
            """{"type":"system","subtype":"background_tasks_changed","tasks":[]}""",
        )

        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `ambient 的任务不进列表`() {
        // 文档原话：这类任务 "are not activity"，hosts 应把它们排除在活动指示器外。
        // 不过滤的话 live-update watcher 会让指示器永远不归零
        val t = trackerAfter(
            """{"type":"system","subtype":"background_tasks_changed","tasks":[
                {"task_id":"w1","task_type":"local_agent","description":"监视","ambient":true},
                {"task_id":"t1","task_type":"local_agent","description":"甲"}
            ]}"""
        )

        assertEquals(listOf("t1"), t.running.map { it.id })
    }

    @Test
    fun `started 上标了 ambient 的同样不进列表`() {
        val t = trackerAfter(started("w1", ""","ambient":true"""))

        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `started 上标了 skip_transcript 的也不进列表`() {
        // 文档：每一个 skip_transcript 任务都是 ambient
        val t = trackerAfter(started("w1", ""","skip_transcript":true"""))

        assertTrue(t.running.isEmpty())
    }

    // ---- 详情 ----

    @Test
    fun `task_progress 补上 token 与时长`() {
        val t = trackerAfter(
            started("t1"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "usage":{"total_tokens":12400,"tool_uses":3,"duration_ms":8000}}""",
        )

        assertEquals(12_400, t.running[0].tokens)
        assertEquals(8_000, t.running[0].durationMs)
    }

    @Test
    fun `task_progress 的 summary 优先于 description`() {
        // summary 是模型生成的一行进度，比一开始的 description 更贴近"现在在干嘛"
        val t = trackerAfter(
            started("t1"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "description":"任务 t1","summary":"正在遍历目录",
                "usage":{"total_tokens":1,"duration_ms":1}}""",
        )

        assertEquals("正在遍历目录", t.running[0].detail)
    }

    @Test
    fun `task_progress 只补充，不凭空造出任务`() {
        // 否则一个 ambient 任务只要冒出一次 progress 就会重新出现在列表里
        val t = trackerAfter(
            """{"type":"system","subtype":"task_progress","task_id":"ghost",
                "summary":"幽灵","usage":{"total_tokens":1,"duration_ms":1}}"""
        )

        assertTrue(t.running.isEmpty())
    }

    @Test
    fun `电平信号只定成员，不抹掉已经学到的 token 与时长`() {
        // 电平信号不带 usage。若替换时一并丢掉详情，每次成员变动浮层里的
        // token 都会闪回 0，看起来像任务重启了
        val t = trackerAfter(
            started("t1"),
            """{"type":"system","subtype":"task_progress","task_id":"t1",
                "usage":{"total_tokens":12400,"duration_ms":8000}}""",
            """{"type":"system","subtype":"background_tasks_changed","tasks":[
                {"task_id":"t1","task_type":"local_agent","description":"甲"}
            ]}""",
        )

        assertEquals(12_400, t.running[0].tokens)
        assertEquals(8_000, t.running[0].durationMs)
    }

    @Test
    fun `未知 subtype 既不清空已有任务也不抛错`() {
        val t = trackerAfter(
            started("t1"),
            """{"type":"system","subtype":"某个以后才有的类型","task_id":"t1"}""",
        )

        assertEquals(listOf("t1"), t.running.map { it.id })
    }

    // ---- 任务清单 ----

    @Test
    fun `助手消息里的 TodoWrite 更新清单`() {
        val t = trackerAfter(
            """{"type":"assistant","message":{"content":[
                {"type":"text","text":"我来拆一下"},
                {"type":"tool_use","name":"TodoWrite","input":{"todos":[
                  {"content":"甲","status":"completed"},
                  {"content":"乙","status":"in_progress"}
                ]}}
            ]}}"""
        )

        assertEquals(2, t.todos?.total)
        assertEquals("乙", t.todos?.current)
    }

    @Test
    fun `别的工具调用不动清单`() {
        val t = trackerAfter(started("t1"))
        val before = t.todos

        t.consume(
            ev(
                """{"type":"assistant","message":{"content":[
                    {"type":"tool_use","name":"Bash","input":{"command":"ls"}}
                ]}}"""
            )
        )

        assertEquals(before, t.todos)
        assertNull(t.todos)
    }

    @Test
    fun `reset 把清单和任务一起清掉`() {
        val t = trackerAfter(
            started("t1"),
            """{"type":"assistant","message":{"content":[
                {"type":"tool_use","name":"TodoWrite","input":{"todos":[{"content":"甲","status":"pending"}]}}
            ]}}""",
        )

        t.reset()

        assertTrue(t.running.isEmpty())
        assertNull(t.todos)
    }
}
