package com.ccoder.ui

import com.ccoder.sync.SyncSnapshot
import com.ccoder.sync.SyncTexts
import com.ccoder.text.CcoderText
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel

/** 日志框里最多倒出几条（与设置页那个框同一个口径：`LOG_KEEP` 60 条取末尾 40）。 */
private const val SYNC_LOG_LINES = 40

/**
 * 点同步卡弹出来的那一屏：**状态 + 日志**。
 *
 * ## 为什么它是个类，不像另外两段那样是个 `buildXxx` 函数
 *
 * 那两段是**一次成形**的（打开时是什么就画什么，要变就整体重画）。日志不一样：
 * 同步每跑完一轮就多几行，而用户点开它，多半正是为了**盯着**看下一轮跑到哪儿
 * （"我改的东西怎么没同步"的答案就在里面）。所以它得能**就地更新** ——
 * 而"就地"要求那几个还要再改的控件攥在手里，于是做成一个有状态的视图。
 *
 * 判据同 `ClaudePanel.refreshRunningPopup`：更新前先核一眼**它真在屏幕上**
 * （`isShowing`），别去改一个已经不在浮层里的组件。
 *
 * ## 那几句话与设置页共用词表键
 *
 * 「运行中 / 未开启 / 已停止 / 被占用 / 有失败」与「暂无」都在 `sync.state.*` /
 * `sync.page.logEmpty` 上 —— 同一件事在两处各写一份，迟早出现"一处改了、
 * 另一处还是旧口径"（`SyncTexts` 的类头注里记过这条）。
 */
internal class SyncLogDetail : JPanel(BorderLayout()) {

    /** 右侧那个状态词。**不是 `sectionHeader`**：那个是一次成形的构造器，而这一格每轮都要改。 */
    private val stateLabel = JBLabel().apply {
        // 小一号的字，主题那个 focusColor 在深色下 4.2:1 压在门槛上（见 [accentTextOn]）
        foreground = accentTextOn(popupBackdrop())
        font = font.deriveFont(font.size2D - 1f)
    }

    private val area = JBTextArea().apply {
        isEditable = false
        // 折行：日志里那些句子（"[完成] 复制 12/12，删除 3 项（0 项目标端本不存在），
        // 顺带清掉 1 个空目录，失败 1 项（用时 480 ms）"）远超 380px。
        // 设置页那个框当初就是**不折行 = 横向被裁**（出图才看出来的）
        lineWrap = true
        wrapStyleWord = true
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(4, 6)
    }

    private val scroll = JBScrollPane(area).apply {
        border = JBUI.Borders.empty()
        // 与子代理转写那一屏同一个尺寸：两个浮层都是"一段可以很长的文字"，
        // 大小不一样会让人以为它们不是同一类东西
        preferredSize = JBUI.size(380, 280)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(10, 12, 11, 12)
        add(header(), BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    /** 抬头：左边是卡片名、右边是状态词（与另两屏的小标题同一套观感）。 */
    private fun header(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        // 这一层是 BorderLayout，不必管 alignmentX（那条规矩是给 detailBox 那种
        // BoxLayout 的：交叉轴上混对齐点会让窄子件跑到中间去）
        border = JBUI.Borders.emptyBottom(6)
        add(
            JBLabel(CARD_SYNC).apply {
                // **正文色，不是 `getInactiveTextColor()`**（2026-09-28 用户：
                // "弹框里的字体颜色太暗了"）。那是主题的"次要文字色"，语义是"可以看不清"，
                // 在这块底上只有 1.9:1。小标题那点层级交给**小一号的字**，
                // 不再由颜色承担 —— 深色主题下颜色这一步没有余量（见 `rowsBox` 那段注）
                foreground = UIUtil.getLabelForeground()
                font = font.deriveFont(font.size2D - 1f)
            },
            BorderLayout.WEST,
        )
        add(stateLabel, BorderLayout.EAST)
    }

    /**
     * 灌一份新的快照。
     *
     * **滚动位置有一道闸**：文本换掉之后，原先停在底下（在看最新那条）就继续停在
     * 底下；而**用户往上翻着看旧记录时不把他拽回来** —— 那个闸就是
     * "换之前先看滚动条在不在底"。
     */
    fun setSnapshot(snapshot: SyncSnapshot) {
        val bar = scroll.verticalScrollBar
        val stickToBottom = bar.value + bar.visibleAmount >= bar.maximum - STICK_SLACK

        stateLabel.text = CcoderText.text(SyncTexts.runKey(snapshot.run))
        // 空日志给一句话，别留一个空框（用户分不清"还没有"还是"坏了"）——
        // 与设置页那个框同一条规矩
        area.text = if (snapshot.log.isEmpty()) {
            CcoderText.text("sync.page.logEmpty")
        } else {
            snapshot.log.takeLast(SYNC_LOG_LINES).joinToString("\n")
        }
        if (stickToBottom) bar.value = bar.maximum
    }

    /** 探针与用例用：框里现在是什么。 */
    internal fun textForTest(): String = area.text

    /** 探针与用例用：抬头右边那个状态词。 */
    internal fun stateForTest(): String = stateLabel.text

    private companion object {
        /** 差这么几像素就算"在底下"（滚动条极值按像素算，末位总会差一点）。 */
        const val STICK_SLACK = 2
    }
}

/**
 * 浮层自己的底 —— 挑强调色时要有个底（[accentTextOn] 吃一个底色）。
 *
 * 与气泡那边同一个道理：**底是平台画的**，我们拿不到真值（那是个
 * `JBPopupFactory` 的弹层，内容只负责往里坐）。按弹层列表那一族估 ——
 * 出图探针 `SyncLogDetailRenderProbe` 用的也是它，这样图上看到的字色就是真机上那个。
 *
 * 正文与小标题不走这里：它们直接用 [UIUtil.getLabelForeground]（见上面那两处注释）。
 */
private fun popupBackdrop(): Color = UIUtil.getListBackground()
