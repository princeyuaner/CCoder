package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 字体那两档的规则：认不出的名字怎么兜底、列表里该有哪几档、栈长什么样。
 *
 * 这里**不**断言"本机装了哪些字体" —— 那是机器的事。只用不变量钉住：
 * 永远在的那两档一定在、上次选的那档一定在、每条栈都以通用族收尾。
 */
class UiFontTest {

    @Test
    fun `认不出的名字回默认档 —— 手改过 XML 的人不该让设置页崩`() {
        // fromName 的兜底值喂给 getter，combo.selectedItem 才不会拿到 null，
        // save() 里那个 `as FontChoice` 也就不会抛 ClassCastException
        assertEquals(FontChoice.DEFAULT, FontChoice.fromName("BOGUS"))
        assertEquals(FontChoice.DEFAULT, FontChoice.fromName(null))
        assertEquals(FontChoice.DEFAULT, FontChoice.fromName(""))
        assertEquals(FontChoice.GEORGIA, FontChoice.fromName("GEORGIA"))

        assertEquals(FontScale.DEFAULT, FontScale.fromName("BOGUS"))
        assertEquals(FontScale.DEFAULT, FontScale.fromName(null))
        assertEquals(FontScale.XLARGE, FontScale.fromName("XLARGE"))
    }

    @Test
    fun `默认档 = 跟随 IDE + 标准 —— 设计稿从第一天起就要的就是它`() {
        assertEquals(FontChoice.FOLLOW_IDE, FontChoice.DEFAULT)
        assertEquals(FontScale.NORMAL, FontScale.DEFAULT)
        assertEquals(1.0, FontScale.DEFAULT.factor)
        // 那一档**不是**默认：想退回今天的样子（浏览器兜底字体）的人自己去选它
        assertFalse(FontChoice.SYSTEM == FontChoice.DEFAULT)
    }

    @Test
    fun `列表里永远有跟随 IDE 与系统默认`() {
        val choices = FontChoice.availableChoices(FontChoice.DEFAULT)

        assertTrue(FontChoice.FOLLOW_IDE in choices)
        assertTrue(FontChoice.SYSTEM in choices)
    }

    @Test
    fun `上次选的那一档一定在列表里 —— 哪怕本机已经取不到它`() {
        // 装过又卸了的字体：下拉里得留着它，否则 combo 会默默显示成别的一档，
        // 而用户一按就把那份选择写没了
        val choices = FontChoice.availableChoices(FontChoice.KAI)

        assertTrue(FontChoice.KAI in choices)
    }

    @Test
    fun `每条栈都以通用族收尾 —— IDE 自带字体没装进系统时的最后一道兜底`() {
        for (choice in FontChoice.entries) {
            val stack = choice.uiStack("JetBrains Sans")

            assertTrue(
                stack.endsWith("sans-serif") || stack.endsWith("serif") || stack.endsWith("monospace"),
                "$choice 的栈没有通用族收尾：$stack",
            )
        }
    }

    @Test
    fun `跟随 IDE 用平台给的家族名，系统默认用通用族`() {
        assertTrue(FontChoice.FOLLOW_IDE.uiStack("Segoe UI").startsWith("\"Segoe UI\""))
        assertEquals("sans-serif", FontChoice.SYSTEM.uiStack("随便什么"))
    }

    @Test
    fun `解析：正文档换家族名，等宽栈始终跟编辑器字体`() {
        val fonts = resolveUiFonts(
            FontChoice.GEORGIA,
            FontScale.LARGE,
            "JetBrains Sans",
            "JetBrains Mono",
        )

        assertTrue(fonts.ui.startsWith("\"Georgia\""), fonts.ui)
        // 选正文档**不动代码块**：等宽栈仍然是平台给的编辑器字体
        assertTrue(fonts.mono.startsWith("\"JetBrains Mono\""), fonts.mono)
        assertEquals(1.15, fonts.scale)
    }

    @Test
    fun `家族名里的危险字符被清掉 —— 一个引号能让整份主题静默失效`() {
        // escapeForJsString 管的是 JS 那一层，管不到 CSS：值里出现 `"`、`;` 或 `}`
        // 会把 `:root { … }` 提前关掉，页面静默退回落色
        val fonts = resolveUiFonts(
            FontChoice.FOLLOW_IDE,
            FontScale.NORMAL,
            ideUiFamily = "We\"ird}; Font",
            ideMonoFamily = "Mono\\\"; font",
        )

        assertTrue(fonts.ui.startsWith("\"Weird Font\""), fonts.ui)
        assertFalse(fonts.ui.contains('}'), fonts.ui)
        assertTrue(fonts.ui.endsWith("sans-serif"), fonts.ui)
        assertTrue(fonts.mono.startsWith("\"Mono font\""), fonts.mono)
        assertTrue(fonts.mono.endsWith("monospace"), fonts.mono)
    }

    @Test
    fun `四档的显示名都是人话、都不重复`() {
        val labels = FontScale.entries.map { it.toString() }
        val names = FontScale.entries.map { it.name }.toSet()

        assertTrue(labels.all { it.isNotBlank() && it !in names }, "下拉里显示的是枚举名？实际：$labels")
        assertEquals(4, labels.toSet().size, "四档显示名重了：$labels")
    }
}
