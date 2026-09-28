package com.ccoder.ui

import com.ccoder.sync.SyncSnapshot
import com.ccoder.text.CcoderText
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 一轮同步真的动了文件时，在**同步卡上方**冒一句的迷你气泡（2026-09-24）。
 *
 * 形态是用户从 `docs/design/sync-bubble.html` 里挑的**甲**：一行标题（同步了几个）、
 * 最多三条相对路径、超出写一句"还有 N 个 ›"，**3 秒后自己关**；点它 = 打开日志浮层
 * （不是第三种点击去处，见下）。
 *
 * ## 为什么三行就够
 *
 * 3 秒是按"读一行标题 + 扫两三条短路径"量的；八行那个方案（乙）当场就被"读不完"
 * 否掉了。要逐条核对的人本来就有两个更合适的入口：点卡片、点气泡 —— 都是同一屏日志。
 *
 * ## 三处不能含糊的边界
 *
 * 1. **只报"真的做成的"**：[SyncSnapshot.copiedNames] / [deletedNames] 取的是
 *    `RoundReport.copiedRel` / `deletedRel`（计划减失败），不是计划。界面替引擎撒谎
 *    是最坏的一种错 —— "我改的东西怎么没同步"这个问题上尤其。
 * 2. **一轮只报一次**：判据是 `lastRoundAtMs`（见 [shouldAnnounceSyncRound]）。
 *    拿整份快照判等不行 —— 夹在两轮之间的那些"还没跑完"的发布与跑完的那一次
 *    可以有完全相同的字段。
 * 3. **不抢焦点、不打断输入**：`setRequestFocus(false)`，且 `hideOnKeyOutside = false`
 *    （在输入框里打字不该把气泡打没 —— 用户要的就是"停 3 秒"）。
 *
 * ## 平台气泡是现成的（核过 PyCharm 2023.3.4 的 `app-client.jar`）
 *
 * `BalloonBuilder.setFadeoutTime(long)` 到点淡出，`JBPopupFactory.createBalloonBuilder(JComponent)`
 * 收自定义内容，`Balloon.show(RelativePoint, Balloon.Position)` 管贴在哪 —— 全都在，
 * 不必自己做计时器（那是丁那一版才要付的代价，见选型稿）。
 */

/** 气泡最多列几条文件名。与 `SyncSnapshot.NAME_LIMIT` 是一对（那边各留三条）。 */
internal const val SYNC_BUBBLE_ROWS = 3

/** 停留多久后自动关闭。用户 2026-09-24 定的 3 秒。 */
internal const val SYNC_BUBBLE_MS = 3_000L

/** 气泡里那一行文件。[deleted] true = 这一条是删掉的（行首加个减号，与复制的分开）。 */
internal data class SyncBubbleRow(val text: String, val deleted: Boolean)

/**
 * 气泡要说的话。全是**已经翻好的字**（这一屏不进树，用完就没了 —— 同 `SyncTexts` 里
 * 那句"立刻翻好的一句"的理由）。
 */
internal data class SyncBubbleModel(
    /** 标题：`已同步 3 个文件 · 480 ms`（删除、用时按有无拼上去）。 */
    val title: String,
    /** 有失败时那一句（红字）。没失败就是 null。 */
    val failed: String?,
    val rows: List<SyncBubbleRow>,
    /** `还有 9 个 ›`。没有更多就是 null。 */
    val more: String?,
)

/** 标题里那几段的连接符。不是句子，是排版，所以不进词表。 */
private const val SEP = " · "

/**
 * 一轮报告 → 气泡要说的话。**没有值得说的就返回 null**（稳态下的大多数轮）。
 *
 * 什么时候值得说（选型稿里那三条"要一起定的事"的第二条，用户没另说，按建议取）：
 * **复制或删除 > 0，或者有失败**。失败尤其要说 —— 那是"我改的东西没过去"的唯一现场提示。
 */
internal fun syncBubbleModelOf(snapshot: SyncSnapshot): SyncBubbleModel? {
    val copies = snapshot.copied
    val deletes = snapshot.deleted
    val failed = snapshot.failed.size
    if (copies == 0 && deletes == 0 && failed == 0) return null

    val head = when {
        copies > 0 && deletes > 0 ->
            CcoderText.text("sync.bubble.copied", copies) + SEP + CcoderText.text("sync.bubble.deleted", deletes)

        copies > 0 -> CcoderText.text("sync.bubble.copied", copies)
        else -> CcoderText.text("sync.bubble.deletedOnly", deletes)
    }
    val cost = snapshot.lastCostMs ?: 0L
    // 用时：一秒以内按毫秒（"480 毫秒"，与设置页/日志同一个口径），
    // 一秒以上换成 `61s` 那句（`elapsedText`，工具卡与思考块用的就是它）——
    // 首次全量核对会跑到一分钟，"61000 毫秒"在一行标题里没人读得下去
    val costText = if (cost >= 1_000) {
        elapsedText(Math.round(cost / 1000.0).toInt())
    } else {
        CcoderText.text("sync.bubble.took", cost)
    }
    val title = head + SEP + costText

    // 复制在前、删除补位。**行数封顶三行**（见 [SYNC_BUBBLE_ROWS]），
    // 而"还有几个"是从**计数**算的，不是从这两个被截断的列表 —— 首轮全量核对
    // 会有一万多个文件，列表里永远只有三条
    val rows = mutableListOf<SyncBubbleRow>()
    snapshot.copiedNames.take(SYNC_BUBBLE_ROWS).forEach { rows += SyncBubbleRow(it, deleted = false) }
    snapshot.deletedNames.take(SYNC_BUBBLE_ROWS - rows.size).forEach { rows += SyncBubbleRow(it, deleted = true) }
    val hidden = copies + deletes - rows.size

    return SyncBubbleModel(
        title = title,
        failed = if (failed > 0) CcoderText.text("sync.bubble.failed", failed) else null,
        rows = rows,
        more = if (hidden > 0) CcoderText.text("sync.bubble.more", hidden) else null,
    )
}

/**
 * 这一轮该不该冒气泡。
 *
 * @param announcedAtMs 上一次已经报过的那一轮（`SyncSnapshot.lastRoundAtMs`），null = 还没报过。
 */
internal fun shouldAnnounceSyncRound(snapshot: SyncSnapshot, announcedAtMs: Long?): Boolean {
    val at = snapshot.lastRoundAtMs ?: return false   // 还没跑完过一轮（开机后的头几个快照）
    if (at == announcedAtMs) return false             // 这一轮报过了
    return snapshot.copied > 0 || snapshot.deleted > 0 || snapshot.failed.isNotEmpty()
}

/**
 * 路径放不下时**从中间省略**：`trunk/src/log/writer.kt` → `trunk/src/log/…/writer.kt`。
 *
 * 为什么不是截尾巴（`chipTitleFor` 那种）：文件名在**末尾**，而它恰恰是这一行里唯一
 * 能让人认出"是不是我改的那个文件"的部分 —— 截掉尾巴等于把这条信息扔了。
 *
 * 纯函数（喂 [FontMetrics]），用例直接打。
 */
internal fun middleTruncate(text: String, metrics: FontMetrics, maxWidth: Int): String {
    if (maxWidth <= 0) return ""
    if (metrics.stringWidth(text) <= maxWidth) return text
    val ellipsis = "…"
    val budget = maxWidth - metrics.stringWidth(ellipsis)
    if (budget <= 0) return ellipsis

    // **文件名（最后一段）整段留住**，哪怕为此把目录整条丢掉 —— 它是这一行里唯一
    // 能让人认出"是不是我改的那个文件"的部分。按比例对半分不行：那样文件名会被截成
    // `…ngFileName.kt`，看着像另一个文件
    val slash = text.lastIndexOf('/')
    val name = text.substring(slash + 1)                    // 不含那个 '/'
    val tail = if (slash >= 0) "/$name" else name
    if (metrics.stringWidth(tail) > budget) {
        // 连文件名自己都放不下（超长文件名）：只好从它的左边截，尾巴还是留着。
        // 找**放得下的最长后缀** —— 从整条开始试，一次丢一个字符，直到装进预算
        // （反过来从最后一个字符开始试会当场"成功"，然后只留下一个字符）
        var start = 0
        while (start < tail.length && metrics.stringWidth(ellipsis + tail.substring(start)) > budget) start++
        return ellipsis + tail.substring(start)
    }

    // 剩下的预算全给开头那截，目录的中段省略
    val headLimit = if (slash >= 0) slash else 0
    var headEnd = 0
    while (headEnd < headLimit) {
        val next = text.substring(0, headEnd + 1) + ellipsis + tail
        if (metrics.stringWidth(next) > budget) break
        headEnd++
    }
    return text.substring(0, headEnd) + ellipsis + tail
}

/**
 * 弹出气泡。锚点不在屏上、或者平台拒绝时就返回 null（**不抛**）——
 * 一条提示不值得把面板搞崩。
 *
 * 位置：贴锚点（那张卡）的正上方，尖头指着它。平台会自己把气泡往屏幕里挤，
 * 但它**不知道工具窗口的边界** —— 面板贴着屏幕右边时气泡可能压到编辑器上；
 * 这是刻意的取舍：宁可越出面板一点，也不要跟卡片脱钩（同 `popupCardX` 那条"跟自己的卡走"）。
 *
 * @param onClick 点气泡做什么（打开日志浮层）。
 * @param parent 面板自己 —— 面板销毁时气泡跟着走，不必手写摘除。
 */
internal fun showSyncBubble(
    anchor: JComponent,
    model: SyncBubbleModel,
    onClick: () -> Unit,
    parent: Disposable,
): Balloon? {
    if (!anchor.isShowing) return null

    val content = bubbleContent(model, onClick)
    val balloon = runCatching {
        JBPopupFactory.getInstance()
            .createBalloonBuilder(content)
            .setFadeoutTime(SYNC_BUBBLE_MS)
            .setHideOnClickOutside(true)
            // 打字不该把气泡打没（用户要的是"停 3 秒"）；点外面关掉就够了
            .setHideOnKeyOutside(false)
            .setHideOnAction(false)
            // **绝不能抢焦点**：用户可能正在输入框里打字，气泡是旁白不是对话
            .setRequestFocus(false)
            .setShowCallout(true)
            .setDisposable(parent)
            .createBalloon()
    }.getOrNull() ?: return null

    // 点在卡片中间那一点上：气泡以它为轴心，于是大体落在卡片上方
    val shown = runCatching {
        balloon.show(RelativePoint(anchor, Point(anchor.width / 2, 0)), Balloon.Position.above)
    }.isSuccess
    return if (shown) balloon else null
}

// ---- 画 ----

/**
 * 气泡里那一块。`internal` 是为了让渲染探针能**离屏画一张图**看观感 ——
 * 真机上它是个浮层窗口，没上屏就截不到（同 `RunDetail` 那几屏的做法）。
 */
internal fun bubbleContent(model: SyncBubbleModel, onClick: () -> Unit): JComponent {
    val box = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        // 透明：底与圆角由平台气泡自己画（跟着主题走，不必自己挑颜色）
        isOpaque = false
        border = JBUI.Borders.empty(8, 11, 9, 11)
    }
    box.add(titleLabel(model.title))
    model.failed?.let { box.add(failedLabel(it)) }
    if (model.rows.isNotEmpty()) {
        box.add(rowsBox(model.rows))
    }
    model.more?.let { box.add(moreLabel(it)) }

    // 整块可点：**子件一个都不能漏** —— Swing 的点击只发给最深的那个组件，
    // 落在标签上的点击不会冒到面板上（`TaskStopButton` 的注释里记过这条）
    wireClick(box, onClick)
    return box
}

private fun titleLabel(text: String): JComponent = JBLabel(text).apply {
    alignmentX = Component.LEFT_ALIGNMENT
}

private fun failedLabel(text: String): JComponent = JBLabel(text).apply {
    alignmentX = Component.LEFT_ALIGNMENT
    foreground = dangerColor()
    border = JBUI.Borders.emptyTop(2)
}

/**
 * 文件名一格能有多宽。
 *
 * **气泡的宽度 = 这三行里最宽的那条**，不封顶的话一条 `src/main/kotlin/...` 的长路径
 * 就能把它撑到屏幕外面去（浮层是贴卡片弹的，右边缘没有东西挡着）。
 * 超了走 [middleTruncate]：文件名留着、目录中段省略 —— 让 `JLabel` 自己截尾巴不行，
 * 那是静默的（这正是仓库里反复出现的那类"装进 IDE 才发现"的错）。
 */
private const val SYNC_BUBBLE_TEXT_W = 280

private fun rowsBox(rows: List<SyncBubbleRow>): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    border = JBUI.Borders.emptyTop(4)
    rows.forEach { row ->
        add(
            JBLabel().apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size - 1)
                // **正文色，不是主题那个"次要文字色"**（2026-09-28 用户："弹框里的字体颜色太暗了"）。
                // 那几行是这个气泡的**内容**（要逐条认"过去的是不是我改的文件"），
                // 而 `getInactiveTextColor()` 的语义是"可以看不清"，在深色底上只有 1.9:1
                // （量过，见 `SyncBubbleRenderProbe` 的打点）。
                //
                // 也没"压一档"：深色主题下这一步没有余量 —— 同一块底上，正文色自己
                // 才 4.4:1，压 15% 就掉到 3.7:1。**层级由等宽字体与小一号承担**，
                // 不再由颜色承担（这行字得先看得清，才谈得上次要）。
                foreground = UIUtil.getLabelForeground()
                border = JBUI.Borders.emptyBottom(1)
                // **先定字体再量**：等宽与默认字体的宽度不一样，先量就白量了
                val shown = if (row.deleted) "− ${row.text}" else row.text
                text = middleTruncate(shown, getFontMetrics(font), JBUI.scale(SYNC_BUBBLE_TEXT_W))
            }
        )
    }
}

private fun moreLabel(text: String): JComponent = JBLabel(text).apply {
    alignmentX = Component.LEFT_ALIGNMENT
    // 小一号的字：主题那个 focusColor 在深色下只有 4.2:1，正压在门槛上，这里往后走一档
    foreground = accentTextOn(bubbleBackdrop())
    font = font.deriveFont(font.size2D - 1f)
    border = JBUI.Borders.emptyTop(3)
}

/**
 * 气泡自己的底 —— 挑强调色时要有个底（[accentTextOn] 吃一个底色）。
 *
 * 它是**平台画的**（我们只往里放内容，`isOpaque = false`）：填充色在平台的
 * `BalloonBuilder` 默认值里，我们这侧拿不到真值。按**提示层那一族**估：
 * 气球连圆角都是从这个族里取的（`ToolTip.arc`，在 `BalloonImpl` 的字节码里核过），
 * 底色用同一族的值，深浅两种主题下都在一档里（Darcula 量到的是 `#4b4d4d`）。
 *
 * 正文那几行不走这里 —— 它们直接用 [UIUtil.getLabelForeground]：**平台自己的正文色
 * 在哪种主题下都够**，不必赌一个估出来的底（见 `rowsBox` 里那段）。
 */
private fun bubbleBackdrop(): Color = UIUtil.getToolTipBackground()

/** 这一整块都能点开（含每一个子标签）。 */
private fun wireClick(root: Container, onClick: () -> Unit) {
    val adapter = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) = onClick()
    }
    fun walk(c: Container) {
        for (child in c.components) {
            if (child is JComponent) {
                child.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                child.addMouseListener(adapter)
            }
            if (child is Container) walk(child)
        }
    }
    walk(root)
}
