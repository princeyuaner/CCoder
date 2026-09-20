package com.ccoder.settings

import com.ccoder.sidecar.DepStatus
import com.ccoder.sidecar.Os
import com.ccoder.sidecar.RuntimeDep
import com.ccoder.sidecar.hostOs
import com.ccoder.sidecar.majorOf
import com.ccoder.text.CcoderText
import com.ccoder.ui.copyToClipboard
import com.ccoder.ui.dangerColor
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 环境页顶部那块「运行依赖」：两个依赖各一行状态 + 安装动作 + 安装输出。
 *
 * 设计稿 `docs/superpowers/specs/2026-09-17-runtime-deps-design.md` §3.7。
 *
 * ```
 * 运行依赖                              重新检测
 * Node.js    可用 · v24.13.1
 * claude     未找到                安装 claude
 * [输出区：安装中 / 装完才有，固定高，等宽，贴底]
 * ```
 *
 * ## 它自己不干活
 *
 * 检测与安装在 [RuntimeDepsService] 里（项目级，活得比这个对话框长）；
 * 这里只做三件事：把服务的状态画出来、把点击翻成一个 [InstallPlan]、
 * 需要确认时问一句。**页里不开线程** —— 测试 JVM 里 `ApplicationManager` 是 null。
 */

/** 这块的标题。 */
internal val RUNTIME_DEPS_TITLE: String get() = CcoderText.text("settings.deps.title")

/** 「重新检测」那颗动作的字。用例按它找控件。 */
internal val RECHECK_LABEL: String get() = CcoderText.text("settings.deps.recheck")

/** 安装进行中，那一行的动作变成「取消」。与对话框那颗取消是**同一个词**（`common.cancel`）。 */
internal val CANCEL_INSTALL_LABEL: String get() = CcoderText.text("common.cancel")

/**
 * 这台机器上装不了（没有 npm/winget/brew）时那颗动作的字。
 *
 * **必须换一个说法**：点下去是"复制命令 + 打开官方页"，还写「安装」就是在骗人。
 */
internal val MANUAL_LABEL: String get() = CcoderText.text("settings.deps.manualAction")

internal val CHECKING_TEXT: String get() = CcoderText.text("settings.deps.checking")

/** 输出区第一行：这一趟在跑什么。带命令本身 —— 用户核对的就是它。 */
internal val INSTALLING_TEXT: String get() = CcoderText.text("settings.deps.installing")
internal val UNKNOWN_TEXT: String get() = CcoderText.text("settings.deps.unknown")
internal val NOT_FOUND_TEXT: String get() = CcoderText.text("settings.deps.notFound")
internal val BROKEN_TEXT: String get() = CcoderText.text("settings.deps.broken")

/** 装完之后给一句结论 —— 光有一堆输出，用户看不出到底成没成。 */
internal val INSTALL_SUCCEEDED_TEXT: String get() = CcoderText.text("settings.deps.installOk")
internal val INSTALL_FAILED_TEXT: String get() = CcoderText.text("settings.deps.installFailed")
internal val INSTALL_CANCELLED_TEXT: String get() = CcoderText.text("settings.deps.installCancelled")

/** 名字那一列的宽度。**两行的状态文字必须从同一条竖线开始** —— 名字长度不同会错开。 */
private const val NAME_WIDTH = 96

/** 输出区的高度。 */
private const val LOG_HEIGHT = 96

/**
 * 界面上那颗动作写什么字。
 *
 * 动词与依赖名之间**留一个空格** —— 本仓中文文案里拉丁词两侧都留
 * （`添加到 CCoder 聊天框`、`＋ 添加一行`），「安装Node.js」那种挤在一起的样子不属于这里。
 */
internal fun installLabel(dep: RuntimeDep, action: InstallAction): String = "${action.labelVerb} ${dep.label}"

/**
 * 状态那一格写什么。纯函数：用例直接打，页面只管画。
 *
 * 四档故障**各说各的**（没找到 / 找到了但跑不起来 / 版本过低 / 状态未知）：
 * 合成一句"不可用"就等于把用户唯一能自己排查的线索抹掉了。
 */
internal fun statusText(status: DepStatus): String = when (status) {
    DepStatus.Unknown -> UNKNOWN_TEXT
    DepStatus.Checking -> CHECKING_TEXT
    is DepStatus.Ok -> status.version?.let { CcoderText.text("settings.deps.okVersion", it) }
        ?: CcoderText.text("settings.deps.ok")

    is DepStatus.TooOld ->
        CcoderText.text("settings.deps.tooOld", status.version, status.minMajor)

    DepStatus.NotFound -> NOT_FOUND_TEXT
    is DepStatus.Broken -> BROKEN_TEXT
}

/** 这个状态下该给什么动作。没问题的依赖不给动作（否则界面上全是按钮）。 */
internal fun installActionFor(status: DepStatus): InstallAction? = when (status) {
    DepStatus.NotFound, is DepStatus.Broken -> InstallAction.INSTALL
    is DepStatus.TooOld -> InstallAction.UPGRADE
    else -> null
}

/**
 * 「运行依赖」那块的**外壳依赖**：默认全是生产实现，用例与渲染探针换掉它。
 *
 * 为什么不把这些直接摊成页面/对话框的构造参数：那要顺着
 * `SettingsDialog → EnvironmentSettingsPage → RuntimeDepsSection` 一路穿四个参数，
 * 而它们只对最里面那一层有意义。收成一个对象，穿一路就够了。
 */
internal class DepsUi(
    val os: Os = hostOs(),
    /** 工具解析（winget / brew / npm）。默认按 [os] 走真机。 */
    val tools: (String?) -> ToolSet = { nodeDir -> toolSet(os = os, nodeDir = nodeDir) },
    /** 确认框。null = 用生产那颗 [confirmInstall]（它要 project，所以在页面里兜底）。 */
    val confirm: ((InstallPlan) -> Boolean)? = null,
    val copy: (String) -> Unit = ::copyToClipboard,
    val browse: (String) -> Unit = { BrowserUtil.browse(it) },
)

internal class RuntimeDepsSection(
    private val deps: RuntimeDepsService,
    /**
     * 确认框。**返回 false 就什么都不做** —— 它必须真的参与计算，
     * 不能是个安慰剂（见 `RuntimeDepsServiceTest` 里那条"确认没同意就不该起进程"）。
     */
    private val confirm: (InstallPlan) -> Boolean,
    /** 兜底路径：把命令（或官方页地址）放进剪贴板。 */
    private val copy: (String) -> Unit,
    /** 兜底路径：打开官方安装页。 */
    private val browse: (String) -> Unit,
    private val os: Os = hostOs(),
    /** 工具解析（winget / brew / npm）。可注入：用例不真装包管理器。 */
    private val tools: (String?) -> ToolSet = { nodeDir -> toolSet(os = os, nodeDir = nodeDir) },
    /**
     * 这一段能用的内容宽度。默认是页宽那一档（[PAGE_CONTENT_WIDTH]）。
     *
     * 2026-09-20 起它被放进一张卡片里，卡内两侧各有内边距 —— 页面按卡内宽度
     * （[CARD_CONTENT_WIDTH]）喂进来。**折行的说明与定高的输出区都按它算**：
     * 喂宽了会被 BoxLayout 夹回去（高度却是按更宽的量出来的），症状是最后一行被裁。
     */
    private val contentWidth: Int = PAGE_CONTENT_WIDTH,
) {

    private var built: JComponent? = null

    /** `internal` 是给用例量的：两行状态文字必须从同一条竖线开始。 */
    internal val statusLabels = LinkedHashMap<RuntimeDep, JBLabel>()

    /** `internal` 是给用例点的（按行取那颗动作）。 */
    internal val actionLabels = LinkedHashMap<RuntimeDep, JBLabel>()

    private var recheckLabel: JBLabel? = null

    /** 兜底路径要说的那句话（"npm 装 claude 需要 Node.js 22+"之类）。 */
    private var hint: String? = null

    /**
     * 输出区。
     *
     * **不用 `wrappedHint`**：那个是"量一次高度、设 `maximumSize`"的静态件，
     * 每追加一行都要重量高。这里建一次、之后只改 `Document`。
     */
    private val logArea = JBTextArea().apply {
        isEditable = false
        isFocusable = false
        lineWrap = false
        // 显式设字体：无父组件时 getFont() 是 null（同 IndicatorView 那条教训）
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
        border = JBUI.Borders.empty(4, 6)
    }

    internal val logScroll = JBScrollPane(logArea).apply {
        border = JBUI.Borders.empty()
        alignmentX = Component.LEFT_ALIGNMENT
        preferredSize = Dimension(JBUI.scale(contentWidth), JBUI.scale(LOG_HEIGHT))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(LOG_HEIGHT))
    }

    /**
     * 输出区外面那层壳。**不能把滚动条直接塞进列里**（2026-09-17 出图看出来的）：
     * 列是个 `BoxLayout`，而滚动条的**最小宽度只有十几像素、最大宽度是无穷** ——
     * 列一挤，它就成了那个被压扁的：实测它被挤成半宽（328/656）**还跑到了右半边**，
     * 压在下面的表上。套一层壳之后，"单子件的 BoxLayout 给满宽"这条规律就生效了。
     * `tableBox` 一直是这个写法，图里那两张表满宽就是证据。
     */
    private val logBox = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
        add(logScroll)
        // 高度必须**等于**定死的那个：给 Int.MAX_VALUE 它会变成页里的弹簧
        // （tableBox / modelListBox 各踩过一次）
        preferredSize = Dimension(JBUI.scale(contentWidth), JBUI.scale(LOG_HEIGHT))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(LOG_HEIGHT))
    }

    /**
     * 提示区。复用 [wrappedHint] 的样式（同一个厂家），只是要能改文本、要重量高度 ——
     * 它是 `JTextArea`，转一下就有这两个能力了。
     */
    private val hintArea = wrappedHint("", contentWidth) as JBTextArea

    /** 服务每次变化都会喊它（EDT 上）。 */
    private val onServiceChanged: () -> Unit = { refresh() }

    fun component(): JComponent = built ?: build().also {
        built = it
        deps.addListener(onServiceChanged)
        refresh()
    }

    /** 对话框关掉时退订。不退订就是每开一次设置漏一个监听器（同 [SettingsPage.dispose]）。 */
    fun dispose() {
        deps.removeListener(onServiceChanged)
    }

    /** 每次打开设置重查一遍。服务里那份缓存会先画出来，所以不会白屏。 */
    fun recheck() {
        deps.refreshAll()
    }

    private fun build(): JComponent {
        // 下沿原来画着"与「额外目录」之间的那条线"（2026-09-16），现在这一段被收进
        // 自己的卡片里：线的位置改由**卡片自己的边**担当，那条 line 挪去标题行下面 ——
        // 卡片里那才是"头"与"身"的分界
        val column = settingsColumn().apply {
            border = JBUI.Borders.emptyBottom(6)
        }
        column.add(headerRow())
        RuntimeDep.entries.forEach { column.add(depRow(it)) }
        column.add(hintArea)
        column.add(logBox)
        return column
    }

    private fun headerRow(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        // **整列的子件必须用同一个 alignmentX**（这里一律 LEFT）：混着 0.5 与 0.0 时，
        // BoxLayout 会把 0.0 的那些挤成半宽推到右半边（2026-09-17 实测，见 logBox 那条）
        alignmentX = Component.LEFT_ALIGNMENT
        // 标题下面那条线：这一段被收进卡片之后，它就是"卡头"与"卡身"的分界
        border = BorderFactory.createCompoundBorder(hairlineBottom(), JBUI.Borders.emptyBottom(8))
        add(
            JBLabel(RUNTIME_DEPS_TITLE).apply { foreground = UIUtil.getLabelForeground() },
            BorderLayout.WEST,
        )
        val recheck = actionLabel(RECHECK_LABEL) {
            // 灰掉的那颗照样收得到 mouseClicked —— 处理函数里再判一次
            if (deps.installState() !is InstallState.Running) deps.refreshAll()
        }
        recheckLabel = recheck
        add(recheck, BorderLayout.EAST)
    }

    private fun depRow(dep: RuntimeDep): JComponent {
        val status = JBLabel(statusText(deps.statusOf(dep)))
        statusLabels[dep] = status

        val action = actionLabel("") { onRowAction(dep) }
        actionLabels[dep] = action

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    // 固定宽度：两行的状态文字因此从同一条竖线开始
                    preferredSize = Dimension(JBUI.scale(NAME_WIDTH), 0)
                    add(JBLabel(dep.label).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.WEST)
                },
                BorderLayout.WEST,
            )
            add(status, BorderLayout.CENTER)
            add(action, BorderLayout.EAST)
        }
    }

    /**
     * 点那一行的动作：安装中就是取消，否则出一个计划 —— 能代跑就先问一句，
     * 不能代跑就把命令和官方页交到用户手上。
     */
    private fun onRowAction(dep: RuntimeDep) {
        val running = deps.installState() as? InstallState.Running
        if (running != null) {
            if (running.dep == dep) deps.cancelInstall()
            return
        }

        val plan = planFor(dep) ?: return

        when (plan.route) {
            InstallRoute.RUN -> if (confirm(plan)) {
                hint = plan.note.ifBlank { null }
                deps.startInstall(plan)
            }

            InstallRoute.MANUAL -> {
                copy(plan.copyText)
                plan.url?.let(browse)
                hint = plan.note
            }
        }
        refresh()
    }

    /**
     * 这一行现在能做什么。**纯计算**（除了问一遍工具在不在）：没问题的依赖返回 null。
     *
     * 界面上那颗动作的字与点下去的后果都由它决定 —— 一处决定，
     * 免得出现"按钮说能装、点下去变成复制命令"这种自相矛盾。
     */
    private fun planFor(dep: RuntimeDep, toolSet: ToolSet = tools(nodeDir())): InstallPlan? {
        val status = deps.statusOf(dep)
        val action = installActionFor(status) ?: return null
        return installPlan(dep, status, toolSet, os, nodeDir(), nodeMajor())
    }

    /** node 的落点目录 —— npm 就在它旁边（装 claude 要用）。 */
    private fun nodeDir(): String? =
        (deps.statusOf(RuntimeDep.NODE) as? DepStatus.Ok)?.path?.let { Path.of(it).parent?.toString() }

    private fun nodeMajor(): Int? =
        (deps.statusOf(RuntimeDep.NODE) as? DepStatus.Ok)?.version?.let { majorOf(it) }

    /**
     * 全量重画。状态、动作、输出、提示**一处决定** ——
     * 各画各的迟早出现"按钮说能装、状态说没装"这种自相矛盾。
     */
    private fun refresh() {
        val running = deps.installState() as? InstallState.Running
        // 工具只解析一次，两行共用（每行各来一遍就是白白多扫几遍已知目录）
        val toolSet = tools(nodeDir())

        RuntimeDep.entries.forEach { dep ->
            val status = deps.statusOf(dep)
            statusLabels[dep]?.apply {
                text = statusText(status)
                foreground = when (status) {
                    is DepStatus.Ok -> UIUtil.getLabelForeground()
                    DepStatus.Checking, DepStatus.Unknown -> UIUtil.getInactiveTextColor()
                    else -> dangerColor()
                }
                // 路径塞不下但**必须有地方看见**（同 MCP 状态行那条）
                toolTipText = (status as? DepStatus.Ok)?.path
                    ?: (status as? DepStatus.TooOld)?.path
                    ?: (status as? DepStatus.Broken)?.path
            }

            val action = actionLabels[dep] ?: return@forEach
            when {
                running != null && running.dep == dep -> {
                    action.text = CANCEL_INSTALL_LABEL
                    setActionEnabled(action, true)
                }

                running != null -> {
                    action.text = ""
                    setActionEnabled(action, false)
                }

                else -> {
                    val plan = planFor(dep, toolSet)
                    action.text = when (plan?.route) {
                        InstallRoute.RUN -> installLabel(dep, plan.action)
                        InstallRoute.MANUAL -> MANUAL_LABEL
                        null -> ""
                    }
                    setActionEnabled(action, plan != null)
                }
            }
        }

        recheckLabel?.let { setActionEnabled(it, running == null && deps.installState() !is InstallState.Running) }
        refreshHint()
        refreshLog()
        built?.let {
            it.revalidate()
            it.repaint()
        }
    }

    private fun refreshHint() {
        val text = hint.orEmpty()
        hintArea.text = text
        hintArea.isVisible = text.isNotEmpty()
        // 折行之后的高度只能自己量（同 wrappedHint 那条）
        hintArea.setSize(JBUI.scale(contentWidth), Int.MAX_VALUE)
        hintArea.maximumSize = Dimension(JBUI.scale(contentWidth), hintArea.preferredSize.height)
    }

    private fun refreshLog() {
        val state = deps.installState()
        val lines = when (state) {
            is InstallState.Running -> state.log
            is InstallState.Finished -> state.log
            InstallState.Idle -> emptyList()
        }
        val head = when {
            state is InstallState.Running -> "$INSTALLING_TEXT（${state.plan.command?.display().orEmpty()}）"
            state is InstallState.Finished -> when (state.result) {
                InstallResult.SUCCEEDED -> INSTALL_SUCCEEDED_TEXT
                InstallResult.FAILED -> INSTALL_FAILED_TEXT
                InstallResult.CANCELLED -> INSTALL_CANCELLED_TEXT
            }

            else -> ""
        }
        val text = (listOf(head) + lines).filter { it.isNotEmpty() }.joinToString("\n")

        if (logArea.text != text) {
            logArea.text = text
            // 贴底：安装器最后一句往往就是结论，别让用户自己去滚
            logArea.caretPosition = logArea.document.length
        }
        if (logBox.isVisible != text.isNotEmpty()) {
            logBox.isVisible = text.isNotEmpty()
        }
    }

    /**
     * 一颗动作的可用态。
     *
     * 不能点的动作必须**看起来**不能点（2026-09-15 那两轮"看得见、点不动"的教训），
     * 而且灰掉之后处理函数里还得再判一次 —— 灰字照样收得到 `mouseClicked`。
     */
    private fun setActionEnabled(label: JBLabel, enabled: Boolean) {
        label.foreground = if (enabled) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
        label.cursor = Cursor.getPredefinedCursor(if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
    }
}

/**
 * 生产用的确认框：**即将执行的那条命令单独摆一行**，用户核对的就应该是它。
 *
 * 三条必须写进去的东西：命令本身、会不会弹 UAC、装完会做什么。
 * 少写最后一条，用户就不知道"装完还要不要再点一次"。
 */
internal fun confirmInstall(project: Project?, plan: InstallPlan): Boolean {
    val command = plan.command ?: return false
    return Messages.showYesNoDialog(
        project,
        buildString {
            append(CcoderText.text("settings.deps.confirmIntro"))
            append("\n\n    ")
            append(command.display())
            append("\n\n")
            if (plan.note.isNotBlank()) append(plan.note).append("\n\n")
            append(CcoderText.text("settings.deps.confirmAfter"))
        },
        // 标题与那颗动作**同一套词**：动词 + 空格 + 依赖名（`installLabel` 的写法）。
        // 原文案是"动词直接接依赖名"（中文里没有空格），英文那样拼就是 Installclaude
        CcoderText.text("settings.deps.confirmTitle", plan.action.labelVerb, plan.dep.label),
        plan.action.labelVerb,
        CcoderText.text("common.cancel"),
        null,
    ) == Messages.YES
}
