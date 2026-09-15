package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
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
internal const val ADD_SERVER_LABEL = "＋ 添加 server"

/** 左栏宽度。它放 CENTER，所以这是"理想宽度" —— 页窄了它会自己收。 */
internal const val MCP_LIST_WIDTH = 240

/** 表单栏宽度。放 EAST，是确定值。 */
internal const val MCP_FORM_WIDTH = PAGE_WIDTH - MCP_LIST_WIDTH

private const val MCP_LIST_PADDING_H = 12
private const val MCP_FORM_PADDING_H = 16
private const val MCP_FORM_CONTENT_WIDTH = MCP_FORM_WIDTH - 2 * MCP_FORM_PADDING_H

/** 多行那几栏先长这么高（约三行），再多就滚。 */
private const val SMALL_BOX_HEIGHT = 62

/**
 * MCP 页：**左栏改配置（写 `.mcp.json`）、右栏看当前会话的实时状态**。
 *
 * 两栏是刻意的分工：
 *  - 左栏是**文件**，能增删改，改了要新开会话才生效
 *  - 右栏是**当前会话真的连上了什么**，只读。它包含用户全局配的那些
 *    （探针实测：`scope` 分 `user` / `project`），所以两栏**对不齐是正常的**
 *
 * 页面上有一句提示专门讲这件事，否则"我加的那条右边没有"看起来就是坏了。
 */
internal class McpSettingsPage(
    /** 项目根。null = 拿不到项目目录，这时**只读不写**（别猜一个路径去写）。 */
    private val baseDir: Path?,
    private val status: McpStatus,
) : SettingsPage {

    override val title: String = "MCP"

    private val listSlot = JPanel()
    private val statusSlot = JPanel()
    private val formSlot = JPanel()

    /** 正在编辑的那条。**可能还不在 [config] 里**（新加的、名字还没填）。 */
    private var editing: McpServer? = null

    /** 它在 `config.servers` 里的下标；-1 = 新加的、还没落库。 */
    private var editingIndex: Int = -1

    /** 文件**原样**读进来的那份：写回时按它保序、保别人的键。 */
    private var root: JsonObject = JsonObject()

    private var config: McpConfig = McpConfig(emptyList(), JsonObject())

    private var built: JComponent? = null

    /**
     * 状态是别处推过来的（面板收到回执后写服务）。
     *
     * **不再套一层 `invokeLater`**：服务保证在 EDT 上通知（`McpStatus` 那条），
     * 而且这层包装在纯 JVM 测试里会拿到 null 的 Application 直接炸
     * —— 同 `PendingPermissionCount` 的监听器，同步调。
     */
    private val statusListener: () -> Unit = { refreshStatus() }

    private fun filePath(): Path? = baseDir?.let { mcpJsonPath(it) }

    override fun component(): JComponent = built ?: build().also {
        built = it
        reload()
        status.addListener(statusListener)
    }

    override fun dispose() {
        status.removeListener(statusListener)
    }

    override fun reload() {
        root = filePath()?.let { ProjectJson.read(it) } ?: JsonObject()
        config = mcpServersOf(root)
        // 正在编辑的那条按名字重新取一次：拿一份过期的副本继续编辑，
        // 用户会发现"我改的东西保存时把别的盖回去了"
        editing = editing?.let { e -> config.servers.firstOrNull { it.name == e.name } }
        editingIndex = editing?.let { e -> config.servers.indexOfFirst { it.name == e.name } } ?: -1
        refresh()
    }

    private fun build(): JComponent = JPanel(BorderLayout()).apply {
        // 表单栏放 EAST 拿确定宽度，列表栏放 CENTER —— 同模型页那条理由
        add(formColumn(), BorderLayout.EAST)
        add(listColumn(), BorderLayout.CENTER)
    }

    private fun refresh() {
        rebuildList()
        rebuildForm()
        refreshStatus()
    }

    // ---- 左栏 ----

    private fun listColumn(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(14, MCP_LIST_PADDING_H)
        preferredSize = Dimension(JBUI.scale(MCP_LIST_WIDTH), 0)
        add(sectionTitle(".mcp.json"))
        add(listSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        })
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(actionLabel(ADD_SERVER_LABEL) {
            // 先进编辑态、**不落库**：名字（JSON 的键）还没定，落库会写出一条
            // 名字为空的 server，而读回来时它会被丢掉 —— 用户会以为修改没保存
            editing = McpServer()
            editingIndex = -1
            refresh()
        })
        add(Box.createVerticalStrut(JBUI.scale(18)))
        add(sectionTitle("当前会话"))
        add(statusSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        })
        add(Box.createVerticalGlue())
    }

    /**
     * 一个小标题。
     *
     * **靠左不能指望 `alignmentX`**：实测（渲染探针）在 BoxLayout 里给它
     * `LEFT_ALIGNMENT` + 放开 `maximumSize` 两样都做了，它照样居中。
     * 改用一个 `BorderLayout` 的 WEST 兜住 —— 那个是确定性的，不看子件的脾气。
     */
    private fun sectionTitle(text: String): JComponent = row(JBLabel(text).apply {
        foreground = UIUtil.getLabelForeground()
    }).apply { border = JBUI.Borders.emptyBottom(6) }

    private fun hint(text: String): JComponent = row(JBLabel(text).apply {
        foreground = UIUtil.getInactiveTextColor()
    })

    /** 把一个标签钉在左栏左边。 */
    private fun row(label: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(label, BorderLayout.WEST)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun rebuildList() {
        listSlot.removeAll()
        when {
            baseDir == null -> listSlot.add(hint("拿不到项目目录，改不了"))
            config.servers.isEmpty() && editingIndex < 0 -> listSlot.add(hint("这个文件里还没有 server"))
        }
        config.servers.forEachIndexed { index, server ->
            listSlot.add(serverRow(server, isEditing = index == editingIndex))
        }
        listSlot.revalidate()
        listSlot.repaint()
    }

    private fun serverRow(server: McpServer, isEditing: Boolean): JComponent {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = if (isEditing) UIUtil.getListSelectionBackground(true) else UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(6, 9)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(JBLabel(server.name).apply { foreground = UIUtil.getLabelForeground() }, BorderLayout.CENTER)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    editing = server
                    editingIndex = config.servers.indexOfFirst { it.name == server.name }
                    refresh()
                }
            })
        }
        // 加完子件再量 max —— 不设的话背景只裹住文字那一段（模型页实测过）
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    private fun refreshStatus() {
        statusSlot.removeAll()
        when {
            !status.known -> statusSlot.add(hint("还没拿到 —— 开一次会话后就有"))
            status.servers.isEmpty() -> statusSlot.add(hint("这个会话里一个都没有"))
            else -> status.servers.forEach { statusSlot.add(statusRow(it)) }
        }
        statusSlot.revalidate()
        statusSlot.repaint()
    }

    private fun statusRow(server: McpServerStatus): JComponent {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 9)
            add(JBLabel(server.name).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.WEST)
            add(JBLabel(statusTail(server)).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.EAST)
        }
        // 失败原因放进 tooltip：列表里塞不下，但**必须有地方看见** ——
        // 只说"失败"等于什么都没说
        if (!server.error.isNullOrBlank()) row.toolTipText = server.error
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    // ---- 右栏 ----

    private fun formColumn(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(14, MCP_FORM_PADDING_H)
        preferredSize = Dimension(JBUI.scale(MCP_FORM_WIDTH), 0)
        add(formSlot, BorderLayout.NORTH)
    }

    private fun rebuildForm() {
        formSlot.removeAll()
        formSlot.layout = BoxLayout(formSlot, BoxLayout.Y_AXIS)

        val editingServer = editing
        if (editingServer == null) {
            formSlot.add(hint("在左边选一条 server，或点「$ADD_SERVER_LABEL」"))
            formSlot.revalidate()
            formSlot.repaint()
            return
        }

        val name = JBTextField(editingServer.name)
        val kind = ComboBox(McpKind.entries.toTypedArray()).apply {
            renderer = SimpleListCellRenderer.create("") { it.label }
            selectedItem = editingServer.mcpKind()
        }
        val command = JBTextField(editingServer.command)
        val argsArea = textArea(textOfArgs(editingServer.args))
        val envArea = textArea(textOfPairs(editingServer.env))
        val url = JBTextField(editingServer.url)
        val headersArea = textArea(textOfPairs(editingServer.headers))

        // 形状决定哪几栏可见。**不重建表单**（重建会丢掉光标与选区），
        // 只切可见性 —— 所以这里存的必须是**加进 formSlot 的那几个容器**，
        // 不是现造一批同款（造新的切了也白切）
        val stdioFields = listOf(
            labeledField("命令", command),
            labeledField("参数（一行一个）", box(argsArea)),
            labeledField("环境变量（一行一个 KEY=VALUE）", box(envArea)),
        )
        val remoteFields = listOf(
            labeledField("地址", url),
            labeledField("请求头（一行一个 KEY=VALUE）", box(headersArea)),
        )

        fun applyKind() {
            val stdio = (kind.selectedItem as McpKind) == McpKind.STDIO
            stdioFields.forEach { it.isVisible = stdio }
            remoteFields.forEach { it.isVisible = !stdio }
            formSlot.revalidate()
            formSlot.repaint()
        }

        fun collect(): McpServer = editingServer.copy(
            name = name.text.trim(),
            kind = (kind.selectedItem as McpKind).name,
            command = command.text.trim(),
            args = argsOf(argsArea.text),
            env = pairsOf(envArea.text),
            url = url.text.trim(),
            headers = pairsOf(headersArea.text),
        )

        fun save() {
            if (baseDir == null) return
            val next = collect()
            // 名字（JSON 的键）还没定就不落库 —— 写出去会在文件里留一条空名字的
            // server，而读回来时它会被丢掉
            if (next.name.isEmpty()) {
                editing = next
                return
            }
            val servers = config.servers.toMutableList()
            if (editingIndex >= 0) {
                servers[editingIndex] = next
            } else {
                // 新加的：到这一刻才真正进列表
                servers += next
                editingIndex = servers.lastIndex
            }
            writeConfig(servers)
            editing = next
            rebuildList()
        }

        listOf(name, command, url).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = save()
            })
        }
        listOf(argsArea, envArea, headersArea).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = save()
            })
        }
        kind.addActionListener {
            applyKind()
            save()
        }

        formSlot.add(labeledField("名称", name))
        formSlot.add(labeledField("形状", kind))
        stdioFields.forEach { formSlot.add(it) }
        remoteFields.forEach { formSlot.add(it) }
        applyKind()

        formSlot.add(
            wrappedHint(
                // 别在这里用 markdown 的星号：wrappedHint 是纯文本，会把 ** 原样画出来
                // （渲染探针里看见过一次）
                "改动写进项目根目录的 .mcp.json —— 那是会被提交、CLI 也认的文件。" +
                    "新加或改过的 server 下次开会话才生效；左下那栏是此刻已经连上的，" +
                    "还包含你自己全局配的那些，所以两边对不齐是正常的。",
                JBUI.scale(MCP_FORM_CONTENT_WIDTH),
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
                        val servers = config.servers.toMutableList()
                        servers.removeAt(editingIndex)
                        writeConfig(servers)
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

    /** 把整份文件重新写一遍：只换 `mcpServers`，别人的键一个字节都不动。 */
    private fun writeConfig(servers: List<McpServer>) {
        val path = filePath() ?: return
        ProjectJson.write(path, withMcpServers(root, servers, config.preserved))
        config = McpConfig(servers, config.preserved)
    }

    private fun textArea(text: String): JBTextArea = JBTextArea(text).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 3
    }

    /** 多行框外面的滚动壳。高度**封顶**：不封的话一条长参数会把表单顶出对话框。 */
    private fun box(area: JBTextArea): JComponent = JBScrollPane(area).apply {
        preferredSize = Dimension(JBUI.scale(MCP_FORM_CONTENT_WIDTH), JBUI.scale(SMALL_BOX_HEIGHT))
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
}

/**
 * 状态说成人话。
 *
 * **认不出的照原样显示**，不编一个词：CLI 将来加一档时，用户至少能看见
 * 那个原始值，而不是一句编出来的"未知"（那会让人以为是我们坏了）。
 */
private fun statusText(status: String): String = when (status) {
    "connected" -> "已连接"
    "failed" -> "失败"
    "needs-auth" -> "要认证"
    "pending" -> "连接中"
    "disabled" -> "已禁用"
    else -> status
}

/** 一行状态：连上的报工具数，没连上的只报状态（原因在 tooltip 里）。 */
private fun statusTail(server: McpServerStatus): String =
    if (server.status == "connected" && server.tools.isNotEmpty()) {
        "${statusText(server.status)} · ${server.tools.size} 个工具"
    } else {
        statusText(server.status)
    }
