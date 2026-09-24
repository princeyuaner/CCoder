package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo
import com.ccoder.sidecar.McpServerStatus
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
import com.ccoder.sidecar.ContextDetail
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
import com.ccoder.settings.OpenTab
import com.ccoder.settings.PermissionModeSetting
import com.ccoder.settings.PromptPreset
import com.ccoder.settings.PromptPresets
import com.ccoder.settings.displayName
import com.ccoder.settings.showSettingsDialog
import com.ccoder.text.CcoderText
import com.ccoder.update.ChangelogStore
import com.ccoder.update.PropertiesChangelogStore
import com.ccoder.update.maybeShowChangelog
import com.ccoder.update.showChangelogDialog
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
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
class ClaudePanel(
    private val project: Project,
    /**
     * 这个面板是不是点「＋」开出来的（见 [SessionTabs.openNewTab]）。
     *
     * **它决定第一次上屏时恢不恢复最近会话**：开工具窗口时建的那个面板要恢复
     * （用户要的是"接着上次聊"），「＋」开出来的必须是**空**的 ——
     * 那个面板的"第一次上屏"是它被创建的那一刻，与"用户第一次打开这个工具窗口"
     * 不是一回事。2026-09-16 的 bug 就在这儿：点「＋」得到的是"最近那条会话"，
     * 用户截图来问"新建会话不应该是空的吗"。
     */
    private val openedByPlus: Boolean = false,
    /**
     * 这条面板是**重启后照存档恢复**出来的那一条（见 [ClaudeSettings.openTabs]）。
     *
     * 非 null 表示"第一次上屏时回到它自己那条会话"，而不是去找最近改过的那条 ——
     * 见 [firstShowPlan]。空 `sessionId` 的存档（「＋」开出来还没聊过的标签）
     * 与 null 等价：它本来就该是条空标签。
     *
     * 只有 [SessionTabs] 装配标签时给这个参数，别处一律 null。
     */
    private val restoredTab: OpenTab? = null,
) : JPanel(BorderLayout()), SidecarListener, Disposable {

    /**
     * 存档里点名要恢复的那条会话。null = 这条面板没有"自己那条"（新建的）。
     *
     * 它是**请求**，与 [resumeTargetId]（正在办的那件事）分开：listSessions 回来
     * 才把前者兑现成后者。空串的存档在这一步就被滤掉，免得后面每条分支都要判一次。
     */
    private val restoreTargetId: String? =
        restoredTab?.sessionId?.trim()?.takeIf { it.isNotEmpty() }

    private val transcriptView = ClaudeTranscriptView(project)

    /**
     * "更新日志给这个用户看过哪个版本"存哪儿（见 `com.ccoder.update`）。
     *
     * 是个 `var` 而不是构造器参数：**这个类是 public 的**，而 [ChangelogStore]
     * 是 internal 的 —— 放进构造器签名编译器不让过（"public function exposes its
     * internal parameter type"）。用例与探针在这里换一个假实现，
     * 因为生产实现读应用级 `PropertiesComponent`，那玩意儿在无头测试里拿不到。
     */
    internal var changelogStore: ChangelogStore = PropertiesChangelogStore()

    /**
     * 连接状态。
     *
     * **不是字符串**：文字与色调都挂在 [ConnectionState] 上，能在无头单测里钉住，
     * 而翻译它一个字都不牵动这边（理由见那个类）。
     */
    private var connectionState = ConnectionState.Idle

    /**
     * 连接卡上那行字：**空闲时是连接状态，忙时是"现在在做什么"**。
     *
     * 映射规则在 [activityChangeOf]（纯函数，能单测）。null = 没有正在跑的动作。
     */
    private var activity: Activity? = null

    /**
     * 上面那个动作词**是不是子代理在跑**（规则见 [subagentOf]）。
     *
     * 不在 [Activity] 里带这个位：它是"谁在跑"，不是"在跑什么" —— 两者寿命与来源
     * 都不同（动作词来自枚举，归属来自渲染项的 `parent`）。卡面据此画一个小角标。
     */
    private var activitySubagent = false

    /**
     * CLI 正在压缩上下文（由 status 事件驱动，spec 事实 9）。
     *
     * 它不是"忙"的另一种写法：压缩中上下文卡的值行要让给「压缩中…」、动作按钮
     * 整个撤掉（spec §3.5），而忙只让按钮变灰。**自动压缩**也走这一档 ——
     * 那种情况我们没发过任何命令，只有 CLI 的实况能告诉我们。
     */
    private var compacting = false

    /**
     * 四张状态卡。**常驻** —— 没内容的格子收边，不隐藏。
     *
     * 卡一会儿出现一会儿消失，输入框就会在会话中途上下跳；稳定比安静重要。
     */
    private val statusCards = StatusCardsRow(
        // 两颗动作按钮发的就是两条命令本身 —— 与在输入框里敲它们完全相同的那条路
        // （[sendText] → [submit]）。清空的清理动作因此一行都不新写（spec §3.6）
        onClear = { sendText("/clear") },
        onCompact = { sendText("/compact") },
        onOpenContext = { toggleDetail(DetailCard.Context) },
        onOpenTodos = { toggleDetail(DetailCard.Todos) },
        onOpenRunning = { toggleDetail(DetailCard.Running) },
    )

    /**
     * 被最小化的提问那条带子（见 [AskRestoreBar]）。
     *
     * 路的落点是**回到那个框**：先把工具窗口激活（用户可能已经切到编辑器里去了，
     * 只弹一个对话框而窗口在后台，看起来同样像"没反应"），再走与状态栏那条完全
     * 相同的入口 —— 身份比对、排队时机都在 [restoreAsk] 里，两条路不各写一套。
     */
    private val askRestoreBar = AskRestoreBar(onRestore = {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        askSequence?.let(::restoreAsk)
    })

    /** 最近一次拿到的上下文用量。取不到时保持 null —— 不造零值。 */
    private var lastUsage: ContextUsage? = null

    /**
     * 上一次量到的**明细**（分类表 + 四张清单）。
     *
     * 与 [lastUsage] 一起来、一起去（同一处赋值、同一处清空）—— 两个字段分开走，
     * 就会出现"数变了、明细还是上一轮的"，而那张框正是把两者摆在一起读给用户看的。
     */
    private var lastContextDetail: ContextDetail? = null

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
        // 占位末尾那句发送说明跟着设置走。现读而不是存下来 —— 同 isSendKey 那条路
        sendShortcut = { ClaudeSettings.getInstance(project).sendShortcut }
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

    /**
     * **这个标签**当前用的模型（哪条配置 + 它里面的哪个模型）。
     *
     * 2026-09-21 起它是本标签自己的一份，不再是全应用共用的一个选中态。
     * 起因是一个真问题：两个 IDE 窗口（或同一个窗口的两个会话标签）会互相改 ——
     * 而模型是**起 sidecar 时烤进进程环境**的，改设置根本到不了另一个跑着的会话。
     * 于是切一次，别人那边标签显示"已切"、手里的会话却还是原来那个：标签撒谎。
     *
     * 现在：本标签点的、本标签用；持久化到**本项目**的最近一次
     * （[ClaudeSettings.lastModel]），新标签从它开局。
     *
     * [Volatile]：`toStartParams` 会在**池化线程**上读它（见 [sendStart] 那条注释），
     * 而写在 EDT 上。
     */
    @Volatile
    private var currentModel: ModelProfile? = initialModel()

    /**
     * 这条标签开局用哪个模型。
     *
     * 恢复出来的那条用它**自己存档里**的配置与模型 —— 这就是"每条标签各记各的"
     * 落地的地方：重启前 A 用 flash、B 用 pro，回来还是各用各的。其余标签（新建的、
     * 以及升级上来的老项目）沿用本项目最近一次的选择（[ClaudeSettings.lastModel]）。
     *
     * 存档里的东西可能已经变了样，两条分开处理，理由不一样：
     *  - **配置被删了** → 当"没选过"（[fallback]）
     *  - **空 modelId** → 原样保留：它是**合法**取值（那条配置不指定模型，官方端点
     *    走 CLI 的默认档）。它不能进 [reconcileModel] —— 那边判的是"这个模型还在
     *    不在列表里"，空串永远不在，于是会退回本项目最近一次的选择：标签用着的
     *    **配置**就不是它原来那条了（比如从"官方端点"变成"公司中转"）
     *  - **模型被删了** → [reconcileModel] 判，退回 [fallback]
     */
    private fun initialModel(): ModelProfile? {
        val profiles = ModelProfiles.getInstance()
        val available = profiles.profiles()
        val fallback = ClaudeSettings.getInstance(project).lastModel(profiles)
        val tab = restoredTab ?: return fallback

        val fresh = available.firstOrNull { it.id == tab.profileId } ?: return fallback
        if (tab.modelId.isBlank()) return fresh.copy(modelId = "")
        return reconcileModel(fresh.copy(modelId = tab.modelId), available, fallback)
    }

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

    /**
     * 刚发出去、还没等到回执的那次模式切换。
     *
     * 只为**压掉重复说明**：CLI 在回控制请求之前也可能先吐一条 `system/status`，
     * 那条同样会被读成"模式变了"。用户自己点的切换，说明由回执那条负责
     * （"权限模式已切换为…"），status 这条只管把标签改对、不吭声。
     */
    private var requestedMode: PermissionModeSetting? = null

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
    // 会话标签在 2026-09-16 被 sessionChips（上面那排胶囊）取代 —— 见它的注释

    /**
     * 当前会话的标题。null = 还不知道（全新会话，或者刚恢复还没拿到标题）。
     *
     * **界面上永远不显示会话 id** —— 列表里显示的是标题，一串 UUID 前缀
     * 对不上号，纯噪音。标签的显示规则就一条：有标题显示标题，没有显示
     * 斜体的「新会话」。
     *
     * 恢复出来的标签先拿存档里那个名字顶着（见 [OpenTab.title]）：它要等上屏、
     * 要等 sidecar 起来才问得到会话列表，而胶囊行**现在**就要画 —— 不顶的话
     * 一开 IDE 会看到一排「新会话」。真拿到了会话列表就换成权威的那份
     * （`sessionLabelTitle`），所以这只是一个占位，不是第二份真相。
     */
    private var currentSessionTitle: String? = restoredTab?.title?.takeIf { it.isNotBlank() }

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
     * 顶行那排会话胶囊（2026-09-16 起替掉了原来的单个会话标签）。
     *
     * 点别的胶囊 = 切过去；点当前那颗 = 开历史会话列表（与原标签同一个入口）；
     * 点 ✕ = 关掉那个标签（忙时由 [SessionTabs] 先问一句）。
     */
    private val sessionChips = SessionChips(
        onPick = { chip ->
            val owner = chip.owner as? ClaudePanel
            when {
                owner == null -> Unit
                owner === this -> toggleSessionChooser()
                else -> SessionTabs.getInstance(project).selectOwner(owner)
            }
        },
        onClose = { chip -> SessionTabs.getInstance(project).closeTab(chip.owner) },
    )

    /**
     * 标签那边有变化就重画胶囊行、并把「＋」重算一次（到上限要置灰）。
     *
     * 存成字段而不是就地写 lambda：退订得用**同一个引用**（[dispose] 里摘），
     * 而订阅是累加的 —— 漏摘一次就多留一个捕获着本面板的监听器。
     */
    private val tabsListener: () -> Unit = {
        refreshChips()
        newSessionButton.setTabState(SessionTabs.getInstance(project).tabCount)
    }

    /**
     * 这个标签里有没有"活着的"东西 —— 关它之前该不该问一句。
     *
     * 判定在 [closeNeedsConfirm]（纯函数，可单测），这里只把四个字段喂进去。
     * `starting` 也在其中：点「＋」之后立刻点叉是最常见的路径，而那一刻
     * `proc` 还是 null（见 [SessionGate]），只判 `proc` 会静默放过。
     */
    internal fun hasLiveSessionState(): Boolean =
        closeNeedsConfirm(starting, proc != null, busy, permissionQueue.totalPending)

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

    /**
     * "第一次上屏"已经发生过没有 —— 只有那一次才恢复最近会话（见 [consumeFirstShow]）。
     *
     * 多标签之前不需要它：一个面板一辈子只上屏一次。现在切走再切回会反复上屏，
     * 不钉住的话一个起失败的标签会自己接到别的会话上。
     */
    private var firstShowDone = false

    /** 启动期取消闸（见 [SessionGate] 的文件头：不判它就会留下孤儿进程）。 */
    private val sessionGate = SessionGate()

    /**
     * 本会话最后一份 MCP 状态。
     *
     * 多标签之后 `McpStatus` 服务仍是**单槽**（设置页只表达"当前会话"），所以面板
     * 自己留一份：切到这个标签时重新发布（见 [publishMcpStatus]）。null = 还没有过。
     */
    private var lastMcpServers: List<McpServerStatus>? = null

    /** 打开着的详情浮层。用它实现"再点一次收起"。 */
    private var runDetailPopup: JBPopup? = null

    /**
     * 挂着的那张详情卡。
     *
     * 三张卡（任务列表 / 子代理 / 上下文）共用一个浮层 —— 同一时刻只该有一个挂着，
     * 而这张记着是谁，好在关闭时把对应那张取消高亮。
     */
    private var openDetail: DetailCard? = null

    /** 画「运行中」浮层用的两份输入 —— 清单变了重画时照用，子代理那段不重问 sidecar。 */
    private var runningAgents: List<SubagentInfo> = emptyList()

    /**
     * 空闲时子代理浮层里「看已结束的 N 个 ›」那一行展开过没有（2026-09-20）。
     *
     * 每次**重新点开**那张卡都从收起开始（见 [toggleDetail]）：那行"先答有没有在跑"
     * 的默认态才是用户点开时想看的东西；展开是"我要回看"的显式选择，不该被记住。
     */
    private var subagentHistoryExpanded = false
    private var shownRunning: List<RunningTask> = emptyList()

    /**
     * 此刻浮层里画的是**"运行中"清单**（而不是某个子代理的转写页）。
     *
     * 单独一面旗，而不是看 [openDetail]：转写页复用的也是"子代理"这张卡的
     * 浮层，靠 openDetail 分不开。清单变了要重画，但**不能把人正看着的
     * 转写页顶掉**。
     */
    private var runningListShown = false

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
    /**
     * 这一轮里**还没等到结果**的工具调用 id（`tool_use.id`）。
     *
     * 只用来回答一个问题：状态卡上那行字该不该切成「等待响应」。一条 assistant
     * 消息可以带多个 tool_use，所以"收到一个结果"不等于"工具都跑完了" ——
     * 见 [activityChangeOf] 的 `toolsStillRunning`。
     *
     * 只在实时路径上维护；回放不碰它（那是旧会话，没有"现在在跑什么"这件事）。
     */
    private val pendingToolIds = mutableSetOf<String>()

    /**
     * 「等待响应」那格的秒表（见 [startWaitingTicker]）。null = 还没建过。
     */
    private var waitingTicker: javax.swing.Timer? = null

    /** 这一轮等待的开始时刻（毫秒）。只在 [Activity.Waiting] 那一档有意义。 */
    private var waitingSince = 0L

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

    /**
     * 符号名的**全量枚举结果**（`#` 那条路），比 [projectFiles] 活得更久。
     *
     * **为什么不跟着弹层一起清**：PyCharm 里那个符号贡献者只提供了"全量吐名字"的口子
     * （没有前缀收窄，见 `SymbolLookup.kt` 的文件头），一次枚举就是整个项目的符号 ——
     * 而它与**查询无关**。每关一次层就重来一遍，等于每次"没匹配上"都让用户多等一次全量扫描。
     * 清它的地方只有一处：输入框里连 `#` 查询都没有了（见 [refreshCompletion]）。
     */
    private var symbolNamesCache: List<String>? = null

    /** 在飞的符号搜索作废用的代数：每次发起 +1，关层也 +1（Esc 之后回来的结果不许再开层）。 */
    private var symbolSearchId = 0

    /** 符号搜索还在飞。弹层里这时挂一行"正在搜索符号…" —— 不许看起来像卡住。 */
    private var symbolSearching = false

    /** 符号搜索本身失败的原因。非空时必须显示出来（不静默）。 */
    private var symbolFailure: String? = null

    /**
     * 弹层里那行说明（**不是候选**）。两种情况：
     *  - 光打了一个触发字符（"再打一个字找文件 / 找符号"）—— 见 [showHint]
     *  - 索引还在建（符号那条）—— 见 [SymbolSearchResult.status]
     *
     * 都不是失败，不弹气球；[completionStatus] 按优先级把它排给弹层。
     */
    private var completionHint: String? = null

    /** 采纳时的程序化改写会触发文档监听，用它挡掉自引发的重算。 */
    private var suppressCompletion = false

    /** 这一回合是命令回合（发出去的消息以 `/` 开头）。 */
    private var lastSendWasCommand = false

    /** 排队中的输入（spec §3）。忙时回车进这里，回合结束由 [flushQueue] 发出去。 */
    private val queue = SendQueue()

    /** 排队条本体。存成字段而不是现场 new —— 刷新时要直接够得着它。 */
    private val queueStrip = QueueStrip { removeQueued(it) }

    init {
        // 标签数变化 → 「＋」的可用性重算（多标签之后它只在到上限时置灰）
        SessionTabs.getInstance(project).addTabsListener(tabsListener)
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

        // 诊断：Shift+Enter 到底有没有**到**这个组件（2026-09-23 用户报"不换行"）。
        // 与上面那条 Ctrl+V 同一个用意：这一行能把"平台键位/输入法把它吃了"
        // （日志里压根没有这行）与"到了我们手上但处理错了"（有这行）分开 ——
        // 少了它，两种情况的界面表现一模一样。
        //
        // 这一笔**只记"到了"**，不记文本长度。曾经记过一笔"处理完：文本 N → M"，
        // 那是错的：`invokeLater` 跑在 KEY_PRESSED 之后、KEY_TYPED 之前，
        // 而 Shift+Enter 的换行走的是后者（见 `ComposerTextArea.installShiftEnterBreak`），
        // 于是那笔永远显示"没变"，看着像"确实什么都没做"——**那是量早了**。
        //
        // 顺带把弹层状态带上：补全那条分支（`completionKey`）从前是不看修饰键的。
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown) {
                    LOG.info(
                        "Shift+Enter 到了输入框：补全开着=${completion.isOpen} 候选数=${completionItems.size}",
                    )
                }
            }
        })

        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // 补全开着时，上下键与 Enter/Tab/Esc 归补全。
                // **只在真开着时短路** —— 关着的时候 Enter 该不该发送
                // 仍然是 isSendKey 的事，那个函数一行都不改
                //
                // 2026-09-15（符号那条路）：弹层有时**只有一行状态**、一个候选都没有
                // （正在搜、或者搜索本身失败了）。那时上下键与回车**不接管** ——
                // 屏幕上根本没有可选的东西，把它们吃掉就成了"按了没反应"，
                // 而回车在这个输入框里是有分量的键（发送/排队）。Esc 例外：任何时候都该能关掉
                if (completion.isOpen) {
                    val haveRows = completionItems.isNotEmpty()
                    when (completionKey(e.keyCode, e.isShiftDown)) {
                        CompletionKey.Up -> if (haveRows) { e.consume(); moveCompletion(-1); return }
                        CompletionKey.Down -> if (haveRows) { e.consume(); moveCompletion(1); return }
                        CompletionKey.Accept -> if (haveRows) { e.consume(); acceptCompletion(); return }
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
        val top = buildTopRow(sessionChips, settingsButton, newSessionButton)

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
        // 点缩略图放大看（只在这个框里看，不落临时文件、也不在编辑器里开 ——
        // 见 ImagePreviewDialog 的说明）。框是模态的，开着的时候输入区冻着，
        // 所以不存在"看着看着图被发出去"
        attachments.onPreview = { images, index -> showImagePreview(project, images, index) }

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
            // 被最小化的提问那条带子**长在这里**（用户报"找不到从哪里重新打开"）。
            // 排在最上面：它说的是一件等他处理的事，比状态卡更急
            add(askRestoreBar.apply { alignmentX = LEFT_ALIGNMENT })
            // 2026-09-15 用户要求"最小化后再往上挪 5px"：那条带子贴面板顶太近，
            // 和它下面那排状态卡挤在一起。留 5px 的缝，其余间距一个字不动 ——
            // 状态卡与输入框之间那 7px 是原有设计，不跟着一起松。
            //
            // **struts 也要左对齐**：默认是居中，而它正是会把 BoxLayout 的公共
            // 对齐基准推歪的那个（这个坑本项目踩过，见上面那段说明）。
            // `as JComponent` 不是多余的：`createVerticalStrut` 声明返回
            // `java.awt.Component`，而 `alignmentX` 长在 JComponent 上
            add(
                (Box.createVerticalStrut(JBUI.scale(5)) as JComponent)
                    .apply { alignmentX = LEFT_ALIGNMENT }
            )
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
            // 第一次上屏要开哪一个由 [firstShowPlan] 定：开工具窗口那条回到最近
            // 会话（用户要的是"接着上次聊"），「＋」出来的必须是空的，
            // 重启后照存档恢复的那条回到它自己那条
            startSession(consumeFirstShow())
            checkChangelogOnUpdate()
        }
    }

    override fun addNotify() {
        super.addNotify()
        addHierarchyListener(showingWatcher)

        // 兜住"装监听时它已经显示完了"这一种：那时 SHOWING_CHANGED 早就发生在
        // 装监听之前，不会有事件再来。延后一拍再判 isShowing，避开布局未完成的时刻。
        ApplicationManager.getApplication().invokeLater {
            LOG.info("CCoder 面板上屏：isShowing=$isShowing，据此决定是否建会话")
            if (isShowing) {
                startSession(consumeFirstShow())
                checkChangelogOnUpdate()
            }
            // 切到这个标签（或者它刚建出来）：把**本会话**的 MCP 状态重新发布 ——
            // 设置页右栏读的是那个单槽服务，不发布的话它显示的是上一个标签的
            publishMcpStatus()
        }
    }

    /**
     * "第一次上屏要开哪一个"只该算**一次**。
     *
     * 多标签之后 [showingWatcher] 与 [addNotify] 会被反复触发（切走再切回 =
     * `removeNotify`/`addNotify`），而今天那条路里"起失败 / 断开导致 `proc == null`"
     * 会重新走"挑一条会话"—— 于是一个起失败的标签会**悄悄接到别的会话上**
     * （还会撞上 [OpenSessions] 的占用登记）。
     *
     * 两个触发点共用这一个开关，所以谁先到谁赢，后到的那个拿到 [FirstShow.Nothing]。
     */
    private fun consumeFirstShow(): FirstShow {
        if (firstShowDone) return FirstShow.Nothing
        firstShowDone = true
        return firstShowPlan(
            firstShow = true,
            openedByPlus = openedByPlus,
            restoredSessionId = restoreTargetId,
            // 恢复出来的标签即便不是「＋」开出来的，也不是"开工具窗口那一条" ——
            // 存档里没有会话号的那种要的是一条空标签，见 [firstShowPlan]
            restored = restoredTab != null,
        )
    }

    /**
     * 更新之后弹一次这一版的更新日志（2026-09-17，设计稿见
     * `docs/superpowers/specs/2026-09-17-changelog-on-update-design.md`）。
     *
     * **挂在"面板真的显示出来"这一点上**（同 [consumeFirstShow] 的两个触发点）：
     * 用户完全可能开着 IDE 一整天也不点开这个工具窗口，而这一步的语义正是
     * "他开始用了"。判断与写标记都在 EDT 上，所以**多标签、多项目也只弹一次**
     * （先后执行，第二个看到标记就跳过）。
     *
     * 卸载/异常一律吞掉：这是"顺带说一句"的功能，不该在谁的启动路径上抛异常。
     */
    private fun checkChangelogOnUpdate() {
        runCatching {
            val decision = maybeShowChangelog(
                store = changelogStore,
                show = { changelog -> showChangelogDialog(project, changelog) },
            )
            LOG.info("CCoder 更新日志：$decision（已看过 ${changelogStore.shownVersion()}）")
        }.onFailure { LOG.warn("CCoder 更新日志弹窗没成", it) }
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
                            lastContextDetail = report.detail
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
                            // 先记本地：切标签回来时要拿它重新发布（见 [publishMcpStatus]）
                            lastMcpServers = report.servers
                            publishMcpStatus()
                        }
                    }

                    is RequestOutcome.Failed -> LOG.warn("读取 MCP 状态失败：${outcome.reason}")
                }
            }
        }
    }

    /**
     * 把本会话的 MCP 状态发布到项目服务（设置页右栏读它）。
     *
     * **只有当前选中的标签才有资格发布**：服务仍是单槽，"当前会话"就是选中的那个。
     * 后台标签的上报与清空都不该动它 —— 否则前台刚拿到的读数会被后台的会话状态顶掉
     * （`Exit` 那条路就是这么漏的）。
     */
    private fun publishMcpStatus() {
        if (!SessionTabs.getInstance(project).isSelected(this)) return
        val servers = lastMcpServers
        if (servers == null) McpStatus.getInstance(project).clear()
        else McpStatus.getInstance(project).set(servers)
    }

    /** 唯一的连接状态写入口。状态变了，卡上的点与色跟着变。 */
    private fun setConnection(state: ConnectionState) {
        connectionState = state
        refreshStatusCards()
    }

    /**
     * 当前动作的唯一写入口。
     *
     * **值没变就直接返回**：流式期间这条会被每个 token 调一次，而"思考中"
     * 要连着几十上百次增量保持不变 —— 不挡一下就是每个 token 重画一次四张卡。
     */
    private fun setActivity(next: Activity?, subagent: Boolean) {
        // 两个都判：动作词没变、归属变了（主线程 → 子代理在跑同一类活）也得重画
        if (activity == next && activitySubagent == subagent) return
        activity = next
        activitySubagent = subagent
        // [Activity.Waiting] 那格要显示秒数，所以这一档得自己走表（见 waitingCardOf）。
        // 其余动作词不带秒数：工具卡上本来就有自己的计时，两处同时跳反而吵
        if (next == Activity.Waiting) startWaitingTicker() else stopWaitingTicker()
        refreshStatusCards()
    }

    /**
     * 等待中的秒表。
     *
     * **一秒一跳，但只在「等待响应」这一档**：等模型开口的那几十秒里，一个不动的
     * 四个字分不出"它在走"还是"它挂了"（2026-09-15 用户报过"卡几十秒"）。
     *
     * 建的时机是第一次要用它，而不是在构造里 —— 面板建起来的时候还没在等谁。
     * 停下来由 [stopWaitingTicker] 负责，`dispose()` 里也摘一次：这个 Timer 捕获
     * 整个面板，留着它等于把面板钉在事件队列上。
     */
    private fun startWaitingTicker() {
        waitingSince = System.currentTimeMillis()
        val ticker = waitingTicker
            ?: javax.swing.Timer(1000) { refreshConnectionCard() }.also { waitingTicker = it }
        if (!ticker.isRunning) ticker.start()
    }

    private fun stopWaitingTicker() {
        waitingTicker?.stop()
    }

    /** 这一档等了多久（秒）。没有计时在跑时给 0。 */
    private fun waitingSeconds(): Int =
        ((System.currentTimeMillis() - waitingSince) / 1000).toInt().coerceAtLeast(0)

    /**
     * 连接卡单独一刷。
     *
     * 与 [refreshStatusCards] 分开是为了秒表：等待中的数字每秒都在变，而另外
     * 三张卡（用量、清单、子代理）跟这一秒毫无关系，不该跟着重算。
     */
    private fun refreshConnectionCard() {
        val now = activity
        statusCards.connection.setModel(
            when {
                now == Activity.Waiting -> waitingCardOf(waitingSeconds(), activitySubagent)
                now != null -> activityCardOf(now, activitySubagent)
                // 连接状态本身没有归属 —— "已连接"是会话的状态，不是谁的
                else -> connectionCardOf(connectionState)
            }
        )
    }

    /**
     * 按当前四份数据重画四张卡。
     *
     * 没内容的格子由 [StatusCardModel.quiet] 收边 —— 不是隐藏，四张卡始终在。
     */
    private fun refreshStatusCards() {
        // 忙时这张卡改说"在干什么"：转写区是滚动区，长任务跑起来最新的那条
        // 早就滚上去了，抬头一眼能看见的只有这里
        refreshConnectionCard()
        // 压缩中：值行让给「压缩中…」，比例条跟着转 Warn（spec §3.8）
        statusCards.context.setModel(contextCardOf(lastUsage, compacting = compacting))
        statusCards.todos.setModel(todoCardOf(runStatus.todos))
        statusCards.running.setModel(runningCardOf(runStatus.running))
        refreshCardActions()
        // 「运行中」浮层开着时，清单变了就重画 —— task_progress 每走一步、
        // 终止后那一行被收掉，都会走到这儿
        refreshRunningPopup()
    }

    /**
     * 两颗动作按钮的可用性。
     *
     * **与卡面分开刷**：卡面的四份数据各有各的来源，而动作只跟"会话活着吗 /
     * 忙不忙 / 在压缩吗"三件事走 —— 忙闲切换时卡面数据一个字都没变，
     * 但按钮必须跟着灰（spec §3.5）。漏调一次就会出现"按钮亮着、点了没反应"。
     */
    private fun refreshCardActions() {
        statusCards.connection.setAction(clearActionOf(ready = ready, busy = busy))
        statusCards.context.setAction(
            compactActionOf(ready = ready, busy = busy, compacting = compacting)
        )
    }

    /**
     * 模型标签的唯一出口，与 [refreshModeLabel] 同一个道理。
     *
     * 显示的是**这个标签在用的那份**（[currentModel]）—— 谁改了它就调一下这里。
     *
     * 2026-09-21 之前读的是 `ModelProfiles.selected()`（全应用一份），于是别的
     * 窗口一切，这边的标签就跟着变，而这边跑着的会话根本没动。现在读自己的。
     */
    private fun refreshModelLabel() {
        val profiles = ModelProfiles.getInstance()
        // 核一遍：设置对话框可能刚把这条配置删了、改过、或删掉了那个模型。
        // currentModel 是快照，不核的话标签会显示一条已经不存在的配置
        // （推理见 [reconcileModel]）
        currentModel = reconcileModel(
            current = currentModel,
            available = profiles.profiles(),
            fallback = ClaudeSettings.getInstance(project).lastModel(profiles),
        )
        modelLabel.setProfile(currentModel)
    }

    /**
     * 把"这个标签现在用这条配置里的这个模型"落下去（三处一起）：
     * 本标签的 [currentModel]、配置里的当前模型（设置页要显示它）、
     * 本项目的最近一次（[ClaudeSettings.lastModel]，新标签从它开局）。
     *
     * 配置没了、或模型已不在它的列表里就**整个不动** —— 这两件事发生在
     * "回执到达时用户已经改过设置了"那条竞态上，那时宁可什么都不改。
     */
    private fun applyModel(profileId: String, modelId: String) {
        val profiles = ModelProfiles.getInstance()
        val target = profiles.profiles().firstOrNull { it.id == profileId } ?: return
        if (modelId !in target.modelIds) return
        profiles.pick(profileId, modelId)
        currentModel = target.copy(modelId = modelId)
        ClaudeSettings.getInstance(project).rememberModel(profileId, modelId)
        refreshModelLabel()
        // 标签的存档里那份模型也得跟着走：它是"每条标签各记各的"唯一的落点，
        // 不在这里落一次，重启后这个标签用的是**上一次存盘时**那个模型
        SessionTabs.getInstance(project).rememberTabs()
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
            // 上下文：**模态框**（2026-09-22，用户从选型稿 `docs/design/context-details.html`
            // 里挑的乙）。show() 一进去就阻塞到用户关掉它，所以这条路不用浮层、
            // 也没有"再点一次收起"——高亮在框关掉之后立刻收掉（浮层那边是靠
            // "点外面"的回调收的）。
            //
            // 用的是**上一次量到的数**：每轮跑完都刷过一次，最多差一个回合。
            // 点一下就该看见东西，不该先转个圈 —— 运行中那张卡的"先请求、收到才弹"
            // 是因为它的记录在磁盘上，不是这个理由。
            DetailCard.Context -> {
                view.setOpen(true)
                try {
                    showContextDetail(project, lastUsage ?: ContextUsage(0, 0), lastContextDetail)
                } finally {
                    view.setOpen(false)
                    openDetail = null
                }
            }

            DetailCard.Todos -> showDetailPopup(
                view,
                runStatus.todos?.let(::buildTodoDetail)
                    ?: buildRunningDetail(emptyList(), emptyList(), {}, {}),
            )

            // 子代理那一段要问一次 sidecar —— 它的记录在磁盘上，不在事件流里。
            // **先请求、收到才弹**（同会话列表）：不先弹一个"载入中"，
            // 免得还要处理"弹出后再换内容"那套尺寸重算
            DetailCard.Running -> {
                subagentsLoading = true
                // 重新点开 = 回到"先答有没有在跑"那一态
                subagentHistoryExpanded = false
                requestSubagents()
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
        runningListShown = false
        // **also 把"正在等子代理清单"那面旗清掉**（2026-09-24 补）。它是"这一趟还
        // 算不算数"的凭据：不清的话，一条迟到的应答会以为自己仍被需要，于是把你
        // 刚点开的**另一张**卡的浮层顶掉（点了「任务列表」，跳出来的却是「子代理」）。
        // 收起来的动作本身就等于"这一趟不要了"。
        subagentsLoading = false
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
    private fun requestSubagents() {
        val c = client
        val dir = project.basePath
        val sessionId = currentSessionId
        if (c == null || dir == null || sessionId == null) {
            subagentsLoading = false
            showRunningDetail(emptyList())
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
                showRunningDetail(agents)
            }
        }
    }

    /**
     * 把「运行中」清单画进浮层，并记下这次用的两份输入。
     *
     * 首次打开与"清单变了重画"共用这一条（[refreshRunningPopup] 也调它）。
     */
    private fun showRunningDetail(agents: List<SubagentInfo>) {
        runningAgents = agents
        shownRunning = runStatus.running
        // 旗子**必须在 showDetailPopup 之后立**：它内部先 cancel 旧浮层，
        // 那一刻的 onClosed 会走 closeDetail 把旗清掉。
        //
        // 而"立不立"取决于浮层**真显示出来了没有** —— 没显示还立着，下一次刷新
        // 就会把那个从没露过面的窗口显示出来（2026-09-24 用户报的"自己跳出来"）。
        runningListShown = showDetailPopup(statusCards.running, runningDetailPanel())
    }

    /**
     * 那一屏的内容。三个入口共用（首次打开、形状变了重建、就地换内容）——
     * 参数只写在一个地方，免得哪天漏改一处（"展开"那个回调尤其容易漏，
     * 它捕获的是 [runningAgents] 而不是参数）。
     */
    private fun runningDetailPanel(): JComponent = buildRunningDetail(
        runStatus.running,
        runningAgents,
        ::stopRunningTask,
        ::openSubagentTranscript,
        historyExpanded = subagentHistoryExpanded,
        // 展开/收起就地重画：浮层的内容每画一次都是新造的，
        // 状态只能住在面板上（见 buildRunningDetail 那个参数）
        onToggleHistory = {
            subagentHistoryExpanded = !subagentHistoryExpanded
            showRunningDetail(runningAgents)
        },
    )

    /**
     * 清单变了就重画开着的「运行中」浮层。
     *
     * 少了它，点「终止」之后那一行得收起再打开才消失 —— 看起来就是"点了没反应"。
     *
     * ## 分两档：形状没变就地换内容，形状变了才重建（2026-09-24 修"闪来闪去"）
     *
     * 判等拿的是整个清单（含 detail / 时长），所以 `task_progress` 每走一步它都判"变了"
     * —— 而从前那条路是 [showRunningDetail] → **cancel 旧浮层 + 新建一个**。
     * 四个子代理一起跑的时候这个窗口每秒被拆好几次，用户看到的正是"闪来闪去"。
     *
     * `JBPopup` 没有 `setContent`，但 `content` 拿到的**就是我们自己那个 `JPanel`**
     * （[buildRunningDetail] 返回的那个盒子），把它的孩子换掉就是"换内容不换窗"。
     * 形状相同时高度也不变（判据见 [rendersSameShapeAs]），所以连尺寸都不用重设。
     *
     * 只在**画着清单**时重画：子代理转写页复用的是同一张卡的浮层，
     * 把人正看着的转写顶掉比不刷新坏得多。
     */
    private fun refreshRunningPopup() {
        // 除了那面旗，还核一眼**它真在屏幕上**：旗有可能漏网（浮层被某种不触发
        // onClosed 的方式收掉时不会清），而"以为开着"的代价就是下一次刷新把这个
        // 其实没人看得见的窗口显示出来 —— 就是用户说的"自己跳出来"
        if (!runningListShown || runDetailPopup?.isVisible != true) return
        if (runStatus.running == shownRunning) return
        if (runStatus.running.rendersSameShapeAs(shownRunning) && swapRunningDetailContent()) return
        // 形状真变了（多了/少了一个任务、或者某条冒出了进行时）→ 高度也变了，
        // 那就老老实实重建一次，让它重新量尺寸与位置
        showRunningDetail(runningAgents)
    }

    /** 就地换掉浮层里的内容（不拆窗）。拿不到那个容器就给 false，让调用方走重建。 */
    private fun swapRunningDetailContent(): Boolean {
        val box = runDetailPopup?.content as? JPanel ?: return false
        shownRunning = runStatus.running
        val fresh = runningDetailPanel()
        box.removeAll()
        fresh.components.forEach { box.add(it) }
        box.revalidate()
        box.repaint()
        return true
    }

    /**
     * 终止一个正在跑的任务（"运行中"浮层每行右端那颗）。
     *
     * 发完就走 —— 界面的反馈是那一行**自己消失**：CLI 发 task_notification、
     * RunStatusTracker 把它收掉、[refreshRunningPopup] 重画浮层。
     * 失败才出声（sidecar 报 STOP_TASK_FAILED，走 Failure 那条路进转写区）。
     */
    private fun stopRunningTask(taskId: String) {
        client?.sendLine(Protocol.encodeStopTask(nextId(), taskId))
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
                            CcoderText.text("chat.error.subagentTranscript", (outcome as? RequestOutcome.Failed)?.reason ?: CcoderText.text("chat.reason.noReply"))
                        )
                    )
                    return@invokeLater
                }
                showDetailPopup(statusCards.running, buildSubagentDetail(agent, msg.items))
                // 换页之后画的不是清单了 —— 清单再变也不许把它顶掉
                runningListShown = false
            }
        }
    }

    /**
     * 把详情浮层挂到某张卡上。首次打开与换页走同一条。
     *
     * @return **真显示出来了没有**。锚点不在屏上时 `showAboveOrBelow` 会提前返回，
     *   那时浮层只是个对象、从没露过面 —— 调用方据此决定要不要把"开着"的旗子立起来
     *   （见 [showRunningDetail] 与那几个字段的注释）。
     */
    private fun showDetailPopup(card: StatusCardView, content: JComponent): Boolean {
        // 先取消旧的：它的 onClosed 会把字段置空，所以必须排在赋值之前
        runDetailPopup?.cancel()
        val popup = showTogglePopup(anchor = card, content = content, underAnchor = true) {
            // 点浮层外面关掉时也要把高亮与"开着谁"一起清掉 ——
            // 少了这一句，那张卡会一直亮着，再点它反而变成"收起"
            closeDetail()
        }
        // **锚点不在屏上时 `showAboveOrBelow` 会提前返回**，那个浮层于是**从没显示过**
        // （对象建了、`showInScreenCoordinates` 没调）。从前照样把 `runDetailPopup`
        // 与 `openDetail` 立起来，于是面板以为"开着" —— 而**任何一次**后续刷新
        // （清单变了、或者一条迟到的应答）都会 `cancel` 掉它再新建一个，
        // 那一次锚点在屏上了，窗口就**自己跳出来**了。
        //
        // 2026-09-24 用户报的原话："我都没点，为什么自己跳这个页面"。
        if (!popup.isVisible) {
            runDetailPopup = null
            card.setOpen(false)
            return false
        }
        runDetailPopup = popup
        card.setOpen(true)
        // 取消旧浮层时 onClosed 把 openDetail 清掉了（见上面那句注释），
        // 这里按新内容补回来 —— 不补的话换页/重画之后"再点一次收起"会失灵
        // （点它变成重新打开）。2026-09-18 与「运行中」重画一起补的
        detailOf(card)?.let { openDetail = it }
        return true
    }

    /**
     * 浮层锚在哪张卡 → 它是哪一页。连接卡没有详情，给 null。
     *
     * **上下文那张不再走浮层**（2026-09-22 起它开对话框，见 [toggleDetail]），
     * 所以这里的 Context 那一档今天到不了 —— 留着是为了让"哪张卡有详情"这件事
     * 在一处看全：以后它要是回到浮层，改回去只是删掉 [toggleDetail] 里那个分支。
     */
    private fun detailOf(card: StatusCardView): DetailCard? = when (card) {
        statusCards.context -> DetailCard.Context
        statusCards.todos -> DetailCard.Todos
        statusCards.running -> DetailCard.Running
        else -> null
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
        /**
         * 横向贴不贴锚点。
         *
         * false（默认）= 贴面板左边缘，底部那排长列表用（会话标签是右对齐的，
         * 跟着锚点会整块溢出）；true = 贴锚点，唯一用户是状态卡那三个详情浮层 ——
         * 四张卡横排，浮层跑到面板最左就跟"这是哪张卡的"断了联系（见 [popupCardX]）。
         *
         * **必须排在 `onClosed` 前面**：尾随 lambda 会静默绑到最后一个参数上，
         * 放在后面的话下面四个调用点的 `{ ... }` 会变成往 Boolean 上传函数 ——
         * 这个坑 StatusCardsRow 的注释里记过一次，这里不再踩。
         */
        underAnchor: Boolean = false,
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
        showAboveOrBelow(popup, anchor, underAnchor)
        return popup
    }

    /**
     * 锚点都在工具窗口底部，向下弹必然出屏，所以位置得自己算。
     * 见 [popupAnchorY]。
     */
    private fun showAboveOrBelow(popup: JBPopup, anchor: JComponent, underAnchor: Boolean = false) {
        if (!anchor.isShowing) return
        val at = anchor.locationOnScreen
        val screen = anchor.graphicsConfiguration?.bounds ?: Rectangle(0, 0, 1920, 1080)
        // 名字带前缀：`width` 在这个类里是**面板自己的宽度**，同名会把 panelRight 算错
        val popupWidth = popupWidthOf(popup.size, popup.content?.preferredSize)
        val y = popupAnchorY(
            anchorTop = at.y,
            anchorHeight = anchor.height,
            popupHeight = popupHeightOf(popup.size, popup.content?.preferredSize),
            screenTop = screen.y,
            screenBottom = screen.y + screen.height,
            gap = JBUI.scale(4),
        )

        // 横向两条路，见 showTogglePopup 的 underAnchor 与 [popupCardX] 里那段：
        // 状态卡的详情跟自己的卡走，底部那排长列表贴面板左边缘
        val x = if (underAnchor) {
            popupCardX(
                anchorLeft = at.x,
                popupWidth = popupWidth,
                panelLeft = locationOnScreen.x,
                panelRight = locationOnScreen.x + width,
                screenLeft = screen.x,
                screenRight = screen.x + screen.width,
            )
        } else {
            popupLeftX(
                panelLeft = locationOnScreen.x,
                popupWidth = popupWidth,
                screenLeft = screen.x,
                screenRight = screen.x + screen.width,
            )
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
     *
     * 「界面」卡里的偏好走第三条路：**纯界面的事，只往转写区推一次** —— 它们不碰会话，
     * 所以不进 [applySavedSettingsToSession]（那个函数有"会话没就绪"的早退分支，
     * 偏好塞进去会被顺手吃掉）。与主题同一条道理。
     *
     * 推的是两条通道，各管各的：思考折叠走偏好快照（[ClaudeTranscriptView.setPreferences]），
     * 字体与字号走**主题 CSS**（[ClaudeTranscriptView.setTheme] —— 颜色与字体从 2026-09-11 起
     * 就是同一份注入，见 `ThemeInjector`）。
     */
    private fun openModelSettings() {
        // 先把 MCP 状态问一遍：右栏读的是 McpStatus 服务，不先问就显示上一次的
        requestMcpStatus()
        showSettingsDialog(project)
        // 关框那一刻推一次：已经画在屏幕上的思考块跟着收/展、字体与字号当场换
        // （"界面立刻切换"那条承诺）。三条通道一起推，见 pushUiState
        transcriptView.pushUiState()
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
            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.settingsSaved")))
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
            current = currentModel?.let { ModelPick(it.id, it.modelId) },
            // 弹层上那句〔会重开会话〕与真正怎么切，走的是**同一个判定**
            effectOf = { p -> effectFor(p, profiles) },
            onPick = { pick -> switchModel(pick) },
            onManage = {
                // 与 [switchModel] 同一条规矩：先收起浮层，别让它挂在模态对话框后面
                modelPopup?.cancel()
                modelPopup = null
                openModelSettings()
            },
        )) { modelPopup = null }
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
        val current = currentModel
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
        val current = currentModel
        if (current?.id == target.id && current.modelId == pick.modelId) return

        if (effectFor(target, profiles) == PickEffect.Hot) {
            // 回合进行中也允许、也不弹确认框 —— setModel 改的是**后续回合**，
            // 当前这轮既不该被腰斩，上下文也不丢。这与重开那条路正相反
            // （那边忙时必须确认，因为上下文真的要没）
            val c = client
            if (c == null) {
                // ready 为真而通道为空，理论上到不了这里。不静默吞掉 ——
                // 点了没反应比明说更让人困惑（同 [pickPermissionMode] 那条）
                pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.noSessionForModel")))
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

        applyModel(pick.profileId, pick.modelId)
        // 重开 = 这段上下文真的没了（用户刚在确认框里点过）。**现在就清**，不等新
        // 会话的 init 回来再清 —— 迟到的清会吃掉用户在这中间发出的第一条消息
        // （2026-09-22 用户报的正是这个：气泡刚推上去，就被那次 Reset 抹掉）
        clearForNewSession()
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
            CcoderText.text("chat.switchModel.confirmBody"),
            CcoderText.text("chat.switchModel.confirmTitle", target.displayName(), modelId),
            CcoderText.text("chat.switchModel.confirmOk"),
            CcoderText.text("common.cancel"),
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
            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.noSessionForList")))
            return
        }
        val dir = project.basePath
        if (dir == null) {
            pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.noBasePathSessions")))
            return
        }

        val reqId = nextId()
        c.request(reqId, Protocol.encodeListSessions(reqId, dir, SESSION_LIST_QUERY_LIMIT, 0)) { outcome ->
            // 回调在读取线程上，碰 Swing 必须回到 EDT
            ApplicationManager.getApplication().invokeLater {
                when (outcome) {
                    is RequestOutcome.Answered -> {
                        val msg = outcome.message as? SidecarMessage.SessionList
                        if (msg == null) {
                            pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.unexpectedSessionList")))
                        } else {
                            showSessionPopup(msg.sessions)
                        }
                    }

                    is RequestOutcome.Failed ->
                        pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.listSessions", outcome.reason)))
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
            // 别的标签正在跑的那些行不可点，并标一句「已打开」（见 OpenSessions）
            takenIds = OpenSessions.getInstance(project).takenIds(),
            onDelete = { s -> requestDeleteSession(s) },
            onRename = { s, title -> requestRenameSession(s.sessionId, title) },
            onTag = { s, tag -> requestTagSession(s.sessionId, tag) },
            // 顶部右上角那颗「清空全部」（2026-09-17）。确认在列表里做完了，
            // 到这里就是"用户已经确认过"
            onClearAll = { requestClearAllSessions() },
        ) { picked ->
            sessionPopup?.cancel()
            sessionPopup = null
            switchToSession(picked)
        }
        // 居中于面板：锚点（会话标签）右对齐，标题短时弹层会跟着溢出到面板外
        // 锚点换成胶囊行：原来的会话标签已经被它取代，弹层跟着新的那一个走
        sessionPopup = showTogglePopup(sessionChips, content) { sessionPopup = null }
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
        // 「新建会话」绝不能带着上一个会话的 resume 目标 —— [sendStart] 读的正是它。
        // 留着的后果有两条：一条起失败的标签会"新建"出上一个会话（磁盘上不出现新
        // 会话，用户以为新建没生效），以及 [tabSessionId] 会把那条旧会话当成这条
        // 标签的现状存下去
        resumeTargetId = null
        refreshSessionLabel(enabled = true)
        pushOp(TranscriptOp.Reset)
        startSession()
    }

    /**
     * 「＋」：**开一个新标签**（2026-09-16 多标签）。
     *
     * 改之前它是"停掉当前会话、原地开一条新的"（[startNewSession]）—— 点一下
     * 等于放弃手上这条。现在只是再加一条，手上这条继续跑，所以**忙时也照样能点**
     * （这也是 [SessionNewButton] 的置灰条件从"忙"换成"到上限"的原因）。
     */
    private fun onNewSession() {
        // 到上限时按钮已经置灰 + tooltip 说明原因，这里只是兜底（键盘/程序化触发）
        if (!SessionTabs.getInstance(project).openNewTab()) {
            LOG.info("CCoder 标签已达上限（$MAX_SESSION_TABS），忽略这一次新建")
        }
    }

    /**
     * 改名 / 打标签。与删除同一条规矩：**等回执之后才动界面**。
     *
     * 这里没有删除那种"不可逆"的分量（改错了再改一次就行），但规矩不变 ——
     * 先改行再等回执的话，写入失败时列表显示的是一个并不存在的新名字。
     */
    private fun requestRenameSession(sessionId: String, title: String) {
        val c = client ?: return reportSessionEditFailure(CcoderText.text("chat.action.rename"), CcoderText.text("chat.reason.channelClosed"))
        val id = nextId()
        c.request(id, Protocol.encodeRenameSession(id, sessionId, title)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                val msg = (outcome as? RequestOutcome.Answered)?.message
                if (msg !is SidecarMessage.SessionRenamed) {
                    reportSessionEditFailure(CcoderText.text("chat.action.rename"), (outcome as? RequestOutcome.Failed)?.reason)
                    return@invokeLater
                }
                applySessionEdit(msg.sessionId) { it.copy(customTitle = msg.title) }
            }
        }
    }

    private fun requestTagSession(sessionId: String, tag: String?) {
        val c = client ?: return reportSessionEditFailure(CcoderText.text("chat.action.retag"), CcoderText.text("chat.reason.channelClosed"))
        // 空串在界面上是"清掉"：传 null 过去，sidecar 会显式发一个 JSON null
        val normalized = tag?.takeIf { it.isNotBlank() }
        val id = nextId()
        c.request(id, Protocol.encodeTagSession(id, sessionId, normalized)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                val msg = (outcome as? RequestOutcome.Answered)?.message
                if (msg !is SidecarMessage.SessionTagged) {
                    reportSessionEditFailure(CcoderText.text("chat.action.retag"), (outcome as? RequestOutcome.Failed)?.reason)
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
        pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.editFailed", what, reason ?: CcoderText.text("chat.reason.noReply"))))
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
            pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.deleteSession", CcoderText.text("chat.reason.channelClosed"))))
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
                pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.deleteSession", outcome.reason)))

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
     * 清空这个项目的历史会话（用户已在列表顶部确认过）。
     *
     * ## 为什么是一次请求而不是循环调 [requestDeleteSession]
     *
     * 列表只取了前 [SESSION_LIST_QUERY_LIMIT] 条，而"清空所有"要覆盖到没列出来的
     * 那些 —— 循环删就得在这边自己再做一遍分页；而且部分失败要有"删掉几条、
     * 哪几条没删掉"，N 个回调里自己记账等于把 sidecar 的活搬到界面线程上。
     *
     * ## keep：**正在被标签跑着的会话不删**
     *
     * 那条 jsonl 正被一个活着的 CLI 进程写着，删文件是拿正在写的会话冒险。
     * 行内那颗删除早就有同样的规矩（被占的行连入口都不给），这里只是把它
     * 说给 sidecar 听。当前会话另外再保一份：万一它还没来得及登记
     * （登记在 `init` 时做，见 [confirmOwnership]），那一条同样不能删。
     */
    private fun requestClearAllSessions() {
        val c = client
        if (c == null) {
            pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.clearSessions", CcoderText.text("chat.reason.channelClosed"))))
            return
        }
        val dir = project.basePath
        if (dir == null) {
            pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.clearSessions", CcoderText.text("chat.reason.noBasePath"))))
            return
        }
        val keep = OpenSessions.getInstance(project).takenIds().toMutableSet()
        currentSessionId?.let { keep.add(it) }

        val id = nextId()
        c.request(id, Protocol.encodeClearSessions(id, dir, keep.toList())) { outcome ->
            // 回调在读取线程上，碰 Swing 必须回到 EDT
            ApplicationManager.getApplication().invokeLater { onClearOutcome(outcome, keep.size) }
        }
    }

    private fun onClearOutcome(outcome: RequestOutcome, keptCount: Int) {
        val msg = (outcome as? RequestOutcome.Answered)?.message
        if (msg !is SidecarMessage.SessionsCleared) {
            // 行**不动**（它本来就在），只报错 —— 同删除那一条：
            // 失败时把行摘掉才是撒谎，用户会以为删掉了
            pushItem(
                RenderItem.ErrorItem(
                    CcoderText.text("chat.error.clearSessions", (outcome as? RequestOutcome.Failed)?.reason ?: CcoderText.text("chat.reason.noReply")),
                ),
            )
            return
        }

        val gone = msg.deleted.toSet()
        if (gone.isNotEmpty()) sessionListCache = sessionListCache.filterNot { it.sessionId in gone }
        // 删的正是当前会话（兜底：它一般都在 keep 里）：与删一行那条同路 ——
        // 停会话、清转写区、回新会话
        val current = currentSessionId
        if (current != null && current in gone) startNewSession()
        refreshSessionList()

        pushItem(RenderItem.SystemNote(clearAllResultText(deleted = gone.size, kept = keptCount)))
        if (msg.failed.isNotEmpty()) {
            pushItem(RenderItem.ErrorItem(clearAllFailedText(msg.failed)))
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

        // 已被**别的标签**占着的会话不能切过去：两边会同时写同一个 jsonl
        // （设计稿里那条"不做检测"的风险，多标签之后变成必然）
        if (OpenSessions.getInstance(project).isTaken(target.sessionId)) {
            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.sessionTakenOver")))
            return
        }

        LOG.info("CCoder 切换会话：${target.sessionId}")
        // 顺序要紧：[stopSession] 会把本面板的占用登记全部放掉（它覆盖新建/重启/
        // 销毁全部入口），所以**占要放在停之后**，否则刚占上的立刻被自己清掉
        stopSession()
        OpenSessions.getInstance(project).reserve(target.sessionId, this)
        // 标题从列表里就知道，不必等 loadHistory
        currentSessionTitle = sessionLabelTitle(target)
        refreshSessionLabel(enabled = true)
        resumeTargetId = target.sessionId
        startSession()
    }

    /**
     * 把占用登记校正到 `init` 报的那条会话上（**权威信号**）。
     *
     * 为什么不能只信我们发出去的 `resumeSessionId`：`resume` 未必按我们传的 id
     * 成立（CLI 侧可能落到别的 id 上），只有 init 报回来的这一条是事实。
     * 所以每条会话第一次 init 时都在这里对一次账。
     */
    private fun confirmOwnership(sessionId: String) {
        val claims = OpenSessions.getInstance(project)
        val previous = currentSessionId
        if (previous != null && previous != sessionId) {
            // /clear 那种：CLI 在同一进程里换了 id，旧的得放掉
            claims.release(previous, this)
        }
        if (!claims.reserve(sessionId, this)) {
            // 别的标签正跑着同一条：**不硬断开**（进程还活着，硬停比说一句更糟），
            // 但必须说出来 —— 两边同时写同一个 jsonl 是会丢历史的
            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.sessionShared")))
        }
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
            failReplay(CcoderText.text("chat.reason.channelClosed"))
            return
        }
        val dir = project.basePath
        if (dir == null) {
            failReplay(CcoderText.text("chat.reason.noBasePath"))
            return
        }

        // 清空转写区。Reset 是既有操作，Kotlin 编码与 React 消费都已实现
        // 并有测试（codec.test.ts「reset 清空全部」）
        pushOp(TranscriptOp.Reset)
        setConnection(ConnectionState.Loading)
        // 回放期间不接受输入：否则历史与实时消息会交错（spec §10 的风险项）
        setBusy(true)

        val reqId = nextId()
        c.request(reqId, Protocol.encodeLoadHistory(reqId, dir, sessionId)) { outcome ->
            ApplicationManager.getApplication().invokeLater {
                when (outcome) {
                    is RequestOutcome.Answered -> {
                        val msg = outcome.message as? SidecarMessage.History
                        if (msg == null) {
                            failReplay(CcoderText.text("chat.reason.unexpectedHistory"))
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
                setConnection(ConnectionState.Connected)
                pushItem(
                    RenderItem.SystemNote(CcoderText.text("chat.note.restored", items.size, rendered))
                )

                // **回放期间用户敲的消息要在这里接上**（2026-09-22 修的）。那一段
                // 有两条岔路，从前两条都会把它弄丢：
                //  - 会话还没起来就敲 → 走的是"暂存首条"，而补发它的只有 Ready 那
                //    一支；恢复这条路 Ready 已经过去了，于是那条消息永远发不出去，
                //    气泡还被回放开头的 Reset 抹掉了（用户看到的就是"消息不见了"）
                //  - 正在回放时敲 → busy，进的是队列；而 flushQueue 只在回合结束
                //    （result）时被调，回放之后根本没有回合会跑 —— 队列会一直悬着
                // 补推气泡的那一档：回放的 Reset 已经把 submit 推的那条抹了，
                // 所以这里按同样的内容补一遍（用的是用户敲的原样 `typed`）
                flushPendingFirstMessage(repushBubble = true)
                flushQueue()
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
        setConnection(ConnectionState.RestoreFailed)
        currentSessionTitle = null
        refreshSessionLabel(enabled = true)
        pushItem(RenderItem.ErrorItem(CcoderText.text("chat.error.restoreSession", reason)))
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
            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.noSessionForMode")))
            return
        }
        requestedMode = mode
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
            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.noSessionForEffort")))
            return
        }
        // wireValue 为 null 就是「默认」：让 sidecar 把这一项从 flag 层清掉。
        // 这个 null 必须**显式**发出去（见 Protocol.encodeSetEffort）——
        // 省略字段只表示"没提这件事"，清不掉任何东西
        c.sendLine(Protocol.encodeSetEffort(nextId(), setting.wireValue))
    }

    /**
     * 会话标题的唯一出口。规则见 [currentSessionTitle]。
     *
     * 2026-09-16：目标从"面板里那个会话标签"换成了**胶囊行**（以及平台那份
     * displayName）。名字保留是因为它在这 19 处调用点里就是"标题变了"的意思。
     *
     * @param enabled 保留参数：原来忙时要把标签变灰，而现在那颗胶囊靠**状态点**
     *   表达忙闲，标题不再变灰 —— 于是这里忽略它，仍收下是为了不动那 19 个调用点
     */
    @Suppress("UNUSED_PARAMETER")
    private fun refreshSessionLabel(enabled: Boolean = !busy) {
        // 标题如今住在胶囊行上，而胶囊是**照着 SessionTabs 现算的**（它问本面板要
        // `tabTitle()`）—— 所以这里只要喊一声"重画"，不留第二份标题
        SessionTabs.getInstance(project).notifyTabsChanged()
    }

    /**
     * 界面语言变了：把本面板上所有"从词表取的字"重取一遍。
     *
     * 由 `SessionTabs` 订阅语言变更后统一叫（见 `UiLanguageSettings.addListener`）——
     * 每切一次语言，每个开着的面板各跑一遍。
     *
     * 三件事，缺一不可：
     *
     * 1. **带键的控件走一遍树**（[retranslateTree]）：这是大头 —— 键记在控件上的
     *    那些自动跟着换，不必每个组件各写一个 retranslate（漏一个就是"角落里留着
     *    旧语言"，见 LocalizedText.kt）；
     * 2. **刷新家族**：状态卡、胶囊、主按钮、模式/思考/模型标签、会话标题、排队条
     *    —— 它们的字是算出来的，重算即新语言（`refresh*` 是各自唯一的出口）；
     * 3. **两条跨进程的桥**：转写区（JCEF 页面）重推语言、sidecar 收一条 `setUiLang`
     *    —— 少一条就会得到"面板英文、报错中文"这种半截子（设计稿 §七的教训）。
     *
     * **已经在转写区里的条目不动**：那是"说过的话"，跟日志一样留在当时的语言里，
     * 新说的才是新语言。刻意如此，写在这儿免得以后有人当漏译来修。
     */
    internal fun retranslate() {
        retranslateTree(this)
        refreshStatusCards()
        refreshConnectionCard()
        refreshCardActions()
        refreshMainButton()
        refreshModeLabel()
        refreshEffortLabel()
        refreshModelLabel()
        refreshChips()
        refreshSessionLabel()
        refreshQueueStrip()
        transcriptView.setLocale()
        client?.sendLine(Protocol.encodeSetUiLang(nextId(), CcoderText.tag()))
        // 英文比中文长：宽度都是在各自的首选尺寸里定下来的，不 revalidate 会留一帧
        // 裁切；composer 的占位符更是**画的时候**才取词（见 Composer.kt），要重画
        revalidate()
        repaint()
    }

    /** 按服务里的现状重画胶囊行。谁改了"标签那边的事"就调它。 */
    private fun refreshChips() {
        sessionChips.render(SessionTabs.getInstance(project).tabs())
    }

    /** 会话标题（给胶囊行读）。null = 还没起名。 */
    internal fun tabTitle(): String? = currentSessionTitle

    /**
     * 这条会话此刻的状态（给胶囊上那颗点读）。
     *
     * 判据在 [tabStateOf]（纯函数，可单测）—— 这里只喂它三个字段。
     */
    internal fun tabState(): TabState = tabStateOf(permissionQueue.totalPending, busy, starting)

    /**
     * 这条标签**现在**是哪条会话 —— 落进 `ClaudeSettings.openTabs` 的就是它
     * （见 [SessionTabs.rememberTabs]）。
     *
     * 三处取值按"谁更权威"排：
     *  1. [currentSessionId]：会话已经建起来了，这是事实
     *  2. [resumeTargetId]：正在恢复它（回执还没回来）
     *  3. 存档里那个 id：**只在这个标签还没上过屏时算数**。上过屏之后它就不再代表
     *     这条标签了 —— 用户可能已经点了「新建会话」或切到了别的会话，那时再拿
     *     存档里的 id 存下去，重启后会把一条早就被换掉的会话认回来
     *
     * 空串（不是 null）：XmlSerializer 那边"没值"和"空串"是一回事，而 [OpenTab]
     * 的空串是有意义的那一种（"这条标签还没起过会话"）。
     */
    internal fun tabSessionId(): String? =
        currentSessionId ?: resumeTargetId ?: restoreTargetId?.takeIf { !firstShowDone }

    /** 这条标签用的哪个模型（存标签时读它，见 [tabSessionId]）。 */
    internal fun tabModel(): ModelProfile? = currentModel

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
        // 「＋」相反：单一动作按钮点了没反应更像坏了，所以到上限时直接置灰 ——
        // 但它**不再**跟着忙闲走（多标签之后新建不停当前会话，见 [onNewSession]）
        newSessionButton.setTabState(SessionTabs.getInstance(project).tabCount)
        refreshMainButton()
        // 忙闲直接决定两颗动作按钮的可用性 —— 它不经过卡面刷新（spec §3.5）
        refreshCardActions()
    }

    /**
     * 换一条**新的**会话重开（改完模型要重开、以及 fatal 断开后重开）。
     *
     * ## 为什么必须清 [currentSessionId]（2026-09-22 修的那个 bug）
     *
     * 新进程回来时会带一个**新的 session id**，而这个字段还指着上一条 —— 那条
     * `init` 于是被 [isSessionSwitch] 当成 `/clear`，**迟到地把转写区清了一次**。
     * 用户看到的是「重开之后发出的第一条消息不见了」：那条消息的气泡刚推上去
     * （[submit] 先推气泡、再暂存），就被这次 Reset 抹掉 —— 而消息其实已经发出去，
     * 回答照样会回来（屏幕上就成了"只有回答、没有问题"）。
     *
     * 清成 null 之后，[isSessionSwitch] 按它自己的规矩不判切换（"`current` 为 null
     * 时不算 —— 全新会话的第一个 init 就是这种情况"），新 id 只是被记下来。
     *
     * ## 两档调用方要的东西不同，所以清空不在这里做
     *
     * - fatal 断开后重开：转写历史**留着**（本轮之前那句文档要的就是"留在界面上
     *   供参考"）—— 从前它其实留不住，会被上面那次迟到的 Reset 抹掉；
     * - 换模型重开：上下文真的没了，由调用方**当场**清（见 [switchModel]）——
     *   在场清才是对的：迟到的清会吃掉用户在这中间发的消息。
     */
    private fun restartSession() {
        stopSession()
        currentSessionId = null
        currentSessionTitle = null
        // 重开 = 起一条**新的**：带着上次的恢复目标会让新进程又去恢复旧会话，
        // 与"重开"这个动作的本意相反（[startNewSession] 里同一条规矩）
        resumeTargetId = null
        refreshSessionLabel(enabled = true)
        disconnected = false
        setBusy(false)
        // 用量归零：新会话**确实**没有用量。不清的话上下文卡会挂着上一段的读数，
        // 而那正是"看着还在、其实没了"
        lastUsage = null
        lastContextDetail = null
        refreshMainButton()
        startSession()
    }

    /**
     * 换会话时的清空：转写区 + 标题 + 用量读数。
     *
     * **两处共用**，因为它们是同一件事，只是谁先开口不同：`/clear` 是 CLI 说它换了
     * （见 init 那一支），换模型重开是我们自己要换（见 [switchModel]）。
     * 清哪几样必须一致 —— 少清用量，新会话的上下文卡就会挂着上一段的读数。
     *
     * 标题也回「新会话」：旧标题描述的是那条已经不在的会话。
     */
    private fun clearForNewSession() {
        pushOp(TranscriptOp.Reset)
        currentSessionTitle = null
        refreshSessionLabel(enabled = true)
        lastUsage = null
        lastContextDetail = null
    }

    // ---- 会话生命周期 ----

    /**
     * 起一个会话。
     *
     * @param open 这条会话从哪儿起步，三档见 [FirstShow]：新建、恢复**最近**那条、
     *   或恢复**点名**的那条（重启后照存档回来的标签）。**只有第一次上屏这条路**
     *   会传后两档（见 [consumeFirstShow]）；「＋」新建、删掉当前会话、断开后重启
     *   都走默认的 [FirstShow.New] —— 它们的语义都是"要一个新的"，不该被抢走。
     *
     * 恢复那两条路刻意**不是**"先起新会话再切过去"：那样会在硬盘上真的留下一条
     * 空会话（列表里越攒越多），用户也会看见转写区闪一下。
     *
     * `internal` 而不是 public：参数类型 [FirstShow] 是内部的（同 [ChangelogStore]
     * 那条规矩，public 函数不许暴露 internal 类型）。外面本来也没人调它 ——
     * 面板起来之后，会话的生杀都由面板自己那几条路走。
     */
    internal fun startSession(open: FirstShow = FirstShow.New) {
        if (proc != null || starting) return
        starting = true
        // 这一趟的令牌。异世界（池线程）走一遭回来要拿它问一句"我还算数吗"
        val token = sessionGate.begin()

        // 新会话，旧会话的任务与清单全部作废。
        // SDK 的电平信号"在启动时不发任何东西"，只会在下次成员变动时重发全量 ——
        // 所以消费者必须自己清空，否则上一轮的"2 个运行中"会一直挂在那儿
        runStatus.reset()
        // 用量同理：它是**上一个会话**的读数，新会话起手是空的。
        // 不清的话，换模型重开会话之后那一格会继续显示上一场的百分比 ——
        // 一个又大又吓人的数，而新会话其实什么都没装
        lastUsage = null
        lastContextDetail = null
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
            fail(CcoderText.text("chat.error.noBasePathCwd"))
            return
        }
        setConnection(ConnectionState.Starting)
        refreshMainButton() // ready 仍为 false → 按钮显示"启动中…"并禁用
        LOG.info("CCoder 会话启动：cwd=$base")
        val epoch = ++sessionEpoch

        ApplicationManager.getApplication().executeOnPooledThread {
            // spec §5.3：node 与 claude 的缺失各有独立原因，
            // "没装 node"和"启动失败"的修复动作完全不同
            //
            // 先三级解析（2026-09-17）：装完 node 之后 IDE 的 PATH 不会刷新，
            // winget/brew 装出来的那份只有"已知安装目录"那一级找得到
            val nodePath = NodeCheck.resolve()
            when (val node = NodeCheck.verify(nodePath ?: "node")) {
                is NodeStatus.NotFound -> {
                    fail(
                        CcoderText.text("chat.error.nodeMissing", NodeCheck.MIN_MAJOR)
                    )
                    return@executeOnPooledThread
                }
                is NodeStatus.TooOld -> {
                    fail(
                        CcoderText.text("chat.error.nodeTooOld", node.version, NodeCheck.MIN_MAJOR)
                    )
                    return@executeOnPooledThread
                }
                is NodeStatus.Ok -> Unit
            }

            try {
                val sidecarDir = SidecarLocator.resolve(base)
                val p = SidecarProcess(
                    sidecarDir,
                    nodePath = nodePath ?: "node",
                    // 会话建立**之前**那些报错（找不到 claude、认证失败…）也要是当前语言，
                    // 而那时候还没有 start 消息可带 —— 所以进程环境这一路不能省
                    uiLang = CcoderText.tag(),
                ) { exit ->
                    // 回调在看门狗线程上，碰 Swing 必须回 EDT
                    ApplicationManager.getApplication().invokeLater {
                        // 已经换过会话了，这条死讯属于上一个进程
                        if (epoch != sessionEpoch) return@invokeLater
                        onSidecarDied(exit)
                    }
                }
                p.start()
                val c = SidecarClient(p.stdout!!, p.stdin!!, this)

                // 进程起来了，但这一趟可能已经作废（点「＋」立刻点叉、切会话、
                // 面板被销毁）。不判的话没有任何代码会杀它 —— stopSession 只对
                // proc/client 动刀，而此刻它们还是 null（见 [SessionGate]）
                if (!sessionGate.isCurrent(token)) {
                    LOG.info("CCoder 启动被取消（进程已起，未归属），收掉它")
                    runCatching { p.shutdown() }
                    runCatching { c.close() }
                    return@executeOnPooledThread
                }

                ApplicationManager.getApplication().invokeLater {
                    // 再判一次：停止可能发生在"这条 invokeLater 排队"之后、执行之前，
                    // 而真正把进程交出去的正是下面这两行
                    if (!sessionGate.isCurrent(token)) {
                        LOG.info("CCoder 启动被取消（回到 EDT 之后），收掉它")
                        runCatching { p.shutdown() }
                        runCatching { c.close() }
                        return@invokeLater
                    }
                    proc = p
                    client = c
                    // 起好了，启动闸归位（见 [starting]）
                    starting = false
                    sessionGate.finish()
                }
                c.start()

                // 新建那条不走列表：起手就是一条空会话。只有"恢复"两档才要去问
                // 一句有哪些历史会话 —— 见 [FirstShow]
                if (open != FirstShow.MostRecent && open != FirstShow.Given) {
                    sendStart(c, base)
                    return@executeOnPooledThread
                }

                // 点名要恢复的那条。只有 Given 那一档上它才非空（见 [firstShowPlan]），
                // 所以这里取的就是"存档里写的那条"
                val wantedId = if (open == FirstShow.Given) restoreTargetId else null

                // 打开面板：先问一句"这个项目有哪些历史会话"，再决定起哪一个。
                // listSessions 不需要活会话（sidecar/index.js:117），所以这里问得出口
                val reqId = nextId()
                c.request(
                    reqId,
                    Protocol.encodeListSessions(reqId, base, SESSION_LIST_QUERY_LIMIT, 0),
                ) { outcome ->
                    // 回调在读取线程上，碰 Swing 必须回到 EDT
                    ApplicationManager.getApplication().invokeLater {
                        // 抢先发消息会走 stopSession + startSession 重开，那时这个
                        // client 已经废了（照发 start 会打到一条没人读的通道上）。
                        // 那条路的 resumeTargetId 是 null，本来就该开新会话，
                        // 所以这里直接放弃，什么都不用补
                        if (client !== c) return@invokeLater

                        // 跳过已被别的标签占住的会话（见 [OpenSessions]）——
                        // 两个标签开同一条会两边同时写同一个 jsonl
                        val claims = OpenSessions.getInstance(project)
                        // 「点名要哪条」（重启后照存档回来的标签）与「挑最近那条」共用
                        // 同一套结局与后续动作，只有"挑哪一条"不同。分开各写一遍的话
                        // 下面这段"占用登记 + 设标题 + 挂 resumeTargetId"迟早会漂移
                        val pick = if (wantedId != null) {
                            openPickGiven(outcome, wantedId) { claims.isTaken(it) }
                        } else {
                            openPick(outcome) { claims.isTaken(it) }
                        }
                        when (pick) {
                            is OpenPick.Resume -> {
                                val sid = pick.session.sessionId
                                if (claims.reserve(sid, this@ClaudePanel)) {
                                    LOG.info("CCoder 第一次上屏恢复会话：$sid（恢复来源：${if (wantedId != null) "存档" else "最近"}）")
                                    currentSessionTitle = sessionLabelTitle(pick.session)
                                    resumeTargetId = sid
                                    refreshSessionLabel(enabled = true)
                                } else {
                                    // 竞态：挑的时候还空着，这一会儿被别的标签占了。
                                    // 那就当没有历史 —— 开新会话，不提示（同 OpenPick.None）
                                    LOG.info("CCoder 想恢复的会话已被别的标签占用：$sid，改开新会话")
                                }
                            }

                            // 没有历史：一个字都不说，与「＋」新建同一条路。
                            // 这里报"列不出会话"的话，每开一个新项目都会收到一句
                            // 并不存在的错误。
                            //
                            // 但**存档点名却没找着**是另一回事，用户明确期待看到那条 ——
                            // 说一句，别让这个标签看起来像是"自己变成了新会话"。两种
                            // 可能都在那句话里：它被删了，或者它已经被别的标签开着
                            // （后者要用户先在别的标签里手动打开同一条，才可能发生 ——
                            // 存档里两条标签写同一条的情况在 prune 那一步就去掉了）
                            OpenPick.None -> if (wantedId != null) {
                                LOG.info("CCoder 恢复标签：存档里的会话 $wantedId 拿不到（已删或在别的标签里），开新会话")
                                // 存档里那个标题也得作废：会话都没了，顶着它的胶囊
                                // 就是一个在撒谎的名字（见 currentSessionTitle）
                                currentSessionTitle = null
                                refreshSessionLabel(enabled = true)
                                pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.restoredGone")))
                            }

                            is OpenPick.Unavailable ->
                                pushItem(
                                    RenderItem.SystemNote(
                                        CcoderText.text("chat.note.openedNewSession", pick.reason)
                                    )
                                )
                        }
                        sendStart(c, base)
                    }
                }
            } catch (e: SidecarNotFoundException) {
                fail(e.message ?: CcoderText.text("chat.error.sidecarDirMissing"))
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
                    .toStartParams(Path.of(base), ModelProfiles.getInstance(), currentModel)
                    // 语言与 resumeSessionId 一样是"发出时才读"的字段：这一条让新会话
                    // 用上当前语言（node 进程是复用的，光靠启动环境变量做不到）
                    .copy(resumeSessionId = resumeTargetId, uiLang = CcoderText.tag()),
            )
        )
    }

    private fun fail(text: String) {
        // 起会话中途失败的每一条路都走这里（NodeCheck 不过、sidecar 找不到、
        // 建进程抛错），启动闸必须在这里归位，否则面板从此再也起不了会话
        starting = false
        sessionGate.invalidate()
        ApplicationManager.getApplication().invokeLater {
            setConnection(ConnectionState.StartFailed)
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
        sessionGate.invalidate()
        // 进程没了，这条会话就不再被本标签占着 —— 不放的话列表里那一行会永远
        // 写着"已打开"，而实际上谁也打不开它（同 [stopSession] 那条生命周期）
        OpenSessions.getInstance(project).releaseAll(this)

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

        setConnection(ConnectionState.Disconnected)
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
        // 在途的那一趟启动就此作废：它回来时会在 [SessionGate] 那里被拦下并收掉
        // 自己刚起的进程。**也必须放掉 [starting]** —— 否则"启动期间切会话"
        // 会让下面那次 startSession 直接返回，界面停在没有会话的状态上
        sessionGate.invalidate()
        starting = false

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
        // 占用的生命周期就是"本标签有一个活着的 sidecar 在跑这条会话" ——
        // 会话停了，登记也该放掉（它覆盖新建/重启/切会话/dispose 全部入口）
        OpenSessions.getInstance(project).releaseAll(this)
    }

    override fun dispose() {
        // 秒表捕获着这个面板：不摘的话它会把面板钉在事件队列上，一秒一跳地
        // 刷一个已经没了的组件
        stopWaitingTicker()
        // 标签容器那边的订阅同理 —— 摘晚一步它就会去碰一个已经销毁的按钮
        SessionTabs.getInstance(project).removeTabsListener(tabsListener)
        // 状态栏那份记账也要摘干净：留着的 restoreAsk 会让人点一下"回到提问"，
        // 而那个框属于一个已经销毁的面板（点下去什么都不发生，或者更糟）
        PendingPermissionCount.getInstance(project).clear(this)
        stopSession()
        Disposer.dispose(transcriptView)
    }

    // ---- SidecarListener ----

    override fun onMessage(msg: SidecarMessage) {
        ApplicationManager.getApplication().invokeLater {
            when (msg) {
                is SidecarMessage.Ready -> {
                    ready = true
                    setConnection(ConnectionState.Connected)
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
                        pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.sessionReady")))
                        // 全新会话：没有标题可显示，标签是斜体的「新会话」
                        currentSessionTitle = null
                        refreshSessionLabel(enabled = true)

                        // 补发窗口就绪前暂存的首条消息。气泡还在（submit 推过、中间
                        // 没有任何东西清过），所以不必重新推
                        flushPendingFirstMessage(repushBubble = false)
                    }
                }

                is SidecarMessage.Event -> {
                    val items = MessageRenderer.render(msg)
                    // 命令回合里的空输出丢掉；其余一律照常（设计稿 §5.1）。
                    // pushItem 只在 ToolStarting 没 id 时跳过它（那种卡片配不上结果）
                    items.filterNot { lastSendWasCommand && isEmptyCommandOutput(it) }
                        .forEach { pushItem(it) }

                    // 连接卡上那行字跟着这批渲染项走（见 Activity.kt）。
                    // **只走实时路径**：回放旧会话时最后一条可能是被中断的
                    // 工具调用，照着它显示"运行指令"会是一句假话
                    items.forEach { item ->
                        // 先记这批工具：一条 assistant 消息可以带多个 tool_use，
                        // 「整批跑完没有」决定工具结果之后是继续写工具词还是切成
                        // 「等待响应」（那几十秒的 TTFT 就落在这个判断后面）
                        when (item) {
                            is RenderItem.ToolUse -> pendingToolIds += item.id
                            is RenderItem.ToolResult -> pendingToolIds -= item.toolUseId
                            else -> Unit
                        }
                        when (val change = activityChangeOf(item, pendingToolIds.isNotEmpty())) {
                            // 归属只在卡面**真的换了**这一拍算（规则见 subagentOf）：
                            // Keep 那些项不该动它
                            is ActivityChange.Now ->
                                setActivity(change.activity, subagentOf(item, activitySubagent))
                            ActivityChange.Idle -> setActivity(null, subagent = false)
                            ActivityChange.Keep -> Unit
                        }
                    }
                    // result 是回合结束的信号，此时按钮从"停止"变回"发送"
                    if (items.any { it is RenderItem.Result }) {
                        // 回合结束了，上一批工具若有没配到结果的（中断、报错），
                        // 那些 id 不能留到下一轮去
                        pendingToolIds.clear()
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
                            // 这一份到齐 = `/` 的列表可以换成"CLI 认的那些名字"了。
                            // 弹层里可能正拿着"归一化猜出来的"那批在显示（见
                            // commandCandidates 的 preInit 说明）—— 刷新一次，
                            // 让每一行的插入值换成真正可发送的那个
                            .also { if (completion.isOpen) refreshCompletion() }

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
                                clearForNewSession()
                                refreshSessionList()
                                pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.contextCleared")))
                            }
                            // 占用登记对账：init 报的 id 才是事实（见 [confirmOwnership]）
                            confirmOwnership(sid)
                            // 当前会话指针以 init 里的 id 为准，**不以会话列表为准**
                            // —— 刚 /clear 出来的新会话还没落盘，列表未必列得到它
                            currentSessionId = sid
                        }
                    }
                    // 权限模式的**读回**：CLI 的 status 事件里带着它此刻真正在跑的模式
                    // （2026-09-16 实测，sidecar/tools/probe-auto-mode.mjs）。事件本来
                    // 就透传到这里，只是从前没人解读 subtype=status 这一支。
                    //
                    // 这条补的是"问不到"的那一半：切换有回执所以向来可信，但
                    // **会话以什么模式起来**没有任何读回手段 —— 闸门（disableAutoMode /
                    // 订阅档 / 断路器）都在 CLI 侧，它启动时换了档，我们从前看不见，
                    // 标签会一直显示用户选的那个。
                    //
                    // 只改标签、**不回写设置**：设置是用户的意愿，CLI 报的是现场；
                    // 拿现场写回意愿，等于用户的选项被悄悄改掉。
                    val reported = permissionModeOfStatus(msg.event)
                    val action = modeReadbackOf(reported, currentMode, requestedMode)
                    if (reported != null && action != ModeReadback.None) {
                        currentMode = reported
                        refreshModeLabel()
                        // 用户自己点的那次切换由回执那条负责说明（见 ModeReadback）
                        if (action == ModeReadback.Announce) {
                            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.modeCorrected", reported.label)))
                        }
                    }

                    // 压缩中/已结束：同一条 status 事件里带着（spec 事实 9、11）。
                    // 取 CLI 的实况而不是"我们刚发了什么"——**自动压缩**也要能在卡上
                    // 看见，而那种情况我们没发过任何命令。结束认 status 回到 null，
                    // **不等 compact_boundary**：实测它更晚（在 init 之后），
                    // 卡上的"压缩中"不该多挂一秒
                    compactStateOfStatus(msg.event)?.let { phase ->
                        val next = phase == CompactState.Compacting
                        if (next != compacting) {
                            compacting = next
                            refreshStatusCards()
                        }
                    }

                    // 任务与子代理的状态要走**每一个**事件，不只是 result ——
                    // task_progress 这类事件不会产出任何转写项，但它们正是
                    // "现在在跑什么"的全部信息来源
                    runStatus.consume(msg.event)
                    refreshStatusCards()
                }

                is SidecarMessage.Failure -> {
                    // 请求失败了，"等着回执"这件事就结束了 —— 不清掉的话
                    // 下一次真的读数（哪怕不是同一档）会被误当成"用户刚点的"
                    requestedMode = null
                    pushItem(RenderItem.ErrorItem(failureHintText(msg.code, msg.message)))
                    if (msg.fatal) {
                        // 不静默重连——重连会让用户误以为上下文还在（spec §7.5）
                        setConnection(ConnectionState.Disconnected)
                        ready = false
                        disconnected = true
                        setBusy(false)
                        // 断开后标签变灰但**仍然可点** —— 这正是最需要换个会话的时候
                        refreshSessionLabel(enabled = true)
                        refreshMainButton()
                    }
                }

                is SidecarMessage.Permission -> {
                    // 这一拍最该说清楚的就是"为什么不动了"：在等你点授权。
                    // 归属**沿用上一刻**：授权请求本身没带是谁要的（子代理的工具同样
                    // 要过这道闸），保留比妄断 false 准
                    setActivity(Activity.Permission, activitySubagent)
                    showPermissionCard(msg)
                }

                // 切换**生效了**才更新标签。认不出的模式名什么都不改 ——
                // 显示一个我们自己都不认识的模式，不如保持原样
                is SidecarMessage.PermissionModeChanged -> {
                    requestedMode = null
                    val mode = PermissionModeSetting.entries.firstOrNull { it.wireValue == msg.mode }
                    if (mode == null) {
                        LOG.warn("收到不认识的权限模式回执：${msg.mode}")
                    } else {
                        currentMode = mode
                        refreshModeLabel()
                        // 写回设置：下次启动还按这个模式起会话
                        ClaudeSettings.getInstance(project).permissionMode = mode
                        pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.modeSwitched", mode.label)))
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
                            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.effortSwitched", picked.label)))
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
                            applyModel(pick.profileId, pick.modelId)
                            // 窗口大小跟着模型走：不重问的话，用量卡还会用上一个
                            // 模型的分母，而那多半是另一个窗口
                            requestContextUsage()
                            pushItem(RenderItem.SystemNote(CcoderText.text("chat.note.modelSwitched", pick.modelId)))
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
                    setConnection(ConnectionState.Ended)
                    ready = false
                    // 会话没了，上一份 MCP 状态就不作数了 —— 留着的话，
                    // 用户切到新会话后右栏还在显示上一个会话的 server，
                    // 那比空着更糟：它看起来是"当前"的
                    //
                    // 多标签：**只有自己是被选中的那个标签时才清服务** —— 否则
                    // 一个后台标签的断开会把前台标签刚拿到的读数一起抹掉
                    lastMcpServers = null
                    if (SessionTabs.getInstance(project).isSelected(this)) {
                        McpStatus.getInstance(project).clear()
                    }
                    setBusy(false)
                    refreshMainButton()
                }

                // 请求-响应式的应答本该由 SidecarClient 的待决表按 id 截走
                // （Task 4），到不了这里。列出来只为穷尽性 —— 真漏过来说明
                // 配对没接上，而那个症状会在发起请求的那一侧超时暴露，不在这里补救
                is SidecarMessage.SessionList,
                is SidecarMessage.History,
                is SidecarMessage.SessionDeleted,
                is SidecarMessage.SessionsCleared,
                is SidecarMessage.Commands,
                -> Unit

                is SidecarMessage.Unknown -> Unit // 静默忽略（spec §3.3）
            }
        }
    }

    // 错误码 → 可操作的提示：抽成纯函数了（`FailureHint.kt`），用例直接打

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
            // 起头帧：模型刚决定要用这个工具，参数还在生成。卡片**这时就出生**
            // （名字 + 转圈 + 秒表），参数到了由下面那条同 id 的 ToolUse 补全 ——
            // 界面按 toolUseId 合并成一张（见 web/src/codec.ts 的 applyOps）。
            //
            // 从前这里给 null（卡片要等完整消息，于是读取/搜索那类卡生下来就是
            // 完成态）。id 空时 `startedToolCard` 仍给 null —— 那种卡片配不上结果。
            is RenderItem.ToolStarting -> startedToolCard(item)?.let {
                TranscriptOp.Append(
                    TranscriptItem.ToolUse(
                        nextMessageId(), now(), it.id, it.name, it.input, it.parent,
                    )
                )
            }

            is RenderItem.UserText ->
                TranscriptOp.Append(
                    TranscriptItem.User(nextMessageId(), now(), item.text, item.images)
                )

            // 子代理说的正文**自成一项**，不往主线程那个"进行中"的气泡里塞：
            // 它会带 parent（那张 Task 卡的 toolUseId），界面据此把它收进卡里。
            // 它也**没有增量帧**（实测：子代理正文整段到达，且不会混进匿名的
            // 增量流 —— 见设计稿事实 5 与 12），所以这里 Append 而不是 Finalize。
            is RenderItem.AssistantText -> if (item.parent != null) {
                TranscriptOp.Append(
                    TranscriptItem.Assistant(nextMessageId(), now(), item.text, item.parent)
                )
            } else {
                // 最终消息是权威版本，用它收尾进行中的气泡
                TranscriptOp.FinalizeDelta("assistant", item.text)
            }

            is RenderItem.AssistantDelta -> TranscriptOp.AppendDelta("assistant", item.text)

            // 思考流照常推，但**界面默认收着**（逐字铺开会持续刷屏，决策价值低于
            // 正文 —— 这一条没变）。它存在的意义是让人看见"它还在动"：实测 29%
            // 的思考块跑过 5 秒（P90 10.5s，最长 31.5s），而在这之前屏幕上什么都
            // 不动，用户的原话是"看起来感觉像卡死了"。
            // 整块到达时（下面的 Thinking 分支）由界面自己清掉这个缓冲。
            is RenderItem.ThinkingDelta -> TranscriptOp.AppendDelta("thinking", item.text)

            is RenderItem.Thinking ->
                TranscriptOp.Append(
                    TranscriptItem.Thinking(nextMessageId(), now(), item.text, item.parent)
                )

            is RenderItem.ToolUse ->
                TranscriptOp.Append(
                    TranscriptItem.ToolUse(
                        nextMessageId(), now(), item.id, item.name,
                        // 兜底再截一次：历史回放那条路不经过 renderToolResults /
                        // renderAssistant，Write 整文件仍可能从这儿过（见
                        // truncateForTranscript 的说明 —— 大报文会掐断 IDEA 的
                        // remote JCEF 通道）
                        shrinkToolInput(item.input), item.parent,
                    )
                )

            // 结果单独成项，按 toolUseId 由界面挂回那张卡片 ——
            // 操作序列因此保持"只追加"，不必去改一条已经推出去的项
            is RenderItem.ToolResult ->
                TranscriptOp.Append(
                    TranscriptItem.ToolResult(
                        nextMessageId(), now(), item.toolUseId,
                        truncateForTranscript(item.text), item.isError,
                    )
                )

            is RenderItem.ErrorItem ->
                TranscriptOp.Append(TranscriptItem.Error(nextMessageId(), now(), item.message))

            is RenderItem.Result ->
                TranscriptOp.Append(
                    TranscriptItem.Result(
                        nextMessageId(), now(), item.subtype, item.costUsd, item.durationMs,
                        item.inputTokens, item.outputTokens, item.cacheReadTokens,
                    )
                )

            is RenderItem.SystemNote ->
                TranscriptOp.Append(TranscriptItem.SystemNote(nextMessageId(), now(), item.text))
        }
    }

    private fun pushOp(op: TranscriptOp) = transcriptView.push(op)

    /**
     * 推一条渲染项。产出 null 的那些（没带 id 的 [RenderItem.ToolStarting]）自动跳过。
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
                    note = CcoderText.text("chat.note.answered", picked.values.flatten().joinToString(CcoderText.text("common.listSeparator"))),
                )
            },
            onDeny = { decide(perm, deniedByUser()) },
            // 最小化/恢复都要让状态栏跟着变 —— 那儿是"回来的路"（见 updateStatusBar）
            onSuspendChange = { updateStatusBar() },
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
                    decision.stopAsking -> CcoderText.text("chat.note.allowedForSession", perm.toolName, AUTO_ALLOW_LABEL)
                    decision.allow -> CcoderText.text("chat.note.allowed", perm.toolName)
                    else -> CcoderText.text("chat.note.denied", perm.toolName)
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
     *
     * **挂起提问也在这里同步**：状态永远由 [askSequence] 说了算，于是
     * "答完了 / 拒了 / 会话停了 / 框被终止路径关掉"这些路径只要走到这儿，
     * 状态栏就不会留下一个点下去什么都不发生的入口。
     */
    private fun updateStatusBar() {
        runCatching {
            val service = PendingPermissionCount.getInstance(project)
            // 按 owner 写（多标签：两个面板的计数要**求和**、挂起提问各存各的，
            // 见 PendingPermissionCount 的类注释）
            service.set(this, permissionQueue.totalPending)
            val suspended = askSequence?.suspended == true
            val seq = askSequence
            service.setSuspended(
                owner = this,
                suspended = suspended,
                restore = if (suspended && seq != null) {
                    { showModal { restoreAsk(seq) } }
                } else {
                    null
                },
            )
            // 工具窗口里那条带子与状态栏**同一个状态源**：两条路都是"有没有被
            // 最小化的提问"，各判各的迟早会漂移（一条说有事、另一条说没事）
            askRestoreBar.setSuspended(shouldShowAskRestore(suspended))
        }
        // 权限队列变化影响忙闲与状态栏 —— 但「＋」只跟标签数走
        // （多标签之后有权限挂着也能开新标签，见 [onNewSession]）
        newSessionButton.setTabState(SessionTabs.getInstance(project).tabCount)
        // 待决数变了 → 胶囊上那颗点可能要从"空闲"变成"等你批准"，
        // 而且**别的**标签那行也要跟着变（它们画的是同一份列表）
        SessionTabs.getInstance(project).notifyTabsChanged()
    }

    /**
     * 从状态栏回到那个被最小化的提问。
     *
     * 身份比对同 [openPermissionDialog] 那两处：用户点状态栏的这一拍里，那个序列
     * 可能已经被拒掉/终止掉了（他可能刚点了「停止」，或 sidecar 退了），
     * 那时再弹回来就是朝一个已经不存在的请求说话。
     *
     * 调用方负责排在"没有模态框"的那一拍上（[showModal]）—— 理由同 [AskSequence.restore]。
     */
    private fun restoreAsk(seq: AskSequence) {
        if (askSequence !== seq) return
        seq.restore()
    }

    // 说明：spec §6.3 原本那两条补偿（粘性通知、待决超时提醒）随"非模态卡片"
    // 一起退休了（2026-09-14 改成模态框）。留着状态栏计数就够：框会自己弹出来，
    // 而"弹出来还被忽略"这件事在模态形态下不存在。
    //
    // 2026-09-15 用户报"想看看代码再来回答"→ 加了提问框的最小化（见 AskSequence.minimize）。
    // 那是**用户主动**把框挪开的，于是"被忽略"这件事重新出现了一次 —— 补偿就是上面
    // 这条状态栏：挂起期间它一直写着"有提问待回答"。与 §6.3 不冲突：模态框对**没被
    // 挪开**的那些请求仍然不需要补偿。

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
        if (q == null) {
            // `#` 查询整个没了（退格掉、或者光标挪开）：符号名缓存这时才该丢。
            // 它是**全量枚举**的结果、与查询无关（见字段上的说明），
            // 所以只在"这一轮引用结束了"时重来一遍 —— 不跟着弹层开关走
            symbolNamesCache = null
            return closeCompletion()
        }

        when (q.trigger) {
            // 符号那条是**异步**的（要查索引、还要解析 PSI），形状与下面两条不一样：
            // 它自己管开层与关层，见 refreshSymbolCompletion
            Trigger.Symbol -> refreshSymbolCompletion(q)

            // 预设排在最前：打 `/` 的人多半想找的是自己那几条常用说法。
            // **必须整组连续** —— 分组标题只在换组时插一条（CompletionPopup），
            // 把预设和命令交叉排会画出一串重复的标题
            Trigger.Command -> openCompletion(
                q,
                filterCandidates(
                    promptCandidates(promptPresets()) +
                        commandCandidates(commandList, sendableNames),
                    q.query,
                ),
            )

            // 索引期间不查文件候选：[collectProjectFiles] 走 ProjectFileIndex，
            // dumb 态下那套 API 会抛 IndexNotReadyException。命令那条不碰索引，
            // 照常给。面板本身是 DumbAware（见 ClaudeToolWindowFactory），
            // 所以这里必须自己挡 —— 平台不会再替我们兜底了
            Trigger.File ->
                if (q.query.isEmpty()) {
                    // 光打一个 `@`：**不列候选**（那会闪一屏，`@` 从上线起就是这规矩），
                    // 但也不再什么都不显示 —— 用户 2026-09-15 报"输入 @ 和 # 都没反应"，
                    // 卡的就是这一步：他以为功能坏了，其实只是还差一个字
                    showHint(q, HINT_FILE)
                } else {
                    openCompletion(
                        q,
                        if (DumbService.getInstance(project).isDumb) emptyList()
                        else fileCandidates(allProjectFiles(), q.query),
                    )
                }
        }
    }

    /**
     * 光打一个触发字符时的那行提示（**不是候选**）。
     *
     * 与"`@` 不能一打就闪一屏"那条规则不冲突：这里一行候选都不列，只说明下一步该干什么。
     */
    private fun showHint(q: CompletionQuery, text: String) {
        symbolSearching = false
        symbolFailure = null
        completionHint = text
        completionQuery = q
        completionItems = emptyList()
        completionIndex = 0
        showCompletion()
    }

    /** 同步那两条的收尾：截到弹层行数、空则关层、否则开层。 */
    private fun openCompletion(q: CompletionQuery, candidates: List<CompletionItem>) {
        val filtered = visibleCandidates(candidates)
        if (filtered.isEmpty()) return closeCompletion()

        symbolSearching = false
        symbolFailure = null
        completionHint = null
        completionQuery = q
        completionItems = filtered
        completionIndex = 0
        showCompletion()
    }

    /**
     * `#` 那条路：**异步**。
     *
     * 另外两条用的都是手上现成的数据（命令列表、项目文件），这一条要查平台的符号索引、
     * 还要把命中的名字解析成 PSI 元素 —— 两者都会阻塞 EDT，而 EDT 一卡，用户看到的就是
     * "打字打不动"。所以查在池线程 + 读操作里做，结果回 EDT 时才开层。
     *
     * 期间弹层里挂一行「正在搜索符号…」（见 [completionStatus]）：一个字都不显示的话，
     * 第一次敲 `#`（要付一次全量枚举，见 [symbolNamesCache]）看起来就像没反应。
     */
    private fun refreshSymbolCompletion(q: CompletionQuery) {
        if (q.query.isEmpty()) {
            // 光打一个 `#` 不查（与 `@` 一致：不能一打符号就闪一屏）。
            // 已经在显示的那批先别关 —— 退格到这一步的人多半还要再打一个字符；
            // 一行都没有时就给那行提示（用户 2026-09-15 卡在的正是这一步）
            if (completionItems.isEmpty()) showHint(q, HINT_SYMBOL)
            return
        }

        symbolSearchId++
        symbolSearching = true
        symbolFailure = null
        completionHint = null
        // 查询词此刻就记下：结果回来时要靠代数比对（用户可能已经改了前缀）
        completionQuery = q
        // 手上已经有行就先留着（用户正在继续打字）—— 新结果到了整批换掉，别闪
        if (completionItems.isEmpty()) showCompletion()

        val id = symbolSearchId
        val prefix = q.query
        val cached = symbolNamesCache
        val project = this.project
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                // 索引与 PSI 都必须在读操作里、且**不在 EDT 上**碰（见 SymbolLookup.kt 的文件头）
                ReadAction.compute<SymbolSearchResult, Exception> {
                    searchSymbols(project, prefix, cached)
                }
            }.getOrElse {
                LOG.warn("符号：搜索抛错", it)
                SymbolSearchResult(
                    hits = emptyList(),
                    names = null,
                    matched = 0,
                    failure = CcoderText.text("chat.error.symbolSearchFailed", it.javaClass.simpleName),
                )
            }
            ApplicationManager.getApplication().invokeLater { onSymbolSearched(id, result) }
        }
    }

    /**
     * 搜索结果回 EDT。
     *
     * 第一件事是**比对代数**：用户接着打字、或者按了 Esc 让层关掉，这次的结果就作废了 ——
     * 不比对的话，Esc 之后弹层会自己长回来（[closeCompletion] 也 +1，正是为了这个）。
     */
    private fun onSymbolSearched(id: Int, result: SymbolSearchResult) {
        if (id != symbolSearchId) return
        symbolSearching = false
        symbolFailure = result.failure
        completionHint = result.status
        result.names?.let { symbolNamesCache = it }

        // 查询取自当前那次补全 —— 高亮的字符要与"为什么这条会出现在列表里"
        // 用同一把尺子（见 symbolCandidates 的 hits）
        val items = symbolCandidates(result.hits, completionQuery?.query.orEmpty())
        if (items.isNotEmpty()) {
            completionItems = visibleCandidates(items)
            completionIndex = 0
            showCompletion()
            return
        }

        val failure = result.failure
        if (failure != null || result.status != null) {
            // 搜不成、"名字找到了、一个都解析不出来"、或者索引还在建：
            // 层里留着那句话（气球只给真失败）。安静地关掉的话，
            // 用户看到的是"这个符号不存在" —— 那是另一回事
            completionItems = emptyList()
            showCompletion()
            if (failure != null) notifySymbolFailure(failure)
            return
        }

        // 真的没有这个名字：安静地不弹，与 `@` 一致（宁缺勿错）
        closeCompletion()
    }

    /** 符号这条路失败时说的那句话。**非粘性**气球：这是"这一次没成"，不是待办。 */
    private fun notifySymbolFailure(reason: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CCoder")
            .createNotification(CcoderText.text("chat.notify.symbolRefTitle"), reason, NotificationType.WARNING)
            .notify(project)
    }

    /** 弹层里那一行状态。有候选时不挂 —— 那时它是噪音。 */
    private fun completionStatus(): String? = when {
        completionItems.isNotEmpty() -> null
        symbolFailure != null -> symbolFailure
        completionHint != null -> completionHint
        symbolSearching -> CcoderText.text("composer.symbol.searching")
        else -> null
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
        // 状态行只在"一行候选都没有"时挂（符号那条路：正在搜 / 搜不成）
        completion.show(input, caret, completionItems, completionIndex, completionStatus())
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

        // 符号：记号与源码**同一刻**记住。与 addSnippetToComposer 同一条规矩 ——
        // "屏幕上写的是哪个符号"与"发出去的是哪段代码"由同一个构造点产出，不可能对不上。
        // 解析结果跟着候选一路走到这里，所以这一步没有额外等待，也不会失败
        item.symbol?.let { snippetRefs.remember(item.insert, symbolSnippet(it)) }

        closeCompletion()
    }

    private fun closeCompletion() {
        completion.hide()
        completionQuery = null
        completionItems = emptyList()
        completionIndex = 0
        // 关层即丢缓存：下次打开能看到这一轮新建的文件
        projectFiles = null
        // 符号：在飞的结果一律作废（Esc 之后弹层不许自己长回来），
        // 但**名字缓存留着** —— 它是全量枚举的结果，清它的地方只有一处：
        // 查询整个消失了（见 refreshCompletion）
        symbolSearchId++
        symbolSearching = false
        symbolFailure = null
        completionHint = null
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
        if (imageTooBig(incoming.sourceBytes)) {
            LOG.info("贴图：没收下 —— ${imageTooBigReason(incoming.sourceBytes)}")
            // 递**算法**不递现成的句子：换语言时带子会重算（见 AttachmentStrip.reject）
            attachments.reject { imageTooBigReason(incoming.sourceBytes) }
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
            attachments.reject { CcoderText.text("chat.image.tooLarge") }
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

        submit(text, typed, images)
    }

    /**
     * 状态卡上那颗动作按钮的入口（spec `2026-09-17-card-actions-design.md` §3.6）：
     * 把 `/clear`、`/compact` 当一条普通消息发出去。
     *
     * **什么都不带**：没有图片、没有引用记号、不进 ↑/↓ 历史（那是"用户敲过的"，
     * 按钮点出来的不是）。别的全走 [submit] —— 清空的清理动作一行都不新写，
     * 那种"两条路各清各的"迟早会出现两套互相打架的状态。
     */
    private fun sendText(text: String) {
        submit(text, typed = text, images = emptyList())
    }

    /**
     * 两条发送路径**共同的去路**：忙时入队 / 未就绪先起会话再暂存 / 否则发出去。
     *
     * 抽出来只为一件事：让"按钮点的"和"手敲的"在**所有分岔上**行为一致 ——
     * 包括那些今天还没被走到、将来会被改的分岔。
     */
    private fun submit(text: String, typed: String, images: List<AttachedImage>) {
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

        // 转写区里推的是**用户敲的那份**（`typed`，记号原样留着），不是展开后的全文 ——
        // 展开只给模型看。铺一份几百行的代码进气泡，自己写的那句话就淹在里头了
        // （2026-09-15 用户提：输出区显示的格式该和输入框里的一样）。
        // 排队那条也要带图：补发时 pushOp 用的是它的 images，不是这里的 forTranscript
        pushItem(RenderItem.UserText(typed, forTranscript))

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
     * 补发"窗口就绪前暂存的首条消息"（见 [submit] 里那条暂存）。
     *
     * **两个出口共用**：`Ready` 那一拍（新会话起好了）与回放结束那一拍（恢复的会话
     * 铺完了，见 [replayItems]）。两处都必须接上 —— 只接前者的话，恢复会话那条路上
     * 暂存的消息永远发不出去，而它在转写区里已经露过一次面。
     *
     * @param repushBubble 转写区里那条气泡**还在不在**。新会话那条路是**还在**的
     *   （[submit] 推完就存起来，中间没有任何东西清过）；恢复会话那条路**不在了**
     *   —— 回放开头的 Reset 把它抹了，所以这里要按同样的内容补推一遍。
     *   推的是**用户敲的那份**（`typed`，记号原样留着），与 [submit] 同一个口径：
     *   铺一份展开后的几百行代码进气泡，自己写的那句话就淹了。
     */
    private fun flushPendingFirstMessage(repushBubble: Boolean) {
        val text = pendingFirstMessage ?: return
        // 标题在这儿认：暂存过的那条就是这条会话的第一条消息
        // （[titleFromFirstMessage] 只在标题还空着时才取名，所以恢复来的标题不会被抢）
        val typed = pendingFirstMessageTitle ?: text
        val images = pendingFirstMessageImages
        pendingFirstMessage = null
        pendingFirstMessageTitle = null
        pendingFirstMessageImages = emptyList()

        if (repushBubble) pushItem(RenderItem.UserText(typed, images.map { it.transcriptDataUrl }))
        adoptTitleFrom(typed)
        client?.sendLine(Protocol.encodeSend(nextId(), text, outgoing(images)))
        setBusy(true)
    }

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
        // 第一口 token 可能要等几秒，这期间卡上写"已连接"是句假话。
        // 这条是你自己发的：归属归零（上一轮的子代理已经结束了）
        setActivity(Activity.Waiting, subagent = false)
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
        // 与直接发送同一条规矩：气泡里画用户敲的那份（`typed`），展开的那份只发给模型
        pushItem(RenderItem.UserText(next.typed, next.images.map { it.transcriptDataUrl }))
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
        /**
         * 光打一个触发字符时那行提示的文案。
         *
         * 2026-09-15 用户报"输入 @ 和 # 都没反应" —— 卡的就是"还差一个字"这件事，
         * 而当时屏幕上什么提示都没有（`@` 不能一打就闪一屏那条规则的另一面）。
         */
        val HINT_FILE: String get() = CcoderText.text("composer.hint.file")
        val HINT_SYMBOL: String get() = CcoderText.text("composer.hint.symbol")

        val LOG = Logger.getInstance(ClaudePanel::class.java)
    }
}
