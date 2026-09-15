package com.ccoder.ui

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * 转写区里"复制代码块"这条链路（前端那个复制键的收端）。
 *
 * ## 为什么不走 `navigator.clipboard`
 *
 * JCEF 里页面是 `loadHTML()` 塞进去的，**不是安全上下文** ——
 * `navigator.clipboard` 在那里是 `undefined`：按了复制键什么都不发生
 * （2026-09-15 用户报「复制没用」，前端还把它 catch 掉了，屏幕上一点痕迹都没有）。
 * 平台这边的 [CopyPasteManager] 一直在，也一直是 IDE 里复制东西的正路。
 *
 * 前端保留了一条 navigator 兜底：那是给浏览器里跑的探针与 `npm run dev` 用的，
 * 不是真机上这条路。
 */

/** 从桥消息里读要复制的文本（`{"op":"copy","text":"..."}`）。空的、缺的、类型不对的不复制。 */
internal fun parseCopyText(obj: JsonObject): String? =
    obj.get("text")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.takeIf { it.isNotEmpty() }

/**
 * 放进系统剪贴板。
 *
 * 桥的回调来自 CEF 线程（不保证是 EDT），而 [CopyPasteManager] 是平台服务 ——
 * 不猜自己在哪条线程上，一律回 EDT。
 *
 * 留一行日志：复制的反馈在屏幕上只有前端那个「已复制」，真出错时（比如复制键
 * 根本没走到这儿）日志是唯一能分清"没发"与"发了没生效"的地方。
 */
internal fun copyToClipboard(text: String) {
    ApplicationManager.getApplication().invokeLater {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        LOG.info("CCoder 转写视图：复制 ${text.length} 个字符到剪贴板")
    }
}

private val LOG = Logger.getInstance("com.ccoder.ui.CopyText")
