package com.ccoder.settings

import com.ccoder.text.CcoderText
import java.awt.GraphicsEnvironment

/**
 * 转录区的字号：**四档**（2026-09-21）。
 *
 * 为什么不给滑块：任意百分比要在 24 处字号、几十处折行里都成立，而"哪一档好看"
 * 是看的、不是算的 —— 四档能逐个离屏出图看过，也让版面在小数下不会跑形
 * （搁置计划里用户已定"单一总缩放"，这里落实成四档）。
 *
 * 值进的是 CSS 变量 `--fs-scale`（见 `ThemeInjector`），乘在 styles.css 里那些
 * `calc(Npx * var(--fs-scale, 1))` 上 —— **只缩放字号，不缩放间距**（那是 B 切片里
 * 「密度」那件事，分开做）。
 */
enum class FontScale(val factor: Double, private val labelKey: String) {
    SMALL(0.9, "settings.font.scale.small"),
    NORMAL(1.0, "settings.font.scale.normal"),
    LARGE(1.15, "settings.font.scale.large"),
    XLARGE(1.3, "settings.font.scale.xlarge");

    /** 下拉里显示人话（同 `SendShortcut` / `EffortSetting` / `UiLanguage` 那条先例）。 */
    override fun toString(): String = CcoderText.text(labelKey)

    companion object {
        val DEFAULT = NORMAL

        /**
         * 存的是枚举**名**。认不出回默认。
         *
         * 兜底必须在**getter**这一侧（[UiPreferences.fontScale]），不能只在 `loadState`：
         * 手改过 XML 的人会写出一个不存在的名字，而 `combo.selectedItem = <不存在>` 得到
         * `null`，`save()` 里那个 `as FontScale` 当场 ClassCastException —— 设置页直接崩。
         */
        fun fromName(name: String?): FontScale =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 转录区的正文字族（2026-09-21）。**固定几档 + 跟随 IDE**，不做自由输入 ——
 * 与配色那条"只暴露选项不暴露色值"同一个口径：敲错一个字体名不会报错，
 * 只会静默回退到默认，用户看到的是"改了没生效"。
 *
 * ## 栈里为什么带好几层
 *
 * ① IDE 自带字体（JetBrains Sans / JetBrains Mono）**通常没装进系统**，
 * 而页面的字体解析是浏览器做的 —— 解析不到就得有系统可见的退路（`Segoe UI` /
 * `Consolas` / 通用族），否则整条栈白写；
 * ② 中文那几档同时写英文名与中文名（Windows 上两套名字都在用，浏览器认得哪个都行）；
 * ③ 每条栈都以**通用族**结尾（`sans-serif` / `serif` / `monospace`）—— 最后一道兜底。
 *
 * ## 代码块不跟着变
 *
 * 这一档只换正文（`--font-ui`）；`--font-mono` 始终跟 IDE 的编辑器字体 ——
 * 代码该是等宽的，那是语义不是偏好。选中"等宽"那两档时两者才自然一致。
 */
enum class FontChoice(
    /** 给 `--font-ui` 用的家族名栈。[FOLLOW_IDE] 是空串：那一档的栈运行时从平台字体拼。 */
    private val stack: String,
    /**
     * 判定"本机装没装"用的名字（可能不止一个：Java 与 Chromium 在 Windows 上的取名
     * 不一定一致，中文名与英文名都列上）。
     */
    private val aliases: List<String>,
    /** 词表键；null = 专名（不进词表，照 `UiLanguage` 的 endonym 那条规矩）。 */
    private val labelKey: String?,
) {
    FOLLOW_IDE("", emptyList(), "settings.language.followIde"),
    SYSTEM("sans-serif", emptyList(), "settings.font.systemDefault"),
    YAHEI("\"Microsoft YaHei\", \"微软雅黑\", sans-serif", listOf("Microsoft YaHei", "微软雅黑"), "settings.font.yahei"),
    SIMSUN("\"SimSun\", \"宋体\", serif", listOf("SimSun", "宋体"), "settings.font.simsun"),
    KAI("\"KaiTi\", \"楷体\", serif", listOf("KaiTi", "楷体"), "settings.font.kai"),
    SEGOE("\"Segoe UI\", sans-serif", listOf("Segoe UI"), null),
    GEORGIA("\"Georgia\", serif", listOf("Georgia"), null),
    CONSOLAS("\"Consolas\", monospace", listOf("Consolas"), null),
    JB_MONO(
        "\"JetBrains Mono\", \"Consolas\", monospace",
        listOf("JetBrains Mono", "JetBrains Mono NL"),
        null,
    );

    /** 下拉里显示什么：专名原样（`UiLanguage` 的 endonym 同一条规矩），其余走词表。 */
    override fun toString(): String = labelKey?.let { CcoderText.text(it) } ?: aliases.first()

    /**
     * 这一档给 `--font-ui` 用的栈。
     *
     * [FOLLOW_IDE] 用平台给的那个家族名（`UIUtil.getLabelFont()`），后面接系统可见的
     * 退路 —— IDE 的界面字体多半没装进系统，浏览器解析不到它。
     */
    fun uiStack(ideFamily: String): String =
        if (this == FOLLOW_IDE) "\"$ideFamily\", \"Segoe UI\", sans-serif" else stack

    companion object {
        val DEFAULT = FOLLOW_IDE

        /** 认不出回默认 —— 同 [FontScale.fromName]，理由见那里。 */
        fun fromName(name: String?): FontChoice =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * 本机能选的那几档：**[FOLLOW_IDE] / [SYSTEM] 永远在**，装了的专名也在，
         * 外加 [stored]（哪怕它已经取不到）—— 少了它，下拉会显示成别的一档，
         * 而用户上次选的字体就"凭空丢了"。
         */
        fun availableChoices(stored: FontChoice): List<FontChoice> {
            val installed = installedFamilies
            return entries.filter { it == stored || it.isAlwaysOffered() || it.isInstalled(installed) }
        }

        /**
         * 本机装了哪些字族（小写，便于比对）。
         *
         * 探针环境里取不到就回空集 —— 那时列表只剩"永远在"的那两档 + 上次选的那档，
         * 设置页照样能开（同 `UiLanguageSettings.getInstanceOrNull` 那条口径）。
         * 结果**缓存**：这一调在 Windows 上要扫一遍字体目录，而它只在建页时用。
         */
        private val installedFamilies: Set<String> by lazy {
            runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .availableFontFamilyNames
                    .map { it.lowercase() }
                    .toSet()
            }.getOrDefault(emptySet())
        }

        private fun FontChoice.isAlwaysOffered(): Boolean = this == FOLLOW_IDE || this == SYSTEM

        private fun FontChoice.isInstalled(installed: Set<String>): Boolean =
            aliases.any { it.lowercase() in installed }
    }
}

/** 注入给页面的三样字体值 —— 都是**已经算好的 CSS 值**（见 [resolveUiFonts]）。 */
data class UiFonts(
    /** `--font-ui`：正文/界面字族的栈。**只有家族名，不带字号**。 */
    val ui: String,
    /** `--font-mono`：代码块的等宽栈。 */
    val mono: String,
    /** `--fs-scale`：字号倍率（1.0 = styles.css 里那些设计值）。 */
    val scale: Double,
)

/**
 * 把两档偏好解析成要注入的值（纯函数，可单测）。
 *
 * [ideUiFamily] / [ideMonoFamily] 来自平台（`PlatformTheme.read()` 那两个 `Font`）。
 * 注意平台字号**不参与**：页面的字号全在 styles.css 里（乘 `--fs-scale`），
 * IDE 那个 12/13px 混进来只会让四档变得说不清。
 */
fun resolveUiFonts(
    choice: FontChoice,
    scale: FontScale,
    ideUiFamily: String,
    ideMonoFamily: String,
): UiFonts = UiFonts(
    ui = choice.uiStack(cssFamily(ideUiFamily)),
    // 代码块始终等宽、始终跟 IDE 的编辑器字体 —— 选正文档只换 --font-ui（见 FontChoice 的注释）
    mono = "\"${cssFamily(ideMonoFamily)}\", \"Consolas\", monospace",
    scale = scale.factor,
)

/**
 * 家族名里那些会把 `:root { … }` 这一块**提前关掉**的字符，一个都不留。
 *
 * `escapeForJsString` 管的是"嵌进 JS 字符串"那一层，管不到 CSS 这一层 ——
 * 而这条路现在握的是**用户选的**家族名与平台给的字体名（真实字体名几乎不会带这些，
 * 但代价太不对称：一个引号就能让整份主题静默失效，页面退回落色）。
 */
private fun cssFamily(raw: String): String = raw.filterNot { it in "\"};\\\n\r" }
