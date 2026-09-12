package com.ccoder.ui

import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel

/** 标题后面那个表示"可以展开"的小三角。 */
private const val CARET = " ▾"

/**
 * 顶部的会话标签。可点，点开弹会话列表。
 *
 * 形状照 [ModeLabel]：同样的可点 + 光标 + 展开三角。三处弹出层
 * （任务详情、权限模式、会话列表）共用同一套交互，用户学一次就行。
 *
 * 它只负责**显示**。切换什么时候真的发生，由 [ClaudePanel] 决定 ——
 * 那边等 sidecar 的 ready 回执，不等的话标签会显示一个没生效的会话。
 */
internal class SessionLabel(private val onOpen: () -> Unit) : JLabel() {

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIUtil.getInactiveTextColor()
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpen()
                }
            }
        )
        setTitle(null, enabled = true)
    }

    /**
     * @param text 会话标题。null = 新会话（还没有标题）
     * @param enabled false 时变灰并**拒绝点击** —— 忙时用
     */
    fun setTitle(text: String?, enabled: Boolean) {
        // 新会话用斜体占位：留空会让人以为标签还没加载出来
        val label = text?.takeIf { it.isNotBlank() } ?: "新会话"
        this.text = label + CARET
        font = UIUtil.getLabelFont().deriveFont(if (text == null) Font.ITALIC else Font.PLAIN)
        foreground = if (enabled) UIUtil.getInactiveTextColor() else UIUtil.getLabelDisabledForeground()
        cursor = if (enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        repaint()
    }
}
