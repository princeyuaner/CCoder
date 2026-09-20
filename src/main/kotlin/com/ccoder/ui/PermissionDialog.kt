package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.Action
import javax.swing.JComponent
import com.ccoder.text.CcoderText

/**
 * 权限询问的模态框（设计稿方案 B，2026-09-14 由"工具窗口里的非模态卡片"改过来）。
 *
 * ## 为什么改成模态
 *
 * 非模态卡片住在工具窗口的固定槽位里：窗口没开着就看不见，长对话里还会被滚走。
 * 而 Claude 等权限**没有超时**（spec §6.6）—— 漏看一次就是无限等待。
 * spec §12 的风险表早就给这条路留了口子（"若实践证伪，回退到模态对话框"）。
 *
 * ## 为什么按钮留在卡片里
 *
 * 卡片是这套 UI 里被单测钉得最死的一块（`PermissionCardTest`、`AutoAllowRenderProbe`），
 * 弹窗只负责"框"：标题、模态、关窗语义、焦点。`DialogWrapper` 自带的那对
 * OK/Cancel 在这里只会多出一对语义重复的按钮 —— 所以 [createActions] 返回空、
 * [createSouthPanel] 返回 null。
 *
 * ## 三条 SDK 明文规则（spec §6.2）在本类里的落点
 *
 * - **规则①**：关窗与 Esc 都走 [doCancelAction] → 拒绝。**不是**"稍后再问"：
 *   SDK 那边挂着的是一个没有期限的 Promise，关掉就必须给它一个答案。
 * - **规则②**：[getPreferredFocusedComponent] 落在"拒绝"上；[rootPane] 的默认按钮
 *   被清掉 —— 回车不批准，任何键都不批准。
 * - **规则③**：由 [PermissionCard] 自己把关（`allowsAlwaysAllow` 为假时那个按钮
 *   根本不渲染）。
 *
 * ## 测试里别调 `show()`
 *
 * 平台的 `HeadlessDialog.show()` 在单测模式下会**立刻关掉**（等于替用户做了决定），
 * 而真的模态循环在测试 JVM 里也起不来。测试一律只构造 + 走组件上真挂着的监听器，
 * 用 [closeSilently] / [doCancelAction] 驱动关闭路径。
 */
internal class PermissionDialog(
    project: Project,
    permission: SidecarMessage.Permission,
    queuedCount: Int,
    private val onDecide: (PermissionDecision) -> Unit,
) : DialogWrapper(project) {

    /** 弹窗的内容本体。测试与探针都从它进去找按钮。 */
    internal val card = PermissionCard(permission, queuedCount, ::decide)

    /**
     * 一旦置位，这个框不再回任何决定。
     *
     * 两种置位原因：①已经回过了（连点两下按钮只该发一条）；②终止路径把它静默关掉了
     * （那时协议侧由 sidecar 的 `denyAllPending` 负责，我们这边再发一条就成了
     * 张冠李戴的拒绝）。一个标志位管两件事，因为对调用方来说它们是一回事：
     * **这个框闭嘴了**。
     */
    private var noDecision = false

    init {
        title = TITLE
        // 可拉伸（2026-09-16 方案 B）。原先写死 false，理由是"卡片内容静态" ——
        // 但计划正文可以几百行，420×200 的框里一眼只看得到四五条路径，而**要批准
        // 的东西正是那些字**。现在初始尺寸按屏幕给（见 PERMISSION_CARD_WIDTH /
        // planAreaHeight），拖大时计划区跟着长（那张卡的滚动区不封顶）。
        //
        // 下限由 PermissionCard.getMinimumSize 兜住，拖不成一条缝。
        isResizable = true
        init()

        // 规则②：没有默认按钮 = 回车不批准。
        //
        // 严格说这一句今天是**第二道锁**：`createActions()` 返回空数组，平台
        // 压根不会创建按钮，也就没人去 `setDefaultButton`（Swing 从不自己指派
        // 默认按钮 —— 是平台的 OkAction 带着 DEFAULT_ACTION，才让 OK 成为默认按钮）。
        // 留着它的理由是这条规则太要紧：哪天有人往框里加一颗按钮，这一句会拦住
        // "回车就批准"。
        //
        // 另一个要知道的事实：Windows/Linux 上平台装了 Enter 钩子
        // （`DialogWrapper.installEnterHook`），回车会去点**当前有焦点的那颗按钮** ——
        // 而焦点在"拒绝"上，所以那边表现为"回车 = 拒绝"。这符合规则②（它挡的是
        // "一键批准"），不是漏洞。
        rootPane.defaultButton = null

        // 规则①：Esc 也必须是拒绝。自备一条绑定 —— 平台那条要等 show() 才注册，
        // 没有 show() 的测试查不到它（详见 bindEscapeToDeny 的说明）
        rootPane.bindEscapeToDeny { doCancelAction() }
    }

    override fun createCenterPanel(): JComponent = card

    /** 不画 DialogWrapper 自带的按钮对 —— 按钮在卡片里。 */
    override fun createActions(): Array<Action> = emptyArray()

    /** 连底部那条空槽也不要。 */
    override fun createSouthPanel(): JComponent? = null

    override fun getPreferredFocusedComponent(): JComponent = card.denyButton

    /** X 与 Esc 都落到这里 —— 规则①：关掉就是拒绝。 */
    override fun doCancelAction() {
        decide(deniedByUser())
    }

    /**
     * 静默关掉：终止路径（会话停止 / sidecar 退出 / 中断回合）用它。
     *
     * 这些路径上协议侧已经由 sidecar 的 `denyAllPending` 全部 resolve 了，
     * 我们再回一条决定等于朝已经不存在的请求说话。
     */
    fun closeSilently() {
        if (noDecision) return
        noDecision = true
        close(CLOSE_EXIT_CODE)
    }

    /**
     * 回决定并关框。
     *
     * **先关框、再回决定**：`onDecide` 那条路会走到 `PermissionQueue.resolve`，
     * 于是下一个待确认的请求会立刻被激活 —— 那个框要等这一个真的关掉了再弹，
     * 否则就是嵌套模态框，焦点与层级都不可靠（见 [AskSequence] 的同一条理由）。
     */
    private fun decide(decision: PermissionDecision) {
        if (noDecision) return
        noDecision = true
        close(CLOSE_EXIT_CODE)
        onDecide(decision)
    }

    private companion object {
        val TITLE: String get() = CcoderText.text("permission.dialog.title")

        /**
         * 关框用的退出码。
         *
         * 我们从不读 `showAndGet()` 的返回值（决定是从 [onDecide] 走的），
         * 所以三个常量里选哪个都不影响行为 —— 取"窗口被关掉"这个语义最贴的。
         * 写成常量而不是字面量 `2`：这是平台的常量，不是我们发明的数。
         */
        const val CLOSE_EXIT_CODE = DialogWrapper.CLOSE_EXIT_CODE
    }
}
