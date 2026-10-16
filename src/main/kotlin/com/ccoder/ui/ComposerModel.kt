package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import com.ccoder.settings.displayName
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.LayoutManager2
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.SwingConstants
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
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
 * 2026-10-14（用户从选型台 `web/tools/model-label-view.mjs` 挑的"乙 + 让法②"）：
 * 除了模型 ID，**配置的自定义名字也显示出来**，装在一枚小胶囊里 —— 两条配置
 * 完全可以同一个网关、同一族模型名（[ModelPick] 的注释原话），光看模型名认不出
 * "现在用的是哪条"。
 *
 * 从 [JLabel] 换成自绘：JLabel 给不了两样东西 —— 一枚胶囊底、以及"名字/模型"
 * 两段的排布。三档颜色、悬停亮起、可点这些**行为**照 [ModeLabel] 的一套
 *（并排站着，手感得一样）。
 *
 * 「让法②：缩模型保配置」在 [refreshBudget]：量工具栏那一行让出来多少宽，
 * 窄了先截模型、名字段保住（名字自己的上限见 [NAME_MAX_PX]）。量法与
 * [McpCacheBadge.refreshCompact] 同一条 —— 自己那格从"左组首选宽"里减掉再算，
 * 所以 [getPreferredSize] 用哪个预算都不影响量别人占了多少，不会抖。
 */
internal class ModelLabel(private val onOpen: () -> Unit) : JComponent() {

    private var profile: ModelProfile? = null
    private var hovered = false

    /** 量到的可用宽（-1 = 还没量到 —— 按不截断画）。 */
    private var budget = -1

    init {
        font = UIUtil.getLabelFont()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpen()
                }

                // 这行标签看着就是普通文字，不给悬停反馈就没人知道它能点。
                // [ModeLabel] 里是同一套写法 —— 两个标签并排站着，手感得一样
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    repaint()
                }
            }
        )
        // 窄窗让位：祖先尺寸一变就重新量（同 McpCacheBadge：光靠 doLayout 兜底
        // 轮不到"加进来就已经是终态尺寸、之后没人动过祖先"那种情况）
        addHierarchyBoundsListener(
            object : HierarchyBoundsAdapter() {
                override fun ancestorResized(e: HierarchyEvent) = refreshBudget()
            }
        )
    }

    fun setProfile(profile: ModelProfile?) {
        this.profile = profile
        refreshBudget()
        revalidate()
        repaint()
    }

    /** 这一帧要画的（测试与探针读它）。 */
    internal fun currentParts(): ModelLabelParts =
        modelLabelParts(profile, if (budget < 0) Int.MAX_VALUE else budget, font)

    /** 当前该用的文字色（测试读它；paint 也走这条）。 */
    internal fun textColorNow(): Color = modelLabelTextColor(hovered, profile != null)

    override fun getPreferredSize(): Dimension {
        val fm = labelMetrics(font)
        val parts = currentParts()
        val capW = parts.capsule?.let { fm.stringWidth(it) + CAPSULE_PAD_X * 2 + CAPSULE_GAP } ?: 0
        return Dimension(capW + fm.stringWidth(parts.text), fm.height)
    }

    /** 这一格定宽（像时钟键/发送键）：BoxLayout 不许拉伸它，间距才稳（同 McpCacheBadge）。 */
    override fun getMaximumSize(): Dimension = preferredSize

    override fun getMinimumSize(): Dimension = preferredSize

    override fun doLayout() {
        super.doLayout()
        refreshBudget()
    }

    /**
     * 量这一行里自己能拿到的宽度。行 = 本标签所在左组的父级
     *（`buildComposerToolbar` 装配的那只 BorderLayout）。
     * 链子对不上（探针单画、还没装配）就什么都不做 —— 保持不截断。
     */
    private fun refreshBudget() {
        val group = parent as? JComponent ?: return
        val row = group.parent as? JComponent ?: return
        val layout = row.layout as? BorderLayout ?: return
        val west = layout.getLayoutComponent(BorderLayout.WEST) as? JComponent ?: return
        val east = layout.getLayoutComponent(BorderLayout.EAST) as? JComponent ?: return
        // 还没布局过：量到的都是 0，等真尺寸落下来再说（doLayout / 祖先 resize 会再来）
        if (row.width <= 0) return

        // 自己那格从这里减掉再算 —— 用哪个预算，量出来的"别人占了多少"都一样
        val others = (west.preferredSize.width - preferredSize.width) + east.preferredSize.width
        val next = (row.width - others).coerceAtLeast(JBUI.scale(24))
        if (next == budget) return
        budget = next
        // 父容器缓存着"含我旧宽"的首选尺寸（Container.prefSize 与 BoxLayout 的
        // SizeRequirements）—— 显式作废整条链，不指望 validate 流程替我们清：
        // 离屏探针里没有那条流程，症状是 west 永远卡在旧值、把自己一路缩没；
        // 实机上清不清得了也不该赌
        var c: Container? = this
        while (c != null) {
            (c.layout as? LayoutManager2)?.invalidateLayout(c)
            c.invalidate()
            c = c.parent
        }
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = font
            val fm = g2.fontMetrics
            val parts = currentParts()
            val tone = textColorNow()
            val baseline = (height + fm.ascent - fm.descent) / 2

            var x = 0
            parts.capsule?.let { name ->
                val capW = fm.stringWidth(name) + CAPSULE_PAD_X * 2
                // 胶囊抱着基线长：上缘离 ascent 1px、下缘离 descent 1px，与文字共一条基线
                val padY = JBUI.scale(1)
                val capY = baseline - fm.ascent - padY
                val capH = fm.ascent + fm.descent + padY * 2
                // 胶囊底 = 标签前景 8% 压在底带上（同选型台 .mtag）：深色下看得见、
                // 浅色下不至于像一枚按钮
                g2.color = mix(composerFooterFill(), UIUtil.getLabelForeground(), CAPSULE_FILL)
                g2.fill(
                    RoundRectangle2D.Float(
                        x.toFloat(), capY.toFloat(), capW.toFloat(), capH.toFloat(),
                        JBUI.scale(8).toFloat(), JBUI.scale(8).toFloat(),
                    )
                )
                g2.color = tone
                g2.drawString(name, x + CAPSULE_PAD_X, baseline)
                x += capW + CAPSULE_GAP
            }
            g2.color = tone
            g2.drawString(parts.text, x, baseline)
        } finally {
            g2.dispose()
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
 * 标签上排出来的两段：胶囊里放什么（null = 不画胶囊）+ 胶囊后那一行（含展开箭头）。
 */
internal data class ModelLabelParts(val capsule: String?, val text: String)

/** 胶囊左右内边距（1x）。同选型台 `.mtag` 的 6px。 */
private val CAPSULE_PAD_X = JBUI.scale(6)

/** 胶囊与正文之间的间隙（同选型台 `.mtag` 的 margin-right 6）。 */
private val CAPSULE_GAP = JBUI.scale(6)

/** 胶囊底 = 标签前景压 8%（同选型台；深浅两套都由它派生，不另造颜色）。 */
private const val CAPSULE_FILL = 0.08

/**
 * 名字段自己的上限（像素）。超长的配置名也得截 —— 它不能把模型挤出窗口
 *（选型台「特殊态④」）。数字由 `ComposerModelRenderProbe` 量着看。
 */
internal val NAME_MAX_PX = JBUI.scale(96)

/**
 * 模型段的上限（像素）—— 旧版 `MAX_LABEL_CHARS = 24` 的同一条规矩，按像素重写：
 * 两段合排本来就要算像素，字数算术在中英混排下不准。数字同样由探针量着看。
 */
internal val MODEL_MAX_PX = JBUI.scale(150)

/**
 * 标签上排哪两段（2026-10-14「乙 + 让法②」）：
 *
 * - 没选中配置 → 只有「无模型」；
 * - 配置没指定模型 → 裸写 [ModelProfile.displayName]（那时它才是唯一说明；
 *   **不装胶囊** —— 后面没有模型跟着，独一枚胶囊看起来像按钮）；
 * - 有自定义名字 → **名字进胶囊**、模型跟在后面；[maxWidth] 不够时**先截模型**
 *   —— 名字保到 [NAME_MAX_PX] 为止（选型台的"让法②：缩模型保配置"）；
 * - 没名字 → 与旧版一字不差：只写模型，上限 [MODEL_MAX_PX]。
 *
 * 正文仍是**模型 ID**（切完模型一眼看得出来）；箭头（[EXPAND_CARET]）拼在
 * [ModelLabelParts.text] 的尾巴上 —— 它是文本的一部分，悬停时跟文本一起亮。
 */
internal fun modelLabelParts(profile: ModelProfile?, maxWidth: Int, font: Font): ModelLabelParts {
    val fm = labelMetrics(font)
    val caretW = fm.stringWidth(EXPAND_CARET)
    if (profile == null) {
        return ModelLabelParts(null, CcoderText.text("composer.model.none") + EXPAND_CARET)
    }
    val model = profile.modelId.trim()
    val name = profile.name.trim()
    if (model.isEmpty()) {
        // 没模型：显示名兜底（名字 → 未命名），与旧版一致
        return ModelLabelParts(
            null,
            ellipsizeToWidth(profile.displayName(), maxWidth - caretW, font) + EXPAND_CARET,
        )
    }
    if (name.isEmpty()) {
        val cap = minOf(maxWidth - caretW, MODEL_MAX_PX)
        return ModelLabelParts(null, ellipsizeToWidth(model, cap, font) + EXPAND_CARET)
    }
    val capsule = ellipsizeToWidth(name, NAME_MAX_PX, font)
    val capsuleW = fm.stringWidth(capsule) + CAPSULE_PAD_X * 2 + CAPSULE_GAP
    val cap = minOf(maxWidth - caretW - capsuleW, MODEL_MAX_PX)
    return ModelLabelParts(capsule, ellipsizeToWidth(model, cap, font) + EXPAND_CARET)
}

/**
 * 文字色三档（与 [ModeLabel] / [EffortLabel] 并排同一套）：悬停亮成常规文字色；
 * 有配置时是安静的次要文字；没配置再淡一档（点下去不是"切"，是去设置页）。
 *
 * 后两档在浅色主题里**恰好是同一个灰**（`Label.disabledForeground` 与
 * `Component.infoForeground` 都解到 #999999）—— 别看了觉得没生效就合并掉：
 * 取的是各自语义上对的那一档，换主题就分得开（同 [SessionChips] 那颗状态点的做法）。
 */
internal fun modelLabelTextColor(hovered: Boolean, hasProfile: Boolean): Color = when {
    hovered -> UIUtil.getLabelForeground()
    hasProfile -> UIUtil.getInactiveTextColor()
    else -> UIUtil.getLabelDisabledForeground()
}

/** FontMetrics 要组件出处，借一个标签（同 [ellipsizeToWidth] 的说法，headless 也能用）。 */
private fun labelMetrics(font: Font) = JLabel().getFontMetrics(font)

/** 悬停即览的等待（未缩放 ms）：防"指针路过左栏时右栏闪跳一串"。 */
internal const val HOVER_BROWSE_DELAY_MS = 130

/**
 * 悬停即览（2026-10-14 选型台 A 案）：左栏行悬停一小会后切右栏，点击立即切。
 *
 * 计时用 `javax.swing.Timer`（回调在 EDT 上，Swing 的单线程规矩不破）。
 * [fire] 是"计时到点该做的事"，**测试直接驱它** —— 真进程里由 Timer 到点调；
 * 睡等计时器会把用例做成赌时间。
 */
internal class BrowseHoverController(
    delayMs: Int = HOVER_BROWSE_DELAY_MS,
    private val browse: (String) -> Unit,
) {
    private var pending: String? = null
    private val timer = Timer(delayMs) { fire() }.apply { isRepeats = false }

    /** 悬停到了某个 id：重新起表 —— 连续划过只算最后停下的那条。 */
    fun hover(id: String) {
        pending = id
        timer.restart()
    }

    /** 指针离开左栏：挂起的那次不算。 */
    fun leave() {
        timer.stop()
        pending = null
    }

    /** 点击：立刻切，不等表走完。 */
    fun click(id: String) {
        timer.stop()
        pending = null
        browse(id)
    }

    internal fun fire() {
        val id = pending ?: return
        pending = null
        browse(id)
    }
}

/**
 * 切换弹层的内容：**左栏配置、右栏模型**（2026-10-13 用户从选型台选的 C 案，
 * 选型台 `web/tools/model-popup-height-view.mjs`；2026-10-14 按 A 案改，
 * `web/tools/model-popup-browse-view.mjs`）。
 *
 * 改版前是一条配置一张卡、全部平铺 —— 四条配置十个模型就要 518px 高，一直
 * 顶到屏幕上沿（弹层内容**没有上限也没有滚动**，见 `popupHeightOf`）。两栏
 * 把"看哪条"与"选哪个"分开：左栏一列配置名，右栏只有**浏览中那条**的模型。
 *
 * ## 悬停即览 + 定高（2026-10-14 A 案）
 *
 * 两栏上岗后用户报了两个人机问题，这一案一起修：
 *
 * - **"要点击才会显示子列表"** → 左栏行**悬停**一小段（[HOVER_BROWSE_DELAY_MS]）
 *   就换右栏（[BrowseHoverController]）；点击照旧立即切 —— 老手势不废，
 *   "想看看别条有什么模型"从此不用动手。
 * - **"换浏览时弹层高度跟着跳"**（浏览只有 1 个模型的那条 158px ↔ 有 4 个的
 *   206px；根因是换浏览走 cancel + 重开，尺寸按内容撑）→ 右栏的四条卡
 *   **打开时全部建好**，`CardLayout` 一次只显示一条；它的首选尺寸 =
 *   **最大的那张卡**，于是弹层打开时定高一次，之后只换内容、不重建、不跳。
 *   `ClaudePanel.browseModelProfile` 那条重开路已删。
 *
 * ## 浏览 ≠ 切换
 *
 * 悬停/点左栏只换右栏看什么，**不动**选中的模型；真正的切换还是点右栏的
 * 模型行（[onPick]）。所以开着弹层时有两件"当前"：左栏的**浏览位**
 * （底色那行）与真正在用的那条（行尾的小圆点）——分开是为了防误触：只想看看
 * 别条有什么模型，不该顺手把会话换了。
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
     * 左栏**初始**停在哪条配置上（浏览位）。null = 跟 [current] 那条走，
     * 还没有就第一条。
     *
     * 只是初值：之后换浏览是弹层内部的事（悬停/点击，就地换卡，A 案）。
     * 调用方都从"当前那条"开局（[ClaudePanel.openModelChooser]）——
     * 多数人来换的就是它。
     */
    browsedProfileId: String?,
    effectOf: (ModelProfile) -> PickEffect,
    onPick: (ModelPick) -> Unit,
    onManage: () -> Unit,
    /**
     * 悬停即览的等待。**测试注入一个大值把计时器钉在场外** —— "悬停不立即切"
     * 这类断言就不必赌时间；生产一律走默认 [HOVER_BROWSE_DELAY_MS]。
     */
    hoverDelayMs: Int = HOVER_BROWSE_DELAY_MS,
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
                hoverDelayMs = hoverDelayMs,
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
 *
 * 右栏是 [CardLayout]：**全部卡建好、只显示一条**。定高是它的白拿项 ——
 * 首选尺寸取**所有子件里的最大者**，与此刻显示哪条无关（A 案：弹层用它定
 * 一次尺寸，之后换内容只 `show()`，不重建）。换浏览是弹层内部的事
 * （悬停 [BrowseHoverController] 或点击），不再出走给调用方。
 */
private fun profileColumns(
    profiles: List<ModelProfile>,
    browsed: ModelProfile,
    current: ModelPick?,
    effectOf: (ModelProfile) -> PickEffect,
    onPick: (ModelPick) -> Unit,
    hoverDelayMs: Int,
): JComponent {
    // 右栏：卡全部建好 —— 一个人几条配置，就是几张小卡，值。
    // 每张套一层 [hugTop]：CardLayout 会把显示的卡拉满容器高，短卡不该被抻长
    //（选型台里浏览 1 个模型的配置时，卡是自然高度、下方留空）。
    val cards = CardLayout()
    val cardsPanel = JPanel(cards).apply { isOpaque = false }
    profiles.forEach { p -> cardsPanel.add(hugTop(profileCard(p, effectOf(p), current, onPick)), p.id) }
    // CardLayout 默认露**第一个**加入的卡，不是浏览位那条 —— 先把初态摆正
    cards.show(cardsPanel, browsed.id)

    // 浏览位：底色那行跟着走。rows 在下面装配左栏时填满。
    val rows = HashMap<String, RoundedRow>()
    var shown = browsed.id
    fun showBrowsed(id: String) {
        if (id == shown) return
        shown = id
        cards.show(cardsPanel, id)
        rows.forEach { (pid, row) -> row.setSelected(pid == id) }
    }

    val hover = BrowseHoverController(hoverDelayMs) { id -> showBrowsed(id) }

    val left = ProfileColumn().apply {
        profiles.forEach { p ->
            val row = profileChoiceRow(
                profile = p,
                browsed = p.id == browsed.id,
                inUse = p.id == current?.profileId,
                onClick = { hover.click(p.id) },
                onHover = { hover.hover(p.id) },
            )
            rows[p.id] = row
            addLeftAligned(row)
        }
        // 指针整个离开左栏才取消挂起的那次切换 —— 行与行之间移动不取消
        // （enter 会重置计时：连续划过只算最后停下的那条）
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    hover.leave()
                }
            }
        )
    }

    return JPanel(BorderLayout()).apply {
        isOpaque = false
        add(hugTop(left), BorderLayout.WEST)
        add(
            hugTop(cardsPanel).apply { border = JBUI.Borders.emptyLeft(JBUI.scale(COLUMN_GAP)) },
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
 * 悬停（[onHover]，等 [HOVER_BROWSE_DELAY_MS] 由控制器决定何时切）与点击
 * （[onClick]，立即）都只换右栏看什么，**不动**当前选择 —— 想看看别条有什么
 * 模型，不该顺手把会话换了。所以它跟模型行长得同族、但**不给勾位**：勾是
 * "选了"的意思，这里没有那个动作。
 */
private fun profileChoiceRow(
    profile: ModelProfile,
    browsed: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
    onHover: () -> Unit,
): RoundedRow {
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
    // 悬停也要算数整棵子树：名字标签盖着行的绝大部分，只挂行自己会"压在字上不算数"
    //（同 [handlerInto] 的道理，那条注释里踩过一次）
    handlerInto(
        row,
        object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                onHover()
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
