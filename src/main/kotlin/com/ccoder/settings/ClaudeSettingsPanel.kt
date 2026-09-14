package com.ccoder.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

class ClaudeSettingsPanel(private val project: Project) : Configurable {

    private val claudePathField = TextFieldWithBrowseButton()
    private val modelField = JBTextField()
    private val permissionModeBox = ComboBox(PermissionModeSetting.entries.toTypedArray())
    private val effortBox = ComboBox(EffortSetting.entries.toTypedArray())
    private val sendShortcutBox = ComboBox(SendShortcut.entries.toTypedArray())
    private val dangerousOptIn = JBCheckBox("我明白风险：该模式下 Claude 的所有操作都不再询问")
    private val reminderField = JBTextField()
    private val extraDirsModel = DefaultTableModel(arrayOf("额外目录"), 0)
    private val envModel = DefaultTableModel(arrayOf("变量名", "值"), 0)

    override fun getDisplayName(): String = "CCoder"

    override fun createComponent(): JComponent {
        claudePathField.addBrowseFolderListener(
            project, FileChooserDescriptorFactory.createSingleFileDescriptor()
        )

        permissionModeBox.addActionListener { updateDangerousVisibility() }
        dangerousOptIn.isVisible = false

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("claude 可执行文件："), claudePathField)
            .addComponentToRightColumn(hint("留空则从 PATH 自动解析"))
            .addLabeledComponent(JBLabel("模型："), modelField)
            .addComponentToRightColumn(hint("留空则使用 CLI 自身配置的模型"))
            .addLabeledComponent(JBLabel("权限模式："), permissionModeBox)
            .addComponentToRightColumn(dangerousOptIn)
            .addLabeledComponent(JBLabel("思考深度："), effortBox)
            .addComponentToRightColumn(
                hint("输入框左下角也能随时改（改了立刻生效，不用重开会话）；「极高」「最大」分模型，其余模型上会降级")
            )
            .addLabeledComponent(JBLabel("发送快捷键："), sendShortcutBox)
            .addComponentToRightColumn(
                hint("ENTER = Enter 发送 / Shift+Enter 换行；CTRL_ENTER = Enter 换行 / Ctrl+Enter 发送")
            )
            .addLabeledComponent(JBLabel("待决提醒阈值（秒）："), reminderField)
            .addComponentToRightColumn(hint("Claude 等待授权超过该时长后升级为通知提醒（spec §6.3）"))
            .addLabeledComponent(
                JBLabel("额外目录："),
                ToolbarDecorator.createDecorator(JBTable(extraDirsModel)).createPanel()
            )
            .addLabeledComponent(
                JBLabel("环境变量："),
                ToolbarDecorator.createDecorator(JBTable(envModel)).createPanel()
            )
            .addComponentToRightColumn(
                hint("宿主隔离黑名单中的变量无法通过此处覆盖（设计文档 §3.2）")
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }
    }

    private fun hint(text: String) = JBLabel(text).apply {
        foreground = com.intellij.util.ui.UIUtil.getInactiveTextColor()
        border = JBUI.Borders.emptyLeft(4)
    }

    private fun updateDangerousVisibility() {
        val mode = permissionModeBox.selectedItem as? PermissionModeSetting
        dangerousOptIn.isVisible = mode?.requiresDangerousOptIn == true
    }

    override fun isModified(): Boolean {
        val s = ClaudeSettings.getInstance(project)
        return s.claudePath != claudePathField.text.trim() ||
            s.model != modelField.text.trim() ||
            s.permissionMode != permissionModeBox.selectedItem ||
            s.effort != effortBox.selectedItem ||
            s.sendShortcut != sendShortcutBox.selectedItem ||
            s.pendingReminderSeconds != (reminderField.text.trim().toIntOrNull() ?: 30) ||
            s.extraDirs != extraDirsModel.readColumn(0) ||
            s.envOverrides != envModel.readPairs()
    }

    override fun apply() {
        // 复选框真正参与决定。改之前它是个摆设：这里从头到尾没读过
        // isSelected，勾不勾都照写 bypassPermissions —— "我明白风险"
        // 那个框只是安慰剂
        val effective = effectivePermissionMode(
            permissionModeBox.selectedItem as PermissionModeSetting,
            dangerousOptIn.isSelected,
        )

        ClaudeSettings.getInstance(project).apply {
            claudePath = claudePathField.text.trim()
            model = modelField.text.trim()
            permissionMode = effective
            effort = effortBox.selectedItem as EffortSetting
            sendShortcut = sendShortcutBox.selectedItem as SendShortcut
            pendingReminderSeconds = reminderField.text.trim().toIntOrNull() ?: 30
            extraDirs = extraDirsModel.readColumn(0).toMutableList()
            envOverrides = envModel.readPairs().toMutableMap()
        }

        // 被降级说明用户没勾确认。界面得如实显示降级后的结果，
        // 否则会留下"显示着绕过、实际是默认"的错位
        if (effective != permissionModeBox.selectedItem) {
            permissionModeBox.selectedItem = effective
            updateDangerousVisibility()
        }
    }

    override fun reset() {
        val s = ClaudeSettings.getInstance(project)
        claudePathField.text = s.claudePath
        modelField.text = s.model
        permissionModeBox.selectedItem = s.permissionMode
        // 存着绕过模式就说明当初确认过（没勾的话 apply 会把它降级掉），
        // 所以复位时把勾也还原上
        dangerousOptIn.isSelected = s.permissionMode.requiresDangerousOptIn
        sendShortcutBox.selectedItem = s.sendShortcut
        effortBox.selectedItem = s.effort
        reminderField.text = s.pendingReminderSeconds.toString()
        updateDangerousVisibility()

        extraDirsModel.rowCount = 0
        s.extraDirs.forEach { extraDirsModel.addRow(arrayOf<Any>(it)) }

        envModel.rowCount = 0
        s.envOverrides.forEach { (k, v) -> envModel.addRow(arrayOf<Any>(k, v)) }
    }

    override fun disposeUIResources() = Unit

    private fun DefaultTableModel.readColumn(col: Int): List<String> =
        (0 until rowCount)
            .mapNotNull { getValueAt(it, col)?.toString()?.trim() }
            .filter { it.isNotEmpty() }

    private fun DefaultTableModel.readPairs(): Map<String, String> =
        (0 until rowCount)
            .mapNotNull { row ->
                val key = getValueAt(row, 0)?.toString()?.trim().orEmpty()
                val value = getValueAt(row, 1)?.toString()?.trim().orEmpty()
                if (key.isEmpty()) null else key to value
            }
            .toMap()
}
