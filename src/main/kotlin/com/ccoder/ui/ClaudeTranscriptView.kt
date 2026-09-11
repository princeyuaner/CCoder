package com.ccoder.ui

import com.ccoder.sidecar.TranscriptOp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 转写区。JCEF 可用时渲染 React 界面，不可用时显示明确的提示。
 *
 * 降级不重建原生渲染：那会让代码里长期并存两套渲染实现，维护面翻倍
 * （设计文档 §11 记录了这个取舍）。JCEF 不可用时给提示而非空白面板。
 */
class ClaudeTranscriptView(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser: JBCefBrowser?
    private val jsQuery: JBCefJSQuery?
    private val pump: TranscriptPump?

    /** React 挂载完成前收到的操作要暂存，否则会丢。 */
    private val beforeReady = mutableListOf<TranscriptOp>()

    @Volatile
    private var ready = false

    init {
        if (!JBCefApp.isSupported()) {
            browser = null
            jsQuery = null
            pump = null
            add(fallbackComponent(), BorderLayout.CENTER)
        } else {
            val b = JBCefBrowser()
            browser = b
            pump = TranscriptPump(exec = { json -> pushToJs(json) })

            val query = JBCefJSQuery.create(b as JBCefBrowserBase)
            jsQuery = query
            query.addHandler { message: String ->
                handleFromJs(message)
                null
            }

            add(b.component, BorderLayout.CENTER)

            injectBridge(b, query)
            installNavigationGuard(b)
            loadUi(b)
        }
    }

    private fun fallbackComponent(): JPanel = JPanel(BorderLayout()).apply {
        add(
            JLabel(
                "<html><body style='padding:16px'>" +
                    "当前 IDE 未启用 JCEF 嵌入浏览器，CCoder 无法显示对话。<br><br>" +
                    "可在 Help → Find Action 中查找 Registry，检查 ide.browser.jcef.enabled。" +
                    "</body></html>",
                SwingConstants.LEFT,
            ),
            BorderLayout.CENTER,
        )
    }

    /**
     * 注入桥。
     *
     * 必须在**每次页面加载完成之后**注入 —— 页面加载会重置 window 上的属性，
     * 在加载前注入等于白做。所以挂在 onLoadEnd 上而不是直接执行一次。
     */
    private fun injectBridge(b: JBCefBrowser, query: JBCefJSQuery) {
        val script = """
            window.ccoder = window.ccoder || {};
            window.ccoder.send = function(m) { ${query.inject("m")} };
            window.ccoderSetTheme = function(css) {
              var el = document.getElementById('ccoder-theme');
              if (!el) {
                el = document.createElement('style');
                el.id = 'ccoder-theme';
                document.head.appendChild(el);
              }
              el.textContent = css;
            };
        """.trimIndent()

        b.getJBCefClient().cefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    httpStatusCode: Int,
                ) {
                    // 只在主框架注入，iframe 不重复注入
                    if (frame?.isMain == true) {
                        b.cefBrowser.executeJavaScript(script, b.cefBrowser.url, 0)
                    }
                }
            },
        )
    }

    /**
     * 兜底：任何外部 http(s) 导航都拦下来交给系统浏览器。
     * 即使 React 侧漏了一处链接处理，也不会把整个界面导航掉。
     *
     * 用 onBeforeBrowse 而不是 CefLoadHandler —— 后者是加载开始**之后**的
     * 通知，拦不住导航。
     */
    private fun installNavigationGuard(b: JBCefBrowser) {
        b.getJBCefClient().cefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean {
                    val url = request?.url ?: return false
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return false // about:blank 之类的内部 URL 放行
                    }
                    val devServer = System.getProperty(DEV_SERVER_PROPERTY)
                    if (devServer != null && url.startsWith(devServer)) {
                        return false // 开发模式下允许 dev server 自身的导航
                    }
                    BrowserUtil.browse(url)
                    return true // 取消导航
                }
            },
        )
    }

    private fun loadUi(b: JBCefBrowser) {
        val devServer = System.getProperty(DEV_SERVER_PROPERTY)
        if (!devServer.isNullOrBlank()) {
            // 开发模式：指向 Vite dev server 拿热更新，改 React 代码不用重启 IDE
            b.loadURL(devServer)
            return
        }

        val html = javaClass.getResourceAsStream("/webui/index.html")
            ?.bufferedReader()?.use { it.readText() }
        if (html == null) {
            b.loadHTML(
                "<html><body style='padding:16px'>" +
                    "插件资源缺失：webui/index.html<br><br>" +
                    "若以 -PskipWeb 构建，这是预期的——去掉该开关重新构建。" +
                    "</body></html>"
            )
        } else {
            b.loadHTML(html)
        }
    }

    fun push(op: TranscriptOp) {
        if (op is TranscriptOp.Reset) beforeReady.clear()
        if (!ready) {
            beforeReady.add(op)
            return
        }
        pump?.enqueue(op)
    }

    /** 重新注入主题。必须在 EDT 上调用（PlatformTheme 不是线程安全的）。 */
    fun setTheme() {
        val b = browser ?: return
        b.cefBrowser.executeJavaScript(
            ThemeInjector.buildInjectScript(PlatformTheme.read()),
            b.cefBrowser.url,
            0,
        )
    }

    private fun pushToJs(json: String) {
        val b = browser ?: return
        b.cefBrowser.executeJavaScript("window.ccoder.pushBatch($json);", b.cefBrowser.url, 0)
    }

    private fun handleFromJs(message: String) {
        val obj = runCatching { JsonParser.parseString(message).asJsonObject }.getOrNull() ?: return
        when (obj.str("op")) {
            "ready" -> {
                ready = true
                // 主题必须紧跟着注入，否则会闪一帧无样式内容
                setTheme()
                beforeReady.forEach { pump?.enqueue(it) }
                beforeReady.clear()
                pump?.flushNow()
            }

            "openLink" -> obj.str("url")?.let { BrowserUtil.browse(it) }
        }
    }

    override fun dispose() {
        pump?.dispose()
        jsQuery?.let { Disposer.dispose(it) }
        browser?.let { Disposer.dispose(it) }
        beforeReady.clear()
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        const val DEV_SERVER_PROPERTY = "ccoder.devServer"
    }
}
