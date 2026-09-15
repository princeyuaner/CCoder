package com.ccoder.ui

import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiCompatibleDataProvider

import com.ccoder.settings.SendShortcut
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel

// ---- 输入框高度 ----

/**
 * 输入框的最小行数 —— 它同时也是"拖到最小能有多矮"的地板。
 *
 * **不做自动长高**（2026-09-11 用户明确要求）：高度由用户拖分隔条决定，
 * 输入框只负责在拖出来的空间里撑满。这样"我写多长"和"它占多高"两件事
 * 解耦，不会因为粘一段长文本就把面板顶开。
 *
 * 取 1 而不是 3：它经 preferredSize 变成布局的最小高度，而这个最小高度会
 * 反过来顶住分隔条的默认比例 —— 设为 3 时最小值约 97px，把默认比例往下调
 * 也压不下去，表现为"改了默认高度却没变"。
 */
internal const val COMPOSER_MIN_ROWS = 1

// ---- 输入框本体 ----

/**
 * 竖向撑满视口的输入框。
 *
 * `JTextArea` 默认不随视口长高（`getScrollableTracksViewportHeight()`
 * 返回 false），把它放进滚动面板后，拖高输入区只会多出一片空白 ——
 * 输入框本身纹丝不动，看起来像"拖了没用"。
 */
internal class ComposerTextArea(rows: Int, cols: Int) : JBTextArea(rows, cols), UiCompatibleDataProvider {

    /**
     * Ctrl+V 的接管口（见 [imagePasteProvider]）。
     *
     * **为什么需要它**：IDE 里 Ctrl+V 走的是平台的 `$Paste` action，而那个 action
     * 是从**数据上下文**里取 [PasteProvider] 再调的 —— Swing 的 `TransferHandler`
     * 那条路在 IDE 里根本不会被执行（2026-09-15 实测：处理器装上了，一次都没被调到）。
     *
     * **必须是 [UiCompatibleDataProvider]，不是普通的 `DataProvider`**：后者平台
     * 会从后台线程随便问（所以要求线程安全），于是 2026-09-15 那一版实现完
     * **一次都没被问到** —— 平台自己的 `EditorTextField` 实现的就是前者。
     *
     * 只在"剪贴板里只有图"时回答（见 [imagePasteData]），其余交回平台。
     */
    var pasteProvider: PasteProvider? = null

    /**
     * "从剪贴板收一张图"的回调 —— 给 [PasteImageAction] 用（Ctrl+V 那条自己占住的
     * 路）。由 [installImagePaste] 接上。
     */
    var imagePasteAttach: (() -> Unit)? = null

    override fun uiDataSnapshot(sink: DataSink) {
        // 无条件放：这个 key 只是"焦点在能收图的输入框上"的标记，可不可用由
        // PasteImageAction.update 决定（它还要看剪贴板）
        imagePasteAttach?.let { sink[IMAGE_PASTE_ATTACH] = it }
        val provider = imagePasteData(PlatformDataKeys.PASTE_PROVIDER.name, pasteProvider)
        if (provider != null) {
            LOG.info("贴图：把 PASTE_PROVIDER 交给平台（剪贴板里只有图）")
            sink[PlatformDataKeys.PASTE_PROVIDER] = provider
        }
    }

    override fun getData(dataId: String): Any? = imagePasteData(dataId, pasteProvider)

    override fun getScrollableTracksViewportHeight(): Boolean = true
}

private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance("com.ccoder.ui.ComposerTextArea")

// ---- 发送快捷键 ----

/**
 * 这个按键该不该触发发送。
 *
 * 只判这几个键：**Shift+Enter 在两种约定下都留给换行** —— 用户在
 * Ctrl+Enter 模式下想换行时，多半会先试 Shift+Enter。
 */
internal fun isSendKey(
    keyCode: Int,
    shiftDown: Boolean,
    ctrlDown: Boolean,
    shortcut: SendShortcut,
): Boolean {
    if (keyCode != KeyEvent.VK_ENTER) return false
    return when (shortcut) {
        SendShortcut.ENTER -> !shiftDown
        SendShortcut.CTRL_ENTER -> ctrlDown
    }
}

// ---- 输入区布局 ----

private const val CARD_ARC = 10

/**
 * 输入区是一个**圆角卡片**（方案 A）。
 *
 * 边框画在卡片上，输入框自己是裸的 —— 这是这次重设计的核心取舍。
 * 原来的做法是一个 1px 直角矩形直接框住文字，看起来像表单字段而不像
 * 对话输入；而"分不清输入区与转写区"那个问题，改由卡片的整条上沿来回答，
 * 边界反而更明确（它包住的是整个输入区，不只是文字那一块）。
 *
 * 聚焦时描边变强调色。**只改颜色、不加粗**：加粗会让内容位移一个像素，
 * 在用户正在打字的时候抖一下。
 */
internal class ComposerCard : JPanel(BorderLayout()) {

    private var focused = false

    private val focusWatcher = java.beans.PropertyChangeListener { ev ->
        val owner = ev.newValue as? java.awt.Component
        setFocused(owner != null && javax.swing.SwingUtilities.isDescendingFrom(owner, this))
    }

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder({ if (focused) focusColor() else lineColor() }, JBUI.scale(CARD_ARC)),
            JBUI.Borders.empty(4, 6, 5, 6),
        )
    }

    fun setFocused(value: Boolean) {
        if (focused == value) return
        focused = value
        repaint()
    }

    /**
     * 焦点落在卡片里的任何一个子组件（输入框、发送按钮）上，都算卡片聚焦。
     *
     * 监听器挂在全局的 KeyboardFocusManager 上，所以必须在离开层级时摘掉 ——
     * 否则每开一次工具窗口就漏一个监听器，而它捕获着这个面板。
     */
    override fun addNotify() {
        super.addNotify()
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("focusOwner", focusWatcher)
    }

    override fun removeNotify() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener("focusOwner", focusWatcher)
        super.removeNotify()
    }
}

/**
 * 卡片内部自上而下三段：
 *
 *   NORTH  附件带（待发的图；没有图时它自己收起来，一分高度都不占）
 *   CENTER 输入框（撑满可用高度）
 *   SOUTH  控件工具栏（发送/停止在右，左侧留给模型切换等）
 *
 * NORTH 原先挂着"连接状态 + 上下文 + 任务条"那一行。那四样拆成独立卡片
 * 挪到输入卡**外面**之后这一层空了出来，2026-09-15 贴图把它用掉：
 * 附件带必须在**卡片内部**——它是这条消息的一部分，不是状态卡那种常驻控件
 * （设计稿 docs/design/image-attach.html 方案甲）。
 *
 * 工具栏放 SOUTH 而不是把按钮摆在输入框右边（原来那样）—— 右边放不下
 * 以后的模型切换、权限模式；摆下面则加控件只是往工具栏左侧添，不必再动结构。
 */
internal fun buildComposerCard(
    inputScroll: JComponent,
    toolbar: JComponent,
    attachments: JComponent? = null,
): ComposerCard = ComposerCard().apply {
    if (attachments != null) add(attachments, BorderLayout.NORTH)
    add(inputScroll, BorderLayout.CENTER)
    add(toolbar, BorderLayout.SOUTH)
}

/** 卡片的常态描边。与 IDE 给输入类控件用的一条线同源。 */
internal fun lineColor(): Color = JBColor.namedColor("Component.borderColor", JBColor.border())

/** 聚焦描边。取平台色，取不到时退回 New UI 的一对蓝（浅色/深色各一）。 */
internal fun focusColor(): Color = JBColor.namedColor(
    "Component.focusColor",
    JBColor(Color(0x35, 0x74, 0xF0), Color(0x54, 0x8A, 0xF7)),
)

/**
 * 可展开指示符。放在可点控件的文字末尾。
 *
 * **挤掉它，这条就看不出能点了** —— 所以 [RunStripView] 把它排除在宽度预算之外。
 */
internal const val EXPAND_CARET = " ▾"

/**
 * 危险状态的警示色。权限卡片与权限模式标签共用。
 *
 * 是同一个 `val` 而不是每次构造：测试要用它做**同一性**断言
 * （"这个标签现在用的是警示色"），每次返回新实例的话那个断言就没有意义了。
 */
private val WARNING_COLOR: Color = JBColor(Color(0xD8, 0x43, 0x15), Color(0xFF, 0x8A, 0x65))

internal fun warningColor(): Color = WARNING_COLOR

/**
 * 危险状态的色。状态卡的指示器与权限卡共用。
 *
 * 与 [SessionList] 里私有的 `DELETE_DANGER` 色值相同 —— 那处先留着不动，
 * 动它要连带改会话列表和它的测试，与本次改动无关。
 */
internal fun dangerColor(): Color = DANGER_COLOR

private val DANGER_COLOR: Color = JBColor(Color(0xC0, 0x39, 0x2B), Color(0xDB, 0x5C, 0x5C))
