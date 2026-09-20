package com.ccoder.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import com.ccoder.text.CcoderText

/**
 * 「有个提问被最小化了」—— 工具窗口里的回来的路。
 *
 * ## 为什么必须有它（2026-09-15 用户报"最小化之后我找不到从哪里重新打开了"）
 *
 * 上一版把回来的路只放在**状态栏**那一行字上。那是 IDE 窗口角上一排小控件里
 * 的一格，用户根本不会往那儿看 —— 而且收起来的那个框是他**自己**挪开的，
 * 没有"它还会回来"的心理预期，红点、状态栏这类间接信号都指望不上。
 *
 * 所以这条路要长在**他当时看的那个地方**：工具窗口里。收起之后，CCoder 面板
 * 输入框上方一直挂着这条带子；点一下（或点「回答提问」）就回到原框。
 * 状态栏那格留着 —— IDE 不在前台时它仍是唯一线索，两条路互不冲突。
 *
 * ## 为什么不是 IDE 通知
 *
 * spec §6.3 在改成模态框那天就把粘性通知连通知组一起删掉了，理由是"框会自己弹到
 * 眼前，被忽略这件事在模态形态下不存在"。最小化让"被忽略"重新出现了一次，
 * 但补偿该是**用户自己挪开的东西怎么回来**，而不是再发一条要另外去点掉的通知 ——
 * 那种通知点不掉的时候比没有更烦。也不考虑气泡式弹窗：它是"现在就打断你"的
 * 语义，而用户此刻正是要去干别的。
 *
 * 配色照 [PermissionCard] 那套"要你处理"的语言：琥珀描边 + 琥珀文字，不铺底
 * （2026-09-15 用户报过"弹框有黄色背景，好丑"，两张卡一起去掉了琥珀底）。
 */
internal class AskRestoreBar(private val onRestore: () -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)) {

    private val hint = localizedLabel("ask.restoreBar").apply {
        foreground = ACCENT
    }

    private val restore = JButton().localizedText("ask.restore").apply {
        addActionListener { onRestore() }
    }

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            RoundedLineBorder({ ACCENT }, JBUI.scale(7)),
            JBUI.Borders.empty(4, 8),
        )
        add(restore)
        add(hint)

        // 整条都能点 —— 与 AskQuestionCard 的选项行同一个道理：一条带子里只有
        // 按钮那几十像素能点，会显得很钝
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val click = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onRestore()
        }
        listOf<Component>(this, hint).forEach { it.addMouseListener(click) }

        isVisible = false
    }

    /**
     * 挂起时显示，恢复/答完/终止后隐藏。
     *
     * **由调用方给的是"状态"，不是"事件"**（[syncAskRestoreBar]）——
     * 事件式接口在这里必然漏：答完、拒绝、会话停了、框被终止路径关掉，四条路
     * 都要各喊一声，漏一条屏幕上就留一个点下去什么都不发生的入口。
     */
    fun setSuspended(suspended: Boolean) {
        isVisible = suspended
        // 显隐之后必须让父容器重算：BoxLayout 会跳过不可见的子项（这一条是本项目
        // 的既有经验，见 ClaudePanel 里排队条那段），所以这条带子在不该出现时
        // 一分高度都不占 —— 但反过来，可见之后也得主动 revalidate 一次
        parent?.revalidate()
        parent?.repaint()
    }

    /** 测试与探针用：那条文字现在写的是什么。 */
    internal fun hintText(): String = hint.text

    private companion object {
        val ACCENT = JBColor(0xFFA000, 0xFFB74D)
    }
}

/** 「回答提问」：回到那个被最小化的框。 */
internal val RESTORE_LABEL: String get() = CcoderText.text("ask.restore")

/**
 * 工具窗口里那条带子（[AskRestoreBar]）该不该露头。
 *
 * 单独拎出来是为了可测：`isShowing` 在无头单测里恒为 false，"看得见"这件事
 * 在夹具里量不准，能钉住的是"**有没有可回去的提问**"这个判断本身。
 */
internal fun shouldShowAskRestore(hasSuspendedAsk: Boolean): Boolean = hasSuspendedAsk
