package com.ccoder.ui

import com.intellij.openapi.util.registry.Registry

/**
 * 这一趟 JCEF 是不是跑在**独立进程**里（out-of-process / remote CEF server）——
 * 以及怎么把它关掉。
 *
 * ## 它为什么是个单独的东西
 *
 * IntelliJ 2025.2 起，JCEF 默认不再住在 IDE 进程里，而是丢给一个 `cef_server`
 * 子进程，两边靠一条 RPC 说话。那个模式在随 2025.2+ 发出去的 JCEF 144 上有个
 * 已登记的缺陷 —— **JBR-9234**：只要建 `JBCefJSQuery` 的消息路由就 NPE
 * （`RemoteMessageRouterImpl.create` 读 `robj.isNull` 时空指针）。
 *
 * 那正是转写区这条线上的两个症状，一个是"起不来"、一个是"跑着跑着不动了"：
 *
 * - `newTranscriptCef` 里 `JBCefJSQuery.create` 抛的 NPE（→ [TranscriptFallback.Reason.StartFailed]）；
 * - 建得起来、跑一会儿 `executeJavaScript` 被 `RpcExecutor` 静默丢掉，
 *   心跳连着两拍收不到 pong（→ [TranscriptFallback.Reason.ChannelLost]）。
 *
 * 看着像两件事，其实是同一堵墙：**那条 RPC 通道本身是坏的**。
 *
 * ## 解药就是关掉它
 *
 * IDE 自己的 registry 键 `ide.browser.jcef.out-of-process.enabled`。2026-09-23 在用户
 * 本机核过（`IntelliJ IDEA 2026.2.3/lib/util-8.jar!misc/registry.properties`）：
 *
 * ```properties
 * ide.browser.jcef.out-of-process.enabled=true
 * ide.browser.jcef.out-of-process.enabled.restartRequired=true
 * ```
 *
 * 翻成 `false` 就退回进程内 JCEF —— 那条 RPC 路径整个不存在，NPE 无从谈起。
 * **翻完必须完全重启 IDE**（进程退出重开，不是回欢迎页）：这个键是 `JBCefApp`
 * 初始化时读的一次。
 *
 * ## 这不是我们想出来的偏方
 *
 * `jetbrains-cc-gui`（ccgui）跑在同一条路上，检测到同一族失败后给的就是这一键
 * （`JBCefBrowserFactory.disableOutOfProcessJcefInRegistry`）。它那边的注释把结论
 * 写得更直白 —— *"Terminal state: the remote CefServer process is unhealthy, so
 * every reload/recreate against it will keep throwing the same NPE"*，
 * 和我们心跳判死后认定「重试是条死路」是同一个判断。
 */
internal object JcefRemoteMode {

    /** IDE 的 registry 键名。改动它就等于改动"JCEF 住哪"。 */
    const val KEY = "ide.browser.jcef.out-of-process.enabled"

    /**
     * 现在是不是正跑在那条会踩 JBR-9234 的路上。
     *
     * **未知一律按 `false`**：取值那一步的兜底给的是 `false`，读不出来（用例里没有
     * Application、平台换了实现）也是 `false`。方向是刻意的 —— 判不准的时候我们
     * 没法真的做那个修复，那就不该把人往"重启 IDE"上引，给「重试」就够了。
     * 老平台根本没有这个键，也走这一条。
     */
    // 反引号不是笔误：`is` 在 Kotlin 里是硬关键字，Java 那个 `Registry.is` 只能这么叫
    fun isEnabled(): Boolean = runCatching { Registry.`is`(KEY, false) }.getOrDefault(false)

    /**
     * 关掉它。**写完读回来验**：这个键默认是 `true`，所以只有读回 `false` 才算数。
     *
     * 写不进去就回 `false`，调用方据此把人指到手动那条路（改 vmoptions）——
     * 报一声"已关闭"然后让用户白重启一次，比不报还糟。
     */
    fun disable(): Boolean = runCatching {
        Registry.get(KEY).setValue(false)
        !Registry.`is`(KEY, true)
    }.getOrDefault(false)
}
