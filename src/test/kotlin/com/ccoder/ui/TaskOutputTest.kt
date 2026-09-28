package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 任务输出末尾那几行的读法（[readOutputTail]）。
 *
 * 这一个类钉的是三条边界：**只读末尾**（大文件不许整读）、**残行丢掉**
 * （从中间开始读时第一条是半行）、**文件不在了要说得出来**（临时目录会被清）。
 * 用真文件系统（`@TempDir`），照仓库既有写法。
 */
class TaskOutputTest {

    @TempDir
    lateinit var tmp: Path

    private fun write(name: String, text: String): String {
        val file = tmp.resolve(name)
        Files.writeString(file, text)
        return file.toString()
    }

    private fun linesOf(read: OutputRead): List<String> = (read as OutputRead.Ok).tail.lines

    @Test
    fun `小文件整份读出来，且不算被截断`() {
        val read = readOutputTail(write("a.txt", "第一行\n第二行\n第三行\n"))

        assertEquals(listOf("第一行", "第二行", "第三行"), linesOf(read))
        assertTrue(!(read as OutputRead.Ok).tail.truncatedHead, "整份都在，不该说前面还有")
    }

    @Test
    fun `超过行数上限只留末尾几行，并且明说前面还有`() {
        // 界面靠 truncatedHead 决定说不说"前面还有内容没显示" —— 静默截断就是撒谎
        val text = (1..20).joinToString("\n") { "第 $it 行" } + "\n"
        val read = readOutputTail(write("big.txt", text), maxLines = 3)

        assertEquals(listOf("第 18 行", "第 19 行", "第 20 行"), linesOf(read))
        assertTrue((read as OutputRead.Ok).tail.truncatedHead)
    }

    @Test
    fun `从中间开始读时第一条残行要丢掉`() {
        // 反向读会在任意字节处停下，第一条几乎必然是半行 —— 半行日志比没有更误导。
        // 注意字节上限是**按块**生效的（见 readTailOf）：至少读一块（8KB），
        // 所以文件得比一块大，这一条才真的在考"从中间开始"
        val text = (1..5000).joinToString("\n") { "line-${"%04d".format(it)}" } + "\n"
        val read = readOutputTail(write("mid.txt", text), maxLines = 1000, maxBytes = 200)

        val lines = linesOf(read)
        assertTrue(lines.isNotEmpty(), "至少该留下一行完整的")
        assertTrue(
            lines.all { it.startsWith("line-") && it.length == 9 },
            "不该有半行混进来：$lines",
        )
        assertTrue((read as OutputRead.Ok).tail.truncatedHead)
    }

    @Test
    fun `文件不在了给 Missing，而不是空输出`() {
        // 这两件事在界面上必须分开说：一个说"文件没了"，一个看着像"它什么都没输出"
        assertEquals(OutputRead.Missing, readOutputTail(tmp.resolve("没有这个文件.txt").toString()))
    }

    @Test
    fun `空白路径当不在，不拿去解析`() {
        // `Path.of("")` 是合法的空路径 —— 不挡的话会一路走到"读不到"，
        // 而调用方那边已经因为"非 null"把「看输出」画出来了
        assertEquals(OutputRead.Missing, readOutputTail(""))
        assertEquals(OutputRead.Missing, readOutputTail("   "))
    }

    @Test
    fun `目录不是文件，同样算不在`() {
        assertEquals(OutputRead.Missing, readOutputTail(tmp.toString()))
    }

    @Test
    fun `CRLF 每行的回车都要摘掉`() {
        // Windows 上不摘的话，界面里每行尾部会多一个方块（同 ToolDiff 那条教训）
        val read = readOutputTail(write("crlf.txt", "甲\r\n乙\r\n"))

        assertEquals(listOf("甲", "乙"), linesOf(read))
    }

    @Test
    fun `多字节字符被块边界切断时，那一个字符替换掉，别的照读`() {
        // 反向读是按字节切的，切在多字节字符中间是常态 —— 不许因此整屏读不出来
        val text = "中文内容" + "x".repeat(200) + "\n尾巴\n"
        val read = readOutputTail(write("utf8.txt", text), maxLines = 10, maxBytes = 100)

        val lines = linesOf(read)
        assertTrue(lines.last() == "尾巴", "末尾那行必须完好：$lines")
        assertTrue(lines.isNotEmpty(), "其余内容照读，不是整份放弃")
    }

    @Test
    fun `一行就超过字节上限时，宁可什么都不给也不给半行`() {
        // 极端情形（一行几十万字符的 minified 输出）：末尾那条也是残行，于是丢掉。
        // 结果可能是空列表 —— 这是**说得出理由**的空：界面照 truncatedHead 说"前面还有"
        val read = readOutputTail(write("one-line.txt", "x".repeat(20_000)), maxLines = 10, maxBytes = 64)

        assertEquals(emptyList<String>(), linesOf(read))
        assertTrue((read as OutputRead.Ok).tail.truncatedHead)
    }
}
