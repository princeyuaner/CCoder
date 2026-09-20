package com.ccoder.text

import com.intellij.DynamicBundle
import java.util.Locale

/**
 * 「跟随 IDE」时语言从哪儿来 —— 全插件**唯一读平台**的那一处，因此不写单测
 * （照 `ThemeInjector.PlatformTheme.read()` 的先例：平台读那半靠离屏探针看结果）。
 */
internal object IdeLocale {

    /**
     * 测试与探针的开关，**必须排在平台之前**。
     *
     * 纯 JVM 单测里没有 IDE，而 121 个测试文件、约 731 条中文断言要的是一份
     * **确定**的语言 —— 不能随跑测试的机器变（`build.gradle.kts` 的 `tasks.test`
     * 把它钉成 `zh`，改英文那遍用 `-PtestLang=en`）。
     */
    const val LANG_PROPERTY = "ccoder.lang"

    fun detect(): Locale {
        System.getProperty(LANG_PROPERTY)?.let { return localeOf(it) }

        // `DynamicBundle.getLocale()` 拿到的字面上就是「IDE 自己的界面语言」
        // （由语言包 / 语言设置驱动），正是「跟随 IDE」要的语义。
        // 它是静态方法，不需要 Application。
        // 包 runCatching：纯 JVM 环境下平台从没 loadLocale 过，那里的行为不作为依据。
        runCatching { DynamicBundle.getLocale() }.getOrNull()?.let { return it }

        // **刻意不回退 `Locale.getDefault()`**：中文 Windows 上它是 zh_CN，
        // 于是英文界面的 IDE 也会被判成中文 —— 静默盖掉用户的选择，正是这个仓库最不能忍的
        // 那一类不实。判不出来就说判不出来，显示基础词表（英文）。
        return Locale.ENGLISH
    }

    /** `zh` / `zh-CN` / `zh_CN` 这类标签 → Locale；认不出的一律按英文。 */
    fun localeOf(tag: String): Locale = when {
        tag.startsWith("zh", ignoreCase = true) -> Locale.SIMPLIFIED_CHINESE
        else -> Locale.ENGLISH
    }
}
