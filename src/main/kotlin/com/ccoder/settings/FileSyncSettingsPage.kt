package com.ccoder.settings

import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncService
import com.ccoder.sync.SyncSettings
import com.ccoder.sync.SyncStatus
import com.ccoder.sync.SyncTexts
import com.ccoder.text.CcoderText
import com.ccoder.ui.localizedText
import com.ccoder.ui.localizedTooltip
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** 列表栏宽度。它放 CENTER，所以这是"理想宽度" —— 页窄了它会自己收。 */
internal const val SYNC_LIST_WIDTH = 240

/** 表单栏宽度。放 EAST，是确定值。 */
internal const val SYNC_FORM_WIDTH = PAGE_WIDTH - SYNC_LIST_WIDTH

private const val SYNC_FORM_CONTENT_WIDTH =
    SYNC_FORM_WIDTH - 2 * CARD_INSET - CARD_BORDER_W - 2 * CARD_PAD_H

/**
 * 日志框的高度（约三行）。
 *
 * 它是"我改的东西怎么没同步"的答案所在，但**页面在 560px 高的对话框里放不下更多了** ——
 * `settingsCardColumn` 不管溢出（纯 BoxLayout），内容超了就是被切掉。
 * 第一版给 96px，出图时表单最后两行整行不见了。
 */
private const val LOG_BOX_HEIGHT = 64

/**
 * 同步页（第 9 页）：**左栏看状态、右栏改配置**。
 *
 * ## 两栏的分工
 *
 * - 左栏：**只读**。当前状态、上一轮的计数、需要注意的东西（失败/提醒）、最近日志。
 *   数据来自 [SyncStatus]（同步跑在别的线程上，界面只能订阅一份快照）
 * - 右栏：**能改**。开关、两端目录、范围、时长、镜像删除。写进 [SyncSettings]，
 *   服务会自己重建（见 `SyncService.reconcile`）
 *
 * ## 一处必须小心的地方：左栏刷新**不能碰右栏**
 *
 * 服务每跑完一轮就会推一次状态，而那条路直接回到 EDT —— 如果状态刷新顺手重建了整个
 * 表单，用户正在敲的输入框会被销毁重建（**文本、光标位置、输入法状态全没**）。
 * 所以 [refreshStatus] 只碰左栏那几个标签；右栏的控件建一次、只在 [reload] 里填值。
 *
 * 同理，排除项那个列表**只在增删时重建**，而且重建前先把正在编辑的空行从本地副本里
 * 收回来 —— 见 [rebuildExcludes]。
 */
internal class FileSyncSettingsPage(
    /** 只用来弹出目录选择框（探针传 null）。 */
    private val project: Project?,
    private val settings: SyncSettings,
    private val status: SyncStatus,
) : SettingsPage {

    override val title: String get() = CcoderText.text("settings.page.fileSync")

    private var built: JComponent? = null

    /**
     * 「正在把服务里的值往控件里灌」。
     *
     * 没有它的话：`reload()` 里每设一个值都会触发控件的变更监听器 → 触发保存 →
     * 把刚读出来的值又写回去。更糟的是对**本地副本**（排除项）来说，灌值的那一刻
     * 它还是空的，于是打开对话框就等于把用户的排除项清空一遍。
     * 同 `GeneralSettingsPage` / `EnvironmentSettingsPage` 里那个同名的闸。
     */
    private var loading = false

    // ---- 右栏：建一次，reload 只填值 ----
    // 两个复选框**不带自己的文字**：文字在它们各自那一行的标签上（`settingsRow` 的
    // 行语言）。理由是**用例被它决定**：`FieldLookup` 按 `JLabel` 的文本找字段，
    // 而 `JCheckBox` 的文字不在 `JLabel` 上 —— 写在复选框里就找不到、也点不着。
    private val enableBox = JCheckBox()
    private val deleteBox = JCheckBox()
    private val srcField = JBTextField()
    private val dstField = JBTextField()
    private val rootsField = JBTextField()
    private val settleField = JBTextField()
    private val sweepField = JBTextField()
    private val junkLabel = JBLabel().localizedText("sync.page.junk", "…")

    // ---- 左栏：标签建一次，refreshStatus 填值 ----
    private val stateLabel = JBLabel().localizedText(SyncTexts.runKey(SyncRun.DISABLED))
    private val detailLabel = JBLabel().apply { foreground = UIUtil.getInactiveTextColor() }
    private val roundLabel = JBLabel()
    private val countsLabel = JBLabel()
    private val attentionLabel = JBLabel()
    private val logArea = JBTextArea().apply {
        isEditable = false
        isFocusable = false
        // **要折行**：栏就 200px 宽，而日志里那些句子（"[完成] 复制 12/12，删除 3 项（0 项目标端
        // 本不存在），顺带清掉 1 个空目录，失败 1 项（用时 480 ms）"）远超它 ——
        // 不折行就是**横向被裁掉**，而且没有任何提示告诉用户后面还有字（出图时看出来的）。
        // 折行的代价是一条日志占两三行，那个用滚动条换，比"看不见"好。
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        rows = 3
    }

    /** 排除项的**本地**副本：含"刚加出来还没填名字"的空行（空行不落库，见 [persistExcludes]）。 */
    private var excludes = mutableListOf<String>()
    private val excludeSlot = JPanel()

    private val statusListener: () -> Unit = { refreshStatus() }

    init {
        // 顺带把同步服务拉起来（面板打开时它多半已经在了）。项目已关时 getInstance 会抛，
        // 所以判一下 —— 探针传 null 时整段跳过。
        // 先落到局部变量：`runCatching` 的 lambda 里那个智能转换拿不到（`.isDisposed` 那半边
        // 不算数），编译器只认 `Project?`。
        val p = project
        if (p != null && !p.isDisposed) {
            runCatching { SyncService.getInstance(p) }
        }
    }

    override fun component(): JComponent = built ?: build().also {
        built = it
        reload()
        status.addListener(statusListener)
    }

    override fun dispose() {
        status.removeListener(statusListener)
    }

    // ---------------------------------------------------------------- 骨架

    private fun build(): JComponent = JPanel(BorderLayout()).apply {
        // 表单栏放 EAST 拿确定宽度，列表栏放 CENTER —— 同模型页与 MCP 页那条理由
        add(formColumn(), BorderLayout.EAST)
        add(listColumn(), BorderLayout.CENTER)
    }

    private fun listColumn(): JComponent {
        excludeSlot.layout = BoxLayout(excludeSlot, BoxLayout.Y_AXIS)
        excludeSlot.isOpaque = false
        excludeSlot.alignmentX = Component.LEFT_ALIGNMENT

        val statusCard = settingsCard(
            CcoderText.text("sync.page.statusCard"),
            cardRows(
                // 「状态」这一行有**两行值**：状态本身 + 一句为什么（缺哪一项 / 被谁占着）。
                // 那句是空态最该说的话 —— 光有"未配置"等于什么都没说。
                stateRow(),
                labeled("sync.page.lastRound", roundLabel),
                labeled("sync.page.counts", countsLabel),
                labeled("sync.page.attention", attentionLabel),
            ),
        )
        val excludeCard = settingsCard(
            CcoderText.text("sync.page.excludeCard"),
            cardBlock(excludeSlot),
            footer = actionLabel(CcoderText.text("sync.page.addExclude")) {
                // 先进本地副本、**不落库**：还没填名字，落库会写出一条空规则，
                // 而读回来时它会被丢掉 —— 用户会以为「我加的没了」（同 MCP 页那条）
                excludes += ""
                rebuildExcludes()
            },
        )
        val logCard = settingsCard(
            CcoderText.text("sync.page.logCard"),
            cardBlock(JBScrollPane(logArea).apply {
                preferredSize = Dimension(SYNC_LIST_WIDTH - 40, JBUI.scale(LOG_BOX_HEIGHT))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(LOG_BOX_HEIGHT))
            }),
        )
        return settingsCardColumn(listOf(statusCard, excludeCard, logCard), width = SYNC_LIST_WIDTH)
    }

    /** 「状态」那一行：标题 + 状态 + 一句为什么。 */
    private fun stateRow(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(6, 0, 8, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        add(JBLabel().localizedText("sync.page.state").apply {
            foreground = UIUtil.getInactiveTextColor()
            alignmentX = Component.LEFT_ALIGNMENT
        })
        listOf(stateLabel, detailLabel).forEach {
            it.alignmentX = Component.LEFT_ALIGNMENT
            add(it)
        }
    }

    /**
     * 左栏里的一行：标题在上、值在下（左栏窄，横排会把值挤没）。
     *
     * **上面那 6px 不是装饰**：`cardRows` 把发丝线画在每行的上沿，行自己不留上边距的话
     * 那条线会压在标题字上（出图时一眼看出来的 —— 标题顶被削掉半截）。
     * `settingsRow` 自带 `empty(6, …)`，所以它没这个毛病。
     */
    private fun labeled(titleKey: String, value: JBLabel): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(6, 0, 8, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        add(JBLabel().localizedText(titleKey).apply {
            foreground = UIUtil.getInactiveTextColor()
            alignmentX = Component.LEFT_ALIGNMENT
        })
        value.alignmentX = Component.LEFT_ALIGNMENT
        add(value)
    }

    private fun formColumn(): JComponent = settingsCardColumn(
        settingsCard(
            CcoderText.text("sync.page.formCard"),
            cardBlock(
                // 两个开关走"标签在左、控件在右"，其余走"标签在上、控件在下"——
                // 目录要整行的宽度（路径很长），开关则一行读下来更顺
                settingsRow(CcoderText.text("sync.page.enable"), enableBox),
                wrappedHint(CcoderText.text("sync.page.enableHint"), SYNC_FORM_CONTENT_WIDTH),
                labeledField(CcoderText.text("sync.page.src"), pathRow(srcField)),
                labeledField(CcoderText.text("sync.page.dst"), pathRow(dstField)),
                wrappedHint(CcoderText.text("sync.page.mirrorHint"), SYNC_FORM_CONTENT_WIDTH),
                // 这两条"怎么填"的说明挂在输入框的 tooltip 上，不占版面 ——
                // 这一页在 560px 高的对话框里已经排满了（`settingsCardColumn` 不管溢出，
                // 多出来的行就是被切掉，出图时对着这几行量过），而 tooltip 本就是
                // 这类"填法提示"该待的地方。
                // 那句真正需要**一直看得见**的话（"不配置就不开启"）留在上面那条 hint 里。
                labeledField(CcoderText.text("sync.page.roots"), rootsField.localizedTooltip("sync.page.rootsHint")),
                labeledField(CcoderText.text("sync.page.settle"), settleField),
                labeledField(CcoderText.text("sync.page.sweep"), sweepField.localizedTooltip("sync.page.sweepHint")),
                settingsRow(CcoderText.text("sync.page.deleteMissing"), deleteBox),
                junkLabel.apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyTop(8)
                },
            ),
        ),
        width = SYNC_FORM_WIDTH,
    )

    /**
     * 一行"输入框 + 浏览"。
     *
     * 形状照`GeneralSettingsPage.pathRow()`：**不用 `TextFieldWithBrowseButton`** ——
     * 它的 "…" 是内部扩展组件，离屏布局时会掉到字段外面去。
     * 目录描述符用 `createSingleFolderDescriptor`（目录，不是文件）。
     */
    private fun pathRow(field: JBTextField): JComponent {
        val browse = actionLabel(CcoderText.text("settings.general.browse")) {
            FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
                null,
            ) { picked -> field.text = picked.path }
        }.apply { border = JBUI.Borders.emptyLeft(8) }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(field, BorderLayout.CENTER)
            add(browse, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    // ---------------------------------------------------------------- 读值

    override fun reload() {
        val cfg = settings.config
        loading = true
        try {
            enableBox.isSelected = cfg.enabled
            srcField.text = cfg.src
            dstField.text = cfg.dst
            rootsField.text = cfg.syncRoots.joinToString(", ")
            settleField.text = cfg.settleMs.toString()
            sweepField.text = cfg.sweepMs.toString()
            deleteBox.isSelected = cfg.deleteMissing
            junkLabel.localizedText(
                "sync.page.junk",
                cfg.junk.dirNames.size,
                cfg.junk.fileNames.size,
                cfg.junk.suffixes.size,
                cfg.junk.prefixes.size,
            )
            excludes = cfg.exclude.toMutableList()
        } finally {
            loading = false
        }

        // 监听器**在灌值之后**才挂：避免"打开对话框"本身触发一次保存。
        // 挂在控件上是累加的，所以要用一个标志挡住重复挂（component() 已经记忆化了，
        // 但 reload() 可能被别处再调一次）。
        if (!listenersHooked) {
            listenersHooked = true
            hookListeners()
        }

        rebuildExcludes()
        refreshStatus()
    }

    private var listenersHooked = false

    private fun hookListeners() {
        enableBox.addActionListener { save { it.copy(enabled = enableBox.isSelected) } }
        deleteBox.addActionListener { save { it.copy(deleteMissing = deleteBox.isSelected) } }
        // 目录与范围改动会让服务重建（连看盘的注册都重来），所以**等失焦再写**更好 ——
        // 但仓库里没有"失焦才保存"的先例（都是改动即写 + 服务侧防抖），保持一致。
        srcField.document.addDocumentListener(saver { it.copy(src = srcField.text.trim()) })
        dstField.document.addDocumentListener(saver { it.copy(dst = dstField.text.trim()) })
        rootsField.document.addDocumentListener(saver { it.copy(syncRoots = splitList(rootsField.text)) })
        settleField.document.addDocumentListener(saver { cfg ->
            settleField.text.toLongOrNull()?.let { cfg.copy(settleMs = it) }
        })
        sweepField.document.addDocumentListener(saver { cfg ->
            sweepField.text.toLongOrNull()?.let { cfg.copy(sweepMs = it) }
        })
    }

    /**
     * 一个"改了就写"的文档监听器。
     *
     * [mutate] 返回 null = **这次不写**（比如时长的框里是半截数字、路径还没填完）。
     * 这就是 MCP / hooks 那条"不落库半填的行"的规矩 —— 只不过那几个字段是字符串，
     * 这里是数字，得先解析。
     */
    private fun saver(mutate: (com.ccoder.sync.SyncConfig) -> com.ccoder.sync.SyncConfig?) =
        object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (loading) return
                val next = mutate(settings.config) ?: return
                settings.update(next)
            }
        }

    private fun save(mutate: (com.ccoder.sync.SyncConfig) -> com.ccoder.sync.SyncConfig) {
        if (loading) return
        settings.update(mutate(settings.config))
    }

    /** 逗号或空白分隔 —— 同步范围通常只有一项，不值得为它做一整套列表编辑器。 */
    private fun splitList(raw: String): List<String> =
        raw.split(',', '，', '\n', '\r', '\t').map { it.trim() }.filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- 排除项列表

    private fun rebuildExcludes() {
        excludeSlot.removeAll()
        if (excludes.isEmpty()) {
            excludeSlot.add(JBLabel(CcoderText.text("sync.page.excludeEmpty")).apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }
        excludes.forEachIndexed { index, value ->
            excludeSlot.add(excludeRow(index, value))
        }
        excludeSlot.revalidate()
        excludeSlot.repaint()
    }

    private fun excludeRow(index: Int, value: String): JComponent {
        val field = JBTextField(value)
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (loading) return
                if (index !in excludes.indices) return
                excludes[index] = field.text
                persistExcludes()
            }
        })
        // 用共用的那句（`settings.common.delete`）—— 仓库里五页都删"当前选中那条"，
        // 各写各的迟早出现四处措辞不一样
        val remove = actionLabel(DELETE_LABEL) {
            if (index in excludes.indices) {
                excludes.removeAt(index)
                persistExcludes()
                rebuildExcludes()
            }
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(field, BorderLayout.CENTER)
            add(remove, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    /**
     * 落库：**空行不写进去**（它们只在本地副本里活着，好让用户看得见自己刚加的那一行）。
     *
     * 与 MCP / hooks 同一条规矩：不落库半填的行 —— 落了库、读回来时被丢掉，
     * 用户会以为"我加的没了"。
     */
    private fun persistExcludes() {
        if (loading) return
        settings.update(settings.config.copy(exclude = excludes.filter { it.isNotBlank() }))
    }

    // ---------------------------------------------------------------- 状态（左栏）

    /**
     * 只碰左栏。**绝不能碰右栏的控件** —— 服务每跑完一轮就会推一次，而那条路直接回到
     * EDT，重建表单会毁掉用户正在敲的东西（见类头注）。
     */
    private fun refreshStatus() {
        if (built == null) return
        val s = status.snapshot

        stateLabel.localizedText(SyncTexts.runKey(s.run))
        when {
            // 被占用时那句话的**关键是"谁"** —— 不写出来用户只会看到"不工作"
            s.run == SyncRun.OCCUPIED && !s.occupiedBy.isNullOrBlank() -> {
                detailLabel.isVisible = true
                detailLabel.localizedText("sync.page.occupiedBy", s.occupiedBy!!)
            }

            s.run == SyncRun.DISABLED || s.run == SyncRun.STOPPED -> {
                detailLabel.isVisible = true
                detailLabel.localizedText(SyncTexts.problemKey(s.problem))
            }

            // 在跑的时候**没有"为什么"可说**，这一行整个藏起来。
            // 不能留一句空话：`problem` 那时是 null，而 null 在映射里等于 DISABLED ——
            // 会在"运行中"底下写着"打开开关并选好目录"（第一版就是这么错的）。
            else -> detailLabel.isVisible = false
        }
        detailLabel.parent?.revalidate()   // 藏起来/放出来都要重摆一次

        roundLabel.localizedText(
            if (s.lastRoundAtMs == null) "sync.page.roundNever" else "sync.page.roundValue",
            clock(s.lastRoundAtMs ?: 0L),
            s.lastCostMs ?: 0L,
        )
        countsLabel.localizedText("sync.page.countsValue", s.copied, s.deleted, s.failed.size)

        // `[失败]` 与 `[提醒]` 合在一行里报个数，明细进 tooltip —— 左栏只有 240px
        val attention = buildList {
            s.failed.take(ATTENTION_LINES).forEach { add("${it.rel} — ${it.reason}") }
            s.kept.take(ATTENTION_LINES).forEach { add("${it.rel} — ${CcoderText.text(SyncTexts.keptKey(it.why))}") }
        }
        if (attention.isEmpty()) {
            attentionLabel.localizedText("sync.page.attentionNone")
        } else {
            attentionLabel.localizedText("sync.page.attentionSome", attention.size)
        }
        // 空的时候挂空串而不是不挂：不挂的话会留着上一次的 tooltip
        attentionLabel.localizedTooltip("sync.page.attentionTip", attention.joinToString("\n"))

        // 空日志给一句话，别留一个空框（用户不知道那是"还没有"还是"坏了"）
        logArea.text = if (s.log.isEmpty()) {
            CcoderText.text("sync.page.logEmpty")
        } else {
            s.log.takeLast(LOG_LINES).joinToString("\n")
        }
        logArea.caretPosition = logArea.text.length
    }

    private companion object {
        /** "需要注意"那一行最多列几条（多了塞不进 240px 的栏）。 */
        const val ATTENTION_LINES = 3
        const val LOG_LINES = 40
    }
}

/** `HH:mm:ss`（本地时区）。只用于显示。 */
private fun clock(epochMs: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(epochMs))
