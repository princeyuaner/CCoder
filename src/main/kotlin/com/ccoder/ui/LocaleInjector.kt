package com.ccoder.ui

/**
 * 把界面语言推给 JCEF 里那个页面。
 *
 * 与 [ThemeInjector] 同一个形状（**纯函数构建 JS、平台那半在别处**），理由也一样：
 * 转义与拼串是最容易写错又最难在真机上发现的一段，得能在无头单测里钉住。
 *
 * ## 只推**标签**，不推词表
 *
 * 页面上那份词表由 web 侧自己维护（`web/src/strings.ts`）。推词表的话，
 * React 就没法按自己的方式重渲染，而且 `npm run dev`、渲染探针里没有任何注入 ——
 * 那些场合下页面本来就得自带一份。代价是两份词表，各自有 parity 用例，
 * 边界写在这里免得以后有人"顺手统一"它。
 */
internal object LocaleInjector {

    /**
     * 包成可直接 `executeJavaScript` 的语句。必须单行。
     *
     * 复用 [ThemeInjector.escapeForJsString] 而不是自己再写一份转义：两份转义
     * 迟早有一份忘了某个字符，而那个字符来自哪条路径要看运气。
     *
     * `window.ccoderSetLocale &&` 那半是必需的：页面还没加载完（桥没注入）时
     * 这条会以 `TypeError` 形式静默失败，而它其实是预期路径 —— 与主题那条同款防御。
     */
    fun buildInjectScript(tag: String): String =
        "window.ccoderSetLocale && window.ccoderSetLocale('${ThemeInjector.escapeForJsString(tag)}');"
}
