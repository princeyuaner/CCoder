package com.ccoder.ui

import com.google.gson.JsonObject

/**
 * 把界面偏好推给 JCEF 里那个页面。
 *
 * 与 [LocaleInjector] / [ThemeInjector] 同一个形状（**纯函数构建 JS、平台那半在别处**），
 * 理由也一样：这条语句写错了**不会报错** —— `executeJavaScript` 的返回值没人看，
 * 页面只会安静地停在默认形态上。转义与拼串得能在无头单测里钉住。
 *
 * ## 载荷是**对象**，不是裸布尔
 *
 * `window.ccoderPrefs` 是一份快照，不是一个开关：后面还要往同一个对象里继续加键。
 * 今天只有一个键，但形状先按多键定下来，省得日后两边一起改。
 *
 * **走这条通道的是"React 需要感知"的那类开关**（今天：思考折叠）。
 * 而字号四档与字体走的是**主题那条 CSS 通道**（见 `settings/UiFont.kt` 与 `ThemeInjector`）——
 * 它们是 CSS 变量形状的东西，页面不需要为它们重渲染；两个通道各有一条注释说明边界，
 * 别把字号挪过来（那样 `--fs-scale` 会有两个写者，谁赢靠层叠细节，是静默的）。
 *
 * ## 推入口由**桥脚本**定义，与语言那条一字不差
 *
 * `window.ccoderSetPrefs` 写在 `ClaudeTranscriptView.injectBridge` 的脚本里（写值 + 叫醒
 * React 侧的 `ccoderPrefsSink`），与 `ccoderSetLocale` / `ccoderLocaleSink` 是同一对分工：
 * **写这份快照的地方只有一处**。两份实现迟早会有一份忘了某个键，而那是静默的。
 */
internal object PrefsInjector {

    /**
     * 载荷字面量。**唯一的出处** —— 桥脚本注入的那份快照与推送用的那一份都从这儿来。
     * 两处各手拼一份，迟早有一份忘了某个键，而那是静默的（`pushBatch` 那次的形状）。
     *
     * 用 Gson 而不是手拼：与 `TranscriptOpCodec` 同一套（跨进程的载荷不手写）。
     * 今天只有布尔值，手拼也不会错 —— 但**哪天某个值变成用户可控的文本**
     * （字体名、自定义 CSS），这里要换成 `escapeForJsString` + JSON 字符串那条路，
     * 别直接往这条语句里插。
     */
    fun encode(collapseThinking: Boolean): String =
        JsonObject().apply { addProperty("collapseThinking", collapseThinking) }.toString()

    /**
     * 包成可直接 `executeJavaScript` 的语句。必须单行。
     *
     * `window.ccoderSetPrefs &&` 那半是必需的：页面还没加载完（桥没注入）时这条会以
     * `TypeError` 形式静默失败，而它其实是预期路径 —— 与主题、语言那两条同款防御。
     */
    fun buildInjectScript(collapseThinking: Boolean): String =
        "window.ccoderSetPrefs && window.ccoderSetPrefs(${encode(collapseThinking)});"
}
