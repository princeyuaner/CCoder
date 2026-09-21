package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 偏好那条注入语句的形状。
 *
 * 照 [LocaleInjectorTest] 的先例：真机上这条语句写错了**不会报错** ——
 * `executeJavaScript` 的返回值没人看，页面只会安静地停在默认形态上
 * （思考块一直展开，用户以为那个开关坏了）。所以只能在这一层钉住。
 */
class PrefsInjectorTest {

    @Test
    fun `注入语句是单行的、并且防了桥还没注入的情况`() {
        val script = PrefsInjector.buildInjectScript(collapseThinking = true)

        assertFalse(script.contains("\n"), "必须单行：实际是 $script")
        // 页面加载完之前 window.ccoderSetPrefs 还不存在，那时这条会以 TypeError
        // 静默失败 —— 而那是预期路径（同 ThemeInjector / LocaleInjector 那条防御）
        assertTrue(script.startsWith("window.ccoderSetPrefs &&"), "实际是 $script")
    }

    @Test
    fun `两个值都带对 —— 载荷是对象，不是裸布尔`() {
        // 对象是 B/C 切片（字号、密度、配色）要复用的形状：一份多键快照，
        // 而不是"今天只有一个开关"的临时写法。按 Gson 的**实际**输出钉
        // （手拼"我以为的"那种写法，空格一改就红，而红的理由不该是空格）
        assertEquals("{\"collapseThinking\":true}", PrefsInjector.encode(true))
        assertEquals("{\"collapseThinking\":false}", PrefsInjector.encode(false))
    }

    @Test
    fun `语句用的就是 encode 那一份 —— 载荷只有一个出处`() {
        // 桥脚本里注入的那份快照与推送这份都走 encode（见 injectBridge）：
        // 两处各拼一份，迟早有一份忘了某个键，而那是静默的。
        // 另一侧的对应物是 `web/src/prefs.test.ts` 里 `applyPrefs({ collapseThinking: … })`
        // 那几条 —— 两侧各自的测试都绿、约定对不上，就是 pushBatch 那次的形状
        for (value in listOf(true, false)) {
            val script = PrefsInjector.buildInjectScript(value)

            assertTrue(script.contains(PrefsInjector.encode(value)), script)
        }
    }

    @Test
    fun `一条语句只到分号为止 —— 不许被撕成多条`() {
        val script = PrefsInjector.buildInjectScript(true)

        assertEquals(1, script.count { it == ';' }, script)
    }
}
