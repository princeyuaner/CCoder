package com.ccoder.ui

import com.ccoder.settings.ClaudeSettings
import com.ccoder.settings.OpenTab
import com.ccoder.settings.UiLanguageSettings
import com.ccoder.settings.pruneOpenTabs
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent
import javax.swing.JPanel
import com.ccoder.text.CcoderText

/**
 * 工具窗口 id。
 *
 * 此前这个字面量在**三处**各写了一份（面板、右键动作、状态栏组件），都指向"那一个"
 * 窗口。多标签之后"那一个"不再唯一（同一个窗口里有好几个面板），收敛到一处更必要了。
 */
internal const val TOOL_WINDOW_ID = "CCoder"

/**
 * 一个工具窗口里**最多几条会话**。
 *
 * 每个标签是一个完整的 `ClaudePanel` —— 它自带一个 node 侧车 + 一个 claude CLI
 * （实测约 250MB，见 `ClaudePanel` 里那段"常驻一个 node + claude 进程"）外加一个
 * JCEF 实例。5 个是产品上限（用户 2026-09-16 拍板），不是性能保险。
 *
 * 它同时是**存档的上限**（见 [pruneOpenTabs]）：手改过 XML 也越不过去。
 */
internal const val MAX_SESSION_TABS = 5

/** 还没起名的新标签叫什么。与胶囊上的占位同一个词。 */
internal val NEW_TAB_TITLE: String get() = CcoderText.text("session.tabs.newTitle")

/**
 * 关这个标签要不要先问一句。
 *
 * **`starting` 必须算"在跑"**：点「＋」之后立刻点叉是最常见的一条路径，而那一刻
 * `proc` 还是 null（进程已经起了，见 [SessionGate]）——
 * 漏掉它就会出现"问了没问一样"的静默中断。
 *
 * 抽成纯函数是为了能在纯 JVM 单测里钉住这张真值表；[ClaudePanel.hasLiveSessionState]
 * 只负责把四个字段喂进来。
 */
internal fun closeNeedsConfirm(
    starting: Boolean,
    procAlive: Boolean,
    busy: Boolean,
    pendingPermissions: Int,
): Boolean = starting || procAlive || busy || pendingPermissions > 0

/**
 * 会话标签容器：谁开着、谁当前、开一个、关一个。
 *
 * ## 为什么平台那边**只有一个 content**（2026-09-16 返工）
 *
 * 第一版是"每个会话一个 `Content`"，让平台管标签。结果真机上出现**两行**：
 * 平台那行 `TABBED` 与我们自己那排胶囊各画一遍。
 *
 * 试过关掉它 —— 不行：`ToolWindowContentUiType` 只有 `TABBED` 与 `COMBO` 两个值，
 * **没有"不画"**；`canCloseContents` 只管那颗 ✕，不管这一行在不在。只要
 * `ContentManager` 里内容数 > 1，平台就一定会画。
 *
 * 所以改成：平台那边**永远只有一个 content**，它的组件是一个宿主面板，
 * 里面按需挂当前那一条会话。切标签 = 宿主换组件（`removeAll` + `add`，
 * 与设置对话框 `pageHost` 同一个做法），平台那一行自然就不存在了。
 *
 * 代价是自己管三件事：**当前是哪条**、**关闭时的确认**、**销毁时把每条都收干净**。
 * 这三件都在这个类里，一行没跑远。
 *
 * ## 关闭
 *
 * 我们的 ✕ 走 [closeTab]：先问 [closeNeedsConfirm]（忙 / 有待决权限 / 正在启动时
 * [Messages.showYesNoDialog] 问一句），确认了才 `panel.dispose()`。比原来少了
 * `Close All` / `Ctrl+F4` 那两条平台路径 —— 现在根本没有平台标签，它们无从触发。
 *
 * ## 重启 IDE 之后（2026-09-21）
 *
 * 这一排标签**住在项目设置里**（[ClaudeSettings.State.openTabs]）：关 IDE 时开着
 * 几条，回来还是几条，各自回到自己那条会话、自己那个模型、自己那个名字。在这之前
 * 它是纯内存的 —— 工厂只会重建一条，另外几条得用户自己去会话列表里翻出来。
 *
 * 两条要紧的规矩：
 *  - **只有"当前"的那条马上挂进宿主**（[openInitialTabs]）：面板一上屏就起
 *    sidecar，N 条一起上屏就是开工具窗口时突然多出 N 个 node + claude 进程
 *    （每个约 250MB），而后台标签本来就不该占这份资源
 *  - **存档是"该回哪儿"的意思，不是"这几条会话归本插件所有"**：会话被删了
 *    （用户自己删的、「清空全部」清的），那条标签回来时就是一条空标签，并说一句
 *     —— 见 `ClaudePanel` 里那条 `chat.note.restoredGone`
 */
@Service(Service.Level.PROJECT)
class SessionTabs {

    private var project: Project? = null
    private var toolWindow: ToolWindow? = null
    private var content: Content? = null

    /** 宿主：当前那一条会话挂在这里。平台看到的永远只有这一个组件。 */
    private val host = JPanel(BorderLayout())

    /** 开着的会话，按开出来的顺序（胶囊行照这个顺序画）。 */
    private val panels = mutableListOf<ClaudePanel>()

    /** 当前那一条。null = 一条都还没有（attach 之后立刻会被填上）。 */
    private var current: ClaudePanel? = null

    /**
     * 标签那边有变化时挨个喊一声（每个面板拿它重画自己那排胶囊、重算「＋」）。
     *
     * 面板负责**自己退订**（`ClaudePanel.dispose`）—— 订阅是累加的，漏摘一次
     * 就多留一个捕获着已销毁面板的监听器。
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun attach(project: Project, toolWindow: ToolWindow) {
        this.project = project
        this.toolWindow = toolWindow
        // 平台那唯一一个 content：标题给 null（胶囊行已经在说这件事了，
        // 再给标题会多出一行平台画的字），不可锁。
        val created = ContentFactory.getInstance().createContent(host, null, false)
        content = created
        // 关项目 / 卸载工具窗口时把每一条会话都收干净（杀进程树的顺序在 dispose 里）
        Disposer.register(created) {
            panels.toList().forEach { it.dispose() }
            panels.clear()
            current = null
        }
        toolWindow.contentManager.addContent(created)

        // 界面语言热切换（2026-09-20）：语言一变，本窗口里每个面板自己重译一遍。
        // 订阅记在**项目**上而不是面板上 —— 服务是 APP 级的，项目关掉必须退订，
        // 否则那个项目的一串面板会被一个永远活着的监听列表钉住。
        // 服务取不到就不订阅：订阅不上不该拦着窗口打开（2026-09-20 那次 NPE 的教训）。
        UiLanguageSettings.getInstanceOrNull()?.let { language ->
            val listener: () -> Unit = { panels.toList().forEach { it.retranslate() } }
            language.addListener(listener)
            Disposer.register(project) { language.removeListener(listener) }
        }
    }

    /** 当前有几个标签。上限判定与「＋」的置灰都用它。 */
    val tabCount: Int get() = panels.size

    /** 还有位置吗（给面板上那颗「＋」用）。 */
    fun canOpenNewTab(): Boolean = panels.size < MAX_SESSION_TABS

    /**
     * 开**第一批**标签。由工厂在 `createToolWindowContent` 里调。
     *
     * 有存档就照存档开（重启前开着几条，现在还是几条，各自回到自己那条会话、
     * 自己那个模型），没有存档就是一条新的 —— 那半由 `ClaudePanel` 的首屏闸负责
     * （[firstShowPlan]）："第一次上屏"才恢复会话。
     *
     * **只把存档里"当前"的那条挂进宿主**，其余的等用户点它才上屏。这条很要紧：
     * 面板一上屏就会起 sidecar（node + claude，约 250MB 一条），5 条一起上屏
     * 就是开 IDE 时突然多出 5 个进程 —— 而用户可能只是想看看昨天那条会话。
     */
    fun openInitialTabs() {
        val saved = pruneOpenTabs(archivedTabs(), MAX_SESSION_TABS)
        if (saved.isEmpty()) {
            addTab(openedByPlus = false)
            return
        }
        saved.forEach { addTab(openedByPlus = false, restore = it, select = false) }
        // 上次退出时当前的那条（prune 只保证至多一条标着它；一条都没有就取第一条）
        selectOwner(panels[saved.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0])
    }

    /**
     * 开一个新标签。到上限了返回 false（调用方负责说一句话，不静默）。
     *
     * **新标签是空的**：它不恢复任何历史（`openedByPlus = true` 一路传到面板的
     * 首屏闸）。"接着上次聊"只属于第一次上屏那一次 —— 见 [firstShowPlan]。
     */
    fun openNewTab(): Boolean {
        if (!canOpenNewTab()) return false
        addTab(openedByPlus = true)
        return true
    }

    /**
     * 切到某条会话。
     *
     * 切走的那条**不断开**：它只是从宿主里摘下来（`removeNotify`），
     * 进程与上下文都留着 —— 隐藏不断开这条规矩从单会话时代就没变过。
     */
    fun selectOwner(owner: JComponent) {
        val panel = owner as? ClaudePanel ?: return
        if (panel === current) return
        if (panel !in panels) return
        current = panel
        host.removeAll()
        host.add(panel, BorderLayout.CENTER)
        host.revalidate()
        host.repaint()
        notifyTabsChanged()
    }

    /** 当前那一条（右键「加到聊天」、状态栏那条路都要它）。 */
    internal fun currentPanel(): ClaudePanel? = current

    /** 某个面板是不是当前选中的那个（MCP 状态那条"选中者发布"用它）。 */
    fun isSelected(owner: JComponent): Boolean = owner === current

    /**
     * 眼下开着的那些会话 —— 每个面板顶上那排胶囊就是照着它画的。
     *
     * 标题与状态**现问面板**（`ClaudePanel.tabTitle` / `tabState`），服务自己不存一份：
     * 存了就有两份真相，而"忙碌中"这种状态每秒都可能变。
     */
    internal fun tabs(): List<TabChip> = panels.map { panel ->
        TabChip(
            owner = panel,
            title = panel.tabTitle(),
            state = panel.tabState(),
            current = panel === current,
            // 只剩一条时不给关：这个宿主是平台那唯一一个 content，没有它这一栏就空了
            canClose = panels.size > 1,
        )
    }

    /**
     * 关掉一条会话。
     *
     * 忙 / 有待决权限 / 正在启动时先问一句（[closeNeedsConfirm]）—— 那是原来挂在
     * 平台 `contentRemoveQuery` 上的那道闸，现在标签是我们自己画的，闸也跟着搬过来。
     */
    internal fun closeTab(owner: JComponent) {
        val panel = owner as? ClaudePanel ?: return
        if (panels.size <= 1) return
        if (panel !in panels) return
        if (panel.hasLiveSessionState() && !confirmClose(panel)) return

        val index = panels.indexOf(panel)
        panels.remove(panel)
        if (panel === current) {
            // 关掉的是当前那条：接手的是**原位置**那一条（没有就用末尾的），
            // 于是连点几个 ✕ 时焦点不会在两端之间乱跳
            current = null
            host.removeAll()
            val next = panels.getOrNull(index) ?: panels.lastOrNull()
            if (next != null) selectOwner(next)
        } else {
            // 后台标签：它本来就不在宿主里，直接销毁
            host.remove(panel)
        }
        panel.dispose()
        notifyTabsChanged()
    }

    private fun confirmClose(panel: ClaudePanel): Boolean {
        val project = project ?: return true
        // 关项目时不要弹框（那时用户在关 IDE，不是在做选择）
        if (project.isDisposed || ApplicationManager.getApplication().isDisposed) return true
        return Messages.showYesNoDialog(
            project,
            CcoderText.text("session.close.confirmBody"),
            CcoderText.text("session.close.confirmTitle", panel.tabTitle() ?: NEW_TAB_TITLE),
            CcoderText.text("common.close"),
            CcoderText.text("common.cancel"),
            UIUtil.getWarningIcon(),
        ) == Messages.YES
    }

    /**
     * 磁盘上那份存档（重启前开着的标签）。读不出来就是空表 —— 与"从来没开过"
     * 同一条路（[openInitialTabs] 会退回"开一条新的"）。
     */
    private fun archivedTabs(): List<OpenTab> {
        val project = project ?: return emptyList()
        if (project.isDisposed) return emptyList()
        return ClaudeSettings.getInstance(project).openTabs()
    }

    /**
     * 把现在这几个标签落盘（[ClaudeSettings.rememberOpenTabs]）。
     *
     * 谁改了"标签那边的事"就该喊一声 —— [notifyTabsChanged] 已经喊了，另外两处
     * 面板自己调：切模型（`ClaudePanel.applyModel`）与切会话，它们不改胶囊的样子，
     * 但改的正是存档里的内容。
     *
     * 写的是**内存里的状态**（平台按自己的节奏存盘），所以这里可以随便调 ——
     * 但别改成"只在关窗口时存一次"：那个钩子在崩溃/强杀时不会来，而用户丢掉的是
     * 一整排页签。
     */
    fun rememberTabs() {
        val project = project ?: return
        // 关项目那一路会走到这儿（Disposer 里挨个 dispose），那时服务已经在拆了
        if (project.isDisposed) return
        ClaudeSettings.getInstance(project).rememberOpenTabs(
            panels.map { panel ->
                val model = panel.tabModel()
                OpenTab(
                    sessionId = panel.tabSessionId().orEmpty(),
                    profileId = model?.id.orEmpty(),
                    modelId = model?.modelId.orEmpty(),
                    title = panel.tabTitle(),
                    selected = panel === current,
                )
            }
        )
    }

    private fun addTab(openedByPlus: Boolean, restore: OpenTab? = null, select: Boolean = true) {
        // 「界面语言」在这里定下来：一个面板（= 一条会话的界面）诞生时读一次设置，
        // 之后它整个生命周期都用这一种语言。**不在设置里改的那一刻推**：状态卡
        // 每轮 token 都重算文案，中途换语言会得到"卡片英文、面板中文"。
        // 于是设置里改语言表现为：**重启 IDE 后生效**（那次由 loadState 推），
        // 或者新开一个标签 —— 后者只对新标签生效，旧标签留着旧语言，别当切换手段。
        // 关掉再打开工具窗口**不算**：平台的工具窗口内容只在该实例第一次显示时建一次
        // （2026-09-20 用户报「改成英文没切换」，就是照着"重开窗口"做的）。设计稿 §三。
        UiLanguageSettings.applyLanguageToText()
        val panel = ClaudePanel(
            requireNotNull(project) { "SessionTabs 还没 attach 就要开标签" },
            openedByPlus = openedByPlus,
            restoredTab = restore,
        )
        panels += panel
        // 挂进宿主由 selectOwner 做。[select] 为 false 是恢复那一条路：一次开 N 个，
        // 只在最后挂上"上次当前的那条"—— 否则中途每挂一个都会让上一个摘下来，
        // 而摘下来的那个已经上过屏、已经起过 sidecar 了（白白起一个进程）
        if (!select) return
        selectOwner(panel)
        // 通知要在 selectOwner 之后：那一步已经把 tabs() 的形状定下来了。
        // 注意开第一批这条路上 host 还没上屏，selectOwner 里的 revalidate
        // 只是排个队，等真正上屏时 Swing 会照常走 addNotify（首屏闸在面板那边）
        notifyTabsChanged()
    }

    fun addTabsListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeTabsListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 标签那边有变化（多一个 / 少一个 / 谁忙了 / 谁改名字了）时喊一声。
     *
     * **面板自己调**：忙碌与待决权限是面板的私有状态，服务看不见它们。
     *
     * 顺手落一次盘（[rememberTabs]）：多一个少一个、谁改了名字、谁换了会话，
     * 全都从这儿过 —— 而且这些事在面板那边调之前就已经改完了状态。
     */
    fun notifyTabsChanged() {
        rememberTabs()
        // 复制一份再遍历：监听器可能在回调里退订（同 PendingPermissionCount 的写法）
        listeners.toList().forEach { it() }
    }

    companion object {
        fun getInstance(project: Project): SessionTabs = project.getService(SessionTabs::class.java)
    }
}
