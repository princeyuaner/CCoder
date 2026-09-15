package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo
import com.ccoder.sidecar.NodeCheck
import com.ccoder.sidecar.NodeStatus
import com.ccoder.sidecar.OutgoingImage
import com.ccoder.sidecar.Protocol
import com.ccoder.sidecar.RequestOutcome
import com.ccoder.sidecar.SessionInfo
import com.ccoder.sidecar.SidecarClient
import com.ccoder.sidecar.SidecarExit
import com.ccoder.sidecar.SidecarListener
import com.ccoder.sidecar.SidecarLocator
import com.ccoder.sidecar.SidecarMessage
import com.ccoder.sidecar.SidecarNotFoundException
import com.ccoder.sidecar.SubagentInfo
import com.ccoder.sidecar.SidecarProcess
import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.ccoder.settings.ClaudeSettings
import com.ccoder.settings.EffortSetting
import com.ccoder.settings.McpStatus
import com.ccoder.settings.ModelProfile
import com.ccoder.settings.ModelProfiles
import com.ccoder.settings.PermissionModeSetting
import com.ccoder.settings.PromptPreset
import com.ccoder.settings.PromptPresets
import com.ccoder.settings.displayName
import com.ccoder.settings.showSettingsDialog
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbService
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
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Path
import javax.swing.Box
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
 *   输入区（原生）
 *
 * 输入区刻意留在原生：中文输入法考虑。
 *
 * 权限与提问从 2026-09-14 起是**模态框**（[PermissionDialog] / [AskSequence]），
 * 不再占布局里的位置 —— 工具窗口没开着的时候它们照样弹得出来，这正是改形态的理由。
 * 留在原生的理由没变：审批 UI 是安全关键路径，不该依赖 Web 视图的可用性。
 */
class ClaudePanel(private val project: Project) : JPanel(BorderLayout()), SidecarListener, Disposable {

    private val transcriptView = ClaudeTranscriptView(project)

    /**
     * 连接状态的**文字源**。
     *
     * 留一个纯字符串而不是直接拿卡当状态：文字 → 色调是一次纯逻辑映射
     * （[connectionTone]），能在无头单测里钉住；卡本身是 Swing。
     */
    private var connectionText = "未连接"

    /**
     * 连接卡上那行字：**空闲时是连接状态，忙时是"现在在做什么"**。
     *
     * 映射规则在 [activityChangeOf]（纯函数，能单测）。null = 没有正在跑的动作。
     */
    private var activity: String? = null

    /**
     * 四张状态卡。**常驻** —— 没内容的格子收边，不隐藏。
     *
     * 卡一会儿出现一会儿消失，输入框就会在会话中途上下跳；稳定比安静重要。
     */
    private val statusCards = StatusCardsRow(
        onOpenContext = { toggleDetail(DetailCard.Context) },
        onOpenTodos = { toggleDetail(DetailCard.Todos) },
        onOpenRunning = { toggleDetail(DetailCard.Running) },
    )

    /** 最近一次拿到的上下文用量。取不到时保持 null —— 不造零值。 */
    private var lastUsage: ContextUsage? = null

    /**
     * 正在问子代理列表。
     *
     * 那一拍浮层还没建出来，所以"再点一次收起"不能靠 `runDetailPopup != null`
     * 判断 —— 用这个字段兜住，否则响应回来时会把用户已经放弃的浮层弹出来。
     */
    private var subagentsLoading = false

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
    /**
     * ↑/↓ 翻已发送的历史（行为照 shell：没发出去的那半句会被存成草稿，↓ 走到底
     * 就还回来）。
     *
     * 只活在内存里 —— 面板关掉就没了；跨重启的那份该由设置来管，不混进这里。
     */
    private val inputHistory = InputHistory()

    /**
     * 工具栏最左的附件按钮：点开选文件，加进输入框（分流见 [chooseFilesToAdd]）。
     *
     * 它**没有禁用态** —— 往输入框里添东西不依赖会话是否就绪，断了线也该能先攒着。
     */
    private val attachButton = AttachButton().apply { onClick = { chooseFilesToAdd() } }

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

    /** 思考深度。可点，点开切换。 */
    private val effortLabel = EffortLabel { toggleEffortChooser() }

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

    /**
     * 当前生效的思考深度。
     *
     * 与 [currentMode] 同一条规矩：**用户点的那一下不直接改它**，等 sidecar
     * 的回执到了才改。先改标签后等结果的话，切换失败时标签会显示一个没生效的
     * 档位 —— 而用户正是靠着这个标签判断这一轮要花多少 token。
     */
    private var currentEffort: EffortSetting = ClaudeSettings.getInstance(project).effort

    /** 打开着的思考深度浮层。同上，也由它实现"再点一次收起"。 */
    private var effortPopup: JBPopup? = null

    /**
     * 在途的那次换模型请求（热切换）。
     *
     * 回执里**只有模型名**，没有"哪条配置"—— 而两条填了同样端点的配置之间也能
     * 热切（[canHotSwitch] 有意允许），所以光靠模型名回推不出该写进哪一条。
     * 意图得自己攥着。
     *
     * 它同时是一道校验：回执里的名字必须等于我们发出去的那个。不等就说明有
     * 别的 `setModel` 在飞（或乱序），那时什么都不改比改错强 —— 同
     * [currentMode] 那条"认不出的就不动标签"。
     */
    private var pendingModelPick: ModelPick? = null

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
     * 右上角的齿轮。造型与「＋」共用一份（见 [settingsGearButton] 与
     * [TopRowIconButton]）—— 同一行里两个按钮，一个有边框一个没有会很扎眼。
     *
     * **不随忙闲置灰**：它开的是设置对话框，而对话框只读写配置、不碰会话
     * （见 [showSettingsDialog]），会话进行中也该能开。
     */
    private val settingsButton = settingsGearButton { openModelSettings() }

    /** 「＋」。位置与间距见 [buildTopRow]（2026-09-14 起在齿轮左边）。 */
    private val newSessionButton = SessionNewButton { onNewSession() }

    /**
     * 待发的图（贴图）。挂在输入卡里，空的时候它自己收起来。
     *
     * 回调在 [buildUI] 里接上 —— 这里还不知道输入卡在哪（见 AttachmentStrip 的说明）。
     */
    private val attachments = AttachmentStrip()

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
     * 挂着的那张详情卡。
     *
     * 三张卡（子任务 / 子代理 / 上下文）共用一个浮层 —— 同一时刻只该有一个挂着，
     * 而这张记着是谁，好在关闭时把对应那张取消高亮。
     */
    private var openDetail: DetailCard? = null

    /** 哪张卡的详情浮层。连接卡没有详情，所以不在其中。 */
    private enum class DetailCard { Context, Todos, Running }

    /** 回合进行中：已发出消息，但还没收到 result。 */
    private var busy = false

    /** 会话 fatal 断开：界面还在，但进程已死，需要重启。 */
    private var disconnected = false
    private var idCounter = 0L
    private var messageCounter = 0L

    /** 护着 [messageCounter]：回放那条路在池线程上取号（见 nextMessageId）。 */
    private val messageIdLock = Any()

    /**
     * 会话代次。每次 [startSession] 递增。
     *
     * 用来丢弃"上一个 sidecar 进程死了"的陈旧消息：换会话时会先杀掉旧进程，
     * 它的死讯属于上一条会话，不该打到新会话的界面上。
     */
    private var sessionEpoch = 0

    /**
     * 并发权限询问的串行化队列（spec §6.4）。
     *
     * 一次只弹一个框：队列的串行语义一行没改（见 [PermissionQueue]），
     * 变的只是被激活那一项的去处 —— 从"插一张卡片"变成"弹一个模态框"。
     */
    private val permissionQueue = PermissionQueue { perm, queued -> openPermissionDialog(perm, queued) }

    /**
     * 片段记号 → 发送时要展开成的那段文本。
     *
     * 右键加选区进来的是**一行记号**（见 [refToken]），完整片段存在这里，
     * 发送的那一刻才换上 —— 输入框因此不会被三十行代码顶满。
     */
    private val snippetRefs = SnippetRefs()

    /** 用量请求的闸：一次只允许一个在途（见 [UsageRequestGate]）。 */
    private val usageGate = UsageRequestGate()

    /** 当前挂着的权限框。终止路径要把它关掉（那时不能回决定）。 */
    private var permissionDialog: PermissionDialog? = null

    /** 当前挂着的那串提问框。一次 `AskUserQuestion` 可能有好几道题。 */
    private var askSequence: AskSequence? = null

    /** 懒启动（spec §7.2）：第一次发消息才起 sidecar。 */
    private var pendingFirstMessage: String? = null

    /**
     * 上面那条的**原始输入**（没展开记号的版本），只用来认标题。
     *
     * 单独存一份是因为 `pendingFirstMessage` 是展开后的文本 —— 里面可能是一大段
     * 代码围栏，拿它当标题会变成「```kotlin …」。
     */
    private var pendingFirstMessageTitle: String? = null

    /**
     * 暂存的那条消息带着的图（贴图）。
     *
     * 与 `pendingFirstMessage` 同生共死：就绪前粘的截图也是用户已经发出去的东西，
     * 不能因为会话慢了一拍就把它丢了 —— 补发时两者一起走。
     */
    private var pendingFirstMessageImages: List<AttachedImage> = emptyList()

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

    /** 排队中的输入（spec §3）。忙时回车进这里，回合结束由 [flushQueue] 发出去。 */
    private val queue = SendQueue()

    /** 排队条本体。存成字段而不是现场 new —— 刷新时要直接够得着它。 */
    private val queueStrip = QueueStrip { removeQueued(it) }

    init {
        // 补全：文本变了就重算候选。用文档监听而不是按键监听 ——
        // 粘贴、撤销、退格都会改文本，而它们不都是"按键"
        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                refreshCompletion()
                // 记号（⟦…⟧）的底色跟着文本重刷 —— 它会被粘贴、被删、被拆开
                applyRefHighlights(input)
            }
        })
        // 光标挪走（点了一下别处）时弹层要跟着收，否则它会停在一个
        // 已经没有查询词的位置上
        input.addCaretListener { refreshCompletion() }

        // 贴图：剪贴板里的截图、拖进来的图片文件，都收进附件带。
        // 文字粘贴走的还是原来那个处理器（见 installImagePaste 的说明）
        installImagePaste(input) { incoming -> incoming.forEach(::addAttachment) }

        // 诊断：Ctrl+V 到底有没有到输入框。平台那条链（$Paste → PasteProvider）
        // 只在焦点合适时才轮到我们，这一行能把"焦点不在这儿"和"平台没调我们"分开
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_V && e.isControlDown) {
                    LOG.info("贴图：输入框收到了 Ctrl+V（Swing 层）")
                }
            }
        })

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

                // ↑/↓ 翻历史输入（2026-09-15）。**只在光标贴到首行/末行时才接管** ——
                // 光标夹在中间时那两下是正常的光标移动，抢了就成了"上下键失灵"。
                // 补全开着时上面已经短路了，所以这两件事不会打架
                if (!e.isShiftDown && !e.isControlDown && !e.isAltDown) {
                    val recalled = when {
                        e.keyCode == KeyEvent.VK_UP && onFirstLine(input.text, input.caretPosition) ->
                            inputHistory.prev(input.text)

                        e.keyCode == KeyEvent.VK_DOWN && onLastLine(input.text, input.caretPosition) ->
                            inputHistory.next()

                        else -> null
                    }
                    if (recalled != null) {
                        e.consume()
                        input.text = recalled
                        input.caretPosition = recalled.length
                        return
                    }
                }

                // 哪个键算发送由设置决定（聊天惯例 / 编辑器惯例，见 SendShortcut）
                val shortcut = ClaudeSettings.getInstance(project).sendShortcut

                // 忙时**不再拦在这里**（2026-09-15 改）。从前这里是 `if (busy) return`，
                // 理由写在 mainButtonState 上："按钮那时是停止，发送键却另发一条会让
                // 两者语义打架"。现在不打架了：回合进行中按回车是**排队**
                // （分岔在 sendCurrentInput 里，spec §5.2）—— 而这一行拦在前头的话，
                // 那句话永远进不了队，整个排队功能成了死代码。
                if (isSendKey(e.keyCode, e.isShiftDown, e.isControlDown, shortcut)) {
                    e.consume()
                    sendCurrentInput()
                }
            }
        })

        // 顶部：左边是连接状态，右边是会话标签（可点，点开列历史会话）
        // 与「＋」新建。发送/停止按钮在输入区下方的工具栏里
        // 顶部这一行现在只有会话：标签靠左，「＋」与齿轮在右。
        // 连接状态已经挪到下面的上下文行 —— 那一行原先只为了它一个人撑高度。
        //
        // 布局本身在 [buildTopRow] 里：它可测（这一行不可测 —— 依赖 Project），
        // 而"两个按钮挨多近""谁在左"正是那一版改的两件事。
        val top = buildTopRow(sessionLabel, settingsButton, newSessionButton)

        // 滚动面板与视口都设为透明，否则会盖住输入框自己的底色与边框
        val inputScroll = JBScrollPane(input).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }

        // 底部工具栏：附件按钮在最左，接着是模型、权限模式与思考深度，发送键在右
        refreshModeLabel()
        refreshModelLabel()
        refreshEffortLabel()
        val composerToolbar =
            buildComposerToolbar(attachButton, modelLabel, modeLabel, effortLabel, sendButton)

        // 四张卡先灌一次初值，否则它们是一排没有内容的空框
        refreshStatusCards()

        val inputArea = buildComposerCard(inputScroll, composerToolbar, attachments)
        // 附件带展开/收起会改卡片高度 —— 得让布局重算，不然第一张图会压在
        // 输入框上画出来（附件带占的高度是凭空长出来的那一截）
        attachments.onChanged = { inputArea.revalidate() }

        // 权限卡与状态卡共用 NORTH：两块都在输入卡**外面**、它的上方。
        // 顺序是权限卡在上（它更急）、状态卡紧贴输入框
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            // **三个子项一律左对齐**（JLabel / JPanel 的默认是 0.5 = 居中）。
            // BoxLayout 摆不拉伸的子项用的是"加权平均对齐点"，排队条为了铺满
            // 整行把 max 宽度开到 Short.MAX_VALUE，那一个巨大的权重会把平均值
            // 拖向它自己 —— 结果整条带子被推到中间去（2026-09-15 探头图里
            // 抓到：414 宽的头里它待在 x=113、宽 301）。全设成 0 之后，
            // 平均值恒为 0，各归各位。
            alignmentX = LEFT_ALIGNMENT
            statusCards.alignmentX = LEFT_ALIGNMENT
            add(statusCards)
            // 卡片与输入框之间留一口气。紧贴着看时，四张卡像是输入框的一部分
            // （而且状态卡是"常驻控件"，不是输入区里的一行）。strut 宽 0，
            // 不参与对齐的加权平均，不必管它
            add(Box.createVerticalStrut(JBUI.scale(7)))
            // 排队条紧贴输入卡：它是"还没发出去的输入"，不是状态。
            // **直接挂成子项**，不包一层：实测 BoxLayout 会跳过不可见的子项
            // （空队列时这一行的高度一分不占）
            add(queueStrip)
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
        sendButton.setState(mainButtonState(ready, busy, disconnected, queued = queue.size))
    }

    private fun onMainButtonClick() {
        when (mainButtonState(ready, busy, disconnected).action) {
            // 只中断当前回合：会话与上下文都保留，可以接着聊。
            // 用 "stop" 会销毁整个会话（见 mainButtonState 的说明）
            MainAction.Interrupt -> {
                // 排队的一起没：队列里的还没发出去，清掉是本地动作，不必等回执
                // （SDK 那一侧本来也没有回执，spec §2 事实 05）
                clearQueue()
                // sidecar 收到 interrupt 会把挂起的 canUseTool 全部 deny 掉
                // （session.js 的 denyAllPending），界面上那些框也得跟着消失 ——
                // 否则屏幕上留着一个"点了也没人收"的模态框
                permissionQueue.cancelAll()
                closeDecisionDialogs()
                updateStatusBar()
                client?.sendLine(Protocol.encodeSimple(nextId(), "interrupt"))
            }

            MainAction.Restart -> restartSession()

            MainAction.Send -> sendCurrentInput()

            MainAction.Disabled -> Unit
        }
    }

    /**
     * 问一次上下文用量。
     *
     * 会话建立后问一次，每轮跑完再问一次。**恢复的会话也走同一条路** ——
     * 实测 CLI 一条消息都没发就能答，而且会把恢复的历史算进去（resume 一条长
     * 会话报的是 Messages 45 万，不是 0），所以不必等第一轮、也不必自己从
     * 历史里推。
     *
     * 读失败**不往转写区插错误**：读不到用量不值得打断用户，卡片自己会显示
     * "没测量值"（0）。但必须留痕 —— 否则"卡片一直不动"这种症状无从查起。
     */
    private fun requestContextUsage() {
        val c = client ?: return
        // 已经有在途的就不发第二条（见 UsageRequestGate 里那次事故）
        if (!usageGate.acquire()) return
        val reqId = nextId()
        c.request(reqId, Protocol.encodeContextUsage(reqId)) { outcome ->
            // 回调在读取线程上，碰 Swing 必须回到 EDT
            ApplicationManager.getApplication().invokeLater {
                // 放闸放在 when **之前**：成功、失败、超时三条路都要放，
                // 漏一条就再也问不到用量了
                usageGate.release()
                when (outcome) {
                    is RequestOutcome.Answered -> {
                        val report = outcome.message as? SidecarMessage.ContextUsageReport
                        if (report == null) {
                            LOG.warn("上下文用量返回了意外的消息")
                        } else {
                            lastUsage = ContextUsage(report.usedTokens, report.windowTokens)
                            refreshStatusCards()
                        }
                    }

                    is RequestOutcome.Failed -> LOG.warn("读取上下文用量失败：${outcome.reason}")
                }
            }
        }
    }

    /**
     * 问一次各 MCP server 的实时状态，写进 [McpStatus] 让设置页右栏显示。
     *
     * 与 [requestContextUsage] 同一条：失败**不往转写区插错误** —— 读不到状态
     * 不值得打断用户，但要留痕，否则"面板那一栏一直空着"无从查起。
     *
     * 它是**本地控制请求**（不产生模型调用），所以开会话时问一次、打开设置前
     * 再问一次都不心疼。问两次是因为侧重点不同：开会话那次给页面一个底，
     * 打开设置那次保证用户看到的是"此刻"。
     */
    private fun requestMcpStatus() {
        val c = client ?: return
        val reqId = nextId()
        c.request(reqId, Protocol.encodeMcpServerStatus(reqId)) { outcome ->
            // 回调在读取线程上，碰服务虽不碰 Swing，但下面会触发监听器去改界面
            ApplicationManager.getApplication().invokeLater {
                when (outcome) {
                    is RequestOutcome.Answered -> {
                        val report = outcome.message as? SidecarMessage.McpServers
                        if (report == null) {
                            LOG.warn("MCP 状态返回了意外的消息")
                        } else {
                            McpStatus.getInstance(project).set(report.servers)
                        }
                    }

                    is RequestOutcome.Failed -> LOG.warn("读取 MCP 状态失败：${outcome.reason}")
                }
            }
        }
    }

    /** 唯一的连接状态写入口。文字变了，卡上的点与色跟着变。 */
    private fun setConnection(text: String) {
        connectionText = text
        refreshStatusCards()
    }

    /**
     * 当前动作的唯一写入口。
     *
     * **值没变就直接返回**：流式期间这条会被每个 token 调一次，而"思考中"
     * 要连着几十上百次增量保持不变 —— 不挡一下就是每个 token 重画一次四张卡。
     */
    private fun setActivity(text: String?) {
        if (activity == text) return
        activity = text
        refreshStatusCards()
    }

    /**
     * 按当前四份数据重画四张卡。
     *
     * 没内容的格子由 [StatusCardModel.quiet] 收边 —— 不是隐藏，四张卡始终在。
     */
    private fun refreshStatusCards() {
        // 忙时这张卡改说"在干什么"：转写区是滚动区，长任务跑起来最新的那条
        // 早就滚上去了，抬头一眼能看见的只有这里
        statusCards.connection.setModel(
            activity?.let(::activityCardOf) ?: connectionCardOf(connectionText)
        )
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
    private fun toggleDetail(card: DetailCard) {
        val view = viewOf(card)
        val wasOpen = openDetail == card && runDetailPopup != null

        closeDetail()

        if (wasOpen) {
            // 还能顺手取消一次正在路上的"打开"
            subagentsLoading = false
            return
        }

        openDetail = card
        when (card) {
            // 上下文那段是纯本地的（用量就在手上），不用问 sidecar
            DetailCard.Context -> showDetailPopup(view, buildContextDetail(lastUsage))

            DetailCard.Todos -> showDetailPopup(
                view,
                runStatus.todos?.let(::buildTodoDetail)
                    ?: buildRunningDetail(emptyList(), emptyList()) {},
            )

            // 子代理那一段要问一次 sidecar —— 它的记录在磁盘上，不在事件流里。
            // **先请求、收到才弹**（同会话列表）：不先弹一个"载入中"，
            // 免得还要处理"弹出后再换内容"那套尺寸重算
            DetailCard.Running -> {
                subagentsLoading = true
                requestSubagents(view)
            }
        }
    }

    private fun viewOf(card: DetailCard): StatusCardView = when (card) {
        DetailCard.Context -> statusCards.context
        DetailCard.Todos -> statusCards.todos
        DetailCard.Running -> statusCards.running
    }

    /** 收起详情浮层：三张卡的高亮一起取消。 */
    private fun closeDetail() {
        runDetailPopup?.cancel()
        runDetailPopup = null
        openDetail = null
        statusCards.context.setOpen(false)
        statusCards.todos.setOpen(false)
        statusCards.running.setOpen(false)
    }

    /**
     * 问一次这个会话的子代理。
     *
     * 拿不到会话或目录时**仍然把浮层弹出来**（只列运行中那段）：点了没反应
     * 比一个少一段的浮层更像坏了。
     */
    private fun requestSubagents(card: StatusCardView) {
        val c = client
        val dir = project.basePath
        val sessionId = currentSessionId
        if (c == null || dir == null || sessionId == null) {
            subagentsLoading = false
            showDetailPopup(card, buildRunningDetail(runStatus.running, emptyList()) {})
            return
        }

        val id = nextId()
        c.request(id, Protocol.encodeListSubagents(id, dir, sessionId)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                // 应答回来时用户可能已经把它收起来了
                if (!subagentsLoading) return@invokeLater
                subagentsLoading = false

                val msg = (outcome as? RequestOutcome.Answered)?.message
                if (msg !is SidecarMessage.Subagents) {
                    // 读不到就不列那一段，但浮层照弹 —— 运行中那段还是有用的
                    LOG.warn("列出子代理失败：${(outcome as? RequestOutcome.Failed)?.reason}")
                }
                val agents = (msg as? SidecarMessage.Subagents)?.agents ?: emptyList()
                showDetailPopup(
                    card,
                    buildRunningDetail(runStatus.running, agents, ::openSubagentTranscript),
                )
            }
        }
    }

    /**
     * 看一个子代理的转写。**换页**而不是另开一个浮层：两个叠在一起的话，
     * 关掉上面那个会把下面那个一起带走（同一套 showTogglePopup 的关闭语义），
     * 用户会觉得"点了一下全没了"。
     */
    private fun openSubagentTranscript(agent: SubagentInfo) {
        val c = client
        val dir = project.basePath
        val sessionId = currentSessionId
        if (c == null || dir == null || sessionId == null) return

        val id = nextId()
        c.request(id, Protocol.encodeSubagentMessages(id, dir, sessionId, agent.agentId)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                val msg = (outcome as? RequestOutcome.Answered)?.message
                if (msg !is SidecarMessage.SubagentMessages) {
                    pushItem(
                        RenderItem.ErrorItem(
                            "读子代理转写失败：${(outcome as? RequestOutcome.Failed)?.reason ?: "没有回执"}"
                        )
                    )
                    return@invokeLater
                }
                showDetailPopup(statusCards.running, buildSubagentDetail(agent, msg.items))
            }
        }
    }

    /** 把详情浮层挂到某张卡上。首次打开与换页走同一条。 */
    private fun showDetailPopup(card: StatusCardView, content: JComponent) {
        // 先取消旧的：它的 onClosed 会把字段置空，所以必须排在赋值之前
        runDetailPopup?.cancel()
        runDetailPopup = showTogglePopup(anchor = card, content = content) {
            // 点浮层外面关掉时也要把高亮与"开着谁"一起清掉 ——
            // 少了这一句，那张卡会一直亮着，再点它反而变成"收起"
            closeDetail()
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

    /**
     * 打开设置对话框，**关掉之后把标签拉回真相**。
     *
     * 对话框是模态的（`DialogWrapper.show()` 关闭即返回），而它里面能删掉或改名
     * 「使用中」那条 —— 真相在 [ModelProfiles] 里。不在这里补这一下的话，
     * 标签会把一条已经删掉的配置一直显示到面板生命周期结束：用户看到的是
     * "还在用它"，实际下次开会话会退回旧字段。控件撒谎比它不好用严重
     * （[currentMode] 那条同理）。
     *
     * 同理还有 [applySavedSettingsToSession]：那一页现在也能改权限模式与思考深度，
     * 而这两项的活控制本来是输入框左下角的标签 —— 在设置里改完却要等下次开会话
     * 才生效，与标签那儿的即时手感是两套规矩。
     */
    private fun openModelSettings() {
        // 先把 MCP 状态问一遍：右栏读的是 McpStatus 服务，不先问就显示上一次的
        requestMcpStatus()
        showSettingsDialog(project)
        refreshModelLabel()
        applySavedSettingsToSession()
    }

    /**
     * 关框之后，把「权限模式」「思考深度」推到**正在跑**的会话上。
     *
     * 设置对话框自己只写配置、不碰会话（那条分界从模型配置那一版起就没动过），
     * 由这里对账 —— 它已经是"关框后拉回真相"的那一处（上面那句 `refreshModelLabel`）。
     *
     * 判定全在 [settingsApplyPlan] 里（可单测），这里只负责接线：
     * **两条都走标签那几个既有出口**，于是"写回设置 + 发协议 + 更新标签"三件事
     * 仍然只有一个出处。
     *
     * 时机必须在 `show()` **返回之后** —— 框还占着时推出去的那条提示会被压在模态框后面。
     */
    private fun applySavedSettingsToSession() {
        val s = ClaudeSettings.getInstance(project)
        val plan = settingsApplyPlan(
            sessionReady = ready,
            currentMode = currentMode,
            currentEffort = currentEffort,
            savedMode = s.permissionMode,
            savedEffort = s.effort,
        )
        if (plan.deferred) {
            pushItem(RenderItem.SystemNote("设置已保存；权限模式与思考深度要等下次建立会话时才生效"))
            return
        }
        plan.permissionMode?.let { pickPermissionMode(it) }
        plan.effort?.let { pickEffort(it) }
    }

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
            // 弹层上那句〔会重开会话〕与真正怎么切，走的是**同一个判定**
            effectOf = { p -> effectFor(p, profiles) },
            onPick = { pick -> switchModel(pick) },
            onManage = {
                // 与 [switchModel] 同一条规矩：先收起浮层，别让它挂在模态对话框后面
                modelPopup?.cancel()
                modelPopup = null
                openModelSettings()
            },
        ), centerOverPanel = true) { modelPopup = null }
    }

    /**
     * 现在这一选会走哪条路。弹层与 [switchModel] 共用，免得两边各判各的。
     *
     * 代价：它要读密钥（判端点变没变），而这是**在 EDT 上**发生的 —— 每条配置
     * 第一次会同步碰一次 PasswordSafe（之后走 [ModelProfiles] 的内存缓存）。
     * 弹层里每条配置都要判一次，所以第一次点开可能卡一下。这条代价是换来的：
     * 弹层上那句〔会重开会话〕必须与实际发生的事同源，猜一个更省的判据
     * 就等于让标签撒谎。
     */
    private fun effectFor(target: ModelProfile, profiles: ModelProfiles): PickEffect {
        val current = profiles.selected()
        return pickEffect(
            hasSession = ready,
            current = current,
            currentSecret = current?.let { profiles.secretOf(it.id) } ?: "",
            to = target,
            toSecret = profiles.secretOf(target.id),
        )
    }

    /** 点思考深度标签 → 弹档位列表；再点一次 → 收起。 */
    private fun toggleEffortChooser() {
        effortPopup?.let { open ->
            open.cancel()
            return
        }
        effortPopup = showTogglePopup(
            anchor = effortLabel,
            content = buildEffortList(currentEffort) { pickEffort(it) },
        ) {
            effortPopup = null
        }
    }

    /**
     * 切换模型。**两条路**，由 [pickEffect] 判定 —— 与弹层上那句〔会重开会话〕
     * 是同一个判定，不会出现"写着秒切、实际重开"。
     *
     * - **端点与凭证没变** → 热切换：发一条 `set_model`，会话、上下文、转写
     *   全都留着。同一条配置内换模型必然走这条。
     * - **变了**（或没有会话）→ 老路：重开会话。`options.env` 烤在子进程里，
     *   端点换了就只能重开。
     */
    private fun switchModel(pick: ModelPick) {
        // 列表的任务到此为止，先收起来 —— 与 [pickPermissionMode] 同一条规矩。
        // 不收的话它会一直挂在面板上，而底下正在重开会话
        modelPopup?.cancel()
        modelPopup = null

        val profiles = ModelProfiles.getInstance()
        // 从 ModelProfiles 现取，不用弹层里那份快照：设置对话框是模态的，
        // 用户完全可能在弹层开着的时候改过这条配置
        val target = profiles.profiles().firstOrNull { it.id == pick.profileId } ?: return
        val current = profiles.selected()
        if (current?.id == target.id && current.modelId == pick.modelId) return

        if (effectFor(target, profiles) == PickEffect.Hot) {
            // 回合进行中也允许、也不弹确认框 —— setModel 改的是**后续回合**，
            // 当前这轮既不该被腰斩，上下文也不丢。这与重开那条路正相反
            // （那边忙时必须确认，因为上下文真的要没）
            val c = client
            if (c == null) {
                // ready 为真而通道为空，理论上到不了这里。不静默吞掉 ——
                // 点了没反应比明说更让人困惑（同 [pickPermissionMode] 那条）
                pushItem(RenderItem.SystemNote("会话还没建立，换模型要等连上会话再改"))
                return
            }
            // 先记意图再发：回执只带模型名，没它认不出该写进哪条配置。
            // 顺序反过来（先发后记）会留下一个回执可能先到的窗口
            pendingModelPick = pick
            // 标签**现在不动**：等回执。先改标签后等结果的话，切换失败时
            // 标签会显示一个没生效的模型 —— 同 [currentMode] / [currentEffort]
            c.sendLine(Protocol.encodeSetModel(nextId(), pick.modelId))
            return
        }

        // 重开那条路。会话进行中先把"上下文会丢"说清楚，别让用户切完才发现。
        // **确认放在改选中态之前**：用户点了取消，选中态就该原样不动。
        // 先改后回滚会留下"标签闪了一下又变回去"的中间态，而且回滚那一步
        // 一旦忘了写，选中态就永久跑偏 —— 这里干脆不给它跑偏的机会
        if (busy && !confirmModelSwitch(target, pick.modelId)) return

        profiles.pick(target.id, pick.modelId)
        refreshModelLabel()
        restartSession()
    }

    /**
     * 会话进行中切换时的确认。
     *
     * 这条提示**不能省** —— 少了它用户会以为切完还能接着聊，
     * 等发现上下文没了已经晚了（spec §9）。
     *
     * 要说**为什么**：热切换的模型是秒切的，不解释的话用户会以为这个也是坏的。
     */
    private fun confirmModelSwitch(target: ModelProfile, modelId: String): Boolean =
        Messages.showYesNoDialog(
            project,
            "新配置的端点或凭证与当前会话不同，只能重开会话 —— 这段对话的上下文不保留。",
            "切换到「${target.displayName()} · $modelId」",
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
            pushItem(RenderItem.SystemNote("会话还没建立，列不出历史会话"))
            return
        }
        val dir = project.basePath
        if (dir == null) {
            pushItem(RenderItem.ErrorItem("项目没有 basePath，无法定位会话目录。"))
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
                            pushItem(RenderItem.ErrorItem("会话列表返回了意外的消息。"))
                        } else {
                            showSessionPopup(msg.sessions)
                        }
                    }

                    is RequestOutcome.Failed ->
                        pushItem(RenderItem.ErrorItem("列出会话失败：${outcome.reason}"))
                }
            }
        }
    }

    private fun showSessionPopup(sessions: List<SessionInfo>) {
        sessionListCache = sessions
        val block = switchBlock(busy, permissionQueue.totalPending)
        val content = buildSessionList(
            sessions, currentSessionId, block,
            // 弹层不比这一栏宽（宽高上限见 SessionList.kt 的文件头）。
            // width 在还没排过版时是 0，那时让默认值兜底
            maxWidth = if (width > 0) width else JBUI.scale(SESSION_LIST_WIDTH),
            onDelete = { s -> requestDeleteSession(s) },
            onRename = { s, title -> requestRenameSession(s.sessionId, title) },
            onTag = { s, tag -> requestTagSession(s.sessionId, tag) },
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
     * 改名 / 打标签。与删除同一条规矩：**等回执之后才动界面**。
     *
     * 这里没有删除那种"不可逆"的分量（改错了再改一次就行），但规矩不变 ——
     * 先改行再等回执的话，写入失败时列表显示的是一个并不存在的新名字。
     */
    private fun requestRenameSession(sessionId: String, title: String) {
        val c = client ?: return reportSessionEditFailure("改名", "会话通道已关闭")
        val id = nextId()
        c.request(id, Protocol.encodeRenameSession(id, sessionId, title)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                val msg = (outcome as? RequestOutcome.Answered)?.message
                if (msg !is SidecarMessage.SessionRenamed) {
                    reportSessionEditFailure("改名", (outcome as? RequestOutcome.Failed)?.reason)
                    return@invokeLater
                }
                applySessionEdit(msg.sessionId) { it.copy(customTitle = msg.title) }
            }
        }
    }

    private fun requestTagSession(sessionId: String, tag: String?) {
        val c = client ?: return reportSessionEditFailure("改标签", "会话通道已关闭")
        // 空串在界面上是"清掉"：传 null 过去，sidecar 会显式发一个 JSON null
        val normalized = tag?.takeIf { it.isNotBlank() }
        val id = nextId()
        c.request(id, Protocol.encodeTagSession(id, sessionId, normalized)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                val msg = (outcome as? RequestOutcome.Answered)?.message
                if (msg !is SidecarMessage.SessionTagged) {
                    reportSessionEditFailure("改标签", (outcome as? RequestOutcome.Failed)?.reason)
                    return@invokeLater
                }
                applySessionEdit(msg.sessionId) { it.copy(tag = msg.tag) }
            }
        }
    }

    /**
     * 把某一行的改动写回缓存并重画。
     *
     * **只动那一行、不重列会话**：改名与打标签不改变列表的成员与顺序，
     * 为它们跑一趟 listSessions 既慢、又会把用户正看着的浮层闪一下。
     */
    private fun applySessionEdit(sessionId: String, edit: (SessionInfo) -> SessionInfo) {
        var updated: SessionInfo? = null
        sessionListCache = sessionListCache.map { row ->
            if (row.sessionId != sessionId) row else edit(row).also { updated = it }
        }
        // 改的正是当前会话时，顶上那个标签也得跟着 —— 否则列表里是新名字、
        // 顶上还挂着旧的，看着像没生效
        if (sessionId == currentSessionId) {
            currentSessionTitle = updated?.let(::sessionLabelTitle)
            refreshSessionLabel()
        }
        refreshSessionList()
    }

    /** 失败时**行不动** —— 把它改成新名字才是撒谎。 */
    private fun reportSessionEditFailure(what: String, reason: String?) {
        pushItem(RenderItem.ErrorItem("${what}失败：${reason ?: "没有回执"}"))
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
            pushItem(RenderItem.ErrorItem("删除会话失败：会话通道已关闭"))
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
                pushItem(RenderItem.ErrorItem("删除会话失败：${outcome.reason}"))

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
            pushItem(RenderItem.SystemNote(switchBlockNotice(block) ?: return))
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
        setConnection("载入中…")
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
     *
     * ## 映射在**池线程**上做，推送回 EDT
     *
     * 历史里的图要解码、缩放、重编码（一张 1568px 的截图约 100ms，见
     * [transcriptDataUrl]），八张就是一秒。这一段以前整段跑在 EDT 上 ——
     * 现象是"恢复会话时界面卡住一下"。`MessageRenderer` 是纯的，整段映射可以
     * 搬走；只有推 JCEF 那一步必须回到 EDT（它碰浏览器）。
     *
     * 顺序也保住了：ops 是先攒好再按原顺序推的，不 interleave。
     */
    private fun replayItems(items: List<JsonObject>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            var rendered = 0
            val ops = mutableListOf<TranscriptOp>()
            for (item in items) {
                MessageRenderer.renderPrompt(item)?.let {
                    toOp(RenderItem.UserText(it.text, it.images))?.let { op -> ops += op }
                    rendered++
                }
                // mapNotNull 顺手滤掉"不产出操作的项"。ToolStarting 只会出现在
                // 实时路径（它来自流事件），历史里不会有 —— 但走同一条转换就一并兜住
                val rest = MessageRenderer.render(SidecarMessage.Event(item)).mapNotNull { toOp(it) }
                ops += rest
                rendered += rest.size
            }
            ApplicationManager.getApplication().invokeLater {
                ops.forEach { pushOp(it) }

                // 用量不在这里补 —— `ready` 那一拍已经问过 CLI 了（实测它一条消息
                // 都没发就能答，而且把这份历史算了进去）。再推一遍只会多一个会漂的数据源

                resumeTargetId = null
                setBusy(false)
                setConnection("已连接")
                pushItem(
                    RenderItem.SystemNote("已恢复会话 · ${items.size} 条历史，其中 $rendered 条可显示")
                )
            }
        }
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
        pushItem(RenderItem.ErrorItem("恢复会话失败：$reason"))
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
            pushItem(RenderItem.SystemNote("会话还没建立，权限模式切换要先连上会话"))
            return
        }
        c.sendLine(Protocol.encodeSetPermissionMode(nextId(), mode.wireValue))
    }

    /**
     * 选中一个思考深度。
     *
     * 与 [pickPermissionMode] 同一条规矩：**只发请求、不动标签**，标签等
     * sidecar 的回执（先改后等的话，切换失败时标签会显示一个没生效的档位）。
     *
     * 回合进行中也允许改，也不弹确认框 —— 它改的是**下一轮**，当前这轮既不会
     * 被腰斩，上下文也不丢。这点与换模型（要重开会话、忙时要确认）正相反。
     */
    private fun pickEffort(setting: EffortSetting) {
        // 列表的任务到此为止，先收起来 —— 与 [pickPermissionMode] 同一条规矩
        effortPopup?.cancel()
        effortPopup = null

        if (setting == currentEffort) return

        val c = client
        if (c == null) {
            // 没有会话可切。不静默吞掉 —— 点了没反应比明说更让人困惑
            pushItem(RenderItem.SystemNote("会话还没建立，思考深度要等连上会话再改"))
            return
        }
        // wireValue 为 null 就是「默认」：让 sidecar 把这一项从 flag 层清掉。
        // 这个 null 必须**显式**发出去（见 Protocol.encodeSetEffort）——
        // 省略字段只表示"没提这件事"，清不掉任何东西
        c.sendLine(Protocol.encodeSetEffort(nextId(), setting.wireValue))
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
     * 把刚发出去的这条消息认成会话标题（用户 2026-09-15 的要求）。
     *
     * 改之前：**全新会话的标签一直是斜体的「新会话」**，聊一小时也还是它 ——
     * 那是当初刻意定的（怕把 session id 写上去像一串对不上号的 UUID），
     * 但结果是这条会话在界面上压根没有标题。
     *
     * 该不该认、认成什么，判定在 [titleFromFirstMessage] 里（可单测）；
     * 这里只负责写回去 + 重画。
     */
    private fun adoptTitleFrom(typedText: String) {
        val next = titleFromFirstMessage(typedText, currentSessionTitle) ?: return
        currentSessionTitle = next
        refreshSessionLabel(enabled = true)
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

    /** 思考深度标签的唯一出口。谁改完 [currentEffort] 就调它。 */
    private fun refreshEffortLabel() {
        effortLabel.setEffort(currentEffort)
    }

    /**
     * 把 [currentEffort] 应用到刚建好的会话。
     *
     * **这一趟不能省**：思考深度不走启动参数（理由是启动参数与 flag 层是
     * 两个优先级来源，两条一起用会让「默认」清不干净 —— 见 sidecar/session.js
     * 里那段说明），所以新会话起手是 CLI 自己的档位。不拨这一下，用户上次
     * 存下的档位就只是"界面上存着"，实际跑的仍是默认档。
     *
     * 「默认」也照发：那是一条明确的"把 flag 层清掉"的指令。新会话的 flag 层
     * 本来就是空的，所以这一趟是空转 —— 但为省这一下发而写一个"默认不用发"
     * 的分支，是在用一条隐性知识换一次网络往返，不划算。
     */
    private fun applyEffortToSession() {
        client?.sendLine(Protocol.encodeSetEffort(nextId(), currentEffort.wireValue))
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
        // 用量同理：它是**上一个会话**的读数，新会话起手是空的。
        // 不清的话，换模型重开会话之后那一格会继续显示上一场的百分比 ——
        // 一个又大又吓人的数，而新会话其实什么都没装
        lastUsage = null
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

        // 思考深度也按设置重置。标签先显示设置里的值、真正下发在 [Ready]
        // （那之前会话还不存在）—— 与权限模式同一条：标签显示的是这个会话
        // 该有的起点，而不是上一个会话留下的值
        currentEffort = ClaudeSettings.getInstance(project).effort
        refreshEffortLabel()

        val base = project.basePath
        if (base == null) {
            fail("项目没有 basePath，无法确定工作目录。")
            return
        }
        setConnection("启动中…")
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
                                pushItem(
                                    RenderItem.SystemNote(
                                        "列不出历史会话（${pick.reason}），已开新会话"
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
            pushItem(RenderItem.ErrorItem(text))
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

        // 挂着的权限框必须一起收掉：Claude 已经没了，那个框点下去也没有收件人了
        // （模态框还留在屏幕上是最糟的一种"看起来还能操作"）。
        // 协议侧本来就由 sidecar 的 denyAllPending 负责 —— 它死了，那条路也断了，
        // 所以这里只需要把界面收拾干净。
        permissionQueue.cancelAll()
        closeDecisionDialogs()
        updateStatusBar()
        // 这一次不走 stopSession（那边会碰 client / proc，而它们已经死了），
        // 所以在这里单独清一次
        clearQueue()

        val detail = sidecarExitReport(exit)

        if (!ready) {
            // fail 会置 disconnected → 按钮变「重启会话」，用户点一下就能重试，
            // 不必重启 IDE
            fail(detail)
            return
        }

        setConnection("已断开")
        ready = false
        disconnected = true
        setBusy(false)
        refreshMainButton()
        pushItem(RenderItem.ErrorItem(detail))
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
        closeDetail()

        // 模式列表同理：它选出来的模式要发给会话，会话没了它就没意义
        modePopup?.cancel()
        modePopup = null

        // 浮层挂在旧会话的列表上，会话没了它就该消失
        sessionPopup?.cancel()
        sessionPopup = null

        // 模型列表同理：它选中的那条要等重开才生效。
        // [dispose] 也走这里 —— 不取消的话面板都没了它还留在屏幕上，
        // 而且手里攥着一个已销毁面板的 lambda
        modelPopup?.cancel()
        modelPopup = null
        // 在途的换模型请求属于**上一个会话**：它的回执（如果还会来）不该再
        // 写进设置里去 —— 那会让一次针对旧会话的切换改掉新会话的模型
        pendingModelPick = null

        // spec §6.2 规则① 的终止路径：先作废本地待决项，并把挂着的框关掉。
        // 真正把挂起的 canUseTool 承诺 resolve 掉的是 sidecar 收到 stop 后的
        // denyAllPending —— 两者都必须发生，缺任一侧都会留下挂起的工具调用。
        //
        // 关框走 closeSilently：这些请求马上就不存在了，回一条决定等于朝新会话
        // 发一条张冠李戴的拒绝（`client` 在这里之后就被置空了）。
        permissionQueue.cancelAll()
        closeDecisionDialogs()
        updateStatusBar()

        client?.sendLine(Protocol.encodeSimple(nextId(), "stop"))
        client?.close()
        proc?.shutdown()
        proc = null
        client = null
        // 排队的是**上一个会话**的指令。会话都没了，把它们发出去是灾难 ——
        // 这条路径覆盖了重启 / 新建 / 切会话 / dispose 全部四个入口
        clearQueue()
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
                    applyEffortToSession()
                    // 用量也问一次：恢复的会话在这里就能拿到真数（含分母），
                    // 新会话则拿到"0 + 窗口"，卡片不用先显示一轮的空白
                    requestContextUsage()
                    // MCP 状态也问一次：设置页右栏读的是 McpStatus 服务，
                    // 不先给个底的话，用户点开设置看到的永远是"还没拿到"
                    requestMcpStatus()

                    val resuming = resumeTargetId
                    if (resuming != null) {
                        // 恢复路径：id 构造即知，同时把它记成当前会话
                        currentSessionId = resuming
                        beginReplay(resuming)
                    } else {
                        pushItem(RenderItem.SystemNote("会话已就绪"))
                        // 全新会话：没有标题可显示，标签是斜体的「新会话」
                        currentSessionTitle = null
                        refreshSessionLabel(enabled = true)

                        // 补发窗口就绪前暂存的首条消息
                        pendingFirstMessage?.let { text ->
                            pendingFirstMessage = null
                            // 暂存过的那条就是这条会话的第一条消息 —— 标题在这儿认
                            // （上面那个 currentSessionTitle = null 刚把它清干净）
                            adoptTitleFrom(pendingFirstMessageTitle ?: text)
                            pendingFirstMessageTitle = null
                            val images = pendingFirstMessageImages
                            pendingFirstMessageImages = emptyList()
                            client?.sendLine(Protocol.encodeSend(nextId(), text, outgoing(images)))
                            setBusy(true)
                        }
                    }
                }

                is SidecarMessage.Event -> {
                    val items = MessageRenderer.render(msg)
                    // 命令回合里的空输出丢掉；其余一律照常（设计稿 §5.1）。
                    // pushItem 会自动跳掉 ToolStarting —— 那种项只喂状态卡
                    items.filterNot { lastSendWasCommand && isEmptyCommandOutput(it) }
                        .forEach { pushItem(it) }

                    // 连接卡上那行字跟着这批渲染项走（见 Activity.kt）。
                    // **只走实时路径**：回放旧会话时最后一条可能是被中断的
                    // 工具调用，照着它显示"运行指令"会是一句假话
                    items.forEach { item ->
                        when (val change = activityChangeOf(item)) {
                            is ActivityChange.Now -> setActivity(change.text)
                            ActivityChange.Idle -> setActivity(null)
                            ActivityChange.Keep -> Unit
                        }
                    }
                    // result 是回合结束的信号，此时按钮从"停止"变回"发送"
                    if (items.any { it is RenderItem.Result }) {
                        setBusy(false)
                        lastSendWasCommand = false

                        // 用量只在 result 事件里给；取不到就保持原样。
                        // 一轮跑完，用量变了。重新问一次而不是自己从事件里解析：
                        // result 的 usage 只有主循环最后一次调用的三个 input 字段，
                        // 而 modelUsage 是跨回合累计的总额 —— 两个都不是"现在有多满"
                        //
                        // **必须在 result 分支里面。** 它曾经在分支外面，于是
                        // **每一条事件**都问一次（逐 token 的 stream_event 也算）：
                        // 实测一轮 5861 条事件就是 5861 条 getContextUsage，而控制
                        // 请求和事件流共用同一根管道 —— 事件流被自己的控制请求挤住，
                        // 屏幕上的表现是「思考走到一半突然停住，过一会儿一大段
                        // 一起冒出来」。2026-09-14 的日志里躺着 4685 条超时。
                        requestContextUsage()

                        // **排在最后**：这一条的 result 已经把这一回合结掉了，
                        // 用量读的是"刚才那一回合"的数。放前面会让用量请求
                        // 与下一条消息抢同一根管子（2026-09-14 那次事故是反例）
                        flushQueue()
                    }
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
                        // 只记 id，**不拿它当标题**：把 id 前 8 位写上去的话，用户看到的
                        // 是一串对不上号的 UUID —— 列表里显示的是标题，不是 id。
                        // 标题由 [adoptTitleFrom] 在"第一条消息发出去"那一刻认
                        // （2026-09-15 改：从前这里什么都不做，于是全新会话的标签
                        // 一直是斜体的「新会话」，聊一小时也还是它）。
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
                                // /clear 之后上下文也归零：不清的话这一格会一直
                                // 挂着上一个对话的读数
                                lastUsage = null
                                pushItem(RenderItem.SystemNote("上下文已清空，这是一条新会话"))
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
                    pushItem(RenderItem.ErrorItem(failureHint(msg.code, msg.message)))
                    if (msg.fatal) {
                        // 不静默重连——重连会让用户误以为上下文还在（spec §7.5）
                        setConnection("已断开")
                        ready = false
                        disconnected = true
                        setBusy(false)
                        // 断开后标签变灰但**仍然可点** —— 这正是最需要换个会话的时候
                        refreshSessionLabel(enabled = true)
                        refreshMainButton()
                    }
                }

                is SidecarMessage.Permission -> {
                    // 这一拍最该说清楚的就是"为什么不动了"：在等你点授权
                    setActivity(ACTIVITY_PERMISSION)
                    showPermissionCard(msg)
                }

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
                        pushItem(RenderItem.SystemNote("权限模式已切换为「${mode.label}」"))
                    }
                }

                // 同权限模式：**生效了**才更新标签，认不出的档位什么都不改
                is SidecarMessage.EffortChanged -> {
                    val picked = EffortSetting.fromWire(msg.level)
                    if (picked == null) {
                        LOG.warn("收到不认识的思考深度回执：${msg.level}")
                    } else {
                        // 起会话时那一趟也会回执。它回的是同一个值，不是"用户
                        // 切换了"，所以只在真的变了时才往转写区插一句 —— 否则
                        // 每开一个新会话都会冒出一行「已切换为「默认」」
                        val changed = picked != currentEffort
                        currentEffort = picked
                        refreshEffortLabel()
                        // 写回设置：下次启动还按这个档位起会话
                        ClaudeSettings.getInstance(project).effort = picked
                        if (changed) {
                            pushItem(RenderItem.SystemNote("思考深度已切换为「${picked.label}」"))
                        }
                    }
                }

                // 同权限模式/思考深度：**生效了**才更新标签
                is SidecarMessage.ModelChanged -> {
                    val pick = pendingModelPick
                    pendingModelPick = null
                    when {
                        // 没发过请求就来了回执（或旧会话的迟到回执）：
                        // 无从知道该写进哪条配置 —— 不动比猜一个强
                        pick == null ->
                            LOG.warn("收到没有对应请求的模型回执：${msg.model}")

                        // 回的不是我发的那个。有别的 setModel 在飞，或者乱序 ——
                        // 按"对不上就不改"处理，免得标签显示一个没人确认过的模型
                        msg.model != pick.modelId ->
                            LOG.warn("模型回执与请求对不上：发的是 ${pick.modelId}，回的是 ${msg.model}")

                        else -> {
                            val profiles = ModelProfiles.getInstance()
                            // pick 会把选中态与当前模型一起写下去。它自己会挡住
                            // "配置已被删掉"与"模型已不在列表里"这两种情况
                            // （那说明用户在我们等回执的时候改过设置），
                            // 挡住了标签就保持原样，不会指向一个不存在的东西
                            profiles.pick(pick.profileId, pick.modelId)
                            refreshModelLabel()
                            // 窗口大小跟着模型走：不重问的话，用量卡还会用上一个
                            // 模型的分母，而那多半是另一个窗口
                            requestContextUsage()
                            pushItem(RenderItem.SystemNote("模型已切换为「${pick.modelId}」"))
                        }
                    }
                }

                // 请求-响应式的应答本该由 SidecarClient 的待决表按 id 截走，
                // 到不了这里 —— 列出来只为穷尽性
                is SidecarMessage.ContextUsageReport,
                is SidecarMessage.SessionRenamed,
                is SidecarMessage.SessionTagged,
                is SidecarMessage.Subagents,
                is SidecarMessage.SubagentMessages,
                is SidecarMessage.McpServers,
                -> Unit

                is SidecarMessage.Exit -> {
                    setConnection("已结束")
                    ready = false
                    // 会话没了，上一份 MCP 状态就不作数了 —— 留着的话，
                    // 用户切到新会话后右栏还在显示上一个会话的 server，
                    // 那比空着更糟：它看起来是"当前"的
                    McpStatus.getInstance(project).clear()
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

        // 资格位现在启动时一律带上（见 session.js），所以"这条会话不是以绕过
        // 启动的"已经不是失败原因。剩下的是 CLI 侧真把它关了 ——
        // settings.json 的 permissions.disableBypassPermissionsMode，或受限配置
        "SET_MODE_FAILED" ->
            "$message\n\n权限模式没有切换，本会话仍按原来的模式跑。" +
                "若要切到「绕过权限」而被拒：CCoder 启动时已带好可切换的资格，" +
                "被拒说明它被设置或策略禁用了 —— 查 ~/.claude/settings.json 的 " +
                "permissions.disableBypassPermissionsMode，以及是否有托管配置。"

        // 切档失败的常见原因是 CLI 太老 —— applyFlagSettings 是较新的控制请求，
        // 老版本上根本没有。不把原因说死：也可能是会话没建起来
        "SET_EFFORT_FAILED" ->
            "$message\n\n思考深度没有改变，这一轮仍按原来的档位跑。" +
                "会话中途改档位需要较新版本的 claude 可执行文件；" +
                "可以升级它，或在设置里改好后重开会话。"

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
    private fun toOp(item: RenderItem): TranscriptOp? {
        val now = { System.currentTimeMillis() }
        return when (item) {
            // 只喂状态卡，**不进转写区**：它说的是"参数还在生成"，而参数生成完
            // 会有一条真正的 ToolUse 进来。给它推一条项就会出现两张卡片
            is RenderItem.ToolStarting -> null

            is RenderItem.UserText ->
                TranscriptOp.Append(
                    TranscriptItem.User(nextMessageId(), now(), item.text, item.images)
                )

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

    /**
     * 推一条渲染项。产出 null 的那些（[RenderItem.ToolStarting]）自动跳过。
     *
     * 有这个包装，调用点就不必各自判断"这一项要不要画" —— 那个判断只该有
     * [toOp] 一个出处。
     */
    private fun pushItem(item: RenderItem) {
        toOp(item)?.let { pushOp(it) }
    }

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
    }

    /**
     * 该弹哪个框（spec §6.1 的第 4 步）。
     *
     * `AskUserQuestion` 走 [AskSequence] —— 它的语义是**选哪一个**，通用框的
     * 「拒绝 / 允许」根本表达不了，用户只能看着原始 JSON 发愣；多道题时一题一个框。
     *
     * 解析不出来就**退回**通用框。渲染一张解析不全的提问框比显示原始 JSON 更糟：
     * 用户会以为那就是全部的问题，然后把一个不完整的答案送回去。
     *
     * 两条路最后都汇进同一个 [decide] —— 发送路径只有一处。
     */
    private fun openPermissionDialog(perm: SidecarMessage.Permission, queuedCount: Int) {
        val request = if (perm.toolName == ASK_TOOL_NAME) askRequestOf(perm.input) else null

        if (request == null) {
            val dialog = PermissionDialog(project, perm, queuedCount) { decision ->
                decide(perm, decision)
            }
            permissionDialog = dialog
            // 关掉之后队列可能已经又激活了一条，那时这个 dialog 已经不是"当前"了
            showModal { if (permissionDialog === dialog) dialog.show() }
            return
        }

        val sequence = AskSequence(
            project = project,
            request = request,
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
            onDeny = { decide(perm, deniedByUser()) },
        )
        askSequence = sequence
        showModal { if (askSequence === sequence) sequence.start() }
    }

    /**
     * 弹一个模态框 —— **让出一拍**再弹。
     *
     * 这个调用可能来自上一个框的按钮处理里（决定回完，队列立刻激活下一条）。
     * 那时上一个框刚 `close()`，但它的模态循环还没退出，紧接着 `show()` 会叠出
     * 一个嵌套模态框 —— 焦点与层级都不可靠。`ModalityState.NON_MODAL` 说的正是
     * "等模态框都没了再说"：正常情况下就是下一拍，而用户在别处开着一个模态窗时
     * 就等它关掉（那种时候弹出来也点不动，等是对的）。
     *
     * 用的是 `nonModal()` 而不是 `NON_MODAL` —— 后者在这版平台里已废弃，语义相同。
     */
    private fun showModal(open: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(open, ModalityState.nonModal())
    }

    /**
     * 关掉挂着的权限框与提问框，**不回决定**。
     *
     * 终止路径共用（会话停止 / sidecar 退出 / 中断回合）：这些路上协议侧由
     * sidecar 的 `denyAllPending` 负责 resolve，界面上把这些框关掉就够 ——
     * 再回一条决定等于朝已经不存在的请求说话。
     */
    private fun closeDecisionDialogs() {
        permissionDialog?.closeSilently()
        permissionDialog = null
        askSequence?.closeSilently()
        askSequence = null
    }

    private fun decide(
        perm: SidecarMessage.Permission,
        decision: PermissionDecision,
        note: String? = null,
    ) {
        if (decision.stopAsking) {
            // 开关先拨上，再回决定 —— 决定回完这条就结束了，中间的窗口越短越好。
            //
            // 已知的缺口：**已经排在队列里**的那几条仍会逐个弹框。
            // PermissionQueue 只发 activate 回调，不经过 showPermissionCard，
            // 所以这里够不着它们。数量有限（同一条 assistant 消息里的并行
            // 工具调用），点完就到底，没有单独修。
            autoAllow = true
            refreshModeLabel()
        }
        sendDecision(perm, decision)

        pushItem(
            RenderItem.SystemNote(
                note ?: when {
                    decision.stopAsking -> "已允许：${perm.toolName}，$AUTO_ALLOW_LABEL"
                    decision.allow -> "已允许：${perm.toolName}"
                    else -> "已拒绝：${perm.toolName}"
                }
            )
        )
    }

    /**
     * 把决定送上线路，并同步状态栏。
     *
     * 自动放行那条路也走它 —— 两处各写一份发送逻辑，迟早有一处漏掉同步状态栏。
     *
     * 界面上的收尾（关框）由调用方负责：框是**决定的一方**，它在回决定之前
     * 已经把自己关掉了（见 `PermissionDialog.decide`）。这里再去关一次会把
     * "谁负责关框"变成两处，而两处迟早不一致。
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
        updateStatusBar()
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

    // 说明：spec §6.3 原本那两条补偿（粘性通知、待决超时提醒）随"非模态卡片"
    // 一起退休了（2026-09-14 改成模态框）。留着状态栏计数就够：框会自己弹出来，
    // 而"弹出来还被忽略"这件事在模态形态下不存在。

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
                // 预设排在最前：打 `/` 的人多半想找的是自己那几条常用说法。
                // **必须整组连续** —— 分组标题只在换组时插一条（CompletionPopup），
                // 把预设和命令交叉排会画出一串重复的标题
                Trigger.Command ->
                    filterCandidates(
                        promptCandidates(promptPresets()) +
                            commandCandidates(commandList, sendableNames),
                        q.query,
                    )

                // 索引期间不查文件候选：[collectProjectFiles] 走 ProjectFileIndex，
                // dumb 态下那套 API 会抛 IndexNotReadyException。命令那条不碰索引，
                // 照常给。面板本身是 DumbAware（见 ClaudeToolWindowFactory），
                // 所以这里必须自己挡 —— 平台不会再替我们兜底了
                Trigger.File ->
                    if (DumbService.getInstance(project).isDumb) emptyList()
                    else fileCandidates(allProjectFiles(), q.query)
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

    /**
     * 预置 prompt **现取现用，不缓存**。
     *
     * 它和命令列表不是一回事：命令来自会话（会话一停 `sendableNames` 就清空、
     * 补全也关掉），而预设来自设置 —— 用户刚加的那条应当立刻能补全出来，
     * 且**不跟着会话一起消失**。现取恰好把这两条都变成"不用维护"。
     */
    private fun promptPresets(): List<PromptPreset> = PromptPresets.getInstance().presets()

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

    /**
     * 一张刚粘/拖进来的图 → 附件带。
     *
     * 缩放、编码、条数上限都在 [AttachedImage] 与 [AttachmentStrip] 那两层，
     * 这里只负责**把没收下的原因显示出来**：用户刚按了 Ctrl+V，什么都没发生
     * 看起来就是坏了（静默失败比多一行字严重）。
     */
    private fun addAttachment(incoming: IncomingImage) {
        LOG.info(
            "贴图：收到一张（${incoming.image.width}x${incoming.image.height}，" +
                "${incoming.sourceBytes} 字节，名字=${incoming.name ?: "（剪贴板来的）"}）"
        )
        val reason = imageRejectReason(incoming.sourceBytes)
        if (reason != null) {
            LOG.info("贴图：没收下 —— $reason")
            attachments.reject(reason)
            return
        }
        val prepared = prepareAttachment(
            source = incoming.image,
            index = attachments.images.size,
            sourceBytes = incoming.sourceBytes,
            name = incoming.name,
        )
        if (prepared == null) {
            LOG.warn("贴图：缩不到能发的大小，放弃")
            attachments.reject("这张图压不小，换个更小的试试")
        } else {
            val ok = attachments.add(prepared)
            LOG.info("贴图：进附件带 ${if (ok) "成功" else "被上限挡下"}，现在带上有 ${attachments.images.size} 张")
        }
    }

    private fun sendCurrentInput() {
        // 纯图消息也能发（截一张图直接甩过来是常见用法），两样都没有才是空
        val images = attachments.images
        if (input.text.isBlank() && images.isEmpty()) return

        // 用户敲进去的原样。标题取的是**它**，不是下面展开后的文本 ——
        // 展开会把记号变成一大段代码，拿它当标题就成了「```kotlin …」
        val typed = input.text.trim()

        // ↑/↓ 的历史记的是**用户敲的那份**：展开后的全文（可能几百行代码）塞回
        // 输入框就没法用了。代价是带片段记号的那句再发一次时，记号只是一行字面
        // 文本（见 InputHistory 顶上那条说明）
        inputHistory.remember(typed)

        // 记号在这一刻展开：输入框里只是「一行记号」，发出去的是路径 + 围栏 + 代码全文。
        // 展开放在**清空输入框之前**，而清空之后表也一起清掉 —— 记号已经不在文本里了，
        // 留着它只会随会话越攒越大
        val text = snippetRefs.expand(typed)

        input.text = ""
        snippetRefs.clear()
        // 图也是同一时刻搬走：取在上面、清在这里，中间不许有第二条路把输入框读空
        attachments.clear()

        // 忙时入队（spec §5.2）—— 不回退成"什么都不做"：用户敲的这句话本来就该
        // 有个去处。**不推转写区**：它还没发出去，而转写区是"跟模型说过什么"的
        // 记录（spec §4）。发出去的那一刻才补一条 [RenderItem.UserText]。
        //
        // 未就绪时 busy 恒为 false（mainButtonState 那时给的是「启动中…」，
        // fail 与 onSidecarDied 都会 setBusy(false)），所以与下面那条路不会同时命中。
        // 转写区那条带的是**给人看的**那份图（data URL，见 transcriptDataUrl）
        val forTranscript = images.map { it.transcriptDataUrl }

        if (busy) {
            queue.enqueue(text, typed, images)
            refreshQueueStrip()
            return
        }

        // 排队那条也要带图：补发时 pushOp 用的是它的 images，不是这里的 forTranscript
        pushItem(RenderItem.UserText(text, forTranscript))

        if (!ready) {
            // 会话还没就绪。可能是 fatal 断开后残留的进程，先清干净再起一个，
            // 否则 startSession 会因为 proc != null 直接返回、消息永远发不出去。
            if (proc != null) stopSession()
            disconnected = false

            // 消息暂存，就绪后由 Ready 分支补发 —— 若此处直接丢弃，
            // 用户点第一次"发送"时会看到消息出现却毫无反应。
            pendingFirstMessage = text
            // 标题取用户敲的那份（见上面 typed），暂存着等 Ready 之后一起认
            pendingFirstMessageTitle = typed
            pendingFirstMessageImages = images
            refreshMainButton()
            startSession()
            return
        }

        sendNow(text, typed, images)
    }

    /** 界面上的图 → 协议要的那份。转换只有这一处，改协议时只改这里。 */
    private fun outgoing(images: List<AttachedImage>): List<OutgoingImage> =
        images.map { OutgoingImage(it.mediaType, it.base64) }

    /**
     * 真正把一条消息发出去。**直接发与排队后发唯一的出口**（spec §5.1）。
     *
     * 这五步都必须发生在**发送那一刻**，不能提前到入队那一刻：
     * - [adoptTitleFrom]：标题取用户敲的原样。排队时就认，等于让一条还没发出去、
     *   还可能被撤掉的消息改掉会话标题
     * - `lastSendWasCommand`：命令回合的判据是"发出去的是什么" ——
     *   命令的空输出不该画气泡（设计稿 §5.1），实测 `result.local_command` 恒为 null
     * - [setBusy] / [setActivity]：它们描述的是"现在在跑"，而排队中并没有在跑
     *
     * 标题必须**紧挨着发送**认下来，不能提前到上面：会话还没就绪时 startSession()
     * 之后 Ready 分支会把标题清成 null，提前认的那一次会被它抹掉。
     */
    private fun sendNow(text: String, typed: String, images: List<AttachedImage> = emptyList()) {
        adoptTitleFrom(typed)
        client?.sendLine(Protocol.encodeSend(nextId(), text, outgoing(images)))
        lastSendWasCommand = text.startsWith("/")
        // 发出后进入"忙"：按钮变"停止"，直到 result 到达
        setBusy(true)
        // 第一口 token 可能要等几秒，这期间卡上写"已连接"是句假话
        setActivity(ACTIVITY_WAITING)
    }

    /**
     * 回合结束，把队首发出去。**一次只发一条**（spec §5.3）——
     * 它的 result 到了再发下一条：顺序天然正确，也不会两条挤进同一回合。
     */
    private fun flushQueue() {
        val next = queue.peek() ?: return
        queue.remove(next)
        // 先重画再发：sendNow 里的 setBusy(true) 会连带刷按钮，
        // 而按钮的文案里带着队列条数 —— 顺序反了它会拿着旧数字去刷
        refreshQueueStrip()
        pushItem(RenderItem.UserText(next.text, next.images.map { it.transcriptDataUrl }))
        sendNow(next.text, next.typed, next.images)
    }

    /** ✕ 撤回单条。撤掉的那条从没进过转写区，所以它不留痕迹（spec §5.4）。 */
    private fun removeQueued(item: QueuedInput) {
        if (queue.remove(item)) refreshQueueStrip()
    }

    /**
     * 清空队列（停止 / 断线 / 切会话 / 重启）。
     *
     * **这是唯一的清空出口**：漏一条路，上一段会话排着的指令就会被发进新会话里。
     */
    private fun clearQueue() {
        if (queue.drain().isEmpty()) return
        refreshQueueStrip()
    }

    /**
     * 排队条的唯一写入口。顺带刷按钮 —— 它的文案里有"会清掉几条"。
     */
    private fun refreshQueueStrip() {
        // 可见性跟着模型走，由 QueueStrip 自己收边（见那里的说明）
        queueStrip.setModel(queueStripModel(queue))
        refreshMainButton()
    }

    /**
     * 追加一段文本到输入框（三个右键动作的落点：加选区 / 加文件 / 项目树加文件）。
     *
     * 顺带把工具窗口激活、焦点抢到输入框：不然点完右键菜单**看不到任何反应** ——
     * 文本被追加进一个没打开的窗口，用户会以为动作失败了。
     */
    fun addToComposer(text: String) {
        appendSnippet(input, text)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        input.requestFocusInWindow()
    }

    /**
     * 附件按钮：选文件 → 加进输入框。
     *
     * 走的是**已经有的两条路**，不新造第三条（分流见 [splitChosenFiles]）：图片与
     * 拖一张 `.png` 进来完全同路 —— 进附件带；其余照右键「加文件」的写法插一个
     * `@相对路径`，内容由 CLI 自己展开。
     *
     * 图**读不出来时退回 `@` 引用**而不是默默丢掉：用户明明选了它，输入框里总得
     * 留下点什么。真正的超限（>8MB）由 [addAttachment] 给一句人话。
     */
    private fun chooseFilesToAdd() {
        val picked = FileChooser.chooseFiles(
            FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor(),
            project,
            null,
        )
        if (picked.isEmpty()) return

        val chosen = splitChosenFiles(picked.map { it.path })
        LOG.info(
            "附件按钮：选了 ${picked.size} 个文件 —— 图 ${chosen.pictures.size} 张、" +
                "@ 引用 ${chosen.mentions.size} 个",
        )
        if (chosen.mentions.isNotEmpty()) {
            addToComposer(
                chosen.mentions.joinToString(" ") { fileMention(mentionPathOf(project, it)) },
            )
        }
        for (path in chosen.pictures) {
            val image = imageFromFile(File(path))
            if (image != null) {
                addAttachment(image)
            } else {
                addToComposer(fileMention(mentionPathOf(project, path)))
            }
        }
    }

    /**
     * 追加一行**片段记号**，并记住它对应的完整片段。
     *
     * 片段此刻不进界面 —— 它留在 [snippetRefs] 里等发送时展开。两样东西
     * **一起进来**（而不是各自去算一遍行号），是为了让"输入框里显示的是哪几行"
     * 与"发出去的是哪几行"在构造上就不可能不一致。
     */
    fun addSnippetToComposer(token: String, snippet: String) {
        snippetRefs.remember(token, snippet)
        addToComposer(token)
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun nextId(): String = "req-${idCounter++}"

    /**
     * 渲染用的消息号。
     *
     * **加锁**：回放的映射跑在池线程上（见 [replayItems]），而它一边跑、会话一边
     * 可能吐新事件 —— 两个线程同时 `++` 会撞出重复的 id，而 id 是 React 的 key，
     * 重复的 key 会让某个气泡渲染错位（那种 bug 找起来很费劲，而这里一行就防住）。
     */
    private fun nextMessageId(): String = synchronized(messageIdLock) { "m${messageCounter++}" }

    private companion object {
        const val TOOL_WINDOW_ID = "CCoder"

        /** 一屏够看了。不做翻页 —— 实测本机 19 条会话。 */
        const val SESSION_LIST_LIMIT = 50

        val LOG = Logger.getInstance(ClaudePanel::class.java)
    }
}
