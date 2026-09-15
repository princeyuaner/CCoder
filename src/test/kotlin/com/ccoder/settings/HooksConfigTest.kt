package com.ccoder.settings

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HooksConfigTest {

    private fun root(json: String) = JsonParser.parseString(json.trimIndent()).asJsonObject

    /** 一份"别人的文件"：一条我们认得的、一条认不得的、一个不归我们管的事件、外加顶层别的键。 */
    private fun foreignFile() = root(
        """
        {
          "hooks": {
            "PreToolUse": [
              { "matcher": "Write", "hooks": [ { "type": "command", "command": "echo 拦一下" } ] },
              { "matcher": "Bash", "hooks": [ { "type": "prompt", "prompt": "看看这条命令" } ] }
            ],
            "PostCompact": [
              { "hooks": [ { "type": "command", "command": "echo 压缩完了" } ] }
            ]
          },
          "permissions": { "allow": ["Bash(git *)"] }
        }
        """,
    )

    @Test
    fun `读一条 command hook`() {
        val rule = hooksOf(foreignFile()).rules.single()

        assertEquals("PreToolUse", rule.event)
        assertEquals("Write", rule.matcher)
        assertEquals("echo 拦一下", rule.command)
    }

    @Test
    fun `不归我们管的事件整条原样留着`() {
        val config = hooksOf(foreignFile())

        assertTrue(config.preservedEvents.containsKey("PostCompact"), "没被管理的事件被丢了")
        assertEquals(1, config.preservedEvents.getValue("PostCompact").size())
    }

    @Test
    fun `被管理事件里认不得的 matcher 原样留着`() {
        val config = hooksOf(foreignFile())

        // `type: "prompt"` 的那种：面板不做，但绝不能弄丢
        val kept = config.preservedMatchers.getValue("PreToolUse")
        assertEquals(1, kept.size())
        assertEquals("Bash", kept[0].asJsonObject.get("matcher").asString)
        assertEquals(
            "prompt",
            kept[0].asJsonObject.getAsJsonArray("hooks")[0].asJsonObject.get("type").asString,
        )
    }

    @Test
    fun `改一条不会弄丢别人的东西`() {
        val original = foreignFile()
        val config = hooksOf(original)

        val next = withHooks(
            original,
            config.rules.map { it.copy(command = "echo 改成这样") },
            config.preservedEvents,
            config.preservedMatchers,
        )

        val hooks = next.getAsJsonObject("hooks")
        assertTrue(next.has("permissions"), "顶层别的键被弄丢了")
        assertTrue(hooks.has("PostCompact"), "不归我们管的事件被弄丢了")
        val pre = hooks.getAsJsonArray("PreToolUse")
        assertEquals(2, pre.size(), "认不得的那条 matcher 被弄丢了")
        assertTrue(
            pre.any {
                it.asJsonObject.getAsJsonArray("hooks")[0].asJsonObject.get("type").asString == "prompt"
            },
            "prompt 那条不见了",
        )
    }

    @Test
    fun `删掉最后一条规则，那个事件不留空数组`() {
        val original = root(
            """{ "hooks": { "Stop": [ { "hooks": [ { "type": "command", "command": "echo hi" } ] } ] } }""",
        )
        val config = hooksOf(original)

        val next = withHooks(original, emptyList(), config.preservedEvents, config.preservedMatchers)

        assertFalse(next.getAsJsonObject("hooks").has("Stop"), "留了个空数组，是噪音")
    }

    @Test
    fun `事件顺序照原文件`() {
        val original = root(
            """
            {
              "hooks": {
                "SessionStart": [ { "hooks": [ { "type": "command", "command": "a" } ] } ],
                "Stop": [ { "hooks": [ { "type": "command", "command": "b" } ] } ]
              }
            }
            """,
        )
        val config = hooksOf(original)

        val next = withHooks(original, config.rules, config.preservedEvents, config.preservedMatchers)

        assertEquals(
            listOf("SessionStart", "Stop"),
            next.getAsJsonObject("hooks").keySet().toList(),
        )
    }

    @Test
    fun `新事件的规则排最后`() {
        val original = root("""{ "hooks": { "Stop": [ { "hooks": [ { "type": "command", "command": "b" } ] } ] } }""")

        val next = withHooks(
            original,
            listOf(
                HookRule(event = "Stop", command = "b"),
                HookRule(event = "SessionStart", command = "新加的"),
            ),
            emptyMap(),
            emptyMap(),
        )

        assertEquals(
            listOf("Stop", "SessionStart"),
            next.getAsJsonObject("hooks").keySet().toList(),
        )
    }

    @Test
    fun `写出来的形状：matcher 加上一条 command`() {
        val json = HookRule(event = "PreToolUse", matcher = "Write", command = "echo hi").toHookJson()

        assertEquals("Write", json.get("matcher").asString)
        val entry = json.getAsJsonArray("hooks")[0].asJsonObject
        assertEquals("command", entry.get("type").asString)
        assertEquals("echo hi", entry.get("command").asString)
    }

    @Test
    fun `matcher 空就不写这个键 —— 空串在 CLI 那里是匹配空名字`() {
        val json = HookRule(event = "Stop", command = "echo hi").toHookJson()

        assertFalse(json.has("matcher"))
    }

    @Test
    fun `timeout 不填就不写 —— 写 0 会被当成立刻超时`() {
        val json = HookRule(event = "Stop", command = "echo hi", timeout = 0).toHookJson()
        assertFalse(json.getAsJsonArray("hooks")[0].asJsonObject.has("timeout"))

        val withTimeout = HookRule(event = "Stop", command = "echo hi", timeout = 30).toHookJson()
        assertEquals(30, withTimeout.getAsJsonArray("hooks")[0].asJsonObject.get("timeout").asInt)
    }

    @Test
    fun `一个 matcher 挂多条 hook 的整条保留 —— 拆开再合回去会改变文件形状`() {
        val original = root(
            """
            {
              "hooks": {
                "PreToolUse": [
                  { "matcher": "Write", "hooks": [
                    { "type": "command", "command": "a" },
                    { "type": "command", "command": "b" }
                  ] }
                ]
              }
            }
            """,
        )
        val config = hooksOf(original)

        assertTrue(config.rules.isEmpty(), "挂了两条的被拆成规则了 —— 合回去就变了形")
        assertEquals(1, config.preservedMatchers.getValue("PreToolUse").size())
    }

    @Test
    fun `没有 hooks 这一块就是空的，不是错误`() {
        val config = hooksOf(root("""{ "permissions": {} }"""))

        assertTrue(config.rules.isEmpty())
        assertTrue(config.preservedEvents.isEmpty())
    }
}
