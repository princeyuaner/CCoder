package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.intellij.ui.DocumentAdapter
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
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** 列表栏底部那个新建按钮。空态文案会引用它，所以别在别处抄字面量。 */
internal val ADD_PRESET_LABEL: String get() = CcoderText.text("settings.presets.addPreset")

/** 列表栏宽度。它放 CENTER，所以这是"理想宽度" —— 页窄了它会自己收。 */
internal const val PRESET_LIST_WIDTH = 200

/** 表单栏宽度。它放 EAST，是确定值，[PRESET_FORM_CONTENT_WIDTH] 由它推出来。 */
internal const val PRESET_FORM_WIDTH = PAGE_WIDTH - PRESET_LIST_WIDTH

private const val LIST_PADDING_H = 12
private const val FORM_PADDING_H = 16

/** 表单内容宽度：栏宽减去左右内边距。内容框与提示语都按它摆。 */
// 卡片式改版后，卡内可用的宽度还要扣掉卡片两侧的缝与描边那 2px
private const val PRESET_FORM_CONTENT_WIDTH =
    PRESET_FORM_WIDTH - 2 * CARD_INSET - CARD_BORDER_W - 2 * FORM_PADDING_H

/** 内容框先长这么高（约八行），再多就滚。 */
private const val CONTENT_ROWS = 8

/**
 * 预置 prompt 页。
 *
 * 两栏（左列表 / 右表单）与模型页同一个形状 —— 但**只有两个字段**，
 * 所以没有模型页那套"一行一个模型 ID"的复杂度。
 */
internal class PromptPresetsPage(
    private val presets: PromptPresets,
) : SettingsPage {

    override val title: String get() = CcoderText.text("settings.page.presets")

    private val listSlot = JPanel()
    private val formSlot = JPanel()

    /** 正在编辑的那条。**只在本次开框期间有意义** —— 它可能已经过期，见 [reload]。 */
    private var editing: PromptPreset? = null

    /**
     * 右栏那张卡：**建一次**（它要活在刷新之外，否则每打一个字就换一副卡头），
     * 卡头（正在编辑哪条）与卡脚（删除）随 [rebuildForm] 换。
     */
    private val formCard = settingsCard(null, formBody())

    private fun formBody(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, FORM_PADDING_H)
        add(formSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }, BorderLayout.NORTH)
    }

    private val deleteLabel = JBLabel(DELETE_LABEL).apply {
        foreground = UIUtil.getErrorForeground()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private var built: JComponent? = null

    override fun component(): JComponent = built ?: build().also {
        built = it
        refresh()
    }

    override fun reload() {
        // 服务是唯一真相：按 id 重新取一次，免得拿着一份已经过期的副本继续编辑
        editing = editing?.let { e -> presets.presets().firstOrNull { it.id == e.id } }
        refresh()
    }

    private fun build(): JComponent = JPanel(BorderLayout()).apply {
        // 表单栏放 EAST：`BorderLayout` 把多余宽度**全给 CENTER**，所以只有放
        // WEST/EAST 的那一栏拿的是确定的首选宽度。表单栏必须确定 ——
        // [PRESET_FORM_CONTENT_WIDTH] 是内容框的宽度，它是个变量的话整个表单会随对话框漂。
        // 列表栏放 CENTER 则更稳：页窄了它自己变窄，而不是两栏重叠
        add(formColumn(), BorderLayout.EAST)
        add(listColumn(), BorderLayout.CENTER)
    }

    /**
     * 两栏整体重建。
     *
     * 与模型页同理由：条目很少，重建比增量同步可靠 —— 增量同步要处理
     * "删掉当前项之后表单显示什么"这类边界，正是 bug 的温床。
     */
    private fun refresh() {
        rebuildList()
        rebuildForm()
    }

    private fun listColumn(): JComponent {
        listSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 6)
            add(listSlot)
        }
        // 卡头留空：这一栏装的既然是"预设列表"，标题也只是把页签那两个字再写一遍
        return settingsCardColumn(
            settingsCard(null, body, footer = addPresetLabel()),
            width = PRESET_LIST_WIDTH,
        )
    }

    private fun addPresetLabel(): JComponent = actionLabel(ADD_PRESET_LABEL) {
        // 先进编辑态、**不落库**：空条目会在读盘时被滤掉（isBlankPromptPreset），
        // 所以这一步 upsert 等于什么都没加，反而会在列表里留一行空白
        editing = PromptPreset()
        refresh()
    }

    private fun formColumn(): JComponent = settingsCardColumn(formCard, width = PRESET_FORM_WIDTH)

    private fun rebuildList() {
        listSlot.removeAll()
        presets.presets().forEach { preset ->
            listSlot.add(presetRow(preset, isEditing = preset.id == editing?.id))
        }
        listSlot.revalidate()
        listSlot.repaint()
    }

    private fun presetRow(preset: PromptPreset, isEditing: Boolean): JComponent {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = true
            // 未选中的那行底色取卡底（同模型页那条：取面板底会在卡上留一条浅方条）
            background = if (isEditing) UIUtil.getListSelectionBackground(true) else cardFill()
            border = JBUI.Borders.empty(6, 9)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(
                JBLabel(preset.name).apply { foreground = UIUtil.getLabelForeground() },
                BorderLayout.CENTER,
            )
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    editing = preset
                    refresh()
                }
            })
        }
        // 必须**加完子件之后**再量：不设这一条背景就只裹住文字那一段，
        // 而不是整行 —— BoxLayout 只把子件排到它的首选宽度，除非 max 允许它长
        // （模型页实测 134px vs 整行 240px）
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    private fun rebuildForm() {
        formSlot.removeAll()
        formSlot.layout = BoxLayout(formSlot, BoxLayout.Y_AXIS)
        formCard.setFooter(null)

        val p = editing
        if (p == null) {
            formCard.setTitle(null)
            // 空态指的是**列表栏**那个按钮：空态下表单里什么都没有，
            // 指着表单里的东西说等于让人去找一个不在屏幕上的按钮
            formSlot.add(
                JBLabel(CcoderText.text("settings.presets.formEmpty", ADD_PRESET_LABEL)).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            )
            formSlot.revalidate()
            formSlot.repaint()
            return
        }

        val name = JBTextField(p.name)
        val content = JBTextArea(p.content).apply {
            lineWrap = true
            wrapStyleWord = true
            rows = CONTENT_ROWS
        }

        fun collect(): PromptPreset = p.copy(name = name.text, content = content.text)

        fun save() {
            val next = collect()
            val nameChanged = next.name.trim() != editing?.name
            editing = next
            presets.upsert(next)
            // 只在"列表上那行字"真的变了时才重建列表。内容框里打字时列表不用动 ——
            // 每敲一个字重建一次纯属白费。模型页那边没有多行内容框，所以没这一层
            if (nameChanged || next.name.isBlank() != next.content.isBlank()) rebuildList()
        }

        // 内容框**不监听 document 的每一次改动**去重建表单：那会把光标位置与选区丢掉。
        // 两个框都只走 save()，save() 里最多重建左栏
        listOf(name, content).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = save()
            })
        }

        formSlot.add(labeledField(CcoderText.text("settings.presets.field.name"), name))
        formSlot.add(
            labeledField(
                CcoderText.text("settings.presets.field.content"),
                contentBox(content),
            )
        )
        formSlot.add(
            wrappedHint(
                // 说的是**实际行为**：预置走补全的采纳路径，那段 /… 会被替换掉。
                // 别写成"追加到末尾" —— 那是 spec 最初的设想（对齐"添加选区"），
                // 但补全这条路根本没有追加这回事，照着写就是让界面撒谎
                CcoderText.text("settings.presets.formHint"),
                JBUI.scale(PRESET_FORM_CONTENT_WIDTH),
            )
        )

        // 删除进**卡脚**（2026-09-20 卡片式改版），与"关闭"分开 ——
        // 它和"改两个字"不是一类动作
        formCard.setTitle(p.name)
        formCard.setFooter(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(deleteLabel, BorderLayout.WEST)
            }.apply {
                deleteLabel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        presets.remove(p.id)
                        editing = null
                        refresh()
                    }
                })
            },
        )

        formSlot.revalidate()
        formSlot.repaint()
    }

    /**
     * 内容框的滚动壳。
     *
     * 高度要**封顶**：`JTextArea` 的 preferredSize 随内容行数长，不封的话
     * 一条长 prompt 会把整张表单顶出对话框（模型页那个"模型 ID"滚动框同理）。
     */
    private fun contentBox(area: JBTextArea): JComponent = JBScrollPane(area).apply {
        preferredSize = Dimension(JBUI.scale(PRESET_FORM_CONTENT_WIDTH), JBUI.scale(CONTENT_ROWS * 16))
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
}
