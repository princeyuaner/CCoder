package com.ccoder.update

import com.intellij.ide.util.PropertiesComponent
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 插件的版本号与更新日志 —— 两样都从**我们自己的插件描述符**里读。
 *
 * ## 为什么是这条路径（2026-09-17 实测）
 *
 * 平台那条路（`PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))`）在**测试 JVM 里
 * 返回 null**（沙箱里插件装好了也照样 null），而"读得到读不到"不该成为弹不弹的前提。
 * 自己的描述符就在自己的 classpath 上：
 *
 * ```
 * jar:file:…/plugins-test/CCoder/lib/CCoder-0.2.19.jar!/META-INF/plugin.xml
 * ```
 *
 * 而且它和市场页、插件管理器里显示的 change-notes 是**同一份**文件 —— 写一次，
 * 三处一致（本仓为"同一份知识的两份副本"付过代价）。
 *
 * `version` 是构建期由 `intellijPlatform.pluginConfiguration.version`（来自
 * `gradle.properties` 的 `pluginVersion`）注入的：源文件里**没有** `<version>` 这一行，
 * 所以 dev 模式（Run Plugin，没走打包）读不到版本 —— 那时不弹，符合预期。
 */
internal data class PluginChangelog(val version: String, val notesHtml: String)

/** 插件 id。与 plugin.xml 的 `<id>` 必须逐字相同。 */
internal const val PLUGIN_ID = "com.ccoder.claudecode"

/** 自己的描述符在 classpath 上的位置。 */
internal const val DESCRIPTOR_RESOURCE = "/META-INF/plugin.xml"

/**
 * 解析一份插件描述符，取出 id / version / change-notes。
 *
 * **id 对不上就返回 null**：`/META-INF/plugin.xml` 这个路径不止我们有（平台自带的插件、
 * 别的第三方插件都可能有），classloader 万一先撞上别人的，就会弹**别人的**更新日志。
 * 一行校验把这条彻底关掉。
 */
internal fun parsePluginDescriptor(stream: InputStream, expectedId: String = PLUGIN_ID): PluginChangelog? {
    val root = runCatching {
        // 关掉 DTD 与外部实体：这是自己的文件、本来也没有 DTD，但不能留一个
        // "以后有人往描述符里加 DOCTYPE 就变成 XXE"的口子
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newDocumentBuilder().parse(stream).documentElement
    }.getOrNull() ?: return null

    // 只看**直接子节点**：description / change-notes 里是 CDATA 包着的 HTML，
    // 按标签名全局找的话，将来有人在描述里放一个 `<id>` 就会被误取
    val children = root.childNodes
    var id: String? = null
    var version: String? = null
    var notes: String? = null
    for (i in 0 until children.length) {
        val node = children.item(i)
        when (node.nodeName) {
            "id" -> id = node.textContent?.trim()
            "version" -> version = node.textContent?.trim()
            "change-notes" -> notes = node.textContent?.trim()
        }
    }

    if (id != expectedId) return null
    if (version.isNullOrEmpty()) return null
    return PluginChangelog(version = version, notesHtml = notes.orEmpty())
}

/**
 * 本进程内只读一次。
 *
 * 描述符是**静止的**文件（插件运行期间不会变），而这个检查会被"面板每次上屏"调到 ——
 * 每切一次标签就解析一遍 XML 不值当。测试走 `load` 参数，不碰这个缓存。
 */
private val ownChangelogOnce: PluginChangelog? by lazy { loadOwnChangelog() }

/** 读自己的描述符。读不到（dev 模式、打包异常）返回 null —— 调用方据此不弹。 */
internal fun loadOwnChangelog(): PluginChangelog? =
    runCatching {
        // 拿资源的类必须是**本插件自己的**（它才由插件的 classloader 装载），
        // 用 PluginChangelog 当锚点就够
        PluginChangelog::class.java.getResourceAsStream(DESCRIPTOR_RESOURCE)?.use { parsePluginDescriptor(it) }
    }.getOrNull()

/**
 * 这个版本的更新日志该不该弹。
 *
 * 几个分支的取值理由都写在设计稿 §3.3；这里只说两条最容易改错的：
 *
 * - `stored == null` -> **弹**（用户拍板："老用户也弹"）。第一次跑这套逻辑的人
 *   既可能是老用户（原来没记过）也可能是新装 —— 后者多看一次，接受。
 * - 版本比较**必须语义化**：字符串比较下 `0.2.10 < 0.2.9`，而这两个版本号
 *   本仓都真实存在过。
 */
internal enum class ChangelogDecision {
    Show,

    /** 本版已经看过了。 */
    SameVersion,

    /** 比看过的还旧（回滚了）—— 不再弹一次。 */
    Older,

    /** 读不到描述符 / 没有版本号 / 文案还是占位。 */
    NoContent,
}

internal fun changelogDecision(stored: String?, changelog: PluginChangelog?): ChangelogDecision {
    if (changelog == null || isPlaceholder(changelog.notesHtml)) return ChangelogDecision.NoContent
    if (stored == null) return ChangelogDecision.Show
    return when (compareVersions(changelog.version, stored)) {
        0 -> ChangelogDecision.SameVersion
        in 1..Int.MAX_VALUE -> ChangelogDecision.Show
        else -> ChangelogDecision.Older
    }
}

/**
 * 文案还是占位符吗。
 *
 * 0.2.19 的 change-notes 眼下就是 `TODO: this version's changes…`（见发布设计稿 §八）——
 * 没有这道闸，这一版装上去就会弹出一个写着 TODO 的框。写好之后它自动开始弹，
 * 不用改代码。
 */
internal fun isPlaceholder(notesHtml: String): Boolean {
    val text = notesHtml.trim()
    if (text.isEmpty()) return true
    return text.contains("TODO") || text.contains("待写")
}

/**
 * 版本号比较：`a` 比 `b` 新返回正数，旧返回负数，相同返回 0。
 *
 * 按 `.` 切段：数字段按**数值**比（`10 > 9`），非数字段（`dev`/`EAP` 这类后缀）
 * 按字符串比，缺的段当 0（`0.2` == `0.2.0`）。全是垃圾（两个都切不出段）时等于。
 */
internal fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.')
    val pb = b.trim().split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val sa = pa.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "0"
        val sb = pb.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: "0"
        val na = sa.toIntOrNull()
        val nb = sb.toIntOrNull()
        val cmp = if (na != null && nb != null) na.compareTo(nb) else sa.compareTo(sb)
        if (cmp != 0) return cmp
    }
    return 0
}

/**
 * "上次给这个用户看过哪个版本"存哪儿。
 *
 * 抽成接口是为了**可测**：应用级 `PropertiesComponent` 在无头单测里拿不到
 * （会 NPE，本仓既有注释记过两次），而这段逻辑的边界全在"存了什么、现在是什么"上。
 */
internal interface ChangelogStore {
    fun shownVersion(): String?
    fun markShown(version: String)
}

/** 生产实现：应用级 `PropertiesComponent`（跨项目、跨重启）。 */
internal class PropertiesChangelogStore : ChangelogStore {
    override fun shownVersion(): String? = PropertiesComponent.getInstance().getValue(SHOWN_VERSION_KEY)

    override fun markShown(version: String) {
        PropertiesComponent.getInstance().setValue(SHOWN_VERSION_KEY, version)
    }

    private companion object {
        const val SHOWN_VERSION_KEY = "ccoder.changelog.shownVersion"
    }
}

/**
 * 该弹就弹一次，然后记下这个版本。返回判断结果（调用方拿去打日志）。
 *
 * **先记再弹**：反过来的代价是"弹框还开着的时候又一次上屏 -> 再弹一个"。
 * 宁可对话框万一构造失败就再也不弹了，也不要每次开窗都弹 —— 用户要的是一次。
 */
internal fun maybeShowChangelog(
    store: ChangelogStore,
    load: () -> PluginChangelog? = { ownChangelogOnce },
    show: (PluginChangelog) -> Unit,
): ChangelogDecision {
    val changelog = load()
    val decision = changelogDecision(store.shownVersion(), changelog)
    if (decision == ChangelogDecision.Show) {
        store.markShown(changelog!!.version)
        show(changelog)
    }
    return decision
}
