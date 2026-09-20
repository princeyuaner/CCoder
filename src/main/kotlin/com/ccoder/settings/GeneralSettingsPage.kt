package com.ccoder.settings

import com.ccoder.text.CcoderText
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
internal val BROWSE_LABEL: String get() = CcoderText.text("settings.general.browse")

/** 「claude 可执行文件」那一栏的标签。用例要按它找控件。 */
internal val CLAUDE_PATH_LABEL: String get() = CcoderText.text("settings.general.claudePath")

/** 「兜底模型名」那一栏的标签。 */
internal val FALLBACK_MODEL_LABEL: String get() = CcoderText.text("settings.general.fallbackModel")

/** 「发送快捷键」那一栏的标签。 */
internal val SEND_SHORTCUT_LABEL: String get() = CcoderText.text("settings.general.sendShortcut")

/** 「思考深度」那一栏的标签。 */
internal val EFFORT_LABEL: String get() = CcoderText.text("settings.general.effort")

/**
 * 「界面语言」那一栏的标签与提示。
 *
 * 这两条**从词表取**（不是 `const val`）：它们本身就是要被翻译的东西 ——
 * 一个 `const` 只能在编译期钉死中文。Phase 3 起，这一页其余的标签也照这个形状改。
 */
internal val LANGUAGE_LABEL: String get() = CcoderText.text("settings.language.label")
internal val LANGUAGE_HINT: String get() = CcoderText.text("settings.language.hint")

/**
 * 通用页：claude 可执行文件 · 兜底模型名 · 发送快捷键 · 思考深度 · 界面语言。
 *
 * 前四项都是**控件一变就写** `ClaudeSettings`（各页统一的即时保存，见 [SettingsDialog]）。
 * 内容照搬 2026-09-15 删掉的那个 `ClaudeSettingsPanel` —— 那些灰色提示语是那次
 * 唯一"已经调好的东西"，逐字留着。
 *
 * 「界面语言」写的是另一个服务（[UiLanguageSettings] 是 APP 级，这一页别的项是项目级），
 * 由调用方注入 —— 同 [SettingsDialog] 那条"服务由调用方注入"的道理：探针要在
 * 没有 Application 的纯 JVM 里跑。
 */
internal class GeneralSettingsPage(
    /** 文件选择器要有项目父窗口。探针里给 null —— 只是挂监听器，点按钮时才建文件框。 */
    private val project: Project?,
    private val settings: ClaudeSettings,
    private val language: UiLanguageSettings,
) : SettingsPage {

    override val title: String get() = CcoderText.text("settings.page.general")

    private val pathField = JBTextField()
    private val fallbackModel = JBTextField()
    private val shortcutBox = ComboBox(SendShortcut.entries.toTypedArray())
    private val effortBox = ComboBox(EffortSetting.entries.toTypedArray())
    private val languageBox = ComboBox(UiLanguage.entries.toTypedArray())

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
        languageBox.addActionListener { save() }

        val column = settingsColumn().apply {
            add(labeledField(CLAUDE_PATH_LABEL, pathRow()))
            add(wrappedHint(CcoderText.text("settings.general.claudePathHint"), PAGE_CONTENT_WIDTH))
            add(labeledField(FALLBACK_MODEL_LABEL, fallbackModel))
            add(
                wrappedHint(
                    CcoderText.text("settings.general.fallbackModelHint"),
                    PAGE_CONTENT_WIDTH,
                )
            )
            add(labeledField(SEND_SHORTCUT_LABEL, shortcutBox))
            add(labeledField(EFFORT_LABEL, effortBox))
            add(
                wrappedHint(
                    // 括号那半句照中文的写法分开写：英文里两个档位名要各自加引号，
                    // 拼在同一句里读起来是"「极高」「最大」"那种堆叠
                    CcoderText.text("settings.general.effortHint"),
                    PAGE_CONTENT_WIDTH,
                )
            )
            add(labeledField(LANGUAGE_LABEL, languageBox))
            add(wrappedHint(LANGUAGE_HINT, PAGE_CONTENT_WIDTH))
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
            languageBox.selectedItem = language.language
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
        // 写下去就生效：`UiLanguageSettings` 的 setter 会把选择推给词表层，
        // 并通知界面上的东西重译（面板那一遍见 `ClaudePanel.retranslate`）。
        // 这里不需要再做什么 —— 设置页只负责"把用户选的写进去"。
        language.language = languageBox.selectedItem as UiLanguage
    }
}
