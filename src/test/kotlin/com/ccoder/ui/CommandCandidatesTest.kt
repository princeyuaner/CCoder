package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 三份来源拼成候选（设计稿 §4.1）。
 *
 * A = supportedCommands()（显示信息）、B = init 的可发送名、C = reloadSkills()（分组）。
 */
class CommandCandidatesTest {

    private fun cmd(
        name: String,
        description: String? = null,
        hint: String? = null,
        aliases: List<String> = emptyList(),
    ) = CommandInfo(name, description, hint, aliases)

    @Test
    fun `显示名与可发送名不同时，显示 A 插入 B`() {
        val out = commandCandidates(
            commands = listOf(cmd("Debug Issue", "查问题")),
            skills = emptyList(),
            sendable = setOf("debug-issue"),
        )

        assertEquals(1, out.size)
        assertEquals("Debug Issue", out[0].display)
        assertEquals("debug-issue", out[0].insert, "发出去必须是 kebab，否则会被当普通文本")
    }

    @Test
    fun `配不上可发送名的命令直接不出现`() {
        val out = commandCandidates(
            commands = listOf(cmd("Debug Issue"), cmd("compact")),
            skills = emptyList(),
            sendable = setOf("compact"),
        )

        assertEquals(listOf("compact"), out.map { it.display })
    }

    @Test
    fun `名字归一化：小写、空白折成连字符`() {
        assertEquals("debug-issue", normalizeCommandName("Debug Issue"))
        assertEquals("debug-issue", normalizeCommandName("  DEBUG   issue "))
        assertEquals("code-review:code-review", normalizeCommandName("code-review:code-review"))
    }

    @Test
    fun `在技能列表里的进技能组，其余进内置组`() {
        val out = commandCandidates(
            commands = listOf(cmd("compact"), cmd("brainstorming")),
            skills = listOf(cmd("brainstorming")),
            sendable = setOf("compact", "brainstorming"),
        )

        assertEquals(GROUP_BUILTIN, out.first { it.display == "compact" }.group)
        assertEquals(GROUP_SKILL, out.first { it.display == "brainstorming" }.group)
    }

    @Test
    fun `技能列表取不到时全部退化成内置组，而不是丢候选`() {
        val out = commandCandidates(
            commands = listOf(cmd("brainstorming")),
            skills = emptyList(),
            sendable = setOf("brainstorming"),
        )

        assertEquals(1, out.size)
        assertEquals(GROUP_BUILTIN, out[0].group)
    }

    @Test
    fun `描述压成单行并截断 —— 真实的技能描述是整段的`() {
        val long = "第一行\n第二行\n" + "字".repeat(200)
        val flat = oneLine(long)

        assertTrue(flat.none { it == '\n' }, "换行会让一行变五行，整个列表失去形状")
        assertTrue(flat.endsWith("…"), "截断了要看得出来")
        assertTrue(flat.length <= 110)
    }

    @Test
    fun `短描述原样保留，不加省略号`() {
        assertEquals("压缩上下文", oneLine("压缩上下文"))
    }

    @Test
    fun `副标题拼上参数提示与别名`() {
        val d = describeCommand(cmd("usage", "花费", "<无>", listOf("cost", "stats")))
        assertTrue(d.contains("花费"))
        assertTrue(d.contains("<无>"))
        assertTrue(d.contains("cost"), "别名要看得见 —— 用户在终端里敲惯的是 cost")
    }

    @Test
    fun `参数提示与别名都缺时只给描述，不留多余分隔符`() {
        assertEquals("查问题", describeCommand(cmd("x", "查问题", "", emptyList())))
    }
}
