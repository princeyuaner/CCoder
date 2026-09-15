package com.ccoder.ui

import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 右键「添加到 CCoder 聊天框」。
 *
 * 分成两截测：**选区算哪几行、片段长什么样**（[selectionLineRange]、
 * [formatSnippet]）是纯逻辑，钉死在这里；取编辑器、找工具窗口那一层
 * 是胶水，靠手点验证（见 AddSelectionToChatAction）。
 *
 * 行号是**给 Claude 读的**：路径与行号写错，它会去改别的文件 ——
 * 所以这两处按"宁可多测"处理。
 */
class AddSelectionToChatTest {

    // 一份带换行的假文档：行 1 = abc，行 2 = def，行 3 = ghi
    private val text = "abc\ndef\nghi\n"

    // ---- 选区 → 行号 ----

    @Test
    fun `行号从 1 开始，按选区的起止落点算`() {
        // 选中 "abc\ndef"（不含末尾换行）
        assertEquals(1..2, selectionLineRange(text, 0, 7))
    }

    @Test
    fun `选区包含末尾换行时，不把下一行也算进来`() {
        // 整行整行地选时，选区通常带着行尾的 \n：
        // 选中 "abc\ndef\n"（落点在第 3 行行首），实际覆盖的仍然是第 1、2 行。
        // 多算一行的话，Claude 看到的行号会整体偏大
        assertEquals(1..2, selectionLineRange(text, 0, 8))
    }

    @Test
    fun `同一行的选区首尾是同一个行号`() {
        assertEquals(2..2, selectionLineRange(text, 4, 7))
    }

    @Test
    fun `selection 从行首开始时行号不偏移`() {
        assertEquals(3..3, selectionLineRange(text, 8, 11))
    }

    // ---- 取整行原文（符号引用走这条）----

    @Test
    fun `取整行：首行的缩进留在里面，末行的换行不留`() {
        // 一个方法在 PSI 里的范围从 def 那个词开始 —— 行首那四个空格不在范围里。
        // 直接发 PSI 的 text，模型收到的是"顶层 def"，而原文里它缩在类里
        val src = "class A:\n    def f(self):\n        return 1\n\nx = 1\n"
        assertEquals("    def f(self):\n        return 1", linesText(src, 2..3))
    }

    @Test
    fun `取整行：单行与末行没有换行时都要取得对`() {
        assertEquals("x = 1", linesText("x = 1\n", 1..1))
        assertEquals("c", linesText("a\nb\nc", 3..3))
    }

    @Test
    fun `取整行：行号越界给空串，不抛`() {
        assertEquals("", linesText("a\n", 9..9), "它跑在用户正在打字的路径上")
    }

    // ---- 片段格式 ----

    @Test
    fun `片段是 路径冒号行号 加围栏代码块`() {
        val snippet = formatSnippet("src/A.kt", 1..2, "Kotlin", "abc\ndef\n")

        assertEquals("src/A.kt:1-2\n\n```kotlin\nabc\ndef\n```", snippet)
    }

    @Test
    fun `同行选区只写一个行号`() {
        val snippet = formatSnippet("src/A.kt", 7..7, "Kotlin", "val x = 1")

        assertEquals("src/A.kt:7\n\n```kotlin\nval x = 1\n```", snippet)
    }

    @Test
    fun `围栏语言标签取文件类型名并小写`() {
        val snippet = formatSnippet("src/A.kt", 1..1, "Kotlin", "x")

        assertEquals("src/A.kt:1\n\n```kotlin\nx\n```", snippet)
    }

    @Test
    fun `纯文本之类的类型不写语言标签 —— 写了反而是假的`() {
        // PLAIN_TEXT 小写后是 "plain_text"，当成语言标签写进围栏是错的
        val snippet = formatSnippet("notes.txt", 1..1, "PLAIN_TEXT", "hi")

        assertEquals("notes.txt:1\n\n```\nhi\n```", snippet)
    }

    @Test
    fun `取不到文件类型时也不写语言标签`() {
        val snippet = formatSnippet("notes.txt", 1..1, null, "hi")

        assertEquals("notes.txt:1\n\n```\nhi\n```", snippet)
    }

    @Test
    fun `选区末尾的换行不会在围栏里留出空行`() {
        // 整行选中时 selectedText 常以 \n 收尾，直接拼会在 ``` 前多一个空行
        val snippet = formatSnippet("src/A.kt", 1..1, "Kotlin", "val x = 1\n")

        assertEquals("src/A.kt:1\n\n```kotlin\nval x = 1\n```", snippet)
    }

    // ---- 追加进输入框 ----

    private fun area(text: String = "") = JBTextArea().apply { setText(text) }

    @Test
    fun `空输入框直接放入片段`() {
        val area = area()

        appendSnippet(area, "片段")

        assertEquals("片段", area.text)
    }

    @Test
    fun `已有内容时接在后面，只隔一个空格`() {
        // 追加不覆盖：这个动作是用来攒上下文的。
        // **不另起一行** —— 2026-09-15 用户要求：「跟文字一样跟随在后面」
        val area = area("先看这段")

        appendSnippet(area, "片段")

        assertEquals("先看这段 片段", area.text)
    }

    @Test
    fun `记号后面加文件是同一行 —— 用户报的就是这个`() {
        val token = refToken("a/B.kt", 24..27)
        val area = area(token)

        appendSnippet(area, fileMention("sidecar/session.js"))

        assertEquals("$token @sidecar/session.js ", area.text)
        assertFalse(area.text.contains("\n"), "不该多出换行：${area.text}")
    }

    @Test
    fun `用户自己敲的回车留着`() {
        // 他在「看下这个」后面按了回车，就该从新的一行接着写，
        // 而不是把他敲的换行吃掉、挤回上一行
        val area = area("看下这个\n")

        appendSnippet(area, "@a.kt ")

        assertEquals("看下这个\n@a.kt ", area.text)
    }

    @Test
    fun `连着加两个文件不会攒出两个空格`() {
        // 文件引用自己带一个尾随空格（CLI 认的就是这个形状），
        // 再加一个分隔空格就成了两个 —— 看着像手滑
        val area = area()

        appendSnippet(area, fileMention("a.kt"))
        appendSnippet(area, fileMention("b.kt"))

        assertEquals("@a.kt @b.kt ", area.text)
    }

    @Test
    fun `追加后光标在末尾 —— 接着就能打字`() {
        val area = area("先看这段")

        appendSnippet(area, "片段")

        assertEquals(area.text.length, area.caretPosition)
    }
}
