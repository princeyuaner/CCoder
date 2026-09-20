package com.ccoder.settings

import com.ccoder.text.CcoderText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 「写值 = 立刻生效」（2026-09-20 从"改完要重启"改成热切换后的行为）。
 *
 * 用**真服务**而不是 mock：这个类该干的三件事都纯 JVM 可测 —— 写状态、推给
 * [CcoderText]、通知订阅者。碰平台的只有 `getInstance()`，用例里不走它。
 *
 * 测试 JVM 的语言由 `build.gradle.kts` 钉成 zh（`ccoder.lang`），所以这几条里
 * 「跟随 IDE」就等于中文。
 */
class UiLanguageSettingsTest {

    private val settings = UiLanguageSettings()

    @AfterEach
    fun 收尾() {
        // CcoderText 是全局的：不清掉，"这个用例改过语言"会漏给下一个
        CcoderText.setOverride(null)
    }

    @Test
    fun `写成英文 —— 词表层立刻是英文`() {
        settings.language = UiLanguage.EN
        assertEquals("en", CcoderText.tag())
        assertEquals("en", settings.language.id)
    }

    @Test
    fun `改回跟随 IDE —— 词表层立刻回到 IDE 那门`() {
        settings.language = UiLanguage.EN
        settings.language = UiLanguage.FOLLOW_IDE
        assertEquals("zh", CcoderText.tag())
    }

    @Test
    fun `订阅者会被喊到`() {
        var calls = 0
        settings.addListener { calls++ }
        settings.language = UiLanguage.EN
        assertEquals(1, calls)
    }

    @Test
    fun `写同一档不算变化 —— 不喊`() {
        settings.language = UiLanguage.EN
        var calls = 0
        settings.addListener { calls++ }
        // 设置页每次改动都 save()，而 reload() 也会触发监听器：同一档反复写很常见，
        // 那种"没变"的写不该让整块界面白重译一遍
        settings.language = UiLanguage.EN
        assertEquals(0, calls)
    }

    @Test
    fun `退订之后不再喊`() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        settings.addListener(listener)
        settings.language = UiLanguage.EN
        settings.removeListener(listener)
        settings.language = UiLanguage.ZH
        assertEquals(1, calls)
    }
}
