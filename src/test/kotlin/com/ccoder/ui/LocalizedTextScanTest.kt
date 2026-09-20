package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 源码扫描：**长命控件里不许把翻好的字直接塞进控件**。
 *
 * ## 为什么需要它
 *
 * 热切换（2026-09-20）靠的是"键记在控件上，语言一变走树重取"（`LocalizedText.kt`）。
 * 而 `JBLabel(CcoderText.text("k"))` 这种写法照样能编译、照样好看，只是**换语言时
 * 它永远不会变** —— 界面上静静留着一角旧语言。行为型的用例（`LocalizedTextTest`
 * 里那条走树扫描）只能盖到它建得动的组件，盖不到的地方就靠这条源码扫描：
 *
 * > 这些文件里，`CcoderText.text(` 不许出现在"赋值给控件文字/tooltip"或
 * > "控件构造函数实参"的位置上。
 *
 * ## 边界（哪些不算）
 *
 * 只认**填进控件**这一种形态。下面这些都不算，也不该拦：
 *
 * - 日志、通知、对话框正文、`RenderItem` 那些"说出去就不再变"的字
 *   （转写区里的条目是历史，本来就该留在当时的语言里）；
 * - 每开一次就重建的弹层（`SessionList` / `RunDetail` / `AskQuestionCard`…）——
 *   不在本用例的名单里，因为它们下次打开自然是新语言；
 * - 每次刷新都会重跑的位置（`refresh*` / `get()` 属性 / 纯函数）—— 重算即新语言。
 *
 * 名单与理由都写死在下面，改名单要连着这里一起改。
 */
class LocalizedTextScanTest {

    /**
     * 长命控件所在的文件：这些东西的界面会活很久（整个工具窗口 / 整个会话），
     * 所以"构造时塞进去的字"永远不会自己更新。
     */
    private val longLived = listOf(
        "ClaudePanel.kt",
        "Composer.kt",
        "TopRow.kt",
        "AttachmentStrip.kt",
        "QueueStrip.kt",
        "SessionChips.kt",
        "StatusCards.kt",
        "ComposerEffort.kt",
        "AskRestoreBar.kt",
        "CommandCandidates.kt",
        "PromptCandidates.kt",
        "SymbolLookup.kt",
        "MessageRenderer.kt",
        "ClaudeTranscriptView.kt",
    )

    /**
     * 允许的例外，一条一句理由。
     *
     * 例外要**少**：每多一条，就多一角可能留着旧语言的地方。
     */
    private val allowed = mapOf(
        "ClaudeTranscriptView.kt" to
            "JCEF / 资源缺失时的降级页：只在那种环境下出现、只建一次，而且它占了整块" +
            "视图（那时也没有别的界面可看）。真要修得给降级页也做重译，收益不值。",
    )

    /**
     * 「正在填控件」的样子：赋值给文字 / tooltip，或者就在控件构造函数的实参里。
     *
     * 往前看 160 个字符就够：仓库里的写法最多就是 `JLabel(` 换行 + 一段拼接。
     * 这条判据是**启发式**，不是证明 —— 它的价值在于把新代码的常见写法拦住，
     * 而不是拦住所有可能写法（那需要真解析 Kotlin）。
     */
    private val fillTail = Regex(
        "(toolTipText\\s*=|\\w+\\.text\\s*=|setText\\(|\\b(JB?Label|JButton|JCheckBox|JRadioButton)\\()" +
            "[^;]{0,120}$",
        RegexOption.DOT_MATCHES_ALL,
    )

    @Test
    fun `长命控件里的取词必须走带键的那几个`() {
        val offenders = mutableListOf<String>()

        for (name in longLived) {
            if (name in allowed) continue
            val file = File("src/main/kotlin/com/ccoder/ui/$name")
            if (!file.isFile) {
                offenders += "$name：找不到这个文件（改名了？名单要跟着改）"
                continue
            }
            val source = file.readText()
            var index = source.indexOf(MARK)
            while (index >= 0) {
                val before = source.substring(0, index).takeLast(160)
                if (fillTail.containsMatchIn(before)) {
                    val line = source.substring(0, index).count { it == '\n' } + 1
                    offenders += "$name:$line"
                }
                index = source.indexOf(MARK, index + 1)
            }
        }

        assertEquals(
            emptyList<String>(),
            offenders,
            "这些地方把翻好的字直接填进了控件 —— 换语言时它们不会变。" +
                "改用 localizedText / localizedTooltip / localizedLabel（见 LocalizedText.kt），" +
                "确实该例外的写进 allowed 并说明理由：$offenders",
        )
    }

    @Test
    fun `名单别写成空转 —— 每个文件都真的在扫`() {
        // 上面那条是"没问题就绿"，很容易在某次改名后变成空转：文件找不到、或者扫描
        // 标记改了。这条把两头都钉住。
        val missing = longLived.filter { !File("src/main/kotlin/com/ccoder/ui/$it").isFile }
        assertEquals(emptyList<String>(), missing, "名单里的文件不存在了：$missing")

        val total = longLived.sumOf { file ->
            File("src/main/kotlin/com/ccoder/ui/$file").readText().split(MARK).size - 1
        }
        assertTrue(total > 50, "整批只扫到 $total 处取词，像是扫描标记写错了")
    }

    private companion object {
        /** 取词调用的字面开头（与 `CcoderText.kt` 的 `fun text(` 逐字对应）。 */
        const val MARK = "CcoderText.text("
    }
}
