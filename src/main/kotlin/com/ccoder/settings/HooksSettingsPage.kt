package com.ccoder.settings

import com.google.gson.JsonObject
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** 左栏那个新建按钮。空态文案会引用它，别在别处抄字面量。 */
internal const val ADD_HOOK_LABEL = "＋ 添加 hook"

/** 左栏宽度。与 MCP 页取同一个数 —— 两页并排看时栏宽跳一下会很扎眼。 */
internal const val HOOK_LIST_WIDTH = MCP_LIST_WIDTH

internal const val HOOK_FORM_WIDTH = PAGE_WIDTH - HOOK_LIST_WIDTH

private const val HOOK_LIST_PADDING_H = 12
private const val HOOK_FORM_PADDING_H = 16
private const val HOOK_FORM_CONTENT_WIDTH = HOOK_FORM_WIDTH - 2 * HOOK_FORM_PADDING_H

/**
 * hooks 页：改项目级 `.claude/settings.json` 里的 `hooks` 一块。
 *
 * **这一页会写进一个不属于我们的文件**。仓库此前明确"CCoder 不写
 * `settings.json`"，说的是**用户级**那份（含密钥与端点，碰了会静默改掉认证行为）。
 * 项目级这份可提交、可审查，而且只碰 `hooks` 一个键 —— 但这个"转向"在设计稿
 * 第三章里显式记着，不是悄悄做的。
 *
 * 只做 7 个常用事件、只做 `type: "command"`（见 `MANAGED_HOOK_EVENTS`）。
 * 文件里其余的 hook **原样保留**，页面顶部会如实报出有多少条不归这里管 ——
 * 否则用户会以为"我原来配的那些被这个面板吃掉了"。
 */
internal class HooksSettingsPage(
    /** 项目根。null = 拿不到项目目录，这时只读不写（别猜一个路径去写）。 */
    private val baseDir: Path?,
) : SettingsPage {

    override val title: String = "hooks"

    private val listSlot = JPanel()
    private val formSlot = JPanel()

    private var editing: HookRule? = null
    private var editingIndex: Int = -1

    /** 文件**原样**读进来的那份：写回时按它保序、保别人的键。 */
    private var root: JsonObject = JsonObject()

    private var config: HooksConfig = HooksConfig(emptyList(), emptyMap(), emptyMap())

    private var built: JComponent? = null

    private fun filePath(): Path? = baseDir?.let { projectSettingsPath(it) }

    override fun component(): JComponent = built ?: build().also {
        built = it
        reload()
    }

    override fun reload() {
        root = filePath()?.let { ProjectJson.read(it) } ?: JsonObject()
        config = hooksOf(root)
        editing = editing?.let { e -> config.rules.getOrNull(editingIndex)?.takeIf { it.event == e.event } }
        if (editing == null) editingIndex = -1
        refresh()
    }

    private fun build(): JComponent = JPanel(BorderLayout()).apply {
        add(formColumn(), BorderLayout.EAST)
        add(listColumn(), BorderLayout.CENTER)
    }

    private fun refresh() {
        rebuildList()
        rebuildForm()
    }

    // ---- 左栏 ----

    private fun listColumn(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(14, HOOK_LIST_PADDING_H)
        preferredSize = Dimension(JBUI.scale(HOOK_LIST_WIDTH), 0)
        add(sectionTitle(".claude/settings.json"))
        add(listSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        })
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(actionLabel(ADD_HOOK_LABEL) {
            // 先进编辑态、**不落库**：命令还是空的，写出去会在文件里留一条跑不起来的
            // hook（CLI 会拿一条空命令去执行）—— 那比不写更糟
            editing = HookRule()
            editingIndex = -1
            refresh()
        })
        add(Box.createVerticalGlue())
    }

    private fun sectionTitle(text: String): JComponent = row(JBLabel(text).apply {
        foreground = UIUtil.getLabelForeground()
    }).apply { border = JBUI.Borders.emptyBottom(6) }

    private fun hint(text: String): JComponent = row(JBLabel(text).apply {
        foreground = UIUtil.getInactiveTextColor()
    })

    /** 把一个标签钉在左栏左边。理由同 MCP 页：`alignmentX` 在 BoxLayout 里不听使唤。 */
    private fun row(label: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(label, BorderLayout.WEST)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun rebuildList() {
        listSlot.removeAll()
        when {
            baseDir == null -> listSlot.add(hint("拿不到项目目录，改不了"))
            config.rules.isEmpty() && editingIndex < 0 -> listSlot.add(hint("这里还没有 hook"))
        }
        config.rules.forEachIndexed { index, rule ->
            listSlot.add(ruleRow(rule, isEditing = index == editingIndex))
        }
        val others = config.preservedEvents.size + config.preservedMatchers.size
        if (others > 0) {
            listSlot.add(Box.createVerticalStrut(JBUI.scale(10)))
            // 如实报出来。不报的话，"我原来配的那些哪去了"是必然会被问的问题
            listSlot.add(hint("另有 $others 处不归这个面板管，原样保留"))
        }
        listSlot.revalidate()
        listSlot.repaint()
    }

    private fun ruleRow(rule: HookRule, isEditing: Boolean): JComponent {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = if (isEditing) UIUtil.getListSelectionBackground(true) else UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(6, 9)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(JBLabel(rule.event).apply { foreground = UIUtil.getLabelForeground() }, BorderLayout.WEST)
            add(
                JBLabel(rule.matcher.ifBlank { "全部" }).apply { foreground = UIUtil.getInactiveTextColor() },
                BorderLayout.EAST,
            )
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    editing = rule
                    editingIndex = config.rules.indexOf(rule)
                    refresh()
                }
            })
        }
        if (rule.command.isNotBlank()) row.toolTipText = rule.command
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    // ---- 右栏 ----

    private fun formColumn(): JComponent = JPanel(BorderLayout()).apply {
        border = formColumnBorder(JBUI.Borders.empty(14, HOOK_FORM_PADDING_H))
        preferredSize = Dimension(JBUI.scale(HOOK_FORM_WIDTH), 0)
        add(formSlot, BorderLayout.NORTH)
    }

    private fun rebuildForm() {
        formSlot.removeAll()
        formSlot.layout = BoxLayout(formSlot, BoxLayout.Y_AXIS)

        val editingRule = editing
        if (editingRule == null) {
            formSlot.add(hint("在左边选一条 hook，或点「$ADD_HOOK_LABEL」"))
            formSlot.revalidate()
            formSlot.repaint()
            return
        }

        val event = ComboBox(MANAGED_HOOK_EVENTS.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { it }
            selectedItem = editingRule.event
        }
        val matcher = JBTextField(editingRule.matcher)
        val commandArea = JBTextArea(editingRule.command).apply {
            lineWrap = true
            wrapStyleWord = true
            rows = 2
        }
        val timeout = JBTextField(if (editingRule.timeout > 0) editingRule.timeout.toString() else "")
        val statusMessage = JBTextField(editingRule.statusMessage)

        fun collect(): HookRule = editingRule.copy(
            event = event.selectedItem as String,
            matcher = matcher.text.trim(),
            command = commandArea.text.trim(),
            timeout = timeout.text.trim().toIntOrNull() ?: 0,
            statusMessage = statusMessage.text.trim(),
        )

        fun save() {
            if (baseDir == null) return
            val next = collect()
            // 命令是空的就不落库 —— 写出去等于在文件里留一条每次都会失败的 hook
            if (next.command.isEmpty()) {
                editing = next
                return
            }
            val rules = config.rules.toMutableList()
            if (editingIndex >= 0) {
                rules[editingIndex] = next
            } else {
                rules += next
                editingIndex = rules.lastIndex
            }
            writeConfig(rules)
            editing = next
            rebuildList()
        }

        listOf(matcher, timeout, statusMessage).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = save()
            })
        }
        commandArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = save()
        })
        event.addActionListener { save() }

        formSlot.add(labeledField("事件", event))
        formSlot.add(labeledField("工具匹配（留空 = 全部）", matcher))
        formSlot.add(
            labeledField(
                "命令",
                JBScrollPane(commandArea).apply {
                    preferredSize = Dimension(JBUI.scale(HOOK_FORM_CONTENT_WIDTH), JBUI.scale(52))
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                },
            ),
        )
        formSlot.add(labeledField("超时（秒，留空 = 不限制）", timeout))
        formSlot.add(labeledField("状态文字（运行时的提示语）", statusMessage))

        formSlot.add(
            wrappedHint(
                // 纯文本，别在这里用 markdown 的星号（MCP 页踩过一次）
                "改动写进项目的 .claude/settings.json —— 这份文件 CLI 也读，" +
                    "可以提交给团队。拦截类 hook（PreToolUse）在转写区里会显示成" +
                    "「一条失败的工具结果」，并带上命令写到 stderr 的那句话。",
                JBUI.scale(HOOK_FORM_CONTENT_WIDTH),
            )
        )

        formSlot.add(Box.createVerticalStrut(JBUI.scale(10)))
        formSlot.add(JBLabel("删除").apply {
            foreground = UIUtil.getErrorForeground()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (editingIndex >= 0) {
                        val rules = config.rules.toMutableList()
                        rules.removeAt(editingIndex)
                        writeConfig(rules)
                    }
                    editing = null
                    editingIndex = -1
                    refresh()
                }
            })
        })

        formSlot.revalidate()
        formSlot.repaint()
    }

    /** 把整份文件重新写一遍：只换 `hooks` 里归我们管的那几个事件。 */
    private fun writeConfig(rules: List<HookRule>) {
        val path = filePath() ?: return
        ProjectJson.write(path, withHooks(root, rules, config.preservedEvents, config.preservedMatchers))
        config = HooksConfig(rules, config.preservedEvents, config.preservedMatchers)
    }
}
