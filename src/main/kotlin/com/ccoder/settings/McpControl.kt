package com.ccoder.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 从设置页发往会话的 **MCP 动作**，以及它的结果（2026-09-28）。
 *
 * ## 为什么要一个服务
 *
 * 与 [McpStatus] 是同一条理由的**反方向**：会话与 `SidecarClient` 都住在
 * `ClaudePanel` 里，而设置对话框拿不到它们。那边是"面板写、页面读"，
 * 这边是"页面写、面板读"—— 中间都得有个中立的东西站着。
 *
 * 结果也走这条线（[report]/[failure]），而不是回到 [McpStatus]：一个动作的
 * 成败是**这条线自己的事**，状态那边只说"server 们现在是什么样"。
 *
 * ## 为什么结果要专门带一句话回来
 *
 * 别的控制请求失败时只有一条 `error` 广播，而那条**不带 id**：收端配不上对，
 * 只能等超时。这条不行 —— 发起动作的设置框是**模态**的（`DialogWrapper.show()`），
 * 弹着的时候聊天被挡在后面，用户看不到任何反馈。所以 sidecar 那边失败也发回执，
 * 这边把它落到 [failure] 上，页面当场显示。
 *
 * ## 与 [McpStatus] 一样的规矩：**必须在 EDT 上调用**
 *
 * 监听器是同步调的（同 [McpStatus]、同 `PendingPermissionCount`），而它们会去动
 * Swing 组件。
 */
@Service(Service.Level.PROJECT)
class McpControl {

    /**
     * 一个要发给会话的动作。
     *
     * 两个子类各自对应 sidecar 的一条控制请求（`mcpReconnect` / `mcpToggle`）。
     * 界面挑措辞时**按类型分**（`when (action)`），不认字符串 —— 类型是穷尽的，
     * 多一个子类编译器就会提醒；而对字符串迟早会有一边写歪，歪了不报错，
     * 只是那句话永远挑不中。
     */
    sealed interface Action {
        val serverName: String

        /** 让它再连一次。够得着 `.mcp.json` 里配的 server（实测，见 Protocol 那条注释）。 */
        data class Reconnect(override val serverName: String) : Action

        /** 启用 / 停用。[enabled] 为 false 是**停用**，不是"没设置"。 */
        data class Toggle(override val serverName: String, val enabled: Boolean) : Action
    }

    /**
     * 上一次动作的失败。成功不记 —— 成功了界面靠状态刷新自己变，不需要一句话。
     *
     * [action] 是发起时那个动作：光有 server 名不够，"[启用] 失败"与"[停用] 失败"
     * 要说的话不一样，而回执里那个布尔值也可能是从我们的编码器漏出去的。
     */
    data class Failure(val action: Action, val message: String)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 面板挂上来的处理器。null = 眼下没有活着的会话能接动作。
     *
     * 返回值 = **收下了没有**：面板那边会话已经断了的话回 false，[send] 就照实
     * 说"没送出去"。不这么写的话，一个已经没了的会话会让 `armed` 一直是 true、
     * 按钮一直画着，而每一次点都石沉大海。
     */
    private var handler: ((Action) -> Boolean)? = null

    /**
     * 眼下这个处理器是谁挂的（面板自己）。[detach] 只认主人 —— 同
     * `PendingPermissionCount.clear(owner)` 那条路。
     *
     * 记主人是为了**不去问标签选没选中**：摘的时机有两个（`Exit` 与 `dispose`），
     * 而 `dispose` 那一刻面板多半已经从标签列表里出去了，`isSelected(this)` 说不准；
     * 说错的方向还正好是最糟的那个 —— 后台标签把前台刚挂上的一起摘掉。
     * 认主人就没有这个问题：不是我的，动都不动。
     */
    private var owner: Any? = null

    var failure: Failure? = null
        private set

    /**
     * 眼下有人接得住这个动作吗。设置页据此决定**画不画**那两颗 ——
     * 画一颗点了没反应的按钮，正是这个仓库最不肯做的事（见 `CardAction` 的注释）。
     */
    val armed: Boolean get() = handler != null

    /** 挂上处理器。面板**成为当前标签**时挂（同 [McpStatus] 的"只有选中的才发布"）。 */
    fun attach(owner: Any, h: (Action) -> Boolean) {
        this.owner = owner
        handler = h
        // 换了会话：上一次的失败说的是上一个会话的事，留着会误导
        failure = null
        notifyListeners()
    }

    /**
     * 摘掉处理器。会话结束、面板关掉时摘。
     *
     * **只摘自己挂的那个**（[owner] 不是他就整段跳过）：后台标签的 `Exit`/`dispose`
     * 不能把前台刚挂上的那条线抹掉。
     */
    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        handler = null
        failure = null
        notifyListeners()
    }

    /**
     * 发出一个动作。返回 false = 现在没人接得住（界面本该已经把那颗按钮藏了）。
     *
     * 发之前先把上一次的失败清掉：留着的话，用户刚点了重连、却读着上一次的报错，
     * 会以为这次也失败了。
     */
    fun send(action: Action): Boolean {
        val h = handler ?: return false
        failure = null
        notifyListeners()
        if (h(action)) return true
        // 收不下 = 那边已经没会话了（面板的 `client` 是 null）。**把线放掉**：
        // 不放的话 [armed] 一直是 true、那几颗按钮一直画着，用户点一次
        // 石沉大海一次 —— 而"按钮在、按了没反应"比"按钮不在"糟得多。
        // 会话再起来时面板会重新挂上（见 ClaudePanel.attachMcpControl 的两处调用点）。
        owner = null
        handler = null
        notifyListeners()
        return false
    }

    /** 面板拿到回执后如实记一笔。[message] 非空就是失败。 */
    fun report(action: Action, message: String?) {
        failure = message?.let { Failure(action, it) }
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 复制一份再遍历：监听器可能在回调里退订（同 [McpStatus]）。 */
    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    companion object {
        fun getInstance(project: Project): McpControl = project.getService(McpControl::class.java)
    }
}
