package com.ccoder.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Point
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 弹层最多显示几行。再多的靠继续打字缩小前缀。 */
internal const val COMPLETION_MAX_ROWS = 8

/** 弹层宽度（未缩放 px）。 */
internal const val COMPLETION_WIDTH = 320

/** 一行的行高（未缩放 px）。只用于压住 BoxLayout 的最大高度。 */
private const val ROW_HEIGHT = 20

/**
 * 只留前几项。
 *
 * 上限必须落在**候选**这一层而不是绘制层：绘制层截断的话，高亮索引还能
 * 走到看不见的位置上（按 ↓ 按到第 10 项，屏幕上没有任何一格亮着）。
 */
internal fun visibleCandidates(items: List<CompletionItem>): List<CompletionItem> =
    items.take(COMPLETION_MAX_ROWS)

/** 行里那个间隔。行文本与"塞不下时怎么截"共用一份 —— 免得两处各拼一遍然后漂移。 */
internal const val ROW_SEPARATOR = "  ·  "

/** 一行的左右内边距（未缩放 px），算可用宽度时要从弹层宽度里减掉。 */
private const val ROW_PADDING = 20

/**
 * 一行显示什么。测试与探针共用 —— 免得两边各拼一遍然后漂移。
 */
internal fun completionRowText(item: CompletionItem): String =
    item.description?.let { "${item.display}$ROW_SEPARATOR$it" } ?: item.display

/**
 * 塞不进弹层宽度时怎么让位。
 *
 * **按像素量，不数字符**：2026-09-15 的探针图上连着栽了两次 —— 按字符数估的预算，
 * 53 个字符的行画得下、50 个的那行反而被裁；比例字体下字符数与宽度不成比例。
 *
 * 让位的次序有讲究：
 *  1. **先让路径**（从左边砍，留下文件名那一头）。容器默认从**尾部**裁，裁掉的
 *     正是文件名 —— 而"是哪个文件"是这一列存在的全部理由；
 *  2. 路径让到"…"都放不下，才动名字（从右边砍）。
 *
 * 只有**符号行**走这条（`symbol != null`）：它的行文本是两段。其它行的 display
 * 就是内容本身，维持原样 —— 免得顺手改掉命令 / 文件 / 预设那三组的既有观感。
 */
internal fun rowTextFor(item: CompletionItem, metrics: FontMetrics, maxWidth: Int): String {
    val path = item.description
    if (item.symbol == null || path == null) return completionRowText(item)

    val head = "${item.display}$ROW_SEPARATOR"
    if (metrics.stringWidth(head + path) <= maxWidth) return head + path

    // **文件名是这一列的下限**，先给它占住位置（其余部分才轮到让）
    val base = "…/" + path.substringAfterLast('/')

    if (metrics.stringWidth(head + base) <= maxWidth) {
        // 名字没被挤到，还能把路径多给回一点（第一个放得下的就是最长的那个）
        val room = maxWidth - metrics.stringWidth(head)
        for (kept in path.length - 1 downTo 1) {
            val tail = path.takeLast(kept)
            // 从尾段里第一段目录处切起：`…/app/parse_test.py` 比 `…/test.py` 有用
            val cut = tail.indexOf('/')
            val candidate = if (cut >= 0) "…" + tail.substring(cut) else "…/$tail"
            if (metrics.stringWidth(candidate) <= room) return head + candidate
        }
        return head + base
    }

    // 名字太长，挤到了文件名：**先砍名字**（文件名留着）——
    // 名字截一半还能认出是哪个，文件名没了就什么都不知道了
    for (kept in item.display.length - 1 downTo 1) {
        val name = item.display.take(kept) + "…"
        if (metrics.stringWidth("$name$ROW_SEPARATOR$base") <= maxWidth) return "$name$ROW_SEPARATOR$base"
    }

    // 连"名字 + 文件名"都放不下（弹层被缩到极窄）：留一个省略号，至少不是空白
    return "…"
}

/**
 * 弹层内容。
 *
 * **不可聚焦**：弹层出现时用户还在打字，焦点跑掉的话接下来的字符就进了
 * 弹层而不是输入框。[CompletionPopup] 的 `setFocusable(false)` 是同一件事的
 * 另一半，这里能断言的是内容组件这一半（`JPanel` 默认就不可聚焦）。
 *
 * 分组标题只在**换组时**插一条，不是每行都挂 —— 45 条命令每行前面顶一个
 * 「内置」，读起来全是重复。
 */
internal fun buildCompletionList(
    items: List<CompletionItem>,
    selected: Int,
    status: String? = null,
): JComponent {
    val column = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
    }

    // 一行候选都没有时才挂状态行（符号那条：正在搜 / 搜不成）。
    // **它不是候选**：不进 completionItems，所以高亮恒为 0、上下键与回车都不接管 ——
    // 屏幕上没有可选的东西，就不该让按键以为有（见 ClaudePanel 的按键监听）
    if (items.isEmpty() && status != null) column.add(statusRow(status))

    var lastGroup: String? = null
    items.forEachIndexed { index, item ->
        if (item.group != null && item.group != lastGroup) {
            column.add(groupHeader(item.group))
            lastGroup = item.group
        }
        column.add(row(item, index == selected))
    }

    return JPanel(BorderLayout()).apply {
        isOpaque = false
        // **显式**不可聚焦。不写这一句的话 `Container.isFocusable()` 会把子树
        // 扫一遍再回报，于是"能不能抢焦点"取决于子组件 —— 而这条路必须由
        // 本组件自己说了算：弹层出现时用户还在打字，焦点被带走一个字都进不去
        isFocusable = false
        add(column, BorderLayout.CENTER)
        // 宽度定死，**高度取内容的真实首选高**。自己按行数估会漏掉分组标题
        // 和字体的实际行高，末一行被容器裁掉 —— 探针图里踩到过（第二版图里
        // 最末那行 `brainstorming` 整个不见了）
        preferredSize = Dimension(JBUI.scale(COMPLETION_WIDTH), column.preferredSize.height)
    }
}

private fun groupHeader(text: String): JComponent = JLabel(text).apply {
    foreground = UIUtil.getInactiveTextColor()
    font = font.deriveFont(font.size2D - 1f)
    border = JBUI.Borders.empty(2, 8, 1, 8)
    alignmentX = Component.LEFT_ALIGNMENT
}

/**
 * 状态行：正在搜、或者搜不成时，弹层里唯一的那一行。
 *
 * 缩进与候选行（12）对齐 —— 它站的是候选的位置，只是暂时没得选。
 * 灰色 + 小一号，与分组标题同一套"这不是内容、是说明"的视觉语言。
 */
private fun statusRow(text: String): JComponent = JLabel(text).apply {
    foreground = UIUtil.getInactiveTextColor()
    font = font.deriveFont(font.size2D - 1f)
    border = JBUI.Borders.empty(2, 12, 2, 8)
    alignmentX = Component.LEFT_ALIGNMENT
    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))
}

/**
 * 把查询命中的字符**加粗**（CLI 的 TUI 也是这么标的）。
 *
 * 两条分寸：
 *
 * - **只在这行确实以 [CompletionItem.display] 打头时才加。** 行文本可能被上面的
 *   让位逻辑砍过（`…/app/x.py`），那时下标对不上位置，标错比不标糟。
 * - **没有命中就原样返回纯文本**：JLabel 一进 HTML 模式就换一套排版规则，
 *   不该为不相干的行付这个代价。
 *
 * 加粗而不是上色：文件名这一列本来就长，再加颜色会和"选中行底色"抢注意力；
 * 而且加粗在两套主题下都不用挑颜色。
 */
internal fun highlightedRowText(text: String, item: CompletionItem): String {
    if (item.hits.isEmpty() || !text.startsWith(item.display)) return text
    val hitSet = item.hits.toHashSet()
    val sb = StringBuilder("<html>")
    var i = 0
    while (i < text.length) {
        val hit = i in hitSet
        val start = i
        while (i < text.length && (i in hitSet) == hit) i++
        val chunk = escapeHtml(text.substring(start, i))
        if (hit) sb.append("<b>").append(chunk).append("</b>") else sb.append(chunk)
    }
    return sb.append("</html>").toString()
}

private fun row(item: CompletionItem, selected: Boolean): JComponent {
    val label = JLabel()
    // 可用宽度 = 弹层定宽 - 这一行的左右内边距。字体要从**标签自己**身上取：
    // 它才是最终画字的那份（LaF / 缩放都会影响它），见 [rowTextFor]
    label.text = highlightedRowText(
        rowTextFor(item, label.getFontMetrics(label.font), JBUI.scale(COMPLETION_WIDTH - ROW_PADDING)),
        item,
    )

    return label.apply {
        // 底色就是"选中"的全部视觉信号 —— 单测只能断言它有没有，好不好看见探针
        isOpaque = selected
        if (selected) background = selectionColor()
        border = JBUI.Borders.empty(2, 12, 2, 8)
        alignmentX = Component.LEFT_ALIGNMENT
        // BoxLayout 下不压住最大高度的话，行会被拉高，行数一多就溢出弹层
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))
    }
}

/**
 * 弹层该出现在哪个 y（屏幕坐标）。
 *
 * 与 [popupAnchorY] 同一套退回逻辑：**向下优先，放不下往上翻，都放不下贴屏顶**。
 * 输入框在工具窗口底部，所以"往上翻"是常态而不是边角情况。
 */
internal fun completionPopupY(
    caretTop: Int,
    caretBottom: Int,
    popupHeight: Int,
    screenTop: Int,
    screenBottom: Int,
    gap: Int,
): Int {
    val below = caretBottom + gap
    if (below + popupHeight <= screenBottom) return below

    val above = caretTop - gap - popupHeight
    if (above >= screenTop) return above

    return screenTop
}

/**
 * 非聚焦的补全弹层。
 *
 * 与 [ClaudePanel] 那三个 `showTogglePopup` 浮层不是一回事：那三个是
 * **点击驱动**、锚点是控件；这个是**键盘驱动**、锚点是光标。共用的只有
 * 定位计算。
 *
 * `setCancelOnClickOutside(false)`：开着的话，点输入框想挪光标会被弹层吃掉，
 * 而用户只是想改个错字。关层由 ClaudePanel 的文档/光标监听负责。
 *
 * `setCancelKeyEnabled(false)`：不让弹层注册全局 Esc —— 那个键要留给输入框，
 * 由 [completionKey] 判成 [CompletionKey.Dismiss]。
 */
internal class CompletionPopup {

    private var popup: JBPopup? = null

    val isOpen: Boolean get() = popup != null

    fun show(
        anchor: JComponent,
        caret: Rectangle,
        items: List<CompletionItem>,
        selected: Int,
        status: String? = null,
    ) {
        hide()
        if (!anchor.isShowing) return

        val content = buildCompletionList(items, selected, status)
        val at = anchor.locationOnScreen
        val screen = anchor.graphicsConfiguration?.bounds ?: Rectangle(0, 0, 1920, 1080)
        val height = popupHeightOf(null, content.preferredSize)

        val y = completionPopupY(
            caretTop = at.y + caret.y,
            caretBottom = at.y + caret.y + caret.height,
            popupHeight = height,
            screenTop = screen.y,
            screenBottom = screen.y + screen.height,
            gap = JBUI.scale(4),
        )

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(false)
            .setCancelKeyEnabled(false)
            .createPopup()
            .also { it.showInScreenCoordinates(anchor, Point(at.x + caret.x, y)) }
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }
}

/**
 * 选中行的底色。
 *
 * 与 [lineColor] / [focusColor] 同一个写法：取平台的具名颜色，取不到时
 * 退回一对浅色/深色各一的值。
 */
private fun selectionColor(): Color = JBColor.namedColor(
    "List.selectionBackground",
    JBColor(Color(0x35, 0x74, 0xF0), Color(0x54, 0x8A, 0xF7)),
)
