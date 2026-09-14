package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 点文件名 → 编辑器打开：解析与路径判定这半段。
 *
 * 测不了的那半段是 [openInEditor]（碰 VFS / 编辑器，项目里没有
 * BasePlatformTestCase），它的正确性靠"刷新后查找 + 回 EDT 导航"那几行
 * 注释与手工冒烟守着。
 */
class OpenFileTargetTest {

    private fun parse(json: String) = parseOpenFileTarget(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `读出 path 与 line`() {
        val t = parse("""{"op":"openFile","path":"C:\\p\\A.kt","line":120}""")
        assertEquals("C:\\p\\A.kt", t?.path)
        assertEquals(120, t?.line)
    }

    @Test
    fun `没有 line 时按没有行号处理`() {
        assertNull(parse("""{"op":"openFile","path":"/p/A.kt"}""")?.line)
    }

    @Test
    fun `line 不是正经行号时当没有行号，但请求仍然有效`() {
        // 路径是对的、文件就能打开 —— 为了一行数字丢掉整个请求是亏的
        for (bad in listOf("0", "-3", "\"120\"", "null")) {
            val t = parse("""{"op":"openFile","path":"/p/A.kt","line":$bad}""")
            assertEquals("/p/A.kt", t?.path)
            assertNull(t?.line, "line=$bad 不该被当成行号")
        }
    }

    @Test
    fun `缺 path、path 空白、path 不是字符串都给 null，不抛`() {
        assertNull(parse("""{"op":"openFile"}"""))
        assertNull(parse("""{"op":"openFile","path":""}"""))
        assertNull(parse("""{"op":"openFile","path":"   "}"""))
        assertNull(parse("""{"op":"openFile","path":42}"""))
    }

    @Test
    fun `行号基准只在这里换算一次：1 基 → 0 基`() {
        assertEquals(0, lineIndex(1))
        assertEquals(119, lineIndex(120))
        assertEquals(0, lineIndex(null))
        assertEquals(0, lineIndex(0))
        assertEquals(0, lineIndex(-5))
    }

    // ---- 路径解析 ----

    @Test
    fun `Windows 反斜杠绝对路径归一成正斜杠`() {
        assertEquals(
            "C:/p/src/A.kt",
            resolveAbsolutePath("C:\\p\\src\\A.kt", basePath = null, isWindows = true),
        )
    }

    @Test
    fun `Windows 正斜杠绝对路径原样`() {
        assertEquals(
            "C:/p/src/A.kt",
            resolveAbsolutePath("C:/p/src/A.kt", basePath = null, isWindows = true),
        )
    }

    @Test
    fun `Git Bash 风格的 c 盘路径翻成盘符路径`() {
        // Claude 在 Git Bash 下报出来的路径就是 /c/... 这个形状
        assertEquals(
            "C:/Users/CY/a.kt",
            resolveAbsolutePath("/c/Users/CY/a.kt", basePath = null, isWindows = true),
        )
    }

    @Test
    fun `UNC 路径不被折成相对路径`() {
        assertEquals(
            "//srv/share/a.kt",
            resolveAbsolutePath("\\\\srv\\share\\a.kt", basePath = "C:/p/proj", isWindows = true),
        )
    }

    @Test
    fun `Windows 上认不出的前导斜杠给 null —— 不猜盘符`() {
        // 当成"项目内的相对路径"会把用户送到一个毫不相干的文件里
        assertNull(resolveAbsolutePath("/etc/hosts", basePath = "C:/p/proj", isWindows = true))
    }

    @Test
    fun `相对路径拼到 basePath 上（basePath 是反斜杠的）`() {
        assertEquals(
            "C:/p/proj/web/src/App.tsx",
            resolveAbsolutePath("web/src/App.tsx", basePath = "C:\\p\\proj", isWindows = true),
        )
    }

    @Test
    fun `点段与双点段在拼完之后一起折掉`() {
        assertEquals(
            "C:/p/proj/web/src/A.tsx",
            resolveAbsolutePath("./web/other/../src/A.tsx", basePath = "C:/p/proj", isWindows = true),
        )
        // 翻出 base 之外也照样折
        assertEquals(
            "C:/p/other/A.kt",
            resolveAbsolutePath("../other/A.kt", basePath = "C:/p/proj", isWindows = true),
        )
    }

    @Test
    fun `相对路径没有 basePath 可拼时给 null`() {
        assertNull(resolveAbsolutePath("web/src/App.tsx", basePath = null, isWindows = true))
        // 但绝对路径不靠 basePath
        assertEquals(
            "C:/p/A.kt",
            resolveAbsolutePath("C:/p/A.kt", basePath = null, isWindows = true),
        )
    }

    @Test
    fun `POSIX 绝对路径与相对路径`() {
        assertEquals(
            "/home/u/a.kt",
            resolveAbsolutePath("/home/u/a.kt", basePath = null, isWindows = false),
        )
        assertEquals(
            "/home/u/proj/src/A.kt",
            resolveAbsolutePath("src/A.kt", basePath = "/home/u/proj", isWindows = false),
        )
    }

    @Test
    fun `空路径给 null，不抛`() {
        assertNull(resolveAbsolutePath("", basePath = "C:/p/proj", isWindows = true))
        assertNull(resolveAbsolutePath("   ", basePath = "C:/p/proj", isWindows = true))
    }
}
