package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities

/**
 * 行尾删除按钮上写什么。
 *
 * **2026-09-15 从 `✕` 改成文字「删除」。** 用户原话：「删除按钮看不见也需要优化，
 * 按钮直接叫文字的 删除就好了」。`✕` 是一条被走过两遍的路（悬停才浮出 → 常驻
 * 半透明 → 常驻三档强调），每一遍都在同一个点上不够：**它得先被认出来是个删除**。
 * 文字没有这个成本，代价是宽 26px 而不是 16px —— 宽度现在由 [SESSION_LIST_WIDTH]
 * 钉住，那 10px 从标题那儿出。
 *
 * 实现与测试共用这一个常量，免得两边各写一份然后漂移。
 */
internal const val DELETE_TEXT = "删除"

/**
 * 弹层宽度上限（未缩放 px）。
 *
 * 弹层宽度**原先没有上限**：由最长那条标题撑出来 —— 实测 731px，而工具窗口
 * 只有 420px。跟 [SESSION_LIST_MAX_ROWS] 一起，是设计稿
 * `docs/design/session-list-v2.html` 方案 A 的两条。
 */
internal const val SESSION_LIST_WIDTH = 420

/**
 * 一屏最多几行，多出来的滚。
 *
 * 高度原先同样没有上限：77 个会话 = 1702px，而弹层**不能滚** —— 下半截落在
 * 屏幕外，点都点不到（`popupAnchorY` 那条"上下都放不下就贴屏幕顶"的分支就是
 * 为这种情况写的）。10 行 ≈ 220px。
 */
internal const val SESSION_LIST_MAX_ROWS = 10

/** 没有标签时那个入口上写什么。淡色的加号，点一下就地打标签。 */
internal const val TAG_EMPTY_MARK = "＋"

/**
 * 标签 chip 上写什么。
 *
 * 有标签时带一个 `#` 前缀：它是**用户自己打的**标记，不带前缀的话会和标题
 * 混成一句，看着像标题的一部分。
 */
internal fun tagChipText(tag: String?): String =
    if (tag.isNullOrBlank()) TAG_EMPTY_MARK else "#$tag"

/** 删除按钮悬停时的颜色。与设计稿 §二 A 的 danger 同值。 */
private val DELETE_DANGER = JBColor(0xC0392B, 0xDB5C5C)

/**
 * 同一时刻只允许一行处于确认态。
 *
 * 每行进入确认态前先调 [swap]，它会收回上一行 —— 否则界面上会同时挂着
 * 两个待确认的删除，而删除是不可逆的。
 */
private class ConfirmSlot {
    private var restore: (() -> Unit)? = null

    fun swap(restorePrevious: () -> Unit) {
        restore?.invoke()
        restore = restorePrevious
    }
}

/**
 * 会话列表。
 *
 * 排布是单行紧凑（设计稿 A）：标题在左可伸缩，时间在右不参与收缩 ——
 * 时间被挤掉的话排序就看不出来了。
 *
 * **宽高都钉住**（2026-09-15，设计稿 `session-list-v2.html` 方案 A）：
 * 宽不超过 [maxWidth]，高不超过 [SESSION_LIST_MAX_ROWS] 行 —— 超了就滚。
 * 这两条治的是实测出来的两个数：弹层宽 731px（由最长标题撑的）、
 * 高 1702px（77 个会话 × 22px），而它当时**既不能滚也装不下**。
 *
 * [block] 非 None 时**整列不可点**，并在顶部显示一句拦住的原因。
 * 拦住而不是静默忽略：点不动的东西容易被当成 bug（spec §5.1）。
 *
 * 行尾的 [DELETE_TEXT] 点了原地变成问句（设计稿 §二 A、§三 A）。
 * [onDelete] 只在用户**确认之后**才被调用。
 *
 * 当前会话那个勾用的是 [MARK] —— 与权限模式列表共用同一个常量，
 * 各写一份迟早漂移。
 */
internal fun buildSessionList(
    sessions: List<SessionInfo>,
    currentSessionId: String?,
    block: SwitchBlock,
    /**
     * 调用方那栏现在有多宽。弹层不比它宽（见 [SESSION_LIST_WIDTH]）——
     * 面板被拖窄了，弹层跟着窄，不能反过来溢出去。
     */
    maxWidth: Int = JBUI.scale(SESSION_LIST_WIDTH),
    // onDelete / onRename / onTag 刻意排在 onPick **前面**：Kotlin 的尾随 lambda
    // 绑的是最后一个参数，加在后面的话，所有既有的 `buildSessionList(s, id, block) { ... }`
    // 会**静默地**从"选中回调"变成别的什么 —— 点了会话什么都不发生，而且不报错。
    // 删除是破坏性动作，要求具名传入也更合适。
    onDelete: (SessionInfo) -> Unit = {},
    onRename: (SessionInfo, String) -> Unit = { _, _ -> },
    onTag: (SessionInfo, String?) -> Unit = { _, _ -> },
    onPick: (SessionInfo) -> Unit = {},
): JComponent {
    val root = ListColumn().apply { border = JBUI.Borders.empty(4, 4) }

    switchBlockNotice(block)?.let { root.add(noticeRow(it)) }

    if (sessions.isEmpty()) {
        root.add(noteRow("没有找到历史会话"))
        return root
    }

    val now = System.currentTimeMillis()
    // 整列共用一个确认槽：同一时刻只允许一行在确认态
    val confirmSlot = ConfirmSlot()
    sessions.forEach { s ->
        root.add(
            sessionRow(
                session = s,
                selected = s.sessionId == currentSessionId,
                clickable = block == SwitchBlock.None,
                nowMs = now,
                onPick = onPick,
                onDelete = onDelete,
                onRename = onRename,
                onTag = onTag,
                confirmSlot = confirmSlot,
            )
        )
    }

    // 宽：**必须显式钉住**。行里的标题没有上限，`preferredSize` 会一路长到
    // 标题那么宽（实测 731px）。钉住之后行会被压到列的宽度，标题自己打省略号。
    val width = minOf(root.preferredSize.width, maxWidth, JBUI.scale(SESSION_LIST_WIDTH))

    // 高：行高按**排出来的那一行**量，不写死 22 —— 字体与缩放下它不是常数
    val rowH = root.components.last().preferredSize.height
    val cap = rowH * SESSION_LIST_MAX_ROWS + root.insets.top + root.insets.bottom
    val height = root.preferredSize.height
    if (height <= cap) {
        root.preferredSize = Dimension(width, height)
        return root
    }
    return scrolledList(root, width, cap)
}

/**
 * 超高的那一版：套一层滚动。**只有超了才套** —— 会话少的时候（最常见）保持
 * 原来的组件结构，少一层壳，探针与测试也不用都跟着往里挖一层。
 */
private fun scrolledList(column: JPanel, width: Int, height: Int): JComponent =
    JBScrollPane(column).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(width, height)
    }

/**
 * 一列会话行。
 *
 * 与普通 `JPanel` 的唯一差别：塞进视口后**宽度跟着视口走**
 * （[getScrollableTracksViewportWidth]）。不这样的话，滚动条一出现，
 * 视口就比这一列窄 ~10px，而右边被切掉的正好是行尾那个「删除」。
 */
private class ListColumn : JPanel(), Scrollable {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(r: Rectangle?, orientation: Int, direction: Int): Int =
        JBUI.scale(16)

    override fun getScrollableBlockIncrement(r: Rectangle?, orientation: Int, direction: Int): Int =
        maxOf(1, (r?.height ?: 0) - JBUI.scale(16))

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}

private fun sessionRow(
    session: SessionInfo,
    selected: Boolean,
    clickable: Boolean,
    nowMs: Long,
    onPick: (SessionInfo) -> Unit,
    onDelete: (SessionInfo) -> Unit,
    onRename: (SessionInfo, String) -> Unit,
    onTag: (SessionInfo, String?) -> Unit,
    confirmSlot: ConfirmSlot,
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，deriveFont 会 NPE
    // （RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(3, 6)
    }

    // 标题：summary 优先，退回 firstPrompt，都没有给占位 —— 与删除确认语
    // 共用同一个函数，否则确认语里说的名字会和这一行显示的不是同一个
    val title = sessionTitle(session)

    // 未选中用空格而非 isVisible=false：不可见的组件仍然在组件树里，
    // 既让"只该有一条被标记"测不了，也让可访问性工具读到幻影文字。
    // 空格占位是 modeRow 已经在用的做法（ComposerMode.kt:115）
    val mark = JLabel(if (selected) MARK else " ").apply { font = base }
    val markSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        // 固定宽度：勾出现或消失时标题不左右跳
        preferredSize = JBUI.size(13, base.size)
        add(mark, BorderLayout.WEST)
    }

    val titleLabel = JLabel(title).apply {
        font = if (selected) base.deriveFont(Font.BOLD) else base
        // 唯一可伸缩的元素
    }

    /**
     * 标题所在的槽。
     *
     * 单独包一层是为了就地改名 —— 那时要把标题换成输入框，提交完还要能换回来。
     * 直接往 `row` 的 CENTER 里拿进拿出会把 `tail` 挤得跳一下。
     */
    val centerSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(titleLabel, BorderLayout.CENTER)
    }

    val timeLabel = JLabel(relativeTime(nowMs, session.lastModified)).apply {
        font = base
        foreground = UIUtil.getInactiveTextColor()
    }

    /**
     * 标签。有标签显示 `#标签`，没有显示一个淡色的「＋」。
     *
     * **没有标签时也必须留个东西**：改名是"在看得见的东西上加动作"（双击标题），
     * 而"给一个还没有标签的会话打标签"没有可点的对象 —— 藏进悬停或右键都会让
     * 它变得找不着。删除按钮当初从"悬停才浮出来"改成常驻，就是同一个理由。
     */
    val tagChip = JLabel(tagChipText(session.tag)).apply {
        font = base.deriveFont(base.size2D - 1f)
        foreground = if (session.tag.isNullOrBlank()) {
            UIUtil.getLabelDisabledForeground()
        } else {
            UIUtil.getInactiveTextColor()
        }
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = if (session.tag.isNullOrBlank()) "给这个会话打个标签" else "改标签"
    }

    // 按钮藏在固定宽度的槽里：直接拿进拿出布局会让时间标签左右跳一下。
    // 监听器在下面函数定义之后再挂 —— Kotlin 的局部函数不支持前向引用
    val deleteButton = JButton(DELETE_TEXT).apply {
        // **常驻可见**，不再"悬停才浮出来"。
        //
        // 设计稿 §二 A 选的是悬停才出现（列表最干净），但实测反馈**三轮**：
        // 悬停才出来 → "看不清楚"；改成常驻的 `✕` → 还是"看不见"；
        // 2026-09-15 用户给了答案：「按钮直接叫文字的 删除就好了」——
        // 卡着的从来不是显不显眼，而是**它得先被认出来是个删除**。
        //
        // 字号就是标签字号（`✕` 那版是 +2f）：文字不需要放大就有存在感，
        // 放大反而会盖过标题。层次仍靠颜色：平时次要色 → 指针到这一行上提亮 →
        // 停在按钮上时变红并长出一个真的按钮框（危险信号 + 可点 affordance）。
        font = base
        foreground = UIUtil.getInactiveTextColor()
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusable = false
        toolTipText = "删除这个会话"
        margin = JBUI.emptyInsets()
    }

    /**
     * 三档强调。删除不可逆，光标越靠近它信号越强。
     *
     * @param danger 指针就在按钮上：变红 + 长出按钮框
     * @param strong 指针在这一行上：提亮到正常前景色
     */
    fun paintDelete(danger: Boolean, strong: Boolean) {
        deleteButton.foreground = when {
            danger -> DELETE_DANGER
            strong -> UIUtil.getLabelForeground()
            else -> UIUtil.getInactiveTextColor()
        }
        deleteButton.isContentAreaFilled = danger
        deleteButton.isBorderPainted = danger
        deleteButton.repaint()
    }

    deleteButton.addMouseListener(
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = paintDelete(danger = true, strong = true)
            override fun mouseExited(e: MouseEvent) = paintDelete(danger = false, strong = true)
        }
    )

    val deleteSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        // 宽度按按钮自己量：文字比 `✕` 宽（26 对 16）——写死一个数的话，
        // 换字体/换语言时又会对不上，而这槽的意义就是"勾/按钮怎么变，时间都不跳"
        preferredSize = Dimension(deleteButton.preferredSize.width, base.size)
        add(deleteButton, BorderLayout.WEST)
    }

    /** 标签所在的槽。同上，编辑时要能换成输入框。 */
    val tagSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(tagChip, BorderLayout.CENTER)
    }

    val tail = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
        add(tagSlot)
        add(timeLabel)
        add(deleteSlot)
    }

    fun showNormal() {
        row.removeAll()
        centerSlot.removeAll()
        centerSlot.add(titleLabel, BorderLayout.CENTER)
        tagSlot.removeAll()
        tagSlot.add(tagChip, BorderLayout.CENTER)
        row.add(markSlot, BorderLayout.WEST)
        row.add(centerSlot, BorderLayout.CENTER)
        row.add(tail, BorderLayout.EAST)
        // 忙时整列不可点，删除自然也不该露出入口 —— 用可见性而不是禁用，
        // 禁用会留下一个"看得见点不动"的东西
        deleteButton.isVisible = clickable
        paintDelete(danger = false, strong = false)
        row.revalidate()
        row.repaint()
    }

    /**
     * 就地编辑。
     *
     * 回车提交、Esc 取消、**失焦也提交** —— 失焦取消的话，用户打完字去点别处，
     * 那行字就无声地没了。而且这是可逆的编辑（改错了再改一次），不需要删除
     * 那种"不可逆"的仪式。
     *
     * [done] 那道闸是必须的：提交时会把输入框从组件树上摘下来，而摘下来这一下
     * **自己会触发一次 focusLost** —— 不挡的话每次提交都要发两遍请求。
     */
    fun editorFor(initial: String, commit: (String) -> Unit): JTextField {
        var done = false
        fun finish(value: String?) {
            if (done) return
            done = true
            showNormal()
            if (value != null) commit(value)
        }

        return JTextField(initial).apply {
            font = base
            border = JBUI.Borders.empty(0, 2)
            addActionListener { finish(text) }
            addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ESCAPE) finish(null)
                    }
                }
            )
            addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(e: FocusEvent) {
                        // 只认真正的失焦：点一下别处再点回来中间会闪一次临时失焦
                        if (!e.isTemporary) finish(text)
                    }
                }
            )
        }
    }

    /** 双击标题 → 就地改名。 */
    fun enterRename() {
        if (!clickable) return
        // 收回别的行的特殊态：同一时刻只允许一行不是"常态"
        confirmSlot.swap { showNormal() }
        centerSlot.removeAll()
        centerSlot.add(
            editorFor(title) { value ->
                // 没改就不发请求。空串**要发** —— 那是"恢复自动标题"
                if (value != title) onRename(session, value)
            },
            BorderLayout.CENTER,
        )
        centerSlot.revalidate()
        centerSlot.repaint()
        centerSlot.components.firstOrNull()?.requestFocusInWindow()
    }

    /** 点标签 → 就地编辑标签。空串 = 清掉。 */
    fun enterTagEdit() {
        if (!clickable) return
        confirmSlot.swap { showNormal() }
        tagSlot.removeAll()
        tagSlot.add(
            editorFor(session.tag.orEmpty()) { value -> onTag(session, value) },
            BorderLayout.CENTER,
        )
        tagSlot.revalidate()
        tagSlot.repaint()
        tagSlot.components.firstOrNull()?.requestFocusInWindow()
    }

    // 监听器在函数定义之后再挂 —— Kotlin 的局部函数不支持前向引用。
    // 双击标题才进改名：单击已经归"切换会话"了，占用它等于把切换弄钝
    titleLabel.addMouseListener(
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) enterRename()
            }
        }
    )
    tagChip.addMouseListener(
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = enterTagEdit()
        }
    )

    fun enterConfirm() {
        confirmSlot.swap { showNormal() }

        val prompt = JLabel(deleteConfirmPrompt(session, isCurrent = selected)).apply { font = base }
        val cancel = JButton("取消").apply {
            font = base
            isFocusable = false
            addActionListener { showNormal() }
        }
        val confirm = JButton("删除").apply {
            font = base
            isFocusable = false
            addActionListener { onDelete(session) }
        }

        row.removeAll()
        row.add(prompt, BorderLayout.CENTER)
        row.add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(cancel)
                add(confirm)
            },
            BorderLayout.EAST,
        )
        row.revalidate()
        row.repaint()

        // 焦点落在「取消」：删除不可逆，默认焦点绝不能停在「删除」上
        // （PermissionCard.kt:26-28 同一条规则）
        SwingUtilities.invokeLater { cancel.requestFocusInWindow() }
    }

    deleteButton.addActionListener { enterConfirm() }
    showNormal()

    if (clickable) {
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        // **同一个** MouseAdapter 挂到行本身**和它的每个子组件**上。
        //
        // 两边都得挂，这是真鼠标点击的行为决定的：事件发给鼠标底下**最深的
        // 有监听器的组件**，不是你想的那个容器。只挂行的话，点标题（它为了
        // 悬停也挂了监听器）会被标题吃掉，行收不到 —— 表现就是"点会话没反应"；
        // 只挂子组件的话，点在行内空白处又没人接。
        //
        // **只在可点时才挂**：忙时整列不该有任何点击响应，一行也不例外
        // （列表自己的用例就是这么断言的）。
        val rowMouse = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // ✕ 与确认行上的按钮不冒泡成"切换会话" —— 不拦的话点删除
                // 会先切过去，然后你可能正在删一个刚被激活的会话
                if (e.component is JButton) return
                onPick(session)
            }

            override fun mouseEntered(e: MouseEvent) {
                paintDelete(danger = false, strong = true)
            }

            override fun mouseExited(e: MouseEvent) {
                // 离开**整行**是干净信号，直接收
                if (e.component === row) {
                    paintDelete(danger = false, strong = false)
                    return
                }
                // 从一个**子组件**移出则往往是移到了同一行的另一个子组件上
                // （标题→时间），此时行本身并没有离开事件。延后一拍看指针
                // 是否真的不在这一行里，否则强调会一闪一闪
                SwingUtilities.invokeLater {
                    val p = row.mousePosition
                    val inside = p != null && p.x in 0 until row.width && p.y in 0 until row.height
                    if (!inside) paintDelete(danger = false, strong = false)
                }
            }
        }
        row.addMouseListener(rowMouse)
        listOf(markSlot, titleLabel, tail, timeLabel, deleteSlot).forEach {
            it.addMouseListener(rowMouse)
        }
    }

    if (!clickable) row.isEnabled = false
    return row
}

private fun noticeRow(text: String): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(4, 6, 6, 6)
    // 定宽 HTML：说明要换行显示，不定宽的话它在弹出层里会被拉成一长条
    add(JLabel("<html><body style='width:230px'>$text</body></html>").apply {
        font = UIUtil.getLabelFont()
    }, BorderLayout.CENTER)
}

private fun noteRow(text: String): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    border = JBUI.Borders.empty(8, 6)
    add(JLabel(text).apply {
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getInactiveTextColor()
    }, BorderLayout.CENTER)
}

/**
 * 相对时间。分档到"天"为止 —— 再细就没意义了，列表是按时间排序的。
 *
 * 未来时间戳（时钟回拨、时区错乱）一律当"刚刚"，不显示负数。
 */
internal fun relativeTime(nowMs: Long, thenMs: Long): String {
    val delta = nowMs - thenMs
    val minutes = delta / 60_000
    val hours = delta / 3_600_000
    val days = delta / 86_400_000

    return when {
        delta < 60_000 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        days == 1L -> "昨天"
        else -> "$days 天前"
    }
}
