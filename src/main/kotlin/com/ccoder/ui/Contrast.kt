package com.ccoder.ui

import java.awt.Color

/**
 * "这个颜色在**这个底**上看得清吗" —— 对比度计算与按对比度选色。
 *
 * ## 为什么需要它（2026-09-16 用户截图）
 *
 * 计划审批框里的行内代码取自 `UIUtil.getTreeSelectionBackground` —— 那是
 * **选中块的底色**，语义上就是"用来衬字的背景"。深色主题下它是个深蓝，
 * 拿它当前景色往深色卡片上画，等于深蓝印在深灰上：对底色约 **1.6:1**，
 * 不是"不好看"，是**读不了**。
 *
 * 教训不是"换一个蓝色"，而是：**主题给的颜色得先问一句它是干什么用的**。
 * 所以这里不写死品牌色，改成"给候选、量对比度、挑第一个够用的" ——
 * 主题换（Darcula / Material / 高对比 / 浅色）都不用重新挑色。
 *
 * 判据用 WCAG 的对比度公式（1:1 ~ 21:1）。正文 4.5:1 是那个标准的 AA 档，
 * 这里也拿它当门槛：[pickReadable] 的默认值。
 */

/** AA 档：正文文字该有的对比度。 */
internal const val CONTRAST_AA = 4.5

/**
 * 兜底的两个蓝：深色底上亮的赢、浅色底上深的赢 —— 由对比度算出来，不是 if 出来的。
 *
 * 到今天只有一处取色用它俩当最后一道：[PermissionCard] 的计划正文（HTML 里那点颜色）。
 * （2026-09-16 曾同时给 [ThemeInjector] 算过一份给网页层的，随"正文路径可点击"那版
 * 一起撤了 —— 网页那边现在仍用主题给的 `--accent` 当文字色。）
 */
internal val CODE_BLUE_BRIGHT = Color(0x7F, 0xB0, 0xFF)
internal val CODE_BLUE_DEEP = Color(0x1F, 0x6F, 0xEB)

/** WCAG 相对亮度（0 = 黑，1 = 白）。人眼对绿最敏感、对蓝最不敏感，权重照标准来。 */
internal fun relativeLuminance(color: Color): Double {
    fun channel(value: Int): Double {
        val s = value.coerceIn(0, 255) / 255.0
        // 标准里的分段函数：暗部线性、亮部按 2.4 次幂 —— 直接拿 s 算，
        // 深色主题下会把"深蓝"估得太亮，于是继续用它（正是要修的那个毛病）
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** 两个颜色的对比度（1:1 ~ 21:1）。与谁在前谁在后无关。 */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * 从候选里挑一个在 [background] 上看得清的。
 *
 * 依次取**第一个够 [minRatio] 的**；一个都不够（底色太极端）就给对比度最高的
 * 那个 —— 那时没有更好的答案，但至少别给最差的。
 *
 * 候选的**顺序就是优先级**：主题自己的颜色排前面（跟 IDE 一致），
 * 手挑的那几个当兜底（主题给的是底色时它们会顶上）。
 */
internal fun pickReadable(
    candidates: List<Color>,
    background: Color,
    minRatio: Double = CONTRAST_AA,
): Color = candidates.firstOrNull { contrastRatio(it, background) >= minRatio }
    ?: candidates.maxByOrNull { contrastRatio(it, background) }
    ?: background

/**
 * 把 [fg] 往 [bg] 的方向压：[keep] = 1 原样，0.85 = 留 85%。
 *
 * 用于"次要一点、但还得看清"的文字（小节标题）：主题给的 `InactiveTextColor`
 * 在深色下常常只有 3.4:1，压得过头了；从**正常前景**往下压一档，
 * 层级还在，但不掉到读不清。
 */
internal fun blend(fg: Color, bg: Color, keep: Double): Color {
    fun mix(a: Int, b: Int) = (a * keep + b * (1 - keep)).toInt().coerceIn(0, 255)
    return Color(mix(fg.red, bg.red), mix(fg.green, bg.green), mix(fg.blue, bg.blue))
}
