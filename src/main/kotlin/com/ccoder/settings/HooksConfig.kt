package com.ccoder.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * `.claude/settings.json`（**项目级**）。
 *
 * 与用户级那份 `~/.claude/settings.json` 不是一回事：仓库此前明确"CCoder 不写
 * settings.json"，说的是用户级那份（含密钥与端点，碰了会静默改掉认证行为）。
 * 项目级这份可提交、可审查，而且面板只碰 `hooks` 一个键 —— 但这个"转向"
 * 在设计稿第三章里显式记着，不是悄悄做的。
 */
internal fun projectSettingsPath(projectBase: Path): Path =
    projectBase.resolve(".claude").resolve("settings.json")

/**
 * 面板暴露的 hook 事件（33 个里挑常用的这 7 个）。
 *
 * 其余 26 个**明确不做** —— 但它们在文件里出现时**原样保留**，
 * 见 [HooksConfig.preservedEvents]。清单外的名字是大小写敏感的，照 CLI 的写法。
 */
internal val MANAGED_HOOK_EVENTS = listOf(
    "PreToolUse",
    "PostToolUse",
    "PostToolUseFailure",
    "UserPromptSubmit",
    "Stop",
    "SessionStart",
    "SessionEnd",
)

/**
 * 一条 hook。
 *
 * 只做 `type: "command"` 这一种（`prompt` 那种要模型参与，是另一回事）。
 * 字段挑最常用的四个：`matcher` / `command` / `timeout` / `statusMessage`；
 * `args`、`if`、`once`、`async` 等明确不做（见设计稿第三章）。
 */
data class HookRule(
    var event: String = MANAGED_HOOK_EVENTS.first(),
    var matcher: String = "",
    var command: String = "",
    /** 秒。0 = 不写这一项（写 0 会被 CLI 当成"立刻超时"）。 */
    var timeout: Int = 0,
    var statusMessage: String = "",
)

/**
 * 读出来的结果，**三层各归各的**：
 *
 *  - [rules]：面板能编辑的
 *  - [preservedEvents]：**整个事件**都不归我们管（26 个里的某个，或将来新增的），
 *    连数组一起逐字保留
 *  - [preservedMatchers]：事件归我们管、但某一条 matcher 认不得
 *    （比如 `type: "prompt"`），按事件名分组逐字保留
 *
 * 分开装是这份文件存在的理由：用户可能配了插件不认识的东西，
 * 而**改别的绝不能把它们弄丢** —— 那是在删用户的配置。
 */
data class HooksConfig(
    val rules: List<HookRule>,
    val preservedEvents: Map<String, JsonArray>,
    val preservedMatchers: Map<String, JsonArray>,
)

/** 读 `hooks`。没有这一块就是空的（不是错误）。 */
fun hooksOf(root: JsonObject): HooksConfig {
    val block = root.getAsJsonObject("hooks") ?: return HooksConfig(emptyList(), emptyMap(), emptyMap())
    val rules = mutableListOf<HookRule>()
    val preservedEvents = linkedMapOf<String, JsonArray>()
    val preservedMatchers = linkedMapOf<String, JsonArray>()

    for ((event, value) in block.entrySet()) {
        val array = value.takeIf { it.isJsonArray }?.asJsonArray ?: continue
        if (event !in MANAGED_HOOK_EVENTS) {
            preservedEvents[event] = array
            continue
        }
        for (element in array) {
            val matcher = element.takeIf { it.isJsonObject }?.asJsonObject
            val rule = matcher?.let { parseHookRule(event, it) }
            if (rule != null) {
                rules += rule
            } else {
                preservedMatchers.getOrPut(event) { JsonArray() }.add(element)
            }
        }
    }
    return HooksConfig(rules, preservedEvents, preservedMatchers)
}

/**
 * 写回 `hooks`。
 *
 * **事件顺序照原文件**：改一条不该让它在文件里换位置。新事件（原文件里没有、
 * 但用户刚加了规则的）排最后。
 *
 * 已知的一处不完美：被管理的事件里，认不得的那几条 matcher 会被排到该事件的
 * **末尾**（而不是它们原来的位置）。换取的是不必给每条 matcher 记位置 ——
 * 那种复杂度不值当，而这种情况本身很少见（同一个事件下混着 command 与 prompt）。
 */
fun withHooks(
    root: JsonObject,
    rules: List<HookRule>,
    preservedEvents: Map<String, JsonArray>,
    preservedMatchers: Map<String, JsonArray>,
): JsonObject {
    val original = root.getAsJsonObject("hooks") ?: JsonObject()
    val block = JsonObject()

    for ((event, value) in original.entrySet()) {
        if (event !in MANAGED_HOOK_EVENTS) {
            block.add(event, preservedEvents[event] ?: value)
            continue
        }
        val array = JsonArray()
        preservedMatchers[event]?.forEach { array.add(it) }
        rules.filter { it.event == event }.forEach { array.add(it.toHookJson()) }
        // 空了就不写这个事件 —— 删掉最后一条规则之后留个空数组是噪音
        if (array.size() > 0) block.add(event, array)
    }

    val appended = rules.map { it.event }.distinct().filter { !original.has(it) }
    for (event in appended) {
        val array = JsonArray()
        preservedMatchers[event]?.forEach { array.add(it) }
        rules.filter { it.event == event }.forEach { array.add(it.toHookJson()) }
        if (array.size() > 0) block.add(event, array)
    }

    return root.deepCopy().apply { add("hooks", block) }
}

/** 一条规则写成一个 matcher 对象：`{matcher?, hooks: [{type: "command", ...}]}`。 */
fun HookRule.toHookJson(): JsonObject = JsonObject().apply {
    // matcher 空 = 全部工具，这时**不写这个键** —— 空串在 CLI 那里是"匹配空名字"
    if (matcher.isNotBlank()) addProperty("matcher", matcher.trim())
    val commandEntry = JsonObject().apply {
        addProperty("type", "command")
        addProperty("command", command.trim())
        if (timeout > 0) addProperty("timeout", timeout)
        if (statusMessage.isNotBlank()) addProperty("statusMessage", statusMessage.trim())
    }
    add("hooks", JsonArray().apply { add(commandEntry) })
}

/**
 * 认一条 matcher。**只认"恰好一条 command"**：
 *
 * 一个 matcher 底下挂多条 hook 是合法写法，但面板的模型是"一条规则一行"，
 * 拆开再合回去会改变文件形状 —— 认不得就整条保留，宁可不让它出现在面板里。
 */
private fun parseHookRule(event: String, matcher: JsonObject): HookRule? {
    val hooks = matcher.get("hooks")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    if (hooks.size() != 1) return null
    val entry = hooks[0].takeIf { it.isJsonObject }?.asJsonObject ?: return null
    if (entry.str("type") != "command") return null
    val command = entry.str("command") ?: return null
    return HookRule(
        event = event,
        matcher = matcher.str("matcher").orEmpty(),
        command = command,
        timeout = entry.int("timeout") ?: 0,
        statusMessage = entry.str("statusMessage").orEmpty(),
    )
}

// ---- 本文件私有的 JSON 取值辅助（仓库里已有几份同样的，各自私有）----

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
