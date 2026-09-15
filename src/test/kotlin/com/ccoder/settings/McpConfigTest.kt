package com.ccoder.settings

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpConfigTest {

    private fun root(json: String) = JsonParser.parseString(json.trimIndent()).asJsonObject

    /** 一份"别人的文件"：两个我们认得的 server、一个认不得的、外加一个顶层未知键。 */
    private fun foreignFile() = root(
        """
        {
          "mcpServers": {
            "codegraph": { "command": "npx", "args": ["-y", "codegraph"] },
            "remote": { "type": "sse", "url": "https://example.com/sse" },
            "weird": { "type": "sdk", "name": "插件不认识的形状" }
          },
          "someOtherToolKey": { "a": 1 }
        }
        """,
    )

    @Test
    fun `没有 type 就是 stdio —— 这是 CLI 自己的写法`() {
        val config = mcpServersOf(foreignFile())

        val codegraph = config.servers.first { it.name == "codegraph" }
        assertEquals(McpKind.STDIO, codegraph.mcpKind())
        assertEquals("npx", codegraph.command)
        assertEquals(listOf("-y", "codegraph"), codegraph.args)
    }

    @Test
    fun `sse 认 type 与 url`() {
        val remote = mcpServersOf(foreignFile()).servers.first { it.name == "remote" }

        assertEquals(McpKind.SSE, remote.mcpKind())
        assertEquals("https://example.com/sse", remote.url)
    }

    @Test
    fun `认不得的形状进 preserved，不进可编辑列表`() {
        val config = mcpServersOf(foreignFile())

        assertEquals(listOf("codegraph", "remote"), config.servers.map { it.name })
        assertTrue(config.preserved.has("weird"), "认不得的那条没被留下")
    }

    @Test
    fun `改一条不会弄丢别人的东西 —— 顶层未知键与认不得的 server 都得在`() {
        val original = foreignFile()
        val config = mcpServersOf(original)

        // 只动 codegraph 的 args
        val edited = config.servers.map {
            if (it.name == "codegraph") it.copy(args = mutableListOf("-y", "codegraph", "--stdio"))
            else it
        }
        val next = withMcpServers(original, edited, config.preserved)

        assertTrue(next.has("someOtherToolKey"), "顶层别的工具的键被弄丢了")
        val block = next.getAsJsonObject("mcpServers")
        assertTrue(block.has("weird"), "认不得的那条 server 被弄丢了")
        assertEquals(
            listOf("-y", "codegraph", "--stdio"),
            block.getAsJsonObject("codegraph").getAsJsonArray("args").map { it.asString },
        )
    }

    @Test
    fun `按原顺序重建 —— 改一条不该让它挪位置`() {
        val original = root(
            """
            { "mcpServers": { "a": {"command": "a"}, "b": {"command": "b"}, "c": {"command": "c"} } }
            """,
        )
        val config = mcpServersOf(original)

        val next = withMcpServers(original, config.servers.map { it.copy(command = it.command + "!") }, config.preserved)

        assertEquals(
            listOf("a", "b", "c"),
            next.getAsJsonObject("mcpServers").keySet().toList(),
            "改一条把顺序打乱了 —— 用户会看到一片与内容无关的 diff",
        )
    }

    @Test
    fun `删掉一条就真的不写出去`() {
        val original = root("""{ "mcpServers": { "a": {"command": "a"}, "b": {"command": "b"} } }""")
        val config = mcpServersOf(original)

        val next = withMcpServers(original, config.servers.filter { it.name == "a" }, config.preserved)

        val block = next.getAsJsonObject("mcpServers")
        assertFalse(block.has("b"), "删掉的又写回去了")
        assertTrue(block.has("a"))
    }

    @Test
    fun `新加的排在最后`() {
        val original = root("""{ "mcpServers": { "a": {"command": "a"} } }""")
        val config = mcpServersOf(original)

        val next = withMcpServers(
            original,
            config.servers + McpServer(name = "新的", command = "new-cmd"),
            config.preserved,
        )

        assertEquals(
            listOf("a", "新的"),
            next.getAsJsonObject("mcpServers").keySet().toList(),
        )
    }

    @Test
    fun `空的可选字段不写进文件`() {
        val json = McpServer(name = "x", command = "cmd", args = mutableListOf(" ", "")).toMcpJson()

        assertFalse(json.has("args"), "空 args 也写进去了 —— 别往用户文件里塞空数组")
        assertFalse(json.has("env"))
        assertFalse(json.has("type"), "stdio 的 type 是可省的，省掉更接近手写的文件")
        assertEquals("cmd", json.get("command").asString)
    }

    @Test
    fun `sse 写出来带 type，且不带 stdio 那几个字段`() {
        val json = McpServer(name = "x", kind = McpKind.SSE.name, url = "https://e.com/sse").toMcpJson()

        assertEquals("sse", json.get("type").asString)
        assertEquals("https://e.com/sse", json.get("url").asString)
        assertFalse(json.has("command"))
    }

    @Test
    fun `没有 mcpServers 这一块就是空的，不是错误`() {
        val config = mcpServersOf(root("""{ "别的": 1 }"""))

        assertTrue(config.servers.isEmpty())
        assertTrue(config.preserved.isEmpty())
    }
}
