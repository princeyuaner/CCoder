package com.ccoder.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * 键与代码的对账，**两个方向**。
 *
 * 为什么值得写这条：`CcoderText` 缺键时返回**键本身**（那是刻意的 —— 显示英文是撒谎），
 * 所以一个打错的键会在界面上显示成 `settings.page.general`。而词表里一条没人用的键
 * 会一直躺着、被下一个译者当成真在用的东西去翻。
 *
 * 这条用例的形状照 `build.gradle.kts` 里那条 sidecar 相对引入的反向检查：
 * 那种"代码里写了、但资源里没有"的错，只有在源码层面扫一遍才拦得住。
 *
 * **扫的是源码文本，不是运行时**：所以只认字面量调用。键**永不动态拼**
 * （见 CcoderBundle.properties 顶上的约定），这条用例才有意义。
 */
class TextKeysTest {

    /** `CcoderText.text("…")` / `textOrNull("…")` / `has("…")`。 */
    private val callSite = Regex("""CcoderText\.(?:text|textOrNull|has)\(\s*"([^"]+)"""")

    /** plugin.xml 里的 `%key`（action 的 text/description 用它）。 */
    private val xmlRef = Regex("""%([a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9]+)+)""")

    private val mainSources = File("src/main/kotlin")
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml")

    private fun sources(): List<File> = mainSources.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sorted()
        .toList()

    /**
     * 去掉注释行。
     *
     * 文档注释里会**引用**键（"缺键时显示 `settings.page.general`"这种），
     * 那不是调用点。规则同仓库里其它源码扫描：只看非注释行。
     */
    private fun codeLines(file: File): List<String> = file.readLines().filterNot { line ->
        val trimmed = line.trimStart()
        trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
    }

    @Test
    fun `代码里引用的键，词表里都得有`() {
        val missing = mutableListOf<String>()
        for (file in sources()) {
            for ((lineNumber, line) in codeLines(file).withIndex()) {
                for (match in callSite.findAll(line)) {
                    val key = match.groupValues[1]
                    if (!TextCatalog.has(base(), key)) missing += "$key（${file.name} 附近 $lineNumber）"
                }
            }
        }
        assertEquals(emptyList<String>(), missing, "这些键在基础词表里找不到")
    }

    @Test
    fun `plugin_xml 里引用的键，词表里也得有`() {
        val missing = xmlRef.findAll(pluginXml.readText())
            .map { it.groupValues[1] }
            .filterNot { TextCatalog.has(base(), it) }
            .toList()
        assertEquals(emptyList<String>(), missing, "plugin.xml 引用了词表里没有的键")
    }

    @Test
    fun `词表里的键都有人引用 —— 没人用的键会烂在词表里`() {
        val literals = sources().flatMap { codeLines(it) }.joinToString("\n")
        val xml = pluginXml.readText()

        val dead = base().keySet()
            .filterNot { key -> literals.contains("\"$key\"") || xml.contains("%$key") }
            .sorted()

        assertEquals(
            emptyList<String>(),
            dead,
            "这些键没有任何地方引用：要么接上线，要么从两份词表里删掉",
        )
    }

    private fun base() = TextCatalog.bundleFor(Locale.ENGLISH)
}
