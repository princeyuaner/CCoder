package com.ccoder.ui

import com.ccoder.sidecar.SessionInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 会话列表。
 *
 * 排布是单行紧凑（设计稿 A）：标题在左可伸缩，时间在右不参与收缩 ——
 * 时间被挤掉的话排序就看不出来了。
 *
 * [block] 非 None 时**整列不可点**，并在顶部显示一句拦住的原因。
 * 拦住而不是静默忽略：点不动的东西容易被当成 bug（spec §5.1）。
 *
 * 当前会话那个勾用的是 [MARK] —— 与权限模式列表共用同一个常量，
 * 各写一份迟早漂移。
 */
internal fun buildSessionList(
    sessions: List<SessionInfo>,
    currentSessionId: String?,
    block: SwitchBlock,
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
    sessions.forEach { s ->
        root.add(
            sessionRow(
                session = s,
                selected = s.sessionId == currentSessionId,
                clickable = block == SwitchBlock.None,
                nowMs = now,
                onPick = onPick,
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
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，deriveFont 会 NPE
    // （RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(3, 6)
        if (clickable) {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onPick(session)
                }
            )
        }
    }

    // 标题：summary 优先，退回 firstPrompt，都没有给占位。
    // 空白行看起来像渲染坏了，不如直说
    val title = session.summary?.takeIf { it.isNotBlank() }
        ?: session.firstPrompt?.takeIf { it.isNotBlank() }
        ?: "（无标题）"

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

    row.add(markSlot, BorderLayout.WEST)
    row.add(titleLabel, BorderLayout.CENTER)
    row.add(timeLabel, BorderLayout.EAST)

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
