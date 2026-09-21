package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.intellij.openapi.project.Project
import java.nio.file.Path
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.BorderFactory
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
        mcpStatus = McpStatus.getInstance(project),
        deps = RuntimeDepsService.getInstance(project),
        language = UiLanguageSettings.getInstance(),
        prefs = UiPreferences.getInstance(),
    ).show()
}

/**
 * 设置对话框（骨架是 2026-09-15 的方案 C；2026-09-20 按用户选的**方案 B** 改版：
 * 左栏带图标与胶囊选中态、页里改成卡片分区 —— 选型图在 `docs/design/settings-v3.html`）。
 * 八页签：模型 / 预置 / 通用 / 权限 / 环境 / MCP / hooks / 群交流。
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
    private val mcpStatus: McpStatus,
    /**
     * 运行依赖的检测结果与安装进度。**不给默认值**：默认值只能写成
     * `RuntimeDepsService.getInstance(project)`，而用例里没有平台服务（会抛），
     * 更糟的是任何忘了注入的用例都会去**真跑** `claude --version` —— 又慢又不确定。
     * 签名上多一个必填参数，换的是用例的确定性。
     */
    private val deps: RuntimeDepsService,
    /**
     * 界面语言（APP 级）。**不给默认值**，同 [deps] 的理由 ——
     * 默认值只能写成 `UiLanguageSettings.getInstance()`，而它在纯 JVM 的探针里会抛。
     */
    private val language: UiLanguageSettings,
    /**
     * 界面偏好（APP 级：今天只有「思考折叠」那一项）。**不给默认值**，同 [language] 的理由
     * —— 默认值只能写成 `UiPreferences.getInstance()`，而它在纯 JVM 的探针里会抛。
     */
    private val prefs: UiPreferences,
    /**
     * 「运行依赖」那块的确认框/剪贴板/浏览器外壳。生产用默认值；渲染探针换掉它
     * （要画"这台机器上装不了"那一屏就得把平台与工具一起换掉）。
     */
    private val depsUi: DepsUi = DepsUi(),
    /**
     * 项目根。默认取 [project] 的 basePath。
     *
     * 可注入是为了让渲染探针指定一个临时目录 —— 它的假 Project 是个
     * 什么都返回 null 的代理，不给这一项就画不出"能编辑"的那一屏。
     */
    private val baseDir: Path? = project?.basePath?.let { Path.of(it) },
) : DialogWrapper(project) {

    /**
     * 八页，连同左栏那枚图标。**页序 = 导航序** —— 图标与页成对写在一处，
     * 免得日后插一页时导航上的小图整体错位一格（那只是"图标配错了"，不报错）。
     *
     * `internal val pages` 是给用例逐页点的（页多了漏挂监听器就看不出来）。
     */
    private val pageSpecs: List<Pair<SettingsPage, NavIcon>> = run {
        val models = ModelProfilesPage(settings, profiles)
        listOf(
            models to NavIcon.Models,
            PromptPresetsPage(presets) to NavIcon.Presets,
            GeneralSettingsPage(project, settings, language, prefs) to NavIcon.General,
            PermissionSettingsPage(settings) to NavIcon.Permission,
            // 环境页改一个键，模型页那条冲突警告要跟着重算 ——
            // 不然"刚加完键、切过去却没提示"看起来就像那个提示坏了
            EnvironmentSettingsPage(project, settings, deps, depsUi) { models.refreshConflictWarning() } to
                NavIcon.Environment,
            // 项目根给 MCP 页写 `.mcp.json` 用；拿不到就只读（不猜一个路径去写）
            McpSettingsPage(baseDir, mcpStatus) to NavIcon.Mcp,
            HooksSettingsPage(baseDir) to NavIcon.Hooks,
            // 一页只放一张二维码（2026-09-17）。它不读写任何配置，放最后 ——
            // 前面七页是"把插件配成你要的样子"，这一页是"找人"
            GroupChatSettingsPage() to NavIcon.GroupChat,
        )
    }

    internal val pages: List<SettingsPage> = pageSpecs.map { it.first }

    private var current: SettingsPage = pages.first()

    override fun dispose() {
        // 先让各页退订，再交给平台 —— 反过来的话，退订时可能碰到的组件已经没了
        pages.forEach { it.dispose() }
        super.dispose()
    }

    /**
     * 页宿主。切页只换它里面的东西，四个组件本身缓存着不重建。
     *
     * `internal` 是给用例量的：那一栏的实际宽度必须等于 [PAGE_WIDTH]，
     * 而这个数算错了界面上只会"悄悄挤一点"（2026-09-15 就是这么漏过去的）。
     */
    internal val pageHost = JPanel(BorderLayout())

    private val navItems = LinkedHashMap<SettingsPage, SettingsNavItem>()

    /**
     * 左栏那一条。
     *
     * `internal` 是给用例与探针的：点页签时**只在这一条里**找标签 —— 页内一旦出现
     * 同文标签（卡头、"权限模式"那类），满框搜索会静默点到没有监听器的那个，
     * 症状是"探针出的图全变成第一页"，而单测全绿。
     */
    internal val tabStrip: JComponent = buildTabsColumn()

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
    private val closeAction =
        object : DialogWrapper.DialogWrapperAction(CcoderText.text("settings.dialog.close")) {
            override fun doAction(e: ActionEvent) {
                close(OK_EXIT_CODE)
            }
        }

    init {
        title = CcoderText.text("settings.dialog.title")
        setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
        init()
        // 每次打开都是一次新的构造，所以这一遍等价于"每次 show 之前"。
        // 必须做：「权限模式」「思考深度」会被输入框左下角那两个标签反写
        // （ClaudePanel 里的 pickPermissionMode / pickEffort），不重读就是陈旧值。
        pages.forEach { it.reload() }
        applySelection()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        // **取那一个已经建好的实例**，不要再调一次 buildTabsColumn()：那会造出第二条
        // 左栏（用例与探针手里的 tabStrip 成了没人挂上去的副本，而 navItems 被后来
        // 那一套覆盖）—— 症状是"点页签没反应"，但两边都"看起来在工作"
        add(tabStrip, BorderLayout.WEST)
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
            // 页脚上方那条线（设计稿 `.dlg-ft { border-top }`）：不画的话，
            // 「改动即时保存」与「关闭」是两颗浮在空白里的字，不像一条页脚
            border = hairlineTop()
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
     * 左栏页签：图标 + 标题，选中是一枚实心强调色胶囊（2026-09-20 方案 B）。
     *
     * 手写而不是用 `JBTabbedPane` / `TabbedPaneWrapper`：平台那两个要 `Disposable`
     * 与全局 `UISettings`，而本仓库的测试跑在纯 JVM 里（`ApplicationManager` 是 null）。
     * 全仓 `JBTabs` 一族零命中 —— 这一栏从 2026-09-13 起就是手写的。
     */
    private fun buildTabsColumn(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        // 页签栏右侧那条线（设计稿 `.tabs-v { border-right }`）：有它才看得出
        // 左栏是导航、右面是内容，而不是七个字浮在整张纸上
        border = BorderFactory.createCompoundBorder(hairlineRight(), JBUI.Borders.empty(10, 8))
        preferredSize = Dimension(JBUI.scale(TABS_WIDTH), 0)
        pageSpecs.forEach { (page, icon) ->
            val item = SettingsNavItem(page, icon, page.title) { select(page) }
            item.label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // 加完子件之后再量（同 ModelProfiles 里 listRow 的注释）：不设这一条
            // 胶囊底色只裹住图标与文字那一段，而不是整行那么宽
            item.maximumSize = Dimension(Int.MAX_VALUE, item.preferredSize.height)
            navItems[page] = item
            add(item)
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
        navItems.forEach { (page, item) -> item.setSelected(page === current) }
        pageHost.removeAll()
        pageHost.add(current.component(), BorderLayout.CENTER)
        pageHost.revalidate()
        pageHost.repaint()
    }
}

/** 底部那句话。原来那句只解释"取消不回滚"，现在没有取消了，只需说清生效时机。 */
internal val FOOTER_HINT: String get() = CcoderText.text("settings.dialog.footerHint")
