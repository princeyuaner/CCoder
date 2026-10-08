package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * 技能的"四档"与它落在哪几个文件里 —— **纯函数**，单测直接打。
 *
 * ## 这一整套不是我们的发明
 *
 * 四档、`skillOverrides` 这个键、以及"更严的赢"的合并规矩，都是 CLI 自己的
 * （`claude.exe` 内嵌 JS 里的 zod schema + `/skills` 菜单；2026-09-30 读的）。
 * 我们只做两件事：**读**三层设置文件算出每条现在是什么状态；**写**交给 CLI 自己的
 * 写手（见 [skillEditArgs]），不自己拼 JSON 往用户文件里塞。
 *
 * ## 为什么写不自己写
 *
 * `claude edit-skill-overrides` 是 VS Code 扩展用的同一条路：它知道该写哪一份、
 * 该怎么合并、怎么写才不会被别的进程打断。我们照着 `withMcpServers` 那样自己写
 * 也能写对，但那是**两套写手维护同一份文件**的开始 —— 而这一页只需要一个键。
 */

/** 用户级设置。**只读不写**：那一份里有密钥与端点（见 `HooksConfig` 的说明）。 */
internal fun userSettingsPath(home: Path): Path =
    home.resolve(".claude").resolve("settings.json")

/**
 * 项目本地设置（`.claude/settings.local.json`）。
 *
 * **CLI 的写手写的就是这一份**（2026-09-30 实测：在临时项目里跑
 * `edit-skill-overrides`，它落在 `<cwd>/.claude/settings.local.json`）。
 * 通常这份是 gitignore 的，所以"我关掉了某条技能"不会跟着进仓库 —— 正好。
 */
internal fun projectLocalSettingsPath(projectBase: Path): Path =
    projectBase.resolve(".claude").resolve("settings.local.json")

/**
 * 四档状态。**线上取值就是这四个字符串** —— 改一个字就是改协议，
 * 所以它们与词表里那四个标签是两回事（标签可以翻译，这个不能）。
 */
enum class SkillState(val wire: String, private val labelKey: String) {
    ON("on", "settings.skills.state.on"),
    NAME_ONLY("name-only", "settings.skills.state.nameOnly"),
    USER_ONLY("user-invocable-only", "settings.skills.state.userOnly"),
    OFF("off", "settings.skills.state.off"),
    ;

    /** 界面上那一行的词。 */
    val label: String get() = CcoderText.text(labelKey)

    companion object {
        /** 认不出就给 null（文件是手可改的，值可能是第五档）。 */
        fun ofWire(raw: String?): SkillState? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * 读一份设置文件里的 `skillOverrides`。
 *
 * **值原样收着**（不在这里转枚举）：认不出的档位是文件里的既成事实，
 * 转成 ON 会让界面撒谎说"它是开着的"。
 */
internal fun skillOverridesOf(root: JsonObject): Map<String, String> {
    val block = root.getAsJsonObject("skillOverrides") ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    for ((key, value) in block.entrySet()) {
        val text = value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: continue
        if (key.isNotBlank()) out[key] = text
    }
    return out
}

/**
 * 三层里**生效**的那一档。
 *
 * 优先级照 CLI 自己的原话（`get_skills_dialog` 那个字段的说明）：
 * "the locked value, else the local setting, else the project or user setting, else 'on'"。
 * 被锁的那一档（policy / flag / 插件）**我们这一侧看不见**，也改不了 ——
 * 所以这里只答"本地这三层谁说了算"，锁定由调用方另外标（插件来源见 `locked`）。
 *
 * @return 没被任何一层覆盖过就是 null（= 默认的 ON）
 */
internal fun effectiveSkillState(
    key: String,
    local: Map<String, String>,
    project: Map<String, String>,
    user: Map<String, String>,
): String? = local[key] ?: project[key] ?: user[key]

/** 三层合成一份"键 → 生效值"。给 [skillRowsOf] 用；没覆盖过的键不出现。 */
internal fun mergedSkillOverrides(
    local: Map<String, String>,
    project: Map<String, String>,
    user: Map<String, String>,
): Map<String, String> {
    val keys = LinkedHashSet<String>().apply {
        addAll(local.keys); addAll(project.keys); addAll(user.keys)
    }
    return keys.associateWith { effectiveSkillState(it, local, project, user) ?: SkillState.ON.wire }
}

/**
 * 喂给 CLI 写手的那一行 JSON（stdin）。
 *
 * **`name` 是设置键，不是显示名** —— 2026-09-30 实测的坑：写手**什么都不验**，
 * 给它 "Debug Issue" 它照写（回执还回 `changed:true`），而那条覆盖**永远不会命中**。
 * 真键是目录名。谁保证喂进来的是键，见 `skillRowsOf`。
 */
internal fun skillEditInput(key: String, state: SkillState): String =
    JsonObject().apply {
        addProperty("name", key)
        addProperty("state", state.wire)
    }.toString()

/** CLI 写手的调用形状。`--json` 加不加都一样（子命令的 requiredOption），写上更清楚。 */
internal fun skillEditArgs(claudePath: String): List<String> =
    listOf(claudePath, "edit-skill-overrides", "--json")

/**
 * 写手回执判成功。
 *
 * 它的输出**不是稳定的 JSON**：成功是 `{"ok":true,…}`，失败可能是
 * `Not a skill state edit (name: Invalid input).` 这种纯文本（实测）。
 * 所以这里只认"能解析出 ok:true"，其余一律算失败并把原文递回去。
 */
internal fun skillEditSucceeded(stdout: String): Boolean {
    val line = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") } ?: return false
    val obj = runCatching { com.google.gson.JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return false
    return obj.get("ok")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
}

// ---- 磁盘：SKILL.md 与它的 frontmatter ----

/**
 * 从 `SKILL.md` 文本里取 frontmatter 的 `name`。
 *
 * **没有 frontmatter 就是 null**，不是空串：这台机器上 7 个用户技能里有 3 个
 * （caveman / lazy-senior-dev / …）根本没有 frontmatter，CLI 那时拿目录名当显示名。
 * 返回空串会让调用方以为"名字是空的"。
 */
internal fun skillMdNameOf(text: String): String? {
    val lines = text.lineSequence().toList()
    if (lines.firstOrNull()?.trim() != "---") return null
    // **先找收尾那条 `---`**：没有收尾就不是 frontmatter，正文里的 `name:` 不算数
    val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null
    for (i in 1 until end) {
        val line = lines[i].trim()
        if (line.startsWith("name:")) return line.removePrefix("name:").trim().takeIf { it.isNotEmpty() }
    }
    return null
}
