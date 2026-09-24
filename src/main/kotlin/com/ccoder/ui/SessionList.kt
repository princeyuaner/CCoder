package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import com.ccoder.text.CcoderText
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
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
internal val DELETE_TEXT: String get() = CcoderText.text("session.list.delete")

/**
 * 行尾改名按钮上写什么（2026-09-24）。
 *
 * 与 [DELETE_TEXT] 同一套写法：文字、常驻、指针停上去才长出按钮框。
 *
 * **为什么不继续用"双击标题"那条路**：双击在真机上到不了。`rowMouse`
 * （单击 = 切换会话）也挂在标题上（见 `sessionRow` 末尾那段），双击的**第一下**
 * 就触发了 `onPick` —— 弹层跟着关掉，第二下永远落不到标题上。
 * 而单测是往标题直接派发一个 `clickCount = 2` 的事件，绕过了这一层，所以一直绿着
 * （仓库里"单测全绿、东西还是坏的"又一例）。用户的原话是要一个按钮。
 */
internal val RENAME_TEXT: String get() = CcoderText.text("session.list.rename")

/**
 * 顶部那行右上角的入口上写什么（2026-09-17）。
 *
 * 「清空全部」而不是「清空」：这一列里每个会话都有自己的一颗「删除」，
 * 少一个字会读成"清空当前这条"。
 */
internal val CLEAR_ALL_TEXT: String get() = CcoderText.text("session.list.clearAll")

/** 确认态里那颗真正动手的按钮。与行内确认同一个词：那一刻问的是同一件事。 */
internal val CLEAR_CONFIRM_TEXT: String get() = CcoderText.text("session.list.clear")

/** 顶部那行左边那三个字。有它，右边那颗「清空全部」才像一条工具栏而不是飘着的动作。 */
internal val LIST_TITLE_TEXT: String get() = CcoderText.text("session.list.title")

/**
 * 顶部那一行的组件名。
 *
 * 唯一的用处是让**用例**能把它认出来排除掉：列表的直接子件里从此既有会话行
 * 也有这一行，而按 `components[0]` 取第一行的老用例会取到它 —— 它不是会话，
 * 点它什么都不会发生（2026-09-17 加这一行时，三条用例当场红了）。
 */
internal const val SESSION_LIST_HEADER_NAME = "ccoder.sessionListHeader"

/**
 * 一次列几个会话（查询上限，不是显示上限）。
 *
 * 2026-09-17 从 `ClaudePanel.SESSION_LIST_LIMIT` 搬到这里：列表页要知道
 * "列出来的条数是不是全量"，才能把「清空全部」的确认语说准（见
 * [clearAllConfirmPrompt] 的 `moreThanListed`）。
 */
internal const val SESSION_LIST_QUERY_LIMIT = 50

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
 * 为这种情况写的）。
 *
 * 2026-09-22 卡片式改版后这个数从 10 改成 8：一行从 28px（文字行）变成 36px
 * （[CARD_H] 32 + [CARD_GAP] 4），**一屏的像素高度不变** —— 8 × 36 = 288，
 * 与改版前 10 × 28 = 280 是同一档。所以"卡片式会更占地方"这件事在这一屏里
 * 只体现为"看见的条数少了"（10 → 8），浮层本身没有变高。
 */
internal const val SESSION_LIST_MAX_ROWS = 8

/** 没有标签时那个入口上写什么。淡色的加号，点一下就地打标签。 */
internal const val TAG_EMPTY_MARK = "＋"

/**
 * 已被别的标签占着的那一行，时间那一格改写这个。
 *
 * 为什么不只靠"点不动"：点不动的东西会被当成 bug（spec §5.1 的老账）。
 * 一句话讲清"为什么点不动、去哪儿找它"。
 */
internal val TAKEN_TEXT: String get() = CcoderText.text("session.list.taken")

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
 * 一张卡的高度（不含它与下一张之间的缝）。
 *
 * 由内容最高的那一件（删除按钮 22）+ 上下内边距 3 + 3 推出来，是**量出来**的数
 * 而不是挑的：行高 29 那版就是"按钮 22 + 上下 3"撑出来的（见 [SessionCard] 的注释）。
 */
private const val CARD_H = 32

/**
 * 卡与卡之间的缝。
 *
 * **留在卡片自己下边**（[SessionCard] 绘制时的那个 `top` 偏移），
 * 而不是往列里插 `Box.createVerticalStrut` —— 插了的话 `root.components` 里就
 * 一行卡一行缝，列表那几十条用例按序号取行的写法会一起错位。卡片自己吃下这条缝，
 * 外面看起来仍然是"一个会话一个子件"。
 */
private const val CARD_GAP = 4

/**
 * 被占着的那张卡降多少透明度（见 [SessionCard.paint]）。
 *
 * 0.55 是"一眼看出它不归你"，同时里面的字还读得清 —— 压到 0.4 以下标题就糊了，
 * 而"这是哪条会话"仍然是最要紧的信息。出图看 `session-list-probe-taken.png`。
 */
private const val DIM_ALPHA = 0.55f

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
 * 每一行现在是一张 [SessionCard]（2026-09-22 卡片式改版，设计稿
 * `session-list-v3.html` 方案 A）：当前会话由卡片画出来的强调条 + 加粗标题标出，
 * 不再是行首那个 `✓`。
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
    /**
     * 已被**某个标签**占着的会话（见 [OpenSessions]）—— **含本面板自己正跑着的那一条**
     * （它在 `init` 时就登记了，取的是全部登记，见 `2026-09-17-session-clear-all-design.md`
     * §3.3）。这些行不可切换，并在时间那一格写一句「已打开」：两条标签开同一条会话会
     * 两边同时写同一个 jsonl；而自己那条点下去等于把会话重启一遍。
     *
     * 不可切换**不等于**什么都干不了：改名对它们（至少对当前那条）开着，见
     * [sessionRow] 里的 `editAllowed`。
     *
     * 位置在 [onPick] 之前是**硬要求**：Kotlin 的尾随 lambda 绑最后一个参数，
     * 见下面那段注释。
     */
    takenIds: Set<String> = emptySet(),
    // onDelete / onRename / onTag 刻意排在 onPick **前面**：Kotlin 的尾随 lambda
    // 绑的是最后一个参数，加在后面的话，所有既有的 `buildSessionList(s, id, block) { ... }`
    // 会**静默地**从"选中回调"变成别的什么 —— 点了会话什么都不发生，而且不报错。
    // 删除是破坏性动作，要求具名传入也更合适。
    onDelete: (SessionInfo) -> Unit = {},
    /**
     * 点了行尾那颗「改名」。
     *
     * **填名字的输入框在 `ClaudePanel` 那层弹**（2026-09-24 改的）：对话框要 Project，
     * 而这个文件没有、也刻意不拿。这一层只回答"用户点了哪一行的哪颗按钮"。
     *
     * 为什么不是就地编辑：这个弹层**不可聚焦**（`showTogglePopup` 建的时候
     * `setFocusable(false)`），里面塞输入框根本拿不到焦点 —— 敲的字全进了主输入框。
     * 那套设置对别的三个浮层（状态卡 / 模型 / 思考档）是对的，它们是只读选择器。
     */
    onRename: (SessionInfo) -> Unit = {},
    /** 点了标签 chip。同上：输入在 `ClaudePanel` 那层弹。 */
    onTag: (SessionInfo) -> Unit = {},
    /**
     * 顶部右上角那颗「清空全部」被确认之后才调用（2026-09-17）。
     *
     * 与 [onDelete] 同一条规矩：**确认不是装饰**，不确认这里不会被调到。
     */
    onClearAll: () -> Unit = {},
    onPick: (SessionInfo) -> Unit = {},
): JComponent {
    val root = ListColumn().apply { border = JBUI.Borders.empty(4, 4) }

    switchBlockNotice(block)?.let { root.add(noticeRow(it)) }

    if (sessions.isEmpty()) {
        // 没有会话时**不画**那一行：右上角挂一颗"清空全部"去清一个空列表，
        // 只会让人怀疑列表是不是没加载出来
        root.add(noteRow(CcoderText.text("session.list.empty")))
        return root
    }

    val now = System.currentTimeMillis()
    // 整列共用一个确认槽：同一时刻只允许**一个**确认态 —— 一行或者整列，
    // 谁后进谁把前一个收回（删除不可逆，界面上同时挂着两个待确认的删除更糟）
    val confirmSlot = ConfirmSlot()
    root.add(
        clearAllRow(
            sessions = sessions,
            block = block,
            takenIds = takenIds,
            currentSessionId = currentSessionId,
            onClearAll = onClearAll,
            confirmSlot = confirmSlot,
        ),
    )
    sessions.forEach { s ->
        root.add(
            sessionRow(
                session = s,
                selected = s.sessionId == currentSessionId,
                // 整列能不能动。"这一行可不可切换"由它 + taken 在 sessionRow 里推出来
                // （被占的那条不可切换：点过去就是两边写同一条 jsonl）
                idle = block == SwitchBlock.None,
                taken = s.sessionId in takenIds,
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
 * 行里那种"文字按钮"（「删除」/「清空全部」）。
 *
 * 常驻可见但**不画框**：平时就是一行次要色的字，指针停到它上面才变红并长出
 * 按钮框（见 [paintDanger]）—— 这样它读起来像文字，但认得出是个动作。
 *
 * 尺寸不由它自己定：它照常给出首选尺寸，由外面的槽钉住。槽**不能**压扁它 ——
 * 压扁了那个框会横穿字形（2026-09-17 用户报的「轮廓太矮」，见 `deleteSlot`）。
 */
private fun textAction(text: String, tooltip: String, font: Font): JButton = JButton(text).apply {
    this.font = font
    foreground = UIUtil.getInactiveTextColor()
    isContentAreaFilled = false
    isBorderPainted = false
    isFocusable = false
    toolTipText = tooltip
    margin = JBUI.emptyInsets()
}

/**
 * 三档强调。行里那些"文字按钮"（删除 / 改名 / 清空全部）共用这一个写法：
 * 光标越靠近它，信号越强。
 *
 * @param hot 指针就在按钮上：长出按钮框
 * @param strong 指针在这一行上：提亮到正常前景色
 * @param danger 破坏性动作（删除一行 / 清空全部）：这一档用红色。
 *   改名这类**可逆**动作只长框不变红 —— 红色在这套界面里是"这一下不可逆"的信号
 *   （2026-09-24 加改名按钮时把它从 `paintDanger` 推广成这一个）
 */
private fun paintAction(button: JButton, hot: Boolean, strong: Boolean, danger: Boolean = false) {
    button.foreground = when {
        danger && hot -> DELETE_DANGER
        hot || strong -> UIUtil.getLabelForeground()
        else -> UIUtil.getInactiveTextColor()
    }
    button.isContentAreaFilled = hot
    button.isBorderPainted = hot
    button.repaint()
}

/**
 * 列表顶部那一行：左边「历史会话」，右边「清空全部」（2026-09-17）。
 *
 * ## 为什么在弹层里而不是面板上
 *
 * 用户原话：「右上角需要增加一个一键清空所有历史对话的功能」。清的是**这个项目的
 * 历史**，而"这个项目有哪些历史"只有弹层在说 —— 把入口放在弹层的右上角，
 * 它管的东西就在它下面。
 *
 * ## 与行内删除同一套写法
 *
 * 文字按钮、平时次要色、指针停上去变红；点下去**就地**变成一句问句 +
 * `[取消] [清空]` —— 不可逆的动作不给"点一下就走完"。
 * 确认槽与行内**共用**（见 [ConfirmSlot]）：一行在确认时点这里、或者反过来，
 * 前者都会先收回原样。
 *
 * 忙时（[block] 非 None）**不露入口**：那时整列都不可点，冒一个能点的出来
 * 就成了唯一的例外（同行内那颗删除，`deleteButton.isVisible = clickable`）。
 */
private fun clearAllRow(
    sessions: List<SessionInfo>,
    block: SwitchBlock,
    takenIds: Set<String>,
    currentSessionId: String?,
    onClearAll: () -> Unit,
    confirmSlot: ConfirmSlot,
): JComponent {
    val base = UIUtil.getLabelFont()
    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        name = SESSION_LIST_HEADER_NAME
        // 下面那条线（同设置框里那几处 hairline）：有它，这一行才像一条工具栏，
        // 而不是列表里多出来的第一行
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineBottom(lineColor()),
            JBUI.Borders.empty(2, 6, 4, 6),
        )
    }

    val title = JLabel(LIST_TITLE_TEXT).apply {
        font = base
        foreground = UIUtil.getInactiveTextColor()
    }

    // 「正在使用中」的条数：清空时会跳过它们，确认语里要说清有几条。
    // 口径与行内那颗删除一致 —— 被占的会话连删除入口都没有
    val kept = sessions.count { it.sessionId in takenIds || it.sessionId == currentSessionId }

    val action = textAction(CLEAR_ALL_TEXT, CcoderText.text("session.list.clearAllTip"), base)
    // 槽照抄按钮的首选尺寸，一个数都不改（同 deleteSlot 那条：压扁了框会横穿字形）。
    // 不用 hover 整行提亮那一档：这一行除了它没有别的可点动作，
    // 两档（安静 ↔ 指针停在按钮上）就够了
    val actionSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        preferredSize = action.preferredSize
        add(action, BorderLayout.WEST)
    }
    action.addMouseListener(
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = paintAction(action, hot = true, strong = true, danger = true)
            override fun mouseExited(e: MouseEvent) = paintAction(action, hot = false, strong = false)
        }
    )

    fun showNormal() {
        row.removeAll()
        action.isVisible = block == SwitchBlock.None
        // 强调也一并归零：按钮被隐藏时不会再收到 mouseExited，停在它上面的
        // 那一档红色会一直留着（下次显示出来就是红的）
        paintAction(action, hot = false, strong = false)
        row.add(title, BorderLayout.CENTER)
        row.add(actionSlot, BorderLayout.EAST)
        row.revalidate()
        row.repaint()
    }

    fun enterConfirm() {
        confirmSlot.swap { showNormal() }

        // 问句与两颗按钮**挤在同一行**：弹层的尺寸是打开那一刻定死的
        // （`showTogglePopup` → `setResizable(false)`），这一行一长高就再也长不出来。
        //
        // 试过的那一版是"问句占一整行、按钮另起一行"：出图一看，那一行比原来高
        // 26px，而弹层没跟着长 —— 问句被挤成了负高度，图上直接缺一块。
        // 所以文案必须短到能和按钮并排放下（见 [clearAllConfirmPrompt]）。
        //
        // 也**不套 HTML 定宽**：JLabel 装不下是**裁**不是折行，
        // 而 HTML 的 `width` 只影响排版、不影响它报出来的首选宽（量过：
        // 写 250 时首选仍是那一行的自然宽 325）—— 等于白写还多一层壳。
        // "放得下"这件事改由用例钉住（见 SessionListTest 里那条量宽的）。
        val prompt = JLabel(
            clearAllConfirmPrompt(
                count = sessions.size,
                keptCount = kept,
                moreThanListed = sessions.size >= SESSION_LIST_QUERY_LIMIT,
            ),
        ).apply { font = base }

        val cancel = JButton(CcoderText.text("common.cancel")).apply {
            font = base
            isFocusable = false
            addActionListener { showNormal() }
        }
        val confirm = JButton(CLEAR_CONFIRM_TEXT).apply {
            font = base
            isFocusable = false
            addActionListener { onClearAll() }
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

        // 焦点落在「取消」（同行内确认、PermissionCard.kt:26-28）：清空是这一列里
        // 唯一一个"按错就没了"的动作，默认焦点绝不能停在「清空」上
        SwingUtilities.invokeLater { cancel.requestFocusInWindow() }
    }

    action.addActionListener { enterConfirm() }
    showNormal()
    return row
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

/**
 * 会话行 = **一张单行卡**（2026-09-22 用户从三个方案里选的 A，见
 * `docs/design/session-list-v3.html`）。
 *
 * ## 画什么
 *
 * 底与边**同一个圆角、同一个矩形**（[CARD_CORNER_ARC] / [cardFill] / [lineColor]，
 * 与输入卡、状态卡、设置卡同一套）：底比页底亮/暗 5%，卡片靠**边 + 圆角**读出来。
 * 底由这里的 `fillRoundRect` 画、边由 `RoundedLineBorder` 画 —— 两个数一旦分叉，
 * 四个角上就露出"底比边圆"的毛刺（`StatusCardView` / `ComposerCard` 各踩过一次）。
 *
 * - **当前会话**：描边换强调色 + 左侧一条 3px 强调条（标题另由调用方加粗）。
 *   改版前那个 `✓` 由这条 + 加粗顶掉 —— 一个 3px 的色块比一个字符更早被眼睛抓到，
 *   也不用再占标题左边那 13px。
 * - **悬停**（可点时）：底换成列表选中那一档的"弱"版本，描边提亮。
 * - **被别的标签占着 / 忙时**（不可点）：两个都不画 —— 它本来就不该显得能点。
 *
 * ## 为什么钉高
 *
 * 浮层开出来之后**不能缩放**（`showTogglePopup` → `setResizable(false)`），
 * 所以卡片一旦比预期高，多出来的那一截就是被裁掉、再也长不出来 ——
 * 「清空全部」那行确认态当初就是这么缺了一块的（见 `clearAllRow` 里的注释）。
 * 这里把首选/最小/最大三个高度一起钉住，内容矮一点高一点都由布局居中吸收。
 *
 * `internal` 是给用例量的（"哪一条是当前"只有卡片自己知道）。
 */
internal class SessionCard(
    /** 当前会话：强调色描边 + 左侧强调条。 */
    val current: Boolean,
    /** 可点（不忙、也没被别的标签占着）：悬停才有反馈。 */
    private val clickable: Boolean,
    /**
     * 被**别的标签**占着：整卡压暗。
     *
     * 光靠"时间那格写着已打开"和"点不动"不够 —— 点不动的东西会被当成 bug
     * （spec §5.1 的老账），而压暗是"这一条现在不归你"最省事的一眼信号。
     * 忙时（整列不可点）**不压暗**：那是临时的，而且整列都灰了就没法读。
     */
    private val dimmed: Boolean = false,
) : JPanel(BorderLayout()) {

    /** 指针在不在这一张上（由 [sessionRow] 的鼠标监听器维护）。 */
    var hovered: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        isOpaque = false
        // 上 3 下 3：内容最高的那件（删除按钮 22）撑出 28，剩下的由钉高居中吸收。
        // 右边 6、左边 8：左边那 4px 是留给强调条的（它在 x=4..6）
        border = JBUI.Borders.empty(3, 8, 3, 6)
    }

    /** 实际画出来的高度（下面的 [CARD_GAP] 那一条缝不画东西）。 */
    private val paintedHeight: Int get() = JBUI.scale(CARD_H)

    override fun getPreferredSize(): Dimension =
        Dimension(super.getPreferredSize().width, JBUI.scale(CARD_H + CARD_GAP))

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    /**
     * 压暗**整张卡**（含里面的字），而不是只把卡底调灰一点。
     *
     * 卡底本来就只比页底亮 5%（[CARD_TINT]），在它上面再调灰**看不出来** ——
     * 出图时试过，两张图几乎一样。整卡降透明度是唯一看得见的那种"退后一步"，
     * 而且一处就够：子组件跟着父组件的绘制一起被降透明。
     */
    override fun paint(g: Graphics) {
        if (!dimmed) {
            super.paint(g)
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = java.awt.AlphaComposite.getInstance(
                java.awt.AlphaComposite.SRC_OVER,
                DIM_ALPHA,
            )
            super.paint(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(CARD_CORNER_ARC)
            val w = width - 1
            val h = paintedHeight - 1
            // 卡片缝的一半留在上边：这样"卡片的中线"与"内容的中线"落在同一条线上
            // （内边距上下对称 = 内容居中于整个 36px 组件；卡片若贴着顶画，
            // 它的中线就比内容高了 2px —— 2026-09-22 用户报的「文字没有上下居中」）
            val top = JBUI.scale(CARD_GAP / 2)

            g2.color = if (hovered && clickable) {
                UIUtil.getListSelectionBackground(false)
            } else {
                cardFill()
            }
            g2.fillRoundRect(0, top, w, h, arc, arc)

            g2.color = if (current) focusColor() else lineColor()
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, top, w, h, arc, arc)

            // 左侧那条 3px 强调条。画在描边里侧、与文字之间留一口气（内容从 x=8 起）
            if (current) {
                val bar = JBUI.scale(3)
                val inset = JBUI.scale(6)
                g2.color = focusColor()
                g2.fillRoundRect(JBUI.scale(4), top + inset, bar, h - inset * 2, bar, bar)
            }
        } finally {
            g2.dispose()
        }
    }
}

private fun sessionRow(
    session: SessionInfo,
    selected: Boolean,
    /**
     * 整列能不能动（[SwitchBlock.None]）。忙时任何入口都不露 —— 这是**列级**的规矩，
     * 所以它比"这一行可不可切换"更靠上一层。
     */
    idle: Boolean,
    nowMs: Long,
    onPick: (SessionInfo) -> Unit,
    onDelete: (SessionInfo) -> Unit,
    onRename: (SessionInfo) -> Unit,
    onTag: (SessionInfo) -> Unit,
    confirmSlot: ConfirmSlot,
    /** 已被别的标签占着。不可点，并在时间那一格写一句「已打开」。 */
    taken: Boolean = false,
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，deriveFont 会 NPE
    // （RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    /** 可**切换过去**：整列能动、而且这条没被占着（自己跑着的那条也算占着）。 */
    val clickable = idle && !taken

    /**
     * 行内的"改元数据"入口露不露（改名按钮 / 标签 chip）。
     *
     * **当前会话也能改**（2026-09-24，用户选的）：挡住删除的那条理由 ——
     * "那条 jsonl 正被活着的 CLI 进程写着" —— 只对得上删除（见
     * `2026-09-17-session-clear-all-design.md` §3.3）；改名与打标签写的是标题 /
     * 标签，不碰转写。所以当前这一行虽然不可切换、不可删，这两个入口开着。
     */
    val editAllowed = idle && (clickable || selected)

    val row = SessionCard(current = selected, clickable = clickable, dimmed = taken)

    // 标题：summary 优先，退回 firstPrompt，都没有给占位 —— 与删除确认语
    // 共用同一个函数，否则确认语里说的名字会和这一行显示的不是同一个
    val title = sessionTitle(session)

    val titleLabel = JLabel(title).apply {
        font = if (selected) base.deriveFont(Font.BOLD) else base
        // 唯一可伸缩的元素
        if (taken) {
            // 压暗 + 一句 tooltip：光"点不动"会被当成 bug（spec §5.1 的老账）
            foreground = UIUtil.getLabelDisabledForeground()
            toolTipText = TAKEN_TEXT
        }
    }

    // 被占的那一行，时间那一格改说「已打开」：状态比"多久以前"更该被看见，
    // 而这一行本来就点不动了（时间对它没有意义）
    val timeLabel = JLabel(if (taken) TAKEN_TEXT else relativeTime(nowMs, session.lastModified)).apply {
        font = base
        foreground = UIUtil.getInactiveTextColor()
    }

    /**
     * 标签。有标签显示 `#标签`，没有显示一个淡色的「＋」。
     *
     * **没有标签时也必须留个东西**：这一列里每个动作都有一个看得见的落点
     * （改名、删除都是文字按钮），只有"给一个还没有标签的会话打标签"原本
     * 没有可点的对象 —— 藏进悬停或右键都会让它变得找不着。
     * 删除按钮当初从"悬停才浮出来"改成常驻，就是同一个理由。
     */
    val tagChip = JLabel(tagChipText(session.tag)).apply {
        font = base.deriveFont(base.size2D - 1f)
        foreground = if (session.tag.isNullOrBlank()) {
            UIUtil.getLabelDisabledForeground()
        } else {
            UIUtil.getInactiveTextColor()
        }
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = if (session.tag.isNullOrBlank()) CcoderText.text("session.list.tagTip") else CcoderText.text("session.list.tagEditTip")
        // 点一下弹对话框（2026-09-24：原来是就地编辑，而弹层不可聚焦，敲不进字）
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // 当前会话也允许 —— 改名与打标签都是元数据写入（见 [editAllowed]）
                    if (editAllowed) onTag(session)
                }
            }
        )
    }

    // 按钮藏在固定宽度的槽里：直接拿进拿出布局会让时间标签左右跳一下。
    // 监听器在下面函数定义之后再挂 —— Kotlin 的局部函数不支持前向引用
    //
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
    val deleteButton = textAction(DELETE_TEXT, CcoderText.text("session.list.deleteTip"), base)

    fun paintDelete(danger: Boolean, strong: Boolean) =
        paintAction(deleteButton, hot = danger, strong = strong, danger = danger)

    deleteButton.addMouseListener(
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = paintDelete(danger = true, strong = true)
            override fun mouseExited(e: MouseEvent) = paintDelete(danger = false, strong = true)
        }
    )

    val deleteSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        // 尺寸**整个照抄按钮自己的首选尺寸**，一个数都不改。
        //
        // 宽度按按钮量：文字比 `✕` 宽（26 对 16）——写死一个数的话，
        // 换字体/换语言时又会对不上，而这槽的意义就是"勾/按钮怎么变，时间都不跳"。
        //
        // 高度也必须按按钮量。2026-09-17 之前这里写的是**字号**（`base.size`，
        // 实测 12），而按钮首选高是 22（字高 16 + 平台边框上下各 3）——
        // 于是按钮被压成 12px 高，悬停时那个"长出按钮框"的强调就画在一个
        // 12px 的盒子里：**上下边框正好横穿这两个字**，看着像划掉，
        // 左右也贴着字形。用户的原话是"轮廓不对，太矮了"。
        //
        // 这个框的尺寸只由这里决定，所以它同时也是"行高"的决定者（见下面那条
        // "行高按排出来的那一行量"）—— 按钮不再压扁之后，行高仍是 22，没有变。
        preferredSize = deleteButton.preferredSize
        add(deleteButton, BorderLayout.WEST)
    }

    /**
     * 改名按钮（2026-09-24，用户要的：原来是"双击标题"，而那个入口在真机上到不了，
     * 见 [RENAME_TEXT]）。
     *
     * 与删除同一套文字按钮，只有两点不同：
     *  - **不变红**：改名可逆，红色留给"这一下不可逆"的动作（[paintAction] 的 danger）
     *  - 排在上面那颗标签之后、删除之前 —— 两个动作挨着，别再往中间塞别的东西
     */
    val renameButton = textAction(RENAME_TEXT, CcoderText.text("session.list.renameTip"), base)

    fun paintRename(hot: Boolean, strong: Boolean) = paintAction(renameButton, hot = hot, strong = strong)

    renameButton.addMouseListener(
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = paintRename(hot = true, strong = true)
            override fun mouseExited(e: MouseEvent) = paintRename(hot = false, strong = true)
        }
    )

    /** 同上：槽照抄按钮自己的首选尺寸，压扁了那个框会横穿字形（deleteSlot 那条的老账）。 */
    val renameSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        preferredSize = renameButton.preferredSize
        add(renameButton, BorderLayout.WEST)
    }

    /**
     * 行尾那一串：标签 / 时间 / 改名 / 删除。
     *
     * 外面这层 `GridBagLayout` **只干一件事：把这一行上下居中**。里层仍是
     * `FlowLayout`（右对齐、间距 6，与从前一模一样）。为什么不直接用它：
     * `FlowLayout` 把行里的组件**贴着顶**摆 —— 卡片比以前高（[CARD_H] 32，
     * 内容是 22），空出来的那几像素全落到了下面，于是这一串比标题高出去 4px。
     * 2026-09-22 用户报的「文字没有上下居中」就是它（标题居中、这一串没居中，
     * 两边对不上更显眼）。`GridBagLayout` 只有一个孩子、权重为 0 时默认就把它
     * 居中，不用手写"上下各留几像素"那种会在换字体时失效的数。
     */
    val tail = JPanel(GridBagLayout()).apply {
        isOpaque = false
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(tagChip)
                add(timeLabel)
                add(renameSlot)
                add(deleteSlot)
            },
        )
    }

    /**
     * 回到"常态"那一版布局。
     *
     * 编辑不再从这里进进出出（改成对话框了，见 [buildSessionList] 的 `onRename`），
     * 但删除确认 / 清空确认还是要靠它把行恢复原样。
     */
    fun showNormal() {
        row.removeAll()
        row.add(titleLabel, BorderLayout.CENTER)
        row.add(tail, BorderLayout.EAST)
        // 忙时整列不可点，删除自然也不该露出入口 —— 用可见性而不是禁用，
        // 禁用会留下一个"看得见点不动"的东西
        deleteButton.isVisible = clickable
        paintDelete(danger = false, strong = false)
        // 忙时整列不该有任何入口；空闲时**当前会话那一行**也留着改名（见 [editAllowed]）
        renameButton.isVisible = editAllowed
        paintRename(hot = false, strong = false)
        row.revalidate()
        row.repaint()
    }

    fun enterConfirm() {
        confirmSlot.swap { showNormal() }

        val prompt = JLabel(deleteConfirmPrompt(session, isCurrent = selected)).apply { font = base }
        val cancel = JButton(CcoderText.text("common.cancel")).apply {
            font = base
            isFocusable = false
            addActionListener { showNormal() }
        }
        val confirm = JButton(DELETE_TEXT).apply {
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
    // 与删除那颗不同：改名不在这里换形态，只把"用户点了这一行"报出去
    // （弹输入框的是 ClaudePanel，见 [buildSessionList] 的 `onRename`）
    renameButton.addActionListener { if (editAllowed) onRename(session) }
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
                // 悬停的反馈有两处：卡片的底/描边（卡片自己画）与
                // 行尾那两颗文字按钮（改名 / 删除）提亮
                row.hovered = true
                paintDelete(danger = false, strong = true)
                paintRename(hot = false, strong = true)
            }

            override fun mouseExited(e: MouseEvent) {
                // 离开**整行**是干净信号，直接收
                if (e.component === row) {
                    row.hovered = false
                    paintDelete(danger = false, strong = false)
                    paintRename(hot = false, strong = false)
                    return
                }
                // 从一个**子组件**移出则往往是移到了同一行的另一个子组件上
                // （标题→时间），此时行本身并没有离开事件。延后一拍看指针
                // 是否真的不在这一行里，否则强调会一闪一闪
                SwingUtilities.invokeLater {
                    val p = row.mousePosition
                    val inside = p != null && p.x in 0 until row.width && p.y in 0 until row.height
                    if (!inside) {
                        row.hovered = false
                        paintDelete(danger = false, strong = false)
                        paintRename(hot = false, strong = false)
                    }
                }
            }
        }
        row.addMouseListener(rowMouse)
        listOf(titleLabel, tail, timeLabel, deleteSlot).forEach {
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
        delta < 60_000 -> CcoderText.text("session.time.justNow")
        minutes < 60 -> CcoderText.text("session.time.minutes", minutes)
        hours < 24 -> CcoderText.text("session.time.hours", hours)
        days == 1L -> CcoderText.text("session.time.yesterday")
        else -> CcoderText.text("session.time.days", days)
    }
}
