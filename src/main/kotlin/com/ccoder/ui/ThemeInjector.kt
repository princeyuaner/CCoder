package com.ccoder.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font

data class ThemeColors(
    val bg: Color,
    val text: Color,
    val textDim: Color,
    val border: Color,
    val accent: Color,
    val surface: Color,
    val codeBg: Color,
    val errorBg: Color,
    /** diff 的新增行底色（工具卡片里的 Edit 预览）。 */
    val diffAddBg: Color,
    /** diff 的删除行底色。 */
    val diffDelBg: Color,
    /** 标题行上 `+1` 的颜色。 */
    val diffAddFg: Color,
    /** 标题行上 `−1`、以及"失败"标记的颜色。 */
    val diffDelFg: Color,
    /** 思考正文的颜色：浅黄，但由文本色混出，随主题自动变（见 [PlatformTheme]）。 */
    val thinkingFg: Color,
    val fontUi: Font,
    val fontMono: Font,
)

/**
 * 把平台的颜色与字体转成 CSS 变量。
 *
 * JCEF 拿不到 IDE 的主题变量，所以由 Kotlin 侧读出后注入。这样明暗主题
 * 自动正确，而不是我们猜两套配色。
 *
 * 生成逻辑与"读平台 API"分成两个对象，是为了让生成逻辑可测 ——
 * 单测环境里拿不到真实主题，混在一起就什么都测不了。
 *
 * 不依赖 [com.intellij.ui.ColorUtil]：自己写颜色运算，让这一层完全自包含，
 * 不随平台 API 变化而失效。
 */
object ThemeInjector {

    fun buildCss(colors: ThemeColors): String = buildString {
        append(":root {")
        append(" --bg: ${colors.bg.hex()};")
        append(" --text: ${colors.text.hex()};")
        append(" --text-dim: ${colors.textDim.hex()};")
        append(" --border: ${colors.border.hex()};")
        append(" --accent: ${colors.accent.hex()};")
        append(" --surface: ${colors.surface.hex()};")
        append(" --code-bg: ${colors.codeBg.hex()};")
        append(" --error-bg: ${colors.errorBg.hex()};")
        append(" --diff-add-bg: ${colors.diffAddBg.hex()};")
        append(" --diff-del-bg: ${colors.diffDelBg.hex()};")
        append(" --diff-add-fg: ${colors.diffAddFg.hex()};")
        append(" --diff-del-fg: ${colors.diffDelFg.hex()};")
        append(" --thinking-fg: ${colors.thinkingFg.hex()};")
        append(" --font-ui: ${colors.fontUi.css()};")
        append(" --font-mono: ${colors.fontMono.css()};")
        append(" }")
    }

    /** 把 CSS 包成可直接 executeJavaScript 的赋值语句。必须单行。 */
    fun buildInjectScript(colors: ThemeColors): String =
        "window.ccoderSetTheme && window.ccoderSetTheme('${escapeForJsString(buildCss(colors))}');"

    /**
     * 转义为可安全嵌入单引号 JS 字符串的形式。
     *
     * 独立出来是为了可测：字体名等输入可能含引号，但真实系统上很难
     * 构造出这样的字体，只能直接测这一层。
     *
     * 反斜杠必须最先替换，否则会把它自己引入的转义再转一遍。
     */
    internal fun escapeForJsString(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        // CRLF 必须先于单独的 \n 与 \r 处理，否则一个 CRLF 会变成两个空格
        .replace("\r\n", " ")
        .replace("\n", " ")
        .replace("\r", " ")

    private fun Color.hex(): String = "#%02x%02x%02x".format(red, green, blue)

    private fun Font.css(): String = "\"$family\", ${size}px"
}

/**
 * 从平台 API 读取当前主题。
 *
 * 生产路径，不做单测 —— 单测环境里拿不到真实主题。
 *
 * **必须在 EDT 上调用**：`EditorColorsManager` 不是线程安全的。
 */
object PlatformTheme {

    fun read(): ThemeColors {
        val bg = UIUtil.getPanelBackground()
        val text = UIUtil.getLabelForeground()
        return ThemeColors(
            bg = bg,
            text = text,
            textDim = UIUtil.getInactiveTextColor(),
            border = JBColor.border(),
            accent = UIUtil.getTreeSelectionBackground(true),
            surface = surfaceFor(bg),
            codeBg = UIUtil.getTextFieldBackground(),
            // 错误背景：以文本色为基准向橙红偏移，得到低饱和变体。
            // 不用纯红——纯红在浅色主题下刺眼，在深色主题下又太暗。
            errorBg = mix(text, Color(0xD8, 0x43, 0x15), 0.85),
            // diff 的两套颜色同理由主题色混出来，不写死绿/红：
            // 硬编码的那种在浅色主题下要么看不见、要么刺眼。底色从**面板**
            // 出发（只要一点点偏色），文字从**文本色**出发（要能读）
            diffAddBg = mix(bg, Color(0x4C, 0xAF, 0x50), 0.16),
            diffDelBg = mix(bg, Color(0xE0, 0x54, 0x54), 0.16),
            diffAddFg = mix(text, Color(0x4C, 0xAF, 0x50), 0.60),
            diffDelFg = mix(text, Color(0xE0, 0x54, 0x54), 0.60),
            thinkingFg = mix(text, THINK_FG, 0.85),
            fontUi = UIUtil.getLabelFont(),
            fontMono = EditorColorsManager.getInstance().globalScheme
                .getFont(EditorFontType.PLAIN),
        )
    }

    /**
     * 气泡背景。优先用文本框背景色；若与面板背景过于接近，
     * 按明暗方向偏移，保证气泡有可见边界。
     */
    private fun surfaceFor(bg: Color): Color {
        val candidate = UIUtil.getTextFieldBackground()
        if (distance(candidate, bg) > SURFACE_MIN_DISTANCE) return candidate
        return if (luminance(bg) < 128) shift(candidate, 12) else shift(candidate, -10)
    }

    private fun distance(a: Color, b: Color): Int =
        kotlin.math.abs(a.red - b.red) +
            kotlin.math.abs(a.green - b.green) +
            kotlin.math.abs(a.blue - b.blue)

    private fun luminance(c: Color): Int = (c.red * 299 + c.green * 587 + c.blue * 114) / 1000

    private fun shift(c: Color, amount: Int): Color = Color(
        (c.red + amount).coerceIn(0, 255),
        (c.green + amount).coerceIn(0, 255),
        (c.blue + amount).coerceIn(0, 255),
    )

    /** 把 [fg] 以 [ratio] 的比例混入 [base]，得到低饱和变体。 */
    private fun mix(base: Color, fg: Color, ratio: Double): Color {
        fun blend(b: Int, f: Int) = (b + (f - b) * ratio).toInt().coerceIn(0, 255)
        return Color(blend(base.red, fg.red), blend(base.green, fg.green), blend(base.blue, fg.blue))
    }

    private const val SURFACE_MIN_DISTANCE = 24

    /**
     * 思考正文的黄（奶油黄）。
     *
     * **不写死在 CSS 里**：浅色主题下浅黄压白底等于看不见。所以与 diff 那两套
     * 颜色同一个做法 —— 从**文本色**混出来。
     *
     * 2026-09-15 调过一次：用户报"颜色太深"，换成更亮的奶油黄，混入比例也从
     * 0.70 提到 0.85。深色主题下现在是 `#ecddb4`（面板色上约 8:1）—— 比正文的
     * 灰白（约 5.4:1）还亮，所以思考会比正文更抢眼。觉得过头就把比例调回 0.75
     * 左右。浅色主题下是 `#d5c69d`。
     */
    private val THINK_FG = Color(0xF5, 0xE3, 0xB3)
}
