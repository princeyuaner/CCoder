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
internal const val MANAGE_LABEL = "管理模型配置…"

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
                // ModeLabel 现在还没有，回头一起补上
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
     * 取的是各自语义上对的那一档，和 [SessionLabel] 的做法一致，换主题就分得开。
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
 * 标签上写什么。
 *
 * null 是"一条配置都没配 / 没选中"，与"选中了一条没名字的"是两回事 ——
 * 后者由 [ModelProfile.displayName] 回退到模型 ID。回退规则只有那**一处**，
 * 这里委托过去而不是再抄一遍，否则两处的规则迟早会分家。
 */
internal fun modelLabelText(profile: ModelProfile?): String =
    profile?.displayName() ?: "无模型"

/**
 * 切换弹层的内容。当前项打勾。
 *
 * **只负责"切"，不负责"改"** —— 编辑是设置那一页的事。把表单塞进来会让
 * "点一下切换"变成"点一下进表单"，高频动作被低频动作拖累。要看配置详情、
 * 要改密钥，出口是最后那行 [MANAGE_LABEL]。
 */
internal fun buildModelList(
    profiles: List<ModelProfile>,
    currentId: String?,
    onPick: (ModelProfile) -> Unit,
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
        profiles.forEach { addLeftAligned(modelRow(it, it.id == currentId, onPick)) }
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
 * 行里的第二行：这条配置会打到哪个模型、哪个端点。
 *
 * [ModeLabel] 那边写的是"选下去意味着什么"，因为那几个模式名字没人看得懂；
 * 这里写的是"这条到底是哪条" —— 名字是用户自己起的，两条配置完全可以都叫
 * 「中转」，光看名字选不出来，而 modelId 和端点是他自己填过的东西。
 */
internal fun modelDetail(profile: ModelProfile): String =
    profile.modelId.trim().ifEmpty { "未填写模型 ID" } + " · " + endpointLabel(profile.baseUrl)

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

private fun modelRow(
    profile: ModelProfile,
    selected: Boolean,
    onPick: (ModelProfile) -> Unit,
): JComponent {
    // 显式取字体：未挂到层级上时 getFont() 可能是 null，
    // deriveFont 会直接 NPE（RunStripView 上踩过同一个坑）
    val base = UIUtil.getLabelFont()

    val row = JPanel(BorderLayout()).apply {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(3, 6)
    }

    // 未选中的行也缩进同样的宽度，勾出现或消失时文字不会左右跳
    val name = JLabel((if (selected) MARK else " ") + " " + profile.displayName()).apply {
        foreground = UIUtil.getLabelForeground()
        font = if (selected) base.deriveFont(Font.BOLD) else base
    }

    val detail = JLabel(modelDetail(profile)).apply {
        foreground = UIUtil.getInactiveTextColor()
        font = base.deriveFont(base.size2D - 1f)
    }

    row.add(name, BorderLayout.NORTH)
    row.add(detail, BorderLayout.SOUTH)

    // 整行可点，不只是文字那一小块 —— 一行里拆成了两个标签，
    // 点到模型 ID 上没反应会显得很钝
    val handler = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            onPick(profile)
        }
    }
    listOf(row, name, detail).forEach { it.addMouseListener(handler) }

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
