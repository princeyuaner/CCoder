package com.ccoder.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
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
import javax.swing.event.DocumentEvent

/** `JBPasswordField` 打码时用的字符。显式写出来，是因为那只眼睛要拿它做比对。 */
private const val ECHO_MASKED = '•'

/** 打开设置对话框，停在「模型」页。 */
fun showModelProfilesDialog(project: Project) {
    ModelProfilesDialog(project, ModelProfiles.getInstance()).show()
}

/**
 * 设置对话框（设计稿方案 A）。860×540，三栏：左页签 | 模型列表 | 编辑表单。
 *
 * **改动即时保存** —— 没有"应用"按钮，也没有"取消"回滚。所以字段的监听器
 * 直接写回 [ModelProfiles]，不攒 pending 副本：没有副本，就没有"忘了保存"。
 *
 * 它只读写配置，**不碰当前会话** —— 选中态改掉之后由 ClaudePanel 决定
 * 要不要重开会话。设置界面自己去动会话会把两处的生命周期缠在一起。
 *
 * [profiles] 由调用方传进来，而不是在这里 `ModelProfiles.getInstance()` ——
 * 同 [ClaudeSettings.toStartParams] 的理由：测试环境里没有 Application 服务，
 * 而对话框的渲染探针恰恰要在那种环境里跑。拿服务是入口 [showModelProfilesDialog]
 * 的事。
 */
internal class ModelProfilesDialog(
    private val project: Project,
    private val profiles: ModelProfiles,
) : DialogWrapper(true) {

    /** 当前正在编辑的副本。null = 一条都没选中。 */
    private var editing: ModelProfile? = null

    private val listSlot = JPanel()
    private val formSlot = JPanel()

    init {
        title = "设置"
        setSize(860, 540)
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(tabsColumn(), BorderLayout.WEST)
        add(listColumn(), BorderLayout.CENTER)
        add(formColumn(), BorderLayout.EAST)
    }

    /**
     * 底部左侧多一句「改动即时保存」（设计稿的 footer 就有）。
     *
     * **不是为了好看**：右边那对按钮里的「取消」在这页上**不回滚任何东西**，
     * 不写清楚，用户会拿它当撤销用 —— 点完发现改过的还在，才知道被骗了。
     * 所以这句必须贴着那两个按钮，放进表单里就没这个作用了。
     */
    override fun createSouthPanel(): JComponent {
        val base = super.createSouthPanel()
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JBLabel("改动即时保存").apply { foreground = UIUtil.getInactiveTextColor() },
                BorderLayout.WEST,
            )
            add(base, BorderLayout.CENTER)
        }
    }

    /**
     * 左栏 132px。
     *
     * 本版**只放「模型」一项** —— 不摆"通用/权限/关于"的空壳。空壳点不动，
     * 用户会先以为是自己点错了，再以为是坏的（spec §7）。
     */
    private fun tabsColumn(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10, 8)
        preferredSize = Dimension(JBUI.scale(132), 0)
        add(JBLabel("模型").apply {
            border = JBUI.Borders.empty(6, 9)
            foreground = UIUtil.getLabelForeground()
        })
        add(Box.createVerticalGlue())
    }

    private fun listColumn(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(14, 12)
        preferredSize = Dimension(JBUI.scale(250), 0)
        conflictWarning()?.let {
            add(it)
            add(Box.createVerticalStrut(JBUI.scale(8)))
        }
        add(listSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        })
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(addButton())
        add(Box.createVerticalGlue())
    }

    /**
     * `envOverrides` 与模型配置抢同一批变量时的警告条（spec §6）。
     *
     * 没有冲突就返回 null —— 一条"一切正常"的常驻提示只会变成噪音。
     * 放**列表上方**而不是表单里：冲突是整页的事，与当前在编辑哪一条无关。
     */
    private fun conflictWarning(): JComponent? {
        val keys = conflictingEnvKeys(ClaudeSettings.getInstance(project).envOverrides)
        if (keys.isEmpty()) return null

        // 中栏放得下 226px（250 减左右各 12 的内边距），这句话却要 321px 起 —— 三把键
        // 都在时更长。不折行就只剩半句：实测被裁在"会被选中"，而"的模型配置覆盖"
        // 才是重点（说清谁会覆盖谁）。
        val width = JBUI.scale(226)
        val text = JBTextArea("设置里的 ${keys.joinToString("、")} 会被选中的模型配置覆盖").apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            foreground = UIUtil.getInactiveTextColor()
            border = JBUI.Borders.empty()
            // JTextArea 默认是等宽体，跟同栏的列表项不像一家的
            font = UIUtil.getLabelFont()
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // 折行后的高度只能自己量：宽度先喂进去，preferredSize 才按折行后的行数算。
        // 这里封顶必须**等于**量出来的高度 —— 给 Int.MAX_VALUE 的话这条就成了中栏的
        // 弹簧，实测吃掉 330px 把列表挤到垂直中间去（折行要的是宽度，不是高度）。
        text.setSize(width, Int.MAX_VALUE)
        text.maximumSize = Dimension(width, text.preferredSize.height)
        return text
    }

    private fun formColumn(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(14, 16)
        preferredSize = Dimension(JBUI.scale(440), 0)
        add(formSlot, BorderLayout.NORTH)
    }

    /** 「＋ 添加模型」：建一条空配置并立刻进入编辑。 */
    private fun addButton(): JComponent = JBLabel("＋ 添加模型").apply {
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
     * 中栏内容：一条配置一行。
     *
     * 行上有**两个互不相干**的信号，别把它们并成一个：
     *   - 背景高亮 = **我正在编辑哪条**（点出来的，只在本次会话里有意义）
     *   - 右侧「使用中」= **哪条在生效**（`ModelProfiles.selectedId()`，是落盘的配置）
     * 它们回答的是两个问题（"我在改谁"和"谁在跑"），合成一个就会出现"点开看看
     * 就把在用的模型换掉了"这种事。
     */
    private fun rebuildList() {
        listSlot.removeAll()
        val inUse = profiles.selectedId()
        profiles.profiles().forEach { p ->
            listSlot.add(listRow(p, isEditing = p.id == editing?.id, inUse = p.id == inUse))
        }
        listSlot.revalidate()
        listSlot.repaint()
    }

    private fun listRow(p: ModelProfile, isEditing: Boolean, inUse: Boolean): JComponent {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = if (isEditing) UIUtil.getListSelectionBackground(true) else UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(6, 9)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(
                JBLabel(p.displayName()).apply { foreground = UIUtil.getLabelForeground() },
                BorderLayout.CENTER,
            )
            // 放右边而不是名字前面：文字长短不一，前置标记会让名字各起一行
            if (inUse) {
                add(
                    JBLabel("使用中").apply { foreground = UIUtil.getInactiveTextColor() },
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
     * 右栏表单。
     *
     * **字段顺序不能变**：名称 → Base URL → 认证方式 → API Key → 模型 ID。
     * 「认证方式」必须在密钥**之前** —— 它决定密钥填的是哪一种，
     * 先填密钥再选方式是反着的（spec §7）。
     */
    private fun rebuildForm() {
        formSlot.removeAll()
        formSlot.layout = BoxLayout(formSlot, BoxLayout.Y_AXIS)

        val p = editing
        if (p == null) {
            formSlot.add(JBLabel("选一条配置，或点「＋ 添加模型」").apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = Component.LEFT_ALIGNMENT
            })
            formSlot.revalidate()
            formSlot.repaint()
            return
        }

        val name = JBTextField(p.name)
        val url = JBTextField(p.baseUrl)
        val modelId = JBTextField(p.modelId)
        val authKind = ComboBox(AuthKind.entries.toTypedArray()).apply {
            // 显示 label 而不是枚举名：用户不该看到 AUTH_TOKEN 这种给代码看的词。
            // 只在**这里**翻译，不去覆写 toString()（那会连日志里的名字一起改掉）
            renderer = SimpleListCellRenderer.create("") { it.label }
            selectedItem = p.authKindEnum()
        }
        // 密钥不从 ModelProfile 取 —— 它住在 PasswordSafe 里。
        // 用 JBPasswordField 而不是普通输入框：这是唯一一个明文写在屏幕上就等于泄漏的字段
        val secret = JBPasswordField().apply { text = profiles.secretOf(p.id) }

        fun save() {
            val next = p.copy(
                name = name.text,
                baseUrl = url.text,
                modelId = modelId.text,
                authKind = (authKind.selectedItem as AuthKind).name,
            )
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

        listOf(name, url, modelId, secret).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = save()
            })
        }
        authKind.addActionListener { save() }

        formSlot.add(field("名称", name))
        formSlot.add(field("Base URL", url))
        formSlot.add(field("认证方式", authKind))
        formSlot.add(field("API Key", secretField(secret)))
        formSlot.add(field("模型 ID", modelId))

        // 删除放**底部左**，与"关闭"分开 —— 它和"保存这次编辑"不是一类动作
        formSlot.add(Box.createVerticalStrut(JBUI.scale(16)))
        formSlot.add(JBLabel("删除").apply {
            foreground = JBColor.namedColor("Component.errorFocusColor", JBColor.RED)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    profiles.remove(p.id)   // remove() 负责一并清掉密钥
                    editing = null
                    refresh()
                }
            })
        })

        formSlot.revalidate()
        formSlot.repaint()
    }

    /**
     * 密钥框 + 那只眼睛。
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
            toolTipText = "显示/隐藏密钥"
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
            add(eye, BorderLayout.EAST)
        }
        // 外面套了一层，宽度就不再由输入框自己撑开 —— 不设这条这个框会比别家窄一截
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    /**
     * 一个字段：上面标签、下面输入框。
     *
     * 三处 `alignmentX = LEFT_ALIGNMENT` 都是必需的，不是装饰：BoxLayout 在交叉轴上
     * 按 alignmentX 摆放子件，而 JComponent 的默认值是**居中**（0.5）。不压到 0，
     * 标签会各自居中、彼此错开 —— 实测"名称"在 x=31、"认证方式"在 x=202，
     * 而输入框一律 x=0，整张表单看起来是歪的。
     */
    private fun field(label: String, input: JComponent): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyBottom(10)
        alignmentX = Component.LEFT_ALIGNMENT
        add(JBLabel(label).apply {
            foreground = UIUtil.getInactiveTextColor()
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = Component.LEFT_ALIGNMENT
        })
        input.alignmentX = Component.LEFT_ALIGNMENT
        add(input)
    }
}
