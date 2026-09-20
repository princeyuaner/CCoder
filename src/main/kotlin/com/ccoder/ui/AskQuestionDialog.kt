package com.ccoder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.SwingUtilities
import com.ccoder.text.CcoderText

/**
 * 一道题的模态框（设计稿里"第 2 / 3 题"那个形态）。
 *
 * 与 [PermissionDialog] 同一副骨架：卡片自带按钮、没有平台按钮、没有默认按钮
 * （回车不批准）、焦点落在"拒绝"、Esc 与关窗都等于**整条拒绝**。
 *
 * "整条"是关键：`AskUserQuestion` 只回传一次入参。在第三题上点拒绝，
 * 拒的是**这一个工具调用**，而不是"跳过这题" —— 半份答案喂回模型比拒绝更糟。
 */
internal class AskQuestionDialog(
    project: Project,
    internal val card: AskQuestionCard,
    private val onCancel: () -> Unit,
) : DialogWrapper(project) {

    /** 关过一次就是关过了：之后再来的关闭请求（X、Esc、终止路径）都不再回回调。 */
    private var closed = false

    init {
        title = TITLE
        isResizable = false
        init()

        // 规则②：没有默认按钮 = 回车不批准
        rootPane.defaultButton = null
        rootPane.bindEscapeToDeny { doCancelAction() }
    }

    override fun createCenterPanel(): JComponent = card

    override fun createActions(): Array<Action> = emptyArray()

    override fun createSouthPanel(): JComponent? = null

    override fun getPreferredFocusedComponent(): JComponent = card.denyButton

    /** 还开着吗。序列靠它保证"上一个关掉了才弹下一个"。 */
    internal val isOpen: Boolean get() = !closed

    /** X 与 Esc 都落到这里 —— 整条拒绝。 */
    override fun doCancelAction() {
        if (closed) return
        closed = true
        close(CLOSE_EXIT_CODE)
        onCancel()
    }

    /**
     * 关框，且不再回任何回调。
     *
     * 三种情况用它：答完了（接着提交）、换下一题（接着弹下一个框）、
     * 终止路径（会话停了 / sidecar 没了，协议侧由 sidecar 负责 resolve）。
     */
    fun closeNow() {
        if (closed) return
        closed = true
        close(CLOSE_EXIT_CODE)
    }

    private companion object {
        val TITLE: String get() = CcoderText.text("ask.dialog.title")
    }
}

/**
 * 多题提问的弹窗序列：**一题一个框，答完接着弹下一个**。
 *
 * ## 为什么不是"一个框里翻页"
 *
 * 用户要的就是"一个一个弹出"。顺带一个好处：每个框的焦点/按钮规则都只服务一道题，
 * 不用在一个框里同时管"N 道题的作答状态"和"第几题"。
 *
 * ## 弹下一个之前必须先关掉上一个
 *
 * 从上一个框的 Action 里**同步** `show()` 下一个，得到的是嵌套模态框 ——
 * 两个模态循环套在一起，焦点与层级都不可靠。所以 [advance] / [back] 一律
 * "先关、再让出一拍、然后弹"。让出的那一拍用 [SwingUtilities.invokeLater]：
 * 它会把这件事排到当前这轮事件之后，而那时上一个框已经关掉了。
 *
 * 这个不变式有测试盯着（`AskSequenceTest`：present 的当下不许还有别的框开着）。
 *
 * ## 谁管什么
 *
 * 题号与作答状态在 [AskFlow] 里（可单测），框的生命周期在这里，卡片只负责画。
 * 卡片按下「下一题」时**自己**推进 [flow]，然后调 [onAdvance] 通知这边换框 ——
 * 推进的理由（答没答完）只有一处判断。
 */
internal class AskSequence(
    private val project: Project,
    request: AskRequest,
    private val onSubmit: (Picked) -> Unit,
    private val onDeny: () -> Unit,
    /** 怎么把框弹出去。测试与探针给假实现，生产走 [DialogWrapper.show]。 */
    private val present: (AskQuestionDialog) -> Unit = { it.show() },
    /** 让出一拍。测试给同步实现，好让推进过程是确定的。 */
    private val defer: ((() -> Unit)) -> Unit = { SwingUtilities.invokeLater(it) },
    /**
     * 挂起状态变了。生产路径拿它更新状态栏（回来的入口在那儿）；
     * 测试与探针给假实现 —— 它们没有状态栏，也不该有。
     */
    private val onSuspendChange: (Boolean) -> Unit = {},
) {

    internal val flow = AskFlow(request)

    private var dialog: AskQuestionDialog? = null

    /** 整条拒掉之后不该再弹下一个框。 */
    private var cancelled = false

    /**
     * 是不是被用户最小化了：框收起来了，但这条提问**没有结束**。
     *
     * 与 [cancelled] 是两件事：那个是"整条拒掉、别再回来"，这个是"先挪开、
     * 一会儿接着答"。
     */
    var suspended = false
        private set

    /** 弹第一题。 */
    fun start() {
        openCurrent()
    }

    /**
     * 最小化：把框收起来，**不回任何回调、也不取消序列**。
     *
     * ## 它是第三条路，不是「关掉」
     *
     * spec §6.2 规则① 把"关窗/Esc"定义成**整条拒绝**，并特意写明那不是
     * "稍后再问"。最小化是另一条路，理由是用户真的需要它：批准与选择题都
     * 得先看一眼代码，而模态框把代码挡在外面（§12 里本来就留着一条
     * "模态框能不能把 IDE 窗口带到前台，平台没给保证"）。
     *
     * 它与"关掉"的区别在于**协议侧一个字都没变**：请求仍在队列里
     * （`permissionQueue` 仍计数、`cancelAll` 仍能作废它）、sidecar 那边仍在等、
     * 我们也没回任何决定。所以规则① 不受影响 —— 那条规则管的是"怎么算拒绝"，
     * 而这里什么都没拒绝。
     *
     * 代价是"被忽略"这件事重新出现了（§6.3 说模态不需要补偿，正是因为框会
     * 自己弹到眼前）。补偿就是状态栏常驻那一行 —— 而且挂起是用户**主动**制造的，
     * 不是没注意到。
     */
    fun minimize() {
        if (cancelled || suspended) return
        val current = dialog ?: return
        dialog = null
        current.closeNow()
        suspended = true
        onSuspendChange(true)
    }

    /**
     * 从挂起里回来：**用同一个 [flow] 重开当前题**。
     *
     * 为什么是重开而不是把旧框再显示出来：`DialogWrapper.close()` 不可逆，
     * 而 [AskQuestionDialog] 的 `closed` 标志是"回调只回一次"的闸
     * （`AskSequenceTest` 里那条不变式钉着它）。重开这条路径本来就是现成的 ——
     * 「上一题」就是这么走的，答案（含「其它…」里打的字）都从 [flow] 里读回来。
     *
     * **必须由调用方排在"没有模态框"的那一拍上**（[ClaudePanel] 走 `showModal`），
     * 理由同 [advance]：同步 `show()` 会叠出嵌套模态框。
     */
    fun restore() {
        if (cancelled || !suspended) return
        suspended = false
        onSuspendChange(false)
        openCurrent()
    }

    /**
     * 终止路径（会话停止 / sidecar 退出 / 中断回合）用：关掉当前框，且不再弹。
     *
     * 这些路径上协议侧由 sidecar 的 `denyAllPending` 负责 resolve，
     * 我们这边再回一条决定等于朝已经不存在的请求说话。
     *
     * 挂起中的也要一起清掉：否则状态栏会一直挂着"有提问待回答"，
     * 点下去却什么都没有 —— 那比没有这个入口更糟。
     */
    fun closeSilently() {
        cancelled = true
        dialog?.closeNow()
        dialog = null
        if (suspended) {
            suspended = false
            onSuspendChange(false)
        }
    }

    private fun openCurrent() {
        if (cancelled) return

        val card = AskQuestionCard(
            flow,
            onSubmit = ::finish,
            onAdvance = ::advance,
            onBack = ::back,
            onDeny = ::deny,
            onMinimize = ::minimize,
        )
        val next = AskQuestionDialog(project, card) { deny() }
        dialog = next
        present(next)
    }

    private fun finish(picked: Picked) {
        val current = dialog ?: return
        dialog = null
        current.closeNow()
        onSubmit(picked)
    }

    private fun advance() {
        val current = dialog ?: return
        dialog = null
        current.closeNow()
        defer { openCurrent() }
    }

    private fun back() {
        // 题号已经由卡片退回上一题了（它调的是 flow.back()），这里只管换框
        val current = dialog ?: return
        dialog = null
        current.closeNow()
        defer { openCurrent() }
    }

    private fun deny() {
        cancelled = true
        val current = dialog
        dialog = null
        current?.closeNow()
        onDeny()
    }
}
