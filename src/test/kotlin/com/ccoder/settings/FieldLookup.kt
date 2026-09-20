package com.ccoder.settings

import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.text.JTextComponent

/**
 * 按「字段标签」找控件 —— 设置页用例共用的那**一个**定位器。
 *
 * ## 为什么不再按"兄弟顺序"找（2026-09-20）
 *
 * 从 2026-09-15 起，全套用例靠一句话找控件：**标签的父容器里第一个不是标签的
 * JComponent 兄弟就是输入控件**。`labeledField`（`SettingsPage.kt`）正是这个契约
 * 的生产者 —— 它把标签与输入摆成"上标签、下输入"两层，于是"父容器的另一个孩子"
 * 恰好就是输入控件。hooks / MCP / 预置 / 设置框 / 探针里那**七份私有副本**
 * 抄的都是这一句。
 *
 * 设置页改成卡片式之后，一行变成**标签（说明挂在它下面）在左、控件在右** ——
 * 标签与控件不再是同一个父容器下的两个孩子。旧写法此时会**把说明那块
 * `JTextArea` 当成输入控件**（它是标签后面第一个非标签兄弟），而且不报错：
 * 改的是说明的文本、断言却从服务里读回旧值，红的地方离病因很远。
 *
 * ## 新的三条规则
 *
 * 1. **先上溯到那一行**：从标签往上走，找第一个"子树里有输入件、且自己不是
 *    输入件"的祖先；**上溯到调用方给的那个 root 就停**（再往上会摸到别的字段）。
 * 2. **在行里按类型找第一个输入件**：[JComboBox] / [JCheckBox] / [JTable] /
 *    [JScrollPane]（命中即停 —— 多行框的壳就是它，`commandArea` 要的就是这层）/
 *    **可编辑**的 [JTextComponent]。
 * 3. **跳过说明**：`wrappedHint` 产的是 `isEditable=false && isFocusable=false`
 *    的 `JTextArea`，按这两条排除即可 —— 不必给生产代码加标记。
 *
 * 旧布局是它的特例：标签的父容器本来就是那个"最近的、含输入件的祖先"，
 * 第 1 条走出的还是同一个节点 —— 所以这次改动对旧页面是**严格等价替换**。
 * 等价性由 [FieldLookupTest] 的两条合成夹具钉着（一条旧布局、一条新行语言）。
 */

/** 按文本找标签，**完全相等**；深度优先。 */
internal fun findLabel(root: Container, text: String): JLabel? {
    for (child in root.components) {
        if (child is JLabel && child.text == text) return child
        if (child is Container) findLabel(child, text)?.let { return it }
    }
    return null
}

/**
 * 规则 2 的"输入件"。
 *
 * `JScrollPane` 与 `JTable` 命中即停、不下钻：hooks 的命令框、模型 ID 那一框、
 * 环境页两张表都指望着"拿到的就是那层壳"。
 */
private fun isInput(c: Component): Boolean = when (c) {
    is JComboBox<*>, is JCheckBox, is JTable, is JScrollPane -> true
    // 说明也是 JTextComponent（JTextArea），靠 isEditable 把它与真输入分开
    is JTextComponent -> c.isEditable
    else -> false
}

private fun firstInputIn(c: Container): JComponent? {
    for (child in c.components) {
        if (child is JComponent && isInput(child)) return child
        if (child is Container) firstInputIn(child)?.let { return it }
    }
    return null
}

/**
 * 标签所在的那一行 —— 规则 1。
 *
 * 拿不到时退回标签的父容器（老写法就是拿它去找控件），这样**纯标签**的调用点
 * 也不会因为上溯没结果而炸得莫名其妙。
 */
internal fun rowOf(root: Container, label: String): JComponent {
    val lab = findLabel(root, label) ?: error("找不到字段标签「$label」")
    var c: Container? = lab.parent
    while (c != null && c !== root) {
        if (c is JComponent && firstInputIn(c) != null) return c
        c = c.parent
    }
    return lab.parent as? JComponent ?: error("「$label」不在容器里")
}

/** 字段标签那一行里的输入控件 —— 规则 1 + 规则 2。 */
internal fun inputOf(root: Container, label: String): JComponent {
    val row = rowOf(root, label)
    return firstInputIn(row) ?: error("「$label」那一行里没有输入控件")
}
