package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 任务清单：从 `TodoWrite` 的 tool_use input 里取。
 *
 * 数据一直在手上 —— 之前只是把它当成一个普通的折叠 tool block 显示了，
 * 所以清单会变成一堆过期快照，得滚动去找最新的那份。
 */
class TaskListTest {

    private fun input(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `三种状态各自映射，进行中那项单独取得出来`() {
        val list = todoListOf(
            input(
                """
                {"todos":[
                  {"content":"定位差异","status":"completed","activeForm":"正在定位差异"},
                  {"content":"加指纹校验","status":"completed","activeForm":"正在加指纹校验"},
                  {"content":"修复比对","status":"in_progress","activeForm":"正在修复比对"},
                  {"content":"补测试","status":"pending","activeForm":"正在补测试"}
                ]}
                """.trimIndent()
            )
        )!!

        assertEquals(4, list.total)
        assertEquals(2, list.completed)
        assertEquals("修复比对", list.current)
        assertEquals(TodoState.InProgress, list.items[2].state)
        assertEquals(TodoState.Pending, list.items[3].state)
    }

    @Test
    fun `条目内容是 content 而不是 activeForm`() {
        // activeForm 是"正在做…"的进行时措辞，只适合给进行中那一项做标题；
        // 列表里显示它会让每条都变成"正在…"，读起来像全都在跑
        val list = todoListOf(
            input("""{"todos":[{"content":"修复比对","status":"in_progress","activeForm":"正在修复比对"}]}""")
        )!!

        assertEquals("修复比对", list.items[0].text)
    }

    @Test
    fun `没有进行中的项时 current 为 null`() {
        val list = todoListOf(
            input("""{"todos":[{"content":"甲","status":"completed"},{"content":"乙","status":"pending"}]}""")
        )!!

        assertNull(list.current)
        assertEquals(1, list.completed)
    }

    @Test
    fun `多个进行中只取第一个`() {
        // 模型偶尔会同时标两项。取第一个而不是全列出来 —— 条上只放得下一个
        val list = todoListOf(
            input(
                """{"todos":[
                  {"content":"甲","status":"in_progress"},
                  {"content":"乙","status":"in_progress"}
                ]}"""
            )
        )!!

        assertEquals("甲", list.current)
    }

    @Test
    fun `空清单返回 null —— 显示零值是假信息`() {
        assertNull(todoListOf(input("""{"todos":[]}""")))
    }

    @Test
    fun `不是 todo 事件时返回 null`() {
        assertNull(todoListOf(input("""{"command":"ls"}""")), "别的工具调用的 input 不该被当清单")
        assertNull(todoListOf(input("""{"todos":"nope"}""")), "todos 不是数组")
    }

    @Test
    fun `未知状态当作待办，不丢掉这一条`() {
        // SDK 目前只有三种状态，但联合类型会随版本增长。
        // 丢掉整条会让清单看起来"少了"，比状态不准更糟
        val list = todoListOf(
            input("""{"todos":[{"content":"甲","status":"something_new"}]}""")
        )!!

        assertEquals(1, list.total)
        assertEquals(TodoState.Pending, list.items[0].state)
    }

    @Test
    fun `没有文字的条目跳过，不留空行`() {
        val list = todoListOf(
            input("""{"todos":[{"status":"pending"},{"content":"甲","status":"pending"}]}""")
        )!!

        assertEquals(1, list.total)
        assertEquals("甲", list.items[0].text)
    }

    @Test
    fun `全是没有文字的条目时等同于空清单`() {
        assertNull(todoListOf(input("""{"todos":[{"status":"pending"},{"activeForm":""}]}""")))
    }

    // ---- 新一代：TaskCreate 的 id 与 TaskList 的快照（形状取真实样本）----

    @Test
    fun `从 TaskCreate 的结果里认出 id`() {
        assertEquals("1", taskIdOfCreated("Task #1 created successfully: 写文档"))
        assertEquals("team-7", taskIdOfCreated("Task #team-7 created successfully: 跑测试"))
    }

    @Test
    fun `认不出的结果不硬凑一个 id`() {
        assertNull(taskIdOfCreated("Updated task #1 status"))
        assertNull(taskIdOfCreated(""))
    }

    @Test
    fun `TaskList 的快照逐行读回条目`() {
        val entries = taskEntriesOf("#1 [completed] 写文档\n#2 [pending] 跑测试")!!

        assertEquals(2, entries.size)
        assertEquals("1", entries[0].first)
        assertEquals(TodoItem("写文档", TodoState.Completed), entries[0].second)
        assertEquals("2", entries[1].first)
        assertEquals(TodoState.Pending, entries[1].second.state)
    }

    @Test
    fun `快照里认不出的行跳过，认得出的照读`() {
        val entries = taskEntriesOf("（没有任务）\n#1 [in_progress] 甲\ngarbage")!!

        assertEquals(1, entries.size)
        assertEquals("甲", entries[0].second.text)
        assertEquals(TodoState.InProgress, entries[0].second.state)
    }

    @Test
    fun `一份都认不出的快照返回 null，不返回空表`() {
        // 空表会被读成"清单空了"，把界面上好好的一张卡抹掉 ——
        // 那是在说一件我们并不知道的事
        assertNull(taskEntriesOf("No tasks found"))
        assertNull(taskEntriesOf(""))
    }
}
