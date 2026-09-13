package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 两份来源拼成候选（设计稿 §4.1）。
 *
 * A = supportedCommands()（显示信息）、B = init 的可发送名。
 * 设计稿原定的 C（`reloadSkills()`）实测这台 CLI 不支持，分组改走命名空间。
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

    // ---- 插件命名空间（2026-09-13 探针实测）----

    @Test
    fun `可发送名带插件前缀时照样配上`() {
        // 下面三对全是探针从真实数据里抓的：不认这一层的话 45 条只配上 27 条，
        // superpowers 整个技能库一条都不显示
        assertTrue(sameCommand("brainstorming", "superpowers:brainstorming"))
        assertTrue(sameCommand("frontend-design", "frontend-design:frontend-design"))
        assertTrue(sameCommand("skill-creator", "skill-creator:skill-creator"))
    }

    @Test
    fun `不带前缀的老写法仍然配上`() {
        assertTrue(sameCommand("compact", "compact"))
        assertTrue(sameCommand("Debug Issue", "debug-issue"))
        assertTrue(sameCommand("code-review:code-review", "code-review:code-review"))
    }

    @Test
    fun `不是同一条命令的不要误配`() {
        assertFalse(sameCommand("compact", "context"))
        assertFalse(sameCommand("clear", "code-review:clear-ish"), "只有整段后缀才算")
        assertFalse(sameCommand("brainstorming", "superpowers:writing-plans"))
    }

    @Test
    fun `命令候选整条走通：显示名不带前缀，插入名带`() {
        val out = commandCandidates(
            commands = listOf(cmd("brainstorming", "想清楚")),
            sendable = setOf("superpowers:brainstorming"),
        )

        assertEquals(1, out.size)
        assertEquals("brainstorming", out[0].display, "列表里显示的是人话名")
        assertEquals("superpowers:brainstorming", out[0].insert, "发出去要带前缀，否则 CLI 认不得")
    }

    // ---- 分组 ----

    @Test
    fun `带插件命名空间的进插件组，其余进其它组`() {
        val out = commandCandidates(
            commands = listOf(cmd("compact"), cmd("brainstorming"), cmd("caveman")),
            sendable = setOf("compact", "superpowers:brainstorming", "caveman"),
        )

        assertEquals(GROUP_OTHER, out.first { it.display == "compact" }.group)
        assertEquals(GROUP_PLUGIN, out.first { it.display == "brainstorming" }.group)
        assertEquals(
            GROUP_OTHER,
            out.first { it.display == "caveman" }.group,
            "用户技能目录里的是裸名，协议分不出来 —— 所以标签只能叫「其它」，",
        )
    }

    // ---- 副标题 ----

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
