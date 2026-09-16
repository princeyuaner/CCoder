package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import com.ccoder.settings.displayName
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 弹层最底下的"去改"入口。
 *
 * 实现与测试共用，免得两边各写一遍然后漂移（同 [MARK]）。
 */
internal const val MANAGE_LABEL = "⚙ 管理模型…"

/** 一条配置都没有时的说明。弹层空着看起来像抽风，得讲清楚并给出唯一的出路。 */
private const val NO_PROFILE_LABEL = "还没有配置任何模型"

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
    if (profile == null) return "无模型"
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
 * 切换弹层的内容。**按配置分组**：组头一条，组里每个模型一行。
 *
 * 分组而不是平铺，是因为一次点击的后果分两类，而这两类正好按组分开：
 * **当前组是热切换**（会话与上下文都留着），**别的组多半要重开会话**
 * （端点或凭证变了，`options.env` 烤在子进程里）。组头右侧因此挂一个
 * 〔会重开会话〕，把这件事在点之前就说清楚。
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
    currentId: String?,
    effectOf: (ModelProfile) -> PickEffect,
    onPick: (ModelPick) -> Unit,
    onManage: () -> Unit,
): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(4, 4)

    if (profiles.isEmpty()) {
        addLeftAligned(
            JLabel(NO_PROFILE_LABEL).apply {
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.empty(6, 6)
            }
        )
    } else {
        profiles.forEach { p ->
            addLeftAligned(groupHeader(p, effectOf(p)))
            // 一条没配模型的配置也是合法配置（官方端点用 CLI 默认模型那种），
            // 所以它也得有一行可以选 —— 只显示组头的话，这条配置就成了纯装饰
            val ids = p.modelIds.ifEmpty { listOf("") }
            ids.forEach { addLeftAligned(modelRow(p, it, currentId, onPick)) }
        }
    }

    addLeftAligned(manageRow(onManage))
}

/**
 * 加一行，统一按左边界排。
 *
 * `JLabel` 是左对齐、`JPanel` 是居中对齐，混在一个 `BoxLayout` 里就会各自
 * 往不同的地方站 —— 空列表那行说明文字会自己往右飘 40px 出去（探针里量出来的：
 * 说明文字起于 x≈70，而下面那行"管理模型配置"起于 x≈27）。
 */
private fun JPanel.addLeftAligned(c: JComponent) {
    c.alignmentX = Component.LEFT_ALIGNMENT
    add(c)
}

/**
 * 组头：这条配置是**哪一条**，以及它会打到哪台机器。
 *
 * 名字是用户自己起的，两条配置完全可以都叫「中转」，光看名字选不出来 ——
 * 而端点是他自己填过的东西。主机名截断的限额见 [MAX_HOST_CHARS]。
 */
internal fun groupHeaderText(profile: ModelProfile): String =
    profile.displayName() + " · " + endpointLabel(profile.baseUrl)

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
internal const val NO_MODEL_LABEL = "未指定模型"

/**
 * 主机名再长也只写到这个长度。
 *
 * 弹层的宽度是**内容撑开的**，而主机名是用户填的、没有上限的字符串。
 * 实测一条 `https://api.anthropic-relay.internal.corp.example.com` 就能把
 * 弹层撑到 442px —— 比工具窗口的 420px 还宽。这里截断，让最长的一行
 * 也塞得进它要去的那块地方。
 */
private const val MAX_HOST_CHARS = 22

/** 端点只留主机名：完整 URL 太长，而"哪台机器"才是用来区分两条配置的信息。 */
private fun endpointLabel(baseUrl: String): String {
    val url = baseUrl.trim()
    if (url.isEmpty()) return "官方端点"
    val host = url.removePrefix("https://").removePrefix("http://").substringBefore('/').trim()
    // 只输了半截地址（"https://"）时宁可原样显示 —— 说成"官方端点"是反的
    if (host.isEmpty()) return url
    return if (host.length <= MAX_HOST_CHARS) host else host.take(MAX_HOST_CHARS) + "…"
}

/**
 * 一条配置的组头：名字 · 端点，右侧在需要时挂〔会重开会话〕。
 *
 * 它不响应点击 —— 点它是"选这条配置"，而那件事没有意义（配置不是可切的对象，
 * **模型**才是）。做成可点会让人以为点了能切换，实际什么都不发生。
 */
private fun groupHeader(profile: ModelProfile, effect: PickEffect): JComponent {
    val base = UIUtil.getLabelFont()
    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        // 上面多留一点：组头属于下一组，不属于上一组的最后一个模型
        border = JBUI.Borders.empty(7, 6, 2, 6)
    }

    row.add(
        JLabel(groupHeaderText(profile)).apply {
            foreground = UIUtil.getInactiveTextColor()
            font = base.deriveFont(base.size2D - 1f)
        },
        BorderLayout.WEST,
    )
    // 后果写在**点之前**（见 restartBadge）。右对齐：组头本身长度不一，
    // 挂在左边会让这一列参差不齐
    restartBadge(effect)?.let { badge ->
        row.add(
            JLabel(badge).apply {
                foreground = UIUtil.getInactiveTextColor()
                font = base.deriveFont(base.size2D - 1f)
                // 留一道缝：BorderLayout 的 WEST 与 EAST 之间没有默认间距，
                // 最长的那一行（也就是把整行宽度撑满的那一行）会贴在一起
                border = JBUI.Borders.emptyLeft(8)
            },
            BorderLayout.EAST,
        )
    }
    return row
}

private fun modelRow(
    profile: ModelProfile,
    modelId: String,
    currentId: String?,
    onPick: (ModelPick) -> Unit,
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，
    // deriveFont 会直接 NPE（RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    // 当前项判的是**两级**：既要是选中的那条配置，也要是它当前那个模型。
    // 只判配置的话，一条配置的多个模型会一起打上勾
    val selected = profile.id == currentId && profile.modelId == modelId

    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(3, 6)
    }

    // 未选中的行也缩进同样的宽度，勾出现或消失时文字不会左右跳
    val name = JLabel((if (selected) MARK else " ") + " " + modelRowText(modelId)).apply {
        foreground = UIUtil.getLabelForeground()
        font = if (selected) base.deriveFont(Font.BOLD) else base
    }

    row.add(name, BorderLayout.NORTH)

    val handler = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            onPick(ModelPick(profile.id, modelId))
        }
    }
    listOf(row, name).forEach { it.addMouseListener(handler) }

    return row
}

/**
 * "去设置里改"那一行。
 *
 * 它是另一类动作（跳出去，而不是切换当前项），所以不留勾位而用同样的缩进 ——
 * 和上面的配置名对齐，看起来才是一个列表里的最后一项，而不是一个按钮。
 */
private fun manageRow(onManage: () -> Unit): JComponent {
    val base = UIUtil.getLabelFont()

    val label = JLabel("  " + MANAGE_LABEL).apply {
        foreground = UIUtil.getInactiveTextColor()
        font = base
    }

    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // 上边多留一点：它不是又一条配置，别混在列表里
        border = JBUI.Borders.empty(7, 6, 3, 6)
        add(label, BorderLayout.NORTH)
    }

    val handler = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            onManage()
        }
    }
    listOf(row, label).forEach { it.addMouseListener(handler) }

    return row
}
