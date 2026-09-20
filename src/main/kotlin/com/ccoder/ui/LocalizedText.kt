package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * 「语言一变就重译」这一套：**把词表键记在控件上**，而不是只留一句翻好的字。
 *
 * ## 为什么不给每个组件写一个 `retranslate()`
 *
 * 那种写法要求每加一处文案都记得回来补一行 —— 而"漏一处"的表现是**界面里静静
 * 留着旧语言的一角**，正是这个仓库最不能忍的那类失真。这里换个做法：取词时顺手把
 * **键**记在控件的 client property 上（[localizedText] / [localizedTooltip]），
 * 语言一变就 [retranslateTree] 走一遍树、照着键重取一遍。
 * 于是"写文案的人"不需要知道语言这回事，**新代码也漏不了**。
 *
 * 自绘的控件（状态卡那种自己在 `paintComponent` 里画字的）套不进 JLabel /
 * AbstractButton：实现 [Relocalizable]，走树的同一遍会叫到它。
 *
 * ## 兜底在用例里，不在运行期
 *
 * 直接把 `CcoderText.text(...)` 塞进控件构造照样能编译 —— 那是这套机制唯一的漏法，
 * `LocalizedTextScanTest` 用源码扫描把它钉住（长命 UI 文件里出现即红）。
 *
 * 相关：`ClaudePanel.retranslate()`（面板那一遍的总入口）、
 * `UiLanguageSettings.addListener`（谁在什么时候喊这一遍）。
 */
internal interface Relocalizable {
    /** 照着当前语言把自己身上的文案重取一遍。 */
    fun retranslate()
}

/** 控件上记的取值：键 + 实参（`{0}` 那类）。 */
private class TextEntry(val key: String, val args: List<Any>)

private const val TEXT_KEY = "ccoder.localizedText"
private const val TOOLTIP_KEY = "ccoder.localizedTooltip"

/** 造一个"带键"的标签：语言一变它跟着变。 */
internal fun localizedLabel(key: String, vararg args: Any): JBLabel =
    JBLabel().localizedText(key, *args)

/**
 * 把键记在控件上，并立刻按当前语言填一次。
 *
 * 两个重载（`JLabel` / `AbstractButton`）是刻意的：**取词与回填必须配得上**，
 * 让"记在不能设字的控件上"在编译期就不可能。
 */
internal fun <T : JLabel> T.localizedText(key: String, vararg args: Any): T = apply {
    putClientProperty(TEXT_KEY, TextEntry(key, args.toList()))
    applyLocalizedText(this)
}

internal fun <T : AbstractButton> T.localizedText(key: String, vararg args: Any): T = apply {
    putClientProperty(TEXT_KEY, TextEntry(key, args.toList()))
    applyLocalizedText(this)
}

/**
 * 同上，挂在 tooltip 上。
 *
 * tooltip 与 `text` 不同：它是**弹出来那一刻才读**的，所以光记键不够，重译那一遍
 * 必须真的重挂一次（[applyLocalizedTooltip] 干的就是这件事）。
 */
internal fun <T : JComponent> T.localizedTooltip(key: String, vararg args: Any): T = apply {
    putClientProperty(TOOLTIP_KEY, TextEntry(key, args.toList()))
    applyLocalizedTooltip(this)
}

/**
 * 走一遍树：带键的控件按当前语言重取，实现 [Relocalizable] 的叫它自己重译。
 *
 * 树外的浮层（补全弹层、那一刻正开着的对话框）走不到 —— 它们本来就是每次打开/刷新
 * 重建的，不需要这一遍。唯一例外是"改语言那一刻正开着"的浮层：它会留旧语言到下次
 * 打开。写在这儿，免得以后有人把它当 bug 修。
 */
internal fun retranslateTree(root: Component) {
    if (root is JComponent) {
        applyLocalizedText(root)
        applyLocalizedTooltip(root)
        (root as? Relocalizable)?.retranslate()
    }
    if (root is Container) root.components.forEach(::retranslateTree)
}

private fun applyLocalizedText(c: JComponent) {
    val entry = c.getClientProperty(TEXT_KEY) as? TextEntry ?: return
    val value = CcoderText.text(entry.key, *entry.args.toTypedArray())
    when (c) {
        is JLabel -> c.text = value
        is AbstractButton -> c.text = value
        // 到不了这儿：`localizedText` 只有 JLabel / AbstractButton 两个重载
        else -> Unit
    }
}

private fun applyLocalizedTooltip(c: JComponent) {
    val entry = c.getClientProperty(TOOLTIP_KEY) as? TextEntry ?: return
    c.toolTipText = CcoderText.text(entry.key, *entry.args.toTypedArray())
}
