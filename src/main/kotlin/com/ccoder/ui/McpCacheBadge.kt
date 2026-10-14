package com.ccoder.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import javax.swing.JComponent
import javax.swing.Timer

/**
 * 「MCP 还没连完」的常驻记号 —— 输入框**工具栏右端**的小徽章
 * （2026-10-14 选型台 `web/tools/mcp-corner-view.mjs` 里的乙案，用户选定）。
 *
 * 前身是输入框上方那条全宽带子（`McpCacheWarnBar`，同日撤掉）：全宽带子一直占着
 * 输入框上方那一格，而它说的其实是"此刻这一发"的事 —— 挪到发送键left边挨着站，
 * 与时钟键、发送键同一行。
 *
 * ## 五种形态（照旧带子那套，只换了长法）
 *
 *  - **Pending**：琥珀。只写状态词（"MCP 连接中"）——整句放不下工具栏那一行；
 *    全句与正在连的名字都在悬停里（同一句文案在 `McpCacheWarn.kt` 头注有原委）。
 *  - **Settled**：绿，**六秒后自动隐去**。它是"刚才那件事办完了"的收尾 ——
 *    只在先前显示过（[active]）时出现，凭空冒出一条绿的在屏幕上没人看得懂。
 *  - **Failed**：红，常驻且整颗可点（开 MCP 设置页）。**不受 worth 门槛管** ——
 *    值不值当开口那条判据只说"缓存这一发贵不贵"（只认 pending），而失败是"配置
 *    出了问题"，跟会话长短没关系，藏着才是骗人（旧带子的注释就是这么写的，
 *    但旧实现的值当闸把它一并吞了；这里按设计意图放行）。
 *  - **Compact**：窄窗让位 —— 工具栏一行放不下带字的徽章时，字退成点（见
 *    [cacheWarnNeedsCompact]），全句照旧在悬停里。
 *  - **Hidden**。
 *
 * ## 为什么从"带子"变成"自绘的小胶囊"
 *
 * 工具行里住着三颗自绘控件（附件/时钟/发送），平台的 `JButton` 在这套 New UI 下
 * 自带 72px 的最小宽度（见 `IdeLaf` 的实测），塞一颗进去整行的间距就毁了。所以照
 * `ScheduleButton` 的模子自己画：`RoundedLineBorder` 描边（四角均匀那段原委在它的
 * 注释里），底与圆点自己填。
 *
 * 颜色**从工具栏底带混出来**（[composerFooterFill]），不是从面板底 —— 这一小颗
 * 是坐在卡片下半格那条带子上的，从面板底混会在带子上显出一道色差。
 *
 * 该不该开口（恢复的/长起来的会话才值当）由调用方按 `McpCacheWarn` 那两个纯函数
 * 判好，再用 [setState] 把结论送进来 —— 这里只负责画。
 */
internal class McpCacheBadge(
    private val onOpenSettings: () -> Unit,
) : JComponent() {

    /**
     * 徽章正文。自绘控件套不进 `LocalizedText` 的 JLabel/AbstractButton 两个重载，
     * 所以用一颗**子标签**装字：键记在它身上，换语言走树那一遍照样能重挂
     * （同 `StatusCardView` 里那几颗）。
     */
    private val label = JBLabel().apply {
        isOpaque = false
        // 比工具栏的状态标签（模型/权限/思考）小一号：它是一条状态注记，
        // 不该跟那三个"现在是什么"抢同档的分量
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2f)
    }

    /** 最后一份读数；null = 还没有读数。 */
    private var state: CacheWarnState? = null

    /** 值当开口吗（调用方按 McpCacheWarn 的判据算好）—— 只管 pending。 */
    private var worth = false

    /** 窄窗让位：字退成点。由 [refreshCompact] 按行宽算，见 `cacheWarnNeedsCompact`。 */
    private var compact = false

    /**
     * 先前显示过 —— 绿的收尾（[CacheWarnState.Settled]）的前提。
     *
     * 没有它的话：任何一次"落定"都会让徽章冒出来报一句"已连接"，而用户从没被
     * 提示过、也不知道它在说什么。
     */
    private var active = false

    init {
        isOpaque = false
        add(label)
        border = RoundedLineBorder({ ringColor() }, JBUI.scale(PILL_ARC))
        isVisible = false

        // 窄窗让位：祖先把尺寸一变（窗口拉动、第一个布局落下来）就重量一遍。
        // 也在 doLayout 里兜一道 —— 只挂监听器的话，"加进来就已经是终态尺寸、
        // 之后没人动过祖先"那种布局轮不到它响
        addHierarchyBoundsListener(
            object : HierarchyBoundsAdapter() {
                override fun ancestorResized(e: HierarchyEvent) = refreshCompact()
            }
        )

        addMouseListener(
            object : MouseAdapter() {
                // 普通 JComponent 不像 AbstractButton 那样自己吞掉禁用后的点击，
                // 显式拦一道（同 RoundSendButton）：只有失败态才可点
                override fun mouseClicked(e: MouseEvent) {
                    if (isEnabled && isVisible && state is CacheWarnState.Failed) onOpenSettings()
                }
            }
        )
    }

    /**
     * 送一份结论进来。**由调用方给的是"状态"，不是"事件"**（同旧带子那条：
     * 事件式接口必然漏，答完/失败/会话停了几条路都要各喊一声）。
     */
    fun setState(next: CacheWarnState?, worthwhile: Boolean) {
        fadeTimer.stop()
        state = next
        worth = worthwhile
        render()
    }

    /** 撤下徽章（会话没了、新会话起手）。名字不叫 hide —— 那是 `JComponent` 的旧方法。 */
    fun dismiss() {
        fadeTimer.stop()
        state = null
        worth = false
        active = false
        applyVisible(false)
    }

    override fun removeNotify() {
        fadeTimer.stop()
        super.removeNotify()
    }

    /**
     * 绿的收尾自动隐去。单发、用到才跑。
     *
     * 常驻 Timer 会握住组件（开合工具窗口就漏一个）—— `removeNotify` 里 stop
     * （同旧带子的纪律）。
     */
    private val fadeTimer = Timer(FADE_MS) { hideBadge() }.apply { isRepeats = false }

    /** 测试与探针用：徽章上那行字现在写的是什么。 */
    internal fun badgeText(): String = label.text

    /** 测试与探针用：现在是退成点的窄窗形态吗。 */
    internal fun isCompact(): Boolean = compact

    // ---------------------------------------------------------------- 画

    private fun render() {
        // 描边色是按状态现取的（pending 琥珀 / 失败红 / 落定绿），状态换了不改
        // 尺寸、只改颜色 —— 这一笔重画不能省（同旧带子）
        repaint()

        val s = state
        // 三道门各管各的：pending 看 worth（要不要开口）、failed 常驻（配置坏了
        // 就得说）、settled 看 active（先前显示过才有"已连接"要报）。worth 那条
        // **不能**拿来当"已经开口了要不要收尾"的闸 —— 它是判定的反面，会把绿收尾
        // 整条吞掉
        if (s == null || (s is CacheWarnState.Pending && !worth) ||
            (s is CacheWarnState.Settled && !active)
        ) {
            applyVisible(false)
            return
        }

        when (s) {
            is CacheWarnState.Pending -> {
                // 名字不进正文：工具栏一行放不下（旧带子那版的名字被渲染探针抓到过
                // 尾巴被切）。谁是连接中的在悬停与设置页里
                applyStateText(
                    key = "mcp.cacheWarn.badge.pending",
                    tipWithNames = "mcp.cacheWarn.tip.pending",
                    tipWithoutNames = "mcp.cacheWarn.tip.pendingBare",
                    names = s.names,
                )
                active = true
                applyVisible(true)
            }

            is CacheWarnState.Failed -> {
                applyStateText(
                    key = "mcp.cacheWarn.badge.failed",
                    tipWithNames = "mcp.cacheWarn.tip.failed",
                    tipWithoutNames = "mcp.cacheWarn.tip.failedBare",
                    names = s.names,
                )
                active = true
                applyVisible(true)
            }

            CacheWarnState.Settled -> {
                // 收尾那句没有"带名字"的版本（名字对"已连接"没有意义）
                applyStateText(
                    key = "mcp.cacheWarn.badge.settled",
                    tipWithNames = "mcp.cacheWarn.tip.settled",
                    tipWithoutNames = "mcp.cacheWarn.tip.settled",
                    names = "",
                )
                active = false
                applyVisible(true)
                // 收尾：绿几秒，然后自己走
                fadeTimer.start()
            }
        }
    }

    /**
     * 正文与悬停一起换。**键每次都重挂**（`localizedText` / `localizedTooltip`）——
     * 状态一变文案就变，记在控件上的那条键得跟着走，否则换语言时重取的是旧状态的话。
     *
     * 悬停一般是"带名字"那版；名字为空（读数没报名字）退回不带名字的版本。三条键
     * 都在调用处以**字面量**出现（词表约定：键永不动态拼，`TextKeysTest` 两头对账）。
     */
    private fun applyStateText(key: String, tipWithNames: String, tipWithoutNames: String, names: String) {
        label.localizedText(key)
        if (names.isBlank()) localizedTooltip(tipWithoutNames) else localizedTooltip(tipWithNames, names)
    }

    private fun applyVisible(show: Boolean) {
        if (isVisible != show) {
            isVisible = show
            // 显隐之后必须让父容器重算：BoxLayout 会跳过不可见的子项（本仓既有经验，
            // 见旧带子/AskRestoreBar 的同一条）—— 隐藏时那格与间隙一起消失
            parent?.revalidate()
            parent?.repaint()
        }
        refreshCompact()
        updateClickable()
    }

    /**
     * 窄窗让位：量一遍工具栏那一行放不放得下带字的自己。
     *
     * 行 = 徽章所在右组（[parent]）的父级，是 `buildComposerToolbar` 装配的那只
     * BorderLayout 面板（WEST=左排、EAST=右组）。链子对不上就什么都不做 —— 渲染
     * 探针会拿徽章单独出图，那时它没有行可量，保持完整形态即可。
     *
     * 判据由 `cacheWarnNeedsCompact` 给（那条纯函数解释了为什么这个量法不会抖）。
     * 翻转只在真的变了的时候发生，`doLayout` 里那道兜底因此最多多跑一轮布局。
     */
    private fun refreshCompact() {
        val group = parent as? JComponent ?: return
        val row = group.parent as? JComponent ?: return
        val layout = row.layout as? BorderLayout ?: return
        val west = layout.getLayoutComponent(BorderLayout.WEST) as? JComponent ?: return
        val east = layout.getLayoutComponent(BorderLayout.EAST) as? JComponent ?: return
        // 还没布局过：量到的都是 0，等真尺寸落下来再说（doLayout / 祖先 resize 会再来）
        if (row.width <= 0) return

        // 右组的首选宽里此刻含着自己那一份（不可见时 BoxLayout 不计它，就不减）
        val badgeNow = if (isVisible) preferredSize.width else 0
        val next = cacheWarnNeedsCompact(
            rowWidth = row.width,
            westWidth = west.preferredSize.width,
            eastWidthWithoutBadge = east.preferredSize.width - badgeNow,
            badgeFullWidth = preferredWidth(compact = false),
        )
        if (next == compact) return
        compact = next
        label.isVisible = !compact
        revalidate()
        repaint()
    }

    /** 只有失败态可点（开设置）—— 其余形态点下去什么都不发生，而"点一下什么都不发生"比不可点更糟。 */
    private fun updateClickable() {
        val clickable = isVisible && state is CacheWarnState.Failed
        if (clickable == (clickListener != null)) return

        cursor = if (clickable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
        // 子标签会吞鼠标事件，两颗都要挂（旧带子的 box/hint 同一条）
        val targets = listOf<JComponent>(this, label)
        targets.forEach { it.cursor = cursor }
        if (clickable) {
            targets.forEach { it.addMouseListener(clickListener) }
        } else {
            targets.forEach { it.removeMouseListener(clickListener) }
            clickListener = null
        }
    }

    private var clickListener: MouseAdapter? = null

    /** 悬停时才轮到它：状态色偏亮/偏暗都在这两档里混，底色是工具栏的底带。 */
    private fun toneOf(s: CacheWarnState?): Color = when (s) {
        is CacheWarnState.Pending -> toneColor(Tone.Warn)
        is CacheWarnState.Failed -> toneColor(Tone.Danger)
        CacheWarnState.Settled -> toneColor(Tone.Ok)
        null -> UIUtil.getInactiveTextColor()
    }

    private fun fillColor(): Color = mix(composerFooterFill(), toneOf(state), FILL_TINT)

    private fun ringColor(): Color = mix(composerFooterFill(), toneOf(state), RING_TINT)

    // ---------------------------------------------------------------- 尺寸与布局

    override fun getPreferredSize(): Dimension = Dimension(preferredWidth(compact), preferredHeight())

    /** 这一格是**定宽**的（像时钟键/发送键）：BoxLayout 不许拉伸它，间距才稳。 */
    override fun getMaximumSize(): Dimension = preferredSize

    override fun getMinimumSize(): Dimension = preferredSize

    private fun preferredHeight(): Int = maxOf(JBUI.scale(PILL_H), label.preferredSize.height + JBUI.scale(4))

    private fun preferredWidth(compact: Boolean): Int =
        if (compact) {
            JBUI.scale(COMPACT_W)
        } else {
            JBUI.scale(PAD_L) + JBUI.scale(DOT) + JBUI.scale(DOT_GAP) + label.preferredSize.width + JBUI.scale(PAD_R)
        }

    /**
     * 手摆子标签的位置（BoxLayout 不往 JComponent 里塞东西）。
     *
     * 顺手重量一遍让位（见 [refreshCompact]）：第一次真布局就落在这条路上，
     * 只挂祖先监听器的话那一次量不到。判据翻转才 revalidate，多跑一轮就收敛。
     */
    override fun doLayout() {
        refreshCompact()
        if (compact) return
        val h = label.preferredSize.height
        label.setBounds(
            JBUI.scale(PAD_L) + JBUI.scale(DOT) + JBUI.scale(DOT_GAP),
            (height - h) / 2,
            label.preferredSize.width,
            h,
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 胶囊底。圆角取整高 = 端头是半圆（stadium），与描边同一个圆心
            g2.color = fillColor()
            g2.fillRoundRect(0, 0, width, height, height, height)

            // 那颗点：字在时靠左带头，退成点时居中 —— 这颗点是形态本身的记号，
            // 颜色是它唯一能说话的地方（琥珀=待连 / 红=失败 / 绿=落定）
            val d = JBUI.scale(DOT).toDouble()
            val x = if (compact) {
                (width - d) / 2.0
            } else {
                JBUI.scale(PAD_L).toDouble()
            }
            g2.color = toneOf(state)
            g2.fill(Ellipse2D.Double(x, (height - d) / 2.0, d, d))
        } finally {
            g2.dispose()
        }
    }

    private fun hideBadge() {
        active = false
        applyVisible(false)
    }

    private companion object {
        /** 绿的收尾停留多久。六秒：够看到，又不至于一直占着工具栏那一格（旧带子同数）。 */
        const val FADE_MS = 6_000

        /** 胶囊高度与圆角（未缩放）。18/9 = 端头半圆。 */
        const val PILL_H = 18
        const val PILL_ARC = 9

        /** 内衬与点（未缩放）：左 7 + 点 6 + 缝 5 + 字 + 右 9。 */
        const val PAD_L = 7
        const val PAD_R = 9
        const val DOT = 6
        const val DOT_GAP = 5
        const val COMPACT_W = 20

        /** 底色混比：13% 看得出来是一层底，38% 的边在小尺寸上还认得出轮廓。 */
        const val FILL_TINT = 0.13
        const val RING_TINT = 0.38
    }
}
