package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.ui.components.JBLabel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.util.Locale
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 「切成英文之后，控件树里不许再剩中文」—— 热切换那条路的结构性护栏。
 *
 * ## 它守的是什么
 *
 * 热切换的做法是"把键记在控件上，语言一变走树重取一遍"（见 `LocalizedText.kt`）。
 * 唯一的漏法是**有人把翻好的字直接塞进控件**（`JBLabel(CcoderText.text("k"))`）——
 * 那种控件走树时看不见（最后一条用例把这个边界钉住）。这条文件把每个长命组件在
 * 中文下建出来、切成英文、走一遍树，然后要求树里**一个汉字都没有**。
 *
 * ## 判据为什么成立
 *
 * 夹具数据全是 ASCII（工具自己写的数据自己保证），所以剩下的任何汉字都只可能来自
 * 产品文案 —— 而英文词表里一个汉字都没有（`TextCatalogTest` 钉着）。**别把中文
 * 夹具写进这一条**，否则判据就废了。
 *
 * 覆盖不到的两处，各自有理由：转写区是 JCEF 页面（语言由
 * `ClaudeTranscriptView.setLocale` 推，web 侧自己有"换语言不重载页面"的用例）；
 * 转写视图的降级页只在 JCEF/资源缺失时出现。面板本体（`ClaudePanel`）在纯 JVM 里
 * 建不出来（要平台服务与 JCEF），它的那些字走的是"刷新家族"（见
 * `ClaudePanel.retranslate`），在真机上由 `-PtestLang=en` 那遍探针看着。
 */
class LocalizedTextTest {

    @AfterEach
    fun 收尾() {
        // CcoderText 是全局的：不清掉，"这个用例切过英文"会漏给下一个
        CcoderText.setOverride(null)
    }

    // ---- 机制本身 ----

    @Test
    fun `切成英文 —— 带键的标签跟着变`() = onEdt {
        val label = localizedLabel("settings.language.label")
        assertEquals("界面语言", label.text)

        CcoderText.setOverride(Locale.ENGLISH)
        retranslateTree(label)

        assertEquals("Interface language", label.text)
    }

    @Test
    fun `实参在重取时也重算`() = onEdt {
        val label = localizedLabel("session.tabs.limit", 5)
        val zh = label.text
        assertTrue(zh.contains("5"), "中文那份就带着实参：$zh")

        CcoderText.setOverride(Locale.ENGLISH)
        retranslateTree(label)

        assertNotEquals(zh, label.text)
        assertTrue(label.text.contains("5"), "实参要跟着重取：${label.text}")
    }

    @Test
    fun `tooltip 也重译 —— 它跟 text 是两条独立的线`() = onEdt {
        val gear = settingsGearButton {}
        val zh = gear.toolTipText
        assertTrue(zh.isNotEmpty())

        CcoderText.setOverride(Locale.ENGLISH)
        retranslateTree(gear)

        assertNotEquals(zh, gear.toolTipText, "tooltip 是弹出来那一刻才读的，走树那一遍必须重挂")
    }

    @Test
    fun `没带键的控件不会自己变 —— 这条钉的是机制边界`() = onEdt {
        // 直接把翻好的字塞进控件，走树时看不见它。所以长命控件里一律要用带键的那几个
        // （`localizedText` / `localizedTooltip` / `localizedLabel`），源码扫描另有
        // 一条用例守门（LocalizedTextScanTest）
        val raw = JBLabel(CcoderText.text("settings.language.label"))

        CcoderText.setOverride(Locale.ENGLISH)
        retranslateTree(raw)

        assertEquals("界面语言", raw.text, "没带键的控件本来就该不变；变了说明机制不止一种")
    }

    // ---- 每个长命组件：切成英文后不许剩中文 ----

    @Test
    fun `恢复条`() = 切成英文后不许剩中文("AskRestoreBar") { AskRestoreBar {} }

    @Test
    fun `齿轮`() = 切成英文后不许剩中文("齿轮按钮") { settingsGearButton {} }

    @Test
    fun `排队条`() = 切成英文后不许剩中文("QueueStrip") { QueueStrip {} }

    @Test
    fun `附件带 —— 连拒收理由一起`() = 切成英文后不许剩中文("AttachmentStrip") {
        AttachmentStrip {}.apply {
            // 理由收的是**算法**（见 AttachmentStrip.reject）：切成英文后它要重算
            reject { CcoderText.text("chat.image.tooLarge") }
        }
    }

    /**
     * 中文下建出来 → 确认真有汉字（否则断言恒真）→ 切成英文 → 走一遍树 → 一个汉字都不许剩。
     *
     * 每条用例一个组件：组件少而长命，逐个建得动，比"造个假面板"实在。
     */
    private fun 切成英文后不许剩中文(name: String, build: () -> Component) = onEdt {
        val root = build()
        val before = textsIn(root)
        assertTrue(
            before.any { it.hasCjk() },
            "$name：中文下都找不到汉字，这条用例失去意义（夹具写成英文了？）：$before",
        )

        CcoderText.setOverride(Locale.ENGLISH)
        retranslateTree(root)

        val left = textsIn(root).filter { it.hasCjk() }
        assertTrue(left.isEmpty(), "$name：切成英文后还留着中文：$left")
    }

    /** 树里所有"用户看得见的字"：标签文字的、按钮文字的、tooltip。 */
    private fun textsIn(root: Component): List<String> {
        val out = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is JComponent) {
                (c as? JLabel)?.text?.let { out += it }
                (c as? AbstractButton)?.text?.let { out += it }
                c.toolTipText?.let { out += it }
            }
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return out.filter { it.isNotEmpty() }
    }

    private fun String.hasCjk(): Boolean = any { it.code in 0x4E00..0x9FFF }
}

/**
 * 在 EDT 上跑一段，并拆掉 `invokeAndWait` 那层包装 —— 否则断言失败只剩一句
 * InvocationTargetException（同 `AskSequenceTest` / `ComposerInputTest` 的写法）。
 */
private fun onEdt(block: () -> Unit) {
    var thrown: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            block()
        } catch (t: Throwable) {
            thrown = t
        }
    }
    thrown?.let { throw it }
}
