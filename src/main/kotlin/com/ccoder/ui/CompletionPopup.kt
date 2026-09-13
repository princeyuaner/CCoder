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

/**
 * 一行显示什么。测试与探针共用 —— 免得两边各拼一遍然后漂移。
 */
internal fun completionRowText(item: CompletionItem): String =
    item.description?.let { "${item.display}  ·  $it" } ?: item.display

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
internal fun buildCompletionList(items: List<CompletionItem>, selected: Int): JComponent {
    val column = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
    }

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

private fun row(item: CompletionItem, selected: Boolean): JComponent = JLabel(completionRowText(item)).apply {
    // 底色就是"选中"的全部视觉信号 —— 单测只能断言它有没有，好不好看见探针
    isOpaque = selected
    if (selected) background = selectionColor()
    border = JBUI.Borders.empty(2, 12, 2, 8)
    alignmentX = Component.LEFT_ALIGNMENT
    // BoxLayout 下不压住最大高度的话，行会被拉高，行数一多就溢出弹层
    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))
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

    fun show(anchor: JComponent, caret: Rectangle, items: List<CompletionItem>, selected: Int) {
        hide()
        if (!anchor.isShowing) return

        val content = buildCompletionList(items, selected)
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
