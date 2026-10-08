package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 一次性枚举那一行 JSON 的解析。
 *
 * 回执是 `sidecar/skills-once.js` 印的，形状对不上时**必须给一句人话**而不是抛 ——
 * 调用方在 EDT 上（见 [parseSkillsOnce] 的注释）。
 */
class SkillsOnceTest {

    @Test
    fun `正常回执`() {
        val reply = parseSkillsOnce(
            """{"ok":true,"commands":[{"name":"caveman","description":"省话","argumentHint":"","builtin":false}],""" +
                """"skills":{"totalSkills":1,"includedSkills":1,"tokens":28,"skillFrontmatter":[{"name":"caveman","source":"userSettings","tokens":28}]}}"""
        )

        assertTrue(reply is SkillsOnceReply.Ok)
        val ok = reply as SkillsOnceReply.Ok
        assertEquals(1, ok.commands.size)
        assertEquals("caveman", ok.commands.single().name)
        assertEquals("userSettings", ok.usage.single().source)
        assertEquals(28L, ok.usage.single().tokens)
    }

    @Test
    fun `ok 为 false 时把原因带出来`() {
        val reply = parseSkillsOnce("""{"ok":false,"error":"claude 40 秒内没有就绪"}""")

        assertEquals("claude 40 秒内没有就绪", (reply as SkillsOnceReply.Failed).reason)
    }

    @Test
    fun `不是 JSON 就给一句人话，不抛`() {
        val reply = parseSkillsOnce("Error: Cannot find module '@anthropic-ai/claude-agent-sdk'\n")

        assertTrue(reply is SkillsOnceReply.Failed)
        assertTrue((reply as SkillsOnceReply.Failed).reason.isNotBlank())
    }

    @Test
    fun `空字符串也不抛`() {
        assertTrue(parseSkillsOnce("") is SkillsOnceReply.Failed)
    }

    @Test
    fun `前面混了别的行时取最后那行带 ok 的`() {
        // node 的告警走 stderr，但 console.log 混进 stdout 的先例在探针里出现过
        val reply = parseSkillsOnce(
            "{\"not\":\"ours\"}\n" +
                """{"ok":true,"commands":[],"skills":null}"""
        )

        assertTrue(reply is SkillsOnceReply.Ok)
    }

    @Test
    fun `缺字段的条目跳过，不整条崩`() {
        val reply = parseSkillsOnce(
            """{"ok":true,"commands":[{"description":"没有名字"},{"name":"好的","description":"x"}],"skills":null}"""
        )

        val ok = reply as SkillsOnceReply.Ok
        assertEquals(listOf("好的"), ok.commands.map { it.name })
        assertTrue(ok.usage.isEmpty(), "skills 为 null 时账单是空的，不是崩")
    }
}
