package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ScrollPaneConstants
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/** 「额外目录」那一栏的标签。用例要按它找控件。也当表头用。 */
internal val EXTRA_DIRS_LABEL: String get() = CcoderText.text("settings.env.extraDirs")

/** 「环境变量」那一栏的标签。 */
internal val ENV_VARS_LABEL: String get() = CcoderText.text("settings.env.envVars")

/** 两张表各自那个"加一行"动作。用例按它点。 */
internal val ADD_ROW_LABEL: String get() = CcoderText.text("settings.env.addRow")

/** 那个"删掉选中的几行"动作。 */
internal val REMOVE_ROW_LABEL: String get() = CcoderText.text("settings.env.removeRow")

/** 两张表各自的高度（约五行）。 */
private const val TABLE_HEIGHT = 118

/**
 * 表头：额外目录只有一列。
 *
 * **写成函数而不是顶层 `val`**：顶层 `val` 只在类加载时求值一次，语言换了
 * （`UiLanguageSettings` 会推新的 locale）表头还停在旧语言上 —— 而"打开设置
 * 就是新语言"是这个功能的承诺。函数每次建页时取一遍。
 */
private fun extraDirsColumns(): Array<String> = arrayOf(EXTRA_DIRS_LABEL)

/** 表头：环境变量两列。理由同 [extraDirsColumns]。 */
private fun envVarsColumns(): Array<String> = arrayOf(
    CcoderText.text("settings.env.column.name"),
    CcoderText.text("settings.env.column.value"),
)

/**
 * 环境页：运行依赖 · 额外目录 · 环境变量 · 冲突提示。
 *
 * 「运行依赖」那块（2026-09-17）是这一页顶上的一段，自成一个
 * [RuntimeDepsSection] —— 它管的是"这台机器上有没有 node 与 claude"，
 * 与下面两张表（管 CLI 怎么跑）是两件事，中间画了条线。
 *
 * ## 两张表都是**改一格就落库**
 *
 * 今天那版 IDE 设置页是点「应用」时一次性读表，中途没提交的编辑会丢；
 * 这里统一成写通。代价要知道：`TableModelListener` 在**每次 `setValueAt`** 都发，
 * 所以**打到一半的字也会立刻落库** —— 与模型页"改名立刻反映到列表"是同一个取舍。
 *
 * ## 写回一律**整体替换**
 *
 * `ClaudeSettings.extraDirs` / `envOverrides` 的 getter 直接把内部那个 `MutableMap`
 * 暴露出来了（见 `ClaudeSettings.kt`），原地 `add` 等于绕过 State 的语义。
 * 所以每次从头读一遍表、整份赋值回去（读的规则见 [readColumn] / [readPairs]）。
 *
 * ## 冲突提示为什么两处都有
 *
 * 这里一句（这几个键正是在这一页编辑的），模型页列表上方也有一句（spec §6 要求
 * 挂在"谁覆盖谁"能被看见的地方）。两处共用 [conflictWarningText]，而且这一页一改
 * 就通过 [onEnvOverridesChanged] 让模型页那句跟着重算 ——
 * 不然"刚加完键、切过去却没提示"看起来就像那个提示坏了。
 */
internal class EnvironmentSettingsPage(
    private val project: Project?,
    private val settings: ClaudeSettings,
    /** 运行依赖的检测结果与安装进度（项目级服务，活得比这个对话框长）。 */
    private val deps: RuntimeDepsService,
    /** 「运行依赖」那块的确认框/剪贴板/浏览器外壳。默认生产实现，探针与用例换掉它。 */
    private val depsUi: DepsUi = DepsUi(),
    /** envOverrides 变了就喊一声：模型页那条冲突警告要跟着重算。 */
    private val onEnvOverridesChanged: () -> Unit = {},
) : SettingsPage {

    override val title: String get() = CcoderText.text("settings.page.environment")

    /**
     * 顶部那块「运行依赖」（2026-09-17 加）。设计稿 §3.7。
     *
     * 它自带整块的订阅与退订，所以这一页只需转发 [dispose] 与 [reload]。
     */
    private val depsSection = RuntimeDepsSection(
        deps = deps,
        confirm = depsUi.confirm ?: { plan -> confirmInstall(project, plan) },
        copy = depsUi.copy,
        browse = depsUi.browse,
        os = depsUi.os,
        tools = depsUi.tools,
        // 这一段被放进卡片里，宽度按卡内算 —— 说明折行与输出区高度都吃这个数
        contentWidth = CARD_CONTENT_WIDTH,
    )

    private val extraDirsModel = DefaultTableModel(extraDirsColumns(), 0)
    private val envModel = DefaultTableModel(envVarsColumns(), 0)

    private val conflictSlot = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private var built: JComponent? = null

    /** reload 期间不写回。理由见 [GeneralSettingsPage.save]。 */
    private var loading = false

    override fun component(): JComponent = built ?: build().also { built = it }

    private fun build(): JComponent {
        // 两张表各挂各的监听器：`TableModelEvent` 只说"哪一行变了"，不说"哪张表变的"
        extraDirsModel.addTableModelListener { save() }
        envModel.addTableModelListener { save() }

        val column = settingsColumn().apply {
            add(depsCard())
            // 冲突提示留在卡外：它说的是"这几个键与模型页那套打架"，不属于任何一张卡
            add(conflictSlot)
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(
                tableCard(
                    EXTRA_DIRS_LABEL,
                    JBTable(extraDirsModel),
                    CcoderText.text("settings.env.extraDirsHint"),
                )
            )
            add(Box.createVerticalStrut(JBUI.scale(10)))
            add(
                tableCard(
                    ENV_VARS_LABEL,
                    JBTable(envModel),
                    CcoderText.text("settings.env.envVarsHint"),
                )
            )
        }
        // 这一页比别的页高（运行依赖那一段 + 两张定高的表，量出来 581–677px，
        // 而对话框是定高的、可用不到 500px）。**不套滚动条的话，底部那行说明会被
        // 直接切掉**（2026-09-17 出图时看出来的）。
        //
        // 横向滚动条关掉：内容的宽度是算好的（PAGE_CONTENT_WIDTH），
        // 纵向滚动条吃掉的是右边那 20px 内边距，盖不到内容。
        return JBScrollPane(settingsPageBody(column)).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }.also { reload() }
    }

    /**
     * 一张表 + 它自己的两个动作。
     *
     * ## 为什么不用 `ToolbarDecorator`
     *
     * 它构造时要 `ActionManager.getInstance()`，而单测 JVM 里
     * `ApplicationManager.getApplication()` 是 null —— 实测直接 NPE
     * （`CommonActionsPanel.<init>` → `ActionManager.getInstance`）。
     * 于是这一页既跑不了用例、也出不了图，等于整页没法验。
     *
     * 手写的代价是丢掉平台的行内编辑体验，换来的是**这一页可测、可看图** ——
     * 模型页那几个动作（`＋ 添加配置` / `✕`）本来就是手写的，四页观感也更齐。
     *
     * ## 高度为什么必须封顶
     *
     * 表挂在 `BoxLayout` 里，不封的话 `JTable` 那个默认的 450×400 会把两张表
     * 摞成八百多像素。封顶必须**等于**定死的那个高度 —— 给 `Int.MAX_VALUE`
     * 它就成了页里的弹簧（同 `ModelProfilesPage.modelListBox` 的教训）。
     */
    /** 卡：运行依赖。这一段自带标题行（那只「重新检测」也在上面），所以卡片不再要卡头。 */
    private fun depsCard(): JComponent = settingsCard(null, cardBlock(depsSection.component()))

    /**
     * 卡：一张表。**卡头就是原来那个字段标签**（`labeledField` 退休了 —— 同页同文
     * 出现两次会被 `findLabel` 抓到前面那一个），两个动作进卡脚。
     */
    private fun tableCard(title: String, table: JBTable, hint: String): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, CARD_PAD_H, 0, CARD_PAD_H)
            add(tableScroll(table))
            add(wrappedHint(hint, CARD_CONTENT_WIDTH))
        }
        return settingsCard(title, body, footer = tableActions(table))
    }

    private fun tableScroll(table: JBTable): JComponent = JBScrollPane(table).apply {
        // 列头要**显式**挂上：`JBScrollPane(table)` 在离屏摆版时它是不出来的，
        // 而"变量名 / 值"这两列离了列头就只能靠猜
        setColumnHeaderView(table.tableHeader)
        border = JBUI.Borders.empty()
        alignmentX = Component.LEFT_ALIGNMENT
        preferredSize = Dimension(JBUI.scale(CARD_CONTENT_WIDTH), JBUI.scale(TABLE_HEIGHT))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(TABLE_HEIGHT))
    }

    /** 表下面那两个动作：`＋ 添加一行` 与 `－ 删除选中`。 */
    private fun tableActions(table: JBTable): JComponent {
        val model = table.model as DefaultTableModel
        val remove = actionLabel(REMOVE_ROW_LABEL) {
            // 从下往上删：删一行之后后面的下标全会往前挪
            table.selectedRows.sortedDescending().forEach { model.removeRow(it) }
        }
        // 没选中就灰着。一个点不动的动作必须**看起来**就是点不动的
        // （2026-09-15 那两轮"看得见、点不动"的教训）
        fun syncRemove() {
            val on = table.selectedRowCount > 0
            remove.foreground = if (on) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
            remove.cursor = Cursor.getPredefinedCursor(if (on) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        }
        table.selectionModel.addListSelectionListener { syncRemove() }
        syncRemove()

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(actionLabel(ADD_ROW_LABEL) { model.addRow(arrayOf<Any>("")) })
            add(Box.createHorizontalStrut(JBUI.scale(14)))
            add(remove)
            add(Box.createHorizontalGlue())
        }
    }

    override fun dispose() {
        depsSection.dispose()
    }

    override fun reload() {
        loading = true
        try {
            extraDirsModel.rowCount = 0
            settings.extraDirs.forEach { extraDirsModel.addRow(arrayOf<Any>(it)) }
            envModel.rowCount = 0
            settings.envOverrides.forEach { (k, v) -> envModel.addRow(arrayOf<Any>(k, v)) }
        } finally {
            loading = false
        }
        refreshConflict()
        // 每次打开设置重查一遍两个依赖。服务里那份缓存会先画出来（不会白屏），
        // 查完再由服务通知这一页重画 —— 页里不开线程
        depsSection.recheck()
    }

    private fun save() {
        if (loading) return
        settings.extraDirs = readColumn(extraDirsModel, 0).toMutableList()
        settings.envOverrides = readPairs(envModel).toMutableMap()
        refreshConflict()
        onEnvOverridesChanged()
    }

    /** 这一页顶部那句冲突提示。与模型页那句**同一句话**，只是摆在这儿离键更近。 */
    private fun refreshConflict() {
        conflictSlot.removeAll()
        val keys = conflictingEnvKeys(settings.envOverrides)
        if (keys.isNotEmpty()) {
            // 它自己带下间距，不要再加 strut（见 wrappedHint 那条）
            conflictSlot.add(wrappedHint(conflictWarningText(keys), PAGE_CONTENT_WIDTH))
        }
        conflictSlot.revalidate()
        conflictSlot.repaint()
    }
}

/**
 * 表里某一列的非空值，从上到下。
 *
 * 抽成顶层函数是为了能单测：这些"从界面读回数据"的规则正是最容易写错的地方
 * （空行要不要留、值要不要 trim、键为空算不算一条）。
 */
internal fun readColumn(model: DefaultTableModel, col: Int): List<String> =
    (0 until model.rowCount)
        .mapNotNull { model.getValueAt(it, col)?.toString()?.trim() }
        .filter { it.isNotEmpty() }

/** 表里的键值对。**键为空的行直接丢掉** —— 半填的一行不该变成一个空变量名。 */
internal fun readPairs(model: DefaultTableModel): Map<String, String> =
    (0 until model.rowCount)
        .mapNotNull { row ->
            val key = model.getValueAt(row, 0)?.toString()?.trim().orEmpty()
            val value = model.getValueAt(row, 1)?.toString()?.trim().orEmpty()
            if (key.isEmpty()) null else key to value
        }
        .toMap()
