package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.ccoder.ui.Tone
import com.ccoder.ui.focusColor
import com.ccoder.ui.mix
import com.ccoder.ui.toneColor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.event.DocumentEvent
import java.awt.Rectangle

/**
 * 设置里的「技能」页 —— 方案一：**分组表格，开关就在行上**（`docs/design/skills-page.html`）。
 *
 * ## 这一页的数据是"下一代会话看到的"
 *
 * 清单来自 `sidecar/skills-once.js` 现起的一次性会话（不落盘），设置读三层文件 ——
 * 两者拼出来的就是**新会话**会看到的样子。而实测（探针 Q2/Q3）说得很清楚：
 * 改完当前会话纹丝不动，所以页脚那句提示必须写准，否则用户改完回聊天区
 * 会发现"怎么还在用"。见 [footerHint]。
 *
 * ## 开关是两档，「⋯」里还有两档
 *
 * CLI 的真实模型是四档（`on` / `name-only` / `user-invocable-only` / `off`），
 * 但日常动作是"这条别来烦我"。所以行上只放开关，另外两档进菜单，
 * 每档带一句解释（光看名字没人猜得出"只列名字"是什么意思）。
 */
internal class SkillsSettingsPage(
    /** 只用来打开 SKILL.md（渲染探针传一个假 Project，见 `FileSyncSettingsPage` 的同一条）。 */
    private val project: Project?,
    /** 拿不到就是没有项目服务（探针那条路）—— 那时这一页只画一句"问不到清单"。 */
    private val catalog: SkillsCatalog?,
) : SettingsPage {

    override val title: String get() = CcoderText.text("settings.page.skills")

    private var built: JComponent? = null
    private var search = ""
    private val collapsed = mutableSetOf<SkillSource>(SkillSource.BUILTIN)

    private val listener: () -> Unit = { refresh() }

    override fun component(): JComponent = built ?: build().also {
        built = it
        val c = catalog
        if (c == null) {
            refresh()
        } else {
            c.addListener(listener)
            refresh()
            // 缓存里还没有就先问一遍（关掉框再打开时就不必再等那 3～5 秒）。
            //
            // 判据是 **[SkillsCatalog.known]，不是 `state is Ready`**：初始状态与
            // "刚问完、结果为空"在类型上是同一个 Ready —— 用类型判的话，第一次打开
            // 永远不自动去问（用例逮到过：整页停在"还没有问过"）。
            if (!c.known && c.state !is SkillsState.Loading) c.refresh()
        }
    }

    override fun dispose() {
        catalog?.removeListener(listener)
    }

    override fun reload() = refresh()

    override fun footerHint(): String = CcoderText.text("settings.skills.footer")

    private fun build(): JComponent = JBScrollPane(
        // 宽度跟着视口走：行要铺满这一栏（与 MCP 页同一件事，同一个做法）
        WidthTracked(settingsPageBody(column)),
    ).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(16)
    }

    private val column: JPanel = settingsColumn()

    private val searchField = JBTextField().apply {
        emptyText.text = CcoderText.text("settings.skills.search")
        preferredSize = Dimension(JBUI.scale(SEARCH_WIDTH), preferredSize.height)
        maximumSize = Dimension(JBUI.scale(SEARCH_WIDTH), preferredSize.height)
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                search = text.trim()
                refresh()
            }
        })
    }

    private fun refresh() {
        column.removeAll()
        val c = catalog
        if (c == null) {
            column.add(hint(CcoderText.text("settings.skills.error.noProject")))
            rebuildColumn()
            return
        }
        column.add(toolbar(c))
        column.add(Box.createVerticalStrut(JBUI.scale(6)))

        when (val state = c.state) {
            is SkillsState.Failed -> {
                column.add(hint(CcoderText.text("settings.skills.failed", state.reason), Tone.Danger))
                column.add(actionLabel(CcoderText.text("settings.skills.retry")) { c.refresh() })
                rebuildColumn()
                return
            }

            is SkillsState.Loading -> {
                if (state.first) {
                    column.add(hint(CcoderText.text("settings.skills.loading")))
                } else {
                    column.add(hint(CcoderText.text("settings.skills.refreshing")))
                }
                if (!c.known) {
                    rebuildColumn()
                    return
                }
            }

            is SkillsState.Ready -> {
                state.notice?.let { column.add(noticeLine(it)) }
                if (!state.known) {
                    column.add(hint(CcoderText.text("settings.skills.notAsked")))
                    rebuildColumn()
                    return
                }
                val visible = filterRows(state.rows)
                if (visible.isEmpty()) {
                    column.add(hint(if (search.isBlank()) CcoderText.text("settings.skills.empty") else CcoderText.text("settings.skills.noMatch", search)))
                    rebuildColumn()
                    return
                }
                skillGroupsOf(visible).forEach { (source, rows) -> addGroup(source, rows) }
            }
        }
        rebuildColumn()
    }

    private fun rebuildColumn() {
        column.revalidate()
        column.repaint()
    }

    private fun filterRows(rows: List<SkillRow>): List<SkillRow> {
        if (search.isBlank()) return rows
        val needle = search.lowercase()
        return rows.filter {
            it.name.lowercase().contains(needle) ||
                it.key.lowercase().contains(needle) ||
                it.description?.lowercase()?.contains(needle) == true
        }
    }

    // ---- 顶部那一行 ----

    private fun toolbar(c: SkillsCatalog): JComponent {
        val state = c.state
        val rows = (state as? SkillsState.Ready)?.rows.orEmpty()
        val off = rows.count { !it.isOn }
        val hint = when {
            rows.isNotEmpty() -> CcoderText.text("settings.skills.count", rows.size, off)
            // 「还没有问过」只在**真的没问过**时说：加载中（下面"正在问"那句在说事）
            // 与失败（下面"问不到"那句在说事）时，这一行再这么说就是句假话 ——
            // 真机截图里它和「问不到技能清单：spawn EINVAL」并排出现时逮到的
            state is SkillsState.Ready && !state.known -> CcoderText.text("settings.skills.countEmpty")
            else -> ""
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(dimLabel(hint), BorderLayout.WEST)
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(searchField)
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                    add(
                        actionLabel(CcoderText.text("settings.skills.refresh")) {
                            catalog?.refresh()
                        },
                    )
                },
                BorderLayout.EAST,
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    // ---- 一组 ----

    private fun addGroup(source: SkillSource, rows: List<SkillRow>) {
        val isCollapsed = source in collapsed
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(if (column.componentCount > 2) 10 else 2, 0, 4, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(
                JBLabel(
                    (if (isCollapsed) "▸ " else "▾ ") + source.label +
                        "  " + CcoderText.text("settings.skills.groupCount", rows.size),
                ).apply { foreground = UIUtil.getInactiveTextColor() },
                BorderLayout.WEST,
            )
            add(dimLabel(source.hint), BorderLayout.EAST)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (isCollapsed) collapsed.remove(source) else collapsed.add(source)
                    refresh()
                }
            })
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        column.add(header)
        if (isCollapsed) return

        rows.forEach { column.add(rowOf(it)) }
    }

    // ---- 一行 ----

    private fun rowOf(row: SkillRow): JComponent {
        val line = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(3, 0)
        }

        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            // 名字那一栏**定宽**：不定的的话描述会各自从名字右边开始，
            // 一列字参差不齐、扫起来很费劲（2026-09-30 出图看出来的）
            preferredSize = Dimension(JBUI.scale(NAME_COLUMN_WIDTH), preferredSize.height)
            minimumSize = preferredSize
            maximumSize = preferredSize
            add(
                if (row.locked) {
                    lockChip()
                } else {
                    SkillSwitch(row.key, row.isOn) { toggle(row) }
                },
            )
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(
                JBLabel(row.name).apply {
                    foreground = if (row.isOn) UIUtil.getLabelForeground() else UIUtil.getInactiveTextColor()
                    font = font.deriveFont(if (row.isOn) font.style else font.style or java.awt.Font.ITALIC)
                },
            )
        }
        line.add(left, BorderLayout.WEST)
        // 左边那道 10px 是**必需的**：BorderLayout 的 WEST 与 CENTER 之间没有缝，
        // 描述会贴着名字（2026-09-30 出图时看见的："cavemanKeep technical substance…"）
        line.add(dimLabel(descriptionOf(row)).apply { border = JBUI.Borders.emptyLeft(10) }, BorderLayout.CENTER)
        line.add(
            JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                // 描述长了会一直写到自己的右沿 —— 不留这道缝，它和 token 会贴在一起
                border = JBUI.Borders.emptyLeft(10)
                add(dimLabel(row.tokens?.let { CcoderText.text("settings.skills.tokens", it) } ?: ""))
                add(Box.createHorizontalStrut(JBUI.scale(8)))
                add(moreLabel(row))
            },
            BorderLayout.EAST,
        )
        line.maximumSize = Dimension(Int.MAX_VALUE, line.preferredSize.height)
        return line
    }

    /**
     * 行中间那句。三种情形说的不是一件事：
     *  - 开着：技能的描述（可能很长，这一栏放不下就裁掉）；
     *  - 关着：它现在不参与，而且**列表里本来已经没有它**（这一行是从设置补出来的）；
     *  - 加载中重画：描述还在，照旧显示。
     */
    private fun descriptionOf(row: SkillRow): String = when {
        !row.listed -> CcoderText.text("settings.skills.row.notListed")
        row.description == null -> CcoderText.text("settings.skills.row.noDescription")
        else -> row.description
    }

    /**
     * 插件行前面那枚小标记。
     *
     * **只有两个字**（词表里就是来源名"插件"），不是整句「由插件决定」：整句要 74px 宽，
     * 会把名字那一栏顶到 250px、把描述挤掉 40px —— 而"本地覆盖不了它"这句
     * 分组标题右边紧挨着就写着一次（2026-09-30 出图时权衡的）。
     */
    private fun lockChip(): JComponent = JBLabel(CcoderText.text("settings.skills.source.plugin")).apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        font = font.deriveFont(font.size - 1f)
        // 完整那句挂在悬停上：行上只有两个字，鼠标停一下能问出"为什么这行没有开关"
        toolTipText = CcoderText.text("settings.skills.row.locked")
    }

    private fun moreLabel(row: SkillRow): JComponent = JBLabel("⋯").apply {
        foreground = UIUtil.getInactiveTextColor()
        font = font.deriveFont(font.size + 3f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showMenu(row, this@apply, e)
        })
    }

    private fun showMenu(row: SkillRow, anchor: JComponent, e: MouseEvent) {
        val menu = JPopupMenu()
        skillMenuActions(row).forEach { action ->
            menu.add(
                javax.swing.JMenuItem(action.label).apply {
                    // 那一句解释放 tooltip 而不是拼进标题：菜单宽度会跟着最长那句跑，
                    // 而四档的解释都不短
                    action.hint?.let { toolTipText = it }
                    addActionListener { onMenuAction(row, action) }
                },
            )
        }
        if (menu.componentCount == 0) return
        menu.show(anchor, e.x, e.y)
    }

    private fun onMenuAction(row: SkillRow, action: SkillMenuAction) {
        when {
            action.id == "open" -> openSkillFile(row)
            action.id.startsWith("plugin-disable:") -> {
                val plugin = action.id.removePrefix("plugin-disable:")
                catalog?.runPlugin("disable", plugin, CcoderText.text("settings.skills.plugin.disable"))
            }
            action.id.startsWith("plugin-update:") -> {
                val plugin = action.id.removePrefix("plugin-update:")
                catalog?.runPlugin("update", plugin, CcoderText.text("settings.skills.plugin.update"))
            }
            else -> {
                val state = SkillState.entries.firstOrNull { it.name == action.id } ?: return
                catalog?.setState(row.key, row.name, state)
            }
        }
    }

    private fun toggle(row: SkillRow) {
        catalog?.setState(row.key, row.name, if (row.isOn) SkillState.OFF else SkillState.ON)
    }

    /**
     * 打开那条技能的文件。
     *
     * 走 [FileEditorManager] 而不是 `Desktop.open`：这一页的旁边就是编辑器，
     * 弹一个系统关联程序是另一种体验（同一份文件两处打开、改完两边不同步）。
     * VFS 里找不到（文件刚被删、路径字符化过）就什么都不做 —— 菜单项本来
     * 只有扫到文件时才出现，走到这儿还没打开就是"刚好被删了"。
     */
    private fun openSkillFile(row: SkillRow) {
        val path = row.path ?: return
        val open = project ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(open).openFile(vf, true)
        }
    }

    // ---- 提示语 ----

    private fun noticeLine(notice: SkillsNotice): JComponent = when (notice) {
        is SkillsNotice.Written ->
            hint(CcoderText.text("settings.skills.notice.written", notice.name), Tone.Ok)

        is SkillsNotice.NoEffect ->
            hint(CcoderText.text("settings.skills.notice.noEffect", notice.name), Tone.Danger)

        is SkillsNotice.WriteFailed ->
            hint(CcoderText.text("settings.skills.notice.writeFailed", notice.name, notice.reason), Tone.Danger)

        is SkillsNotice.PluginAction -> if (notice.ok) {
            hint(CcoderText.text("settings.skills.notice.pluginOk", notice.label, notice.plugin), Tone.Ok)
        } else {
            hint(CcoderText.text("settings.skills.notice.pluginFailed", notice.label, notice.plugin, notice.reason.orEmpty()), Tone.Danger)
        }
    }

    private fun hint(text: String, tone: Tone? = null): JComponent = JBLabel(text).apply {
        foreground = tone?.let { toneColor(it) } ?: UIUtil.getInactiveTextColor()
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyBottom(4)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun dimLabel(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getInactiveTextColor()
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** 宽度跟着视口走（抄 MCP 页那一份，逐条都一样；理由见那边的注释）。 */
    private class WidthTracked(private val inner: JComponent) : JPanel(BorderLayout()), Scrollable {
        init {
            isOpaque = false
            add(inner, BorderLayout.NORTH)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(64)
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private companion object {
        const val SEARCH_WIDTH = 180

        /**
         * 名字那一栏的宽度。
         *
         * 得按**最宽的那种行**算：插件行前面站着一枚小标记（约 34px），
         * 再加上最长的键 `code-review:code-review`（约 160px）—— 190 会把那个键**裁掉半截**
         * （2026-09-30 出图时就是这么裁的）。
         */
        const val NAME_COLUMN_WIDTH = 215
    }
}

/**
 * 行上那颗开关：开是强调色胶囊、关是灰的。**自绘**，同导航图标那条理由 ——
 * 一颗真的 `JCheckBox` 在这个字体与间距下要么太大、要么带着平台默认的方框底。
 *
 * 只有两档：另外两档在「⋯」里（见 `skillMenuActions`）。
 *
 * [key] 是它管的那条技能的设置键：用例靠它找开关（`SkillSwitch` 不带文本，
 * 没法像其它控件那样按字找），出错时的日志也才有话说。
 */
internal class SkillSwitch(
    internal val key: String,
    private val on: Boolean,
    private val onClick: () -> Unit,
) : JComponent() {

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(WIDTH), JBUI.scale(HEIGHT))
    override fun getMaximumSize(): Dimension = preferredSize
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height.toDouble()
            val w = width.toDouble()
            val track = if (on) focusColor() else mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.25)
            g2.color = track
            g2.fill(RoundRectangle2D.Double(0.5, 0.5, w - 1, h - 1, h - 1, h - 1))

            val knob = h - JBUI.scale(4).toDouble()
            val x = if (on) w - knob - JBUI.scale(2) else JBUI.scale(2).toDouble()
            g2.color = if (on) JBColor.WHITE else UIUtil.getInactiveTextColor()
            g2.fill(Ellipse2D.Double(x, JBUI.scale(2).toDouble(), knob, knob))
            g2.stroke = BasicStroke(1f)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val WIDTH = 30
        const val HEIGHT = 16
    }
}
