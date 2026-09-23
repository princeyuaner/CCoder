package com.ccoder.ui

import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiCompatibleDataProvider

import com.ccoder.settings.SendShortcut
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.text.JTextComponent
import com.ccoder.text.CcoderText

// ---- 输入框高度 ----

/**
 * 输入框的最小行数 —— 它同时也是"拖到最小能有多矮"的地板。
 *
 * **不做自动长高**（2026-09-11 用户明确要求）：高度由用户拖分隔条决定，
 * 输入框只负责在拖出来的空间里撑满。这样"我写多长"和"它占多高"两件事
 * 解耦，不会因为粘一段长文本就把面板顶开。
 *
 * 取 1 而不是 3：它经 preferredSize 变成布局的最小高度，而这个最小高度会
 * 反过来顶住分隔条的默认比例 —— 设为 3 时最小值约 97px，把默认比例往下调
 * 也压不下去，表现为"改了默认高度却没变"。
 */
internal const val COMPOSER_MIN_ROWS = 1

// ---- 空输入框的用法提示 ----

/**
 * 空输入框里那行灰字（2026-09-15 用户要求："聊天输入框应该写上这些符号的用法"）。
 *
 * 前半段是三个触发符号，末尾接一句**怎么发送 / 怎么换行**
 * （2026-09-22 用户："缺发送和换行的说明"）—— 那句跟着设置里的发送约定走，
 * 写死一句"Enter 发送"在 Ctrl+Enter 模式下就是撒谎（真值在 [isSendKey]）。
 *
 * **文案与能力同步**：它写着 `# 符号`，就得先有 `#`；哪一件被砍掉，这行字要一起改
 * —— 否则界面就在替一个不存在的功能做广告。
 */
internal fun composerPlaceholder(shortcut: SendShortcut): String =
    CcoderText.text("composer.placeholder") + " · " + CcoderText.text(sendHintKey(shortcut))

/**
 * 发送说明的键：两种约定各一句。
 *
 * 措辞与 `settings.sendShortcut.*.label` 同义却更短，**刻意不复用那句**：
 * label 是给设置页一整栏写的（英文整句 `Enter sends, Shift+Enter adds a newline`），
 * 接在这行后面英文实测 ~470px —— 面板默认宽度（500）下正好顶出去。两处说的
 * 是同一件事，改一处要连着另一处一起看一眼，别让两边说岔。
 *
 * `when` 而不是 map：枚举将来多一档时，这里是编译错误，而不是运行期一个空串。
 */
private fun sendHintKey(shortcut: SendShortcut): String = when (shortcut) {
    SendShortcut.ENTER -> "composer.placeholder.send.enter"
    SendShortcut.CTRL_ENTER -> "composer.placeholder.send.ctrlEnter"
}

/**
 * 该不该显示那行提示；不该显示时给 null。
 *
 * 抽成纯函数是为了可测：输入框与面板都依赖平台，起不了单测，而"什么时候显示"
 * 是这条路上唯一的判断（画在哪、什么颜色都是绘制层的事）。
 *
 * **禁用态不显示**：今天面板并不真的禁用输入框（发不出去是拦在发送那一步的），
 * 所以这条规则眼下只是守门 —— 但哪天有人禁用了它，挂一行"你可以打 #"就是撒谎。
 */
internal fun placeholderTextOf(area: JTextComponent, shortcut: SendShortcut): String? =
    if (area.isEnabled && area.text.isEmpty()) composerPlaceholder(shortcut) else null

// ---- 输入框本体 ----

/**
 * 竖向撑满视口的输入框。
 *
 * `JTextArea` 默认不随视口长高（`getScrollableTracksViewportHeight()`
 * 返回 false），把它放进滚动面板后，拖高输入区只会多出一片空白 ——
 * 输入框本身纹丝不动，看起来像"拖了没用"。
 */
internal class ComposerTextArea(rows: Int, cols: Int) : JBTextArea(rows, cols), UiCompatibleDataProvider, Relocalizable {

    /**
     * 当前的发送约定 —— 占位末尾那句（[composerPlaceholder]）跟着它变。
     *
     * **每次绘制现取**，而不是存一个值：设置页改完没有任何通知会发到面板
     * （[isSendKey] 那条路也是每次按键现读设置），存下来的话，用户改完设置
     * 得等下一次无关的重绘才看得见新文案 —— 那正是"改了没生效"的样子。
     *
     * 由 [ClaudePanel] 接上（要读项目级设置）；纯 JVM 测试里没人接，按默认那份。
     */
    var sendShortcut: () -> SendShortcut = { SendShortcut.DEFAULT }

    /**
     * Ctrl+V 的接管口（见 [imagePasteProvider]）。
     *
     * **为什么需要它**：IDE 里 Ctrl+V 走的是平台的 `$Paste` action，而那个 action
     * 是从**数据上下文**里取 [PasteProvider] 再调的 —— Swing 的 `TransferHandler`
     * 那条路在 IDE 里根本不会被执行（2026-09-15 实测：处理器装上了，一次都没被调到）。
     *
     * **必须是 [UiCompatibleDataProvider]，不是普通的 `DataProvider`**：后者平台
     * 会从后台线程随便问（所以要求线程安全），于是 2026-09-15 那一版实现完
     * **一次都没被问到** —— 平台自己的 `EditorTextField` 实现的就是前者。
     *
     * 只在"剪贴板里只有图"时回答（见 [imagePasteData]），其余交回平台。
     */
    var pasteProvider: PasteProvider? = null

    /**
     * "从剪贴板收一张图"的回调 —— 给 [PasteImageAction] 用（Ctrl+V 那条自己占住的
     * 路）。由 [installImagePaste] 接上。
     */
    var imagePasteAttach: (() -> Unit)? = null

    override fun uiDataSnapshot(sink: DataSink) {
        // 无条件放：这个 key 只是"焦点在能收图的输入框上"的标记，可不可用由
        // PasteImageAction.update 决定（它还要看剪贴板）
        imagePasteAttach?.let { sink[IMAGE_PASTE_ATTACH] = it }
        val provider = imagePasteData(PlatformDataKeys.PASTE_PROVIDER.name, pasteProvider)
        if (provider != null) {
            LOG.info("贴图：把 PASTE_PROVIDER 交给平台（剪贴板里只有图）")
            sink[PlatformDataKeys.PASTE_PROVIDER] = provider
        }
    }

    override fun getData(dataId: String): Any? = imagePasteData(dataId, pasteProvider)

    /**
     * 视口够高就撑满；**装不下就别撑** —— 那时要出滚动条。
     *
     * ## 为什么不是恒 `true`（2026-09-23 用户报"文本多了不出滚动条，后面的看不见"）
     *
     * 恒 `true` 的意思是"我的高度永远等于视口高度"——滚动条据此认定**永远装得下**，
     * 于是它永远不出现，超出去的那些行既看不见也够不着。
     * 而输入框**不做自动长高**（高度由用户拖分隔条决定，见 [COMPOSER_MIN_ROWS]），
     * 所以"超出"是常态，滚动是唯一的出路。
     *
     * 恒 `false` 是另一个极端：拖高之后输入框纹丝不动、旁边多出一片空白，
     * 看起来像"拖了没用"——这正是原来那版注释记的教训。
     * **两头都要**：视口高过内容时撑满（拖高算数），内容高过视口时退出（滚动条出场）。
     *
     * 判据用 `preferredSize` 而不是构造时那几行的行高：对 `JTextArea` 来说，
     * 它跟着**当前文本的行数**长（`PlainView.getPreferredSpan`），
     * 那正是"装不装得下"的问法。
     *
     * 没有父级（纯 JVM 用例里）按"撑满"算：那时没有视口可言，怎么答都不影响布局。
     */
    override fun getScrollableTracksViewportHeight(): Boolean {
        val viewport = parent as? JViewport ?: return true
        return viewport.height >= preferredSize.height
    }

    /**
     * LAF 一变输入表会被整张重装，所以绑定跟着 `updateUI` 装 —— 不放在 `init`：
     * 表是 UI 装出来的，构造器那个时刻它可能还是空的。
     */
    override fun updateUI() {
        super.updateUI()
        installShiftEnterBreak()
    }

    /**
     * 让 **Shift+Enter 也能换行**。
     *
     * ## 为什么得自己挂（2026-09-23 用户报"Shift+Enter 不换行"）
     *
     * 输入框的键表里**只有裸 Enter 绑了 `insert-break`，Shift+Enter 是 `null`** ——
     * 这是把表倒出来看过的（`shift pressed ENTER -> null`，见 [ComposerRulesTest]）。
     * 普通 Swing 里 Shift+Enter 能换行，靠的是按键之后那个 KEY_TYPED 的 `'\n'`
     * 走 [javax.swing.text.DefaultEditorKit.DefaultKeyTypedAction] 兜底；
     * 那条路在 IDE 里会被掐掉（按下事件被消费过就不发字符事件那类一次性判断），
     * 于是 Shift+Enter 两头落空：**没绑定，兜底的字符又不来**。
     *
     * 修法不绕：把裸 Enter 的那个绑定**原样复制**一份到 Shift+Enter 上。
     * 不写死 `"insert-break"` 是刻意的 —— 平台或 LAF 换了绑的是什么就跟什么，
     * 我们只要求"和裸 Enter 一样"，那样它走的就是裸 Enter 那条**已被验证过**的路
     * （包括"绑定消费掉按下事件、于是不会有第二个字符事件"这层，新行也只会有一个）。
     */
    private fun installShiftEnterBreak() {
        val map = getInputMap(JComponent.WHEN_FOCUSED) ?: return
        val plain = map.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)) ?: return
        map.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            plain,
        )
    }

    /**
     * 空着的时候，在**光标的落点**画一行灰字说明三个触发符号怎么用。
     *
     * 画在文字将要出现的位置（内边距之后），所以它不需要单独的控件 ——
     * 也不会有"控件与文字错位"这种问题。有字时 [placeholderTextOf] 直接给 null。
     */
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val placeholder = placeholderTextOf(this, sendShortcut()) ?: return

        val g2 = g.create()
        try {
            g2.font = font
            g2.color = UIUtil.getInactiveTextColor()
            g2.drawString(placeholder, insets.left, insets.top + g2.fontMetrics.ascent)
        } finally {
            g2.dispose()
        }
    }

    /**
     * 语言一变重画一遍 —— 这行字是**画**出来的（不是控件的 text），走树那一遍
     * 摸不到它，只能自己重取。
     *
     * 自绘控件实现 [Relocalizable] 是 `LocalizedText.kt` 定下的规矩（见那里的说明）。
     */
    override fun retranslate() {
        repaint()
    }
}

private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance("com.ccoder.ui.ComposerTextArea")

// ---- 发送快捷键 ----

/**
 * 这个按键该不该触发发送。
 *
 * 只判这几个键：**Shift+Enter 在两种约定下都留给换行** —— 用户在
 * Ctrl+Enter 模式下想换行时，多半会先试 Shift+Enter。
 */
internal fun isSendKey(
    keyCode: Int,
    shiftDown: Boolean,
    ctrlDown: Boolean,
    shortcut: SendShortcut,
): Boolean {
    if (keyCode != KeyEvent.VK_ENTER) return false
    return when (shortcut) {
        SendShortcut.ENTER -> !shiftDown
        SendShortcut.CTRL_ENTER -> ctrlDown
    }
}

// ---- 输入区布局 ----

/**
 * 这一片（输入卡 + 上面那排状态卡）所有圆角的**直径**。
 *
 * `drawRoundRect` / `fillRoundRect` 收的都是直径而不是半径 —— 所以半径是它的一半，
 * 也就是 8px。**一处定义、两处使用**（[ComposerCard] 与 [StatusCardView]）：
 * 这两张卡上下叠着只隔 7px，两个半径一旦分叉，看着就像是画错了。
 *
 * ## 改过两次，第二次是同一天
 *
 * - 2026-09-14 改版：从"1px 直角矩形"换成圆角卡片，当时是 10（半径 5）。
 * - 2026-09-16：用户报**状态卡**「这个也稍微圆一点点，现在太方正了」。看了
 *   `composer-probe.png`（那张图把状态卡与输入卡画在一起）之后把**两处一起**
 *   提到 16（半径 8）—— 只动状态卡的话，紧挨着的输入卡反而更方，前后不一致。
 *   半径 8 也正好是设计稿里那一档（`session-tabs.html` 的 `.inbar` 是 8px）。
 *
 * 观感看 `ComposerRenderProbe` / `StatusCardsRenderProbe` 出的图。
 */
internal const val CARD_CORNER_ARC = 16

/**
 * 卡片**底**比页底亮/暗多少。
 *
 * 取一个**两边主题都成立**的数：[mix] 算的是"底色 → 文本色"，深色主题下文本色是亮的
 * （卡比页底亮一档）、浅色下是暗的（卡比页底暗一档），方向不用判明暗
 * （同 [FOOTER_TINT] 那条道理）。
 *
 * 0.05 是"看得出这是一张卡"，同时**不靠色差堆**：卡片主要靠**边 + 圆角**读出来，
 * 底色只差这一点点。改用例见 `SettingsCardTest` 里那条数像素的。
 *
 * 2026-09-22 从 `settings/SettingsCard.kt` 搬到这儿：会话列表的卡片
 * （`SessionList`）也要用它，而 `ui` 是画法这一层的公共落点（[CARD_CORNER_ARC]
 * 早就住在这儿了）—— 两份配方各写一份，迟早分叉。
 */
internal const val CARD_TINT = 0.05

/** 卡片底。 */
internal fun cardFill(): Color =
    mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), CARD_TINT)

/**
 * 输入区是一个**圆角卡片**（方案 A）。
 *
 * 边框画在卡片上，输入框自己是裸的 —— 这是这次重设计的核心取舍。
 * 原来的做法是一个 1px 直角矩形直接框住文字，看起来像表单字段而不像
 * 对话输入；而"分不清输入区与转写区"那个问题，改由卡片的整条上沿来回答，
 * 边界反而更明确（它包住的是整个输入区，不只是文字那一块）。
 *
 * 聚焦时描边变强调色。**只改颜色、不加粗**：加粗会让内容位移一个像素，
 * 在用户正在打字的时候抖一下。
 */
internal class ComposerCard : JPanel(BorderLayout()) {

    private var focused = false

    private val focusWatcher = java.beans.PropertyChangeListener { ev ->
        val owner = ev.newValue as? java.awt.Component
        setFocused(owner != null && javax.swing.SwingUtilities.isDescendingFrom(owner, this))
    }

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder(
                { if (focused) focusColor() else lineColor() },
                JBUI.scale(CARD_CORNER_ARC),
            ),
            JBUI.Borders.empty(4, 6, 5, 6),
        )
    }

    fun setFocused(value: Boolean) {
        if (focused == value) return
        focused = value
        repaint()
    }

    /**
     * 工具栏那一行的底带（2026-09-17 用户："最下面这里我想加个背景，让他们感觉是一体的"）。
     *
     * **由卡片画而不是工具栏自己画**：底带要铺到卡片下沿、还要跟描边同一个圆心，
     * 而工具栏的边界在卡片内边距**以内**（左右各 6、下面 5）——它连自己外面一个
     * 像素都画不到。卡片知道自己的尺寸与圆角，也只有它能把这条带子画成
     * "卡片的下半格面"。
     *
     * 底带的上沿 = SOUTH 那个组件（工具栏）的上沿，**现场问布局要**：工具栏多高
     * 由它自己的字体和内边距决定，写死一个数迟早对不上。
     * 还没布局过（bounds 全是 0）时什么都不画 —— [paintComposerFooter] 里挡着。
     */
    override fun paintComponent(g: Graphics) {
        val band = (layout as? BorderLayout)?.getLayoutComponent(BorderLayout.SOUTH) ?: return
        if (!band.isVisible) return
        paintComposerFooter(g, width, height, band.bounds.y, composerFooterFill())
    }

    /**
     * 焦点落在卡片里的任何一个子组件（输入框、发送按钮）上，都算卡片聚焦。
     *
     * 监听器挂在全局的 KeyboardFocusManager 上，所以必须在离开层级时摘掉 ——
     * 否则每开一次工具窗口就漏一个监听器，而它捕获着这个面板。
     */
    override fun addNotify() {
        super.addNotify()
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("focusOwner", focusWatcher)
    }

    override fun removeNotify() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removePropertyChangeListener("focusOwner", focusWatcher)
        super.removeNotify()
    }
}

/**
 * 卡片内部自上而下三段：
 *
 *   NORTH  附件带（待发的图；没有图时它自己收起来，一分高度都不占）
 *   CENTER 输入框（撑满可用高度）
 *   SOUTH  控件工具栏（发送/停止在右，左侧留给模型切换等）
 *
 * NORTH 原先挂着"连接状态 + 上下文 + 任务条"那一行。那四样拆成独立卡片
 * 挪到输入卡**外面**之后这一层空了出来，2026-09-15 贴图把它用掉：
 * 附件带必须在**卡片内部**——它是这条消息的一部分，不是状态卡那种常驻控件
 * （设计稿 docs/design/image-attach.html 方案甲）。
 *
 * 工具栏放 SOUTH 而不是把按钮摆在输入框右边（原来那样）—— 右边放不下
 * 以后的模型切换、权限模式；摆下面则加控件只是往工具栏左侧添，不必再动结构。
 */
internal fun buildComposerCard(
    inputScroll: JComponent,
    toolbar: JComponent,
    attachments: JComponent? = null,
): ComposerCard = ComposerCard().apply {
    if (attachments != null) add(attachments, BorderLayout.NORTH)
    add(inputScroll, BorderLayout.CENTER)
    add(toolbar, BorderLayout.SOUTH)
}

/** 卡片的常态描边。与 IDE 给输入类控件用的一条线同源。 */
internal fun lineColor(): Color = JBColor.namedColor("Component.borderColor", JBColor.border())

// ---- 工具栏底带（卡片下半格那层底）----

/**
 * 底带比面板底色亮多少 / 暗多少。
 *
 * 取一个**两边主题都成立**的数：[mix] 算的是"底色 → 文本色"，深色主题下文本色
 * 是亮的（带子变亮一档）、浅色主题下是暗的（带子变暗一档），方向不用判明暗。
 *
 * 0.09 是"看得出这是一层"，同时也是"不抢"：0.03 几乎看不见（白加），
 * 0.15 往上就开始像另一块面板了。档位见 `ComposerFooterRenderProbe` 出的对比图。
 */
internal const val FOOTER_TINT = 0.09

/** 底带的颜色。 */
internal fun composerFooterFill(): Color =
    mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), FOOTER_TINT)

/**
 * 把底带画进 [g]（卡片坐标系）。
 *
 * **只圆下面两个角**，而且与卡片描边**同一个模子**：同样的矩形、同样的半径，
 * 于是两条弧之间那圈间隙处处等宽。半径或矩形任一不同，四个角上就会露出来
 * 一边宽一边窄的月牙 —— 这是"底比边圆"那类毛刺的同一种毛病
 * （[StatusCardView] 的注释里记过）。
 *
 * 上沿是**直的**：那是输入区与工具栏的分界，有弧度反而像两个控件。
 * 做法是**求交**：整块的圆角矩形 ∩ "从 [bandTop] 往下的直角矩形"，
 * 留下的正好是"下圆上方"。
 *
 * > 别用"把圆角矩形往上挪一个半径、再用直角矩形盖掉上边"那种画法：
 * > 盖的位置极易写反（2026-09-17 第一次就写反了 —— 盖的是 [bandTop] 下面
 * > 那一段，于是带子从 `bandTop - 半径` 就开始铺，整整多出 16px 压在输入区上；
 * > 探针图上一条凭空多出来的浅色横带当场把这事暴露了）。而且那种画法还得
 * > 知道底色才盖得干净，求交不需要。
 *
 * 左右各留 1px、下边也留 1px：描边那条线画在那一圈上，底带铺到线底下会跟它
 * 抢像素（描边是后画的，但抗锯齿的边缘上会出现两种颜色的锯齿）。
 *
 * 画不进去就什么都不画（尺寸还没定、或带子比两个半径还窄）。
 */
internal fun paintComposerFooter(g: Graphics, width: Int, height: Int, bandTop: Int, fill: Color) {
    if (width <= 2 || height <= 2 || bandTop <= 0 || bandTop >= height - 2) return
    val arc = JBUI.scale(CARD_CORNER_ARC).toDouble()
    val g2 = g.create() as Graphics2D
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val rounded = Area(RoundRectangle2D.Double(1.0, 1.0, width - 2.0, height - 2.0, arc, arc))
        // 与"从带子上沿往下的直角矩形"求交 → 上沿直、下面两个角圆
        val below = Rectangle2D.Double(
            1.0, bandTop.toDouble(), width - 2.0, (height - 1 - bandTop).toDouble(),
        )
        rounded.intersect(Area(below))
        g2.color = fill
        g2.fill(rounded)
    } finally {
        g2.dispose()
    }
}

/** 聚焦描边。取平台色，取不到时退回 New UI 的一对蓝（浅色/深色各一）。 */
internal fun focusColor(): Color = JBColor.namedColor(
    "Component.focusColor",
    JBColor(Color(0x35, 0x74, 0xF0), Color(0x54, 0x8A, 0xF7)),
)

/**
 * 可展开指示符。放在可点控件的文字末尾。
 *
 * **挤掉它，这条就看不出能点了** —— 所以 [RunStripView] 把它排除在宽度预算之外。
 */
internal const val EXPAND_CARET = " ▾"

/**
 * 危险状态的警示色。权限卡片与权限模式标签共用。
 *
 * 是同一个 `val` 而不是每次构造：测试要用它做**同一性**断言
 * （"这个标签现在用的是警示色"），每次返回新实例的话那个断言就没有意义了。
 */
private val WARNING_COLOR: Color = JBColor(Color(0xD8, 0x43, 0x15), Color(0xFF, 0x8A, 0x65))

internal fun warningColor(): Color = WARNING_COLOR

/**
 * 危险状态的色。状态卡的指示器与权限卡共用。
 *
 * 与 [SessionList] 里私有的 `DELETE_DANGER` 色值相同 —— 那处先留着不动，
 * 动它要连带改会话列表和它的测试，与本次改动无关。
 */
internal fun dangerColor(): Color = DANGER_COLOR

private val DANGER_COLOR: Color = JBColor(Color(0xC0, 0x39, 0x2B), Color(0xDB, 0x5C, 0x5C))
