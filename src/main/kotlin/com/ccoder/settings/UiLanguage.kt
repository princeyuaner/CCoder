package com.ccoder.settings

import com.ccoder.text.CcoderText
import java.util.Locale

/**
 * 「界面语言」这一档的取值。
 *
 * ## 语言名是**自称**，永不翻译
 *
 * `中文` / `English` 两条按各自的语言写死：看不懂当前界面语言的人，要能在下拉里
 * **认出自己那一行** —— 翻成 `Chinese` 就把这根唯一的救命绳剪了。
 * 只有「跟随 IDE」是词表里的键（它得跟着当前语言走）。
 *
 * ## 存的是 [id] 字符串
 *
 * 同 `sendShortcut` / `effort` 的先例：认不出的值一律回到 [FOLLOW_IDE]，
 * 手改过 XML 的人不会因为一个错字就卡在某个语言上。
 */
enum class UiLanguage(val id: String, private val endonym: String?) {
    FOLLOW_IDE("follow", null),
    ZH("zh", "中文"),
    EN("en", "English"),
    ;

    /** 下拉里显示的那行字。 */
    fun label(): String = endonym ?: CcoderText.text("settings.language.followIde")

    /** `null` = 跟随 IDE（由 [com.ccoder.text.IdeLocale] 判）。 */
    fun localeOrNull(): Locale? = when (this) {
        FOLLOW_IDE -> null
        ZH -> Locale.SIMPLIFIED_CHINESE
        EN -> Locale.ENGLISH
    }

    /** `ComboBox` 直接渲染这个（同 `PermissionModeSetting` 的做法）。 */
    override fun toString(): String = label()

    companion object {
        fun fromId(raw: String?): UiLanguage = entries.firstOrNull { it.id == raw } ?: FOLLOW_IDE
    }
}
