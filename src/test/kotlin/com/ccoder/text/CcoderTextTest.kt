package com.ccoder.text

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * 语言怎么被选中。
 *
 * 判据顺序（[IdeLocale.detect]）：系统属性 → 平台 → 英文。
 * 测试靠**系统属性**这一档，因此这一族用例永远不会碰平台。
 */
class CcoderTextTest {

    private var savedProperty: String? = null

    @BeforeEach
    fun saveProperty() {
        savedProperty = System.getProperty(IdeLocale.LANG_PROPERTY)
    }

    @AfterEach
    fun restoreProperty() {
        CcoderText.setOverride(null)
        if (savedProperty == null) {
            System.clearProperty(IdeLocale.LANG_PROPERTY)
        } else {
            System.setProperty(IdeLocale.LANG_PROPERTY, savedProperty!!)
        }
    }

    private fun sayLanguage(tag: String) = System.setProperty(IdeLocale.LANG_PROPERTY, tag)

    @Test
    fun `系统属性说中文 —— 取到中文那份词表`() {
        sayLanguage("zh")

        assertEquals("zh", CcoderText.tag())
        assertEquals("界面语言", CcoderText.text("settings.language.label"))
    }

    @Test
    fun `系统属性说英文 —— 取到基础那份（英文）`() {
        sayLanguage("en")

        assertEquals("en", CcoderText.tag())
        assertEquals("Interface language", CcoderText.text("settings.language.label"))
    }

    @Test
    fun `认不出的语言按英文 —— 不回退系统默认 Locale`() {
        // 中文 Windows 上 Locale.getDefault() 是 zh_CN：拿它当回退，会让英文界面的 IDE
        // 也判成中文，静默盖掉用户的选择
        sayLanguage("fr")

        assertEquals("en", CcoderText.tag())
        assertEquals("Interface language", CcoderText.text("settings.language.label"))
    }

    @Test
    fun `zh 的各种写法都认`() {
        for (tag in listOf("zh", "zh-CN", "zh_CN", "ZH")) {
            assertEquals(Locale.SIMPLIFIED_CHINESE, IdeLocale.localeOf(tag), "认不出 $tag")
        }
    }

    @Test
    fun `设置里的选择盖过系统属性`() {
        sayLanguage("en")
        CcoderText.setOverride(Locale.SIMPLIFIED_CHINESE)

        assertEquals("zh", CcoderText.tag())
        assertEquals("界面语言", CcoderText.text("settings.language.label"))
    }

    @Test
    fun `撤销选择后回到系统属性`() {
        sayLanguage("en")
        CcoderText.setOverride(Locale.SIMPLIFIED_CHINESE)
        CcoderText.setOverride(null)

        assertEquals("en", CcoderText.tag())
        assertEquals("Interface language", CcoderText.text("settings.language.label"))
    }

    @Test
    fun `tag 与 text 永远同一门语言 —— 缓存按 Locale 走`() {
        // 两份缓存各判各的，就会出现"sidecar 拿到 zh、界面显示 en"
        sayLanguage("zh")
        CcoderText.text("settings.language.label")   // 先把 zh 那份缓存起来
        sayLanguage("en")                            // 只有测试会中途换（生产是进程内稳定）

        assertEquals("en", CcoderText.tag())
        assertEquals("Interface language", CcoderText.text("settings.language.label"))
    }

    @Test
    fun `has 与 textOrNull`() {
        sayLanguage("zh")

        assertTrue(CcoderText.has("settings.language.label"))
        assertEquals("界面语言", CcoderText.textOrNull("settings.language.label"))
        assertEquals(null, CcoderText.textOrNull("no.such.key"))
    }
}
