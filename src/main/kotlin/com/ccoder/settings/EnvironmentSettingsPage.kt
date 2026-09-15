package com.ccoder.settings

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/** 「额外目录」那一栏的标签。用例要按它找控件。 */
internal const val EXTRA_DIRS_LABEL = "额外目录"

/** 「环境变量」那一栏的标签。 */
internal const val ENV_VARS_LABEL = "环境变量"

/** 两张表各自那个"加一行"动作。用例按它点。 */
internal const val ADD_ROW_LABEL = "＋ 添加一行"

/** 那个"删掉选中的几行"动作。 */
internal const val REMOVE_ROW_LABEL = "－ 删除选中"

/** 两张表各自的高度（约五行）。 */
private const val TABLE_HEIGHT = 118

/** 表头：额外目录只有一列。 */
private val EXTRA_DIRS_COLUMNS = arrayOf("额外目录")

/** 表头：环境变量两列。 */
private val ENV_VARS_COLUMNS = arrayOf("变量名", "值")

/**
 * 环境页：额外目录 · 环境变量 · 冲突提示。
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
    private val settings: ClaudeSettings,
    /** envOverrides 变了就喊一声：模型页那条冲突警告要跟着重算。 */
    private val onEnvOverridesChanged: () -> Unit = {},
) : SettingsPage {

    override val title: String = "环境"

    private val extraDirsModel = DefaultTableModel(EXTRA_DIRS_COLUMNS, 0)
    private val envModel = DefaultTableModel(ENV_VARS_COLUMNS, 0)

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
            add(conflictSlot)
            add(labeledField(EXTRA_DIRS_LABEL, tableBox(JBTable(extraDirsModel))))
            add(
                wrappedHint(
                    "传给 CLI 的 --add-dir：项目之外也允许 Claude 读写的目录",
                    PAGE_CONTENT_WIDTH,
                )
            )
            add(labeledField(ENV_VARS_LABEL, tableBox(JBTable(envModel))))
            add(
                wrappedHint(
                    "宿主隔离黑名单中的变量无法通过此处覆盖（设计文档 §3.2）",
                    PAGE_CONTENT_WIDTH,
                )
            )
        }
        return settingsPageBody(column).also { reload() }
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
    private fun tableBox(table: JBTable): JComponent {
        val scroll = JBScrollPane(table).apply {
            // 列头要**显式**挂上：`JBScrollPane(table)` 在离屏摆版时它是不出来的，
            // 而"变量名 / 值"这两列离了列头就只能靠猜
            setColumnHeaderView(table.tableHeader)
            border = JBUI.Borders.empty()
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(JBUI.scale(PAGE_CONTENT_WIDTH), JBUI.scale(TABLE_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(TABLE_HEIGHT))
        }

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

        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(4)
            add(actionLabel(ADD_ROW_LABEL) { model.addRow(arrayOf<Any>("")) })
            add(Box.createHorizontalStrut(JBUI.scale(14)))
            add(remove)
            add(Box.createHorizontalGlue())
        }
        actions.maximumSize = Dimension(Int.MAX_VALUE, actions.preferredSize.height)

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(scroll)
            add(actions)
        }
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
