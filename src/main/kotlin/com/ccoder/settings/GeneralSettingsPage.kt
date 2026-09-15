package com.ccoder.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** 那个「浏览…」动作。用例按它点。 */
internal const val BROWSE_LABEL = "浏览…"

/** 「claude 可执行文件」那一栏的标签。用例要按它找控件。 */
internal const val CLAUDE_PATH_LABEL = "claude 可执行文件"

/** 「兜底模型名」那一栏的标签。 */
internal const val FALLBACK_MODEL_LABEL = "没有选中配置时用的模型"

/** 「发送快捷键」那一栏的标签。 */
internal const val SEND_SHORTCUT_LABEL = "发送快捷键"

/** 「思考深度」那一栏的标签。 */
internal const val EFFORT_LABEL = "思考深度"

/**
 * 通用页：claude 可执行文件 · 兜底模型名 · 发送快捷键 · 思考深度。
 *
 * 四项都是**控件一变就写** `ClaudeSettings`（四页统一的即时保存，见 [SettingsDialog]）。
 * 内容照搬 2026-09-15 删掉的那个 `ClaudeSettingsPanel` —— 那些灰色提示语是那次
 * 唯一"已经调好的东西"，逐字留着。
 */
internal class GeneralSettingsPage(
    /** 文件选择器要有项目父窗口。探针里给 null —— 只是挂监听器，点按钮时才建文件框。 */
    private val project: Project?,
    private val settings: ClaudeSettings,
) : SettingsPage {

    override val title: String = "通用"

    private val pathField = JBTextField()
    private val fallbackModel = JBTextField()
    private val shortcutBox = ComboBox(SendShortcut.entries.toTypedArray())
    private val effortBox = ComboBox(EffortSetting.entries.toTypedArray())

    private var built: JComponent? = null

    /** reload 期间不写回。理由见 [save]。 */
    private var loading = false

    override fun component(): JComponent = built ?: build().also { built = it }

    /**
     * 路径那一栏：输入框 + 一个自己画的「浏览…」。
     *
     * **不用 `TextFieldWithBrowseButton`**：它那个「…」不是子件，而是
     * `ExtendableTextField` 的一个**内嵌扩展**，位置按字段当时的宽度算 ——
     * 离屏摆版时它落在字段外面（实测 `x=-27`），画出来就是"没有浏览按钮"。
     * 平台组件在真机上大概没问题，但这个项目在"看不见的按钮"上已经付过两次代价
     * （`✕` 那回、项目树右键那回），不值得为省 6 行代码再赌一次。
     * 顺手的好处：它与四页里其它动作（`＋ 添加配置` / `－ 删除选中`）同一个形状。
     */
    private fun pathRow(): JComponent {
        val browse = actionLabel(BROWSE_LABEL) {
            FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor(),
                project,
                null,
            ) { picked -> pathField.text = picked.path }
        }.apply { border = JBUI.Borders.emptyLeft(8) }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(pathField, BorderLayout.CENTER)
            add(browse, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun build(): JComponent {
        pathField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = save()
        })
        fallbackModel.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = save()
        })
        shortcutBox.addActionListener { save() }
        effortBox.addActionListener { save() }

        val column = settingsColumn().apply {
            add(labeledField(CLAUDE_PATH_LABEL, pathRow()))
            add(wrappedHint("留空则从 PATH 自动解析", PAGE_CONTENT_WIDTH))
            add(labeledField(FALLBACK_MODEL_LABEL, fallbackModel))
            add(
                wrappedHint(
                    "只在「模型」页一条配置都没选中时生效；选中了配置就用它的模型",
                    PAGE_CONTENT_WIDTH,
                )
            )
            add(labeledField(SEND_SHORTCUT_LABEL, shortcutBox))
            add(labeledField(EFFORT_LABEL, effortBox))
            add(
                wrappedHint(
                    "输入框左下角也能随时改（改了立刻生效，不用重开会话）；" +
                        "「极高」「最大」分模型，其余模型上会降级",
                    PAGE_CONTENT_WIDTH,
                )
            )
        }
        return settingsPageBody(column).also { reload() }
    }

    override fun reload() {
        loading = true
        try {
            pathField.text = settings.claudePath
            fallbackModel.text = settings.model
            shortcutBox.selectedItem = settings.sendShortcut
            effortBox.selectedItem = settings.effort
        } finally {
            loading = false
        }
    }

    /**
     * 写回。
     *
     * **只在 [loading] 为假时写**：`reload()` 要设 `selectedItem` 与 `text`，
     * 而那两样都会触发监听器 —— 不挡的话"打开一次设置"就等于把用户配置原样重写一遍
     * （在今天那版 IDE 设置页上不存在这个问题，它是"读在 apply"；
     * **改成写通之后这是新引入的坑**）。
     */
    private fun save() {
        if (loading) return
        settings.claudePath = pathField.text.trim()
        settings.model = fallbackModel.text.trim()
        settings.sendShortcut = shortcutBox.selectedItem as SendShortcut
        settings.effort = effortBox.selectedItem as EffortSetting
    }
}
