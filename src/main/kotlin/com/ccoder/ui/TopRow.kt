package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 右上角两个图标按钮之间的间距。
 *
 * **2026-09-14 用户反馈"间隔太大"，而那 6px 其实不是这里给的**：两个按钮各自
 * 默认边框带着 3px 内边距，边框画都不画（`isBorderPainted = false`），内边距
 * 却照样占着。所以这一版做两件事：横向那部分内边距去掉（见
 * [asTopRowIconButton]），间隔改由这个常量**显式**给 —— 一个看得见的数，
 * 好过一个藏在边框里的数。
 */
internal val TOP_ROW_GAP: Int = JBUI.scale(2)

/**
 * 顶部一行：会话标签占满中间（长标题自己打省略号），两个图标按钮在最右。
 *
 * 抽成独立函数是为了可测 —— [ClaudePanel] 依赖 `Project`，起不了单测，
 * 而"这两个按钮挨多近""谁在左"恰好是这一版改的两件事。探针
 * [TopRowRenderProbe] 画的也是这一份，不是画一份长得像的。
 *
 * **顺序：「＋」在左、齿轮占最右角**（2026-09-14 用户要求对调，原为齿轮在左）。
 * 顺序写进这个函数而不是留给调用处，是为了让上面那句话**能被测到** ——
 * 调用处只有一行，测不着。
 */
internal fun buildTopRow(
    sessionLabel: JComponent,
    settingsButton: JComponent,
    newSessionButton: JComponent,
): JPanel = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.empty(4, 8)
    add(sessionLabel, BorderLayout.CENTER)
    add(
        JPanel().apply {
            // X_AXIS 且容器宽度就给首选宽（BorderLayout 的 EAST 槽）：
            // 两个按钮不会被拉伸，所以"挨多近"完全由下面的间距说了算
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(newSessionButton)
            add(Box.createHorizontalStrut(TOP_ROW_GAP))
            add(settingsButton)
        },
        BorderLayout.EAST,
    )
}

/**
 * 右上角图标按钮的统一造型。齿轮与「＋」各调它一次 —— **只写一遍**。
 *
 * 理由与 [SessionNewButton] 最初的注释同源：同一行里两个按钮，一个有边框一个
 * 没有、字号差半号，并排放在一起都扎眼。分开写就迟早会分叉。
 *
 * 有一处不是随手写的：那个 `border`。默认边框的 3px 内边距**两个方向待遇不同**：
 *  - **横向**：纯粹是空隙（两个按钮之间那 6px 全从这儿来）—— 去掉，间隔改由
 *    [TOP_ROW_GAP] 显式给；
 *  - **竖向**：那是**点击区域的高度**，留着。一起去掉的话按钮会比这一行矮
 *    6px，可点的地方明显变小 —— 而这件事在截图上看不出来。
 *
 * 3px 是平台边框给的，不同 LAF、不同缩放下未必是这个数，所以**读出来再用**，
 * 不写死。
 */
internal fun JButton.asTopRowIconButton(): JButton = apply {
    isContentAreaFilled = false
    isBorderPainted = false
    isFocusable = false
    margin = JBUI.emptyInsets()
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    font = font.deriveFont(15f)
    // 先读后改：改完再读就是 0 了
    val vPad = insets.top
    border = JBUI.Borders.empty(vPad, 0)
}

/**
 * 右上角的齿轮。
 *
 * 与「＋」的唯一差别是它**不随忙闲置灰** —— 它开的是设置对话框，而对话框只
 * 读写配置、不碰会话（见 `ClaudePanel.showModelProfilesDialog`），会话进行中
 * 也该能开。
 */
internal fun settingsGearButton(onClick: () -> Unit): JButton = JButton("⚙").apply {
    asTopRowIconButton()
    foreground = UIUtil.getLabelForeground()
    toolTipText = "设置"
    addActionListener { onClick() }
}
