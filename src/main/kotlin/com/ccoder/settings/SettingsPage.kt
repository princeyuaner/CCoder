package com.ccoder.settings

import com.ccoder.ui.lineColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.Border

/**
 * 设置对话框里的一页（2026-09-15 起有四页：模型 / 通用 / 权限 / 环境）。
 *
 * ## 为什么是「页」而不是「一组控件」
 *
 * 四页之间**除 [labeledField] / [wrappedHint] 之外没有任何共享状态** ——
 * 每页只管自己那几个字段与它写哪个服务。所以切分按页走，新增第五页时只加一个文件。
 *
 * ## [component] 必须**记忆化**
 *
 * 不是优化：`TextFieldWithBrowseButton.addBrowseFolderListener` 是**累加**式装监听器的，
 * 同一个实例调两次 `component()`，点一下「浏览」会弹出两个文件框。
 * 页实现里存一个 `private var built: JComponent?` 就够了（见各页的写法）。
 *
 * ## [reload] 的时机只有一处
 *
 * 对话框每次打开都是新实例，所以外壳在**构造时**对每页调一次 [reload]，
 * 等价于"每次 show 之前"。**切页不调** —— 模态框开着的时候背后没人能改服务，
 * 切页时 reload 只会拿旧值盖掉页上正在编辑的中间状态（没打完的字、那个刻意
 * 不落库的空模型行）。
 */
internal interface SettingsPage {

    /** 页签上写什么。 */
    val title: String

    /** 这一页的组件。同一个实例上多次调用必须返回**同一个**组件。 */
    fun component(): JComponent

    /** 把服务里的值读进控件。在对话框构造时调一次。 */
    fun reload()

    /**
     * 对话框关掉时调一次。
     *
     * 要退订服务、要摘全局监听器的页在这里做。**必须做** ——
     * 页的组件活到对话框销毁为止，而订阅是累加的：不退订就是每开一次
     * 设置漏一个监听器，而它捕获着这一页。
     */
    fun dispose() {}
}

/**
 * 一条发丝分界线，跟随主题。
 *
 * ## 为什么补这三条（2026-09-16）
 *
 * 设计稿（`docs/design/settings-v2.html`，方案 C）里**三处都画了线**：
 * 页签栏右侧（`.tabs-v { border-right }`）、列表栏与表单栏之间（`.lcol { border-right }`）、
 * 页脚上方（`.dlg-ft { border-top }`）。实现时三条全漏了 —— 于是整张对话框同一片底色，
 * 左栏、列表、表单、页脚糊成一片。用户原话：「设置界面现在没有分隔条，很难看」。
 *
 * 颜色取面板那支 [lineColor]（`Component.borderColor`）：转写区里那些分界线用的就是它。
 * 设置框与面板是一个插件的两块界面，各用一支灰迟早看出一深一浅。
 */
internal fun hairlineLeft(): Border = JBUI.Borders.customLineLeft(lineColor())

/**
 * 页签栏右侧那条 —— 设计稿的 `.tabs-v { border-right }`。
 *
 * 画在**页签栏自己的右沿**：它是 `BorderLayout.WEST`，整列占满高度，线也就整条下来。
 * 页签栏的宽度是写死的 [TABS_WIDTH]，线吃掉的 1px 在栏内，页内容那侧不多不少。
 */
internal fun hairlineRight(): Border = JBUI.Borders.customLineRight(lineColor())

/**
 * 页脚上方那条 —— 设计稿的 `.dlg-ft { border-top }`。
 *
 * 底部从"浮在空白里的两颗字"变成一条真页脚：这句话与「关闭」是一条栏上的东西。
 */
internal fun hairlineTop(): Border = JBUI.Borders.customLineTop(lineColor())

/**
 * 列表栏与表单栏之间的那条 —— 设计稿的 `.lcol { border-right }`。
 *
 * 线画在**表单栏的左沿**而不是列表栏的右沿：`BorderLayout` 里表单栏在 `EAST`、
 * 列表栏在 `CENTER`，两者紧挨着，画哪边位置一样；而表单栏的宽度是各页写死的常量
 * （`*_FORM_WIDTH`），线跟着它走，不会被列表栏那侧忽多忽少的内容带偏。
 *
 * 四页共用（模型 / 预置 / MCP / hooks）—— 它们是同一个两栏骨架。
 */
internal fun formColumnBorder(inner: Border): Border =
    BorderFactory.createCompoundBorder(hairlineLeft(), inner)

/** 各页内容区的左右内边距。 */
internal const val PAGE_PADDING_H = 20

/** 各页内容的宽度。提示语折行按它算（见 [wrappedHint]）。 */
internal const val PAGE_CONTENT_WIDTH = PAGE_WIDTH - 2 * PAGE_PADDING_H

/**
 * 一页的外壳：左右各留 [PAGE_PADDING_H]，里面那条列贴着顶。
 *
 * 列挂 `NORTH` 而不是 `CENTER`：`CENTER` 会把它拉到页底，字段之间那些
 * `createVerticalGlue` 就会把东西撑开。
 */
internal fun settingsPageBody(column: JComponent): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(14, PAGE_PADDING_H)
    add(column, BorderLayout.NORTH)
}

/** 一条纵向的列，页里的字段都往里塞。 */
internal fun settingsColumn(): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
}

/**
 * 一个"动作文字"：灰字、可点。模型页那几个（`＋ 添加配置` / `＋ 添加模型`）就是这个形状。
 *
 * 没用平台按钮是因为这儿的动作都很轻（加一行、删选中），一颗带边框的按钮太吵；
 * 也与模型页那边保持一致。
 */
internal fun actionLabel(text: String, onClick: () -> Unit): JBLabel =
    JBLabel(text).apply {
        foreground = UIUtil.getInactiveTextColor()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
    }

/**
 * 一个字段：上面标签、下面输入框。
 *
 * ## 这个布局是**接口**，不是样式
 *
 * `ModelProfilesDialogSaveTest` 的一批辅助函数（`inputOf` / `fieldOf` / `comboOf`）
 * 靠「标签的父容器里第一个不是标签的兄弟」来找输入控件。四页**必须**都走这个函数 ——
 * 换成 `FormBuilder.addLabeledComponent` 那种"标签与输入同一行"的摆法，
 * 那些辅助函数会直接找不到控件。
 *
 * ## 三处 `alignmentX = LEFT_ALIGNMENT` 都是必需的
 *
 * 不是装饰：BoxLayout 在交叉轴上按 alignmentX 摆放子件，而 JComponent 的默认值是
 * **居中**（0.5）。不压到 0，标签会各自居中、彼此错开 —— 实测"名称"在 x=31、
 * "认证方式"在 x=202，而输入框一律 x=0，整张表单看起来是歪的。
 */
internal fun labeledField(label: String, input: JComponent): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.emptyBottom(10)
    alignmentX = Component.LEFT_ALIGNMENT
    add(JBLabel(label).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.emptyBottom(4)
        alignmentX = Component.LEFT_ALIGNMENT
    })
    input.alignmentX = Component.LEFT_ALIGNMENT
    add(input)
}

/**
 * 一句灰色的说明，**自己折行**。
 *
 * 为什么不用 `JBLabel`：它不折行，超出的部分直接裁掉 —— 而这里几句话（比如思考深度
 * 那句 46 个字）在 448px 宽的栏里必然超。今天 IDE 那页用的就是 `JBLabel`，
 * 长的那两句是不是被裁了没人量过（那一页没有任何测试）。
 *
 * 宽度必须由调用方喂进来：折行后的高度**只能自己量**，而量高度得先知道宽度
 * （同 `conflictWarning` 的写法）。封顶必须**等于**量出来的高度，给 `Int.MAX_VALUE`
 * 的话这条会成为页里的弹簧，把下面的字段顶开。
 */
internal fun wrappedHint(text: String, widthPx: Int): JComponent = JBTextArea(text).apply {
    isEditable = false
    isFocusable = false
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    foreground = UIUtil.getInactiveTextColor()
    // **下面那 12px 是必需的**：这句说明跟在某个字段后面，而它的下面紧挨着
    // 下一个字段的标签。不留间距的话它离下面那个标签比离自己说明的字段还近 ——
    // 读起来就像在说下面那一项（2026-09-15 出图时看出来的）
    border = JBUI.Borders.emptyBottom(12)
    // JTextArea 默认是等宽体，跟同栏的标签不像一家的
    font = UIUtil.getLabelFont()
    alignmentX = Component.LEFT_ALIGNMENT
    setSize(widthPx, Int.MAX_VALUE)
    maximumSize = Dimension(widthPx, preferredSize.height)
}
