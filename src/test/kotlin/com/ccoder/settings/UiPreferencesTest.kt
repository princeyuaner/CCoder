package com.ccoder.settings

import com.intellij.openapi.components.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 界面偏好那份 State 的形状。
 *
 * 服务自己就能在纯 JVM 里建出来（没有平台依赖，注解只是元数据），所以直接 new ——
 * 与 `UiLanguageSettingsTest` 同一个套路（那边也是直接 new 的）。
 */
class UiPreferencesTest {

    @Test
    fun `默认不折叠 —— 2026-09-14 定的默认形态没被动过`() {
        // 这条是"升级不偷袭"的守门人：默认值一改，所有老用户的观感都会跟着变，
        // 而那是用户当初明确要求过的（"思考块默认展开"）
        assertFalse(UiPreferences().collapseThinking)
    }

    @Test
    fun `setter 落在 state 上 —— 落盘的永远是用户选的那个值`() {
        val prefs = UiPreferences()

        prefs.collapseThinking = true
        assertTrue(prefs.collapseThinking)
        assertTrue(prefs.state.collapseThinking)

        prefs.collapseThinking = false
        assertFalse(prefs.state.collapseThinking)
    }

    @Test
    fun `读盘逐字段复制 —— 加字段时漏在 loadState 里会静默丢掉，这条钉住现有的那几个`() {
        // 形状照 ClaudeSettings.loadState：那边的教训是"忘了写就静默丢"，
        // 症状是"重启 IDE 之后设置又变回去了"
        val prefs = UiPreferences().apply {
            loadState(
                UiPreferences.State(
                    collapseThinking = true,
                    fontChoice = FontChoice.GEORGIA.name,
                    fontScale = FontScale.XLARGE.name,
                )
            )
        }

        assertTrue(prefs.collapseThinking)
        assertEquals(FontChoice.GEORGIA, prefs.fontChoice)
        assertEquals(FontScale.XLARGE, prefs.fontScale)
    }

    @Test
    fun `字体与字号的默认档 = 跟随 IDE + 标准`() {
        val prefs = UiPreferences()

        assertEquals(FontChoice.FOLLOW_IDE, prefs.fontChoice)
        assertEquals(FontScale.NORMAL, prefs.fontScale)
    }

    @Test
    fun `存的是枚举名，而且非法名读出来回默认 —— 但**不改写**盘上那份`() {
        val prefs = UiPreferences().apply {
            loadState(UiPreferences.State(fontChoice = "BOGUS", fontScale = "BOGUS"))
        }

        // 读出来是默认档（设置页因此不会崩）
        assertEquals(FontChoice.DEFAULT, prefs.fontChoice)
        assertEquals(FontScale.DEFAULT, prefs.fontScale)
        // 盘上那份原样留着 —— 在 loadState 里"修好"它就等于把非法值抹掉，
        // 探针再也测不到"打开设置什么都没写"这一路
        assertEquals("BOGUS", prefs.state.fontChoice)
        assertEquals("BOGUS", prefs.state.fontScale)
    }

    @Test
    fun `setter 落的是枚举名`() {
        val prefs = UiPreferences().apply {
            fontChoice = FontChoice.SIMSUN
            fontScale = FontScale.SMALL
        }

        assertEquals("SIMSUN", prefs.state.fontChoice)
        assertEquals("SMALL", prefs.state.fontScale)
    }

    @Test
    fun `平台反射要一个无参构造器`() {
        // @Service(APP) 的类由平台反射实例化（见类注释）—— 少了无参构造，
        // 真机上第一次取服务就炸，而那时已经离"框架的玄学"很难分清了
        assertNotNull(UiPreferences::class.java.getDeclaredConstructor().newInstance())
    }

    @Test
    fun `落盘的名字是 CCoderUiPreferences`() {
        // 改名 = 老用户那份偏好在升级后静默丢失（XML 对新名字一无所知，
        // 而默认值恰好是"不折叠"—— 现象是"我明明勾过，怎么又没了"）
        val state = UiPreferences::class.java.getAnnotation(State::class.java)

        assertEquals("CCoderUiPreferences", state.name)
    }
}
