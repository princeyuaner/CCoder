package com.ccoder.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 「MCP 还没连完」那条带子 —— 输入框上方，排队条之下。
 *
 * 形态与显隐照 [AskRestoreBar]（同一格子里住着的老邻居）：不铺底、圆角描边、
 * `isVisible` 起步 false（BoxLayout 会跳过不可见子项 ⇒ 不该出现时一分高度不占）。
 *
 * 分两层（[box] + 它的下面一口 [BOTTOM_GAP]）：2026-10-13 用户报"连接提示和输入框
 * 粘在一起了" —— 两块都带圆角描边，贴着看像一块卡被切成两半。那口气只能留在
 * 描边**外面**（描边按组件的完整边界画），所以带子比它画的那个框高 5px。
 *
 * 五种形态：
 *
 *  - **Pending**：琥珀。现在发送的那一发大概率全价重算（实测见 `McpCacheWarn.kt`）。
 *  - **Settled**：绿，**几秒后自动隐去**。它是"刚才那件事办完了"的收尾 ——
 *    只在先前显示过（[active]）时出现，凭空冒出一条绿的在屏幕上没人看得懂。
 *  - **Failed**：红，常驻且整条可点（开设置）。失败不是落定，藏着才是骗人。
 *  - **Confirm**：就地确认（D）—— 「照发 / 等它 / 本会话不再问」，不是对话框
 *    （同 `SessionList` 那条"确认不是装饰、但也不弹框"的规矩）。
 *  - **Hidden**。
 *
 * 该不该开口（恢复的/长起来的会话才值当）由调用方按 `McpCacheWarn` 那两个纯函数
 * 判好，再用 [setState] 把结论送进来 —— 这里只负责画。
 */
internal class McpCacheWarnBar(
    private val onOpenSettings: () -> Unit,
    private val onSendAnyway: (muteForSession: Boolean) -> Unit,
    private val onWait: () -> Unit,
) : JPanel() {

    private val hint: JBLabel = JBLabel()

    private val sendAnyway = JButton().localizedText("mcp.cacheWarn.send").apply {
        addActionListener { onSendAnyway(mute.isSelected) }
    }

    private val wait = JButton().localizedText("mcp.cacheWarn.wait").apply {
        addActionListener { onWait() }
    }

    private val mute = JCheckBox().localizedText("mcp.cacheWarn.mute")

    /**
     * 确认态那行控件（等它 · 照发 · 本会话不再问）。
     *
     * **单独一行、显式两层**（不是让 FlowLayout 去换行）：换行的高度要 FlowLayout
     * 与 BoxLayout 互相问出来，问的顺序不对高度就少一截 —— 渲染探针 2026-10-13
     * 出的那张图里，勾选框跑到第二行、整条被裁掉（`preferredSize.height=40`，
     * 而里面实际要 60）。分层之后高度是"标签 + 这行"的确定和，没有换行可猜。
     * 那一行的首选宽（中英两套都量过）仍小于面板宽，不会横向溢出。
     */
    private val controls = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        add(wait)
        add(sendAnyway)
        add(mute)
        isVisible = false
    }

    /**
     * 有边的那一层 —— 描边、状态色、可点区域都长在它身上。
     *
     * 与输入卡之间那口气留在它**外面**（[BOTTOM_GAP]）：描边画在组件的完整边界上，
     * 气口要是加在带子的内边距里，会被一并圈进框里 —— 看起来只是框变高了，
     * 而不是两块之间有了缝。
     */
    private val box: JPanel = object : JPanel() {
        // 撑满整行，理由同外层那条（BoxLayout 会把子项的 max 宽度相加，不能用 Int 上限）
        override fun getMaximumSize(): Dimension =
            Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)
    }.apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder({ borderColor() }, JBUI.scale(7)),
            JBUI.Borders.empty(4, 8),
        )
        add(hint.apply { alignmentX = LEFT_ALIGNMENT })
        add(controls.apply { alignmentX = LEFT_ALIGNMENT })
    }

    /** 最后一份读数（不含确认态）；null = 还没有读数。 */
    private var state: CacheWarnState? = null

    /** 值当开口吗（调用方按 McpCacheWarn 的判据算好）。 */
    private var worth = false

    /** 正在就地确认。 */
    private var confirming = false

    /**
     * 先前显示过 —— 绿的收尾（[CacheWarnState.Settled]）的前提。
     *
     * 没有它的话：任何一次"落定"都会让带子冒出来报一句"已连接"，而用户从没被
     * 提示过、也不知道它在说什么。
     */
    private var active = false

    /**
     * 绿的收尾自动隐去。单发、用到才跑。
     *
     * 常驻 Timer 会握住组件（开合工具窗口就漏一个）—— 这里照 `StatusCardView`
     * 的纪律：`removeNotify` 里 stop。
     */
    private val fadeTimer = Timer(FADE_MS) {
        if (!confirming) hideBar()
    }.apply { isRepeats = false }

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(box.apply { alignmentX = LEFT_ALIGNMENT })
        // 与输入卡之间那口气。**挂在带子自己身上**而不是 ClaudePanel 的装配里：
        // 带子不显示时整条被跳过（BoxLayout 不看不可见子项），气口跟着一起消失 ——
        // "隐藏时一分高度不占"这条不用在装配处再维护一遍（挂在那儿就得跟着显隐进退）
        add(
            (Box.createVerticalStrut(JBUI.scale(BOTTOM_GAP)) as JComponent)
                .apply { alignmentX = LEFT_ALIGNMENT },
        )
        isVisible = false
    }

    /**
     * 送一份结论进来。**由调用方给的是"状态"，不是"事件"**（同 `AskRestoreBar`
     * 那条：事件式接口必然漏，答完/失败/会话停了几条路都要各喊一声）。
     */
    fun setState(next: CacheWarnState?, worthwhile: Boolean) {
        fadeTimer.stop()
        state = next
        worth = worthwhile
        render()
    }

    /**
     * 把发送拦下来问一句（D）。只有 pending 且值当时才该被调用 ——
     * 闸的判据是纯函数 `shouldConfirmCacheWarn`，调用方先判后调。
     */
    fun showConfirm() {
        if (state !is CacheWarnState.Pending) return
        confirming = true
        mute.isSelected = false
        render()
        // 焦点落到**安全的那颗**（同 SessionCard.enterConfirm 的规矩）：用户是
        // 按回车走到这里的，那时焦点还在输入框上 —— 再按一下回车不该把钱花出去
        SwingUtilities.invokeLater { wait.requestFocusInWindow() }
    }

    fun dismissConfirm() {
        if (!confirming) return
        confirming = false
        render()
    }

    /** 撤下带子（会话没了、新会话起手）。名字不叫 hide —— 那是 `JComponent` 的旧方法。 */
    fun dismiss() {
        fadeTimer.stop()
        state = null
        worth = false
        confirming = false
        active = false
        applyVisible(false)
    }

    override fun removeNotify() {
        fadeTimer.stop()
        super.removeNotify()
    }

    /**
     * 撑满整行（同 `QueueStrip` 那条）：BoxLayout 不覆写就按**首选宽度**摆子项，
     * 于是比面板宽的那些字会被整条切掉 —— 渲染探针 2026-10-13 抓到的就是这个
     * （pending 那句尾巴没了）。铺满之后 FlowLayout 才有"放不下就换行"的余地。
     *
     * 宽度必须是 [Short.MAX_VALUE] 而**不是** `Int.MAX_VALUE`：BoxLayout 会把
     * 子项的 max 宽度**相加**，用 Int 上限当场溢出成负数（QueueStrip 的实测）。
     */
    override fun getMaximumSize(): Dimension =
        Dimension(Short.MAX_VALUE.toInt(), preferredSize.height)

    /** 测试与探针用：那条文字现在写的是什么。 */
    internal fun hintText(): String = hint.text

    /** 测试与探针用：确认态的那几颗在不在。 */
    internal fun isConfirming(): Boolean = confirming

    // ---------------------------------------------------------------- 画

    private fun render() {
        // 描边色是按状态现取的（pending 琥珀 / 失败红 / 落定绿），状态换了
        // 不改尺寸、只改颜色 —— 这一笔重画不能省。重画的是 [box]：描边长在它身上
        box.repaint()

        val s = state
        // worth 管的是"**要不要开口**"，不是"已经开口了的话要不要收尾"——
        // 落定（Settled）恰恰是价值判定的**反面**（`cacheWarnWorthwhile` 只认
        // pending），拿它当闸会把绿色收尾整条吞掉。收尾的门槛是 [active]：
        // 先前显示过才有"已连接"要报。
        if (s == null || (!worth && s !is CacheWarnState.Settled)) {
            confirming = false
            applyVisible(false)
            return
        }
        // 落定/失败之后"现在发送会亏"这句话就不成立了 —— 确认态自动收回
        if (s !is CacheWarnState.Pending) confirming = false

        if (confirming) {
            hint.localizedText("mcp.cacheWarn.confirm")
            setControlsVisible(true)
            applyVisible(true)
            return
        }

        setControlsVisible(false)
        when (s) {
            is CacheWarnState.Pending -> {
                // 名字不画进正文：420px 下一定被切掉尾巴（渲染探针抓过那一版）。
                // 谁是连接中的在设置页里，失败态那条整条可点、点进去就是它
                hint.localizedText("mcp.cacheWarn.pending")
                active = true
                applyVisible(true)
            }

            is CacheWarnState.Failed -> {
                hint.localizedText("mcp.cacheWarn.failed")
                active = true
                applyVisible(true)
            }

            CacheWarnState.Settled -> {
                if (!active) {
                    applyVisible(false)
                    return
                }
                // 收尾：绿几秒，然后自己走
                hint.localizedText("mcp.cacheWarn.settled")
                active = false
                applyVisible(true)
                fadeTimer.start()
            }
        }
    }

    /** 那一行控件的显隐。变了要重算布局：整条的高度 = 标签 + 这一行。 */
    private fun setControlsVisible(show: Boolean) {
        if (controls.isVisible == show) return
        controls.isVisible = show
        // 重算的是 [box]（控件住在它里面）—— revalidate 会把带子和外面的面板
        // 一路带上去，带子的高度跟着 box 的首选高走
        box.revalidate()
    }

    private fun applyVisible(show: Boolean) {
        if (isVisible != show) {
            isVisible = show
            // 显隐之后必须让父容器重算：BoxLayout 会跳过不可见的子项（本仓既有经验，
            // 见 AskRestoreBar.setSuspended 那条）
            parent?.revalidate()
            parent?.repaint()
        }
        updateClickable()
    }

    /**
     * 只有失败态整条可点（开设置）—— 其余形态点下去什么都不发生，
     * 而"点一下什么都不发生"比不可点更糟。
     */
    private fun updateClickable() {
        val clickable = isVisible && state is CacheWarnState.Failed && !confirming
        if (clickable == (clickListener != null)) return

        cursor = if (clickable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
        if (clickable) {
            clickListener = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onOpenSettings()
            }
            // box 也要挂：描边以内那一片（内边距、标签两边）现在都是它，
            // 只挂带子和标签的话，点进去那一圈会没反应
            listOf<Component>(this, box, hint).forEach { it.addMouseListener(clickListener) }
        } else {
            listOf<Component>(this, box, hint).forEach { it.removeMouseListener(clickListener) }
            clickListener = null
        }
    }

    private var clickListener: MouseAdapter? = null

    private fun borderColor() = when {
        confirming || state is CacheWarnState.Pending -> toneColor(Tone.Warn)
        state is CacheWarnState.Failed -> toneColor(Tone.Danger)
        else -> toneColor(Tone.Ok)
    }

    private fun hideBar() {
        active = false
        applyVisible(false)
    }

    private companion object {
        /** 绿的收尾停留多久。六秒：够看到，又不至于一直占着输入框上方那格。 */
        const val FADE_MS = 6_000

        /**
         * 与输入卡之间那口气（未缩放 px）。
         *
         * 5 取的是"提示带挨着别的东西"用的那一档（`AskRestoreBar` 与状态卡之间
         * 就是它）；不复用状态卡与输入卡之间那个 7 —— 那档说的是"两片区域分家"，
         * 这里只是"两块圆角描边别贴在一起"。
         */
        const val BOTTOM_GAP = 5
    }
}
