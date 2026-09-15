package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font

class ThemeInjectorTest {

    private fun sample() = ThemeColors(
        bg = Color(0x1E1F22),
        text = Color(0xDCDCDC),
        textDim = Color(0x8C8C8C),
        border = Color(0x393B40),
        accent = Color(0x2F65CA),
        surface = Color(0x2B2D30),
        codeBg = Color(0x191A1C),
        errorBg = Color(0x4A1F1F),
        diffAddBg = Color(0x1E2F1F),
        diffDelBg = Color(0x33222A),
        diffAddFg = Color(0x5FAD65),
        diffDelFg = Color(0xDB5C5C),
        thinkingFg = Color(0xEC, 0xDD, 0xB4),
        fontUi = Font("JetBrains Sans", Font.PLAIN, 13),
        fontMono = Font("JetBrains Mono", Font.PLAIN, 13),
    )

    @Test
    fun `颜色以十六进制输出`() {
        val css = ThemeInjector.buildCss(sample())
        assertTrue(css.contains("--bg: #1e1f22;"), "实际：$css")
        assertTrue(css.contains("--text: #dcdcdc;"))
    }

    @Test
    fun `所有约定的变量都存在`() {
        // 这份清单就是前端 styles.css 依赖的契约：少一个，那边就退回落色
        val css = ThemeInjector.buildCss(sample())
        for (name in listOf(
            "--bg", "--text", "--text-dim", "--border", "--accent",
            "--surface", "--code-bg", "--error-bg",
            "--diff-add-bg", "--diff-del-bg", "--diff-add-fg", "--diff-del-fg",
            "--thinking-fg",
            "--font-ui", "--font-mono",
        )) {
            assertTrue(css.contains("$name:"), "缺少变量 $name")
        }
    }

    @Test
    fun `字体以 family size 输出且 family 带引号`() {
        // 断言用**实际解析出**的 family：Java 的 Font 在请求的字体不存在时
        // 会静默回退到默认逻辑字体，getFamily() 返回解析后的值而非请求的名字。
        // 假设字体名原样往返会让测试依赖机器上装了什么字体。
        val colors = sample()
        val css = ThemeInjector.buildCss(colors)
        assertTrue(
            css.contains("""--font-ui: "${colors.fontUi.family}", ${colors.fontUi.size}px;"""),
            "实际：$css",
        )
        assertTrue(
            css.contains("""--font-mono: "${colors.fontMono.family}", ${colors.fontMono.size}px;"""),
            "实际：$css",
        )
    }

    @Test
    fun `字号变化被反映到输出`() {
        val colors = sample().copy(fontMono = Font(Font.MONOSPACED, Font.PLAIN, 15))
        val css = ThemeInjector.buildCss(colors)
        assertTrue(css.contains("15px"), "实际：$css")
    }

    @Test
    fun `输出可被包进 style 标签`() {
        val css = ThemeInjector.buildCss(sample())
        assertTrue(css.contains(":root"), "应包含 :root 选择器")
        assertTrue(css.trimEnd().endsWith("}"), "应是完整的规则块")
    }

    @Test
    fun `十六进制补零`() {
        // Color(0x000102) 的十六进制必须是 000102 而不是 0102
        val colors = sample().copy(bg = Color(0x000102))
        val css = ThemeInjector.buildCss(colors)
        assertTrue(css.contains("--bg: #000102;"), "实际：$css")
    }

    @Test
    fun `相同输入产出相同输出`() {
        // 注入内容稳定，否则每次主题刷新都会重写 DOM
        assertEquals(ThemeInjector.buildCss(sample()), ThemeInjector.buildCss(sample()))
    }

    @Test
    fun `注入脚本把 CSS 包成单行字符串`() {
        val script = ThemeInjector.buildInjectScript(sample())
        assertEquals(1, script.lines().size, "注入脚本必须单行——多行会破坏 executeJavaScript")
        assertTrue(script.contains("window.ccoderSetTheme"), "实际：$script")
        assertTrue(script.contains(":root"), "CSS 内容应被嵌进字符串")
    }

    @Test
    fun `转义层处理单引号`() {
        // 直接测转义函数：真实系统上很难构造出字体名含单引号的情况，
        // 只能在这一层验证。字体名是最可能带引号的输入。
        assertEquals("Foo\\'s Font", ThemeInjector.escapeForJsString("Foo's Font"))
    }

    @Test
    fun `转义层处理反斜杠且不重复转义`() {
        // 反斜杠必须最先替换，否则会把它自己引入的转义再转一遍
        assertEquals("C:\\\\path", ThemeInjector.escapeForJsString("C:\\path"))
        // 输入里已经有转义序列时，反斜杠与引号都应各自被处理一次
        assertEquals("a\\\\b\\'c", ThemeInjector.escapeForJsString("a\\b'c"))
    }

    @Test
    fun `转义层把换行压成空格`() {
        assertEquals("a b", ThemeInjector.escapeForJsString("a\nb"))
        assertEquals("a b", ThemeInjector.escapeForJsString("a\r\nb"))
    }

    @Test
    fun `注入脚本始终是单行`() {
        val script = ThemeInjector.buildInjectScript(sample())
        assertEquals(1, script.lines().size, "多行会破坏 executeJavaScript")
        assertTrue(script.startsWith("window.ccoderSetTheme"))
        assertTrue(script.endsWith("');"))
    }
}
