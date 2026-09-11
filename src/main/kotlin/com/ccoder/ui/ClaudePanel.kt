package com.ccoder.ui

import com.ccoder.sidecar.NodeCheck
import com.ccoder.sidecar.NodeStatus
import com.ccoder.sidecar.Protocol
import com.ccoder.sidecar.SidecarClient
import com.ccoder.sidecar.SidecarListener
import com.ccoder.sidecar.SidecarLocator
import com.ccoder.sidecar.SidecarMessage
import com.ccoder.sidecar.SidecarNotFoundException
import com.ccoder.sidecar.SidecarProcess
import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.ccoder.settings.ClaudeSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.text.DefaultCaret

/**
 * 工具窗口主体。
 *
 * 布局（设计文档 §2.1）：
 *   头部状态栏
 *   消息流（JCEF）          ← 唯一的 Web 区域
 *   权限卡片槽位（原生）
 *   输入区（原生）
 *
 * 输入区与权限卡片刻意留在原生：前者是中文输入法考虑，
 * 后者是安全考虑——审批 UI 不该依赖 Web 视图的可用性。
 */
class ClaudePanel(private val project: Project) : JPanel(BorderLayout()), SidecarListener, Disposable {

    private val transcriptView = ClaudeTranscriptView(project)

    /**
     * 权限卡片的固定槽位。
     *
     * 卡片不能再嵌进消息流——那是浏览器组件了。放在这里反而更符合
     * spec §6.3 的"固定可见、不被滚走"：它永远在转写区与输入区之间。
     */
    private val permissionSlot = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val input = ComposerTextArea(COMPOSER_ROWS, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        // 输入框得有输入框的样子，否则与转写区糊在一起（见 styleComposerInput）
        styleComposerInput(this)
        caret = DefaultCaret().apply { updatePolicy = DefaultCaret.ALWAYS_UPDATE }
    }
    /** 发送与停止合一，显示什么由 [mainButtonState] 决定。 */
    private val mainButton = JButton("发送")
    private val statusLabel = JLabel("未连接")

    private var client: SidecarClient? = null
    private var proc: SidecarProcess? = null
    private var ready = false

    /** 回合进行中：已发出消息，但还没收到 result。 */
    private var busy = false

    /** 会话 fatal 断开：界面还在，但进程已死，需要重启。 */
    private var disconnected = false
    private var idCounter = 0L
    private var messageCounter = 0L

    /** 并发权限询问的串行化队列（spec §6.4）。 */
    private val permissionQueue = PermissionQueue { perm, queued -> appendPermissionCard(perm, queued) }

    /** requestId → 卡片容器，用于决定后把卡片换成一行结论。 */
    private val pendingCards = mutableMapOf<String, JComponent>()

    /** 懒启动（spec §7.2）：第一次发消息才起 sidecar。 */
    private var pendingFirstMessage: String? = null

    init {
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // 哪个键算发送由设置决定（聊天惯例 / 编辑器惯例，见 SendShortcut）
                val shortcut = ClaudeSettings.getInstance(project).sendShortcut

                // 回合进行中不发送：那时按钮是"停止"，发送键却另发一条会让
                // 两者语义打架（见 mainButtonState）
                if (busy) return
                if (isSendKey(e.keyCode, e.isShiftDown, e.isControlDown, shortcut)) {
                    e.consume()
                    sendCurrentInput()
                }
            }
        })

        mainButton.addActionListener { onMainButtonClick() }

        // 顶部只留状态；发送/停止按钮在输入区下方的工具栏里
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel, BorderLayout.WEST)
        }

        // 滚动面板与视口都设为透明，否则会盖住输入框自己的底色与边框
        val inputScroll = JBScrollPane(input).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }

        // 底部工具栏：发送按钮归位到右下，左侧留给以后的模型切换、权限模式、
        // 用量读数等 —— 加控件不用再动结构
        val composerToolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(mainButton, BorderLayout.EAST)
        }

        val inputArea = buildComposerArea(inputScroll, composerToolbar)

        // 顶边画一条线：即使分隔条本身在某些 LAF 下不画线，
        // 输入区与转写区之间也始终有明确的边界
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineTop(JBColor.border())
            add(permissionSlot, BorderLayout.NORTH)
            // 输入区放 CENTER 而不是 SOUTH：BorderLayout 只给 SOUTH 首选高度，
            // 那样把分隔条往上拖，多出来的高度会落到空着的 CENTER，输入区
            // 纹丝不动 —— 看起来像"拖了没用"。放 CENTER 才能真正吸收。
            add(inputArea, BorderLayout.CENTER)
        }

        // 上下之间可拖动调节高度。拖动结果由 splitterProportionKey 自动持久化。
        //
        // 权限卡片不会被挤掉：bottom 是 BorderLayout(NORTH=卡片槽, CENTER=输入区)，
        // 它的 minimumSize 由布局管理器自动取两者之和，而 splitter 设了
        // honorComponentsMinimumSize —— 拖到顶也压不没卡片（spec §6.3 要求
        // 卡片固定可见）。所以这里不需要额外维护最小高度。
        val splitter = buildTranscriptSplit(transcriptView, bottom).apply {
            // 单独一步：要读应用级的 PropertiesComponent，无头单测里拿不到，
            // 所以没放进 buildTranscriptSplit（见那里的说明）
            setAndLoadSplitterProportionKey(TRANSCRIPT_SPLIT_KEY)
        }

        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        preferredSize = Dimension(500, 600)
    }

    /**
     * 面板真正上屏时建立会话。
     *
     * 时机选择：打开 CCoder 窗口就连（而不是发第一条消息才连）。状态栏立刻
     * 显示"已连接"，第一条消息零等待；代价是窗口开着期间常驻一个 node +
     * claude 进程（实测约 250MB）。
     *
     * 用 addNotify 而非在 createToolWindowContent 里直接连：后者在"项目启动时
     * 工具窗口本就处于显示状态"的情况下也会被调用，会退化成"IDE 一打开就连"。
     * addNotify 只在组件真正进入可显示层级时触发。
     *
     * 隐藏窗口**不**断开：sidecar 目前不支持会话恢复（SDK 的 session id 只用于
     * 显示，没有回传给启动参数），断开重连会让模型丢掉上下文，而界面上的历史
     * 还在、看不出区别 —— 那属于静默丢失（spec §7.5 明确反对）。进程随内容
     * 的 Disposer 结束，即移除工具窗口或关闭项目时（见 ClaudeToolWindowFactory）。
     */
    override fun addNotify() {
        super.addNotify()
        // 延后一拍再判 isShowing：addNotify 时组件尚未完成布局，此刻 isShowing
        // 还可能是 false，直接判会漏掉本该连的情况
        ApplicationManager.getApplication().invokeLater {
            LOG.info("CCoder 面板上屏：isShowing=$isShowing，据此决定是否建会话")
            if (isShowing) startSession()
        }
    }

    // ---- 主按钮（发送 / 停止合一）----

    private fun refreshMainButton() {
        val s = mainButtonState(ready, busy, disconnected)
        mainButton.text = s.text
        mainButton.isEnabled = s.enabled
    }

    private fun onMainButtonClick() {
        when (mainButtonState(ready, busy, disconnected).action) {
            // 只中断当前回合：会话与上下文都保留，可以接着聊。
            // 用 "stop" 会销毁整个会话（见 mainButtonState 的说明）
            MainAction.Interrupt -> client?.sendLine(Protocol.encodeSimple(nextId(), "interrupt"))

            MainAction.Restart -> restartSession()

            MainAction.Send -> sendCurrentInput()

            MainAction.Disabled -> Unit
        }
    }

    /** 回合开始/结束时切换按钮。回合结束的信号是 result 事件。 */
    private fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        refreshMainButton()
    }

    /** fatal 断开后重开：只换会话，转写历史留在界面上供参考。 */
    private fun restartSession() {
        stopSession()
        disconnected = false
        setBusy(false)
        refreshMainButton()
        startSession()
    }

    // ---- 会话生命周期 ----

    fun startSession() {
        if (proc != null) return
        val base = project.basePath
        if (base == null) {
            fail("项目没有 basePath，无法确定工作目录。")
            return
        }
        statusLabel.text = "正在启动…"
        refreshMainButton() // ready 仍为 false → 按钮显示"启动中…"并禁用
        LOG.info("CCoder 会话启动：cwd=$base")

        ApplicationManager.getApplication().executeOnPooledThread {
            // spec §5.3：node 与 claude 的缺失各有独立原因，
            // "没装 node"和"启动失败"的修复动作完全不同
            when (val node = NodeCheck.verify()) {
                is NodeStatus.NotFound -> {
                    fail("未找到 node。CCoder 的 sidecar 需要 Node.js ${NodeCheck.MIN_MAJOR} 或更高版本。")
                    return@executeOnPooledThread
                }
                is NodeStatus.TooOld -> {
                    fail("node 版本过低（${node.version}），需要 ${NodeCheck.MIN_MAJOR} 或更高。")
                    return@executeOnPooledThread
                }
                is NodeStatus.Ok -> Unit
            }

            try {
                val sidecarDir = SidecarLocator.resolve(base)
                val p = SidecarProcess(sidecarDir, nodePath = "node")
                p.start()
                val c = SidecarClient(p.stdout!!, p.stdin!!, this)

                ApplicationManager.getApplication().invokeLater {
                    proc = p
                    client = c
                }
                c.start()
                c.sendLine(
                    Protocol.encodeStart(
                        nextId(),
                        ClaudeSettings.getInstance(project).toStartParams(Path.of(base)),
                    )
                )
            } catch (e: SidecarNotFoundException) {
                fail(e.message ?: "未找到 sidecar 目录。")
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
    }

    private fun fail(text: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = "启动失败"
            pushOp(toOp(RenderItem.ErrorItem(text)))
            // 按"已断开"处理，让按钮变成"重启会话"：装好 node 之后用户不必
            // 重启 IDE，点一下就能重试。输入框保持可用，便于重试时带上消息
            ready = false
            disconnected = true
            setBusy(false)
            refreshMainButton()
        }
    }

    /**
     * 只停会话，不动界面。
     *
     * 与 [dispose] 分开：重启路径需要"换一个会话但保留转写历史"，
     * 而 dispose 会连转写视图一起销毁、面板就废了。
     *
     * 顺序按 spec §7.4：先停会话，再关通道，最后杀进程树。
     */
    private fun stopSession() {
        // spec §6.2 规则① 的终止路径：先作废本地待决卡片。
        // 真正把挂起的 canUseTool 承诺 resolve 掉的是 sidecar 收到 stop 后的
        // denyAllPending —— 两者都必须发生，缺任一侧都会留下挂起的工具调用。
        permissionQueue.cancelAll()
        pendingCards.clear()
        updateStatusBar()

        client?.sendLine(Protocol.encodeSimple(nextId(), "stop"))
        client?.close()
        proc?.shutdown()
        proc = null
        client = null
        ready = false
    }

    override fun dispose() {
        stopSession()
        Disposer.dispose(transcriptView)
    }

    // ---- SidecarListener ----

    override fun onMessage(msg: SidecarMessage) {
        ApplicationManager.getApplication().invokeLater {
            when (msg) {
                is SidecarMessage.Ready -> {
                    ready = true
                    statusLabel.text = "已连接"
                    disconnected = false
                    refreshMainButton()
                    pushOp(toOp(RenderItem.SystemNote("会话已就绪")))

                    // 补发窗口就绪前暂存的首条消息
                    pendingFirstMessage?.let { text ->
                        pendingFirstMessage = null
                        client?.sendLine(Protocol.encodeSend(nextId(), text))
                        setBusy(true)
                    }
                }

                is SidecarMessage.Event -> {
                    val items = MessageRenderer.render(msg)
                    items.forEach { pushOp(toOp(it)) }
                    // result 是回合结束的信号，此时按钮从"停止"变回"发送"
                    if (items.any { it is RenderItem.Result }) setBusy(false)
                }

                is SidecarMessage.Failure -> {
                    pushOp(toOp(RenderItem.ErrorItem(authHint(msg.code, msg.message))))
                    if (msg.fatal) {
                        // 不静默重连——重连会让用户误以为上下文还在（spec §7.5）
                        statusLabel.text = "会话已断开"
                        ready = false
                        disconnected = true
                        setBusy(false)
                        refreshMainButton()
                    }
                }

                is SidecarMessage.Permission -> showPermissionCard(msg)

                is SidecarMessage.Exit -> {
                    statusLabel.text = "会话已结束"
                    ready = false
                    setBusy(false)
                    refreshMainButton()
                }

                is SidecarMessage.Unknown -> Unit // 静默忽略（spec §3.3）
            }
        }
    }

    /**
     * spec §5.3：认证失败要附带可操作的提示。
     * 实测该失败最常见的原因是环境变量污染（spec §11.1），
     * 但用户看到 "authentication_failed" 无从下手。
     */
    private fun authHint(code: String?, message: String): String = when (code) {
        "AUTH_FAILED" ->
            "$message\n\n请检查 ~/.claude/settings.json 的 env 块是否包含有效的 " +
                "ANTHROPIC_AUTH_TOKEN 与 ANTHROPIC_BASE_URL。\n" +
                "若配置无误，可能是宿主环境变量污染——CCoder 已剥离 " +
                "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST 等 10 个变量（设计文档 §3.2）。"
        else -> message
    }

    // ---- 渲染 ----

    /**
     * 把渲染项转为转写操作。
     *
     * 这是 Kotlin 渲染逻辑（[MessageRenderer]）与 React 之间的最后一步转换。
     * [MessageRenderer] 本身不动 —— 它做的是"SDK 事件 → RenderItem"，
     * 与用什么渲染无关。
     */
    private fun toOp(item: RenderItem): TranscriptOp {
        val now = { System.currentTimeMillis() }
        return when (item) {
            is RenderItem.UserText ->
                TranscriptOp.Append(TranscriptItem.User(nextMessageId(), now(), item.text))

            // 最终消息是权威版本，用它收尾进行中的气泡
            is RenderItem.AssistantText -> TranscriptOp.FinalizeDelta("assistant", item.text)

            is RenderItem.AssistantDelta -> TranscriptOp.AppendDelta("assistant", item.text)

            // 思考流的逐字渲染刻意丢弃（持续刷屏，决策价值远低于正文），
            // 但必须显式清掉进行中的状态——见 TranscriptOp.FinalizeDelta 的注释。
            is RenderItem.ThinkingDelta -> TranscriptOp.ClearDelta("thinking")

            is RenderItem.Thinking ->
                TranscriptOp.Append(TranscriptItem.Thinking(nextMessageId(), now(), item.text))

            is RenderItem.ToolUse ->
                TranscriptOp.Append(
                    TranscriptItem.ToolUse(nextMessageId(), now(), item.name, item.input)
                )

            is RenderItem.ErrorItem ->
                TranscriptOp.Append(TranscriptItem.Error(nextMessageId(), now(), item.message))

            is RenderItem.Result ->
                TranscriptOp.Append(
                    TranscriptItem.Result(
                        nextMessageId(), now(), item.subtype, item.costUsd, item.durationMs,
                    )
                )

            is RenderItem.SystemNote ->
                TranscriptOp.Append(TranscriptItem.SystemNote(nextMessageId(), now(), item.text))
        }
    }

    private fun pushOp(op: TranscriptOp) = transcriptView.push(op)

    // ---- 权限卡片（spec §6）----

    private fun showPermissionCard(perm: SidecarMessage.Permission) {
        permissionQueue.enqueue(perm)
        if (!isShowing) notifyPendingPermission(perm)
    }

    private fun appendPermissionCard(perm: SidecarMessage.Permission, queuedCount: Int) {
        val card = PermissionCard(perm, queuedCount) { decision ->
            client?.sendLine(
                Protocol.encodePermissionDecision(
                    nextId(), perm.requestId,
                    decision.allow, decision.updatedPermissions, decision.message,
                )
            )
            permissionQueue.resolve(perm.requestId, decision)

            // 卡片换成一行结论，不再占据视线
            pendingCards.remove(perm.requestId)?.let { wrapper ->
                permissionSlot.remove(wrapper)
                permissionSlot.revalidate()
                permissionSlot.repaint()
            }
            pushOp(
                toOp(
                    RenderItem.SystemNote(
                        if (decision.allow) "已允许：${perm.toolName}" else "已拒绝：${perm.toolName}"
                    )
                )
            )
            updateStatusBar()
        }

        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(card, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        pendingCards[perm.requestId] = wrapper

        // 槽位固定可见，不受转写区滚动影响（spec §6.3）
        permissionSlot.add(wrapper)
        permissionSlot.revalidate()
        updateStatusBar()
        startReminderTimer(perm)
    }

    /**
     * 待决数量变化时同步状态栏（spec §6.3 的第一道补偿）。
     *
     * 推给服务而非直接操作组件——平台会按需创建/销毁状态栏组件。
     */
    private fun updateStatusBar() {
        runCatching { PendingPermissionCount.getInstance(project).set(permissionQueue.totalPending) }
    }

    /** 卡片插入时若工具窗口不可见，发粘性通知（spec §6.3）。 */
    private fun notifyPendingPermission(perm: SidecarMessage.Permission) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Claude 需要授权",
                PermissionOptions.primaryText(perm),
                NotificationType.WARNING,
            )
            .addAction(
                NotificationAction.createSimple("前往处理") {
                    ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
                }
            )
            .notify(project)
    }

    /**
     * 待决超过阈值升级为提醒（spec §6.3）。
     * 只提醒，**不**升级为模态对话框 —— 用户已选择非模态形态。
     */
    private fun startReminderTimer(perm: SidecarMessage.Permission) {
        val delaySeconds = ClaudeSettings.getInstance(project).pendingReminderSeconds
        if (delaySeconds <= 0) return
        javax.swing.Timer(delaySeconds * 1000) {
            if (permissionQueue.activeRequestId == perm.requestId) notifyPendingPermission(perm)
        }.apply {
            isRepeats = false
            start()
        }
    }

    // ---- 输入 ----

    private fun sendCurrentInput() {
        if (input.text.isBlank()) return
        val text = input.text.trim()

        input.text = ""
        pushOp(toOp(RenderItem.UserText(text)))

        if (!ready) {
            // 会话还没就绪。可能是 fatal 断开后残留的进程，先清干净再起一个，
            // 否则 startSession 会因为 proc != null 直接返回、消息永远发不出去。
            if (proc != null) stopSession()
            disconnected = false

            // 消息暂存，就绪后由 Ready 分支补发 —— 若此处直接丢弃，
            // 用户点第一次"发送"时会看到消息出现却毫无反应。
            pendingFirstMessage = text
            refreshMainButton()
            startSession()
            return
        }

        client?.sendLine(Protocol.encodeSend(nextId(), text))
        // 发出后进入"忙"：按钮变"停止"，直到 result 到达
        setBusy(true)
    }

    private fun nextId(): String = "req-${idCounter++}"

    private fun nextMessageId(): String = "m${messageCounter++}"

    private companion object {
        const val NOTIFICATION_GROUP = "CCoder Permissions"
        const val TOOL_WINDOW_ID = "CCoder"

        val LOG = Logger.getInstance(ClaudePanel::class.java)
    }
}
