package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 语言那条注入语句的形状。
 *
 * 照 [ThemeInjectorTest] 的先例：真机上这条语句写错了**不会报错** ——
 * `executeJavaScript` 的返回值没人看，页面只会安静地停在基础语言上。
 * 所以只能在这一层钉住。
 */
class LocaleInjectorTest {

    @Test
    fun `注入语句是单行的、并且防了桥还没注入的情况`() {
        val script = LocaleInjector.buildInjectScript("zh")

        assertFalse(script.contains("\n"), "必须单行：实际是 $script")
        // 页面加载完之前 window.ccoderSetLocale 还不存在，那时这条会以 TypeError
        // 静默失败 —— 而那是预期路径（同 ThemeInjector 那条防御）
        assertTrue(script.startsWith("window.ccoderSetLocale &&"), "实际是 $script")
        assertTrue(script.contains("window.ccoderSetLocale('zh')"), "实际是 $script")
    }

    @Test
    fun `标签会被转义 —— 撇号不能把语句撕开`() {
        // 真实的标签只有 zh / en，但这条语句的形状必须与主题那条一样稳：
        // 哪天有人把用户可控的东西塞进来，转义是唯一还站着的东西
        val script = LocaleInjector.buildInjectScript("a'b")

        assertTrue(script.contains("'a\\'b'"), "撇号没转义：$script")
        assertEquals(1, script.count { it == ';' }, "语句被撕成了多条：$script")
    }
}
