package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo
import com.ccoder.sidecar.NodeCheck
import com.ccoder.sidecar.NodeStatus
import com.ccoder.sidecar.Protocol
import com.ccoder.sidecar.RequestOutcome
import com.ccoder.sidecar.SessionInfo
import com.ccoder.sidecar.SidecarClient
import com.ccoder.sidecar.SidecarExit
import com.ccoder.sidecar.SidecarListener
import com.ccoder.sidecar.SidecarLocator
import com.ccoder.sidecar.SidecarMessage
import com.ccoder.sidecar.SidecarNotFoundException
import com.ccoder.sidecar.SidecarProcess
import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.ccoder.settings.ClaudeSettings
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.ModelProfiles
import com.ccoder.settings.PermissionModeSetting
import com.ccoder.settings.displayName
import com.ccoder.settings.showModelProfilesDialog
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
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

    /**
     * 连接状态的**文字源**。
     *
     * 留一个纯字符串而不是直接拿卡当状态：文字 → 色调是一次纯逻辑映射
     * （[connectionTone]），能在无头单测里钉住；卡本身是 Swing。
     */
    private var connectionText = "未连接"

    /**
     * 四张状态卡。**常驻** —— 没内容的格子收边，不隐藏。
     *
     * 卡一会儿出现一会儿消失，输入框就会在会话中途上下跳；稳定比安静重要。
     */
    private val statusCards = StatusCardsRow(
        onOpenTodos = { toggleDetail(wantsTodos = true) },
        onOpenRunning = { toggleDetail(wantsTodos = false) },
    )

    /** 最近一次拿到的上下文用量。取不到时保持 null —— 不造零值。 */
    private var lastUsage: ContextUsage? = null

    /**
     * 运行状态与任务清单。
     *
     * **每一个事件都喂给它**（包括 Push 给转写区的那些）—— 这里读的是
     * "现在是什么状态"，而转写区读的是"发生过什么"，两者来源相同但用途不同。
     */
    private val runStatus = RunStatusTracker()

    private val input = ComposerTextArea(COMPOSER_MIN_ROWS, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        // 输入框得有输入框的样子，否则与转写区糊在一起（见 styleComposerInput）
        styleComposerInput(this)
        caret = DefaultCaret().apply { updatePolicy = DefaultCaret.ALWAYS_UPDATE }
    }
    /** 发送与停止合一，显示什么由 [mainButtonState] 决定。 */
    private val sendButton = RoundSendButton().apply { onClick = { onMainButtonClick() } }

    /**
     * 当前模型配置。可点，点开切换。
     *
     * 写它只有 [refreshModelLabel] 一个出口 —— 显示的是**用户选中的那条配置**
     * （spec §8），来源是 [ModelProfiles]，不是 SDK 报上来的模型名。
     * 标签的文字与那个 `▾` 是 [ModelLabel] 自己拼的，这里直接写 `.text`
     * 会把箭头抹掉、与弹层里的勾对不上。
     */
    private val modelLabel = ModelLabel { toggleModelChooser() }

    /** 权限模式。可点，点开切换。 */
    private val modeLabel = ModeLabel { toggleModeChooser() }

    /**
     * 当前生效的权限模式。
     *
     * **只在收到 sidecar 回执后才变**，点的那一下不发。先改标签后等结果的话，
     * 切换失败时标签会显示一个没生效的模式 —— 这是个安全控件，
     * 它撒谎比它不好用严重。
     */
    private var currentMode: PermissionModeSetting = ClaudeSettings.getInstance(project).permissionMode

    /**
     * 本会话不再询问：开着时收到的权限询问不回卡片，直接放行。
     *
     * 它**不是** SDK 的模式之一 —— 权限模式一个字都没变，变的是插件怎么回应
     * 询问。所以它不需要动协议、不往设置里写东西：会话一结束就没了。
     */
    private var autoAllow = false

    /** 打开着的模式列表浮层。用它实现"再点一次收起"。 */
    private var modePopup: JBPopup? = null

    /** 打开着的模型列表浮层。同上，也由它实现"再点一次收起"。 */
    private var modelPopup: JBPopup? = null

    /** 顶部左侧的会话标签。可点，点开列历史会话。 */
    private val sessionLabel = SessionLabel { toggleSessionChooser() }

    /**
     * 当前会话的标题。null = 还不知道（全新会话，或者刚恢复还没拿到标题）。
     *
     * **界面上永远不显示会话 id** —— 列表里显示的是标题，一串 UUID 前缀
     * 对不上号，纯噪音。标签的显示规则就一条：有标题显示标题，没有显示
     * 斜体的「新会话」。
     */
    private var currentSessionTitle: String? = null

    /**
     * 当前会话 id。
     *
     * 两个来源：resume 时构造即知；全新会话从 `system`/`init` 事件取。
     * **不读 `ready.sessionId`** —— 那个回显的是请求参数，全新会话时是 null
     * （spec §10）。
     */
    private var currentSessionId: String? = null

    /** 非空表示下一次 [startSession] 要恢复这个会话。发送后即清空。 */
    private var resumeTargetId: String? = null

    /** 打开着的会话列表浮层。用它实现"再点一次收起"。 */
    private var sessionPopup: JBPopup? = null

    /**
     * 右上角的齿轮。造型照 [newSessionButton] —— 同一行里两个按钮，一个有边框
     * 一个没有会很扎眼（字号 15f 也是照它）。
     *
     * **不随忙闲置灰**：它开的是设置对话框，而对话框只读写配置、不碰会话
     * （见 [showModelProfilesDialog]），会话进行中也该能开。
     */
    private val settingsButton = JButton("⚙").apply {
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(15f)
        foreground = UIUtil.getLabelForeground()
        toolTipText = "设置"
        addActionListener { showModelProfilesDialog(project) }
    }

    /** 最右的「＋」。会话标签在它左边（设计稿 §一 A）。 */
    private val newSessionButton = SessionNewButton { onNewSession() }

    /** 最近一次列出来的会话。删除成功后从它里面摘掉那一行再重画。 */
    private var sessionListCache: List<SessionInfo> = emptyList()

    private var client: SidecarClient? = null
    private var proc: SidecarProcess? = null
    private var ready = false

    /**
     * 正在起会话。
     *
     * [proc] 要等 sidecar 建好才有值，而那是异步的 —— 同一拍里的第二次触发会从
     * [proc] 的判空里漏过去，起出第二个 node 进程（约 250MB，且没人收）。
     * 面板"显示出来"这件事现在有两个触发点（见 [showingWatcher] 与 [addNotify]），
     * 所以必须有这个**同步置位**的闸。
     *
     * 归位只有两处：起好了（赋值 [proc] 时）与起失败了（[fail]）。
     */
    @Volatile
    private var starting = false

    /** 打开着的详情浮层。用它实现"再点一次收起"。 */
    private var runDetailPopup: JBPopup? = null

    /**
     * 挂着的那张详情卡是不是"子任务"卡。
     *
     * 两张卡共用一个浮层，关的时候得知道该把哪一张取消高亮。
     */
    private var todosOpen = false

    /** 回合进行中：已发出消息，但还没收到 result。 */
    private var busy = false

    /** 会话 fatal 断开：界面还在，但进程已死，需要重启。 */
    private var disconnected = false
    private var idCounter = 0L
    private var messageCounter = 0L

    /**
     * 会话代次。每次 [startSession] 递增。
     *
     * 用来丢弃"上一个 sidecar 进程死了"的陈旧消息：换会话时会先杀掉旧进程，
     * 它的死讯属于上一条会话，不该打到新会话的界面上。
     */
    private var sessionEpoch = 0

    /** 并发权限询问的串行化队列（spec §6.4）。 */
    private val permissionQueue = PermissionQueue { perm, queued -> appendPermissionCard(perm, queued) }

    /** requestId → 卡片容器，用于决定后把卡片换成一行结论。 */
    private val pendingCards = mutableMapOf<String, JComponent>()

    /** 懒启动（spec §7.2）：第一次发消息才起 sidecar。 */
    private var pendingFirstMessage: String? = null

    // ---- 补全（设计稿 §3）----

    /** 命令显示信息，会话就绪后拉一次。 */
    private var commandList: List<CommandInfo> = emptyList()

    /** 可发送的命令名，来自最近一条 `init` 事件的 `slash_commands`。 */
    private var sendableNames: Set<String> = emptySet()

    private val completion = CompletionPopup()
    private var completionQuery: CompletionQuery? = null
    private var completionItems: List<CompletionItem> = emptyList()
    private var completionIndex = 0

    /**
     * 项目文件列表，**按弹层生命周期缓存**。
     *
     * 不缓存的话每次按键都要走一遍 ProjectFileIndex，那是几万条 VFS 访问。
     * 关层时清掉，所以新开的弹层总能看到新建的文件。
     */
    private var projectFiles: List<String>? = null

    /** 采纳时的程序化改写会触发文档监听，用它挡掉自引发的重算。 */
    private var suppressCompletion = false

    /** 这一回合是命令回合（发出去的消息以 `/` 开头）。 */
    private var lastSendWasCommand = false

    init {
        // 补全：文本变了就重算候选。用文档监听而不是按键监听 ——
        // 粘贴、撤销、退格都会改文本，而它们不都是"按键"
        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshCompletion()
        })
        // 光标挪走（点了一下别处）时弹层要跟着收，否则它会停在一个
        // 已经没有查询词的位置上
        input.addCaretListener { refreshCompletion() }

        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // 补全开着时，上下键与 Enter/Tab/Esc 归补全。
                // **只在真开着时短路** —— 关着的时候 Enter 该不该发送
                // 仍然是 isSendKey 的事，那个函数一行都不改
                if (completion.isOpen) {
                    when (completionKey(e.keyCode)) {
                        CompletionKey.Up -> { e.consume(); moveCompletion(-1); return }
                        CompletionKey.Down -> { e.consume(); moveCompletion(1); return }
                        CompletionKey.Accept -> { e.consume(); acceptCompletion(); return }
                        CompletionKey.Dismiss -> { e.consume(); closeCompletion(); return }
                        CompletionKey.Ignore -> Unit
                    }
                }

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

        // 顶部：左边是连接状态，右边是会话标签（可点，点开列历史会话）
        // 与「＋」新建。发送/停止按钮在输入区下方的工具栏里
        // 顶部这一行现在只有会话：标签靠左，齿轮与「＋」在右（用户最初要的就是右上角）。
        // 连接状态已经挪到下面的上下文行 —— 那一行原先只为了它一个人撑高度。
        //
        // 标签待在 CENTER 里拿剩余宽度而不是给固定首选宽：长标题才不会把
        // 右边两个按钮挤出去，超了自己打省略号（spec §2.3 的同一条理由）。
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(sessionLabel, BorderLayout.CENTER)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(settingsButton)   // 齿轮在左
                    add(newSessionButton) // 「＋」仍在最右 —— 用户最初要的就是右上角
                },
                BorderLayout.EAST,
            )
        }

        // 滚动面板与视口都设为透明，否则会盖住输入框自己的底色与边框
        val inputScroll = JBScrollPane(input).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }

        // 底部工具栏：模型与权限模式在左、发送键在右
        refreshModeLabel()
        refreshModelLabel()
        val composerToolbar = buildComposerToolbar(modelLabel, modeLabel, sendButton)

        // 四张卡先灌一次初值，否则它们是一排没有内容的空框
        refreshStatusCards()

        val inputArea = buildComposerCard(inputScroll, composerToolbar)

        // 权限卡与状态卡共用 NORTH：两块都在输入卡**外面**、它的上方。
        // 顺序是权限卡在上（它更急）、状态卡紧贴输入框
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(permissionSlot)
            add(statusCards)
        }

        // 不再单独画顶边线：输入区现在是一张圆角卡片，它自己的上沿
        // 就是与转写区之间的边界，再画一条会变成两道线
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 8, 8)
            add(header, BorderLayout.NORTH)
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
     * 面板**真正**显示出来时建立会话。
     *
     * 时机选择：打开 CCoder 窗口就连（而不是发第一条消息才连）。状态栏立刻
     * 显示"已连接"，第一条消息零等待；代价是窗口开着期间常驻一个 node +
     * claude 进程（实测约 250MB）。
     *
     * **为什么是 SHOWING_CHANGED 而不是 addNotify**：addNotify 只代表"变得
     * **可**显示"（displayable），不代表"正在显示"（showing）。实测三次启动全中 ——
     * 每次启动的第一次 addNotify 里 `isShowing` 都是 false，而那唯一一次机会就此
     * 用掉：界面停在"未连接"，要切到别的工具窗口再切回来（内容被摘掉重挂，第二次
     * addNotify 时 isShowing 才是 true）才连上。
     *
     * 延后一拍（invokeLater）也救不了：它只是换个时刻再问一次 isShowing，窗口那时
     * 仍然没显示出来。Swing 实测 SHOWING_CHANGED 会派发到深层子组件，所以监在自己
     * 身上就够。
     *
     * 隐藏窗口**不**断开：断开重连会让模型丢掉上下文，而界面上的历史还在、
     * 看不出区别 —— 那属于静默丢失（spec §7.5 明确反对）。这也让"打开面板恢复
     * 最近会话"只发生一次：隐藏后再显示时 [proc] 还在，[startSession] 直接返回，
     * 不会把用户从正在聊的那个会话上拽走。进程随内容的 Disposer 结束，
     * 即移除工具窗口或关闭项目时（见 ClaudeToolWindowFactory）。
     */
    private val showingWatcher = HierarchyListener { e ->
        if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
            LOG.info("CCoder 面板显示（SHOWING_CHANGED），据此决定是否建会话")
            // 打开面板默认回到最近那条会话 —— 用户要的是"接着上次聊"，
            // 而不是每次开窗都从零开始（旧行为见 session-switch spec §1.3）
            startSession(pickMostRecent = true)
        }
    }

    override fun addNotify() {
        super.addNotify()
        addHierarchyListener(showingWatcher)

        // 兜住"装监听时它已经显示完了"这一种：那时 SHOWING_CHANGED 早就发生在
        // 装监听之前，不会有事件再来。延后一拍再判 isShowing，避开布局未完成的时刻。
        ApplicationManager.getApplication().invokeLater {
            LOG.info("CCoder 面板上屏：isShowing=$isShowing，据此决定是否建会话")
            if (isShowing) startSession(pickMostRecent = true)
        }
    }

    override fun removeNotify() {
        // 不摘的话每开合一次工具窗口就漏一个监听器，而它捕获着这个面板
        removeHierarchyListener(showingWatcher)
        super.removeNotify()
    }

    // ---- 主按钮（发送 / 停止合一）----

    private fun refreshMainButton() {
        sendButton.setState(mainButtonState(ready, busy, disconnected))
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

    /**
     * 记下最新的上下文用量。
     *
     * 没有用量数据时**保持原样**（可能是非 result 事件，也可能是 SDK 这次
     * 没带 modelUsage）—— 清空会把已有的读数抹掉。
     */
    private fun updateUsage(event: JsonObject) {
        val usage = contextUsageOf(event) ?: return
        lastUsage = usage
        refreshStatusCards()
    }

    /** 唯一的连接状态写入口。文字变了，卡上的点与色跟着变。 */
    private fun setConnection(text: String) {
        connectionText = text
        refreshStatusCards()
    }

    /**
     * 按当前四份数据重画四张卡。
     *
     * 没内容的格子由 [StatusCardModel.quiet] 收边 —— 不是隐藏，四张卡始终在。
     */
    private fun refreshStatusCards() {
        statusCards.connection.setModel(connectionCardOf(connectionText))
        statusCards.context.setModel(contextCardOf(lastUsage))
        statusCards.todos.setModel(todoCardOf(runStatus.todos))
        statusCards.running.setModel(runningCardOf(runStatus.running))
    }

    /**
     * 模型标签的唯一出口，与 [refreshModeLabel] 同一个道理。
     *
     * 显示的是**选中的配置**，而选中态存在 [ModelProfiles] 里 —— 谁改了它就
     * 调一下这里，免得标签与真实生效的那条对不上。
     */
    private fun refreshModelLabel() {
        modelLabel.setProfile(ModelProfiles.getInstance().selected())
    }

    /**
     * 点卡 → 弹它那一段详情；再点一次 → 收起。
     *
     * 两张卡共用一个浮层字段：同一时刻只该有一个浮层挂着，而 [todosOpen]
     * 记住是哪一个，好在关闭时把对应的卡取消高亮。
     *
     * 浮层不抢焦点（`setRequestFocus(false)`）：你正在输入框里打字，
     * 点一下看一眼进度不该把光标弄丢。
     *
     * @param wantsTodos 参数名刻意不叫 `todosOpen` —— 与字段同名会遮蔽它，
     *   一旦漏写 `this.` 就是静默的错。
     */
    private fun toggleDetail(wantsTodos: Boolean) {
        val card = if (wantsTodos) statusCards.todos else statusCards.running
        val wasOpen = todosOpen == wantsTodos && runDetailPopup != null

        runDetailPopup?.cancel()
        runDetailPopup = null
        statusCards.todos.setOpen(false)
        statusCards.running.setOpen(false)

        if (wasOpen) return

        todosOpen = wantsTodos
        runDetailPopup = showTogglePopup(
            anchor = card,
            content = if (wantsTodos) {
                runStatus.todos?.let(::buildTodoDetail) ?: buildRunningDetail(emptyList())
            } else {
                buildRunningDetail(runStatus.running)
            },
        ) {
            runDetailPopup = null
            statusCards.todos.setOpen(false)
            statusCards.running.setOpen(false)
        }
        card.setOpen(true)
    }

    /**
     * 开一个"再点一次收起"的浮层。
     *
     * 抽出来的时机是第三份拷贝出现时 —— 任务详情、权限模式、会话列表三处的
     * 创建参数完全一致，散着写迟早会改漏一处。位置计算复用 [showAboveOrBelow]。
     */
    private fun showTogglePopup(
        anchor: JComponent,
        content: JComponent,
        // centerOverPanel 排在 onClosed **前面**：Kotlin 的尾随 lambda 绑的是
        // 最后一个参数，加在后面的话三个既有调用点的 lambda 会静默改绑。
        // 与 buildSessionList 的 onDelete 是同一个坑（那次两边都是 Function1，
        // 编译器逮不住，一路红到冒烟）
        centerOverPanel: Boolean = false,
        onClosed: () -> Unit,
    ): JBPopup {
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        popup.addListener(
            object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = onClosed()
            }
        )
        showAboveOrBelow(popup, anchor, centerOverPanel)
        return popup
    }

    /**
     * 锚点都在工具窗口底部，向下弹必然出屏，所以位置得自己算。
     * 见 [popupAnchorY]。
     */
    private fun showAboveOrBelow(popup: JBPopup, anchor: JComponent, centerOverPanel: Boolean = false) {
        if (!anchor.isShowing) return
        val at = anchor.locationOnScreen
        val screen = anchor.graphicsConfiguration?.bounds ?: Rectangle(0, 0, 1920, 1080)
        val y = popupAnchorY(
            anchorTop = at.y,
            anchorHeight = anchor.height,
            popupHeight = popupHeightOf(popup.size, popup.content?.preferredSize),
            screenTop = screen.y,
            screenBottom = screen.y + screen.height,
            gap = JBUI.scale(4),
        )

        // 横向：默认贴着锚点的左边缘；会话列表要**居中于面板**，
        // 因为它的锚点是右对齐的标签，标题短时标签缩到最右，
        // 弹层会跟着整块溢出面板（见 popupCenteredX）
        val x = if (centerOverPanel) {
            val panelAt = locationOnScreen
            popupCenteredX(
                panelLeft = panelAt.x,
                panelWidth = width,
                popupWidth = popupWidthOf(popup.size, popup.content?.preferredSize),
                screenLeft = screen.x,
                screenRight = screen.x + screen.width,
            )
        } else {
            at.x
        }

        popup.showInScreenCoordinates(anchor, Point(x, y))
    }

    // ---- 权限模式热切换 ----

    /** 点标签 → 弹模式列表；再点一次 → 收起。 */
    private fun toggleModeChooser() {
        modePopup?.let { open ->
            open.cancel()
            return
        }
        modePopup = showTogglePopup(
            anchor = modeLabel,
            content = buildModeList(currentMode) { pickPermissionMode(it) },
        ) {
            modePopup = null
        }
    }

    // ---- 模型切换 ----

    /** 点模型标签 → 弹切换列表；再点一次 → 收起。 */
    private fun toggleModelChooser() {
        modelPopup?.let { open ->
            open.cancel()
            return
        }
        val profiles = ModelProfiles.getInstance()
        modelPopup = showTogglePopup(modelLabel, buildModelList(
            profiles = profiles.profiles(),
            currentId = profiles.selectedId(),
            onPick = { pick -> switchModel(pick) },
            onManage = { showModelProfilesDialog(project) },
        ), centerOverPanel = true) { modelPopup = null }
    }

    /**
     * 切换模型 = 重开会话。
     *
     * 模型是会话启动参数（`toStartParams` → `start` → `options.model`），
     * 没有热切换这条路。转写历史留着 —— 走 [restartSession] 那条既有路径。
     */
    private fun switchModel(pick: ModelProfile) {
        // 列表的任务到此为止，先收起来 —— 与 [pickPermissionMode] 同一条规矩。
        // 不收的话它会一直挂在面板上，而底下正在重开会话
        modelPopup?.cancel()
        modelPopup = null

        val profiles = ModelProfiles.getInstance()
        if (profiles.selectedId() == pick.id) return

        // 会话进行中先把"上下文会丢"说清楚，别让用户切完才发现。
        // **确认放在改选中态之前**：用户点了取消，选中态就该原样不动。
        // 先改后回滚会留下"标签闪了一下又变回去"的中间态，而且回滚那一步
        // 一旦忘了写，选中态就永久跑偏 —— 这里干脆不给它跑偏的机会
        if (busy && !confirmModelSwitch(pick)) return

        profiles.select(pick.id)
        refreshModelLabel()
        restartSession()
    }

    /**
     * 会话进行中切换时的确认。
     *
     * 这条提示**不能省** —— 少了它用户会以为切完还能接着聊，
     * 等发现上下文没了已经晚了（spec §9）。
     */
    private fun confirmModelSwitch(pick: ModelProfile): Boolean =
        Messages.showYesNoDialog(
            project,
            "切换会重开会话，这段对话的上下文不保留。",
            "切换到「${pick.displayName()}」",
            "切换并重开",
            "取消",
            null,
        ) == Messages.YES

    /**
     * 点会话标签 → 列出历史会话 → 弹层。
     *
     * **先请求、收到后才弹**，而不是先弹一个"载入中"。实测 listSessions 是
     * 纯本地读取，140ms 量级，用户察觉不到；换来的是不必处理"弹出后再换内容"
     * 那套尺寸重算。
     */
    private fun toggleSessionChooser() {
        sessionPopup?.let { open ->
            open.cancel()
            return
        }

        val c = client
        if (c == null) {
            // 不静默吞掉 —— 点了没反应比明说更让人困惑
            pushOp(toOp(RenderItem.SystemNote("会话还没建立，列不出历史会话")))
            return
        }
        val dir = project.basePath
        if (dir == null) {
            pushOp(toOp(RenderItem.ErrorItem("项目没有 basePath，无法定位会话目录。")))
            return
        }

        val reqId = nextId()
        c.request(reqId, Protocol.encodeListSessions(reqId, dir, SESSION_LIST_LIMIT, 0)) { outcome ->
            // 回调在读取线程上，碰 Swing 必须回到 EDT
            ApplicationManager.getApplication().invokeLater {
                when (outcome) {
                    is RequestOutcome.Answered -> {
                        val msg = outcome.message as? SidecarMessage.SessionList
                        if (msg == null) {
                            pushOp(toOp(RenderItem.ErrorItem("会话列表返回了意外的消息。")))
                        } else {
                            showSessionPopup(msg.sessions)
                        }
                    }

                    is RequestOutcome.Failed ->
                        pushOp(toOp(RenderItem.ErrorItem("列出会话失败：${outcome.reason}")))
                }
            }
        }
    }

    private fun showSessionPopup(sessions: List<SessionInfo>) {
        sessionListCache = sessions
        val block = switchBlock(busy, permissionQueue.totalPending)
        val content = buildSessionList(
            sessions, currentSessionId, block,
            onDelete = { s -> requestDeleteSession(s) },
        ) { picked ->
            sessionPopup?.cancel()
            sessionPopup = null
            switchToSession(picked)
        }
        // 居中于面板：锚点（会话标签）右对齐，标题短时弹层会跟着溢出到面板外
        sessionPopup = showTogglePopup(sessionLabel, content, centerOverPanel = true) { sessionPopup = null }
    }

    /** 按缓存的列表重画弹层。删除成功后用。浮层没开着就什么都不做。 */
    private fun refreshSessionList() {
        if (sessionPopup == null) return
        sessionPopup?.cancel()
        sessionPopup = null
        showSessionPopup(sessionListCache)
    }

    // ---- 新建与删除（设计稿 session-manage.html）----

    /**
     * 换一个空会话。
     *
     * **不需要任何确认** —— 旧会话不会丢，它照样在列表里，随时能恢复。
     * 加确认反而是撒谎，暗示这个动作危险（设计稿 §边界 03）。
     *
     * 真正的风险只有一个：你以为在跟旧会话说话，其实已经换了。
     * 所以这一刻标签必须立刻回到「新会话」、转写区清空。
     */
    private fun startNewSession() {
        stopSession()
        currentSessionId = null
        currentSessionTitle = null
        refreshSessionLabel(enabled = true)
        pushOp(TranscriptOp.Reset)
        startSession()
    }

    private fun onNewSession() {
        // 按钮已置灰，这里只是兜底
        if (switchBlock(busy, permissionQueue.totalPending) != SwitchBlock.None) return
        startNewSession()
    }

    /**
     * 用户已确认删除。发请求，**等回执之后才动界面**。
     *
     * 先摘行再等回执的话，删除失败时那一行已经不见了，用户会以为删掉了。
     * 这是本次唯一一个不可逆的操作，宁可慢一拍。
     */
    private fun requestDeleteSession(session: SessionInfo) {
        val c = client
        if (c == null) {
            pushOp(toOp(RenderItem.ErrorItem("删除会话失败：会话通道已关闭")))
            return
        }
        val id = nextId()
        c.request(id, Protocol.encodeDeleteSession(id, session.sessionId)) { outcome ->
            // 回调在读取线程上，碰 Swing 必须回到 EDT
            ApplicationManager.getApplication().invokeLater {
                onDeleteOutcome(session.sessionId, outcome)
            }
        }
    }

    private fun onDeleteOutcome(sessionId: String, outcome: RequestOutcome) {
        when (outcome) {
            is RequestOutcome.Failed ->
                // 行**不放回**（它本来就在），只报错。
                // 失败时把行摘掉才是撒谎：用户会以为删掉了
                pushOp(toOp(RenderItem.ErrorItem("删除会话失败：${outcome.reason}")))

            is RequestOutcome.Answered -> {
                sessionListCache = sessionListCache.filterNot { it.sessionId == sessionId }
                if (sessionId == currentSessionId) {
                    // 删的正是当前会话：停会话、清转写区、回新会话（设计稿 §4.3）。
                    // 与「＋」完全同路 —— 这两件事本来就是一回事
                    startNewSession()
                }
                refreshSessionList()
            }
        }
    }

    /**
     * 切换到另一个历史会话。
     *
     * 步骤见 spec §5.2。**不复用 [restartSession]** —— 那个刻意保留转写历史
     * （见它的注释），而切换要的正是清空，语义相反。
     */
    private fun switchToSession(target: SessionInfo) {
        // 双保险：列表已经把忙时的点击拦住了，但那之后到真正执行之间
        // 状态可能变（比如又来了一个权限询问）
        val block = switchBlock(busy, permissionQueue.totalPending)
        if (block != SwitchBlock.None) {
            pushOp(toOp(RenderItem.SystemNote(switchBlockNotice(block) ?: return)))
            return
        }

        LOG.info("CCoder 切换会话：${target.sessionId}")
        stopSession()
        // 标题从列表里就知道，不必等 loadHistory
        currentSessionTitle = sessionLabelTitle(target)
        refreshSessionLabel(enabled = true)
        resumeTargetId = target.sessionId
        startSession()
    }

    /**
     * 把历史灌进转写区。
     *
     * 历史条目与流式事件**同构**，所以整条渲染管线（含 toOp 的映射）
     * 原样复用，React 侧零改动（spec §6.1）。
     */
    private fun beginReplay(sessionId: String) {
        val c = client
        if (c == null) {
            failReplay("会话通道已关闭")
            return
        }
        val dir = project.basePath
        if (dir == null) {
            failReplay("项目没有 basePath")
            return
        }

        // 清空转写区。Reset 是既有操作，Kotlin 编码与 React 消费都已实现
        // 并有测试（codec.test.ts「reset 清空全部」）
        pushOp(TranscriptOp.Reset)
        setConnection("正在载入…")
        // 回放期间不接受输入：否则历史与实时消息会交错（spec §10 的风险项）
        setBusy(true)

        val reqId = nextId()
        c.request(reqId, Protocol.encodeLoadHistory(reqId, dir, sessionId)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                when (outcome) {
                    is RequestOutcome.Answered -> {
                        val msg = outcome.message as? SidecarMessage.History
                        if (msg == null) {
                            failReplay("历史接口返回了意外的消息")
                        } else {
                            replayItems(msg.items)
                        }
                    }

                    is RequestOutcome.Failed -> failReplay(outcome.reason)
                }
            }
        }
    }

    /**
     * 逐条灌入历史。
     *
     * 先试 [MessageRenderer.renderPrompt]（提问），再走 [MessageRenderer.render]
     * （其余）。顺序不能反：`render` 对 `type:"user"` 一律返回空，所以两条路
     * 不会重复产出。
     */
    private fun replayItems(items: List<JsonObject>) {
        var rendered = 0
        for (item in items) {
            MessageRenderer.renderPrompt(item)?.let {
                pushOp(toOp(RenderItem.UserText(it)))
                rendered++
            }
            val rest = MessageRenderer.render(SidecarMessage.Event(item))
            rest.forEach { pushOp(toOp(it)) }
            rendered += rest.size
        }

        resumeTargetId = null
        setBusy(false)
        setConnection("已连接")
        pushOp(toOp(RenderItem.SystemNote("已恢复会话 · ${items.size} 条历史，其中 $rendered 条可显示")))
    }

    /**
     * 回放失败。
     *
     * **不回退到新会话** —— 那会让用户以为历史加载好了（spec §7.5 反对静默
     * 丢失）。转写区保持在 Reset 之后的空白状态，并明确说明失败原因。
     */
    private fun failReplay(reason: String) {
        resumeTargetId = null
        setBusy(false)
        setConnection("恢复失败")
        currentSessionTitle = null
        refreshSessionLabel(enabled = true)
        pushOp(toOp(RenderItem.ErrorItem("恢复会话失败：$reason")))
    }

    /**
     * 只发请求，**不动标签** —— 标签等回执。
     *
     * 切换失败时标签保持原样，用户看到"没变"外加一条错误说明。
     * 这和"先改后等"的区别，就是控件撒谎与不撒谎的区别。
     */
    private fun pickPermissionMode(mode: PermissionModeSetting) {
        modePopup?.cancel()
        modePopup = null

        // 选模式等于"回到 SDK 的模式语义"，本会话自动放行到此为止。
        // 必须放在下面那个相等判断**之前**：用户完全可能就选着当前这个模式，
        // 目的正是把自动放行关掉（标签此刻显示的是"本会话不再询问"）。
        if (autoAllow) {
            autoAllow = false
            refreshModeLabel()
        }

        if (mode == currentMode) return

        val c = client
        if (c == null) {
            // 没有会话可切。不静默吞掉 —— 点了没反应比明说更让人困惑
            pushOp(toOp(RenderItem.SystemNote("会话还没建立，权限模式切换要先连上会话")))
            return
        }
        c.sendLine(Protocol.encodeSetPermissionMode(nextId(), mode.wireValue))
    }

    /**
     * 会话标签的唯一出口。规则见 [currentSessionTitle]。
     *
     * @param enabled false 时变灰（忙时）—— 但**仍然可点**，见 [setBusy]
     */
    private fun refreshSessionLabel(enabled: Boolean = !busy) {
        sessionLabel.setTitle(currentSessionTitle, enabled = enabled)
    }

    /**
     * 标签的唯一出口。
     *
     * 它显示的是 [currentMode] 与 [autoAllow] 两个字段合起来的状态，所以只留
     * 这一个地方决定显示什么 —— 谁改完状态就调它，免得两处各改各的然后对不上。
     */
    private fun refreshModeLabel() {
        if (autoAllow) modeLabel.setAutoAllow() else modeLabel.setMode(currentMode)
    }

    /** 回合开始/结束时切换按钮。回合结束的信号是 result 事件。 */
    private fun setBusy(value: Boolean) {
        if (busy == value) return
        busy = value
        // 忙时会话标签变灰但**仍然可点** —— spec §5.1 的"点了才说"：
        // 点开能看到置灰的列表加一句说明，比一个点不动的标签强
        refreshSessionLabel(enabled = !value)
        // 「＋」相反：单一动作按钮点了没反应更像坏了，所以直接置灰
        newSessionButton.setBlock(switchBlock(busy, permissionQueue.totalPending))
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

    /**
     * 起一个会话。
     *
     * @param pickMostRecent 打开面板时用：先列出本项目的历史会话，恢复**最近**
     *   的那一条；没有历史或问不出来则退回新会话。**只有打开这条路**传 true ——
     *   「＋」新建、删掉当前会话、断开后重启，语义都是"要一个新的"，不该被抢走。
     *
     * 打开这条路刻意**不是**"先起新会话再切过去"：那样会在硬盘上真的留下一条
     * 空会话（列表里越攒越多），用户也会看见转写区闪一下。
     */
    fun startSession(pickMostRecent: Boolean = false) {
        if (proc != null || starting) return
        starting = true

        // 新会话，旧会话的任务与清单全部作废。
        // SDK 的电平信号"在启动时不发任何东西"，只会在下次成员变动时重发全量 ——
        // 所以消费者必须自己清空，否则上一轮的"2 个运行中"会一直挂在那儿
        runStatus.reset()
        refreshStatusCards()

        // 标题由 [switchToSession] 在切之前就设好了（列表里现成的）；
        // 全新会话这里是 null，标签显示斜体的「新会话」
        refreshSessionLabel()

        // 会话按设置里的模式启动，标签跟着它走 —— 显示的必须是这个会话
        // 真正的起点，而不是上一个会话留下的值
        currentMode = ClaudeSettings.getInstance(project).permissionMode
        // 新会话从零开始：上一个会话的"不再询问"不跟过来
        autoAllow = false
        refreshModeLabel()

        val base = project.basePath
        if (base == null) {
            fail("项目没有 basePath，无法确定工作目录。")
            return
        }
        setConnection("正在启动…")
        refreshMainButton() // ready 仍为 false → 按钮显示"启动中…"并禁用
        LOG.info("CCoder 会话启动：cwd=$base")
        val epoch = ++sessionEpoch

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
                val p = SidecarProcess(sidecarDir, nodePath = "node") { exit ->
                    // 回调在看门狗线程上，碰 Swing 必须回 EDT
                    ApplicationManager.getApplication().invokeLater {
                        // 已经换过会话了，这条死讯属于上一个进程
                        if (epoch != sessionEpoch) return@invokeLater
                        onSidecarDied(exit)
                    }
                }
                p.start()
                val c = SidecarClient(p.stdout!!, p.stdin!!, this)

                ApplicationManager.getApplication().invokeLater {
                    proc = p
                    client = c
                    // 起好了，启动闸归位（见 [starting]）
                    starting = false
                }
                c.start()

                if (!pickMostRecent) {
                    sendStart(c, base)
                    return@executeOnPooledThread
                }

                // 打开面板：先问一句"这个项目有哪些历史会话"，再决定起哪一个。
                // listSessions 不需要活会话（sidecar/index.js:117），所以这里问得出口
                val reqId = nextId()
                c.request(
                    reqId,
                    Protocol.encodeListSessions(reqId, base, SESSION_LIST_LIMIT, 0),
                ) { outcome ->
                    // 回调在读取线程上，碰 Swing 必须回到 EDT
                    ApplicationManager.getApplication().invokeLater {
                        // 抢先发消息会走 stopSession + startSession 重开，那时这个
                        // client 已经废了（照发 start 会打到一条没人读的通道上）。
                        // 那条路的 resumeTargetId 是 null，本来就该开新会话，
                        // 所以这里直接放弃，什么都不用补
                        if (client !== c) return@invokeLater

                        when (val pick = openPick(outcome)) {
                            is OpenPick.Resume -> {
                                LOG.info("CCoder 打开时恢复最近会话：${pick.session.sessionId}")
                                currentSessionTitle = sessionLabelTitle(pick.session)
                                resumeTargetId = pick.session.sessionId
                                refreshSessionLabel(enabled = true)
                            }

                            // 没有历史：一个字都不说，与「＋」新建同一条路。
                            // 这里报"列不出会话"的话，每开一个新项目都会收到一句
                            // 并不存在的错误
                            OpenPick.None -> Unit

                            is OpenPick.Unavailable ->
                                pushOp(
                                    toOp(
                                        RenderItem.SystemNote(
                                            "列不出历史会话（${pick.reason}），已开新会话"
                                        )
                                    )
                                )
                        }
                        sendStart(c, base)
                    }
                }
            } catch (e: SidecarNotFoundException) {
                fail(e.message ?: "未找到 sidecar 目录。")
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
    }

    /**
     * 发出 `start`。
     *
     * 打开面板那条路要等列表回来才发，其余入口立即发 —— 抽出来是为了让两条路
     * 共用一个调用点。各写一遍的话，`resumeTargetId` 这种"发出时才读"的字段
     * 迟早有一处忘了带，而忘了带的表现是"恢复了、但模型没有上下文"，界面上
     * 完全看不出来。
     */
    private fun sendStart(c: SidecarClient, base: String) {
        c.sendLine(
            Protocol.encodeStart(
                nextId(),
                ClaudeSettings.getInstance(project)
                    // 模型配置必须显式传进来：toStartParams 的默认值是 null，
                    // 也就是"一条配置都没配"。漏传不会报错，只会安静地退回旧行为，
                    // 症状是"配了模型却不生效"（spec §5）
                    .toStartParams(Path.of(base), ModelProfiles.getInstance())
                    .copy(resumeSessionId = resumeTargetId),
            )
        )
    }

    private fun fail(text: String) {
        // 起会话中途失败的每一条路都走这里（NodeCheck 不过、sidecar 找不到、
        // 建进程抛错），启动闸必须在这里归位，否则面板从此再也起不了会话
        starting = false
        ApplicationManager.getApplication().invokeLater {
            setConnection("启动失败")
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
     * sidecar 进程**自己**退出了（不是我们杀的 —— 那种情况被 SidecarProcess
     * 的 shuttingDown 挡掉，到不了这里）。
     *
     * 启动期退出必须当成启动失败。否则：进程没了，但 [proc] 非空、`ready` 仍为
     * false，界面停在「正在启动…」且按钮禁用 —— 用户没有恢复路径，只能重启 IDE。
     * 2026-09-13 的现场正是这个形态：`sidecar/package.json` 被写坏，node 报
     * `ERR_INVALID_PACKAGE_CONFIG` 后立刻退出，而那条报错从头到尾没机会显示。
     *
     * 会话建好之后退出是"断开"：转写历史留着供参考，按钮变「重启会话」。
     */
    private fun onSidecarDied(exit: SidecarExit) {
        // 进程已经死了，但它拉起的 claude 可能成了孤儿继续耗额度 ——
        // shutdown 的最后一步是杀整棵树，不能因为进程死了就省掉
        client?.close()
        proc?.shutdown()
        proc = null
        client = null
        starting = false

        val detail = sidecarExitReport(exit)

        if (!ready) {
            // fail 会置 disconnected → 按钮变「重启会话」，用户点一下就能重试，
            // 不必重启 IDE
            fail(detail)
            return
        }

        setConnection("会话已断开")
        ready = false
        disconnected = true
        setBusy(false)
        refreshMainButton()
        pushOp(toOp(RenderItem.ErrorItem(detail)))
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
        // 命令列表随会话走（设计稿 §4.1）。留着它会让未连接时打 `/`
        // 弹出一份过期的
        commandList = emptyList()
        sendableNames = emptySet()
        closeCompletion()

        // 浮层挂在旧会话的状态上，会话没了它就该消失
        runDetailPopup?.cancel()
        runDetailPopup = null
        statusCards.todos.setOpen(false)
        statusCards.running.setOpen(false)

        // 模式列表同理：它选出来的模式要发给会话，会话没了它就没意义
        modePopup?.cancel()
        modePopup = null

        // 浮层挂在旧会话的列表上，会话没了它就该消失
        sessionPopup?.cancel()
        sessionPopup = null

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
                    setConnection("已连接")
                    disconnected = false
                    refreshMainButton()
                    requestCommands()

                    val resuming = resumeTargetId
                    if (resuming != null) {
                        // 恢复路径：id 构造即知，同时把它记成当前会话
                        currentSessionId = resuming
                        beginReplay(resuming)
                    } else {
                        pushOp(toOp(RenderItem.SystemNote("会话已就绪")))
                        // 全新会话：没有标题可显示，标签是斜体的「新会话」
                        currentSessionTitle = null
                        refreshSessionLabel(enabled = true)

                        // 补发窗口就绪前暂存的首条消息
                        pendingFirstMessage?.let { text ->
                            pendingFirstMessage = null
                            client?.sendLine(Protocol.encodeSend(nextId(), text))
                            setBusy(true)
                        }
                    }
                }

                is SidecarMessage.Event -> {
                    val items = MessageRenderer.render(msg)
                    // 命令回合里的空输出丢掉；其余一律照常（设计稿 §5.1）
                    items.filterNot { lastSendWasCommand && isEmptyCommandOutput(it) }
                        .forEach { pushOp(toOp(it)) }
                    // result 是回合结束的信号，此时按钮从"停止"变回"发送"
                    if (items.any { it is RenderItem.Result }) {
                        setBusy(false)
                        lastSendWasCommand = false
                    }

                    // 用量只在 result 事件里给；取不到就保持原样
                    updateUsage(msg.event)
                    // init 事件里那个 model **不再写进标签**：标签现在由
                    // refreshModelLabel 填，写的是用户选中的那条配置（spec §8）。
                    // 直接写 .text 会连它的展开箭头一起抹掉，也会与弹层里
                    // 打勾的那条对不上
                    if (msg.event.str("subtype") == "init") {
                        // 可发送的命令名。与显示名不是一回事（设计稿 §2 事实 5），
                        // 补全列表要靠它才知道选中后该写什么进输入框
                        msg.event.arr("slash_commands")
                            ?.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                            ?.map { it.asString }
                            ?.toSet()
                            ?.let { sendableNames = it }

                        // 真正的会话 id 只在这里。**不读 ready.sessionId** ——
                        // 那个回显的是请求参数，全新会话时是 null（spec §10）。
                        //
                        // 只记 id，**不动标签**：全新会话的标签该保持斜体的「新会话」
                        // （设计稿 A，也是 Task 11 冒烟 6 的验收条件）。
                        // 把 id 前 8 位写上去的话，用户看到的是一串对不上号的 UUID ——
                        // 列表里显示的是标题，不是 id。
                        msg.event.str("session_id")?.let { sid ->
                            if (isSessionSwitch(currentSessionId, sid)) {
                                // /clear：CLI 换了会话，进程不动（设计稿 §5.2）。
                                // 清空转写区而不是插一条分隔线 —— 留着一段
                                // 已经不在上下文里的历史，正是 §7.5 反对的
                                // 那种"看着还在、其实没了"
                                pushOp(TranscriptOp.Reset)
                                currentSessionTitle = null
                                refreshSessionLabel(enabled = true)
                                refreshSessionList()
                                pushOp(toOp(RenderItem.SystemNote("上下文已清空，这是一条新会话")))
                            }
                            // 当前会话指针以 init 里的 id 为准，**不以会话列表为准**
                            // —— 刚 /clear 出来的新会话还没落盘，列表未必列得到它
                            currentSessionId = sid
                        }
                    }
                    // 任务与子代理的状态要走**每一个**事件，不只是 result ——
                    // task_progress 这类事件不会产出任何转写项，但它们正是
                    // "现在在跑什么"的全部信息来源
                    runStatus.consume(msg.event)
                    refreshStatusCards()
                }

                is SidecarMessage.Failure -> {
                    pushOp(toOp(RenderItem.ErrorItem(failureHint(msg.code, msg.message))))
                    if (msg.fatal) {
                        // 不静默重连——重连会让用户误以为上下文还在（spec §7.5）
                        setConnection("会话已断开")
                        ready = false
                        disconnected = true
                        setBusy(false)
                        // 断开后标签变灰但**仍然可点** —— 这正是最需要换个会话的时候
                        refreshSessionLabel(enabled = true)
                        refreshMainButton()
                    }
                }

                is SidecarMessage.Permission -> showPermissionCard(msg)

                // 切换**生效了**才更新标签。认不出的模式名什么都不改 ——
                // 显示一个我们自己都不认识的模式，不如保持原样
                is SidecarMessage.PermissionModeChanged -> {
                    val mode = PermissionModeSetting.entries.firstOrNull { it.wireValue == msg.mode }
                    if (mode == null) {
                        LOG.warn("收到不认识的权限模式回执：${msg.mode}")
                    } else {
                        currentMode = mode
                        refreshModeLabel()
                        // 写回设置：下次启动还按这个模式起会话
                        ClaudeSettings.getInstance(project).permissionMode = mode
                        pushOp(toOp(RenderItem.SystemNote("权限模式已切换为「${mode.label}」")))
                    }
                }

                is SidecarMessage.Exit -> {
                    setConnection("会话已结束")
                    ready = false
                    setBusy(false)
                    refreshMainButton()
                }

                // 请求-响应式的应答本该由 SidecarClient 的待决表按 id 截走
                // （Task 4），到不了这里。列出来只为穷尽性 —— 真漏过来说明
                // 配对没接上，而那个症状会在发起请求的那一侧超时暴露，不在这里补救
                is SidecarMessage.SessionList,
                is SidecarMessage.History,
                is SidecarMessage.SessionDeleted,
                is SidecarMessage.Commands,
                -> Unit

                is SidecarMessage.Unknown -> Unit // 静默忽略（spec §3.3）
            }
        }
    }

    /**
     * 错误码 → 可操作的提示。
     *
     * spec §5.3：认证失败要附带提示。实测最常见的原因是环境变量污染（spec §11.1），
     * 但用户看到 "authentication_failed" 无从下手。
     *
     * 模式切换失败同理：光说"切换失败"没用，得指出往哪儿走。
     */
    private fun failureHint(code: String?, message: String): String = when (code) {
        "AUTH_FAILED" ->
            "$message\n\n请检查 ~/.claude/settings.json 的 env 块是否包含有效的 " +
                "ANTHROPIC_AUTH_TOKEN 与 ANTHROPIC_BASE_URL。\n" +
                "若配置无误，可能是宿主环境变量污染——CCoder 已剥离 " +
                "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST 等 10 个变量（设计文档 §3.2）。"

        // 没有把原因说死：绕过模式究竟能不能热切，取决于 CLI 是否要求
        // 启动时就带那个开关，这一条没验证过（见 session.js 的说明）
        "SET_MODE_FAILED" ->
            "$message\n\n权限模式没有切换。若目标是「绕过权限」，可能是该会话不是" +
                "以它启动的 —— SDK 要求绕过在启动时就声明（sdk.d.ts:1853-1856）。" +
                "可在设置里把权限模式改过去，然后重启会话。"

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

            // 思考流照常推，但**界面默认收着**（逐字铺开会持续刷屏，决策价值低于
            // 正文 —— 这一条没变）。它存在的意义是让人看见"它还在动"：实测 29%
            // 的思考块跑过 5 秒（P90 10.5s，最长 31.5s），而在这之前屏幕上什么都
            // 不动，用户的原话是"看起来感觉像卡死了"。
            // 整块到达时（下面的 Thinking 分支）由界面自己清掉这个缓冲。
            is RenderItem.ThinkingDelta -> TranscriptOp.AppendDelta("thinking", item.text)

            is RenderItem.Thinking ->
                TranscriptOp.Append(TranscriptItem.Thinking(nextMessageId(), now(), item.text))

            is RenderItem.ToolUse ->
                TranscriptOp.Append(
                    TranscriptItem.ToolUse(nextMessageId(), now(), item.id, item.name, item.input)
                )

            // 结果单独成项，按 toolUseId 由界面挂回那张卡片 ——
            // 操作序列因此保持"只追加"，不必去改一条已经推出去的项
            is RenderItem.ToolResult ->
                TranscriptOp.Append(
                    TranscriptItem.ToolResult(
                        nextMessageId(), now(), item.toolUseId, item.text, item.isError,
                    )
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
        // 本会话不再询问：不回卡片，直接放行。
        // AskUserQuestion 除外 —— 那不是授权请求，是在问你要答案，
        // 自动"允许"等于把那个问题吞掉（见 PermissionOptions.autoAllowApplies）
        if (autoAllow && PermissionOptions.autoAllowApplies(perm)) {
            sendDecision(
                perm,
                PermissionDecision(allow = true, updatedPermissions = null, message = null),
            )
            return
        }
        permissionQueue.enqueue(perm)
        if (!isShowing) notifyPendingPermission(perm)
    }

    /**
     * 该用哪张卡片。
     *
     * `AskUserQuestion` 走 [AskQuestionCard] —— 它的语义是**选哪一个**，
     * 而通用卡片的「拒绝 / 允许」根本表达不了，用户只能看着原始 JSON 发愣。
     *
     * 解析不出来就**退回**通用卡片。显示一张渲染不全的提问卡片比显示原始
     * JSON 更糟：用户会以为那就是全部的问题，然后把一个不完整的答案送回去。
     */
    private fun buildPermissionCard(
        perm: SidecarMessage.Permission,
        queuedCount: Int,
    ): JComponent {
        val request = if (perm.toolName == ASK_TOOL_NAME) askRequestOf(perm.input) else null

        if (request == null) {
            return PermissionCard(perm, queuedCount) { decision -> decide(perm, decision) }
        }

        return AskQuestionCard(
            request,
            onSubmit = { picked ->
                decide(
                    perm,
                    PermissionDecision(
                        allow = true,
                        updatedPermissions = null,
                        message = null,
                        // 答案就是这么回传的：允许这个工具调用时改写它的入参
                        updatedInput = updatedInputFor(perm.input, request, picked),
                    ),
                    // 转写区里留一行"我选了哪个" —— 那正是这次交互的全部内容
                    note = "已作答：${picked.values.flatten().joinToString("、")}",
                )
            },
            onDeny = {
                decide(
                    perm,
                    PermissionDecision(allow = false, updatedPermissions = null, message = "用户拒绝"),
                )
            },
        )
    }

    private fun decide(
        perm: SidecarMessage.Permission,
        decision: PermissionDecision,
        note: String? = null,
    ) {
        if (decision.stopAsking) {
            // 开关先拨上，再回决定 —— 决定回完这条就结束了，中间的窗口越短越好。
            //
            // 已知的缺口：**已经排在队列里**的那几条仍会逐个弹卡片。
            // PermissionQueue 只发 activate 回调，不经过 showPermissionCard，
            // 所以这里够不着它们。数量有限（同一条 assistant 消息里的并行
            // 工具调用），点完就到底，没有单独修。
            autoAllow = true
            refreshModeLabel()
        }
        sendDecision(perm, decision)

        pushOp(
            toOp(
                RenderItem.SystemNote(
                    note ?: when {
                        decision.stopAsking -> "已允许：${perm.toolName}，$AUTO_ALLOW_LABEL"
                        decision.allow -> "已允许：${perm.toolName}"
                        else -> "已拒绝：${perm.toolName}"
                    }
                )
            )
        )
    }

    /**
     * 把决定送上线路，并收拾界面上的痕迹。
     *
     * 自动放行那条路也走它 —— 两处各写一份发送逻辑，迟早有一处漏掉
     * 清卡片或清状态栏。
     */
    private fun sendDecision(perm: SidecarMessage.Permission, decision: PermissionDecision) {
        client?.sendLine(
            Protocol.encodePermissionDecision(
                nextId(), perm.requestId,
                decision.allow, decision.updatedPermissions, decision.message,
                decision.updatedInput,
            )
        )
        permissionQueue.resolve(perm.requestId, decision)

        // 卡片换成一行结论，不再占据视线
        pendingCards.remove(perm.requestId)?.let { wrapper ->
            permissionSlot.remove(wrapper)
            permissionSlot.revalidate()
            permissionSlot.repaint()
        }
        updateStatusBar()
    }

    private fun appendPermissionCard(perm: SidecarMessage.Permission, queuedCount: Int) {
        val card = buildPermissionCard(perm, queuedCount)

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
        // 权限队列变化同样影响忙闲 —— 「＋」得跟着
        newSessionButton.setBlock(switchBlock(busy, permissionQueue.totalPending))
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

    // ---- 补全（设计稿 §3）----

    /**
     * 重算候选并更新弹层。文档变化与光标变化都走这里。
     *
     * **两类触发各自过滤，不共用 [filterCandidates]。** 文件那条要的是
     * "路径前缀**或文件名**前缀"（`Comp` 要能命中
     * `src/main/kotlin/com/ccoder/ui/Composer.kt`），而通用过滤走的是
     * 显示串整体前缀 —— 共用的话按文件名搜会一条都搜不到。
     */
    private fun refreshCompletion() {
        if (suppressCompletion) return
        val q = completionQuery(input.text, input.caretPosition)
        if (q == null) return closeCompletion()

        val filtered = visibleCandidates(
            when (q.trigger) {
                Trigger.Command ->
                    filterCandidates(commandCandidates(commandList, sendableNames), q.query)

                Trigger.File -> fileCandidates(allProjectFiles(), q.query)
            }
        )
        if (filtered.isEmpty()) return closeCompletion()

        completionQuery = q
        completionItems = filtered
        completionIndex = 0
        showCompletion()
    }

    /** 全部项目文件，按弹层生命周期缓存，见 [projectFiles]。 */
    private fun allProjectFiles(): List<String> =
        projectFiles ?: collectProjectFiles(project).also { projectFiles = it }

    private fun showCompletion() {
        val caret = caretRect()
        // modelToView2D 在没有布局时返回 null（面板还没显示），此时不弹
        if (caret == null) return closeCompletion()
        completion.show(input, caret, completionItems, completionIndex)
    }

    /**
     * 光标那一格的矩形（相对输入框）。
     *
     * `JBTextArea.modelToView2D` 只在组件已布局时有效，未布局时返回 null ——
     * 直接解引用会在面板还没显示时 NPE。
     */
    private fun caretRect(): Rectangle? =
        runCatching { input.modelToView2D(input.caretPosition)?.bounds }.getOrNull()

    private fun moveCompletion(delta: Int) {
        completionIndex = nextHighlight(completionIndex, delta, completionItems.size)
        showCompletion()
    }

    private fun acceptCompletion() {
        val q = completionQuery ?: return
        val item = completionItems.getOrNull(completionIndex) ?: return
        val (text, caret) = applyCompletion(input.text, input.caretPosition, q, item)

        // 程序化改写会触发文档监听；不挡住的话它会拿旧的光标位置重算一次，
        // 命令那种没有尾随空格的文本还会把弹层又弹回来
        suppressCompletion = true
        try {
            input.text = text
            input.caretPosition = caret
        } finally {
            suppressCompletion = false
        }
        closeCompletion()
    }

    private fun closeCompletion() {
        completion.hide()
        completionQuery = null
        completionItems = emptyList()
        completionIndex = 0
        // 关层即丢缓存：下次打开能看到这一轮新建的文件
        projectFiles = null
    }

    /** 会话就绪后拉一次命令列表。取不到就保持空 —— 补全靠不到它照常工作。 */
    private fun requestCommands() {
        val c = client ?: return
        val reqId = nextId()
        c.request(reqId, Protocol.encodeListCommands(reqId)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                val msg = (outcome as? RequestOutcome.Answered)?.message as? SidecarMessage.Commands
                    ?: return@invokeLater
                // msg.skills 暂时不用：分组走可发送名的命名空间（见 GROUP_PLUGIN）。
                // 那份数据接进来是因为它是 SDK 的真实应答，将来 CLI 支持
                // reload_skills 时分组能更准
                commandList = msg.commands
            }
        }
    }

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
        // 记住这一回合是不是命令 —— 命令的空输出不该画气泡（设计稿 §5.1）。
        // 判据只能用"发出去的是什么"：实测 result.local_command 恒为 null
        lastSendWasCommand = text.startsWith("/")
        // 发出后进入"忙"：按钮变"停止"，直到 result 到达
        setBusy(true)
    }

    /**
     * 追加一段文本到输入框（右键「添加到 CCoder 聊天框」的落点）。
     *
     * 顺带把工具窗口激活、焦点抢到输入框：不然点完右键菜单**看不到任何反应** ——
     * 文本被追加进一个没打开的窗口，用户会以为动作失败了。
     */
    fun addToComposer(text: String) {
        appendSnippet(input, text)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        input.requestFocusInWindow()
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun nextId(): String = "req-${idCounter++}"

    private fun nextMessageId(): String = "m${messageCounter++}"

    private companion object {
        const val NOTIFICATION_GROUP = "CCoder Permissions"
        const val TOOL_WINDOW_ID = "CCoder"

        /** 一屏够看了。不做翻页 —— 实测本机 19 条会话。 */
        const val SESSION_LIST_LIMIT = 50

        val LOG = Logger.getInstance(ClaudePanel::class.java)
    }
}
