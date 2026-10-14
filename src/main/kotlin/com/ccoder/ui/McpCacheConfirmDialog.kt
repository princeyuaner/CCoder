package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 「MCP 还没连完」这一发拦下来之后，用户能回的两个决定。
 *
 * 是个**密封的两出口**：没有"取消"之外的第三态（关窗/Esc 都归 [Wait]）——
 * 输入框里的原文一个字都没动过，用户回去接着改就是。
 */
internal sealed interface CacheWarnDecision {

    /** 照发：这一发立刻按原样出去；[mute] = 勾了「本会话不再问」。 */
    data class SendAnyway(val mute: Boolean) : CacheWarnDecision

    /** 等它（含 Esc、关窗）：什么都不做。安全的那一个 —— 默认焦点也落在它身上。 */
    object Wait : CacheWarnDecision
}

/**
 * 确认框的内容卡（2026-10-14 选型台 B 案，用户选定）。
 *
 * 形态照 `PermissionCard` 的分工：卡片自带按钮、弹窗只当"框"（`DialogWrapper`
 * 那对 OK/Cancel 不画）。文案每开一次现取一次 —— 弹窗随开随建，不走"键记在
 * 控件上"那套（同 `SchedulePicker` 的口径；`LocalizedText` 头注把这条边界写清楚过）。
 *
 * 三颗控件的分工与选型台画的那一版一致：
 *  - **[waitButton]**「等它」在左、**默认焦点**（蓝框那颗）—— 用户是刚按了回车
 *    走到这里的，焦点落在安全那颗上，第二下回车不该把钱花出去；
 *  - **[sendButton]**「照发」在右；
 *  - **[muteBox]**「本会话不再问」在最左（点照发时它才生效 —— 它说的是"以后
 *    别再问"，不是"这一发别发"）。
 */
internal class McpCacheConfirmCard(
    names: String,
    onDecide: (CacheWarnDecision) -> Unit,
) : JPanel(BorderLayout()) {

    internal val waitButton: JButton = JButton().localizedText("mcp.cacheWarn.wait")

    internal val sendButton: JButton = JButton().localizedText("mcp.cacheWarn.send")

    internal val muteBox: JCheckBox = JCheckBox().localizedText("mcp.cacheWarn.mute")

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 14, 12, 14)

        // 正文：有名字就带上（弹窗有宽度，名字一直放得下 —— 带子时代放不下才省掉它）
        add(
            JBLabel(
                if (names.isBlank()) {
                    CcoderText.text("mcp.cacheWarn.dialog.bodyBare")
                } else {
                    CcoderText.text("mcp.cacheWarn.dialog.body", names)
                }
            ),
            BorderLayout.CENTER,
        )

        waitButton.addActionListener { onDecide(CacheWarnDecision.Wait) }
        sendButton.addActionListener { onDecide(CacheWarnDecision.SendAnyway(muteBox.isSelected)) }

        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(12)
                add(muteBox, BorderLayout.WEST)
                add(
                    JPanel().apply {
                        isOpaque = false
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        // 等它在前（左）· 照发在后 —— 与选型台画的同序
                        add(waitButton)
                        add(Box.createHorizontalStrut(JBUI.scale(8)))
                        add(sendButton)
                    },
                    BorderLayout.EAST,
                )
            },
            BorderLayout.SOUTH,
        )
    }
}

/**
 * 「MCP 还没连完 · 这一发会全价重算」的居中确认框（B 案）。
 *
 * ## 与旧行为的关系
 *
 * 前身是那条全宽带子上的"就地确认"（2026-10-13，当时刻意**不弹框**）。2026-10-14
 * 用户在看板里改选居中模态，于是"确认不是装饰、但也不弹框"这条旧规矩在这里
 * 正式翻掉 —— 按钮的语义一个没变，变的是它从哪冒出来。
 *
 * ## 几条从旧实现与邻居（PermissionDialog）直接搬过来的规矩
 *
 * - **焦点落在安全那颗**（[getPreferredFocusedComponent] →「等它」）；[rootPane]
 *   的默认按钮清掉 —— 回车只可能落到眼前这颗，不会盲发。
 * - **Esc / 关窗 = 等它**（[doCancelAction]）：拦下来的输入原文未动，用户回去
 *   接着改；这也让 [closeSilently] 与"用户选择"共用同一条安全出口。
 * - **先关框、再回决定**（[decide] 的顺序同 PermissionDialog）：回调里可能立刻
 *   又发起新的模态动作（照发 → 真正发送），框没关就会叠出嵌套模态。
 * - **只回一次**：[decided] 一个标志位管住"连点两下"与"静默关掉后再有人回话"。
 *
 * ## 测试里别调 `show()`
 *
 * 同 PermissionDialog：平台的 HeadlessDialog 在单测模式下会立刻关掉，真模态循环
 * 也起不来。测试只构造 + 走控件上真挂着的监听器。
 */
internal class McpCacheConfirmDialog(
    project: Project,
    names: String,
    private val onDecide: (CacheWarnDecision) -> Unit,
) : DialogWrapper(project) {

    /** 弹窗的内容本体。测试与探针都从它进去找按钮。 */
    internal val card = McpCacheConfirmCard(names) { decide(it) }

    /** 一旦置位，这个框不再回任何决定（连点两下只该回一条；静默关掉后同理）。 */
    private var decided = false

    init {
        title = CcoderText.text("mcp.cacheWarn.dialog.title")
        // 内容两行字加一行按钮，尺寸静态 —— 不给拉伸（旧带子的确认态也是这么长出来的）
        isResizable = false
        init()

        // 焦点在「等它」上时，平台的回车钩子点的就是它（Windows/Linux 上点的是
        // **有焦点**的那颗）；再把默认按钮清掉，双保险：回车不可能盲发
        rootPane.defaultButton = null

        // Esc 也必须是"等它"。自备一条绑定 —— 平台那条要等 show() 才注册，
        // 没有 show() 的测试查不到它（详见 bindEscapeToDeny 的说明）
        rootPane.bindEscapeToDeny { doCancelAction() }
    }

    override fun createCenterPanel(): JComponent = card

    /** 不画 DialogWrapper 自带的按钮对 —— 按钮在卡片里。 */
    override fun createActions(): Array<Action> = emptyArray()

    /** 连底部那条空槽也不要。 */
    override fun createSouthPanel(): JComponent? = null

    override fun getPreferredFocusedComponent(): JComponent = card.waitButton

    /** X 与 Esc 都落到这里 —— 关掉就是「等它」（输入原文未动，什么都不用回滚）。 */
    override fun doCancelAction() = decide(CacheWarnDecision.Wait)

    /**
     * 静默关掉：**还没轮到它出场**（排在 [showModal] 那一拍里）或**正开着**时，
     * MCP 先落定了 —— 那时"这一发会亏"这句话已经不成立，框留着是句假话。
     *
     * 两条分支都要：还没 show 的框直接作废（调用方随后不再让它出来），
     * 正开着的那就关掉它（不回调 —— 用户的输入本来就没动过，只是问题没了）。
     */
    fun closeSilently() {
        if (decided) return
        decided = true
        if (isShowing) close(CLOSE_EXIT_CODE)
    }

    /** 还没回过决定吗 —— `showModal` 那一拍里用它在"出来"之前再核一次。 */
    fun isDecided(): Boolean = decided

    /**
     * 回决定并关框。
     *
     * **先关框、再回决定**：`onDecide` 那条路会立刻发起发送（照发），发送路径上
     * 可能再弹别的模态 —— 这个框得先真的关掉了，否则就是嵌套模态框
     * （见 PermissionDialog.decide 的同一条理由）。
     */
    private fun decide(decision: CacheWarnDecision) {
        if (decided) return
        decided = true
        close(CLOSE_EXIT_CODE)
        onDecide(decision)
    }

    private companion object {
        /** 关框用的退出码。我们从不读 `showAndGet()` 的返回值，取语义最贴的那个。 */
        const val CLOSE_EXIT_CODE = DialogWrapper.CLOSE_EXIT_CODE
    }
}
