package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Claude 提问的卡片（方案 A · 堆叠块）。
 *
 * ## 为什么不是 [PermissionCard]
 *
 * 那个卡片给的是「拒绝 / 允许」，而 `AskUserQuestion` 的语义是**选哪一个** ——
 * 两个按钮根本表达不了。所以它不是换个皮，是另一条路：这里每个选项都是
 * 真的能点的按钮，点完 [onSubmit] 把 [Picked] 送出去。
 *
 * ## 选项为什么全都摊开
 *
 * 说明文字始终可见。选 A 而不是 C（芯片）的理由就在这儿：选项之间的差别
 * 往往就写在那行小字里，收起来等于没写。代价是 4 道题时卡片会很高 ——
 * 那时用户往下滚，总比看不见差别强。
 *
 * ## 提交门控
 *
 * 没答完不给提交。少答一题就提交，等于替用户答了，而那个答案会被当成
 * "用户的选择"喂回模型。
 */
internal class AskQuestionCard(
    request: AskRequest,
    private val onSubmit: (Picked) -> Unit,
    private val onDeny: () -> Unit,
) : JPanel(BorderLayout()) {

    /** 与 Swing 无关的作答状态。测试直接驱动它，再看界面跟没跟上。 */
    internal val state = AskState(request)

    internal val submitButton = JButton("提交")
    internal val denyButton = JButton("拒绝")

    /** 「其它…」的输入框外壳，按题目文本索引。输入框本身在 client property 上。 */
    private val customWrappers = mutableMapOf<String, JComponent>()

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT, 1),
            JBUI.Borders.empty(9, 11),
        )
        background = CARD_BG
        isOpaque = true

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            state.states.forEachIndexed { i, qs ->
                add(buildQuestion(i, qs))
            }
            add(vStrut(9))
            add(buildActions())
        }

        add(body, BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)

        refreshSubmit()

        // 与 PermissionCard 一致的规则②：焦点默认落在"拒绝"，且不绑键盘快捷键。
        // 递交答案是个不可逆的动作（模型会照着它往下走），不该被误触
        SwingUtilities.invokeLater { denyButton.requestFocusInWindow() }
    }

    /**
     * 选项变了就调它 —— 提交键可不可点**只**由这里决定。
     *
     * 抽成方法而不是散在监听器里：测试要能在改完状态后走**同一条**
     * 真实路径，而不是自己重新实现一遍"什么时候该可点"。
     */
    internal fun refreshSubmit() {
        submitButton.isEnabled = state.complete
        submitButton.toolTipText = if (state.complete) null else "还有没答的题"
    }

    // ---- 组装 ----

    private fun buildQuestion(index: Int, qs: QuestionState): JComponent {
        val base = UIUtil.getLabelFont()
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = if (index == 0) JBUI.Borders.empty() else JBUI.Borders.emptyTop(12)
        }.leftAligned()

        if (qs.question.header.isNotBlank()) {
            header.add(headerChip(qs.question.header))
            header.add(vStrut(4))
        }

        header.add(JBLabel(qs.question.question).apply {
            font = base.deriveFont(Font.BOLD)
            alignmentX = LEFT_ALIGNMENT
        })
        header.add(vStrut(7))

        qs.question.options.forEach { opt ->
            header.add(buildOptionRow(qs, opt))
            header.add(vStrut(5))
        }

        // 「其它…」由界面补上：SDK 在 options 的注释里明说这项不该出现在
        // 入参里、"will be provided automatically"（sdk-tools.d.ts:1070）。
        // 它和普通选项走同一套渲染与互斥逻辑，特判它只会多出一堆分支
        header.add(
            buildOptionRow(qs, AskOption(OTHER_LABEL, "以上都不是，我自己说。", null))
        )
        header.add(vStrut(5))

        val wrapper = buildCustomField(qs)
        customWrappers[qs.question.question] = wrapper
        header.add(wrapper)

        return header
    }

    private fun headerChip(text: String) = JLabel(text).apply {
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2f)
        foreground = ACCENT
        border = JBUI.Borders.empty(1, 6)
    }.leftAligned()

    /**
     * 一个选项。
     *
     * 点整行都能选，不只是文字那一小块 —— 一行里是上下两个标签，
     * 点到说明上没反应会显得很钝。
     */
    private fun buildOptionRow(qs: QuestionState, opt: AskOption): JComponent {
        val base = UIUtil.getLabelFont()

        // 勾单独一格，**宽度写死**。
        //
        // 原来是把它拼进 label 的文字、选中时再把 label 改成粗体 —— 两个动作
        // 都会让标签变宽，而布局拿的还是旧宽度，于是文字被截成
        // 「✓ 审查当前 …」。根子在更前面：点一下不该让整张卡片重排。
        // 把变化的量全挤进这一格里，标签的文字和字体就再也不动了。
        val check = JLabel().apply {
            font = base
            val fm = getFontMetrics(base)
            preferredSize = Dimension(fm.stringWidth("✓"), fm.height)
        }

        val label = JLabel(opt.label).apply { font = base }
        val desc = JLabel(opt.description).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = base.deriveFont(base.size2D - 1f)
            isVisible = opt.description.isNotBlank()
        }

        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }.leftAligned()

        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(label)
            if (desc.isVisible) {
                add(vStrut(2))
                add(desc)
            }
        }
        row.add(check, BorderLayout.WEST)
        row.add(box, BorderLayout.CENTER)

        fun sync() {
            val on = qs.isSelected(opt.label)
            // 只改颜色和文字的**内容**，不改任何尺寸
            check.text = if (on) "✓" else ""
            check.foreground = ACCENT
            label.foreground = if (on) ACCENT else UIUtil.getLabelForeground()
            row.border = BorderFactory.createCompoundBorder(
                RoundedLineBorder({ if (on) ACCENT else lineColor() }, JBUI.scale(7)),
                JBUI.Borders.empty(7, 9),
            )
            revalidate()
            repaint()
        }
        sync()

        val handler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                qs.toggle(opt.label)
                sync()
                syncCustomField(qs)
                refreshSubmit()
            }

            override fun mouseEntered(e: MouseEvent) {
                if (!qs.isSelected(opt.label)) {
                    row.border = BorderFactory.createCompoundBorder(
                        RoundedLineBorder({ lineColor() }, JBUI.scale(7)),
                        JBUI.Borders.empty(7, 9),
                    )
                }
            }
        }
        listOf<JComponent>(row, box, label, desc).forEach { it.addMouseListener(handler) }

        return row
    }

    /**
     * 「其它…」的输入框。
     *
     * 只在选中「其它…」时才占位置 —— 平时 `isVisible = false`，
     * `BoxLayout` 就不会给它算高度。
     */
    private fun buildCustomField(qs: QuestionState): JComponent {
        val field = JBTextField().apply {
            emptyText.text = "说说你想要什么"
            addKeyListener(
                object : KeyAdapter() {
                    override fun keyReleased(e: KeyEvent) {
                        qs.custom = text
                        refreshSubmit()
                    }
                }
            )
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        qs.custom = text
                        refreshSubmit()
                    }
                }
            )
        }
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 9, 4, 9)
            add(field, BorderLayout.CENTER)
            isVisible = false
        }.leftAligned()
        wrapper.putClientProperty(FIELD_KEY, field)
        return wrapper
    }

    /** 「其它…」的输入框外壳，按题目文本取。测试用它断言显隐。 */
    internal fun customWrapperFor(question: String): JComponent? = customWrappers[question]

    /** 「其它…」选中/取消时切换输入框的可见性。 */
    internal fun syncCustomField(qs: QuestionState) {
        val wrapper = customWrappers[qs.question.question] ?: return
        val on = qs.isSelected(OTHER_LABEL)
        if (wrapper.isVisible == on) return
        wrapper.isVisible = on
        wrapper.parent?.revalidate()
        if (on) {
            (wrapper.getClientProperty(FIELD_KEY) as? JBTextField)?.let {
                it.text = qs.custom
                it.requestFocusInWindow()
            }
        }
    }

    private fun buildActions(): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false

        // 不设 mnemonic —— 助记符就是键盘捷径，而这是不可逆动作（同 PermissionCard 规则②）
        denyButton.addActionListener { onDeny() }
        submitButton.addActionListener { onSubmit(state.picked()) }

        add(denyButton)
        add(submitButton)
    }.leftAligned()

    private companion object {
        val ACCENT = JBColor(0xFFA000, 0xFFB74D)
        val CARD_BG = JBColor(0xFFF8E1, 0x3E2C1C)

        /** 输入框装在 wrapper 里，靠它把 wrapper 和 field 关联起来。 */
        const val FIELD_KEY = "ccoder.ask.customField"
    }
}

/**
 * 塞进竖直 `BoxLayout` 之前先左对齐。
 *
 * `BoxLayout` **不是**各摆各的：它取所有子项里 `alignmentX × 宽度` 的最大值
 * 当公共对齐基准，再按各自的 alignmentX 摆放。竖直 Filler 和整宽的选项行
 * 默认是 `CENTER`(0.5)，宽度又接近整行 —— 基准于是被推到中间，
 * 左侧那些窄标签（芯片、题目）全被带着右移。
 *
 * 实测：芯片和题目都落在 x=148，而容器只有 406 宽，看起来就是"全都居中了"。
 *
 * **Filler 也要设** —— 它正是把基准推歪的那个。
 */
private fun <T : JComponent> T.leftAligned(): T =
    apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT }

/** 竖直间距。带左对齐 —— 见 [leftAligned]。 */
private fun vStrut(height: Int): JComponent =
    (Box.createVerticalStrut(JBUI.scale(height)) as JComponent).leftAligned()
