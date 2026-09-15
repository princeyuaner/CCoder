package com.ccoder.ui

import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JTextArea

/**
 * 在卡片树里按文本找组件。
 *
 * 2026-09-15 之前，卡片上的字全是 `JLabel`，三个地方（卡片测试、序列测试、
 * 渲染探针）各写了一份 `labelsIn` 就够了。那天题干与选项文本换成了会换行的
 * `WrapText`（`JTextArea` 的子类）—— 它们是**文本组件但不是标签**，
 * 于是那三份查找同时失效。
 *
 * 写成一份：以后再换渲染方式，只有这里要动。
 *
 * 注意 `JBTextField` 不在其列（它继承的是 `JTextField`）——「其它…」那个
 * 输入框因此不会被当成"一段文字"混进来。
 */
internal fun textComponentsIn(root: Container): List<Component> {
    val out = mutableListOf<Component>()
    fun walk(c: Container) {
        for (child in c.components) {
            if (child is JLabel || child is JTextArea) out += child
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out
}

/** 组件上显示的文字；不是文本组件就给 null。 */
internal fun textOf(c: Component): String? = when (c) {
    is JLabel -> c.text
    is JTextArea -> c.text
    else -> null
}

/** 树里所有文本组件的文字（按遍历顺序）。 */
internal fun textsIn(root: Container): List<String> =
    textComponentsIn(root).mapNotNull { textOf(it) }

/**
 * 按"里面含着这段文字"找一个组件。
 *
 * 找不到时把树里现有的文字全列出来 —— 踩过这个坑：断言只报一句"找不到"，
 * 而真正的原因是某个标签的文案变了，清单能一眼看出来。
 */
internal fun componentWithText(root: Container, text: String): Component =
    textComponentsIn(root).firstOrNull { textOf(it)?.contains(text) == true }
        ?: error("找不到「$text」，树里有：${textsIn(root)}")
