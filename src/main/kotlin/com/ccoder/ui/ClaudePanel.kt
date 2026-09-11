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
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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

        val inputArea = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JBScrollPane(input).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }

        val bottom = JPanel(BorderLayout()).apply {
            add(permissionSlot, BorderLayout.NORTH)
            add(inputArea, BorderLayout.SOUTH)
        }

        add(top, BorderLayout.NORTH)
        add(transcriptView, BorderLayout.CENTER)
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
            input.isEnabled = false
            sendButton.isEnabled = false
        }
    }

    /** 按 spec §7.4 的顺序清理：先停会话，再关通道，最后杀进程树。 */
    override fun dispose() {
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

        Disposer.dispose(transcriptView)
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
                    pushOp(toOp(RenderItem.SystemNote("会话已就绪")))

                    // 补发懒启动时暂存的首条消息
                    pendingFirstMessage?.let { text ->
                        pendingFirstMessage = null
                        client?.sendLine(Protocol.encodeSend(nextId(), text))
                    }
                }

                is SidecarMessage.Event ->
                    MessageRenderer.render(msg).forEach { pushOp(toOp(it)) }

                is SidecarMessage.Failure -> {
                    pushOp(toOp(RenderItem.ErrorItem(authHint(msg.code, msg.message))))
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

        // fatal 断开后按钮变成"重启会话"，此时点击应当重开会话而非发送。
        // 注意 proc 只在 dispose 里置空，所以它是"曾经启动过"的判据。
        if (!ready && proc != null) dispose()

        input.text = ""
        pushOp(toOp(RenderItem.UserText(text)))

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

    private fun nextMessageId(): String = "m${messageCounter++}"

    private companion object {
        const val NOTIFICATION_GROUP = "CCoder Permissions"
        const val TOOL_WINDOW_ID = "CCoder"
    }
}
