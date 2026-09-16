package com.ccoder.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.content.ContentManager
import com.intellij.util.ui.UIUtil
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent

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

/**
 * 关这个标签要不要先问一句。
 *
 * **`starting` 必须算"在跑"**：点「＋」之后立刻点叉是最常见的一条路径，而那一刻
 * `proc` 还是 null（进程已经起了，见 [com.ccoder.ui.SessionGate] 那条注释）——
 * 漏掉它就会出现"问了没问一样"的静默中断。
 *
 * 抽成纯函数是为了能在纯 JVM 单测里钉住这张真值表；[ClaudePanel.closeNeedsConfirm]
 * 只负责把四个字段喂进来。
 */
internal fun closeNeedsConfirm(
    starting: Boolean,
    procAlive: Boolean,
    busy: Boolean,
    pendingPermissions: Int,
): Boolean = starting || procAlive || busy || pendingPermissions > 0

/**
 * 标签容器：谁在开着、开一个、关一个、标题怎么跟上。
 *
 * ## 为什么是项目服务而不是面板自己管
 *
 * 面板不该知道 content 容器 —— 它今天只吃一个 `project`（`ClaudePanel.kt:88`），
 * 这条"面板自足"的性质是本功能能小的根本原因（见设计稿 §3.2）。上限、可关闭性、
 * 关闭确认这三件事需要同时看见**所有**标签，所以它们住在这里。
 *
 * ## 三条平台硬事实（设计稿 §四，读真 jar 得到）
 *
 * 1. `canCloseContents` 只能写在 plugin.xml —— `ToolWindow` 接口上没有 setter
 * 2. 关闭确认**只能**靠 [ContentManagerListener.contentRemoveQuery] 里 `consume()`；
 *    `Content.CLOSE_LISTENER_KEY` 在 2025.3 没有任何读取方
 * 3. **最后一个标签关掉之后平台不会重建面板**（`ToolWindowImpl.createContentIfNeeded`
 *    把 factory 的 `AtomicReference` CAS 成 null 就不再调）—— 所以"至少留一个"
 *    不是可选项，是平台约束，见 [refreshCloseable]
 */
@Service(Service.Level.PROJECT)
class SessionTabs {

    private var project: Project? = null
    private var toolWindow: ToolWindow? = null

    /** 面板 → 它的 content。用 `LinkedHashMap`：顺序就是标签顺序，[order] 要它。 */
    private val contents = LinkedHashMap<JComponent, Content>()

    /**
     * 标签数变化时挨个喊一声（面板拿它刷「＋」的可用性）。
     *
     * 面板负责**自己退订**（[ClaudePanel.dispose]）—— 订阅是累加的，漏摘一次
     * 就多留一个捕获着已销毁面板的监听器。
     */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 批量关闭的去抖时刻（毫秒时间戳）。
     *
     * `Close All` / `Ctrl+F4` 会**同步连问** N 次：用户点一次"关闭"之后，后面那些
     * 不该再问第二遍。这一条是设计稿里明确写"只能靠手工验证"的部分 —— 1.5s 是拍的，
     * 冒烟时若观感不对，就退成"只对第一个问"。
     */
    private var lastBatchCloseAt = 0L

    fun attach(project: Project, toolWindow: ToolWindow) {
        this.project = project
        this.toolWindow = toolWindow
        toolWindow.contentManager.addContentManagerListener(closeGuard)
    }

    /** 当前有几个标签。上限判定与「＋」的置灰都用它。 */
    val tabCount: Int get() = contents.size

    /** 还有位置吗（给面板上那颗「＋」用）。 */
    fun canOpenNewTab(): Boolean = tabCount < MAX_SESSION_TABS

    /**
     * 开**第一个**标签。由工厂在 `createToolWindowContent` 里调。
     *
     * 标题先给一个占位，会话起起来之后 [setTitle] 会把它换成会话标题
     * （与面板自己那个标签同一处更新，见 [ClaudePanel.refreshSessionLabel]）。
     *
     * **待实测**：只有一个标签时平台会不会多画一条标题栏 —— 若显得多余，
     * 改成"只有两个以上标签时才给标题"。
     */
    fun openFirstTab() {
        addTab()
    }

    /**
     * 开一个新标签。到上限了返回 false（调用方负责说一句话，不静默）。
     *
     * 新标签是**全新会话**：它不 resume 任何历史 —— 那半由 [ClaudePanel] 的首屏闸
     * （`pickMostRecent`）负责，"第一次上屏"才恢复最近会话。
     */
    fun openNewTab(): Boolean {
        if (!canOpenNewTab()) return false
        val panel = addTab()
        // 新开出来的那个应当是**当前**这个 —— 否则用户点了「＋」却停在旧标签上
        contents[panel]?.let { toolWindow?.contentManager?.setSelectedContent(it) }
        return true
    }

    /** 标题跟会话走。null = 还没起名（面板那边显示斜体「新会话」）。 */
    fun setTitle(owner: JComponent, title: String?) {
        contents[owner]?.displayName = title ?: NEW_TAB_TITLE
    }

    /** 切到某个面板所在的标签（状态栏点击"回到那个提问"用）。 */
    fun selectOwner(owner: JComponent) {
        contents[owner]?.let { toolWindow?.contentManager?.setSelectedContent(it) }
    }

    /** 某个面板是不是当前选中的那个（MCP 状态那条"选中者发布"用它）。 */
    fun isSelected(owner: JComponent): Boolean {
        val content = contents[owner] ?: return false
        return toolWindow?.contentManager?.selectedContent === content
    }

    /** 面板 → content 的顺序（测试与探针要按顺序点标签）。 */
    internal fun order(): List<JComponent> = contents.keys.toList()

    /**
     * 眼下开着的那些会话 —— 每个面板顶上那行胶囊就是照着它画的。
     *
     * 标题与状态**现问面板**（`ClaudePanel.tabTitle` / `tabState`），服务自己不存一份：
     * 存了就有两份真相，而"忙碌中"这种状态每秒都可能变。
     */
    internal fun tabs(): List<TabChip> {
        val selected = toolWindow?.contentManager?.selectedContent
        return contents.entries.map { (owner, content) ->
            val panel = owner as? ClaudePanel
            TabChip(
                owner = owner,
                title = panel?.tabTitle(),
                state = panel?.tabState() ?: TabState.Idle,
                current = content === selected,
                // 只剩一条时不给关：平台不会重建面板（见 refreshCloseable）
                canClose = contents.size > 1,
            )
        }
    }

    /**
     * 关掉某个标签。
     *
     * 走平台的 `removeContent`，于是**关闭闸照旧生效**（[closeGuard] 的
     * `contentRemoveQuery`：忙 / 有待决权限 / 正在启动时会先问一句）——
     * 我们的 ✕ 与平台的 ✕ 走的是同一条路，只是前者的样子归我们管。
     */
    internal fun closeTab(owner: JComponent) {
        if (contents.size <= 1) return
        val content = contents[owner] ?: return
        toolWindow?.contentManager?.removeContent(content, true)
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

    private fun addTab(): ClaudePanel {
        val panel = ClaudePanel(requireNotNull(project) { "SessionTabs 还没 attach 就要开标签" })
        val content = ContentFactory.getInstance().createContent(panel, NEW_TAB_TITLE, false)
        contents[panel] = content
        // 关闭时按 spec §7.4 的顺序清理进程树，否则 claude 会变孤儿继续消耗额度
        Disposer.register(content) { panel.dispose() }
        toolWindow?.contentManager?.addContent(content)
        refreshCloseable()
        notifyTabsChanged()
        return panel
    }

    /**
     * "至少留一个"不变量。
     *
     * 内容数 ≥ 2 时全部可关；只剩 1 个时那唯一一个不可关 —— 因为平台在
     * `createContentIfNeeded` 把 factory CAS 成 null 之后**再也不会**重建面板，
     * 全关掉等于把这个工具窗口废掉（本次项目生命周期内）。
     */
    private fun refreshCloseable() {
        val single = contents.size == 1
        contents.values.forEach { it.isCloseable = !single }
    }

    /**
     * 关闭的两道闸。
     *
     * 顺序要紧：**先判"项目在关"** —— `removeAllContents`（关项目 / `Close All`）
     * 是逐条 `removeContent(content, true)` 穿过这里来的，那时弹确认框既没意义也危险。
     */
    private val closeGuard = object : ContentManagerListener {

        override fun contentRemoveQuery(event: ContentManagerEvent) {
            val project = this@SessionTabs.project ?: return
            if (project.isDisposed) return

            // 最后一个：直接否决（见 refreshCloseable 那段平台约束）
            if (contents.size <= 1) {
                event.consume()
                return
            }
            // 批量关闭：第一次确认之后短时间内不再问
            val now = System.currentTimeMillis()
            if (now - lastBatchCloseAt < BATCH_CLOSE_WINDOW_MS) {
                lastBatchCloseAt = now
                return
            }

            val panel = event.content.component as? ClaudePanel ?: return
            if (!panel.hasLiveSessionState()) return

            // 重入闸：确认框是模态的，弹着的时候用户还能点另一个标签的叉 ——
            // 那时会在嵌套事件循环里再进来一次
            if (event.content.getUserData(CONFIRM_IN_FLIGHT) == true) return
            event.content.putUserData(CONFIRM_IN_FLIGHT, true)
            val ok = try {
                Messages.showYesNoDialog(
                    project,
                    "这个标签里的会话还在跑，关掉会中断它。",
                    "关闭「${event.content.displayName}」",
                    "关闭",
                    "取消",
                    UIUtil.getWarningIcon(),
                ) == Messages.YES
            } finally {
                event.content.putUserData(CONFIRM_IN_FLIGHT, null)
            }
            if (!ok) {
                event.consume()
                return
            }
            lastBatchCloseAt = System.currentTimeMillis()
        }

        override fun contentRemoved(event: ContentManagerEvent) {
            val panel = event.content.component as? ClaudePanel ?: return
            contents.remove(panel)
            refreshCloseable()
            notifyTabsChanged()
        }

        /**
         * 切了标签 —— 每个面板顶上那行胶囊都要重画（当前那颗的高亮跟着走）。
         *
         * 不挂这一条的话：切过去之后，**新**当前那颗上面没有强调色，旧的还亮着，
         * 而两个面板都不会自己知道。
         */
        override fun selectionChanged(event: ContentManagerEvent) {
            notifyTabsChanged()
        }
    }

    companion object {
        /** 还没起名的新标签叫什么。与面板里那个斜体「新会话」同一个词。 */
        internal const val NEW_TAB_TITLE = "新会话"

        /**
         * 一次批量关闭里，"用户点过确认"之后不再追问的时间窗。
         *
         * **只能靠冒烟验证**：静态分析证不了平台是不是在同一批事件里连发。
         */
        private const val BATCH_CLOSE_WINDOW_MS = 1_500L

        /** 确认框正在弹。挂在 content 上，见 [closeGuard] 里的重入闸。 */
        private val CONFIRM_IN_FLIGHT = com.intellij.openapi.util.Key<Boolean>("ccoder.confirmCloseInFlight")

        fun getInstance(project: Project): SessionTabs = project.getService(SessionTabs::class.java)
    }
}
