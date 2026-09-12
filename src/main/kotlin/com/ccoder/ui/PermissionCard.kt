package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 非模态权限卡片。
 *
 * 落实 spec §6.2 的三条 SDK 明文规则：
 *
 * 规则②（sdk.d.ts:245-248）：不能被误触批准。批准只能显式点击按钮，
 * **不绑任何键盘快捷键**（按钮刻意不设 mnemonic —— 助记符就是键盘捷径）；
 * 卡片获得焦点时焦点落在"拒绝"上。
 *
 * 规则③（sdk.d.ts:249-253）：那个"不再询问"的入口仅在
 * [PermissionOptions.allowsAlwaysAllow] 为真时**渲染**（不是渲染后禁用）。
 *
 * 规则①由调用方保证 —— 任何终止路径都要 resolve，见 [PermissionQueue.cancelAll]。
 *
 * 按钮写的是「本会话不再询问」，回传的也就必须只是本会话的东西（[PermissionDecision.stopAsking]）
 * —— 按钮上写的话和它真正授权的范围不一致，比按钮不好用严重得多。
 */
class PermissionCard(
    private val permission: SidecarMessage.Permission,
    queuedCount: Int,
    private val onDecide: (PermissionDecision) -> Unit,
) : JPanel(BorderLayout()) {

    private val denyButton = JButton("拒绝").apply {
        // 不设 mnemonic：助记符等于键盘捷径，违反规则②
        addActionListener {
            onDecide(PermissionDecision(allow = false, updatedPermissions = null, message = "用户拒绝"))
        }
    }

    private val allowButton = JButton(
        permission.displayName?.takeIf { it.isNotBlank() } ?: "允许"
    ).apply {
        addActionListener {
            onDecide(PermissionDecision(allow = true, updatedPermissions = null, message = null))
        }
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT, 1),
            JBUI.Borders.empty(8),
        )
        background = CARD_BG
        isOpaque = true

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(PermissionOptions.primaryText(permission)).apply {
                font = font.deriveFont(font.style or Font.BOLD)
                alignmentX = LEFT_ALIGNMENT
            })
            permission.description?.takeIf { it.isNotBlank() }?.let { text ->
                add(JBLabel(text).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            // blockedPath 单独高亮 —— 这是"为什么问我"的关键信息（spec §6.5）
            permission.blockedPath?.let { path ->
                add(JBLabel("触发路径：$path").apply {
                    foreground = WARN
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            permission.decisionReason?.let { reason ->
                add(JBLabel("原因：$reason").apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            if (queuedCount > 0) {
                add(JBLabel("还有 $queuedCount 个待确认").apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
        }

        val inputArea = JBTextArea(permission.input.toString()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            rows = 3
            foreground = UIUtil.getInactiveTextColor()
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            // 规则③：不满足条件时**不渲染**，而非渲染后禁用。
            //
            // 注意这个闸卡的性质变了：按钮不再回传 suggestions，所以
            // "suggestions 非空"那一半不再是**能不能**持久化的判断，
            // 而是沿用 CLI 那句"这个请求值不值得给不再问的入口"。
            // 保守留着：放宽它等于多给入口，宁可少给。
            if (PermissionOptions.allowsAlwaysAllow(permission)) {
                add(JButton(AUTO_ALLOW_LABEL).apply {
                    addActionListener {
                        onDecide(
                            PermissionDecision(
                                allow = true,
                                // 不传 suggestions：按钮承诺的范围是"本会话"，
                                // 往 settings.local.json 落一条持久规则比承诺的大
                                updatedPermissions = null,
                                message = null,
                                stopAsking = true,
                            )
                        )
                    }
                })
            }
            add(denyButton)
            add(allowButton)
        }

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(Box.createVerticalStrut(6))
            add(JBLabel("原始输入").apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = LEFT_ALIGNMENT
            })
            add(JBScrollPane(inputArea).apply {
                border = JBUI.Borders.empty()
                alignmentX = LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 80)
            })
            add(Box.createVerticalStrut(6))
            add(buttons.apply { alignmentX = LEFT_ALIGNMENT })
        }

        add(center, BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)

        // 规则②：焦点默认落在"拒绝"。延迟到布局完成后再请求，
        // 否则卡片还未加入组件树，requestFocusInWindow 会静默失败。
        SwingUtilities.invokeLater { denyButton.requestFocusInWindow() }
    }

    private companion object {
        val ACCENT = JBColor(0xFFA000, 0xFFB74D)
        // 与权限模式标签共用 —— 两者说的是同一件事（"这里要留意"），
        // 各存一份迟早会漂移
        val WARN = warningColor()
        val CARD_BG = JBColor(0xFFF8E1, 0x3E2C1C)
    }
}
