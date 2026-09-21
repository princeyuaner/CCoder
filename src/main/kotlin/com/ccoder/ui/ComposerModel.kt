package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import com.ccoder.settings.displayName
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.SwingConstants
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import com.ccoder.text.CcoderText

/**
 * 弹层最底下的"去改"入口。
 *
 * 实现与测试共用，免得两边各写一遍然后漂移（同 [MARK]）。
 */
internal val MANAGE_LABEL: String get() = CcoderText.text("composer.model.manage")

/** 一条配置都没有时的说明。弹层空着看起来像抽风，得讲清楚并给出唯一的出路。 */
private val NO_PROFILE_LABEL: String get() = CcoderText.text("composer.model.noProfiles")

/**
 * 模型标签。可点，点了弹列表切换。
 *
 * 照 [ModeLabel] 做 —— 同一行里两个标签，一个能点一个不能会很扎眼。
 * 它只负责**显示与转发点击**：弹层内容与切换之后什么时候改这个标签，
 * 都由 [ClaudePanel] 决定。
 *
 * 箭头用的是 [EXPAND_CARET]，与 [ModeLabel] 同一个常量而不是各写一份：
 * 两个字符一旦不同，这两行并排站着就会看得很清楚。
 */
internal class ModelLabel(private val onOpen: () -> Unit) : JLabel() {

    private var hovered = false

    /** 当前是否真的有配置被选中。没配置时用更淡一档的色。 */
    private var hasProfile = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIUtil.getLabelDisabledForeground()
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpen()
                }

                // 这行标签看着就是普通文字，不给悬停反馈就没人知道它能点。
                // [ModeLabel] 里是同一套写法 —— 两个标签并排站着，手感得一样
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    applyForeground()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    applyForeground()
                }
            }
        )
    }

    fun setProfile(profile: ModelProfile?) {
        text = modelLabelText(profile) + EXPAND_CARET
        hasProfile = profile != null
        applyForeground()
        repaint()
    }

    /**
     * 三档颜色，越往后越淡：
     *
     * - 悬停时亮成常规文字色 —— 这是"能点"的唯一提示。
     * - 有配置时是安静的次要文字：它是状态，不该跟发送键抢注意力。
     * - 没配置时再淡一档：点下去不是"切"，是去设置页。先用颜色说明这一点，
     *   省得用户点开一个空弹层才发现。
     *
     * 后两档在浅色主题里**恰好是同一个灰**（`Label.disabledForeground` 与
     * `Component.infoForeground` 都解到 #999999）—— 别看了觉得没生效就合并掉：
     * 取的是各自语义上对的那一档，换主题就分得开（同 [SessionChips] 那颗状态点的做法）。
     */
    private fun applyForeground() {
        foreground = when {
            hovered -> UIUtil.getLabelForeground()
            hasProfile -> UIUtil.getInactiveTextColor()
            else -> UIUtil.getLabelDisabledForeground()
        }
    }
}

/**
 * 弹层里的一项：**哪条配置里的哪个模型**。
 *
 * 两级而不是一级 —— 一条配置可以有多个模型，光有模型名认不出是哪来的
 * （两条配置完全可以是同一个网关、同一族模型名）。
 */
internal data class ModelPick(val profileId: String, val modelId: String)

/**
 * 标签上写什么。
 *
 * 显示的是**当前模型 ID** 而不是配置名：同一套 URL 下的两个模型正是靠它区分，
 * 而换模型之后还写着配置名的话，标签上看不出刚才切了没有。
 *
 * 三种回退：没有配置 → 「无模型」；这条配置没指定模型 → 配置名（那时它才是
 * 唯一能说明"现在用哪条"的东西，由 [ModelProfile.displayName] 给出）；
 * 其余 → 模型 ID。回退规则只有那一处，这里委托过去而不是再抄一遍。
 *
 * 模型 ID 是用户填的、没有上限，而这一行还并排站着权限与思考两个标签 ——
 * 不截断的话一个长名字会把那两个挤出工具窗口。
 */
internal fun modelLabelText(profile: ModelProfile?): String {
    if (profile == null) return CcoderText.text("composer.model.none")
    val model = profile.modelId.trim()
    return when {
        model.isEmpty() -> profile.displayName()
        model.length <= MAX_LABEL_CHARS -> model
        else -> model.take(MAX_LABEL_CHARS) + "…"
    }
}

/**
 * 标签上最多写到这个长度。
 *
 * 具体数字由 `ComposerModelRenderProbe` 在 420px 下量出来（观感的事看属性
 * 看不出来）—— 三个标签并排，模型名是最长的那一个。
 */
private const val MAX_LABEL_CHARS = 24

/**
 * 切换弹层的内容。**一条配置一张卡**：卡头写"这是哪条、打哪台机器"，
 * 卡里每个模型一行。
 *
 * 分组而不是平铺，是因为一次点击的后果分两类，而这两类正好按组分开：
 * **当前组是热切换**（会话与上下文都留着），**别的组多半要重开会话**
 * （端点或凭证变了，`options.env` 烤在子进程里）。卡头右侧因此挂一个
 * 〔会重开会话〕，把这件事在点之前就说清楚。
 *
 * **卡而不是一条灰字组头**（2026-09-21 改，设计稿 `docs/design/model-picker.html`
 * 的 B 案）：组头原先是 12px 的灰字，跟下面的模型行同色同重，三条配置摆在一起
 * 只能靠"哪一行短"去猜边界。卡有圆角、有边、卡片之间留 8px，边界不用猜。
 *
 * 卡与行本身住在 [PickerList] —— 权限模式与思考深度那两个弹层是同一套东西，
 * 样式只写一遍。
 *
 * 后果的判定走 [effectOf]，与 [ClaudePanel.switchModel] 是**同一个函数** ——
 * 各判各的就会漂移，而漂移的后果是弹层说"秒切"、实际把上下文丢了。
 *
 * **只负责"切"，不负责"改"** —— 编辑是设置那一页的事。把表单塞进来会让
 * "点一下切换"变成"点一下进表单"，高频动作被低频动作拖累。要改配置，
 * 出口是最后那行 [MANAGE_LABEL]。
 */
internal fun buildModelList(
    profiles: List<ModelProfile>,
    /**
     * 这个**会话标签**在用的是哪个（配置 + 模型）。null = 还没选。
     *
     * 2026-09-21 起由调用方给，不从 [ModelProfiles] 里取 —— 选中态按标签分了，
     * 弹层上的勾必须跟着**这个标签**，否则会出现"勾在 A 上、这个标签跑的是 B"。
     */
    current: ModelPick?,
    effectOf: (ModelProfile) -> PickEffect,
    onPick: (ModelPick) -> Unit,
    onManage: () -> Unit,
): JComponent = cardColumn().apply {
    if (profiles.isEmpty()) {
        addLeftAligned(
            JLabel(NO_PROFILE_LABEL).apply {
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.empty(6, 6)
            }
        )
        addLeftAligned(gap(ACTION_GAP))
    } else {
        profiles.forEach { p ->
            addCard(profileCard(p, effectOf(p), current, onPick))
        }
    }

    addLeftAligned(actionRow(MANAGE_LABEL, onManage))
}

/** 卡片区与最后那行动作之间的缝（同卡片之间那一道，看起来才是一列的收尾）。 */
private const val ACTION_GAP = 8

/** 一条配置：一张卡，卡头 + 卡里每个模型一行。 */
private fun profileCard(
    profile: ModelProfile,
    effect: PickEffect,
    current: ModelPick?,
    onPick: (ModelPick) -> Unit,
): JComponent = optionCard(
    header = cardHeader(profile, effect),
    // 一条没配模型的配置也是合法配置（官方端点用 CLI 默认模型那种），
    // 所以它也得有一行可以选 —— 只有卡头的话，这条配置就成了纯装饰
    rows = profile.modelIds.ifEmpty { listOf("") }.map { modelRow(profile, it, current, onPick) },
)

/**
 * 卡头：配置名 + 端点主机，右侧在需要时挂〔会重开会话〕。
 *
 * 名字与主机拆成两个标签而不是拼成一串（原来是 `"名字 · 主机"`）：
 * 它俩在卡里是**主次**关系，主机只是用来认出"这是哪条"的补充信息，
 * 拼在一起就只能一个颜色，两个都得读。
 *
 * 主机不引等宽字体：设计稿里它是等宽的，但那要往 UI 里拉一个新字族，
 * 而这里靠"小一号 + 次要色"已经够了。
 *
 * 它不响应点击 —— 点它是"选这条配置"，而那件事没有意义（配置不是可切的对象，
 * **模型**才是）。做成可点会让人以为点了能切换，实际什么都不发生。
 */
private fun cardHeader(profile: ModelProfile, effect: PickEffect): JComponent {
    val base = UIUtil.getLabelFont()
    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        // 下面多留一点：卡头与第一行模型之间要能看出是两件事
        border = JBUI.Borders.empty(4, 4, 6, 4)
    }

    row.add(
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(
                JLabel(profile.displayName()).apply {
                    foreground = UIUtil.getLabelForeground()
                    // 加粗是**唯一**把卡头与模型行分开的东西 —— 两边都是常规文字色，
                    // 就差这一个字重（行里靠勾位和底色，卡头没有那些）
                    font = base.deriveFont(Font.BOLD)
                }
            )
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(
                JLabel(endpointLabel(profile.baseUrl)).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    font = base.deriveFont(base.size2D - 1f)
                }
            )
        },
        BorderLayout.WEST,
    )
    // 后果写在**点之前**（见 restartBadge）。右对齐：卡头本身长度不一，
    // 挂在左边会让这一列参差不齐
    restartBadge(effect)?.let { badge ->
        row.add(
            RestartChip(badge).apply {
                // 留一道缝：BorderLayout 的 WEST 与 EAST 之间没有默认间距，
                // 最长的那一行（也就是把整行宽度撑满的那一行）会贴在一起
                border = JBUI.Borders.emptyLeft(8)
            },
            BorderLayout.EAST,
        )
    }
    return row
}

/** 一行模型的名字。空的那一行（配置没指定模型）也要说清楚，不能是空白。 */
internal fun modelRowText(modelId: String): String =
    modelId.trim().ifEmpty { NO_MODEL_LABEL }.let {
        if (it.length <= MAX_MODEL_CHARS) it else it.take(MAX_MODEL_CHARS) + "…"
    }

/**
 * 模型名再长也只写到这个长度。
 *
 * 定在 36 而不是更小：真实的模型名有 31 个字符的
 * （`deepseek-v4-flash-vision-exp[1m]`），卡到 30 会把一个**正常**的名字切掉，
 * 而弹层里被切掉的名字正是用来分辨选哪一行的。这里只挡病态的输入。
 * 上限本身由渲染探针在 420px 下确认。
 */
private const val MAX_MODEL_CHARS = 36

/** 一条配置没指定模型时那一行写什么。 */
internal val NO_MODEL_LABEL: String get() = CcoderText.text("composer.model.unspecified")

/**
 * 主机名再长也只写到这个长度。
 *
 * 弹层的宽度是**内容撑开的**，而主机名是用户填的、没有上限的字符串。
 * 实测一条 `https://api.anthropic-relay.internal.corp.example.com` 就能把
 * 弹层撑到 442px —— 比工具窗口的 420px 还宽。这里截断，让最长的一行
 * 也塞得进它要去的那块地方。
 *
 * 卡版比组头版更吃宽度（卡自己还有内边距与边），所以这个数在 2026-09-21
 * 换卡片时**重新量过**（见 `ComposerModelRenderProbe` 打的那行宽度）。
 */
private const val MAX_HOST_CHARS = 22

/** 端点只留主机名：完整 URL 太长，而"哪台机器"才是用来区分两条配置的信息。 */
internal fun endpointLabel(baseUrl: String): String {
    val url = baseUrl.trim()
    if (url.isEmpty()) return CcoderText.text("composer.model.officialEndpoint")
    val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/').trim()
    // 只输了半截地址（"https://"）时宁可原样显示 —— 说成"官方端点"是反的
    if (host.isEmpty()) return url
    return if (host.length <= MAX_HOST_CHARS) host else host.take(MAX_HOST_CHARS) + "…"
}

/**
 * 一行模型。
 *
 * 当前项判的是**两级**：既要是选中的那条配置，也要是它当前那个模型。
 * 只判配置的话，一条配置的多个模型会一起打上勾。
 */
private fun modelRow(
    profile: ModelProfile,
    modelId: String,
    current: ModelPick?,
    onPick: (ModelPick) -> Unit,
): JComponent = optionRow(
    name = modelRowText(modelId),
    // 与权限模式那边不同：这里颜色不传达任何东西，档位名就是常规文字色
    nameColor = UIUtil.getLabelForeground(),
    description = null,
    // 判的是**两级**：既要是这个标签选的那条配置，也要是它当前那个模型。
    // 只判配置的话，一条配置的多个模型会一起打上勾
    selected = profile.id == current?.profileId && modelId == current?.modelId,
    onClick = { onPick(ModelPick(profile.id, modelId)) },
)

/**
 * 〔会重开会话〕那颗胶囊。
 *
 * 从灰字改成一颗带边、带底的琥珀胶囊：这是弹层里最贵的一句话（点下去这段
 * 上下文就没了），而它原先跟卡头同色同重，看上去像装饰。颜色取 [warningColor]，
 * 与绕过权限那颗警示色同源。
 */
private class RestartChip(text: String) : JLabel(text) {

    init {
        val base = UIUtil.getLabelFont()
        // 小两号而不是一号：它是**提示**，不是这一行的正文 —— 探针里一号时
        // 它跟模型名一样大，四张卡上就是四块最响的色（设计稿里是 10.5 : 12.5）
        font = base.deriveFont(base.size2D - 2f)
        foreground = warningColor()
        isOpaque = false
        // 文字**必须居中**：`JLabel` 默认是左对齐，会把它画在边框内侧的最左边 ——
        // 而胶囊是铺满整个边框内侧的，于是左边一点气都没有、右边空出 [CHIP_PAD_X]
        // 那两口气，看着就是"文字没居中"（2026-09-21 在真机上发现的：探针 1:1 时
        // 只有十几像素的偏差，得放大才看得清）
        horizontalAlignment = SwingConstants.CENTER
    }

    /**
     * 胶囊横着比文字多两口气。
     *
     * 竖着只多一口气 —— 它会被 `BorderLayout.EAST` 拉到整行高，那时**不能**
     * 跟着长，否则胶囊成了一个高盒子（下面按首选高度居中画）。
     */
    override fun getPreferredSize(): Dimension {
        val text = super.getPreferredSize()
        return Dimension(
            text.width + JBUI.scale(CHIP_PAD_X) * 2,
            text.height + JBUI.scale(CHIP_PAD_Y) * 2,
        )
    }

    override fun paintComponent(g: Graphics) {
        // 胶囊画在**边框以内**：卡头给的那道 8px 缝也是这个标签的边框，
        // 不算进来的话胶囊会盖住那道缝、跟主机名贴在一起
        val ins = insets
        val pillW = (width - ins.left - ins.right).toFloat()
        val pillH = (preferredSize.height - ins.top - ins.bottom).toFloat()
        val pillX = ins.left.toFloat()
        val pillY = (height - pillH) / 2f

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val warn = warningColor()
            val panel = UIUtil.getPanelBackground()
            val shape = RoundRectangle2D.Float(
                pillX + 0.5f, pillY + 0.5f, pillW - 1f, pillH - 1f, pillH, pillH,
            )
            g2.color = blend(warn, panel, 0.12)
            g2.fill(shape)
            g2.color = blend(warn, panel, 0.42)
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** 胶囊的横向内边距。 */
private const val CHIP_PAD_X = 7

/** 胶囊的纵向内边距。 */
private const val CHIP_PAD_Y = 1
