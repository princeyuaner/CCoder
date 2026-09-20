package com.ccoder.ui

import com.ccoder.text.TextCatalog
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 送给模型那三句拒绝语，**跨语言逐字一致**。
 *
 * 「用户拒绝」在两侧各写了一遍（Kotlin 的 [DENY_MESSAGE]、sidecar 的 `index.js`），
 * 而它是随权限回执发给 CLI、进而进模型上下文的 —— 只要有一侧被"顺手翻译"了，
 * 就会出现"界面说 X、模型被告知 Y"，而且**没有任何东西会红**。
 *
 * 所以用一份 fixture 把三条钉住，照 `shared/transcript-ops.json` 的先例：
 * 两侧的测试读同一份文件（sidecar 那份在 `sidecar/test/deny-message.test.js`）。
 *
 * 反向那半也在下面：这三句**不许**出现在任何一份词表里 —— 那才是"有人把它翻掉"的真凭据。
 */
class DenyMessageFixtureTest {

    private fun fixture(): JsonObject {
        val path = Path.of("shared", "deny-message.json")
        check(Files.exists(path)) { "找不到共享 fixture：${path.toAbsolutePath()}" }
        return JsonParser.parseString(Files.readString(path)).asJsonObject
    }

    private fun denies(): List<String> = fixture().entrySet().map { it.value.asString }

    @Test
    fun `Kotlin 这边的拒绝语与 fixture 逐字一致`() {
        assertEquals(fixture().get("permissionDeny").asString, DENY_MESSAGE)
    }

    @Test
    fun `fixture 的形状没变 —— 少一个键就该在这里红，而不是等到读不出来`() {
        assertEquals(
            setOf("permissionDeny", "interrupt", "stop"),
            fixture().keySet(),
            "shared/deny-message.json 的键变了：两侧的断言要跟着改",
        )
    }

    @Test
    fun `这三句都不在任何一份词表里 —— 它们是协议内容，不是界面文案`() {
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
            val bundle = TextCatalog.bundleFor(locale)
            val values = bundle.keySet().mapNotNull { bundle.getString(it) }
            for (deny in denies()) {
                assertFalse(
                    values.any { it == deny },
                    "$locale 的词表里出现了拒绝语「$deny」—— 它不该跟着界面语言走",
                )
            }
        }
    }
}
