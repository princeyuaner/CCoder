package com.ccoder.ui

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
 */
internal const val MAX_SESSION_TABS = 5

/** 还没起名的新标签叫什么。与胶囊上的占位同一个词。 */
internal const val NEW_TAB_TITLE = "新会话"

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
    }

    /** 当前有几个标签。上限判定与「＋」的置灰都用它。 */
    val tabCount: Int get() = panels.size

    /** 还有位置吗（给面板上那颗「＋」用）。 */
    fun canOpenNewTab(): Boolean = panels.size < MAX_SESSION_TABS

    /**
     * 开**第一个**标签。由工厂在 `createToolWindowContent` 里调。
     *
     * 新标签是**全新会话**：它不 resume 任何历史 —— 那半由 `ClaudePanel` 的首屏闸
     * （`pickMostRecent`）负责，"第一次上屏"才恢复最近会话。
     */
    fun openFirstTab() {
        addTab(openedByPlus = false)
    }

    /**
     * 开一个新标签。到上限了返回 false（调用方负责说一句话，不静默）。
     *
     * **新标签是空的**：它不恢复任何历史（`openedByPlus = true` 一路传到面板的
     * 首屏闸）。"接着上次聊"只属于开工具窗口那一次 —— 见 [resumeOnFirstShow]。
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
            "这个标签里的会话还在跑，关掉会中断它。",
            "关闭「${panel.tabTitle() ?: NEW_TAB_TITLE}」",
            "关闭",
            "取消",
            UIUtil.getWarningIcon(),
        ) == Messages.YES
    }

    private fun addTab(openedByPlus: Boolean) {
        val panel = ClaudePanel(
            requireNotNull(project) { "SessionTabs 还没 attach 就要开标签" },
            openedByPlus = openedByPlus,
        )
        panels += panel
        // 挂进宿主由 selectOwner 做（新开的必须是当前这条）
        selectOwner(panel)
        // 通知要在 selectOwner 之后：那一步已经把 tabs() 的形状定下来了。
        // 注意 openFirstTab 这条路上 host 还没上屏，selectOwner 里的 revalidate
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
     */
    fun notifyTabsChanged() {
        // 复制一份再遍历：监听器可能在回调里退订（同 PendingPermissionCount 的写法）
        listeners.toList().forEach { it() }
    }

    companion object {
        fun getInstance(project: Project): SessionTabs = project.getService(SessionTabs::class.java)
    }
}
