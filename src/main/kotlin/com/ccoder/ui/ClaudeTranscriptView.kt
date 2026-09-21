package com.ccoder.ui

import com.ccoder.settings.FontChoice
import com.ccoder.settings.FontScale
import com.ccoder.settings.UiPreferences
import com.ccoder.settings.resolveUiFonts
import com.ccoder.sidecar.TranscriptOp
import com.ccoder.text.CcoderText
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
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
            LOG.warn("CCoder 转写视图：当前 IDE 未启用 JCEF，转写区降级为提示文本")
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
            installReadyWatchdog()

            // 主题切换时重新注入 CSS 变量（spec §4.1）。
            // connect(this) 让它随本组件一起释放。
            project.messageBus.connect(this).subscribe(
                LafManagerListener.TOPIC,
                LafManagerListener { setTheme() },
            )
        }
    }

    private fun fallbackComponent(): JPanel = JPanel(BorderLayout()).apply {
        add(
            JLabel(
                "<html><body style='padding:16px'>" +
                    CcoderText.text("transcript.fallback.noJcef") +
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
        // 注入时那份偏好快照 —— 与下面那行 `window.ccoder.locale` 同一用意：页面在
        // React 的挂载 effect 之前（首帧）就能读到它，`prefs.ts` 的惰性读也才有东西可读。
        // 之后的新值由 ready 握手补推（见 [setPreferences]）。
        // 服务取不到就按默认档 —— 桥建不起来比偏好读不准严重得多（同 [setPreferences]）。
        val prefsJson = PrefsInjector.encode(
            UiPreferences.getInstanceOrNull()?.collapseThinking ?: false,
        )
        val script = """
            window.ccoder = window.ccoder || {};
            window.ccoder.send = function(m) { ${query.inject("m")} };
            window.ccoder.locale = '${ThemeInjector.escapeForJsString(CcoderText.tag())}';
            window.ccoderSetTheme = function(css) {
              var el = document.getElementById('ccoder-theme');
              if (!el) {
                el = document.createElement('style');
                el.id = 'ccoder-theme';
                document.head.appendChild(el);
              }
              el.textContent = css;
            };
            window.ccoderSetLocale = function(tag) {
              window.ccoder.locale = tag;
              window.ccoderLocaleSink && window.ccoderLocaleSink(tag);
            };
            window.ccoderPrefs = $prefsJson;
            window.ccoderSetPrefs = function(prefs) {
              window.ccoderPrefs = prefs;
              window.ccoderPrefsSink && window.ccoderPrefsSink(prefs);
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
                        LOG.info("CCoder 转写视图：页面加载完成（HTTP $httpStatusCode），注入桥")
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
                    CcoderText.text("transcript.fallback.noAssets") +
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

    /**
     * 重新注入主题（颜色**与字体**）。必须在 EDT 上调用（PlatformTheme 不是线程安全的）。
     *
     * 字体走同一条路：家族名与字号档位一起算进那份 CSS（见 `resolveUiFonts`），
     * 所以"设置里换字体/字号"也是调这个方法（`ClaudePanel.openModelSettings`）。
     *
     * 偏好服务取不到就按默认档（跟随 IDE / 标准）—— 同 [setPreferences] 那条理由：
     * 这条路跑在 ready 分支与 LAF 切换里，抛出去会把后面"补推转写"整段吞掉。
     */
    fun setTheme() {
        val b = browser ?: return
        val colors = PlatformTheme.read()
        val prefs = UiPreferences.getInstanceOrNull()
        if (prefs == null) {
            LOG.warn("CCoder 转写视图：界面偏好服务没取到，主题里的字体按默认档（跟随 IDE / 标准）")
        }
        val fonts = resolveUiFonts(
            prefs?.fontChoice ?: FontChoice.DEFAULT,
            prefs?.fontScale ?: FontScale.DEFAULT,
            colors.fontUi.family,
            colors.fontMono.family,
        )
        b.cefBrowser.executeJavaScript(
            ThemeInjector.buildInjectScript(colors, fonts),
            b.cefBrowser.url,
            0,
        )
    }

    /**
     * 把"页面要用、但 Kotlin 才有的真相"一次推齐：主题（颜色**与字体**）、语言、界面偏好。
     *
     * 为什么合成一个入口：这三条各有各的调用时机，但**调用点只有两处** ——
     * `ready` 握手与设置对话框关掉之后。分开写的话，下次再加一条通道总会漏掉一处，
     * 而漏掉的表现是**静默的**（某一项一直不生效，界面看起来完全正常）。
     *
     * **顺序即语义**：主题在最前、偏好最后，而三者都排在补推转写内容之前 ——
     * 那正是"第一个含思考块的渲染就拿着正确的主题/字体/偏好"的证明（见 ready 分支）。
     */
    fun pushUiState() {
        setTheme()
        setLocale()
        setPreferences()
    }

    /**
     * 把当前界面语言推给页面。
     *
     * 与主题同路、同理由：页面加载完就推一次（`ready` 那一步），否则会先按基础
     * 语言（英文）画一帧，再被这条纠正 —— 那一下是**看得见**的闪。
     */
    fun setLocale() {
        val b = browser ?: return
        b.cefBrowser.executeJavaScript(
            LocaleInjector.buildInjectScript(CcoderText.tag()),
            b.cefBrowser.url,
            0,
        )
    }

    /**
     * 把界面偏好推给页面（今天只有一个「思考折叠」，见 `UiPreferences`）。
     *
     * 与主题、语言同路、同理由：页面加载完就推一次（`ready` 那一步），而且必须排在
     * **补推转写之前** —— 否则第一个思考块会先按默认（展开）画一帧再收起，那一下是
     * 看得见的跳。设置对话框关掉之后再推一次，管的是"改完当场生效"。
     *
     * 服务在 `browser ?: return` **之后**才读，而且**取不到也不抛**：这条跑在 ready 分支
     * 这一侧（CEF 回调线程），抛出去会把后面那句"补推滞留的转写"整段吞掉 ——
     * 症状是**界面全空、零报错**（2026-09-11 那次排查花了半程）。取不到就按默认档推
     * （不折叠），界面必须能开（2026-09-20 那次 NPE 起不来的教训）。
     */
    fun setPreferences() {
        val b = browser ?: return
        val prefs = UiPreferences.getInstanceOrNull()
        if (prefs == null) {
            LOG.warn("CCoder 转写视图：界面偏好服务没取到，本次按默认（不折叠）推")
        }
        b.cefBrowser.executeJavaScript(
            PrefsInjector.buildInjectScript(prefs?.collapseThinking ?: false),
            b.cefBrowser.url,
            0,
        )
    }

    /**
     * 前端迟迟不就绪时，把页面内部状态写进日志。
     *
     * 没有这个的话，握手失败的表现是"界面全空且毫无报错"——状态栏还显示
     * "已连接"（那是 sidecar 的状态，与前端无关），用户和开发者都无从下手。
     * 实测踩过一次：2026-09-11 定位这个问题花了大半程，就是因为插件不打日志。
     *
     * 宁可日志吵一点，也不要一个静默失败的黑盒。
     */
    private fun installReadyWatchdog() {
        javax.swing.Timer(READY_TIMEOUT_MS) {
            if (ready) {
                LOG.info("CCoder 转写视图：前端就绪")
                return@Timer
            }
            val b = browser ?: return@Timer
            val q = jsQuery ?: return@Timer
            LOG.warn("CCoder 转写视图：${READY_TIMEOUT_MS / 1000} 秒内未收到前端 ready，请求页面状态")
            val probe = """
                (function(){
                  var r = document.getElementById('root');
                  var info = {
                    op: 'pageState',
                    hasRoot: !!r,
                    rootChildren: r ? r.childElementCount : -1,
                    readyState: document.readyState,
                    url: location.href.slice(0, 80),
                    ccoder: typeof window.ccoder,
                    pushBatch: typeof (window.ccoder && window.ccoder.pushBatch),
                    send: typeof (window.ccoder && window.ccoder.send),
                    locale: typeof (window.ccoder && window.ccoder.locale),
                    setLocale: typeof window.ccoderSetLocale,
                    prefs: typeof (window.ccoder && window.ccoderPrefs),
                    setPrefs: typeof window.ccoderSetPrefs
                  };
                  ${q.inject("JSON.stringify(info)")}
                })();
            """.trimIndent()
            b.cefBrowser.executeJavaScript(probe, b.cefBrowser.url, 0)
        }.apply { isRepeats = false; start() }
    }

    private fun pushToJs(json: String) {
        val b = browser ?: return
        // 必须经 encodePushCall 包成 JS 字符串字面量 —— 直接内联 JSON 会让
        // web 侧拿到对象而非字符串，静默失败。见 TranscriptOpCodec.encodePushCall。
        b.cefBrowser.executeJavaScript(
            TranscriptOpCodec.encodePushCall(json), b.cefBrowser.url, 0,
        )
    }

    private fun handleFromJs(message: String) {
        val obj = runCatching { JsonParser.parseString(message).asJsonObject }.getOrNull() ?: return
        when (obj.str("op")) {
            "ready" -> {
                ready = true
                LOG.info("CCoder 转写视图：收到前端 ready，补推滞留的 ${beforeReady.size} 条")
                // 主题、语言、偏好都得排在**补推转写之前**：主题不推会闪一帧无样式内容；
                // 语言不推会先按基础语言画一帧；偏好不推则第一个思考块先展开再收起。
                // 三条合成一个入口，顺序就在它的注释里（pushUiState）
                pushUiState()
                beforeReady.forEach { pump?.enqueue(it) }
                beforeReady.clear()
                pump?.flushNow()
            }

            // 看门狗探针的回报：前端没就绪时，这是唯一能看到的页面内部状态
            "pageState" -> LOG.warn("CCoder 转写视图：页面状态 $message")

            "openLink" -> obj.str("url")?.let { BrowserUtil.browse(it) }

            // 卡片上点了文件名：在编辑器里打开并定位（见 OpenFileTarget）。
            // 这条链路自己会跳线程，CEF 回调线程上直接调没问题
            "openFile" -> parseOpenFileTarget(obj)?.let { openInEditor(project, it) }

            // 代码块的复制键（见 CopyText）：JCEF 里 navigator.clipboard 不可用
            // （不是安全上下文），复制这件事只能回平台来做
            "copy" -> parseCopyText(obj)?.let(::copyToClipboard)

            // 认不出的 op 不留静默。桥这一层是"界面全对、日志全干净、
            // 就是没反应"最典型的产地，而这个文件已经为这类问题付过一次代价
            else -> LOG.info("CCoder 转写视图：忽略未知的桥消息 ${obj.str("op") ?: message.take(120)}")
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

        /** 前端 ready 的等待上限，超时就把页面状态写进日志。 */
        private const val READY_TIMEOUT_MS = 8000

        private val LOG = Logger.getInstance(ClaudeTranscriptView::class.java)
    }
}
