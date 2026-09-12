package com.ccoder.ui

import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `已有内容时追加在下方，中间隔一个空行`() {
        // 追加不覆盖：这个动作是用来攒上下文的
        val area = area("先看这段")

        appendSnippet(area, "片段")

        assertEquals("先看这段\n\n片段", area.text)
    }

    @Test
    fun `已有内容以换行结尾时不产生多余空行`() {
        val area = area("先看这段\n\n")

        appendSnippet(area, "片段")

        assertEquals("先看这段\n\n片段", area.text)
    }

    @Test
    fun `追加后光标在末尾 —— 接着就能打字`() {
        val area = area("先看这段")

        appendSnippet(area, "片段")

        assertEquals(area.text.length, area.caretPosition)
    }
}
