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
import javax.swing.JPanel

/**
 * 转写区。JCEF 可用时渲染 React 界面，不可用时显示明确的提示。
 *
 * 降级不重建原生渲染：那会让代码里长期并存两套渲染实现，维护面翻倍
 * （设计文档 §11 记录了这个取舍）。JCEF 不可用时给提示而非空白面板。
 *
 * ## 「起不来」有两种，2026-09-23 起两种都兜得住
 *
 * 一种是**没启用**（[JBCefApp.isSupported] 为假）：那是设置，提示里直接指路。
 * 另一种是**启用着、但这一把建不起来** —— 平台的 `RemoteMessageRouterImpl.create`
 * 对着 `RpcContext.execObj` 取回来的 `RObject` 直接读 `.isNull`，而通道断着的时候
 * `execObj` 返回的就是 null → NPE。以前这个异常从**构造器**里一路掀到 EDT：
 * 用户连点五次「新建会话」报了五次，每次都是**整个新会话没建起来**（比"转写区降级"
 * 严重得多）。现在整段起机都收在 [startOrDegrade] 里，失败就换成降级页 + 一个「重试」。
 *
 * ## 同一条通道的**第二副面孔：已经开着的那一格会悄悄卡死**（2026-09-23 查清）
 *
 * 通道断掉之后，每一次 `executeJavaScript`（也就是我们每帧转写的推送）走的是
 * `RpcExecutor.exec`：`if (myTransport == null) return;` —— **平台自己丢掉，不抛错**。
 * 于是输出区停在那一帧、**日志里一个字都没有**（断线本身平台只用 `CefLog.Error`
 * 写进一个被关掉的通道：`JCEF logging: LOGSEVERITY_DISABLE`，落盘是个空文件）。
 * 用户那次"一开始好好的、过一会儿输出区卡死、再输入也不显示"就是这么来的。
 *
 * **没做自动检测**（2026-09-23 用户决定先不做）。真要做，起点在这儿：桥是双向的，
 * `window.ccoder.send({op:'pong'})` 这条回信现成 —— 跟 [installReadyWatchdog] 探页面
 * 状态那条 `pageState` 一模一样（不用动前端）。发送那一刻发一次、几秒内没回信，
 * 就把这一格换成 [TranscriptFallback]（「重试」接的就是 [startOrDegrade]）。
 * 两个已知约束：定期查要养一个心跳定时器；「重试」之后那一格是**空**的
 * （转写历史在 Kotlin 侧没留全量），过去的对话得靠「历史会话」重新打开，
 * 或者顺手把"读存档重放"一起做了。
 */
class ClaudeTranscriptView(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private var browser: JBCefBrowser? = null
    private var jsQuery: JBCefJSQuery? = null
    private var pump: TranscriptPump? = null

    /** React 挂载完成前收到的操作要暂存，否则会丢。 */
    private val beforeReady = mutableListOf<TranscriptOp>()

    @Volatile
    private var ready = false

    init {
        startOrDegrade()
    }

    /**
     * 起一次：JCEF 起得来就接上线，起不来就换成降级页。
     *
     * **整段都不许往外抛**（见类的注释）。建零件那一半在 [startTranscriptCef] 里兜着；
     * 接线那一半（加 load handler、载页面）走的是**同一条 RPC 通道**，所以这里再兜一层，
     * 失败的样子与建不起来一样：收干净 + 降级页。
     */
    private fun startOrDegrade() {
        // 上一次留下的先收干净：重试会再走一遍这里
        teardown()
        removeAll()

        if (!JBCefApp.isSupported()) {
            LOG.warn("CCoder 转写视图：当前 IDE 未启用 JCEF，转写区降级为提示文本")
            showFallback(TranscriptFallback.Reason.NoJcef)
            return
        }

        if (startTranscriptCef(wire = ::wire) == null) {
            // 半成品（浏览器建出来了、线没接完这种）别留着：CEF 那边会多一个没人放的页
            teardown()
            showFallback(TranscriptFallback.Reason.StartFailed)
            return
        }

        revalidate()
        repaint()
    }

    /**
     * 给建好的零件接线：桥、导航闸、页面、看门狗、主题订阅。
     *
     * **字段先落再动手**：中途抛了，[teardown] 才收得到已经建出来的东西。
     */
    private fun wire(cef: TranscriptCef) {
        val b = cef.browser
        browser = b
        jsQuery = cef.query
        pump = TranscriptPump(exec = { json -> pushToJs(json) })

        cef.query.addHandler { message: String ->
            handleFromJs(message)
            null
        }

        add(b.component, BorderLayout.CENTER)

        injectBridge(b, cef.query)
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

    /** 摆降级页。「重试」再走一遍 [startOrDegrade] —— 平台那边可能已经自己好了。 */
    private fun showFallback(reason: TranscriptFallback.Reason) {
        add(TranscriptFallback(reason) { startOrDegrade() }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * 收掉已经建起来的那套（重试 / 接线失败 / 释放都走这里）。
     *
     * `ready` 一并归零：它说的是"当前这个页面回话了"，换了页面就得回到没回话那一态，
     * 否则重试之后收到的转写会直推给一个还不存在的桥。
     */
    private fun teardown() {
        pump?.dispose()
        pump = null
        jsQuery?.let { Disposer.dispose(it) }
        jsQuery = null
        browser?.let { Disposer.dispose(it) }
        browser = null
        ready = false
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
        teardown()
        beforeReady.clear()
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        const val DEV_SERVER_PROPERTY = "ccoder.devServer"

        /** 前端 ready 的等待上限，超时就把页面状态写进日志。 */
        private const val READY_TIMEOUT_MS = 8000
    }
}

/** JCEF 那一套零件：浏览器 + 桥。建它们的那条路会抛，见 [startTranscriptCef]。 */
internal class TranscriptCef(val browser: JBCefBrowser, val query: JBCefJSQuery)

/**
 * 建零件 + 接线，**失败返回 null、绝不往外抛**。
 *
 * 为什么必须兜住：`JBCefJSQuery.create` 一路走到平台的 `RemoteMessageRouterImpl.create`，
 * 那里对着 RPC 通道（`RpcContext.execObj`）取回来的 `RObject` **直接读 `.isNull`** ——
 * 通道断着（JCEF 服务器断过线）时 `execObj` 返回的就是 null，于是 NPE。那条通道健不健康
 * **平台没有 API 可问**，也不该由转写区去猜：能做的只有"抛了别掀到 EDT"（见类的注释）。
 *
 * [create] 是给用例的注入点（同 [TranscriptPump] 的 `exec`）：测试环境里 JCEF 本来就起不来，
 * 不注入就只跑得到"真抛出"那一半 —— 而这条要钉的恰恰是"抛了也得兜住"。
 */
internal fun startTranscriptCef(
    create: () -> TranscriptCef = ::newTranscriptCef,
    wire: (TranscriptCef) -> Unit,
): TranscriptCef? = try {
    create().also(wire)
} catch (t: Throwable) {
    LOG.warn("CCoder 转写视图：JCEF 起不来（${t.javaClass.simpleName}），转写区降级", t)
    null
}

/**
 * 真去建那一刻。
 *
 * "浏览器建出来了、桥没装上"这一截**自己收尾**：不 dispose 的话，CEF 那边会留下一个
 * 没人用也没人放的页 —— [startTranscriptCef] 的 catch 只看得到异常，看不到这个浏览器。
 */
private fun newTranscriptCef(): TranscriptCef {
    val browser = JBCefBrowser()
    val query = try {
        JBCefJSQuery.create(browser as JBCefBrowserBase)
    } catch (t: Throwable) {
        // 收尾别把原来那条错盖掉：dispose 走的也是同一条（可能正断着的）通道
        runCatching { Disposer.dispose(browser) }
            .onFailure { LOG.warn("CCoder 转写视图：没建成的那个浏览器没放掉", it) }
        throw t
    }
    return TranscriptCef(browser, query)
}

/** 文件级：类里那几处与上面两个自由函数共用同一个类别名（日志里的 `#com.ccoder.ui.ClaudeTranscriptView`）。 */
private val LOG = Logger.getInstance(ClaudeTranscriptView::class.java)
