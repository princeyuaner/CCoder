package com.ccoder.ui

import java.awt.Dimension

/**
 * 浮层定位。
 *
 * 这里原先还有一条"上下文右边那条"：一个自画的胶囊，文字按可用宽度截断
 * （`runStripText` + `ellipsize`），把任务清单与在跑的子代理挤在一行里。
 * 它已经拆成四张状态卡（见 [StatusCardsRow]）。
 *
 * 留下的是"浮层该出现在哪"的计算 —— 那部分与卡片形状无关，点会话标签、
 * 点状态卡、点权限模式都用同一套。
 */

/**
 * 浮层将要占据的高度。
 *
 * **不能读 `JBPopup.getSize()`** —— 它给的是弹窗"窗口"的尺寸，而窗口要等
 * show 之后才建出来，在那之前恒为 null。上一版就是这么读的，于是每次点开
 * 浮层都 NPE。（先出现在"任务条"那条路上，而那条平时不显示，所以一直没被
 * 踩到；权限模式标签一上线就被点出来了。）
 *
 * 退回内容的首选高度：那正是浮层将要采用的尺寸。两者都没有时给 0 ——
 * 0 会让 [popupAnchorY] 退化成"贴着锚点上方"，可接受；抛错则整个点击都废掉。
 */
internal fun popupHeightOf(size: Dimension?, contentPreferred: Dimension?): Int =
    size?.height ?: contentPreferred?.height ?: 0

/** 浮层将要采用的宽度。与 [popupHeightOf] 同一套退回逻辑，只是取宽。 */
internal fun popupWidthOf(size: Dimension?, contentPreferred: Dimension?): Int =
    size?.width ?: contentPreferred?.width ?: 0

/**
 * 详情浮层该出现在哪个 y（屏幕坐标）。
 *
 * 锚点（这条）本来就在工具窗口最底部 —— 它下面就是窗口边缘，再往下是屏幕边缘，
 * 所以**向上弹是常态而不是边角情况**。
 *
 * 自己算而不用平台的 `showUnderneathOf`：那个方法会不会在下方没空间时自动
 * 翻转，没有验证过。算错的后果是浮层跑到屏幕外，与"希望平台帮忙"相比，
 * 自己算至少是看得见的。
 */
internal fun popupAnchorY(
    anchorTop: Int,
    anchorHeight: Int,
    popupHeight: Int,
    screenTop: Int,
    screenBottom: Int,
    gap: Int,
): Int {
    val below = anchorTop + anchorHeight + gap
    if (below + popupHeight <= screenBottom) return below

    val above = anchorTop - gap - popupHeight
    if (above >= screenTop) return above

    // 上下都放不下（任务很多时浮层可以比屏幕还高）：贴屏幕顶。
    // 贴底会让标题看不见，而标题是"这是什么"的唯一线索
    return screenTop
}

/**
 * 浮层该出现在哪个 x（屏幕坐标）：**贴面板左边缘**，再钳进屏幕。
 *
 * 2026-09-15 按用户要求统一靠左。此前是两种行为混着：模型与会话那两个长列表
 * **居中于面板**，权限 / 思考 / 详情贴着各自锚点的左边缘 —— 一列弹层点下来
 * 横坐标每次都不一样；而居中那两个离自己的锚点最远（锚点在底部左侧，
 * 弹层却从面板中间开始）。
 *
 * 为什么贴**面板**而不是贴锚点：会话列表的锚点是**右对齐**的会话标签，
 * 标题短时标签缩到最右，弹层跟着跑过去会整块溢出面板。贴面板把两件事一起解了。
 *
 * 基准取面板而不是屏幕：用户看到的是面板；以屏幕中线为准，面板在左半边时
 * 弹层会跟它脱开。
 */
internal fun popupLeftX(
    panelLeft: Int,
    popupWidth: Int,
    screenLeft: Int,
    screenRight: Int,
): Int {
    // 浮层比屏幕还宽时区间会反过来，coerceIn 遇空区间会抛 —— 先保证 lo <= hi
    val lo = screenLeft
    val hi = maxOf(lo, screenRight - popupWidth)
    return panelLeft.coerceIn(lo, hi)
}

