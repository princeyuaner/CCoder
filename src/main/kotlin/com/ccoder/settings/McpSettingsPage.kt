package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import com.ccoder.text.CcoderText
import com.ccoder.ui.cardFill
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
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/** 左栏那个新建按钮。空态文案会引用它，别在别处抄字面量。 */
internal val ADD_SERVER_LABEL: String get() = CcoderText.text("settings.mcp.addServer")

/** 左栏宽度。它放 CENTER，所以这是"理想宽度" —— 页窄了它会自己收。 */
internal const val MCP_LIST_WIDTH = 240

/** 表单栏宽度。放 EAST，是确定值。 */
internal const val MCP_FORM_WIDTH = PAGE_WIDTH - MCP_LIST_WIDTH

private const val MCP_LIST_PADDING_H = 12
private const val MCP_FORM_PADDING_H = 16
// 卡片式改版后，卡内可用的宽度还要扣掉卡片两侧的缝与描边那 2px
private const val MCP_FORM_CONTENT_WIDTH =
    MCP_FORM_WIDTH - 2 * CARD_INSET - CARD_BORDER_W - 2 * MCP_FORM_PADDING_H

/** 多行那几栏先长这么高（约三行），再多就滚。 */
private const val SMALL_BOX_HEIGHT = 62

/**
 * 失败那句那块地方的高度（整三行）。
 *
 * 三行不是拍的：CLI 给的原因往往是 `MCP error -32000: Connection closed`
 * 这种一句半英文，在这栏宽度下正好折成三行 —— 给够它，常见的那几种就都能
 * **整句**看见，也不会因为差几个像素而平白冒出一条滚动条（出图时就是这么差一点的）。
 */
private const val FAILURE_BOX_HEIGHT = 58

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
    /**
     * 动作那条线（重连 / 启用停用，2026-09-28）。**只读** —— 页面把用户的点击
     * 交给它，面板接过去执行；结果也回到它上面（见 [McpControl]）。
     */
    private val control: McpControl,
) : SettingsPage {

    override val title: String = "MCP"

    private val listSlot = JPanel()
    private val statusSlot = JPanel()
    private val formSlot = JPanel()

    /** 正在编辑的那条。**可能还不在 [config] 里**（新加的、名字还没填）。 */
    private var editing: McpServer? = null

    /** 它在 `config.servers` 里的下标；-1 = 新加的、还没落库。 */
    private var editingIndex: Int = -1

    /**
     * 右栏那张卡：**建一次**（它要活在刷新之外，否则每打一个字就换一副卡头），
     * 卡头（正在编辑哪条 server）与卡脚（删除）随 [rebuildForm] 换。
     */
    private val formCard = settingsCard(null, formBody())

    private fun formBody(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, MCP_FORM_PADDING_H)
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

    /**
     * 动作那条线的通知有两件事会用到：失败那句话（[failureText]）和
     * "这会儿有没有人能接"（[McpControl.armed] —— 它变了按钮该跟着出现/消失）。
     */
    private val controlListener: () -> Unit = { refreshStatus() }

    private fun filePath(): Path? = baseDir?.let { mcpJsonPath(it) }

    override fun component(): JComponent = built ?: build().also {
        built = it
        reload()
        status.addListener(statusListener)
        control.addListener(controlListener)
    }

    override fun dispose() {
        status.removeListener(statusListener)
        control.removeListener(controlListener)
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

    /**
     * 左栏：**两张卡**（`.mcp.json` 与「当前会话」）。
     *
     * 它们回答的问题是分开的：上面那张是"文件里配了什么"（能改），下面那张是
     * "此刻连上了什么"（只读，还包含你自己全局配的那些，所以两边对不齐是正常的）
     * —— 所以是两张卡，不是一张卡里两段。
     */
    private fun listColumn(): JComponent {
        listSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        statusSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val listCard = settingsCard(
            ".mcp.json",
            cardBlock(listSlot),
            footer = actionLabel(ADD_SERVER_LABEL) {
                // 先进编辑态、**不落库**：名字（JSON 的键）还没定，落库会写出一条
                // 名字为空的 server，而读回来时它会被丢掉 —— 用户会以为修改没保存
                editing = McpServer()
                editingIndex = -1
                refresh()
            },
        )
        val statusCard = settingsCard(
            CcoderText.text("settings.mcp.currentSession"),
            // 顺序就是视觉顺序：失败那句在状态列表**上面**
            cardBlock(failureHolder, statusSlot),
        )
        return settingsCardColumn(listOf(listCard, statusCard), width = MCP_LIST_WIDTH)
    }

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
            baseDir == null -> listSlot.add(hint(NO_BASE_DIR_TEXT))
            config.servers.isEmpty() && editingIndex < 0 ->
                listSlot.add(hint(CcoderText.text("settings.mcp.fileEmpty")))
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
            // 未选中的那行底色取卡底（同模型页那条：取面板底会在卡上留一条浅方条）
            background = if (isEditing) UIUtil.getListSelectionBackground(true) else cardFill()
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
        // 上一次动作的失败说在最上面：它说的是"你刚点的那个没成"，
        // 得在用户去找那一行之前就看见
        val message = failureText()
        failureHolder.isVisible = message != null
        if (message != null) {
            failureArea.text = message
            // 从头显示：不设的话对话框一开，光标的默认位置会让这段滚到尾巴上
            failureArea.caretPosition = 0
        }
        when {
            !status.known -> statusSlot.add(hint(CcoderText.text("settings.mcp.statusUnknown")))
            status.servers.isEmpty() -> statusSlot.add(hint(CcoderText.text("settings.mcp.statusEmpty")))
            else -> status.servers.forEach { statusSlot.add(statusRow(it)) }
        }
        statusSlot.revalidate()
        statusSlot.repaint()
    }

    /**
     * 上一次动作失败那句话。成功、或者还没动作过，都是 null（不占地方）。
     *
     * 三种措辞按**用户刚点的那一颗**分（重连 / 启用 / 停用），不是按回执里的
     * 布尔值推 —— 回执里的字段可能是从我们自己的编码器漏出去的，而用户点的是
     * 哪一颗，这里最清楚。
     */
    private fun failureText(): String? {
        val failure = control.failure ?: return null
        val name = failure.action.serverName
        return when (val action = failure.action) {
            is McpControl.Action.Reconnect ->
                CcoderText.text("settings.mcp.reconnectFailed", name, failure.message)

            is McpControl.Action.Toggle ->
                if (action.enabled) {
                    CcoderText.text("settings.mcp.enableFailed", name, failure.message)
                } else {
                    CcoderText.text("settings.mcp.disableFailed", name, failure.message)
                }
        }
    }

    /**
     * 失败那句。**要折行**（错误色，同表单页的删除标签那条：红=出事了）。
     *
     * 一行的标签在这里必然被裁掉 —— 那栏两百来像素，而 CLI 给的原因
     * （`MCP error -32000: Connection closed`）远超它，裁了还没有任何提示
     * 告诉用户后面还有字。这个坑同步页先踩过（`logArea` 那条注释），抄的就是它那套。
     *
     * `internal` 是给用例看的：这句**故意不是标签**（要折行，标签不折），
     * 所以按文字找控件的 `findLabel` 够不着它 —— 用例拿这个实例比对在不在树上。
     */
    internal val failureArea = JBTextArea().apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getErrorForeground()
        isOpaque = false
        rows = 3
    }

    /**
     * 那句失败整块（含滚动条）。**只钉高度，宽度交给卡身去铺** ——
     * 同同步页的日志框那条。
     *
     * 宽度是踩出来的：这块原先摆在 `statusSlot` 里（卡身里又套了一层 BoxLayout），
     * 结果被缩成一半宽，那句话折成三截还裁掉一截（出图看出来的）。挪成卡身的
     * **直接子件**之后就铺满了 —— 多套一层的代价看不出来，只在出图时露馅。
     */
    private val failureBox = JBScrollPane(failureArea).apply {
        // 不要那个方框：它是一句话，不是一扇窗（同步页那块日志是窗，所以那边留着）
        border = null
        preferredSize = Dimension(MCP_LIST_WIDTH - 40, JBUI.scale(FAILURE_BOX_HEIGHT))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(FAILURE_BOX_HEIGHT))
    }

    /**
     * 失败那句话的容器：没有失败时**整块藏起来**（BoxLayout 不给不可见的子件留地方），
     * 于是"上一次没失败"与"这次也没失败"长得一样。
     *
     * `internal` 是给用例看的 —— [failureArea] 藏在里面，而"在不在树上"要问它。
     */
    internal val failureHolder = JPanel(BorderLayout()).apply {
        isOpaque = false
        isVisible = false
        add(failureBox, BorderLayout.CENTER)
    }

    private fun statusRow(server: McpServerStatus): JComponent {
        val actions = controlActions(server)
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 9)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(JBLabel(server.name).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.WEST)
                    add(JBLabel(statusTail(server)).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.EAST)
                },
                BorderLayout.CENTER,
            )
            // 动作**另起一行**（出图看出来的）：这一栏只有两百来像素，名字 + 动作 + 状态
            // 挤在一行时 BorderLayout 会让 WEST 与 EAST **直接重叠**（"我自己全局配的已连接"），
            // 而三个都是要读的字，谁也不该让位。没动作时这一行根本不存在 ——
            // 于是没会话接的那一屏与从前长得一模一样
            if (actions.isNotEmpty()) {
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                        isOpaque = false
                        border = JBUI.Borders.emptyTop(JBUI.scale(3))
                        actions.forEach { add(it) }
                    },
                    BorderLayout.SOUTH,
                )
            }
        }
        // 失败原因放进 tooltip：列表里塞不下，但**必须有地方看见** ——
        // 只说"失败"等于什么都没说
        if (!server.error.isNullOrBlank()) row.toolTipText = server.error
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    /**
     * 这一行给哪几颗动作。
     *
     * **没人接就一颗都不画**（[McpControl.armed] = 有活着的会话）—— 画一颗点了
     * 没反应的按钮，正是这个仓库最不肯做的事（见 `CardAction` 的注释）。
     *
     * 画哪几颗看状态：`disabled` 只给「启用」（它现在就是关着的，再给「停用」
     * 是废话）；其余一律「重连 + 停用」—— 连上的、失败中的、要认证的，这两颗
     * 都有意义，将来 CLI 多一档也落到这一支（重连对任何"没关掉"的状态都安全）。
     */
    private fun controlActions(server: McpServerStatus): List<JComponent> {
        if (!control.armed) return emptyList()
        val name = server.name
        return if (server.status == "disabled") {
            listOf(action("settings.mcp.enable") { McpControl.Action.Toggle(name, true) })
        } else {
            listOf(
                action("settings.mcp.reconnect") { McpControl.Action.Reconnect(name) },
                action("settings.mcp.disable") { McpControl.Action.Toggle(name, false) },
            )
        }
    }

    /**
     * 一颗行内动作。样式走 [actionLabel]（设置页里没有 JButton，全是这种灰字），
     * 键**字面量写在调用点上**（同 [statusTail] 那条：词表与源码的对账靠源码扫描）。
     *
     * 送不出去时**这里不必管**：[McpControl.send] 自己会把那条线放掉再通知一次，
     * 这一行跟着就没了（"按钮在、按了没反应"比"按钮不在"糟得多）。
     */
    private fun action(key: String, make: () -> McpControl.Action): JComponent =
        actionLabel(CcoderText.text(key)) { control.send(make()) }

    // ---- 右栏 ----

    private fun formColumn(): JComponent = settingsCardColumn(formCard, width = MCP_FORM_WIDTH)

    private fun rebuildForm() {
        formSlot.removeAll()
        formSlot.layout = BoxLayout(formSlot, BoxLayout.Y_AXIS)
        formCard.setFooter(null)

        val editingServer = editing
        if (editingServer == null) {
            formCard.setTitle(null)
            formSlot.add(hint(CcoderText.text("settings.mcp.formEmpty", ADD_SERVER_LABEL)))
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
            labeledField(CcoderText.text("settings.mcp.field.command"), command),
            labeledField(CcoderText.text("settings.mcp.field.args"), box(argsArea)),
            labeledField(CcoderText.text("settings.mcp.field.env"), box(envArea)),
        )
        val remoteFields = listOf(
            labeledField(CcoderText.text("settings.mcp.field.url"), url),
            labeledField(CcoderText.text("settings.mcp.field.headers"), box(headersArea)),
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

        formSlot.add(labeledField(CcoderText.text("settings.mcp.field.name"), name))
        formSlot.add(labeledField(CcoderText.text("settings.mcp.field.kind"), kind))
        stdioFields.forEach { formSlot.add(it) }
        remoteFields.forEach { formSlot.add(it) }
        applyKind()

        formSlot.add(
            wrappedHint(
                // 别在这里用 markdown 的星号：wrappedHint 是纯文本，会把 ** 原样画出来
                // （渲染探针里看见过一次）
                CcoderText.text("settings.mcp.formHint"),
                JBUI.scale(MCP_FORM_CONTENT_WIDTH),
            )
        )

        // 删除进**卡脚**（2026-09-20 卡片式改版）：与"改配置"不是一类动作
        formCard.setTitle(editingServer.name)
        formCard.setFooter(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(deleteLabel, BorderLayout.WEST)
            }.apply {
                deleteLabel.addMouseListener(object : MouseAdapter() {
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
            },
        )

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
 *
 * 左边那几个是 CLI 的**线上取值**（`connected` / `failed`…），一个个对着词表取；
 * 右边 `else` 那一支原样穿过，所以永远不会有"我们编的词"盖住真实状态。
 */
private fun statusText(status: String): String = when (status) {
    "connected" -> CcoderText.text("settings.mcp.status.connected")
    "failed" -> CcoderText.text("settings.mcp.status.failed")
    "needs-auth" -> CcoderText.text("settings.mcp.status.needsAuth")
    "pending" -> CcoderText.text("settings.mcp.status.pending")
    "disabled" -> CcoderText.text("settings.mcp.status.disabled")
    else -> status
}

/**
 * 一行状态：连上的报工具数，没连上的只报状态（原因在 tooltip 里）。
 *
 * 工具数**分了单复数两条键**：英文里 "1 tools" 是错的，而一个只挂一件工具的
 * server 完全常见（`session.widget.pendingTipOne` 是同一条规矩）。
 */
private fun statusTail(server: McpServerStatus): String {
    val status = statusText(server.status)
    if (server.status != "connected" || server.tools.isEmpty()) return status
    val count = server.tools.size
    // 键**字面量写在调用点上**（两个分支各一条）：词表与源码的对账靠源码扫描
    return if (count == 1) {
        CcoderText.text("settings.mcp.status.toolsOne", status, count)
    } else {
        CcoderText.text("settings.mcp.status.tools", status, count)
    }
}
