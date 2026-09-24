package com.ccoder.ui

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 改动预览的规则，以及它与网页侧那份实现的一致性。
 *
 * 最要紧的一条是 [`两端共读的用例表`]：规则说了两遍（`ToolDiff.kt` 与 `web/src/tools.ts`），
 * 钉住它们的不是注释而是 `shared/tool-diff.json`。同一次改动在"批准前"与"批准后"
 * 长得不一样，用户会以为批错了 —— 所以这里比的是那份表，不是我以为的规则。
 */
class ToolDiffTest {

    private fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun fixture(): List<JsonObject> {
        val path = Path.of("shared", "tool-diff.json")
        // 路径里必须有 shared：这条与 web/src/codec.test.ts 那条同样的用意 ——
        // 有人把它挪进某个子目录，"共享"就名存实亡了
        assertTrue(path.toString().replace('\\', '/').contains("shared"), "夹具路径：$path")
        assertTrue(Files.exists(path), "找不到两端共读的用例表：${path.toAbsolutePath()}")
        return JsonParser.parseString(Files.readString(path)).asJsonArray.map { it.asJsonObject }
    }

    /** `expect` → 可比较的形状。`null` 与"空列表"在表里都写成 `null`（见 toolDiff 的注释）。 */
    private fun expectedOf(element: JsonElement?): List<DiffLine>? =
        if (element == null || element.isJsonNull) {
            null
        } else {
            element.asJsonArray.map {
                val line = it.asJsonObject
                DiffLine(
                    kind = if (line.get("kind").asString == "add") DiffKind.Add else DiffKind.Del,
                    text = line.get("text").asString,
                )
            }
        }

    // ---- 与网页侧那份实现的契约 ----

    @Test
    fun `两端共读的用例表`() {
        val cases = fixture()
        assertTrue(cases.size >= 15, "用例太少，这条用例就失去意义了：${cases.size}")

        for (case in cases) {
            val name = case.get("name").asString
            val input = case.get("input").asJsonObject
            assertEquals(
                expectedOf(case.get("expect")),
                toolDiff(name, input),
                "这条用例与网页侧给的答案不一致（$name / $input）—— 两边说的是同一件事，改哪边都得改 fixture",
            )
        }
    }

    /**
     * 两张表（`toolDiff` 的 `when`、`DIFF_CONSUMED`）放在同一个文件里，是为了让"忘了
     * 同步"这件事看得见；这条用例把它变成红色。走的是 fixture 里的输入，所以顺带钉住
     * "表里每个工具都真的算得出 diff"。
     */
    @Test
    fun `认得的工具，字段表里也得有 —— 两张表不走散`() {
        val cases = fixture()
        for (name in DIFF_TOOLS) {
            assertTrue(diffConsumedFields(name).isNotEmpty(), "$name 认得 diff，却没登记它吃掉了哪些字段")
            assertTrue(
                cases.any { it.get("name").asString == name && expectedOf(it.get("expect")) != null },
                "fixture 里没有 $name 算得出 diff 的用例，这条查不到它",
            )
        }
        assertTrue(diffConsumedFields("Bash").isEmpty(), "认不出的工具谈不上'字段被吃掉了'")
    }

    // ---- 那几条不该靠 fixture 才成立的规矩 ----

    @Test
    fun `NotebookEdit 故意不认 —— 它的新源码可能是删不是增`() {
        // new_source 是插入还是删除取决于另一个字段（edit_mode），照搬"全当新增"
        // 会把删除画成绿色的新增。那是"说不准就不说"，不是漏做
        assertNull(toolDiff("NotebookEdit", obj("""{"notebook_path":"n.ipynb","new_source":"x = 1"}""")))
    }

    @Test
    fun `带 replace_all 的 Edit 照样算 diff`() {
        val lines = toolDiff("Edit", obj("""{"old_string":"a","new_string":"b","replace_all":true}"""))
        assertEquals(listOf(DiffKind.Del to "a", DiffKind.Add to "b"), lines?.map { it.kind to it.text })
    }

    // ---- HTML ----

    private fun html(json: String, name: String = "Edit"): String =
        diffHtml(
            toolDiff(name, obj(json)) ?: error("这份入参算不出 diff，用例写错了：$json"),
            addBg = "#112211", delBg = "#221111",
            addFg = "#33ff33", delFg = "#ff3333",
            signFg = "#888888",
        )

    @Test
    fun `缩进靠 pre 保住 —— 空格不被压掉，行尾的 CR 去掉`() {
        val out = html("""{"old_string":"    x = 1\r\ny","new_string":"        x = 2"}""")

        // 四个空格原样在（HTML 会把连续空格压成一个，pre 是唯一能拦住它的东西）
        assertTrue(
            out.contains("<pre><font color=\"#ff3333\">    x = 1</font></pre>"),
            "缩进丢了或 CR 没去掉：$out",
        )
        // 第二行是真换行切出来的，不是字面 \n
        assertTrue(out.contains(">y</font></pre>"), out)
    }

    @Test
    fun `删与加各自的底色与标记`() {
        val out = html("""{"old_string":"old","new_string":"new"}""")

        assertTrue(out.contains("""<td width="14" bgcolor="#221111"><pre><font color="#888888">−"""), "删除行：$out")
        assertTrue(out.contains("""<td width="14" bgcolor="#112211"><pre><font color="#888888">+"""), "新增行：$out")
        assertTrue(out.contains("""<td bgcolor="#221111"><pre><font color="#ff3333">old"""), out)
        assertTrue(out.contains("""<td bgcolor="#112211"><pre><font color="#33ff33">new"""), out)
    }

    /** 底色带要铺满整宽 —— 表格默认只占内容那么长，右边会参差不齐（离屏渲染看出来的）。 */
    @Test
    fun `表格占满整宽 —— 底色带不该只有最长那行那么长`() {
        val out = html("""{"old_string":"a","new_string":"a much longer replacement line"}""")

        assertTrue(out.contains("width=\"100%\""), "表格没拉满，色带会参差不齐：$out")
    }

    @Test
    fun `尖括号一律转义 —— 代码里的泛型不是标签`() {
        val out = html("""{"old_string":"List<Int>","new_string":"Map<String, Int>"}""")

        assertTrue(out.contains("List&lt;Int&gt;"), "没转义，JEditorPane 会把泛型当标签吃掉：$out")
        assertFalse(out.contains("List<Int>"), out)
        assertTrue(out.contains("Map&lt;String, Int&gt;"), out)
    }

    @Test
    fun `空行给一个空格 —— 否则那一格会塌掉，空行就'消失'了`() {
        // 空行通常是"这里少了一行/多了一行"的证据，塌掉比难看严重
        val out = html("""{"old_string":"a\n\nb","new_string":"c"}""")
        assertTrue(out.contains("""><pre><font color="#ff3333"> </font></pre>"""), "空行没占住位置：$out")
    }

    @Test
    fun `Write 整份都是新增`() {
        val out = html("""{"content":"a\nb"}""", name = "Write")
        assertFalse(out.contains("#ff3333"), "Write 不该有删除行：$out")
        assertEquals(2, Regex("bgcolor=\"#112211\"").findAll(out).count() / 2, "两行，每行两个格子：$out")
    }
}
