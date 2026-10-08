package com.ccoder.settings

import com.ccoder.sidecar.SkillCommand
import com.ccoder.sidecar.SkillUsageLine
import com.ccoder.text.CcoderText
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * 把 CLI 给的清单、上下文账单、三层设置、磁盘上的目录**拼成页面上的行** —— 纯函数。
 *
 * ## 为什么非要拼
 *
 * 三条路各缺一块（都是 2026-09-30 探针量出来的）：
 *
 *  - `reloadSkills()` 给显示名与描述，**不给来源、不给 token**；
 *  - `getContextUsage()` 给来源与 token，显示名与上一条同源；
 *  - 三层设置给"谁被改过"，但**只认设置键**（目录名），而前两条只吐显示名；
 *  - 而**关掉的技能压根不在清单里**（`totalSkills` 一起少一条）—— 只拼前三条的话，
 *    用户关掉一条就再也看不见它、也就开不回来。
 *
 * 所以最后那条"设置里记着的名字"必须补出来，而"显示名 → 设置键"这一跳只能靠
 * 磁盘上的目录名（实测：写显示名给 CLI 的写手，它照收，但那条覆盖永远不命中）。
 */

/**
 * 技能的来源。**取值是 CLI 的原话**（[wire]），标签才是界面上的字。
 *
 * 真机上量到四种；第五种 [OTHER] 是"CLI 将来加一档"的兜底 —— 认不出也照列，
 * 归到"其他"里，别把它藏掉。
 */
enum class SkillSource(val wire: String?, private val labelKey: String) {
    MINE("userSettings", "settings.skills.source.mine"),
    PROJECT("projectSettings", "settings.skills.source.project"),
    PLUGIN("plugin", "settings.skills.source.plugin"),
    BUILTIN("built-in", "settings.skills.source.builtin"),
    OTHER(null, "settings.skills.source.other"),
    ;

    val label: String get() = CcoderText.text(labelKey)

    /** 分组标题右边那句灰字：这一组的东西从哪来、能不能改。 */
    val hint: String
        get() = CcoderText.text(
            when (this) {
                MINE -> "settings.skills.group.mineHint"
                PROJECT -> "settings.skills.group.projectHint"
                PLUGIN -> "settings.skills.group.pluginHint"
                BUILTIN -> "settings.skills.group.builtinHint"
                OTHER -> "settings.skills.group.otherHint"
            },
        )

    companion object {
        fun ofWire(raw: String?): SkillSource =
            entries.firstOrNull { it.wire != null && it.wire == raw } ?: OTHER
    }
}

/**
 * 磁盘上扫到的一条技能目录。
 *
 * 两个用途：拿**设置键**（[dirName]）与拿**能打开的那个文件**（[path]）。
 * 内置技能没有目录（它们在 `claude.exe` 里），插件技能多半在 `~/.claude/plugins/…`
 * 深处 —— 这两种扫不到，于是它们的"打开 SKILL.md"这一项就该**不出现**（白名单式，
 * 不给点了没反应的菜单项）。
 */
data class ScannedSkill(
    val dirName: String,
    val displayName: String,
    val path: Path,
    val inProject: Boolean,
)

/**
 * 页面上的一行。
 *
 * [state] 为 null = 设置文件里那个值我们不认识（第五档、或手写错了）——
 * 那时 [stateRaw] 原样带着，界面显示原文而不是自作主张当"开"。
 *
 * [listed] 为 false = **这一行是从设置里补出来的**（CLI 这次的清单里没有它）。
 * 界面要据此换一句话说（"已关闭"而不是继续显示描述）—— 它现在没有描述也没有 token，
 * 因为 CLI 那边已经没有这条了。
 */
data class SkillRow(
    val key: String,
    val name: String,
    val description: String?,
    val source: SkillSource,
    val tokens: Long?,
    val state: SkillState?,
    val stateRaw: String?,
    val locked: Boolean,
    val path: Path?,
    val listed: Boolean,
) {
    /** 开关画在开还是关。认不出的档位**当开**（它在清单里就是开着）。 */
    val isOn: Boolean get() = state != SkillState.OFF
}

/**
 * 拼行。
 *
 * @param overrides 三层合并后的"设置键 → 生效值"（见 `mergedSkillOverrides`）
 * @param scanned [scanSkillDirs] 扫出来的目录
 */
internal fun skillRowsOf(
    commands: List<SkillCommand>,
    usage: List<SkillUsageLine>,
    overrides: Map<String, String>,
    scanned: List<ScannedSkill>,
): List<SkillRow> {
    // 显示名 → 目录。重名时取第一条：目录名才是设置键，而两个同名技能本来就少见
    val byDisplay = scanned.groupBy { it.displayName }.mapValues { it.value.first() }
    val commandByName = commands.associateBy { it.name }
    val usageByName = usage.associateBy { it.name }

    // 两条路的名字**取并集**：清单给描述、账单给来源与 token，缺哪条都不该整行消失
    val names = LinkedHashSet<String>().apply {
        commands.forEach { add(it.name) }
        usage.forEach { add(it.name) }
    }

    val rows = mutableListOf<SkillRow>()
    val usedKeys = mutableSetOf<String>()

    for (name in names) {
        val scan = byDisplay[name]
        val line = usageByName[name]
        val command = commandByName[name]
        val key = scan?.dirName ?: name
        usedKeys += key

        val fromUsage = SkillSource.ofWire(line?.source)
        val source = when {
            fromUsage != SkillSource.OTHER -> fromUsage
            scan != null -> if (scan.inProject) SkillSource.PROJECT else SkillSource.MINE
            else -> SkillSource.OTHER
        }
        val raw = overrides[key]

        rows += SkillRow(
            key = key,
            name = name,
            description = command?.description?.takeIf { it.isNotBlank() },
            source = source,
            tokens = line?.tokens,
            state = SkillState.ofWire(raw),
            stateRaw = raw,
            // 插件来源的技能**本地覆盖不动**（CLI 对 plugin 直接判 "on"，实测）
            locked = source == SkillSource.PLUGIN,
            path = scan?.path,
            listed = true,
        )
    }

    // 设置里记着、这次清单里却没有的：关掉的，或者已经卸载了还留着的
    for ((key, raw) in overrides) {
        if (key in usedKeys) continue
        val scan = scanned.firstOrNull { it.dirName == key }
        val pluginish = key.contains(':')
        rows += SkillRow(
            key = key,
            name = scan?.displayName ?: key,
            description = null,
            source = when {
                scan != null -> if (scan.inProject) SkillSource.PROJECT else SkillSource.MINE
                pluginish -> SkillSource.PLUGIN
                else -> SkillSource.OTHER
            },
            tokens = null,
            state = SkillState.ofWire(raw),
            stateRaw = raw,
            locked = pluginish,
            path = scan?.path,
            listed = false,
        )
    }

    return rows.sortedWith(compareBy({ it.source.ordinal }, { it.name.lowercase() }))
}

/**
 * 按来源分组，**空组不出现**，组内顺序照 [skillRowsOf] 排好的那份。
 *
 * 分组只是显示手段，别让页面自己写一遍 when —— 用例要能直接断言"空的组不出现在页面上"。
 */
internal fun skillGroupsOf(rows: List<SkillRow>): List<Pair<SkillSource, List<SkillRow>>> =
    SkillSource.entries.mapNotNull { source ->
        val mine = rows.filter { it.source == source }
        if (mine.isEmpty()) null else source to mine
    }

/**
 * 「⋯」菜单里该有哪几项 —— **白名单**：只有真做得到的才出现。
 *
 * 三条规矩都是量出来的，不是猜的：
 *  - **插件来源的技能没有状态项**：写 off 也没用（实测回执 `changed:true`、新会话照旧），
 *    给一颗必然无效的菜单项比不给更糟；
 *  - **没有文件就没有「打开 SKILL.md」**：内置技能在 `claude.exe` 里、插件技能在
 *    `~/.claude/plugins/…` 深处，两处都扫不到 —— 点了没反应的那一项不该画；
 *  - **关掉的（不在清单里）照样有状态项**：那正是把它开回来的路。
 */
internal fun skillMenuActions(row: SkillRow): List<SkillMenuAction> = buildList {
    if (!row.locked) {
        SkillState.entries.forEach { state ->
            if (state != row.state) add(SkillMenuAction(state.name, state.label, state.hint))
        }
    }
    if (row.path != null) {
        add(SkillMenuAction("open", CcoderText.text("settings.skills.menu.open")))
    }
    if (row.source == SkillSource.PLUGIN) {
        // 插件名要从 `plugin:skill` 这种键里剥出来（MCP 技能的键是 `server:skill`，
        // 那一类既不是插件也不是文件，两项都不会出现）
        pluginNameOf(row.key)?.let { plugin ->
            add(SkillMenuAction("plugin-disable:$plugin", CcoderText.text("settings.skills.menu.disablePlugin", plugin)))
            add(SkillMenuAction("plugin-update:$plugin", CcoderText.text("settings.skills.menu.updatePlugin", plugin)))
        }
    }
}

/** `code-review:code-review` → `code-review`；认不出就 null。 */
internal fun pluginNameOf(key: String): String? =
    key.substringBefore(':', missingDelimiterValue = "").takeIf { it.isNotBlank() }

/**
 * 菜单里的一项。
 *
 * [id] 是给页面对回调用的（`"OFF"` / `"NAME_ONLY"` / `"open"` / `"plugin-disable:名字"`），
 * [label] 是人看的。分成两个字段，是因为状态那几项的 id 必须是**枚举名**：
 * 页面拿它直接 `SkillState.valueOf`，拼错就是崩溃而不是"菜单点不动"。
 *
 * [hint] 是右侧那句灰字（"省描述 token"）。四档里有三档光看名字猜不出区别 ——
 * 那不是装饰，是这一页唯一的"教育性"内容。
 */
internal data class SkillMenuAction(val id: String, val label: String, val hint: String? = null)

/** 四档各自那句解释。菜单里挂在右边。 */
internal val SkillState.hint: String
    get() = when (this) {
        SkillState.ON -> CcoderText.text("settings.skills.state.onHint")
        SkillState.NAME_ONLY -> CcoderText.text("settings.skills.state.nameOnlyHint")
        SkillState.USER_ONLY -> CcoderText.text("settings.skills.state.userOnlyHint")
        SkillState.OFF -> CcoderText.text("settings.skills.state.offHint")
    }

/**
 * 扫两处技能目录：项目的 `.claude/skills/` 与用户的 `~/.claude/skills/`。
 *
 * **只扫这一层**：再深就不是这个约定（`<dir>/SKILL.md`）。内置与插件技能扫不到 ——
 * 那正是它们没有"打开文件"的原因。
 *
 * 读不动（没权限、正好被删）就跳过那一条，不抛：这是**补充信息**，不是主数据。
 */
internal fun scanSkillDirs(base: Path?, home: Path): List<ScannedSkill> {
    val out = mutableListOf<ScannedSkill>()
    base?.let { out += scanOne(it.resolve(".claude").resolve("skills"), inProject = true) }
    out += scanOne(home.resolve(".claude").resolve("skills"), inProject = false)
    return out
}

private fun scanOne(dir: Path, inProject: Boolean): List<ScannedSkill> {
    // newDirectoryStream 而不是 Files.list：后者给的是 Stream，收尾要靠调用方 close，
    // 而这台机器上 Windows 的句柄一多就 EPERM（探针那两次删不掉目录就是它）
    val children = runCatching {
        Files.newDirectoryStream(dir).use { stream -> stream.filter { Files.isDirectory(it) } }
    }.getOrNull() ?: return emptyList()

    return children.mapNotNull { child ->
        val md = child.resolve("SKILL.md")
        if (!Files.isRegularFile(md)) return@mapNotNull null
        val text = runCatching { Files.readString(md) }.getOrNull() ?: return@mapNotNull null
        val dirName = child.name
        ScannedSkill(
            dirName = dirName,
            // 没有 frontmatter 就用目录名 —— CLI 也是这么办的（3/7 个用户技能就是这种）
            displayName = skillMdNameOf(text) ?: dirName,
            path = md,
            inProject = inProject,
        )
    }
}
