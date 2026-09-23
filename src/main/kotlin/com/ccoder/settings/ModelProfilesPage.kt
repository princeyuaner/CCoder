package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.ccoder.ui.cardFill
import com.ccoder.ui.copyToClipboard
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

/** `JBPasswordField` 打码时用的字符。显式写出来，是因为那只眼睛要拿它做比对。 */
private const val ECHO_MASKED = '•'

/** 复制键的常态。点一下换成 [COPIED_ICON]，指针离开时换回来（见 `copyLabel`）。 */
private val COPY_ICON = AllIcons.Actions.Copy

/** 刚复制完的样子。 */
private val COPIED_ICON = AllIcons.Actions.Checked

/** 「模型 ID」那一栏的标题。探针与用例引用它，不抄字面量。 */
internal val MODEL_IDS_LABEL: String get() = CcoderText.text("settings.models.field.modelIds")

/**
 * 列表栏底部那个新建按钮。与 [ADD_MODEL_LABEL] **必须不同名** ——
 * 一个建配置、一个给配置添模型，同名叫人点错。
 */
internal val ADD_PROFILE_LABEL: String get() = CcoderText.text("settings.models.addProfile")

/** 模型列表里那个添加按钮。 */
internal val ADD_MODEL_LABEL: String get() = CcoderText.text("settings.models.addModel")

/** 哪一行在用。与左侧列表的〔使用中〕是同一句话，两个地方别写岔。 */
internal val IN_USE_LABEL: String get() = CcoderText.text("settings.models.inUse")

/** 〔使用中〕占的宽度（三个汉字），按固定宽度留位 —— 见 [ModelProfilesPage.modelIdRow]。 */
private const val IN_USE_WIDTH = 46

/** 模型列表最多先长这么高（约四行），再多就滚。 */
private const val MODEL_LIST_MAX_HEIGHT = 132

/**
 * 模型列表**至少**这么高（约一行 + 那条「＋ 添加模型」）。
 *
 * 表单高度不够时 `BoxLayout` 只缩这一格（上面四个字段钉死了高度）——
 * 缩到这个底就停，再不够宁可让表单露出滚动条，也不要缩成一条缝什么都看不见。
 */
private const val MODEL_LIST_MIN_HEIGHT = 56

/** 列表栏宽度。它放 CENTER，所以这是"理想宽度"—— 页窄了它会自己收。 */
internal const val MODEL_LIST_WIDTH = 240

/** 表单栏宽度。它放 EAST，是确定值，[FORM_CONTENT_WIDTH] 由它推出来。 */
internal const val MODEL_FORM_WIDTH = PAGE_WIDTH - MODEL_LIST_WIDTH

/** 表单栏左右内边距。 */
private const val FORM_PADDING_H = 16

/** 列表栏左右内边距。 */
private const val LIST_PADDING_H = 12

/**
 * 表单内容的宽度：栏宽减去左右内边距。量模型列表高度前要先喂它。
 *
 * 这个数**必须跟着栏宽走**（原来是 408 = 440 − 32，2026-09-15 三栏改四页签后
 * 表单栏从 440 变成 480）。它是量"模型 ID"那个滚动框高度的喂入宽度，
 * 不对的话列表高度与滚动条就会错 —— 而那个框的封顶高度是可滚与否的唯一判据。
 */
internal const val FORM_CONTENT_WIDTH = MODEL_FORM_WIDTH - 2 * FORM_PADDING_H

/**
 * 模型配置页（设置对话框的「模型」页）。
 *
 * 内容是从原来的 `ModelProfilesDialog` **整块搬过来**的：列表栏 + 表单栏。
 * 搬的时候刻意**不拆函数** —— `collect()` / `save()` / `applyAndRefresh()` 三者共享
 * `name` / `url` / `secret` / `modelFields` / `currentField` 这五个闭包变量，那是刻意的：
 * 增删模型那两个动作必须先 `collect()` 把已改过的文字收进来，否则"改完第 2 行、
 * 再删第 1 行"会把第 2 行的修改一起丢掉（见 `collect` 上的注释）。
 *
 * 它只读写配置，**不碰当前会话** —— 选中态改掉之后由 ClaudePanel 决定要不要重开会话。
 */
internal class ModelProfilesPage(
    private val settings: ClaudeSettings,
    private val profiles: ModelProfiles,
    /**
     * 把密钥放进剪贴板。默认是生产那条（[copyToClipboard]）；用例换掉它 ——
     * 纯单测 JVM 里没有 `ApplicationManager`，真那条一调就 NPE。
     * 同 `DepsUi.copy` 那条先例。
     */
    private val copy: (String) -> Unit = ::copyToClipboard,
) : SettingsPage {

    override val title: String get() = CcoderText.text("settings.page.models")

    /** 当前正在编辑的副本。null = 一条都没选中。 */
    private var editing: ModelProfile? = null

    private val listSlot = JPanel()
    private val formSlot = JPanel()

    /**
     * 右栏那张卡。**建一次**（它要活在刷新之外，否则每打一个字就换一副卡头），
     * 卡头与卡脚随 [rebuildForm] 换。
     */
    private val formCard = settingsCard(null, formBody())

    private fun formBody(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(6, FORM_PADDING_H)
        // **CENTER 而不是 NORTH**：NORTH 是"给首选高度、不够就裁"，模型一多
        // 最后一行连同「＋ 添加模型」会被裁出可视区（用户报的正是"后面的看不到了"）。
        // CENTER 让这一列拿满卡身高度，由 `BoxLayout` 把高度让给唯一能缩的
        // 模型列表那一格（见 [modelListBox] 的 minimumSize），别的字段钉死不缩。
        add(formSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }, BorderLayout.CENTER)
    }

    /**
     * 表单里那几个**定高**字段的壳（名称 / Base URL / 认证方式 / API Key）。
     *
     * 最大高度必须钉死：放进 `CENTER` 上的 `BoxLayout` 后，不钉的话高度富余时
     * 会被拉成弹簧、输入框变得很高；最小高度也钉死：高度不够时 `BoxLayout`
     * 会往最小高度压，而能缩的只该是模型列表那一格 —— 这四个被压扁了
     * 比"看不到最后几个模型"还糟。
     */
    private fun pinnedField(label: String, input: JComponent): JComponent =
        labeledField(label, input).apply {
            val h = preferredSize.height
            minimumSize = Dimension(0, h)
            maximumSize = Dimension(Int.MAX_VALUE, h)
        }

    /** 卡脚上那颗「删除」。没有在编辑的条目时整条卡脚都不画。 */
    private val deleteLabel = JBLabel(DELETE_LABEL).apply {
        foreground = JBColor.namedColor("Component.errorFocusColor", JBColor.RED)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    /** 列表上方那条冲突警告的槽位。单独留一格是为了能**就地重算** —— 环境页改一个键，
     *  这里要跟着变（见 [refreshConflictWarning]）。 */
    private val conflictSlot = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private var built: JComponent? = null

    /** 装配一次就缓存住（见 [SettingsPage.component] 上那条：装两次监听器会弹两个文件框）。 */
    override fun component(): JComponent = built ?: build().also {
        built = it
        refreshConflictWarning()
        refresh()
    }

    override fun reload() {
        // 服务是唯一真相。`editing` 只在本次开框期间有意义（点出来的），
        // 但那一份副本可能已经过期 —— 按 id 重新取一次。
        editing = editing?.let { e -> profiles.profiles().firstOrNull { it.id == e.id } }
        refreshConflictWarning()
        refresh()
    }

    /**
     * 重算列表上方那条冲突警告。**环境页改一个键就会调到这里** ——
     * 不重算的话"刚加完键、切过来却没提示"看起来就像那个提示坏了。
     */
    internal fun refreshConflictWarning() {
        conflictSlot.removeAll()
        val keys = conflictingEnvKeys(settings.envOverrides)
        if (keys.isNotEmpty()) {
            // 它自己带下间距，不要再加 strut（见 wrappedHint 那条）
            conflictSlot.add(
                wrappedHint(
                    conflictWarningText(keys),
                    JBUI.scale(MODEL_LIST_WIDTH - 2 * LIST_PADDING_H),
                )
            )
        }
        conflictSlot.revalidate()
        conflictSlot.repaint()
    }

    private fun build(): JComponent = JPanel(BorderLayout()).apply {
        // 表单栏放 EAST：`BorderLayout` 把多余宽度**全给 CENTER**，所以只有放
        // WEST/EAST 的那一栏拿的是确定的首选宽度。表单栏**必须**确定 ——
        // [FORM_CONTENT_WIDTH] 是量"模型 ID"那个滚动框高度的喂入宽度，它是个变量的话
        // 列表高度就会随对话框宽度漂。列表栏放 CENTER 则更稳：页窄了它自己变窄，
        // 而不是两栏重叠（2026-09-15 就踩过一次）
        add(formColumn(), BorderLayout.EAST)
        add(listColumn(), BorderLayout.CENTER)
    }

    /**
     * 两栏整体重建。
     *
     * 列表和表单都很小（几条配置、五个字段），重建比增量同步可靠得多 ——
     * 增量同步要处理"删掉当前项之后表单显示什么"这类边界，正是 bug 的温床。
     */
    private fun refresh() {
        rebuildList()
        rebuildForm()
    }

    /**
     * 列表栏。
     *
     * 顶上那条冲突警告（spec §6）放**列表上方**而不是表单里：冲突是整页的事，
     * 与当前在编辑哪一条无关。没有冲突时槽位是空的 —— 一条"一切正常"的常驻提示
     * 只会变成噪音。环境页顶部还有同一句话（见 [conflictWarningText]）。
     */
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
            add(conflictSlot)
            add(listSlot)
        }
        return settingsCardColumn(
            settingsCard(
                CcoderText.text("settings.models.card.list"),
                body,
                footer = addButton(),
            ),
            width = MODEL_LIST_WIDTH,
        )
    }

    /**
     * 右栏：**建一次、内容随刷新重建**（卡头也跟着换 —— 它是"正在编辑哪条"）。
     *
     * 表单里那几行走的是 `labeledField`（标签在上、控件在下），不是单栏页那种
     * "标签在左"：[MODEL_FORM_WIDTH] 扣掉卡片与行两层内边距、再让出一个标签列，
     * 控件只剩两百来像素 —— 而这一栏里的值是最长的（Base URL、一行一个的模型 ID），
     * 探针里那条 61 字的 Base URL 会当场被切（`model-profiles-dialog-long-url.png`）。
     */
    private fun formColumn(): JComponent = settingsCardColumn(formCard, width = MODEL_FORM_WIDTH)

    /**
     * 列表栏的「＋ 添加配置」：建一条空配置并立刻进入编辑。
     *
     * 它建的是**配置**（端点 + 密钥 + 一族模型），不是单个模型 —— 所以文案不能
     * 写成「添加模型」：表单里那一栏也有个添加按钮，两个同名但干的事不同，
     * 点错了是"怎么多出来一条空的配置"。
     */
    private fun addButton(): JComponent = JBLabel(ADD_PROFILE_LABEL).apply {
        foreground = UIUtil.getInactiveTextColor()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(8, 10)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // 默认认证方式走 defaultAuthKind()，别在这里写死 API_KEY ——
                // "空端点=官方=API_KEY，非空=网关=Bearer"这条规则只该有一个出处
                editing = ModelProfile(authKind = defaultAuthKind("").name)
                refresh()
            }
        })
    }

    /**
     * 中栏内容：一条配置一行。
     *
     * 行上有**两个互不相干**的信号，别把它们并成一个：
     *   - 背景高亮 = **我正在编辑哪条**（点出来的，只在本次会话里有意义）
     *   - 右侧「使用中」= **哪条在生效**
     * 它们回答的是两个问题（"我在改谁"和"谁在跑"），合成一个就会出现"点开看看
     * 就把在用的模型换掉了"这种事。
     *
     * 「使用中」取的是**本项目的最近一次**（[ClaudeSettings.lastModel]）。2026-09-21
     * 起选中态按会话标签分了，全应用不再有"当前选中那条"这个东西 —— 页面只能给
     * 这一个近似：这个项目最近用的是哪条。开着的标签有可能跟它不同。
     */
    private fun rebuildList() {
        listSlot.removeAll()
        val inUse = settings.lastModel(profiles)?.id
        profiles.profiles().forEach { p ->
            listSlot.add(listRow(p, isEditing = p.id == editing?.id, inUse = p.id == inUse))
        }
        listSlot.revalidate()
        listSlot.repaint()
    }

    private fun listRow(p: ModelProfile, isEditing: Boolean, inUse: Boolean): JComponent {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = true
            // 未选中的那行底色**取卡底**：取面板底的话，卡上会多出一条比卡底更浅的方条
            background = if (isEditing) UIUtil.getListSelectionBackground(true) else cardFill()
            border = JBUI.Borders.empty(6, 9)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(
                JBLabel(p.displayName()).apply { foreground = UIUtil.getLabelForeground() },
                BorderLayout.CENTER,
            )
            // 放右边而不是名字前面：文字长短不一，前置标记会让名字各起一行
            if (inUse) {
                add(
                    JBLabel(IN_USE_LABEL).apply { foreground = UIUtil.getInactiveTextColor() },
                    BorderLayout.EAST,
                )
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    editing = p
                    refresh()
                }
            })
        }
        // 必须**加完子件之后**再量：不设这一条背景就只裹住文字那一段（实测 134px
        // 而不是整行 240px）—— BoxLayout 只把子件排到它的首选宽度，除非 max 允许它长
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    /**
     * 表单栏。
     *
     * **字段顺序不能变**：名称 → Base URL → 认证方式 → API Key → 模型 ID。
     * 「认证方式」必须在密钥**之前** —— 它决定密钥填的是哪一种，
     * 先填密钥再选方式是反着的（spec §7）。
     */
    private fun rebuildForm() {
        formSlot.removeAll()
        formSlot.layout = BoxLayout(formSlot, BoxLayout.Y_AXIS)
        formCard.setFooter(null)

        val p = editing
        if (p == null) {
            formCard.setTitle(null)
            // 空态指的是**列表栏**那个按钮。原来这里写的是「点「＋ 添加模型」」——
            // 而「＋ 添加模型」在表单里、空态下根本不在屏幕上；建整条配置的那个叫
            // 「＋ 添加配置」。新用户照着这行字找按钮是找不到的（2026-09-15 发现）。
            formSlot.add(
                JBLabel(CcoderText.text("settings.models.formEmpty", ADD_PROFILE_LABEL)).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            )
            formSlot.revalidate()
            formSlot.repaint()
            return
        }

        val name = JBTextField(p.name)
        val url = JBTextField(p.baseUrl)
        val authKind = ComboBox(AuthKind.entries.toTypedArray()).apply {
            // 显示 label 而不是枚举名：用户不该看到 AUTH_TOKEN 这种给代码看的词。
            // 只在**这里**翻译，不去覆写 toString()（那会连日志里的名字一起改掉）
            renderer = SimpleListCellRenderer.create("") { it.label }
            selectedItem = p.authKindEnum()
        }
        // 密钥不从 ModelProfile 取 —— 它住在 PasswordSafe 里。
        // 用 JBPasswordField 而不是普通输入框：这是唯一一个明文写在屏幕上就等于泄漏的字段
        val secret = JBPasswordField().apply { text = profiles.secretOf(p.id) }

        // ---- 模型那一族（一行一个）----

        val modelFields = mutableListOf<JBTextField>()
        // 哪一行是"当前用的"。存**组件**而不是下标：下面会把空行滤掉，
        // 下标就会错位；组件引用不会
        var currentField: JBTextField? = null

        /**
         * 从当前界面装配出一份配置。
         *
         * 抽出来是因为**三个地方要用**：save()，以及增删某一行的两个动作 ——
         * 后两者必须先把已改过的文字收进来，否则"改完第 2 行、再删第 1 行"
         * 会把第 2 行的修改一起丢掉。
         *
         * `modelId` 取的是那**一行**的内容：把在用的那个模型改了名，改名之后
         * 它仍然是在用的那个。取不到（那一行被删了、或改成了空）就给空串，
         * 让 [normalizeModelProfile] 把它落到第一项 —— 政策只有那一个出处。
         */
        fun collect(): ModelProfile = p.copy(
            name = name.text,
            baseUrl = url.text,
            modelIds = modelFields.map { it.text.trim() }.filter { it.isNotEmpty() }.toMutableList(),
            modelId = currentField?.text?.trim().orEmpty(),
            authKind = (authKind.selectedItem as AuthKind).name,
        )

        val modelRows = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        p.modelIds.forEach { id ->
            val f = JBTextField(id)
            modelFields += f
            if (id == p.modelId) currentField = f
            modelRows.add(
                modelIdRow(
                    field = f,
                    inUse = id == p.modelId,
                    onRemove = {
                        // 先 collect 再删：否则上面那些框里刚打的字会跟着这一行一起没
                        val kept = collect()
                        applyAndRefresh(
                            normalizeModelProfile(
                                kept.copy(
                                    modelIds = kept.modelIds.filterNot { m -> m == id }
                                        .toMutableList()
                                )
                            )
                        )
                    },
                )
            )
        }
        modelRows.add(
            addModelRow {
                val kept = collect()
                // 空行先塞进去，用户打字时才落库：normalizeModelProfile 会把空行
                // 滤掉，所以这一步**不能**upsert（那等于什么都没加）
                editing = kept.copy(modelIds = (kept.modelIds + "").toMutableList())
                refresh()
            }
        )

        fun save() {
            val next = collect()
            editing = next
            profiles.upsert(next)
            // **只在密钥真的变了时才写**。四个字段共用这一个 save()，无条件写的话
            // 在「名称」里打 40 个字就是 40 次 PasswordSafe 落盘，而密钥一个字没动 ——
            // 那正是 ModelProfiles 注释里写的"别在 UI 线程上同步碰凭据库"。
            // 比较走 secretCache（内存），不会读盘。
            //
            // 读 password 而不是 text：JPasswordField.getText() 已被弃用（它把口令
            // 变成一个长命 String），CharArray 转一下就完事
            val typed = String(secret.password)
            if (typed.trim() != profiles.secretOf(next.id)) {
                profiles.setSecret(next.id, typed)
            }
            rebuildList()   // 改名要立刻反映到列表
        }

        listOf(name, url, secret).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = save()
            })
        }
        // 模型那几个框**编辑时不重建表单** —— 上面那三个同理由走 save()，
        // 而重建会把光标位置和选区丢掉（同 secretField 那条注释）
        modelFields.forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = save()
            })
        }
        authKind.addActionListener { save() }

        formSlot.add(pinnedField(CcoderText.text("settings.models.field.name"), name))
        // 「Base URL」与「API Key」**不进词表**：它们是端点/凭据那一层的词，
        // 中英两版写的是同一串拉丁字（CLI 那边也这么叫）
        formSlot.add(pinnedField("Base URL", url))
        formSlot.add(pinnedField(CcoderText.text("settings.models.field.authKind"), authKind))
        formSlot.add(pinnedField("API Key", secretField(secret)))
        // 模型那一栏**不走 pinnedField**：它是表单里唯一能缩的（见 [modelListBox]），
        // 连壳一起钉死就又回到"不够高只裁不缩"了。壳的最小高度 = 首选高度 −
        // 列表能缩掉的那一截：标签与下间距不许被压掉，缩的只有列表。
        formSlot.add(
            labeledField(MODEL_IDS_LABEL, modelListBox(modelRows)).apply {
                val h = preferredSize.height
                val listPref = (components.last() as JComponent).preferredSize.height
                val listMin = minOf(modelListBoxMinHeight(), listPref)
                minimumSize = Dimension(0, h - (listPref - listMin))
                maximumSize = Dimension(Int.MAX_VALUE, h)
            }
        )

        // 删除进**卡脚**（2026-09-20 卡片式改版），与"关闭"分开 ——
        // 它和"保存这次编辑"不是一类动作；放在正文末尾时它跟着字段一起被重建，
        // 而卡脚是常驻的那一格
        formCard.setTitle(p.displayName())
        formCard.setFooter(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(deleteLabel, BorderLayout.WEST)
            }.apply {
                deleteLabel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        profiles.remove(p.id)   // remove() 负责一并清掉密钥
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
     * 密钥框 + 那两只手：复制、眼睛。
     *
     * 默认打码（spec §7），但得留一个"看一眼"的出口：第三方网关的密钥多半是从
     * 别处复制来的，粘完想核对一下很正常。切换只动 `echoChar`，**不重建组件** ——
     * 重建会顺手把光标位置和选区丢掉。
     *
     * 看完了要**自己合上**（spec §7「失焦即恢复打码」）：不挂这个监听器的话，
     * 只有点另一行（整张表单重建）才会重新打码，于是明文一直留在屏幕上 ——
     * 那正是这个字段唯一要防的事。
     */
    private fun secretField(secret: JBPasswordField): JComponent {
        secret.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                // 只认真正的失焦：点 ComboBox 弹下拉会让焦点**临时**移走再还回来，
                // 那种也复位的话，眼睛里看到的明文会跟着闪一下
                if (!e.isTemporary) secret.echoChar = ECHO_MASKED
            }
        })
        val eye = JBLabel(AllIcons.General.InspectionsEye).apply {
            toolTipText = CcoderText.text("settings.models.secretEyeTip")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyLeft(6)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // echoChar = 0 就是明文，这是 JPasswordField 的约定
                    secret.echoChar = if (secret.echoChar == ECHO_MASKED) 0.toChar() else ECHO_MASKED
                }
            })
        }
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(secret, BorderLayout.CENTER)
            // 两只手在一格里：BorderLayout 每个方位只收得下一个组件（后添的顶掉先添的）
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(copyLabel(secret))
                    add(eye)
                },
                BorderLayout.EAST,
            )
        }
        // 外面套了一层，宽度就不再由输入框自己撑开 —— 不设这条这个框会比别家窄一截
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    /**
     * 「复制密钥」那颗（2026-09-22 用户："设置界面的密钥需要可以被复制"）。
     *
     * 复制的是**整条**、不是选区：打码时选区在屏幕上看不见，"复制我选的那几个字"
     * 在这里没有意义；而密钥这东西本来就是整条有用。
     *
     * **打码时也照复制**（密码管理器那条惯例）：要求先点眼睛看明文才让复制的话，
     * 那一步反倒把明文留在了屏幕上 —— 那正是这个字段唯一要防的事。
     *
     * 复制是这一格里**唯一一个成功了也看不出来**的动作（眼睛有状态可看、输入框有
     * 光标），所以点完换成对勾 —— **指针一离开就换回来**：那一刻正是"我按完了、
     * 去看别处了"，确认到这里就够了。不用计时器：省一个要在组件销毁时收尾的
     * 长命 Timer，行为也好测（点一下 → 对勾，移开 → 还原）。
     */
    private fun copyLabel(secret: JBPasswordField): JBLabel =
        JBLabel(COPY_ICON).apply {
            toolTipText = CcoderText.text("settings.models.secretCopyTip")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyLeft(6)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // 读 password 而不是 text：同 save()，别把口令变成常驻 String
                    copy(String(secret.password))
                    icon = COPIED_ICON
                }

                override fun mouseExited(e: MouseEvent) {
                    icon = COPY_ICON
                }
            })
        }

    /**
     * 写完一条配置并重建两栏。**结构性**动作走这里（增删模型），
     * 而改文字走 `save()` —— 那个只重建左栏，不碰正在编辑的输入框。
     */
    private fun applyAndRefresh(next: ModelProfile) {
        editing = next
        profiles.upsert(next)
        refresh()
    }

    /**
     * 「模型 ID」那一栏的外框：**可滚，且是表单里唯一能缩的那一格**。
     *
     * 三档高度：
     *   - [preferredSize]：内容高与 [MODEL_LIST_MAX_HEIGHT] 的较小者（约四行封顶）
     *   - [minimumSize]：[MODEL_LIST_MIN_HEIGHT] —— 表单高度不够时往这里缩，
     *     再不够也不要缩成一条缝
     *   - [maximumSize]：**等于**首选高度 —— 富余时不要拉成弹簧，把「＋ 添加模型」
     *     甩到卡脚去（`CardPanel` / `tableBox` / `logBox` 各踩过一次）
     *
     * 2026-09-22 之前它只有一个"封顶"，而表单挂在 `BorderLayout.NORTH` 上、
     * 够高时**不缩只裁** —— 封顶挡不住"整个滚动框被裁出可视区"，症状是模型
     * 三四个起最后一行只见半截、「＋ 添加模型」直接消失。现在表单挂 CENTER
     * （见 [formBody]），高度不够缩的是这一格，滚动条因此始终在卡身里。
     */
    private fun modelListBox(rows: JComponent): JComponent {
        val width = JBUI.scale(FORM_CONTENT_WIDTH)
        rows.setSize(width, Int.MAX_VALUE)
        val contentH = rows.preferredSize.height
        val maxH = minOf(contentH, JBUI.scale(MODEL_LIST_MAX_HEIGHT))
        val scroll = JBScrollPane(
            rows,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
        ).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(width, maxH)
            minimumSize = Dimension(width, minOf(modelListBoxMinHeight(), maxH))
            maximumSize = Dimension(Int.MAX_VALUE, maxH)
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }
        return scroll
    }

    /** [modelListBox] 的最小高度（缩到这个底就停，见 [MODEL_LIST_MIN_HEIGHT]）。 */
    private fun modelListBoxMinHeight(): Int = JBUI.scale(MODEL_LIST_MIN_HEIGHT)

    /**
     * 一个模型：可编辑的输入框 + 右边〔使用中〕与 ✕。
     *
     * 〔使用中〕的位置**按固定宽度留出来**，不写就没有。三行模型里只有一行在用，
     * 不留位的话 ✕ 会一行一个位置，看起来像没对齐 —— 同 `MARK` 那个
     * "未选中也缩进"的道理。
     */
    private fun modelIdRow(field: JBTextField, inUse: Boolean, onRemove: () -> Unit): JComponent {
        // 固定宽度的占位**必须做在 JPanel 上**：JBLabel 覆写了 getPreferredSize
        // （它按内容算，还要处理 HTML），给它设 preferredSize 是不生效的 ——
        // 实测两行的 ✕ 会落在 36px 和 0px 上，看起来像没对齐
        val badge = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(IN_USE_WIDTH), 0)
            if (inUse) {
                add(
                    JBLabel(IN_USE_LABEL).apply { foreground = UIUtil.getInactiveTextColor() },
                    BorderLayout.WEST,
                )
            }
        }

        val remove = JBLabel("✕").apply {
            toolTipText = CcoderText.text("settings.models.removeModelTip")
            foreground = UIUtil.getInactiveTextColor()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyLeft(6)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onRemove()
            })
        }

        val east = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(badge)
            add(remove)
        }

        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(field, BorderLayout.CENTER)
            add(east, BorderLayout.EAST)
        }
        // 加完子件之后再量（同 ModelProfiles 里 listRow 的注释）
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    /** [ADD_MODEL_LABEL]。是个动作，所以不占勾位、贴着左边。 */
    private fun addModelRow(onAdd: () -> Unit): JComponent =
        JBLabel(ADD_MODEL_LABEL).apply {
            foreground = UIUtil.getInactiveTextColor()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyTop(2)
            alignmentX = Component.LEFT_ALIGNMENT
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onAdd()
            })
        }
}

/**
 * 冲突警告那句话。模型页与环境页共用 —— 冲突就是同一件事，两个地方别写岔。
 *
 * 用 [wrappedHint] 而不是 `JBLabel`：这句话在三把键全被抢时要 321px 起，
 * 而列表栏只放得下 216px。不折行就只剩半句 —— 实测被裁在"会被选中"，
 * 而"的模型配置覆盖"才是重点（说清谁会覆盖谁）。
 */
internal fun conflictWarningText(keys: List<String>): String =
    CcoderText.text(
        "settings.models.conflictWarning",
        keys.joinToString(CcoderText.text("settings.models.conflictSeparator")),
    )
