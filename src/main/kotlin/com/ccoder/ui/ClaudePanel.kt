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
import com.ccoder.settings.ClaudeSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret

class ClaudePanel(private val project: Project) : JPanel(BorderLayout()), SidecarListener {

    private val transcript = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }
    private val scroll = JBScrollPane(transcript).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = 16
    }
    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6)
        caret = DefaultCaret().apply { updatePolicy = DefaultCaret.ALWAYS_UPDATE }
    }
    private val sendButton = JButton("发送")
    private val stopButton = JButton("停止").apply { isEnabled = false }
    private val statusLabel = JLabel("未连接")

    private var client: SidecarClient? = null
    private var proc: SidecarProcess? = null
    private var ready = false
    private var idCounter = 0L

    /** 正在流式累积的助手气泡。final assistant 消息到达时以它为准收尾。 */
    private var liveAssistant: JBTextArea? = null

    /** 并发权限询问的串行化队列（spec §6.4）。 */
    private val permissionQueue = PermissionQueue { perm, queued -> appendPermissionCard(perm, queued) }

    /** requestId → 卡片容器，用于决定后把卡片换成一行的结论。 */
    private val pendingCards = mutableMapOf<String, JComponent>()

    /**
     * 懒启动（spec §7.2）：第一次发消息才起 sidecar。
     * 首条消息在此暂存，会话就绪后补发。
     */
    private var pendingFirstMessage: String? = null


    init {
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Enter 发送，Shift+Enter 换行
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendCurrentInput()
                }
            }
        })
        sendButton.addActionListener { sendCurrentInput() }
        stopButton.addActionListener {
            client?.sendLine(Protocol.encodeSimple(nextId(), "stop"))
        }

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel, BorderLayout.WEST)
            add(stopButton, BorderLayout.EAST)
        }
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JBScrollPane(input).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }

        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        preferredSize = Dimension(500, 600)
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
                c.sendLine(Protocol.encodeStart(nextId(), ClaudeSettings.getInstance(project).toStartParams(Path.of(base))))
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
            appendItem(RenderItem.ErrorItem(text))
            input.isEnabled = false
            sendButton.isEnabled = false
        }
    }

    /** 按 spec §7.4 的顺序清理：先停会话，再关通道，最后杀进程树。 */
    fun dispose() {
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

    // ---- SidecarListener ----

    override fun onMessage(msg: SidecarMessage) {
        ApplicationManager.getApplication().invokeLater {
            when (msg) {
                is SidecarMessage.Ready -> {
                    ready = true
                    statusLabel.text = "已连接"
                    stopButton.isEnabled = true
                    sendButton.text = "发送"
                    appendItem(RenderItem.SystemNote("会话已就绪"))

                    // 补发懒启动时暂存的首条消息
                    pendingFirstMessage?.let { text ->
                        pendingFirstMessage = null
                        client?.sendLine(Protocol.encodeSend(nextId(), text))
                    }
                }

                is SidecarMessage.Event -> MessageRenderer.render(msg).forEach(::consume)

                is SidecarMessage.Failure -> {
                    appendItem(RenderItem.ErrorItem(authHint(msg.code, msg.message)))
                    if (msg.fatal) {
                        // 不静默重连——重连会让用户误以为上下文还在（spec §7.5）
                        statusLabel.text = "会话已断开"
                        ready = false
                        stopButton.isEnabled = false
                        sendButton.text = "重启会话"
                    }
                }

                is SidecarMessage.Permission -> showPermissionCard(msg)

                is SidecarMessage.Exit -> {
                    statusLabel.text = "会话已结束"
                    ready = false
                }

                is SidecarMessage.Unknown -> Unit   // 静默忽略（spec §3.3）
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
                transcript.remove(wrapper)
                transcript.revalidate()
                transcript.repaint()
            }
            appendItem(
                RenderItem.SystemNote(
                    if (decision.allow) "已允许：${perm.toolName}" else "已拒绝：${perm.toolName}"
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

        // 固定在消息流顶部而非跟随滚动到底部（spec §6.3）——
        // 底部的卡片会被新的流式输出不断推走
        transcript.add(wrapper, 0)
        transcript.revalidate()
        updateStatusBar()
        startReminderTimer(perm)
    }

    /**
     * 待决数量变化时同步状态栏（spec §6.3 的第一道补偿）。
     *
     * 推给服务而非直接操作组件——平台会按需创建/销毁状态栏组件。
     * null 项目（单元测试）下 getService 会失败，因此包一层。
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

    // ---- 渲染 ----

    /**
     * 消费一个渲染项。
     *
     * 增量与最终消息需要协调：stream_event 的 text_delta 逐字追加到"进行中"
     * 的气泡，随后到达的 assistant 消息携带完整文本，以它为准收尾 ——
     * 两者都渲染会出现重复文本。
     */
    private fun consume(item: RenderItem) {
        when (item) {
            is RenderItem.AssistantDelta -> {
                val bubble = liveAssistant ?: newLiveBubble().also { liveAssistant = it }
                bubble.append(item.text)
                scrollToBottom()
            }

            is RenderItem.ThinkingDelta -> Unit   // 思考过程不做逐字渲染，避免刷屏

            is RenderItem.AssistantText -> {
                val bubble = liveAssistant
                if (bubble != null) {
                    // 最终消息是权威版本，可能包含增量之外的修正
                    bubble.text = item.text
                    liveAssistant = null
                    scrollToBottom()
                } else {
                    appendItem(item)
                }
            }

            // 回合结束或出错时收尾，避免下一次增量接到上一个气泡上
            is RenderItem.Result, is RenderItem.ErrorItem -> {
                liveAssistant = null
                appendItem(item)
            }

            else -> appendItem(item)
        }
    }

    private fun newLiveBubble(): JBTextArea {
        val area = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = ASSISTANT_BG
            border = JBUI.Borders.empty(6)
        }
        val wrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)
            add(JLabel("Claude").apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.NORTH)
            add(area, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        transcript.add(wrapper)
        transcript.revalidate()
        return area
    }

    private fun appendItem(item: RenderItem) {
        transcript.add(componentFor(item))
        transcript.add(Box.createVerticalStrut(4))
        transcript.revalidate()
        scrollToBottom()
    }

    private fun componentFor(item: RenderItem): JComponent = when (item) {
        is RenderItem.UserText -> bubble(item.text, USER_BG, "你")
        is RenderItem.AssistantText -> bubble(item.text, ASSISTANT_BG, "Claude")
        is RenderItem.AssistantDelta -> bubble(item.text, ASSISTANT_BG, "Claude")
        is RenderItem.ThinkingDelta -> centered("", UIUtil.getInactiveTextColor())
        is RenderItem.Thinking -> collapsed("思考过程", item.text)
        is RenderItem.ToolUse -> collapsed("工具：${item.name}", item.input)
        is RenderItem.SystemNote -> centered(item.text, UIUtil.getInactiveTextColor())
        is RenderItem.ErrorItem -> bubble(item.message, ERROR_BG, "错误")
        is RenderItem.Result -> centered(
            buildString {
                append(item.subtype)
                item.costUsd?.let { append(" · \$%.4f".format(it)) }
                item.durationMs?.let { append(" · ${it}ms") }
            },
            UIUtil.getInactiveTextColor()
        )
    }

    private fun bubble(text: String, background: java.awt.Color, who: String): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)
            add(JLabel(who).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.NORTH)
            add(JBTextArea(text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                this.background = background
                border = JBUI.Borders.empty(6)
            }, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun collapsed(title: String, body: String): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)
            add(JLabel("▸ $title").apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.NORTH)
            add(JBTextArea(body).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.empty(0, 12, 0, 0)
            }, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun centered(text: String, color: java.awt.Color): JComponent =
        JPanel(BorderLayout()).apply {
            add(JLabel(text, SwingConstants.CENTER).apply { foreground = color }, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        }
    }

    // ---- 输入 ----

    private fun sendCurrentInput() {
        if (input.text.isBlank()) return
        val text = input.text.trim()

        // fatal 断开后按钮变成"重启会话"，此时点击应当重开会话而非发送。
        // 注意 proc 只在 dispose 里置空，所以它是"曾经启动过"的判据。
        if (!ready && proc != null) dispose()

        input.text = ""
        liveAssistant = null
        appendItem(RenderItem.UserText(text))

        if (!ready) {
            // 懒启动（spec §7.2）：第一次发消息才起 sidecar。
            // 消息暂存，就绪后由 Ready 分支补发 —— 若此处直接丢弃，
            // 用户点第一次"发送"时会看到消息出现却毫无反应。
            pendingFirstMessage = text
            sendButton.text = "启动中…"
            startSession()
            return
        }

        client?.sendLine(Protocol.encodeSend(nextId(), text))
    }

    private fun nextId(): String = "req-${idCounter++}"

    private companion object {
        val USER_BG = JBColor(0xE3F2FD, 0x1E3A5F)
        val ASSISTANT_BG = JBColor(0xF5F5F5, 0x2B2B2B)
        val ERROR_BG = JBColor(0xFFEBEE, 0x4A1F1F)

        const val NOTIFICATION_GROUP = "CCoder Permissions"
        const val TOOL_WINDOW_ID = "CCoder"
    }
}
