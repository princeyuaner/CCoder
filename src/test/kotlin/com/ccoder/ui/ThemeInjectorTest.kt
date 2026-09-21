package com.ccoder.ui

import com.ccoder.settings.FontChoice
import com.ccoder.settings.FontScale
import com.ccoder.settings.resolveUiFonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import kotlin.math.pow

class ThemeInjectorTest {

    private fun sample() = ThemeColors(
        bg = Color(0x1E1F22),
        text = Color(0xDCDCDC),
        textDim = Color(0x8C8C8C),
        border = Color(0x393B40),
        accent = Color(0x2F65CA),
        accentText = Color(0x7F, 0xB0, 0xFF),
        surface = Color(0x2B2D30),
        codeBg = Color(0x191A1C),
        refBg = Color(0x2C2540),
        errorBg = Color(0x4A1F1F),
        diffAddBg = Color(0x1E2F1F),
        diffDelBg = Color(0x33222A),
        diffAddFg = Color(0x5FAD65),
        diffDelFg = Color(0xDB5C5C),
        thinkingFg = Color(0xCF, 0xC8, 0xB8),
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
            "--bg", "--text", "--text-dim", "--border", "--accent", "--accent-text",
            "--surface", "--code-bg", "--ref-bg", "--error-bg",
            "--diff-add-bg", "--diff-del-bg", "--diff-add-fg", "--diff-del-fg",
            "--thinking-fg",
            "--font-ui", "--font-mono", "--fs-scale",
        )) {
            assertTrue(css.contains("$name:"), "缺少变量 $name")
        }
    }

    @Test
    fun `字体只输出家族名 —— 带字号那条会被浏览器丢掉（2026-09-21 修的）`() {
        // 从前这里是 `"家族名", 13px`（给 `font:` 简写准备的），而 styles.css 按
        // `font-family:` 消费 —— 整条声明非法、被浏览器丢掉，于是"尊重用户的编辑器字体"
        // 从 2026-09-11 写下那天起就没生效过（实测 body 落到浏览器兜底的 Noto Sans SC）。
        //
        // 断言用**实际解析出**的 family：Java 的 Font 在请求的字体不存在时会静默回退到
        // 默认逻辑字体，`getFamily()` 返回的是解析后的值 —— 假设字体名原样往返会让这条
        // 用例依赖机器上装了什么字体。
        val colors = sample()
        val css = ThemeInjector.buildCss(colors)
        assertTrue(
            css.contains("""--font-ui: "${colors.fontUi.family}", "Segoe UI", sans-serif;"""),
            "实际：$css",
        )
        assertTrue(
            css.contains("""--font-mono: "${colors.fontMono.family}", "Consolas", monospace;"""),
            "实际：$css",
        )
        // 平台字号（13）一个都不许再混进这份 CSS：页面的字号全在 styles.css 里，
        // 乘的是 --fs-scale
        assertFalse(css.contains("px"), "家族名里又混进字号了：$css")
    }

    @Test
    fun `字号档位进 --fs-scale`() {
        val fonts = resolveUiFonts(
            FontChoice.DEFAULT,
            FontScale.XLARGE,
            "JetBrains Sans",
            "JetBrains Mono",
        )
        val css = ThemeInjector.buildCss(sample(), fonts)
        assertTrue(css.contains("--fs-scale: 1.3;"), "实际：$css")
    }

    @Test
    fun `不传字体时 = 跟随 IDE + 标准字号（没人动过设置的样子）`() {
        val colors = sample()
        val css = ThemeInjector.buildCss(colors)

        assertTrue(css.contains("--fs-scale: 1.0;"), "实际：$css")
        assertTrue(
            css.contains("""--font-ui: "${colors.fontUi.family}", "Segoe UI", sans-serif;"""),
            "实际：$css",
        )
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
    fun `思考正文按主题混出亚麻色`() {
        // 深色：文本色 #dcdcdc 往浅锚色混；浅色：黑字往深锚色混
        assertEquals(Color(0xCF, 0xC8, 0xB8), thinkingFgFor(Color(0xDCDCDC), Color(0x1E1F22)))
        assertEquals(Color(0x5F, 0x5A, 0x4E), thinkingFgFor(Color(0x000000), Color(0xF7F8FA)))
    }

    @Test
    fun `思考正文在两套主题下都够读`() {
        // 守的是 2026-09-15 那个坑：锚色不分明暗时，浅色主题算出 #d0c198 压在白底
        // 上只有 1.7:1 —— 屏幕上等于看不见。单测里钉住"混出来的颜色必须够读"，
        // 比在注释里写一句管用。
        val cases = listOf(
            "深色" to (Color(0xDCDCDC) to Color(0x1E1F22)),
            "浅色" to (Color(0x000000) to Color(0xF7F8FA)),
        )
        for ((name, pair) in cases) {
            val (text, bg) = pair
            val ratio = contrast(thinkingFgFor(text, bg), bg)
            assertTrue(ratio >= 4.5, "$name 主题下思考正文只有 ${"%.2f".format(ratio)}:1，低于 WCAG 正文门槛")
        }
    }

    @Test
    fun `强调色的文字版在气泡底上够读`() {
        // 守的是 2026-09-17 用户截图那个坑：`--accent` 是选中块的**底色**，
        // 深色主题下拿来当文字色对气泡底只有 2.5:1 —— 屏幕上"看得见、读不了"。
        // 网页层要的是**文字版**的强调色，门槛与正文同一条（WCAG AA 4.5:1）。
        val cases = listOf(
            // 主题强调色（树选中底）到气泡底：Darcula 深色 / 一种浅色
            "深色" to (Color(0x2F65CA) to Color(0x2B2D30)),
            "浅色" to (Color(0xA8C7FA) to Color(0xFFFFFF)),
        )
        for ((name, pair) in cases) {
            val (accent, surface) = pair
            val ratio = contrast(accentTextFor(accent, surface), surface)
            assertTrue(
                ratio >= 4.5,
                "$name 主题下强调色文字只有 ${"%.2f".format(ratio)}:1，低于 WCAG 正文门槛",
            )
        }
    }

    @Test
    fun `强调色本身够读时不另挑颜色`() {
        // 主题自己的色排第一（跟 IDE 走）—— 够读就原样用，不无谓地换一支
        val accent = Color(0x7F, 0xB0, 0xFF)
        assertEquals(accent, accentTextFor(accent, Color(0x2B2D30)))
    }

    @Test
    fun `深色底退亮蓝、浅色底退深蓝`() {
        // 兜底不是 if 出来的，是量出来的：这两条钉住测量的结果
        assertEquals(CODE_BLUE_BRIGHT, accentTextFor(Color(0x2F65CA), Color(0x2B2D30)))
        assertEquals(CODE_BLUE_DEEP, accentTextFor(Color(0xA8C7FA), Color(0xFFFFFF)))
    }

    /** WCAG 相对亮度 —— 与 styles.css 里那几个对比度注释同一套算法。 */
    private fun relativeLuminance(c: Color): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(relativeLuminance(a), relativeLuminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
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
