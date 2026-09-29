package com.ccoder.settings

import com.ccoder.text.CcoderText
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
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** 表单栏的宽度（对话框内容区）。预览那块按它折行。 */
internal const val MCP_FORM_WIDTH = 520

/** 多行那几栏先长这么高（约三行），再多就滚。 */
private const val SMALL_BOX_HEIGHT = 62

/**
 * 新增 / 编辑 MCP server 的**表单体**（对话框只是它的壳）。
 *
 * 为什么拆出来：`DialogWrapper` 在无头测试里开不起来（`show()` 要一个真的窗口），
 * 而这张表单要验的东西不少 —— 形状切了哪几栏可见、校验怎么说、预览是不是**真会写出去的那段**。
 * 同 `TaskOutputContent` / `buildContextDetailPanel` 那条路：**框体可测，壳只负责弹**。
 *
 * ## 预览为什么值得占一块地方
 *
 * 这个动作的产物是**要提交进仓库的文件**（`.mcp.json`）。预览用的是 [ProjectJson.format] ——
 * 与真写文件时**同一个序列化器**，所以"预览里没有的字段，写出去也没有"这句话是成立的
 * （空 `args` / 空 `env` 不写进文件那条规矩，用户在预览里直接看得见）。
 *
 * @param initial 编辑时是那一条；新增时是 `McpServer()`（除形状外全空）。
 * @param others 除自己以外现有的名字（重名校验用）。
 */
internal class McpServerForm(
    initial: McpServer,
    private val others: List<String>,
) {

    val nameField = JBTextField(initial.name)
    val kindBox = ComboBox(McpKind.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
        selectedItem = initial.mcpKind()
    }
    val commandField = JBTextField(initial.command)
    val argsArea = textArea(textOfArgs(initial.args))
    val envArea = textArea(textOfPairs(initial.env))
    val urlField = JBTextField(initial.url)
    val headersArea = textArea(textOfPairs(initial.headers))

    /** 预览那块：只读、等宽。 */
    val previewArea = JBTextArea().apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
        foreground = UIUtil.getInactiveTextColor()
        background = UIUtil.getTextFieldBackground()
    }

    /** 每次可能影响结果的变化都喊它（弹窗拿它刷预览与 OK 的可用性）。 */
    var onChange: (() -> Unit)? = null

    private val errorLabel = JBLabel().apply {
        foreground = UIUtil.getErrorForeground()
        isVisible = false
    }

    private val commandLabel = labeledField(CcoderText.text("settings.mcp.field.command"), commandField)
    private val argsLabel = labeledField(CcoderText.text("settings.mcp.field.args"), box(argsArea))
    private val envLabel = labeledField(CcoderText.text("settings.mcp.field.env"), box(envArea))
    private val urlLabel = labeledField(CcoderText.text("settings.mcp.field.url"), urlField)
    private val headersLabel = labeledField(CcoderText.text("settings.mcp.field.headers"), box(headersArea))

    private val built: JComponent = build()

    fun component(): JComponent = built

    /** 读回一条。**原样**（trim 与过滤交给 [McpServer.toMcpJson] 那一层）。 */
    fun collect(): McpServer = McpServer(
        name = nameField.text.trim(),
        kind = (kindBox.selectedItem as McpKind).name,
        command = commandField.text.trim(),
        args = argsOf(argsArea.text),
        env = pairsOf(envArea.text),
        url = urlField.text.trim(),
        headers = pairsOf(headersArea.text),
    )

    /** 现在能不能提交；能就是 null。 */
    fun error(): String? = mcpFormError(collect(), others)

    /**
     * 将要写进 `.mcp.json` 的那一段（**与真写文件同一条序列化**）。
     *
     * 外面套上名字那一层：`{ "名字": { … } }` —— 名字就是 JSON 的键，用户得看见这件事。
     */
    fun preview(): String {
        val server = collect()
        val one = JsonObject().apply { add(server.name, server.toMcpJson()) }
        return ProjectJson.format(one).trimEnd()
    }

    /** 把校验结果画出来（null = 没有问题，那一行藏起来）。 */
    fun showError(message: String?) {
        errorLabel.text = message.orEmpty()
        errorLabel.isVisible = message != null
    }

    private fun build(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 2)
        alignmentX = Component.LEFT_ALIGNMENT

        add(labeledField(CcoderText.text("settings.mcp.field.name"), nameField))
        add(labeledField(CcoderText.text("settings.mcp.field.kind"), kindBox))
        add(commandLabel)
        add(argsLabel)
        add(envLabel)
        add(urlLabel)
        add(headersLabel)
        add(errorLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })

        // 预览：**不折行塞进输入框那套**，单独一段 + 一句标签
        add(JBLabel(CcoderText.text("settings.mcp.dialog.preview")).apply {
            foreground = UIUtil.getInactiveTextColor()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 0, 4, 0)
        })
        add(JBScrollPane(previewArea).apply {
            preferredSize = Dimension(JBUI.scale(MCP_FORM_WIDTH), JBUI.scale(84))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(84))
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(2)
        })
        // 生效时机那句：预览说的是"写什么"，这一句说的是"什么时候算数"
        add(JBLabel(CcoderText.text("settings.mcp.formHint")).apply {
            foreground = UIUtil.getInactiveTextColor()
            alignmentX = Component.LEFT_ALIGNMENT
        })

        // 形状决定哪几栏可见。**不重建表单**（重建会丢光标与选区），只切可见性 ——
        // 所以这里存的必须是**已经加进容器的那几个**，不是现造一批同款。
        fun applyKind() {
            val stdio = (kindBox.selectedItem as McpKind) == McpKind.STDIO
            listOf(commandLabel, argsLabel, envLabel).forEach { it.isVisible = stdio }
            listOf(urlLabel, headersLabel).forEach { it.isVisible = !stdio }
            revalidate()
            repaint()
        }
        applyKind()

        // 显式标类型：`onChange?.invoke()` 的类型是 `Unit?`，直接写在 `= notify()` 那种
        // 单表达式里会让重写的方法返回 `Unit?`（编译不过，2026-09-30 踩过）
        val notify: () -> Unit = { onChange?.invoke() }
        listOf(nameField, commandField, urlField).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = notify()
            })
        }
        listOf(argsArea, envArea, headersArea).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = notify()
            })
        }
        kindBox.addActionListener { applyKind(); notify() }

        // 先摆一次（面板构造完就有内容，探针不用额外调一次）
        refreshPreview()
    }

    /** 刷新预览。弹窗在每次变化时调它（也顺手把校验重判一次）。 */
    fun refreshPreview() {
        previewArea.text = preview()
        previewArea.caretPosition = 0
    }

    private fun textArea(text: String): JBTextArea = JBTextArea(text).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 3
    }

    /** 多行框外面的滚动壳。高度**封顶**：不封的话一条长参数会把框顶出屏幕。 */
    private fun box(area: JBTextArea): JComponent = JBScrollPane(area).apply {
        preferredSize = Dimension(JBUI.scale(MCP_FORM_WIDTH - 20), JBUI.scale(SMALL_BOX_HEIGHT))
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        alignmentX = Component.LEFT_ALIGNMENT
    }
}
