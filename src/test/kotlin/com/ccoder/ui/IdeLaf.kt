package com.ccoder.ui

import javax.swing.LookAndFeel
import javax.swing.UIManager

/**
 * 真机那一套 LAF。**量几何的测试与渲染探针都得在它底下跑。**
 *
 * ## 为什么（2026-09-15 的教训）
 *
 * 用户报「右上角那个「＋」离齿轮隔着老远」。探头图上它们是挨着的 2px，全绿 ——
 * 因为测试 JVM 默认跑在 `MetalLookAndFeel` 下，而真机是 New UI。同一个
 * [TopRowIconButton]，两边的首选宽度：
 *
 * | LAF | preferred.width | 裸 `JButton()` |
 * |---|---|---|
 * | Metal（测试 JVM 默认，**哪个 IDE 都不用**） | 12 | 34 |
 * | Darcula / IntelliJ（New UI = 真机） | **72** | 78 |
 *
 * New UI 给所有 `JButton` 兜了一个 72px 的最小宽度（78 = 72 + 两侧各 3px 内边距）。
 * 图标按钮是"盒子边界即墨迹"的，于是每个图标都坐在一个 72px 宽的透明盒子里居中：
 * 屏幕上是 **62px 的空白**，而前四轮改的都是"图标之间的白"（内边距、字形盒、
 * 间距常量），颗粒度根本不对 —— 这就是它一直没被修好的原因。
 *
 * 所以：**探针和几何单测不许在 Metal 下跑**。渲染出图的那几个探针尤其要在这里，
 * 它们的全部价值就是替人看一眼真实观感。
 *
 * 用法：
 * ```
 * IdeLaf.withRealLaf { ...建组件、排版、量... }
 * ```
 * 用完还原成上一个 LAF —— 同一个 JVM 里还跑着别的测试类。
 */
internal object IdeLaf {

    /** New UI 深色。真机默认就是它（用户那台 PyCharm 2026.1）。 */
    const val DARK = "com.intellij.ide.ui.laf.darcula.DarculaLaf"

    /**
     * 浅色主题**没法在这里渲染**，别想着加一张"浅色探针图"。
     *
     * IntelliJ 的浅色不是"换一个 LAF 类"，而是 LafManager 换一套 theme JSON；
     * 测试 JVM 里没有 LafManager 在管事，`IntelliJLaf` 装上去颜色一个字节都不变 ——
     * 画出来是一张**深色**的 png 却被命名成 light，比没有更糟（2026-09-15 当场
     * 试过一张，两张图逐像素同款，然后删掉）。几何是与主题无关的（尺寸钉死在
     * 图标上），浅色要验的是颜色，那在 ThemeInjector 那一侧。
     */
    const val LIGHT = "com.intellij.ide.ui.laf.IntelliJLaf"

    /**
     * 换成 [name] 跑 [block]，跑完还原。
     *
     * **得在 EDT 之外调用**：`setLookAndFeel` 是全局的，组件在**建的那一刻**取
     * UI 委托，所以必须"先换 LAF、后建组件"。
     */
    fun <T> withLaf(name: String, block: () -> T): T {
        val previous = UIManager.getLookAndFeel()
        UIManager.setLookAndFeel(name)
        try {
            return block()
        } finally {
            UIManager.setLookAndFeel(previous)
        }
    }

    /** 真机那一套（New UI 深色）。 */
    fun <T> withRealLaf(block: () -> T): T = withLaf(DARK, block)

    /** 给 `@BeforeAll` / `@AfterAll` 用的长命版本：整个测试类都待在真机 LAF 下。 */
    fun install(): LookAndFeel? {
        val previous = UIManager.getLookAndFeel()
        UIManager.setLookAndFeel(DARK)
        return previous
    }

    fun restore(previous: LookAndFeel?) {
        if (previous != null) UIManager.setLookAndFeel(previous)
    }
}
