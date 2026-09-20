package com.ccoder.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

/**
 * 词表本身的形状。
 *
 * 这里钉的都是**译文层会静默坏掉**的那几处：空值、首尾空白、占位符错位、
 * 把中文抄进基础词表。它们不会抛异常，只会在界面上少一个词、多一个空格、
 * 或者显示 `{1}`。
 */
class TextCatalogTest {

    private val base = TextCatalog.bundleFor(Locale.ENGLISH)
    private val zh = TextCatalog.bundleFor(Locale.SIMPLIFIED_CHINESE)

    private fun baseKeys() = base.keySet()
    private fun zhKeys() = zh.keySet()

    @Test
    fun `中英两份的键集必须一致`() {
        // 少了中文：中文界面显示英文（还算能读）；少了英文：基础词表缺键 → 界面显示裸键。
        // 两边都该在合并前被拦住，而不是等用户截图来问。
        assertEquals(baseKeys(), zhKeys(), "中英词表的键对不上")
    }

    @Test
    fun `没有空值，也没有首尾空白`() {
        for ((locale, bundle) in listOf(Locale.ENGLISH to base, Locale.SIMPLIFIED_CHINESE to zh)) {
            for (key in bundle.keySet()) {
                val value = bundle.getString(key).orEmpty()
                assertTrue(value.isNotBlank(), "$locale 的 $key 是空的")
                // 拼接（"$message\n\n" + 正文）留在代码里，值里不许带空白 ——
                // 译者看不见的那些会被顺手删掉
                assertEquals(value.trim(), value, "$locale 的 $key 带首尾空白：'$value'")
            }
        }
    }

    @Test
    fun `占位符下标从 0 连续`() {
        // 抓"译者重排句子时丢了 {1}"：那种句子上线后会原样显示 {1}
        val placeholder = Regex("\\{(\\d+)}")
        for ((locale, bundle) in listOf(Locale.ENGLISH to base, Locale.SIMPLIFIED_CHINESE to zh)) {
            for (key in bundle.keySet()) {
                val value = bundle.getString(key).orEmpty()
                // 先看有没有不是 {数字} 的花括号（除 MessageFormat 之外的写法一律不准）
                val stray = value.replace(placeholder, "").let { it.contains("{") || it.contains("}") }
                assertFalse(stray, "$locale 的 $key 里有非 {数字} 的花括号：$value")

                val indices = placeholder.findAll(value).map { it.groupValues[1].toInt() }.toSet()
                if (indices.isEmpty()) continue
                assertEquals(
                    (0 until indices.size).toSet(),
                    indices,
                    "$locale 的 $key 占位符不连续：$value",
                )
            }
        }
    }

    @Test
    fun `英文基础词表里不许有中文 —— 专抓把中文抄进基础那份`() {
        // 抄错了不会报错，只会让"英文界面"里冒出一句中文。允许"中文"二字出现：
        // 那是语言自称（见 UiLanguage 的注释）。
        val cjk = Regex("[\\u4e00-\\u9fff]")
        for (key in baseKeys()) {
            val value = base.getString(key).orEmpty()
            assertFalse(
                cjk.containsMatchIn(value.replace("中文", "")),
                "基础词表的 $key 里有中文：$value",
            )
        }
    }

    @Test
    fun `撇号原样穿过 —— 这就是不用 MessageFormat 的理由`() {
        // MessageFormat 会把 don't 渲染成 dont（要写字面撇号得写 ''）。
        // 这条钉住"我们从来不碰撇号"这件事本身。
        val bundle = bundleOf("x.apostrophe=Don't ask again — Claude's project, the user's call.")
        val out = TextCatalog.render(bundle, "x.apostrophe", emptyArray())
        assertTrue(out.contains("Don't ask"), "撇号被吃掉了：$out")
        assertTrue(out.contains("Claude's"), "撇号被吃掉了：$out")
        assertTrue(out.contains("user's"), "撇号被吃掉了：$out")
    }

    @Test
    fun `占位符按实参替换 —— 多参数与数字都行`() {
        val bundle = bundleOf("x.count=已用 {0}，窗口 {1}，剩余 {2}")
        assertEquals(
            "已用 12.3k，窗口 200k，剩余 87%",
            TextCatalog.render(bundle, "x.count", arrayOf("12.3k", "200k", "87%")),
        )
    }

    @Test
    fun `键不存在时返回键本身，不回退英文`() {
        // 静默显示英文是在撒谎；显示裸键是一眼能报的 bug。
        assertEquals("no.such.key", TextCatalog.render(base, "no.such.key", emptyArray()))
        assertEquals("no.such.key", CcoderText.text("no.such.key"))
    }

    @Test
    fun `读原始文件必须走 UTF-8 —— Properties_load 是 ISO-8859-1`() {
        // 这条钉的是一个**陷阱**而不是功能：用例里读 .properties 最顺手的写法是
        // Properties.load(InputStream)，它会用 ISO-8859-1 解，于是每个中文值都变成乱码，
        // 而 containsKey 这类检查照样通过。这里直接用正确的读法比对一份真值。
        val file = File("src/main/resources/messages/CcoderBundle_zh.properties")
        assertTrue(file.isFile, "找不到 ${file.absolutePath}")

        val correct = PropertyResourceBundle(InputStreamReader(file.inputStream(), Charsets.UTF_8))
        assertEquals("界面语言", correct.getString("settings.language.label"))

        // 反面对照：直觉那种写法读出来是乱的（不是因为文件坏了）
        val naive = java.util.Properties().apply { file.inputStream().use { load(it) } }
        assertFalse(
            naive.getProperty("settings.language.label") == "界面语言",
            "这份文件居然能被 ISO-8859-1 读对？那说明它已经不是 UTF-8 了",
        )
    }

    @Test
    fun `英文查询不受系统默认 Locale 影响 —— 这条踩过`() {
        // 默认的 ResourceBundle.Control 会在候选落空后插一脚**系统默认 Locale**：
        // 实测 Temurin 21 + Locale.setDefault(zh_CN) 时，`getBundle(…, ENGLISH)`
        // 返回的是 `_zh` 那份，哪怕基础（英文）那份就在 classpath 上。
        // 中文 Windows 上这会把"选了 English"的用户悄悄改回中文，且不报错。
        //
        // 每条要先 clearCache：不然前面的用例已经把英文那份缓存好了，这条永远绿。
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)

            ResourceBundle.clearCache()
            assertEquals(
                "Interface language",
                TextCatalog.bundleFor(Locale.ENGLISH).getString("settings.language.label"),
                "问英文却拿到了别的语言",
            )

            ResourceBundle.clearCache()
            assertEquals(
                "界面语言",
                TextCatalog.bundleFor(Locale.SIMPLIFIED_CHINESE).getString("settings.language.label"),
            )
        } finally {
            Locale.setDefault(saved)
            ResourceBundle.clearCache()
        }
    }

    private fun bundleOf(vararg lines: String): ResourceBundle =
        PropertyResourceBundle(StringReader(lines.joinToString("\n")))
}
