package com.ccoder.settings

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 技能四档的**读写规矩**（纯函数层）。
 *
 * 这些字符串是 CLI 的协议，不是我们的措辞 —— 用例钉的是"一个字都不能改"，
 * 而不是"好不好看"。
 */
class SkillsConfigTest {

    @Test
    fun `四档的线上取值一个字都不能改`() {
        assertEquals("on", SkillState.ON.wire)
        assertEquals("name-only", SkillState.NAME_ONLY.wire)
        assertEquals("user-invocable-only", SkillState.USER_ONLY.wire)
        assertEquals("off", SkillState.OFF.wire)
    }

    @Test
    fun `认不出的档位给 null，不当成开`() {
        // 文件是手可改的。转成 ON 会让界面撒谎说"它是开着的"（见 SkillState.ofWire 的注释）
        assertNull(SkillState.ofWire("half-on"))
        assertNull(SkillState.ofWire(null))
        assertEquals(SkillState.OFF, SkillState.ofWire("off"))
    }

    @Test
    fun `只收字符串值，别的形状跳过`() {
        val root = JsonParser.parseString(
            """{"skillOverrides":{"a":"off","b":3,"c":null,"":"off"}}"""
        ).asJsonObject

        assertEquals(mapOf("a" to "off"), skillOverridesOf(root))
    }

    @Test
    fun `没有 skillOverrides 那块就是空表`() {
        assertTrue(skillOverridesOf(JsonParser.parseString("{}").asJsonObject).isEmpty())
    }

    @Test
    fun `三层合并：本地压项目、项目压用户`() {
        val merged = mergedSkillOverrides(
            local = mapOf("a" to "off"),
            project = mapOf("a" to "name-only", "b" to "user-invocable-only"),
            user = mapOf("b" to "on", "c" to "off"),
        )

        assertEquals("off", merged["a"], "本地那一层说了算")
        assertEquals("user-invocable-only", merged["b"], "没有本地覆盖时项目说了算")
        assertEquals("off", merged["c"], "只有用户级覆盖时用它")
    }

    @Test
    fun `没被覆盖过的键不进合并表`() {
        // 进了的话，页面上每条技能都会长得像"被改过"——而它们其实一个字都没写
        val merged = mergedSkillOverrides(emptyMap(), emptyMap(), user = mapOf("x" to "off"))

        assertEquals(setOf("x"), merged.keys)
    }

    @Test
    fun `写手那一行 JSON 用的是设置键和线上取值`() {
        val json = JsonParser.parseString(skillEditInput("debug-issue", SkillState.OFF)).asJsonObject

        assertEquals("debug-issue", json.get("name").asString)
        assertEquals("off", json.get("state").asString)
    }

    @Test
    fun `回执只认 ok 为 true —— 失败那条是纯文本`() {
        assertTrue(skillEditSucceeded("""{"ok":true,"written":{"name":"a","state":"off"},"changed":true}"""))
        assertFalse(skillEditSucceeded("Not a skill state edit (name: Invalid input)."))
        assertFalse(skillEditSucceeded("""{"ok":false,"error":"nope"}"""))
        assertFalse(skillEditSucceeded(""))
    }

    @Test
    fun `多行输出里从后往前找那一行 JSON`() {
        // node 的告警、写手的提示都可能混在前面几行 —— 只认整段会漏掉真回执
        assertTrue(
            skillEditSucceeded(
                "{ not json\n" +
                    """{"ok":true,"changed":false}"""
            )
        )
    }

    @Test
    fun `SKILL_md 的 name 取自 frontmatter`() {
        assertEquals("Debug Issue", skillMdNameOf("---\nname: Debug Issue\ndescription: x\n---\n正文"))
    }

    @Test
    fun `没有 frontmatter 就是 null，不是空串`() {
        // 这台机器上 7 个用户技能有 3 个没有 frontmatter（caveman 那类），
        // 空串会让调用方以为"名字是空的"、把一个有名字的技能显示成无名
        assertNull(skillMdNameOf("<!-- Upstream: https://… -->\n# Caveman\n"))
        assertNull(skillMdNameOf("---\ndescription: 只有描述\n---\n"))
    }

    @Test
    fun `frontmatter 没闭合时不往下吞`() {
        // 只有开头那个 ---，后面全是正文：不能把正文里的 "name:" 当成字段
        assertNull(skillMdNameOf("---\n正文\nname: 这行在正文里\n"))
    }
}
