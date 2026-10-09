package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import com.ccoder.text.CcoderText
import com.ccoder.ui.Tone
import com.ccoder.ui.toneColor
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

/** 「＋ 添加 MCP server」那张卡上的字。 */
internal val ADD_SERVER_LABEL: String get() = CcoderText.text("settings.mcp.addServer")

/**
 * 两栏页里**列表栏**的宽度。
 *
 * MCP 页 2026-09-30 改成卡片流之后自己不再用它，但 hooks 还在用
 * （`HOOK_LIST_WIDTH = MCP_LIST_WIDTH`）—— 两条列表栏同宽是有意的。
 */
internal const val MCP_LIST_WIDTH = 240

/** 卡片脚上那颗「编辑」。 */
internal val EDIT_SERVER_LABEL: String get() = CcoderText.text("settings.mcp.edit")

/**
 * 失败那句那块地方的高度（整三行）。
 *
 * 三行不是拍的：CLI 给的原因往往是 `MCP error -32000: Connection closed`
 * 这种一句半英文 —— 给够它，常见的那几种就都能**整句**看见（旧版在 240px 的
 * 窄栏里就是这么差一点的），也不会因为差几个像素而平白冒出一条滚动条。
 */
private const val FAILURE_BOX_HEIGHT = 58

/**
 * MCP 页：**一台 server 一张卡片**（选型稿 `docs/design/mcp-page.html` 方案一，2026-09-30）。
 *
 * ## 为什么不再分两栏
 *
 * 原来是"左 240px 列表 + 右 591px 常驻表单"：列表一行只够写个名字，而"此刻连上了没有"
 * 还得另开一张卡 —— 那张卡里名字钉左、状态钉右，240px 里一长就叠在一起（用户截图报过）。
 * 现在三样并进同一张卡：卡头是名字与状态、卡体是它长什么样、卡脚是编辑与删除；
 * 新增与编辑都走弹窗（[showMcpServerDialog]）。卡片流的宽度跟着视口走（[WidthTracked]），
 * 高了就滚。
 *
 * ## 三段，按"这份信息从哪来"分
 *
 *  - **项目里**：`<项目根>/.mcp.json` 里的 server，能增删改（改了要新开会话才生效）
 *  - **全局**：会话里连上、但**不在这个文件里**的（用户自己全局配的那些），只读 ——
 *    插件只写项目里这一份
 *  - **认不出的**：文件里形状不是命令行 / SSE / HTTP 的那些（`sdk` 之类）。插件不认，
 *    但**一个字都不会动它**；今天它们在这页上完全看不见，给一张压暗的卡比"悄悄留着"诚实
 *
 * 两栏对不齐是**正常**的（文件是文件、会话是会话），所以分段而不是硬凑成一张表。
 *
 * ## 每张卡上的动作分两组（2026-10-09 回接）
 *
 * **左 = 动这个会话**（重连 / 停用·启用，当即生效），**右 = 动文件里那条配置**
 * （编辑 / 删除，下次会话生效）。会话那组只在有活着的会话接得住（[McpControl.armed]）、
 * 而且这个 server 眼下在会话里时才画 —— 没有它的时候这些卡与从前同貌。
 */
internal class McpSettingsPage(
    /** 项目根。null = 拿不到项目目录，这时**只读不写**（别猜一个路径去写）。 */
    private val baseDir: Path?,
    private val status: McpStatus,
    /**
     * 动作那条线（重连 / 启用停用，2026-09-28 接上、2026-10-09 回接到卡片页）。
     * **只读**视野：页面把用户的点击交给它（[McpControl.send]），面板接过去执行；
     * 结果也回到它上面（[McpControl.failure]）。
     */
    private val control: McpControl,
    private val project: Project? = null,
    /**
     * 打开新增 / 编辑框。**测试注入假的**：真那个要一个窗口，无头里开不起来。
     * 参数：初值、除自己以外的名字、是不是新增、提交回调。
     */
    private val openEditor: (McpServer, List<String>, Boolean, (McpServer) -> Unit) -> Unit =
        { initial, others, isNew, onSubmit ->
            showMcpServerDialog(project, initial, others, isNew, onSubmit)
        },
) : SettingsPage {

    override val title: String = "MCP"

    /**
     * 页脚那句话要改口：这一页**不是**改动即时保存 —— 新增与编辑都在弹窗里，
     * 按了「保存」才写进文件（别处那句「改动即时保存」在这页是假话）。
     */
    override fun footerHint(): String = CcoderText.text("settings.mcp.footerHint")

    /** 卡片流那一列。**建一次**，刷新时只换里面的卡片（同旧版两个 slot 的理由）。 */
    private val column = settingsColumn()

    private var built: JComponent? = null

    /** 文件**原样**读进来的那份：写回时按它保序、保别人的键。 */
    private var root = JsonObject()

    private var config: McpConfig = McpConfig(emptyList(), JsonObject())

    /**
     * 状态是别处推过来的（面板收到回执后写服务）。
     *
     * **不再套一层 `invokeLater`**：服务保证在 EDT 上通知（`McpStatus` 那条），
     * 而且这层包装在纯 JVM 测试里会拿到 null 的 Application 直接炸。
     */
    private val statusListener: () -> Unit = { refresh() }

    /**
     * 动作那条线也走同一个刷新：它会变两件事 —— 失败那句话（[failureText]）和
     * "这会儿有没有人能接"（[McpControl.armed]，它变了那几颗按钮该跟着出现/消失）。
     */
    private val controlListener: () -> Unit = { refresh() }

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
        refresh()
    }

    private fun build(): JComponent = JBScrollPane(
        // 宽度跟着视口走：卡片流要铺满这一栏，而它的首选宽度只等于最宽那张卡
        WidthTracked(settingsPageBody(column)),
    ).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = JBUI.scale(16)
    }

    private fun refresh() {
        column.removeAll()

        // 上一次动作的失败说在**最上面**：它说的是"你刚点的那颗没成"，
        // 得在用户去找那张卡之前就看见。没有失败时整块藏起来 —— BoxLayout
        // 不给不可见的子件留地方，于是"上一次没失败"与"这次也没失败"长得一样
        val message = failureText()
        failureHolder.isVisible = message != null
        if (message != null) {
            failureArea.text = message
            // 从头显示：不设的话对话框一开，光标的默认位置会让这段滚到尾巴上
            failureArea.caretPosition = 0
        }
        column.add(failureHolder)

        // ---- 项目里：能改的那一段 ----
        column.add(sectionLabel("settings.mcp.section.project", "settings.mcp.section.projectHint"))
        when {
            baseDir == null -> column.add(hint(NO_BASE_DIR_TEXT))
            else -> {
                if (config.servers.isEmpty()) column.add(hint(CcoderText.text("settings.mcp.fileEmpty")))
                config.servers.forEach { column.add(serverCard(it)) }
                column.add(addCard())
            }
        }

        // ---- 全局：会话里连上、不在这个文件里的 ----
        val session = if (status.known) status.servers else emptyList()
        val global = session.filter { s ->
            config.servers.none { it.name == s.name } && !config.preserved.has(s.name)
        }
        if (global.isNotEmpty()) {
            column.add(sectionLabel("settings.mcp.section.global", "settings.mcp.section.globalHint"))
            global.forEach { column.add(globalCard(it)) }
        }

        // ---- 认不出的：文件里形状不认识的 ----
        val unknown = config.preserved.keySet().toList()
        if (unknown.isNotEmpty()) {
            column.add(sectionLabel("settings.mcp.section.unknown", "settings.mcp.section.unknownHint"))
            unknown.forEach { column.add(unknownCard(it)) }
        }

        // ---- 状态本身还没到 / 一台都没有：只在这两种时候说 ----
        when {
            !status.known -> column.add(hint(CcoderText.text("settings.mcp.statusUnknown")))
            session.isEmpty() -> column.add(hint(CcoderText.text("settings.mcp.statusEmpty")))
        }

        column.revalidate()
        column.repaint()
    }

    // ---- 三种卡 ----

    /**
     * 文件里的一台：**能改能删**的那种。
     *
     * 卡头：名字 + 形状 + 状态（状态取不到就不画，见 [mcpStatusOf]）。
     * 卡体：命令或 URL（等宽）、变量键名、失败原因。
     * 卡脚：编辑、删除。
     */
    private fun serverCard(server: McpServer): JComponent {
        val known = mcpStatusOf(status, server.name)
        val body = cardBlock()
        body.add(monoLine(mcpCommandLineOf(server)).apply { foreground = UIUtil.getLabelForeground() })
        mcpVarsLineOf(server)?.let { line ->
            body.add(dimLabel(line).apply { border = JBUI.Borders.emptyTop(4) })
        }
        // 失败原因**摊在卡上**（不塞 tooltip）：它是"要不要去修"的唯一线索
        known?.error?.takeIf { it.isNotBlank() }?.let { why ->
            body.add(
                JBLabel(why).apply {
                    foreground = UIUtil.getErrorForeground()
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyTop(4)
                },
            )
        }

        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            // 左 = 动这个**会话**的（重连 / 停用·启用，当即生效），右 = 动**文件**
            // 里那条配置的（编辑 / 删除，下次会话生效）。会话那组只在有人接、且
            // 这个 server 眼下在会话里时才出现（判据见 [sessionActionsOf]）
            sessionActionsOf(known)?.let { add(it, BorderLayout.WEST) }
            add(
                actionRow(
                    listOf(
                        actionLabel(EDIT_SERVER_LABEL) { openEditorFor(server) },
                        deleteLabel(server),
                    ),
                ),
                BorderLayout.EAST,
            )
        }

        return settingsCard(server.name, body, footer).also { it.setHeaderTail(headerTail(server, known)) }
    }

    /** 「＋ 添加 MCP server」：虚线卡（占位，不是配置）。 */
    private fun addCard(): JComponent = settingsCard(
        null,
        cardBlock(actionLabel(ADD_SERVER_LABEL) { openEditorFor(null) }),
        dashed = true,
    ).apply { border = JBUI.Borders.emptyTop(8) }

    /**
     * 全局那一台：只读，**没会话接的时候一行高**（卡头就是全部）。
     *
     * 没有**编辑与删除** —— 插件只写项目里的 `.mcp.json`（改它要去 CLI 自己那边），
     * 而不是给两颗点了会失败的按钮。为什么只读由那一段的提示说一次就够，卡上不再重复。
     * 但**会话那组动作它有**（重连 / 停用·启用）：那两颗动的是当前会话里的连接，
     * 与文件是谁写的无关 —— 而全局配的那些恰恰最需要"死了重连一下"。
     */
    private fun globalCard(server: McpServerStatus): JComponent =
        settingsCard(server.name, cardBlock(), sessionActionsOf(server)).also {
            it.setHeaderTail(headerTail(null, server))
        }

    /** 文件里认不出的那条：压暗，只说它在那儿。 */
    private fun unknownCard(name: String): JComponent {
        val body = cardBlock(dimLabel(CcoderText.text("settings.mcp.unknownBody")))
        return settingsCard(name, body).also {
            it.setHeaderTail(dimLabel(CcoderText.text("settings.mcp.kindTag.raw")))
        }
    }

    // ---- 卡脚那几颗会话动作（2026-10-09 回接）----

    /**
     * 会话那组动作（重连 / 停用·启用），没有就返回 null（那一组整段不出现）。
     *
     * 两个前提都要：[McpControl.armed]（有活着的会话接得住）与 [known]（这个 server
     * 眼下在会话里）—— 会话不知道的 server，这两颗只会拿"没有这个 server"撞回来；
     * 而画一颗点了没反应的按钮，正是这个仓库最不肯做的事（见 `CardAction` 的注释）。
     *
     * 画哪几颗看状态：`disabled` 只给「启用」（它现在就是关着的，再给「停用」
     * 是废话）；其余一律「重连 + 停用」—— 连上的、失败中的、要认证的、连接中的，
     * 这两颗都有意义，将来 CLI 多一档也落到这一支（重连对任何"没关掉"的状态都安全）。
     *
     * 送不出去时**这里不必管**：[McpControl.send] 自己会把那条线放掉再通知一次，
     * 这一组跟着就没了（"按钮在、按了没反应"比"按钮不在"糟得多）。
     */
    private fun sessionActionsOf(known: McpServerStatus?): JComponent? {
        if (!control.armed || known == null) return null
        val name = known.name
        val actions = if (known.status == "disabled") {
            listOf(
                actionLabel(CcoderText.text("settings.mcp.enable")) {
                    control.send(McpControl.Action.Toggle(name, true))
                },
            )
        } else {
            listOf(
                actionLabel(CcoderText.text("settings.mcp.reconnect")) {
                    control.send(McpControl.Action.Reconnect(name))
                },
                actionLabel(CcoderText.text("settings.mcp.disable")) {
                    control.send(McpControl.Action.Toggle(name, false))
                },
            )
        }
        return actionRow(actions)
    }

    /** 卡脚里的一组动作：横排、按内容取宽（两端的隔开交给外层 BorderLayout）。 */
    private fun actionRow(actions: List<JComponent>): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        actions.forEachIndexed { index, action ->
            if (index > 0) add(Box.createHorizontalStrut(JBUI.scale(16)))
            add(action)
        }
    }

    // ---- 卡头的右端：形状 + 状态 ----

    private fun headerTail(server: McpServer?, known: McpServerStatus?): JComponent =
        JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            server?.let { add(dimLabel(it.mcpKind().label)) }
            known?.let {
                if (server != null) add(Box.createHorizontalStrut(JBUI.scale(10)))
                add(
                    JBLabel(mcpStatusTextOf(it)).apply { foreground = toneColor(mcpToneOf(it.status)) },
                )
            }
        }

    // ---- 小件 ----

    /** 一段的抬头（左）与那句"这段是什么"（右）。 */
    private fun sectionLabel(titleKey: String, hintKey: String): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        // 段与段之间留空：卡与卡只隔 8px，段之间不留就糊成一片
        border = JBUI.Borders.empty(16, 0, 6, 0)
        add(
            JBLabel(CcoderText.text(titleKey)).apply { foreground = UIUtil.getLabelForeground() },
            BorderLayout.WEST,
        )
        add(
            JBLabel(CcoderText.text(hintKey)).apply { foreground = UIUtil.getInactiveTextColor() },
            BorderLayout.EAST,
        )
    }

    private fun monoLine(text: String) = JBLabel(text).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun dimLabel(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getInactiveTextColor()
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun hint(text: String): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(dimLabel(text), BorderLayout.WEST)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    // ---- 上一次动作的失败（那句话在最上面）----

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
     * 一行的标签在这里必然被裁掉 —— CLI 给的原因（`MCP error -32000: Connection closed`）
     * 远超一行的宽度，裁了还没有任何提示告诉用户后面还有字。同步页先踩过这个坑
     * （`logArea` 那条注释），抄的就是它那套。
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
     * 那句失败整块（含滚动条）。**只钉高度、宽度交给列去铺**。
     */
    private val failureBox = JBScrollPane(failureArea).apply {
        // 不要那个方框：它是一句话，不是一扇窗（同步页那块日志是窗，所以那边留着）。
        // **壳与视口要一起透明**：只把文字区设透明的话，整块还是一张白底 ——
        // 2026-10-09 出图时一眼看出来的；一句话衬在页底上才不突兀
        border = null
        isOpaque = false
        viewport.isOpaque = false
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

    /** 卡脚右端那颗「删除」：红字，**不弹二次确认** —— 文件里有、git 里有，误删找得回来。 */
    private fun deleteLabel(server: McpServer) = JBLabel(DELETE_LABEL).apply {
        foreground = UIUtil.getErrorForeground()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = delete(server)
        })
    }

    // ---- 动作 ----

    /**
     * 开新增 / 编辑框。
     *
     * 新增时给的是**一条空记录**（名字空着）—— 名字是 JSON 的键，得由用户在框里定，
     * 所以这里一个字都不落库（旧版"点添加只是进编辑态"那条规矩，换了个形式）。
     */
    private fun openEditorFor(server: McpServer?) {
        // 重名要连**认不出的那些**一起算上：JSON 同键只能有一个，撞上就是把人家
        // 原样保留的那份**覆盖掉**（那等于替用户删了配置）
        val others = (config.servers.map { it.name } + config.preserved.keySet().toList())
            .filter { it != server?.name }
        // 记住**打开时**的名字：弹窗里可能把它改掉，而改完之后要替换的是原来那一条
        // （按新名字去找会找不着，于是"改名"变成"多出一条"）
        val original = server?.name
        openEditor(server ?: McpServer(), others, server == null) { submitted -> submit(original, submitted) }
    }

    /**
     * 弹窗按了「添加」/「保存」之后走这里。
     *
     * [originalName] 是**打开弹窗时**那条的名字；null = 这是新增。
     * 名字空的**不落库**（写出去会在文件里留一条会被读丢的空条目）。
     */
    internal fun submit(originalName: String?, server: McpServer) {
        if (server.name.isEmpty()) return
        val servers = config.servers.toMutableList()
        val at = originalName?.let { name -> servers.indexOfFirst { it.name == name } } ?: -1
        if (at >= 0) servers[at] = server else servers += server
        // 改名要告诉写回那一层：不然它会当成"删一条 + 末尾加一条"，文件里多一片挪动
        val renames = if (originalName != null && originalName != server.name) {
            mapOf(originalName to server.name)
        } else {
            emptyMap()
        }
        writeConfig(servers, renames)
        refresh()
    }

    private fun delete(server: McpServer) {
        writeConfig(config.servers.filterNot { it.name == server.name })
        refresh()
    }

    /** 把整份文件重新写一遍：只换 `mcpServers`，别人的键一个字节都不动。 */
    private fun writeConfig(servers: List<McpServer>, renames: Map<String, String> = emptyMap()) {
        val path = filePath() ?: return
        ProjectJson.write(path, withMcpServers(root, servers, config.preserved, renames))
        config = McpConfig(servers, config.preserved)
    }
}

/** 「连上了」那档的绿。取平台那支（`Component.infoForeground`? 不 —— 用 UIUtil 的标准绿）。 */
private fun statusOk() = com.intellij.ui.JBColor.namedColor("Label.successForeground", UIUtil.getLabelForeground())

/**
 * 一块**宽度跟着视口走**的内容（高度照旧按内容）—— 卡片流要的就是这个。
 *
 * 没有它，滚动视口会把内容按"首选宽度"摆（= 最宽那张卡），右边的空档谁也占不到。
 * 高度那侧必须 `false`：撑满就成了"内容再长也不滚"（`Composer` 那条注释记过同一个坑）。
 */
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
