package com.ccoder.text

import java.io.StringReader
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

/**
 * 取词门面 —— 全插件唯一的读文案入口：`CcoderText.text("session.list.delete")`。
 *
 * ## 语言从哪儿来
 *
 * 设置里的「界面语言」三档（跟随 IDE / 中文 / English）。设置层通过 [setOverride]
 * 把选择**推**进来，方向是 `settings → text` **单向**：这一层不许反过来依赖
 * `settings`，否则纯 JVM 单测里一调 `text()` 就得把平台服务拉起来
 * （同一个理由让 `StatusCards` / `MessageRenderer` 那一族一样不带 `Logger`）。
 *
 * 因此「设置里的选择」要生效，得有人先把服务读起来。平台服务是**懒**的，
 * 而最早取文案的两处（右键菜单、状态栏）早于工具窗口 —— 所以 `UiLanguageSettings`
 * 在 `plugin.xml` 里带 `preload="true"` 注册（见那个类顶上的注释）。
 *
 * ## 为什么可以缓存
 *
 * 语言在**进程内是稳定的**：改设置要等**重启 IDE**（或新开一个标签）才生效 ——
 * 「关掉再打开工具窗口」不算，平台不会因此重建面板（设计稿 §三，沿用
 * 「改动即时保存，生效时机另说」的口径，不做热切换）。
 * 而 `refreshStatusCards` 每个 token 都会经过这里，不能每次都读盘。
 *
 * 缓存**按 Locale 走**（不是"存一份就完了"）：[tag] 与 [text] 因此永远同一门语言 ——
 * 两份缓存各判各的，就会出现"sidecar 拿到 zh、界面显示 en"这种自相矛盾。
 *
 * ## 缺键返回**键本身**
 *
 * 界面上显示 `settings.page.general` 是一眼能报的 bug；静默回退英文是在撒谎。
 * 配合 `TextKeysTest` 的源码扫描（引用的键必须存在、词表里的键必须有人引用），
 * 缺键正常到不了用户眼前。
 */
object CcoderText {

    /** 设置层写进来的选择；`null` = 跟随 IDE。 */
    @Volatile
    private var override: Locale? = null

    @Volatile
    private var cachedLocale: Locale? = null

    @Volatile
    private var cachedBundle: ResourceBundle? = null

    fun setOverride(locale: Locale?) {
        override = locale
    }

    fun locale(): Locale = resolve()

    /** 唯一跨到 web / sidecar 的东西：`"zh"` 或 `"en"`。 */
    fun tag(): String = if (resolve().language == "zh") "zh" else "en"

    fun text(key: String, vararg args: Any): String {
        val bundle = bundle()
        if (!TextCatalog.has(bundle, key)) return key
        return TextCatalog.render(bundle, key, args)
    }

    /** 键不存在时给 `null` —— 给"这条文案有没有"这类判断用。 */
    fun textOrNull(key: String): String? {
        val bundle = bundle()
        return if (TextCatalog.has(bundle, key)) TextCatalog.render(bundle, key, emptyArray()) else null
    }

    fun has(key: String): Boolean = TextCatalog.has(bundle(), key)

    private fun resolve(): Locale = override ?: IdeLocale.detect()

    /**
     * 当前语言对应的词表。
     *
     * 语言没变就用缓存那份；变了（只有测试会这么干）就重新取 —— 两个线程同时取
     * 也没关系，`ResourceBundle.getBundle` 自己带缓存且线程安全。
     *
     * ## 取词表**不许抛**（2026-09-20）
     *
     * 这一句在启动路径上会被走到：JCEF 注入的 `tag()`、sidecar 启动、面板构造。
     * 词表读不出来最多该让界面显示键（[text] 本来就是这个行为），**不该把那条路炸掉** ——
     * 那天的教训正是"一个文案层的小问题升级成了界面打不开"。所以这里吞掉异常并退回
     * 空词表（缓存住，避免每次调用都重试）。
     */
    private fun bundle(): ResourceBundle {
        val locale = resolve()
        cachedBundle?.let { if (cachedLocale == locale) return it }

        val loaded = runCatching { TextCatalog.bundleFor(locale) }.getOrNull() ?: EMPTY_BUNDLE
        cachedLocale = locale
        cachedBundle = loaded
        return loaded
    }

    /** 空词表：每个键都"缺"，于是 [text] 原样返回键。 */
    private val EMPTY_BUNDLE: ResourceBundle = PropertyResourceBundle(StringReader(""))
}
