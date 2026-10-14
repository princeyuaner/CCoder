package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 定时发送的第二块：**时间本身**（2026-10-13，设计稿见
 * `web/tools/scheduled-send-view.mjs` 出的选型台，用户选了丙案）。
 *
 * 这里放三样东西：
 *  - 时间的三条纯函数与实时判词（[parseClockTime] / [resolveTodayFireAt] /
 *    [scheduleTimeText] / [clockHintOf]）—— 不依赖 Swing，有单测钉着；
 *  - 工具栏上那颗[ScheduleButton]（画法与附件键同一个模子）；
 *  - [buildSchedulePicker]：向上弹的选时弹层（快捷 + 指定今天时刻），
 *    样式全部取自 [PickerList] 那一套"卡与行"—— 它和模型/权限/思考三个弹层
 *    是同一族控件，行高、圆角、悬停手感只许在那一处写。
 *
 * 口径（用户拍板的）：只收**今天、且晚于现在**的时刻；快捷行的右侧写出落到的
 * 钟点，越过零点才加"明天"。
 */

/**
 * 定时气泡上那颗 ✕ 的落点（由面板注入，同 [TurnChangesHost] 的分工：
 * 视图不认识面板记的那张表）。
 */
internal fun interface ScheduleHost {
    fun cancelScheduled(id: String)
}

// ---------------------------------------------------------------- 纯函数

/**
 * "HH:MM" → [LocalTime]；认不出给 null。
 *
 * 认 `9:05` 也认 `09:05`（一个冒号、两位分钟），别的一律不收 ——
 * 这是给手敲用的最短格式，宽进会把 `9.05`、`905` 这类也放进来，
 * 而它们各自"看起来像"别的意思。
 */
internal fun parseClockTime(raw: String): LocalTime? {
    val m = Regex("""^(\d{1,2}):(\d{2})$""").find(raw.trim()) ?: return null
    val hh = m.groupValues[1].toInt()
    val mm = m.groupValues[2].toInt()
    if (hh !in 0..23 || mm !in 0..59) return null
    return LocalTime.of(hh, mm)
}

/** 今天 [time] 的那一刻（epoch millis）。 */
internal fun todayAtMillis(time: LocalTime, now: ZonedDateTime): Long =
    now.toLocalDate().atTime(time).atZone(now.zone).toInstant().toEpochMilli()

/**
 * 今天 [time] 的那一刻 —— **不晚于现在给 null**。
 *
 * 「只收未来」是设计口径：你说的是"当天几点"，此刻已过的钟点没有一个
 * 合理的解释（顶到明天去发，叫"今天 14:05"就是句假话）。
 */
internal fun resolveTodayFireAt(time: LocalTime, now: ZonedDateTime): Long? =
    todayAtMillis(time, now).takeIf { it > now.toInstant().toEpochMilli() }

/** `HH:MM`（24 小时制）。 */
internal fun clockText(epochMs: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

/** [epochMs] 与 [now] 是不是同一个自然日。 */
internal fun sameScheduleDay(epochMs: Long, now: ZonedDateTime): Boolean =
    Instant.ofEpochMilli(epochMs).atZone(now.zone).toLocalDate() == now.toLocalDate()

/** "今天 18:00" / "明天 01:20" —— 气泡上那行标签用的。 */
internal fun scheduleTimeText(epochMs: Long, now: ZonedDateTime): String {
    val key = if (sameScheduleDay(epochMs, now)) {
        "composer.schedule.today"
    } else {
        "composer.schedule.tomorrow"
    }
    return CcoderText.text(key, clockText(epochMs, now.zone))
}

/**
 * 快捷行右侧的落点：同一天只写钟点（14:52）；越过零点才加上"明天"
 * （那一栏窄，日期前缀只在**确实需要**时出现）。
 */
internal fun scheduleValueText(epochMs: Long, now: ZonedDateTime): String =
    if (sameScheduleDay(epochMs, now)) clockText(epochMs, now.zone) else scheduleTimeText(epochMs, now)

/** 指定时刻那一行的实时判词。 */
internal data class ClockHint(val text: String, val warn: Boolean, val fireAt: Long?)

/**
 * "这一串字现在能不能定时、定到哪一刻" —— 弹层里那一行的唯一判据。
 *
 * 抽成纯函数是因为弹层开合没法单测（同 PickerList 顶上那条），
 * 而"已过的时刻不许发"正是这里最容易写错的一格。
 */
internal fun clockHintOf(raw: String, now: ZonedDateTime): ClockHint {
    if (raw.isBlank()) {
        return ClockHint(CcoderText.text("composer.schedule.enterHint"), warn = false, fireAt = null)
    }
    val parsed = parseClockTime(raw)
        ?: return ClockHint(CcoderText.text("composer.schedule.badFormat"), warn = true, fireAt = null)
    val at = resolveTodayFireAt(parsed, now)
        ?: return ClockHint(
            CcoderText.text("composer.schedule.past", scheduleTimeText(todayAtMillis(parsed, now), now)),
            warn = true,
            fireAt = null,
        )
    return ClockHint(CcoderText.text("composer.schedule.enterHint"), warn = false, fireAt = at)
}

// ---------------------------------------------------------------- 时钟键

/**
 * 工具栏上的定时键（发送键左边那颗）。
 *
 * 画法与 [AttachButton] 同一个模子：12×12 的格子、1.35 的描边、状态色只在
 * 次要/正文/禁用三档里挑。**禁用态是它的一半命运**：输入框空着（也没贴图）
 * 时不可点 —— 没有东西可定时的时刻，这颗键不许骗人有反应。
 */
internal class ScheduleButton(private val onOpen: () -> Unit) : JComponent() {

    private var hovered = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val box = JBUI.scale(BOX_SIDE)
        preferredSize = Dimension(box, box)
        minimumSize = preferredSize
        maximumSize = preferredSize
        // 键记在控件上（见 LocalizedText.kt）：换语言时走树那一遍会重挂
        localizedTooltip("composer.schedule.tip")
        addMouseListener(
            object : MouseAdapter() {
                // 同 RoundSendButton：普通 JComponent 不吞禁用后的点击，得显式拦
                override fun mouseClicked(e: MouseEvent) {
                    if (isEnabled) onOpen()
                }

                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            }
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when {
                !isEnabled -> UIUtil.getLabelDisabledForeground()
                hovered -> UIUtil.getLabelForeground()
                else -> UIUtil.getInactiveTextColor()
            }
            g2.stroke = BasicStroke(JBUI.scale(1.35f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val side = minOf(width, height).toDouble()
            if (side <= 0) return
            val glyph = side * GLYPH_RATIO
            g2.translate((width - glyph) / 2.0, (height - glyph) / 2.0)
            g2.scale(glyph / GRID, glyph / GRID)

            // 一格 12×12：圆 + 长针朝上 + 短针朝右下（四点左右的角度）
            val r = 4.7
            g2.draw(Ellipse2D.Double(6.0 - r, 6.0 - r, r * 2, r * 2))
            g2.draw(Line2D.Double(6.0, 6.0, 6.0, 3.4))
            g2.draw(Line2D.Double(6.0, 6.0, 8.1, 7.2))
        } finally {
            g2.dispose()
        }
    }

    internal companion object {
        /** 与附件键同一格（AttachButton.BOX_SIDE）—— 两颗动作键并排站着要对齐。 */
        const val BOX_SIDE = 22
        const val GLYPH_RATIO = 0.78
        const val GRID = 12.0
    }
}

// ---------------------------------------------------------------- 选时弹层

/** 弹层宽度。做完与模型弹层并排看一眼再定（探针出图）。 */
internal const val SCHEDULE_PICKER_WIDTH = 300

/** 快捷行的三条：从此刻起算的分钟数。 */
private val SCHEDULE_PRESETS = listOf(
    Triple("composer.schedule.afterMinutes", 30, 30L),
    Triple("composer.schedule.afterHours", 1, 60L),
    Triple("composer.schedule.afterHours", 2, 120L),
)

/**
 * 选时弹层的内容。每开一次现建一次（同模型弹层）——所以这里的文案直接取词，
 * 不必走"键记在控件上"那一套。
 *
 * [now] 是**开弹层那一刻**的钟：快捷行落点、右侧钟点、"现在几点"都按它算。
 * [clockNow] 是实时判词（已过 / 日期比较）用的钟读数。**默认就是真钟**；
 * 单独开一个口子是为了渲染探针 —— 它把 [now] 钉死在一个历史时刻出图，
 * 判词若走真钟，"现在 14:22" 的弹层会把 14:52 判成"已经过了"，图上自相矛盾。
 * [onPick] 收一个到点时刻（epoch millis），扣不扣弹层由调用方决定。
 */
internal fun buildSchedulePicker(
    now: ZonedDateTime,
    clockNow: () -> ZonedDateTime = { ZonedDateTime.now() },
    onPick: (Long) -> Unit,
): JComponent {
    val root = cardColumn()
    val nowMs = now.toInstant().toEpochMilli()
    // 卡宽要钉死在弹层**内宽**上：BoxLayout 不会把富余的横向空间分给不拉伸的子件
    // （JPanel 的最大宽度就是首选宽度），不钉的话卡片只有内容那么宽，右侧的钟点
    // 到不了弹层边缘 —— 探针图上量出来 225px 对 300px（2026-10-13 第一次出图）。
    val innerWidth = JBUI.scale(SCHEDULE_PICKER_WIDTH) - root.insets.left - root.insets.right

    // —— 卡一：快捷 ——
    val quickRows = SCHEDULE_PRESETS.map { (key, arg, minutes) ->
        val fireAt = nowMs + minutes * 60_000
        pickerRow(
            label = CcoderText.text(key, arg),
            value = scheduleValueText(fireAt, now),
        ) { onPick(fireAt) }
    }
    root.addCard(
        optionCard(
            header = pickerHeader(
                cardTitle(CcoderText.text("composer.schedule.head.quick")),
                trailing = hintLabel(CcoderText.text("composer.schedule.now", clockText(nowMs, now.zone))),
            ),
            rows = quickRows,
        ).fillWidth(innerWidth)
    )

    // —— 卡二：指定今天时刻 ——
    val field = JTextField(clockText(nowMs + 30 * 60_000, now.zone)).apply {
        // 9 列 ≈ 63px 内容宽，对齐设计稿 .m-field 的 min-width: 64px。
        // **别退回 5 列**：5 列的内容宽恰好等于 "HH:MM" 的字宽（41px 首选 − 6px
        // 边框内衬 = 35px = 五个等宽字），一分余量没有 —— 撇开"看着挤"，焦点
        // 落在末尾时视图还要为光标挪一下，把开头的数字裁掉（2026-10-13 用户报：
        // 框里只剩 ":08"；探针在未聚焦态也把 "14:52" 画成 "14:!"）
        columns = 9
        font = timeFont()
        // 这一格是"槽位"，不是贴着左边写的一行字 —— 设计稿里钟点居中
        horizontalAlignment = SwingConstants.CENTER
    }
    val hint = hintLabel("")
    fun applyHint() {
        val h = clockHintOf(field.text, clockNow())
        hint.text = h.text
        hint.foreground = if (h.warn) warningColor() else UIUtil.getInactiveTextColor()
    }
    field.document.addDocumentListener(
        object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyHint()
            override fun removeUpdate(e: DocumentEvent) = applyHint()
            override fun changedUpdate(e: DocumentEvent) = applyHint()
        }
    )
    field.addActionListener {
        val h = clockHintOf(field.text, clockNow())
        h.fireAt?.let(onPick)
    }
    // 弹层一露头就把光标放进这一格：想敲时间的人不必再点一下
    field.addHierarchyListener { e ->
        if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && field.isShowing) {
            field.requestFocusInWindow()
        }
    }
    applyHint()

    val fieldRow = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(3, 6)
        add(field, BorderLayout.WEST)
        add(hint, BorderLayout.CENTER)
        hint.border = JBUI.Borders.emptyLeft(10)
    }
    root.addCard(
        optionCard(
            header = cardTitle(CcoderText.text("composer.schedule.head.at")),
            rows = listOf(fieldRow),
        ).fillWidth(innerWidth)
    )

    // 宽钉死、高让内容撑 —— 弹层宽度是内容撑出来的，不钉的话两条快捷行
    // 一长一短会把弹层宽度带出两三档（模型弹层当初被主机名撑到 442px 那次）
    root.preferredSize = Dimension(JBUI.scale(SCHEDULE_PICKER_WIDTH), root.preferredSize.height)
    return root
}

/** 弹层里一行：左名字、右落点（钟点列，等宽字）。悬停与整行可点同 PickerList 的规矩。 */
private fun pickerRow(label: String, value: String, onClick: () -> Unit): JComponent {
    val row = RoundedRow(selected = false, fixedHeight = PICKER_ROW_HEIGHT).apply {
        add(
            JLabel(label).apply {
                foreground = UIUtil.getLabelForeground()
                font = UIUtil.getLabelFont()
            },
            BorderLayout.CENTER,
        )
        add(
            JLabel(value).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = timeFont()
            },
            BorderLayout.EAST,
        )
    }
    handlerInto(
        row,
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick()
            }
        },
    )
    return row
}

/** 卡头右边那点小字（"现在 14:22"）。内边距与 [cardTitle] 对齐，一行看着才齐。 */
private fun hintLabel(text: String): JLabel {
    val base = UIUtil.getLabelFont()
    return JLabel(text).apply {
        foreground = UIUtil.getInactiveTextColor()
        font = base.deriveFont(base.size2D - 2f)
        border = JBUI.Borders.empty(7, 9, 3, 9)
    }
}

/** 钟点用的等宽字（同 SyncBubble：数字列对齐）。 */
private fun timeFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size - 1)

/** 卡头一行：左标题、右小字。 */
private fun pickerHeader(main: JComponent, trailing: JComponent?): JComponent =
    JPanel(BorderLayout()).apply {
        isOpaque = false
        add(main, BorderLayout.WEST)
        if (trailing != null) add(trailing, BorderLayout.EAST)
    }

/**
 * 把这张卡的宽度钉死在 [width] 上（= 弹层内宽）。
 *
 * **不是**"把最大宽度放开让 BoxLayout 去撑" —— 试过那个写法：`BoxLayout` 的
 * Y 轴布局里，子件宽度就是它能站的那点地方，不拉伸的（`JPanel` 最大宽度 = 首选
 * 宽度）。出图量出来是 225px 对 300px，右边缘差着一截。钉死之后又与 `QueueStrip`
 * 那条"最大宽度别用 `Int.MAX_VALUE`"的教训无关了 —— 这里根本不走加总那一步。
 */
private fun <T : JComponent> T.fillWidth(width: Int): T = apply {
    val h = preferredSize.height
    preferredSize = Dimension(width, h)
    maximumSize = Dimension(width, h)
}
