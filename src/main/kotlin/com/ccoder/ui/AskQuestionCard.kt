package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
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
 * Claude 提问的卡片（方案 A · 堆叠块）—— 一次只画**一道**题。
 *
 * ## 为什么变成"一题一张卡"
 *
 * 一次 `AskUserQuestion` 可以带 1–4 道题。原来它们全堆在同一张卡片里（那时
 * 这张卡在一次交互里只出现一次），用户看不见"还有几道、答到哪"。改成弹窗之后
 * 由 [AskSequence] 一题弹一个框，所以这张卡只需要画当前那道题，进度交给顶栏那行。
 *
 * 推进的判断（第几题、能不能往下走、回退时答案还在不在）全在 [AskFlow] 里，
 * 与 Swing 无关、可单独测；这张卡只负责把它画出来。
 *
 * ## 为什么不是 [PermissionCard]
 *
 * 那个卡片给的是「拒绝 / 允许」，而 `AskUserQuestion` 的语义是**选哪一个** ——
 * 两个按钮根本表达不了。所以它不是换个皮，是另一条路：这里每个选项都是
 * 真的能点的按钮。
 *
 * ## 选项为什么全都摊开
 *
 * 说明文字始终可见。选 A 而不是 C（芯片）的理由就在这儿：选项之间的差别
 * 往往就写在那行小字里，收起来等于没写。代价是题多时卡片会很高 ——
 * 那时用户往下滚，总比看不见差别强。
 *
 * ## 提交门控
 *
 * 「下一题 / 提交」（同一颗按钮，两副文案）可不可点只由 [AskFlow.canAdvance] 决定：
 * 当前题答了没。少答一题就提交，等于替用户答了，而那个答案会被当成
 * "用户的选择"喂回模型 —— 所以门控之外，监听器里还留了第二道闸。
 */
internal class AskQuestionCard(
    internal val flow: AskFlow,
    private val onSubmit: (Picked) -> Unit,
    private val onAdvance: () -> Unit,
    private val onBack: () -> Unit,
    private val onDeny: () -> Unit,
    private val onMinimize: () -> Unit,
) : JPanel(BorderLayout()) {

    /** 「下一题」或者「提交」，看这是不是最后一题。 */
    internal val submitButton = JButton(if (flow.isLast) SUBMIT_LABEL else NEXT_LABEL)

    internal val denyButton = JButton(DENY_LABEL)

    /**
     * 「最小化」：先把这个框收起来，去代码里看一眼再回来答。
     *
     * 它是第三条路 —— 与「拒绝」和右上角那个 X 都不同：请求仍留在队列里等着
     * （[ClaudePanel] 那边计数不减、`cancelAll` 仍能作废它），Claude 也仍在等。
     * 回来的路在状态栏（见 AskSequence 的挂起状态）。
     */
    internal val minimizeButton = JButton(MINIMIZE_LABEL).apply {
        toolTipText = MINIMIZE_TOOLTIP
    }

    /** 只有第二题起才有它 —— 第一题上没有"上一题"可回。 */
    internal val backButton = JButton(BACK_LABEL)

    /** 「其它…」的输入框外壳。输入框本身在 client property 上。 */
    private val customWrappers = mutableMapOf<String, JComponent>()

    /**
     * 当前题每个选项行的重画钩子（[buildOptionRow] 建行时塞进来）。
     *
     * 单选换选时改的是**状态集合**，而画出来的是**每一行自己**（勾、文字色、边框
     * 都在那一行里）：不把别的行也叫一遍，画面上就是两个都亮着 ——
     * 2026-09-20 用户报的"选了 A 再选 B，A 的选择没有取消"就是这个。
     * 状态一直是对的（`QuestionState.toggle` 单选会清空），只有画面没跟上。
     */
    private val optionSyncs = mutableListOf<() -> Unit>()

    /** 每个选项的勾标签，按 label 存 —— 给测试断言"画面上真的让位了"。 */
    private val optionTicks = mutableMapOf<String, JLabel>()

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT, 1),
            JBUI.Borders.empty(9, 11),
        )
        // **不铺底**：让对话框自己的底色透上来（2026-09-15 用户报"弹框有黄色
        // 背景，好丑"）。原先铺的是一层琥珀。"要你处理"的信号留给那圈琥珀描边
        // 与选项高亮。
        //
        // 用 isOpaque=false 而不是"铺成面板色"：真实 IDE 里对话框的底色未必等于
        // UIUtil.getPanelBackground()（StatusCardView 那边实测踩过这个坑），
        // 铺错了就会在框里显出一块颜色不一样的方块。
        isOpaque = false

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            // 一题的问卷不写"第 1 / 1 题" —— 那是噪音
            if (flow.count > 1) {
                add(progressRow())
                add(vStrut(7))
            }
            add(buildQuestion(flow.current))
            add(vStrut(9))
            add(buildActions())
        }

        add(body, BorderLayout.CENTER)

        refreshSubmit()

        // 与 PermissionCard 一致的规则②：焦点默认落在"拒绝"，且不绑键盘快捷键。
        // 递交答案是个不可逆的动作（模型会照着它往下走），不该被误触
        SwingUtilities.invokeLater { denyButton.requestFocusInWindow() }
    }

    /**
     * 「下一题 / 提交」可不可点**只**由这里决定。
     *
     * 抽成方法而不是散在监听器里：测试要能在改完状态后走**同一条**
     * 真实路径，而不是自己重新实现一遍"什么时候该可点"。
     */
    internal fun refreshSubmit() {
        submitButton.isEnabled = flow.canAdvance
        submitButton.toolTipText = if (flow.canAdvance) null else CcoderText.text("ask.submitTip")
    }

    // ---- 组装 ----

    /** 进度行：`第 2 / 3 题`。只有多题时才画（见 [init]）。 */
    private fun progressRow(): JComponent = JBLabel(
        CcoderText.text("ask.progress", flow.index + 1, flow.count)
    ).apply {
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2f)
        foreground = UIUtil.getInactiveTextColor()
    }.leftAligned()

    private fun buildQuestion(qs: QuestionState): JComponent {
        // 一题一张卡，这里本来就只会来一遍；清一次是把"将来重画同一张卡"也堵住
        optionSyncs.clear()
        optionTicks.clear()

        val base = UIUtil.getLabelFont()
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }.leftAligned()

        // 芯片行：短标题 +「可多选」提示（多选题才有）。
        //
        // 那条提示是 2026-09-20 加的：在这之前，界面**一个字都没提**这题能不能多选
        // （`multiSelect` 只有解析层在用），用户只能靠"点第二个时第一个没消失"来猜 ——
        // 而单选让位那个 bug 恰好让两者在画面上长得一模一样。
        chipRow(qs)?.let {
            header.add(it)
            header.add(vStrut(4))
        }

        header.add(WrapText(qs.question.question) { QUESTION_TEXT_WIDTH }.apply {
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
            buildOptionRow(qs, AskOption(OTHER_LABEL, CcoderText.text("ask.other"), null))
        )
        header.add(vStrut(5))

        val wrapper = buildCustomField(qs)
        customWrappers[qs.question.question] = wrapper
        header.add(wrapper)

        // 重开一题时要恢复现场：选项的勾由 buildOptionRow 的 sync() 自己读状态，
        // 但输入框不会 —— 它建出来永远是隐藏的空框。今天卡片是一次性的，
        // 所以这条路径以前不存在；现在「上一题」会重画，就得补这一下。
        syncCustomField(qs)

        return header
    }

    /**
     * 芯片行：短标题 +「可多选」提示。两个都没有就返回 null —— 空行会白占一段高度。
     *
     * 用横向 `BoxLayout` 而不是 `FlowLayout`：后者会给第一个子项也留 hgap，
     * 芯片就会比下面那些左对齐的文本右移一格（5.1 的边距差肉眼看得出来）。
     */
    private fun chipRow(qs: QuestionState): JComponent? {
        val hasHeader = qs.question.header.isNotBlank()
        val multi = qs.question.multiSelect
        if (!hasHeader && !multi) return null

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            if (hasHeader) {
                add(headerChip(qs.question.header))
                if (multi) add(Box.createHorizontalStrut(6))
            }
            if (multi) add(hintChip())
            add(Box.createHorizontalGlue())
        }.leftAligned()
    }

    private fun headerChip(text: String) = chip(text, ACCENT)

    /** 「可多选」：与标题同形状，颜色压到次要一层 —— 它是说明，不是标题。 */
    private fun hintChip() = chip(CcoderText.text("ask.multiHint"), UIUtil.getInactiveTextColor())

    private fun chip(text: String, color: Color) = JLabel(text).apply {
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2f)
        foreground = color
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

        val label = WrapText(opt.label) { OPTION_TEXT_WIDTH }.apply { font = base }
        val desc = WrapText(opt.description) { OPTION_TEXT_WIDTH }.apply {
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
        optionTicks[opt.label] = check
        optionSyncs += { sync() }
        sync()

        val handler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                qs.toggle(opt.label)
                // 刷**所有**行，不只是被点的这一行：单选换选时前一行得当场暗下去
                refreshOptions()
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
            emptyText.text = CcoderText.text("ask.describe")
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

    /**
     * 当前题所有选项行按状态重画一遍。
     *
     * 单选换选改的是一个集合，而画出来的是每一行自己 —— 只刷被点的那一行，
     * 前一行会留着勾与琥珀色（见 [optionSyncs] 的注释）。
     */
    private fun refreshOptions() = optionSyncs.forEach { it() }

    /**
     * 某个选项**画面上**的勾（`"✓"` 或 `""`）。
     *
     * 测试专用的观察口：`QuestionState` 一直是对的，出问题的是画面，
     * 所以断言必须落在画面上（`单选下再点一个，前一个让位` 那条只测状态，
     * 它绿着的时候用户看见的是两个勾）。
     */
    internal fun tickFor(label: String): String? = optionTicks[label]?.text

    /** 「其它…」的输入框外壳，按题目文本取。测试用它断言显隐。 */
    internal fun customWrapperFor(question: String): JComponent? = customWrappers[question]

    /** 「其它…」输入框里的文字。重开一题时靠它断言现场真的恢复了。 */
    internal fun customTextFor(question: String): String? =
        (customWrappers[question]?.getClientProperty(FIELD_KEY) as? JBTextField)?.text

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
        resyncWindowSize()
    }

    /**
     * 卡片长高（或缩矮）之后，把窗口也调一次大小。
     *
     * 对话框是在弹出来那一刻 `pack()` 过的，尺寸就此定住 —— 里面冒出一个输入框，
     * 窗口不会自己变大，多出来的那截会被压在窗沿下面（探针里第一版就是这样：
     * 一路长到把底部那颗按钮挤没了）。探测窗口用的就是这套布局。
     *
     * 没上屏时（测试、探针）`getWindowAncestor` 是 null，这里什么都不做 ——
     * 那边是自己手动跑布局的，不需要窗口。
     */
    private fun resyncWindowSize() {
        val window = SwingUtilities.getWindowAncestor(this) as? java.awt.Dialog ?: return
        window.pack()
    }

    /**
     * 宽度写死、**高度随内容** —— 同 [PermissionCard.getPreferredSize]。
     *
     * 高度这条在**这张**卡片上是必须的：选中「其它…」会长出一个输入框，
     * 冻住高度等于把它压在窗沿下面。
     */
    override fun getPreferredSize(): Dimension {
        val natural = super.getPreferredSize()
        return Dimension(CARD_WIDTH, natural.height)
    }

    /**
     * 宽度也写死 —— **这一条才是"弹框被撑宽"的真正闸门**。
     *
     * 只钉 `getPreferredSize()` 是不够的：对话框 `pack()` 顶不过子项累出来的
     * **最小**宽度，子项说"我最窄也要 800"，窗口就真会变成 800。2026-09-15
     * 用户报的"描述过长没有换行，导致整个弹框很宽"就是这条：长题干是个
     * 不换行的 `JLabel`，最小宽度跟着文本一路涨。
     *
     * 高度不写死（同 preferred）：选中「其它…」会长出一个输入框。
     * 同源先例见 StatusCardView.getMinimumSize 的注释。
     */
    override fun getMinimumSize(): Dimension =
        Dimension(CARD_WIDTH, super.getMinimumSize().height)

    /**
     * 按钮行：**「最小化」在左，其余靠右**。
     *
     * 左右分开不是排版偏好。「最小化」是可逆的、安全的（框还在，只是收起来，
     * 答案一个字不丢），右边那三颗都会把这条提问**结束掉**（答完 / 整条拒绝）。
     * 安全的那颗离危险的那几颗远一点。
     */
    private fun buildActions(): JComponent {
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false

            // 不设 mnemonic —— 助记符就是键盘捷径，而这是不可逆动作（同 PermissionCard 规则②）
            denyButton.addActionListener { onDeny() }

            submitButton.addActionListener {
                // 第二道闸：按钮灰着时点不动，但"少答一题就提交"的代价是替用户答了，
                // 所以这条判断不能只写在界面上
                if (!flow.canAdvance) return@addActionListener
                if (flow.submitCurrent()) onSubmit(flow.picked()) else onAdvance()
            }

            if (flow.index > 0) {
                backButton.addActionListener { if (flow.back()) onBack() }
                add(backButton)
            }
            add(denyButton)
            add(submitButton)
        }.leftAligned()

        minimizeButton.addActionListener { onMinimize() }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(minimizeButton, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }.leftAligned()
    }

    private companion object {
        val ACCENT = JBColor(0xFFA000, 0xFFB74D)

        /** 输入框装在 wrapper 里，靠它把 wrapper 和 field 关联起来。 */
        const val FIELD_KEY = "ccoder.ask.customField"
    }
}

/** 「下一题」：还有题没问。 */
internal val NEXT_LABEL: String get() = CcoderText.text("ask.next")

/** 「提交」：最后一题答完才出现。 */
internal val SUBMIT_LABEL: String get() = CcoderText.text("ask.submit")

/** 「拒绝」：整条提问都拒掉（不是"跳过这题"）。 */
internal val DENY_LABEL: String get() = CcoderText.text("ask.deny")

/** 「← 上一题」：回看/改答案。 */
internal val BACK_LABEL: String get() = CcoderText.text("ask.back")

/**
 * 「最小化」：先把框收起来，去代码里看一眼再回来答。
 *
 * 文案叫「最小化」而不是「稍后回答」：后者听着像"这件事可以先拖着"，而这条
 * 提问仍占着队列、Claude 仍在等 —— 它只是从眼前挪开了（见 [AskSequence.minimize]）。
 */
internal val MINIMIZE_LABEL: String get() = CcoderText.text("ask.minimize")

/** 悬停说明。「答案不会丢」是这颗按钮唯一需要讲清楚的事。 */
internal val MINIMIZE_TOOLTIP: String
    get() = CcoderText.text("ask.minimizeTip")

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

/**
 * 题干能用的宽度：卡片宽减去卡片自己的左右内边距（描边 1 + `empty(9, 11)`）。
 */
private val QUESTION_TEXT_WIDTH: Int get() = CARD_WIDTH - JBUI.scale(24)

/**
 * 选项的标题/说明能用的宽度：再减掉选项行的内边距（`empty(7, 9)` ×2）
 * 与左边那一格勾（约 11px），末尾留几像素余量。
 *
 * 宁可给窄一点：窄了只是多折一行，宽了就会把卡片顶破。这条线的两个方向
 * 后果不对称，所以往安全的那边留。
 */
private val OPTION_TEXT_WIDTH: Int get() = CARD_WIDTH - JBUI.scale(64)

/**
 * 会换行的只读文本块。
 *
 * 题干、选项标题、选项说明原来都是 `JLabel`，而 **`JLabel` 从不换行**：
 * 它报出来的宽度就是那一整行有多长。一条长题干于是把卡片、连同整个弹框一起
 * 顶宽（2026-09-15 用户报"问题描述过长没有换行，会导致整个弹框很宽"）。
 *
 * 打开换行本身只要两行（`lineWrap` + `wrapStyleWord`，仓库里 PermissionCard /
 * RunDetail / ClaudePanel 都是这个配方），真正要动脑筋的是**尺寸**：
 * `JTextArea` 的首选/最小宽度同样是按最长那一行算的 —— 光打开换行，它照样
 * 把框顶宽。所以这里把宽度覆写成"父容器给多宽我就用多宽"，高度按换行后的
 * 行数自己算。
 *
 * 不走 HTML 定宽那条路（`SessionList` 里的写法）：题干是模型给的自由文本，
 * 含 `<`、`&` 就得先转义，而这条链上的原则是**输入宽容** —— 少一个转义
 * 就多一种坏渲染。
 */
private class WrapText(text: String, private val widthOf: () -> Int) : JBTextArea(text) {

    init {
        isEditable = false
        // 卡片自己不铺底（见 AskQuestionCard 的注释），这段文字也不该铺
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty()
        // 它是"一段文字"，不是"一个能编辑的地方"：不给焦点，免得点选项时
        // 在说明里落下光标或选区 —— 点它等于点整行，那是选中，不是编辑
        isFocusable = false
    }

    override fun getPreferredSize(): Dimension {
        val w = widthOf()
        // 先按目标宽度量一遍：不 setSize 的话，拿到的仍是"一行到底"的那个高度
        setSize(w, Short.MAX_VALUE.toInt())
        return Dimension(w, super.getPreferredSize().height)
    }

    /**
     * 最小尺寸 = 首选尺寸。`BoxLayout` 只按这个下限去压，让它等于卡片宽度
     * （而不是最长那行），是"弹框不再被撑宽"的最后一道。
     */
    override fun getMinimumSize(): Dimension = preferredSize
}
