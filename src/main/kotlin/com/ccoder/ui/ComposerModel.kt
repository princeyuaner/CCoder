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
 * 切换弹层的内容：**左栏配置、右栏模型**（2026-10-13 用户从选型台选的 C 案，
 * 选型台 `web/tools/model-popup-height-view.mjs`）。
 *
 * 改版前是一条配置一张卡、全部平铺 —— 四条配置十个模型就要 518px 高，一直
 * 顶到屏幕上沿（弹层内容**没有上限也没有滚动**，见 `popupHeightOf`）。两栏
 * 把"看哪条"与"选哪个"分开：左栏一列配置名，右栏只有**浏览中那条**的模型；
 * 高度掉到约 170，而且不再随模型总数增长。
 *
 * ## 浏览 ≠ 切换
 *
 * 点左栏只换右栏看什么（[onSelectProfile]），**不动**选中的模型；真正的切换
 * 还是点右栏的模型行（[onPick]）。所以开着弹层时有两件"当前"：左栏的**浏览位**
 * （底色那行）与真正在用的那条（行尾的小圆点）——分开是为了防误触：只想看看
 * 别条有什么模型，不该顺手把会话换了。
 *
 * 换浏览走的是"重开一次弹层"而不是就地换内容：弹层尺寸按内容撑，换一条高度
 * 就变一截，而平台弹层没有"改内容再改尺寸"的现成路数 —— 重建是这个代码库
 * 对动态弹层一贯的做法（同 [ClaudePanel.showDetailPopup]），状态由调用方持有。
 *
 * ## 其它
 *
 * 一次点击的后果分两类（当前那条是热切换、别的多半要重开会话），〔会重开会话〕
 * 因此挂在右栏卡头上 —— 只画当前浏览那条的后果，正好。判定走 [effectOf]，
 * 与 [ClaudePanel.switchModel] 是**同一个函数**：各判各的就会漂移，漂移的
 * 后果是弹层说"秒切"、实际把上下文丢了。
 *
 * **只负责"切"，不负责"改"** —— 编辑是设置那一页的事（出口是 [MANAGE_LABEL]）。
 * 卡与行本身住在 [PickerList] —— 三个弹层是同一套东西，样式只写一遍。
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
    /**
     * 左栏此刻停在（浏览）哪条配置上。null = 跟 [current] 那条走，还没有就第一条。
     *
     * 由调用方（[ClaudePanel]）持有：点左栏 = 重开一次弹层，状态不能只活在
     * 这一份会被重建的内容里。
     */
    browsedProfileId: String?,
    effectOf: (ModelProfile) -> PickEffect,
    onPick: (ModelPick) -> Unit,
    onSelectProfile: (String) -> Unit,
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
        val browsed = profiles.firstOrNull { it.id == browsedProfileId }
            ?: profiles.firstOrNull { it.id == current?.profileId }
            ?: profiles.first()
        addLeftAligned(
            profileColumns(
                profiles = profiles,
                browsed = browsed,
                current = current,
                effectOf = effectOf,
                onPick = onPick,
                onSelectProfile = onSelectProfile,
            )
        )
        addLeftAligned(gap(ACTION_GAP))
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

/** 左栏（配置索引）的宽度（未缩放 px）。 */
private const val PROFILE_COLUMN_WIDTH = 108

/** 左栏一行的行高（未缩放 px）。比模型行的 30 矮一档 —— 它是索引，不是主体。 */
private const val PROFILE_ROW_HEIGHT = 26

/** 两栏之间那道缝（未缩放 px）。 */
private const val COLUMN_GAP = 6

/**
 * 左栏配置名最多画到这（未缩放 px）。
 *
 * 从 [PROFILE_COLUMN_WIDTH] 里扣：边框 2 + 卡内衬 4 + 行内衬 12 + 行尾圆点 12，
 * 再留 2 的余量，剩 76。按**像素**截而不是按字数 —— 名字里中英混着
 * （「公司tokenhub」），一个字宽与一个字母宽差一倍，按字数截必然一边浪费
 * 一边溢出。真机数字由渲染探针复核。
 */
private const val PROFILE_NAME_MAX_PX = 76

/**
 * 两栏本体：左栏配置索引 + 右栏浏览中那条的模型卡。
 *
 * 两个"对齐陷阱"在这里处理掉：左右两栏各按内容高站（`BorderLayout` 默认把
 * WEST/CENTER 拉到整行高），左栏宽度钉死在常量上（见 [ProfileColumn]）。
 */
private fun profileColumns(
    profiles: List<ModelProfile>,
    browsed: ModelProfile,
    current: ModelPick?,
    effectOf: (ModelProfile) -> PickEffect,
    onPick: (ModelPick) -> Unit,
    onSelectProfile: (String) -> Unit,
): JComponent {
    val left = ProfileColumn().apply {
        profiles.forEach { p ->
            addLeftAligned(
                profileChoiceRow(
                    profile = p,
                    browsed = p.id == browsed.id,
                    inUse = p.id == current?.profileId,
                    onClick = { onSelectProfile(p.id) },
                )
            )
        }
    }
    val right = profileCard(browsed, effectOf(browsed), current, onPick)

    return JPanel(BorderLayout()).apply {
        isOpaque = false
        add(hugTop(left), BorderLayout.WEST)
        add(
            hugTop(right).apply { border = JBUI.Borders.emptyLeft(JBUI.scale(COLUMN_GAP)) },
            BorderLayout.CENTER,
        )
    }
}

/**
 * 左栏那一列（一张卡）。宽度钉死在 [PROFILE_COLUMN_WIDTH]：
 * 名字长短只该影响自己被截成几个字，不该让这一栏忽宽忽窄 —— 换浏览对象时
 * 整块布局会跳。
 */
private class ProfileColumn : CardPanel(warn = false) {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(2)
    }

    override fun getPreferredSize(): Dimension =
        Dimension(JBUI.scale(PROFILE_COLUMN_WIDTH), super.getPreferredSize().height)

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension =
        Dimension(JBUI.scale(PROFILE_COLUMN_WIDTH), Int.MAX_VALUE)
}

/** 竖直不拉伸：`BorderLayout` 会把 WEST/CENTER 的子件拉到整行高，这两栏得按自己内容高站。 */
private fun hugTop(c: JComponent): JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    add(c, BorderLayout.NORTH)
}

/**
 * 左栏一行：配置名，行尾一颗「在用」的点。
 *
 * 点它只换右栏看什么（[onSelectProfile] 那条路），**不动**当前选择 —— 想看看
 * 别条有什么模型，不该顺手把会话换了。所以它跟模型行长得同族、但**不给勾位**：
 * 勾是"选了"的意思，这里没有那个动作。
 */
private fun profileChoiceRow(
    profile: ModelProfile,
    browsed: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
): JComponent {
    val base = UIUtil.getLabelFont()
    val row = RoundedRow(selected = browsed, fixedHeight = PROFILE_ROW_HEIGHT).apply {
        add(
            JLabel(
                // 显式取字体（未挂到层级上时 getFont() 可能是 null，optionRow 里同一条）
                ellipsizeToWidth(profile.displayName(), JBUI.scale(PROFILE_NAME_MAX_PX), base)
            ).apply {
                font = base
                foreground = UIUtil.getLabelForeground()
            },
            BorderLayout.CENTER,
        )
        if (inUse) {
            add(InUseDot(), BorderLayout.EAST)
        }
    }
    handlerInto(
        row,
        object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick()
            }
        },
    )
    return row
}

/**
 * 「在用」的小圆点：左栏里只有真正在用的那条有它。
 *
 * 与**浏览位**（[RoundedRow] 的底色）是两件事：浏览只是"右栏现在画谁"，
 * 这个点回答"我现在跑在哪条上"。没有它，点到别的配置上之后就找不回来
 * 当前在哪了。
 */
private class InUseDot : JComponent() {
    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(12), JBUI.scale(12))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = accentColor()
            val d = JBUI.scale(5)
            g2.fillOval(width - d, (height - d) / 2, d, d)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 把 [text] 缩到不超过 [maxWidth] 像素：放不下就砍尾巴、补一个 `…`。
 *
 * 按**像素**而不是字数（理由见 [PROFILE_NAME_MAX_PX]）。从右边一格格收，
 * 同 [com.ccoder.ui.CompletionPopup] 的 `shrunkNameText` —— 名字都短，
 * 不值得二分。
 */
internal fun ellipsizeToWidth(text: String, maxWidth: Int, font: Font): String {
    // 借一个标签量字：FontMetrics 要组件出处，headless 下也一样能用
    val fm = JLabel().getFontMetrics(font)
    if (fm.stringWidth(text) <= maxWidth) return text
    for (kept in text.length - 1 downTo 1) {
        val cut = text.take(kept).trimEnd() + "…"
        if (fm.stringWidth(cut) <= maxWidth) return cut
    }
    return "…"
}

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
        // 它跟模型名一样大，五张卡上就是五块最响的色（设计稿里是 10.5 : 12.5）
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
