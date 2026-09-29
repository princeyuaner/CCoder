package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.JComponent

/**
 * 打开新增 / 编辑框。生产路径给 [McpSettingsPage] 用。
 *
 * 无头测试与渲染探针**不该调它**（`show()` 要真的窗口）—— 直接拿 [McpServerForm]。
 */
internal fun showMcpServerDialog(
    project: Project?,
    initial: McpServer,
    others: List<String>,
    isNew: Boolean,
    onSubmit: (McpServer) -> Unit,
) = McpServerDialog(project, McpServerForm(initial, others), isNew, onSubmit).show()

/**
 * 新增 / 编辑 MCP server（选型稿 `docs/design/mcp-page.html` 方案一那张框）。
 *
 * **一套表单两处用**：新增与编辑只差标题、按钮文案与那几个"除自己以外"的名字。
 * 正文在 [McpServerForm] 里（可测的那一半），这里只管：标题、两颗按钮的文案、
 * 校验结果画在哪、以及**校验不过时 OK 置灰**。
 *
 * 置灰而不是"点了没反应"：后者从界面上看不出为什么（这项目里为这件事栽过好几轮，
 * 见 `AddFilesToChatAction` 的注释）。
 */
internal class McpServerDialog(
    project: Project?,
    /** 探针够得着（同 `TaskOutputDialog.content` 那条：`createCenterPanel` 是 protected）。 */
    internal val form: McpServerForm,
    private val isNew: Boolean,
    private val onSubmit: (McpServer) -> Unit,
) : DialogWrapper(project) {

    init {
        title = CcoderText.text(
            if (isNew) "settings.mcp.dialog.title.new" else "settings.mcp.dialog.title.edit",
        )
        isResizable = true
        init()
        // 两颗按钮的文案也走我们自己的词表 —— 平台那套只认 IDE 语言，认不了设置里
        // 那个「界面语言」（两条腿各干一半，见 `CcoderText` 的注释）
        setOKButtonText(CcoderText.text(if (isNew) "settings.mcp.dialog.add" else "common.save"))
        setCancelButtonText(CcoderText.text("common.cancel"))
        form.onChange = { refresh() }
        refresh()
    }

    override fun createCenterPanel(): JComponent = form.component()

    override fun getPreferredFocusedComponent() = form.nameField

    override fun doOKAction() {
        // 保险丝：按钮已经置灰了，但**置灰的控件照样收得到点击**（这个项目里
        // 栽过一次，见 `RuntimeDepsSection.setActionEnabled` 的注释）
        if (form.error() != null) return
        onSubmit(form.collect())
        super.doOKAction()
    }

    private fun refresh() {
        val message = form.error()
        form.showError(message)
        form.refreshPreview()
        setOKActionEnabled(message == null)
    }
}
