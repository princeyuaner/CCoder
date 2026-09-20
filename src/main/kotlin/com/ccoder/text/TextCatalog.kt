package com.ccoder.text

import java.util.Locale
import java.util.ResourceBundle

/**
 * 词表读取的**纯函数**层 —— 不碰平台、不读设置，可以在纯 JVM 单测里直接跑。
 *
 * 与 [IdeLocale] 的分工照 `ThemeInjector` 的先例（那边是 `buildCss` 纯函数 +
 * `PlatformTheme.read()` 平台读）：这一层可测，那一层不测。
 *
 * ## 布局
 *
 * ```
 * messages/CcoderBundle.properties      基础 = 英文（也是回退）
 * messages/CcoderBundle_zh.properties   中文
 * ```
 *
 * 平台自带「插件翻译」（2024.1 起）本来就按这套后缀查找：`_zh_CN` → `_zh` → 无后缀。
 * 我们只是**自己指定 Locale** 去调它 —— 平台的自动查找只认 IDE 语言，
 * 认不了插件里那个「中文 / English」下拉（理由与证据见设计稿 §0.4）。
 *
 * 建 `_zh` 而不是 `_zh_CN`：`SIMPLIFIED_CHINESE` 的候选是 `zh_CN → zh → 基础`，
 * 一份 `_zh` 同时覆盖两者；将来要加 `_zh_TW` 也不用动别的。
 * **不要**建 `_en`：基础那份就是英文，多一份只会有两个地方要改。
 */
internal object TextCatalog {

    /** `ResourceBundle` 的基础名。`messages` 是资源目录，不能省。 */
    const val BASE_NAME = "messages.CcoderBundle"

    /**
     * 搜索顺序里**不带系统默认 Locale**。
     *
     * 默认的 `Control.getFallbackLocale` 会在候选都落空之后插一脚*系统默认 Locale* ——
     * 实测（2026-09-20，Temurin 21，`Locale.setDefault(zh_CN)`）：
     * `getBundle("messages.CcoderBundle", Locale.ENGLISH)` 返回的是 **`_zh` 那份**，
     * 哪怕基础（英文）那份就在 classpath 上。
     *
     * 后果正是这个仓库最不能忍的一类：中文 Windows 上，英文 IDE 的用户选了 English、
     * 却被系统默认 Locale 悄悄改回中文 —— 而且**不报错**。
     *
     * 返回 null = 候选走完直接落到基础词表。**必须用同一个 Control 实例**：
     * JDK 的缓存键算上了它，每次 new 一个就等于每次重新读盘。
     */
    private val control = object : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String, locale: Locale): Locale? = null
    }

    fun bundleFor(locale: Locale): ResourceBundle =
        ResourceBundle.getBundle(BASE_NAME, locale, control)

    fun has(bundle: ResourceBundle, key: String): Boolean = bundle.containsKey(key)

    fun keysOf(locale: Locale): Set<String> = bundleFor(locale).keySet()

    /**
     * 取词，并把 `{0}`、`{1}`… 换成实参。
     *
     * **刻意不用 `MessageFormat`**：它要求字面撇号写成 `''`，而英文文案里满是
     * `Don't ask` / `Claude's` —— 漏一个就把 `don't` 渲染成 `dont`，是**静默的句子
     * 损坏**而不是报错。这里只做朴素替换，撇号原样穿过（设计稿 §1.4）。
     *
     * 缺参不抛：`{1}` 原样留在句子里，看得见。Swing 的渲染路径不许因为一条文案炸掉，
     * 与 `connectionTone` 那条 `else` 是同一种取舍。
     *
     * 键不存在时返回**键本身** —— 判据与理由在 [CcoderText.text]。
     */
    fun render(bundle: ResourceBundle, key: String, args: Array<out Any>): String {
        if (!has(bundle, key)) return key
        val raw = bundle.getString(key) ?: return key
        if (args.isEmpty()) return raw

        var out = raw
        for ((index, arg) in args.withIndex()) out = out.replace("{$index}", arg.toString())
        return out
    }
}
