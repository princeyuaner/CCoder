package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 排队条一行里最多放多少个字。
 *
 * 截断在**模型层**做而不是交给 JLabel 自动省略：自动省略的宽度取决于布局过程，
 * 单测测不到，而"一行的长度"正是这条带子最容易失控的地方（用户能敲进一整段代码）。
 */
internal const val QUEUE_ROW_CHARS = 40

/** 排队条上的一行：[label] 是截好、折好行的显示文本，[item] 用来撤回。 */
internal data class QueueRow(val item: QueuedInput, val label: String)

/** 排队条要显示什么。空队列给 null —— 调用方据此把整块收起来（spec §6）。 */
internal data class QueueStripModel(
    val count: Int,
    val rows: List<QueueRow>,
) {
    /**
     * 两条以上才值得收成一行等点击（2026-09-15 按用户要求改的：
     * 「排队的信息大于 1 条应该转成一个列表，点击后展开，不然太占用空间」）。
     * 一条时就一条，收起来等于把唯一的内容藏进抽屉。
     */
    val collapsible: Boolean get() = count > 1
}

/** 一行能放下的展示文本：换行与连续空白折成一个空格，超长的截断加省略号。 */
internal fun queueRowText(text: String): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= QUEUE_ROW_CHARS) flat else flat.take(QUEUE_ROW_CHARS) + "…"
}

/**
 * 排队条的内容。空队列给 null。
 *
 * **不再给行数封顶**：从前是"最多列 3 行 + 还有 N 条"，那个上限是替"一眼看全"
 * 省空间的；现在收折那一行干了同样的事，而用户**主动点开**就该看得全。
 */
internal fun queueStripModel(queue: SendQueue): QueueStripModel? {
    if (queue.isEmpty) return null
    val all = queue.snapshot()
    return QueueStripModel(all.size, all.map { QueueRow(it, queueRowText(it.text)) })
}

/**
 * 那一行写什么。
 *
 * 只有一条时把内容一并写出来 —— 「排队 1」单独占一行是白占；两条以上只写条数，
 * 内容点开才有（用户 2026-09-15 的原话）。
 */
internal fun queueLineText(model: QueueStripModel): String =
    if (model.rows.size == 1) "排队 1 · ${model.rows[0].label}" else "排队 ${model.count}"

/**
 * 排队条：哪些消息还排着、各自一眼能认出来、都能撤（spec §6）。
 *
 * 它只负责画 —— 显示什么由 [queueStripModel] 与 [queueLineText] 两个纯函数决定，
 * 那两条规则有测试钉着（同 [RoundSendButton] 与 [MainButtonState] 的分工）。
 *
 * **不管可见性**：空队列时由 [setModel] 自己收掉，别处不必再记一遍。
 */
internal class QueueStrip(private val onRemove: (QueuedInput) -> Unit) : JPanel() {

    private val head = JButton()
    // 用 [LinkButton] 而不是 JButton：它只管字宽，而 New UI 给所有 JButton 兜了
    // 72px 最小宽度 —— 不钉的话 ✕ 会浮在离右边缘 35px 的地方（见 LinkButton）
    private val headRemove = LinkButton("✕")
    private val body = JPanel()

    private var model: QueueStripModel? = null

    /** 两条以上时的开合。**不随每次刷新重置** —— 用户开着看的时候又来一条，
     *  不能把它关掉；队列回到一条或空时由 [setModel] 归位。 */
    private var expanded = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        // 上下各留 4px：它是输入卡上方的一条独立带子，紧贴着状态卡会糊成一块。
        // **不走外面的 strut** —— strut 是独立子项，排队条收起来时它照样占位
        border = JBUI.Borders.empty(4, 8)
        // 出生即隐藏：面板刚建出来时没人调过 setModel，露出来的会是一条
        // 什么都没有、却占着 8px 的空白
        isVisible = false

        // **必须显式左对齐**：BoxLayout 里不拉伸的子项按 alignmentX 摆位，
        // 而 JLabel / JPanel 的默认值是 0.5（居中）。更要紧的是它算位置用的是
        // **加权平均对齐点**、权重是子项的 max 宽度 —— 只要有一项还是 0.5，
        // 整条带子就会被推到中间去（2026-09-15 探头图里抓到过：
        // 414 宽的头里它待在 x=113、宽 301）。全设成 0 之后各归各位
        alignmentX = LEFT_ALIGNMENT

        for (button in listOf(head, headRemove)) {
            button.isContentAreaFilled = false
            button.isBorderPainted = false
            button.font = JBUI.Fonts.smallFont()
            button.foreground = UIUtil.getInactiveTextColor()
        }
        head.horizontalAlignment = SwingConstants.LEFT
        head.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        head.addActionListener {
            if (model?.collapsible == true) setExpanded(!expanded)
        }

        headRemove.isVisible = false
        headRemove.toolTipText = "从队列里撤掉这条"
        headRemove.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        headRemove.addActionListener { model?.rows?.firstOrNull()?.let { onRemove(it.item) } }

        val headRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(head, BorderLayout.CENTER)
            add(headRemove, BorderLayout.EAST)
        }

        body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
        body.isOpaque = false
        body.alignmentX = LEFT_ALIGNMENT

        add(headRow)
        add(body)
    }

    fun setModel(next: QueueStripModel?) {
        isVisible = next != null
        model = next
        // 回到一条或空就归位：留着"展开"的话，下次两条一冒出来就是开着的
        if (next == null || !next.collapsible) expanded = false
        apply()
    }

    /** 开合。也留给渲染探针 —— Swing 的开合没法单测，只能出图看。 */
    fun setExpanded(value: Boolean) {
        expanded = value
        apply()
    }

    private fun apply() {
        val m = model ?: return
        head.text = queueLineText(m) +
            if (m.collapsible) (if (expanded) "  ▾" else "  ▸") else ""
        // 内容就在这一行上时（只有一条），✕ 也跟着在这一行
        headRemove.isVisible = !m.collapsible

        body.removeAll()
        for (row in m.rows) body.add(buildRow(row))
        body.isVisible = m.collapsible && expanded

        revalidate()
        repaint()
    }

    /**
     * 撑满整行、高度只要首选的。
     *
     * 不覆写的话 BoxLayout 拿首选宽度摆它，于是那一排 ✕ 跟着每行文字的长短
     * 左右乱跑（2026-09-15 探头图里抓到的）；铺满之后它们一律贴右边缘。
     *
     * 宽度必须是 [Short.MAX_VALUE] 而**不是** `Int.MAX_VALUE`：BoxLayout 会把
     * 子项的 max 宽度**相加**，用 Int 上限当场溢出成负数。
     */
    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)

    private fun buildRow(row: QueueRow) = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        // 卡面只留一行摘要，全文挂 tooltip（与工具卡片那条摘要同一个道理）
        add(
            JLabel(row.label).apply {
                font = JBUI.Fonts.smallFont()
                toolTipText = row.item.text
            },
            BorderLayout.CENTER,
        )
        add(
            LinkButton("✕").apply {
                // 自绘外观：默认按钮的边框与底色在这条安静的带子上太抢眼
                isContentAreaFilled = false
                isBorderPainted = false
                toolTipText = "从队列里撤掉这条"
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { onRemove(row.item) }
            },
            BorderLayout.EAST,
        )
    }
}
