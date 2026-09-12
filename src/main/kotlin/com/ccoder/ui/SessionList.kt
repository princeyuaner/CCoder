package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** 行尾删除按钮的字符。实现与测试共用，免得两边各写一个字符然后漂移。 */
internal const val DELETE_MARK = "✕"

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
 * [block] 非 None 时**整列不可点**，并在顶部显示一句拦住的原因。
 * 拦住而不是静默忽略：点不动的东西容易被当成 bug（spec §5.1）。
 *
 * 悬停到一行时行尾浮出 [DELETE_MARK]，点了原地变成问句（设计稿 §二 A、§三 A）。
 * [onDelete] 只在用户**确认之后**才被调用。
 *
 * 当前会话那个勾用的是 [MARK] —— 与权限模式列表共用同一个常量，
 * 各写一份迟早漂移。
 */
internal fun buildSessionList(
    sessions: List<SessionInfo>,
    currentSessionId: String?,
    block: SwitchBlock,
    // onDelete 刻意排在 onPick **前面**：Kotlin 的尾随 lambda 绑的是最后一个参数，
    // 加在后面的话，所有既有的 `buildSessionList(s, id, block) { ... }` 会**静默地**
    // 从"选中回调"变成"删除回调" —— 点了会话什么都不发生，而且不报错。
    // 删除是破坏性动作，要求具名传入也更合适。
    onDelete: (SessionInfo) -> Unit = {},
    onPick: (SessionInfo) -> Unit = {},
): JComponent {
    val root = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 4)
    }

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
                confirmSlot = confirmSlot,
            )
        )
    }
    return root
}

private fun sessionRow(
    session: SessionInfo,
    selected: Boolean,
    clickable: Boolean,
    nowMs: Long,
    onPick: (SessionInfo) -> Unit,
    onDelete: (SessionInfo) -> Unit,
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

    val timeLabel = JLabel(relativeTime(nowMs, session.lastModified)).apply {
        font = base
        foreground = UIUtil.getInactiveTextColor()
    }

    // ✕ 藏在固定宽度的槽里：直接拿进拿出布局会让时间标签左右跳一下。
    // 监听器在下面函数定义之后再挂 —— Kotlin 的局部函数不支持前向引用
    val deleteButton = JButton(DELETE_MARK).apply {
        font = base
        isVisible = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusable = false
        toolTipText = "删除这个会话"
        margin = JBUI.emptyInsets()
        foreground = UIUtil.getInactiveTextColor()
    }
    val deleteSlot = JPanel(BorderLayout()).apply {
        isOpaque = false
        preferredSize = JBUI.size(16, base.size)
        add(deleteButton, BorderLayout.WEST)
    }

    val tail = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
        add(timeLabel)
        add(deleteSlot)
    }

    fun showNormal() {
        row.removeAll()
        row.add(markSlot, BorderLayout.WEST)
        row.add(titleLabel, BorderLayout.CENTER)
        row.add(tail, BorderLayout.EAST)
        deleteButton.isVisible = false
        row.revalidate()
        row.repaint()
    }

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
        row.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // ✕（以及确认行上的按钮）不冒泡成"切换会话" ——
                    // 不拦的话点删除会先切过去，然后你可能正在删一个刚被激活的会话
                    if (e.component is JButton) return
                    onPick(session)
                }

                override fun mouseEntered(e: MouseEvent) {
                    deleteButton.isVisible = true
                }

                override fun mouseExited(e: MouseEvent) {
                    deleteButton.isVisible = false
                }
            }
        )

        // 子孙组件上的进入/离开同样要算作"在这一行上" —— 否则鼠标移到时间标签上时
        // row 收到 mouseExited，✕ 一闪就没了。收回前先确认指针真的不在这一行里。
        //
        // **只在可点时才挂**：忙时整列不该有任何点击响应，一行也不该例外
        // （列表自己的用例就是这么断言的）。
        listOf(markSlot, titleLabel, tail, timeLabel, deleteSlot).forEach { child ->
            child.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        deleteButton.isVisible = true
                    }

                    override fun mouseExited(e: MouseEvent) {
                        SwingUtilities.invokeLater {
                            val p = row.mousePosition
                            val inside = p != null && p.x in 0 until row.width && p.y in 0 until row.height
                            if (!inside) deleteButton.isVisible = false
                        }
                    }
                }
            )
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
