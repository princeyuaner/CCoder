package com.ccoder.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 对话框的几何。三处尺寸都从这两个数推出来，别在别处写死。 */
internal const val DIALOG_WIDTH = 860
internal const val DIALOG_HEIGHT = 560

/** 左边页签栏的宽度。 */
internal const val TABS_WIDTH = 140

/**
 * 对话框内容区左右各让出的宽度 —— **平台的边框，不是我们加的**。
 *
 * 2026-09-15 量出来的：`contentPane` 860 宽，里面那层实际只有 836（`x=12`）。
 * 一开始漏算了它，于是 [PAGE_WIDTH] 多了 24，页里的表单栏（固定宽）压过列表栏 24px ——
 * 症状是列表右边那列的「使用中」被盖住。
 *
 * 这个数被 `SettingsDialogTest` 里一条用例钉着：平台哪天改了边框，那条会红，
 * 而不是界面悄悄变得挤一点。
 */
internal const val DIALOG_CONTENT_INSET = 12

/** 页宿主实际能拿到的宽度 = 对话框 − 两侧边框 − 页签栏。各页按它分配自己那几栏。 */
internal const val PAGE_WIDTH = DIALOG_WIDTH - 2 * DIALOG_CONTENT_INSET - TABS_WIDTH

/** 打开设置对话框，停在「模型」页。 */
fun showSettingsDialog(project: Project) {
    SettingsDialog(
        project = project,
        settings = ClaudeSettings.getInstance(project),
        profiles = ModelProfiles.getInstance(),
        presets = PromptPresets.getInstance(),
    ).show()
}

/**
 * 设置对话框（设计稿方案 C，2026-09-15）。五页签：模型 / 预置 / 通用 / 权限 / 环境。
 *
 * ## 从三栏到四页签
 *
 * 原先是「左栏 132px 假页签 + 模型列表 + 编辑表单」。左栏是 2026-09-13 给
 * 「通用 / 权限 / 关于」留的位置，而那次只做了模型页 —— 于是它既不导航也不装饰。
 * 这次把 IDE 里那页（`Settings → Tools → CCoder`）的三项搬进来，左栏兑现成真页签，
 * **两处设置合成一处**（IDE 那一页连同它的注册一起删掉了）。
 *
 * ## 改动即时保存
 *
 * 五页统一：控件一变就写服务，**没有待保存副本，也没有回滚**。所以底部只有
 * 一颗「关闭」——原来的「取消」在这页上什么都不回滚（它旁边那句「改动即时保存」
 * 就是为了解释这件事才写的），换成一颗按钮之后那句解释也随之退休。
 *
 * ## 服务由调用方注入
 *
 * 不在里面 `getInstance()`：探针要在没有 Application 服务的纯 JVM 里跑
 * （同 [ClaudeSettings.toStartParams] 的理由）。拿服务是入口 [showSettingsDialog] 的事。
 *
 * ## 这个对话框只读写配置，不碰会话
 *
 * 「权限模式」「思考深度」改了之后要不要作用到**正在跑**的会话，由 [ClaudePanel]
 * 在 `show()` 返回后自己决定（见 `ui/SettingsApply.kt`）。设置界面自己去动会话
 * 会把两处的生命周期缠在一起 —— 那条分界从模型配置那一版起就没动过。
 */
internal class SettingsDialog(
    private val project: Project?,
    private val settings: ClaudeSettings,
    private val profiles: ModelProfiles,
    private val presets: PromptPresets,
) : DialogWrapper(project) {

    /** 五页。`internal` 是给用例逐页点的（页多了漏挂监听器就看不出来）。 */
    internal val pages: List<SettingsPage> = run {
        val models = ModelProfilesPage(settings, profiles)
        listOf(
            models,
            PromptPresetsPage(presets),
            GeneralSettingsPage(project, settings),
            PermissionSettingsPage(settings),
            // 环境页改一个键，模型页那条冲突警告要跟着重算 ——
            // 不然"刚加完键、切过去却没提示"看起来就像那个提示坏了
            EnvironmentSettingsPage(settings) { models.refreshConflictWarning() },
        )
    }

    private var current: SettingsPage = pages.first()

    /**
     * 页宿主。切页只换它里面的东西，四个组件本身缓存着不重建。
     *
     * `internal` 是给用例量的：那一栏的实际宽度必须等于 [PAGE_WIDTH]，
     * 而这个数算错了界面上只会"悄悄挤一点"（2026-09-15 就是这么漏过去的）。
     */
    internal val pageHost = JPanel(BorderLayout())

    private val tabLabels = LinkedHashMap<SettingsPage, JBLabel>()

    /**
     * 底部那颗「关闭」。
     *
     * **必须声明在 [init] 之前**：Kotlin 的属性初始化器与 `init` 块按书写顺序执行，
     * 而 `init()`（平台那个）里就会走到 [createSouthPanel] → [createActions]。
     * 写在后面的话那一刻它还是 null —— 数组里塞进一个 null，平台不报错，
     * 结果是**底部一颗按钮都不画**（2026-09-15 出图时看出来的）。
     *
     * **刻意不给它 `DEFAULT_ACTION` 这个名字**：给了平台就把 rootPane 的默认按钮
     * 指到它，于是回车会关掉整个设置框 —— 而环境页的表格里回车是"提交这一格"，
     * 那一下会顺手把框关了（同 `PermissionDialog` 那条"回车不批准"的道理）。
     */
    private val closeAction = object : DialogWrapper.DialogWrapperAction("关闭") {
        override fun doAction(e: ActionEvent) {
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = "设置"
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
        init()
        // 每次打开都是一次新的构造，所以这一遍等价于"每次 show 之前"。
        // 必须做：「权限模式」「思考深度」会被输入框左下角那两个标签反写
        // （ClaudePanel 里的 pickPermissionMode / pickEffort），不重读就是陈旧值。
        pages.forEach { it.reload() }
        applySelection()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(tabsColumn(), BorderLayout.WEST)
        add(pageHost, BorderLayout.CENTER)
    }

    /**
     * 底部左侧那句话。
     *
     * 必须**贴着按钮**（放进页里就没这个作用了）—— 它回答的是"我改的东西什么时候生效"，
     * 而人是在按「关闭」之前想这件事的。
     */
    override fun createSouthPanel(): JComponent {
        val base = super.createSouthPanel()
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JBLabel(FOOTER_HINT).apply { foreground = UIUtil.getInactiveTextColor() },
                BorderLayout.WEST,
            )
            add(base, BorderLayout.CENTER)
        }
    }

    /** 只留一颗「关闭」（见 [closeAction] 上那两条说明）。 */
    override fun createActions(): Array<Action> = arrayOf(closeAction)

    /**
     * 左栏页签。
     *
     * 手写而不是用 `JBTabbedPane` / `TabbedPaneWrapper`：平台那两个要 `Disposable`
     * 与全局 `UISettings`，而本仓库的测试跑在纯 JVM 里（`ApplicationManager` 是 null）。
     * 全仓 `JBTabs` 一族零命中 —— 这一栏从 2026-09-13 起就是手写的。
     */
    private fun tabsColumn(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10, 8)
        preferredSize = Dimension(JBUI.scale(TABS_WIDTH), 0)
        pages.forEach { page ->
            val label = JBLabel(page.title).apply {
                border = JBUI.Borders.empty(6, 9)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = select(page)
                })
            }
            // 加完子件之后再量（同 ModelProfiles 里 listRow 的注释）：不设这一条
            // 选中底色只裹住文字那一段，而不是整个页签那么宽
            label.maximumSize = Dimension(Int.MAX_VALUE, label.preferredSize.height)
            tabLabels[page] = label
            add(label)
        }
        add(Box.createVerticalGlue())
    }

    private fun select(page: SettingsPage) {
        if (current === page) return
        current = page
        applySelection()
    }

    /**
     * 选中态与显示内容**一处决定**（同 `refreshModeLabel` / `refreshEffortLabel` 的规矩）。
     *
     * 切页**不重建**组件，只 `removeAll` + `add`：重建会丢掉页上三类中间状态 ——
     * 那个刻意不落库的空模型行、输入框的光标与选区、密钥的 `echoChar`（明文/打码）。
     */
    private fun applySelection() {
        tabLabels.forEach { (page, label) ->
            val on = page === current
            label.isOpaque = on
            label.background = if (on) UIUtil.getListSelectionBackground(true) else UIUtil.getPanelBackground()
            label.foreground = if (on) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
        }
        pageHost.removeAll()
        pageHost.add(current.component(), BorderLayout.CENTER)
        pageHost.revalidate()
        pageHost.repaint()
    }
}

/** 底部那句话。原来那句只解释"取消不回滚"，现在没有取消了，只需说清生效时机。 */
internal const val FOOTER_HINT = "改动即时保存"
