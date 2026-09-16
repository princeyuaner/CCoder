package com.ccoder.ui

import com.intellij.util.ui.JBUI
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.KeyStroke

/**
 * 卡片在窗口里的自然宽度：与工具窗口那一栏同宽（设计稿里 `.tw` 的宽度）。
 *
 * 两张卡片都要它，理由相同：里面那个**换行的**文本区报出来的 preferred 宽度
 * 只有一百多 px —— 那个数的意思是"最窄也能活"，不是"该有多宽"。住在工具窗口里时
 * 卡片被那一栏撑着，谁也看不出问题；进了对话框没有东西撑它，窗口会缩成一条
 * （权限框上量到过 152px，三个按钮一行都排不下）。
 *
 * 写成 getter 而不是常量：`JBUI.scale` 认的是当前的界面缩放，取值时才定得下来。
 */
internal val CARD_WIDTH: Int get() = JBUI.scale(420)

/**
 * 权限（含计划审批）那张卡的宽度 —— **比 [CARD_WIDTH] 宽一档**（2026-09-16 方案 B）。
 *
 * ## 为什么不直接改 [CARD_WIDTH]
 *
 * 那个常量提问框也在用。两个框遇到的问题不一样：提问框的窄是**长题干不换行**撑出来的
 * （已由它自己的 `getMinimumSize` 兜住），权限框的窄是**计划正文长**——满屏都是路径，
 * 420 宽下一行 `trunk/player/achievement/condition/condition.py:AchievementCondition`
 * 要折三行。改共用常量等于顺手把提问框也改了，而那个框没量过、也没人抱怨过。
 *
 * 640 是设计稿里量出来的折中点：比 420 宽一半，常见路径一行放得下，1080p 上也只占
 * 屏宽的一半多一点（见 `docs/design/plan-dialog-v2.html`）。
 */
internal val PERMISSION_CARD_WIDTH: Int get() = JBUI.scale(640)

/**
 * 计划区一开始给多高：**按屏幕算，不写死**（方案 B）。
 *
 * 比例下限上限三个数都在这里，[planAreaHeight] 是纯函数，单测盯着。
 * 上限 520 是为了"按钮一定还在屏幕里"：计划区再高也不该把「拒绝 / 允许」顶出视野。
 */
internal const val PLAN_AREA_SCREEN_FRACTION = 0.45
internal const val PLAN_AREA_MIN = 260
internal const val PLAN_AREA_MAX = 520

/** 屏幕高 → 计划区高度。比例 × 屏幕高，再夹到下限上限之间。 */
internal fun planAreaHeight(screenHeightPx: Int): Int =
    (screenHeightPx * PLAN_AREA_SCREEN_FRACTION).toInt().coerceIn(PLAN_AREA_MIN, PLAN_AREA_MAX)

/**
 * 两个模态框（权限 / 提问）共用的几件小事。
 *
 * 形态是同一套：内容面板自带按钮、不画平台那对 OK/Cancel、关掉就等于拒绝。
 * 规则本身不长，但漏一条的后果很重，所以写成一份 —— 两个框各自钉一遍，
 * 迟早有一处漂移。
 *
 * ## Esc 这一条的事实（先看清楚，别被探针误导）
 *
 * 平台**是**注册取消绑定的，只是那件事发生在 `show()` 里
 * （`DialogWrapper.doShow()`：`registerKeyboardAction(cancel, VK_ESCAPE, WHEN_IN_FOCUSED_WINDOW)`）。
 * 所以它在我们那些**从不 `show()`** 的测试里查不到 —— 早期的探针逐个 condition
 * 翻 input map 什么都没翻到，就是这个原因，不是平台没做。
 *
 * [bindEscapeToDeny] 于是是**自备的等价绑定**，两个用处：
 * ①让"Esc = 拒绝"这条安全规则在没 `show()` 的测试里也能被钉住；
 * ②万一哪天平台不注册了（或有人覆写 `createCancelAction()` 返回 null），行为不变。
 * 真到 show 的时候平台会往同一张 input map 再 put 一次（它赢）——
 * 两条路都通向 `doCancelAction()`，不会变成两个行为。
 *
 * 重复触发不要紧：调用方那边有"只回一次"的闸。
 */
internal fun JRootPane.bindEscapeToDeny(action: () -> Unit) {
    getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ESCAPE_ACTION_KEY)
    actionMap.put(
        ESCAPE_ACTION_KEY,
        object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        },
    )
}

/** 根面板动作表里的键名。两个框各用各的实例，同名不冲突。 */
private const val ESCAPE_ACTION_KEY = "ccoder.modal.deny"
