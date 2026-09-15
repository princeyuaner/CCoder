package com.ccoder.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ProjectJsonTest {

    @Test
    fun `文件不在就给空对象，不抛`(@TempDir dir: Path) {
        assertEquals(JsonObject(), ProjectJson.read(dir.resolve("没有这个文件.json")))
    }

    @Test
    fun `坏 JSON 也给空对象，不抛`(@TempDir dir: Path) {
        val path = dir.resolve(".mcp.json")
        Files.writeString(path, "{ 这不是 JSON")

        assertEquals(JsonObject(), ProjectJson.read(path))
    }

    @Test
    fun `JSON 但不是对象，同样给空对象`(@TempDir dir: Path) {
        val path = dir.resolve(".mcp.json")
        Files.writeString(path, "[1, 2, 3]")

        assertEquals(JsonObject(), ProjectJson.read(path))
    }

    @Test
    fun `格式化：两格缩进 + 恰好一个尾换行`() {
        val obj = JsonObject().apply { addProperty("a", 1) }

        assertEquals("{\n  \"a\": 1\n}\n", ProjectJson.format(obj))
    }

    @Test
    fun `不转义 HTML 敏感字符 —— 这是要提交给团队看的文件`() {
        val obj = JsonObject().apply { addProperty("command", "git commit -m 'a<b>&c'") }

        val text = ProjectJson.format(obj)

        // Gson 默认会转成 < 那一种。在转写区的推送里那是必需的，
        // 但配置文件里是纯噪音
        assertTrue(text.contains("a<b>&c"), "被转义了：$text")
    }

    @Test
    fun `写回再读回来是同一份（幂等）`(@TempDir dir: Path) {
        val path = dir.resolve(".claude").resolve("settings.json")
        val obj = JsonParser.parseString(
            """{"hooks":{"PreToolUse":[{"matcher":"Write","hooks":[{"type":"command","command":"echo hi"}]}]}}""",
        ).asJsonObject

        ProjectJson.write(path, obj)
        val once = Files.readString(path)
        ProjectJson.write(path, ProjectJson.read(path))
        val twice = Files.readString(path)

        assertEquals(once, twice, "再存一次盘结果就变了 —— diff 会一直抖")
        assertEquals(obj, ProjectJson.read(path))
    }

    @Test
    fun `父目录不存在会建出来`(@TempDir dir: Path) {
        val path = dir.resolve(".claude").resolve("settings.json")

        ProjectJson.write(path, JsonObject())

        assertTrue(Files.exists(path), ".claude/ 没被建出来")
    }

    @Test
    fun `不改动原对象`() {
        val original = JsonParser.parseString("""{"a":{"b":1}}""").asJsonObject
        val before = original.toString()

        ProjectJson.write(Files.createTempDirectory("ccoder").resolve("x.json"), original)

        assertEquals(before, original.toString())
    }
}
