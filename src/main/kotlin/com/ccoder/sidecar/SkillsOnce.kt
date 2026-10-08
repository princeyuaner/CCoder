package com.ccoder.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 一次性技能枚举（`sidecar/skills-once.js`）的回执 —— **纯解析**，单测直接打。
 *
 * 为什么不是 NDJSON 协议里的一条：那套是"会话里的一条通道"，要认主人、要退订；
 * 技能页问一次就完，见那个文件头的三条理由。所以它的输出不走 [Protocol]，
 * 而是**一行 JSON 印在 stdout 上**。
 *
 * 为什么 `name` 有两个意思（这里刻意不统一）：`commands` 的 name 与 `usage` 的 name
 * 都是 **CLI 的显示名**（"Debug Issue"），**不是设置键**（`debug-issue`）——
 * 两者在真机上实测不同名（探针 Q5），而写盘认的是设置键。谁负责把它们对起来，
 * 见 `com.ccoder.settings.skillRowsOf`（要靠扫盘上的目录名）。
 */
data class SkillCommand(
    val name: String,
    val description: String,
    val builtin: Boolean,
)

/** 一条技能在上下文里的账单。`source` 是 CLI 的原话（`userSettings` / `plugin` / …）。 */
data class SkillUsageLine(
    val name: String,
    val source: String,
    val tokens: Long,
)

sealed interface SkillsOnceReply {
    data class Ok(
        val commands: List<SkillCommand>,
        val usage: List<SkillUsageLine>,
    ) : SkillsOnceReply

    /** 起不来、超时、或者那行 JSON 不是这个形状。[reason] 直接给用户看。 */
    data class Failed(val reason: String) : SkillsOnceReply
}

/**
 * 解析那一行 JSON。
 *
 * **取最后一行能解析的**：node 的告警（`DeprecationWarning` 那类）走的是 stderr，
 * 但 `console.log` 混进 stdout 的先例在探针里出现过（`shell` 那条 DEP0190）。
 * 从后往前找，比"假设只有一行"稳。
 *
 * 坏 JSON / 缺 `ok` / 形状不对**一律走 [SkillsOnceReply.Failed]**，不抛：
 * 这个函数的调用方在 EDT 上，抛出去就是一张报错弹窗。
 */
fun parseSkillsOnce(stdout: String): SkillsOnceReply {
    val obj = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") }
        .mapNotNull { line -> runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() }
        .lastOrNull { it.has("ok") }
        ?: return SkillsOnceReply.Failed(com.ccoder.text.CcoderText.text("settings.skills.error.badReply"))

    if (!obj.bool("ok")) {
        return SkillsOnceReply.Failed(
            obj.str("error")?.takeIf { it.isNotBlank() }
                ?: com.ccoder.text.CcoderText.text("settings.skills.error.unknown"),
        )
    }

    val commands = obj.arr("commands")?.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SkillCommand(name, o.str("description").orEmpty(), o.bool("builtin"))
    }.orEmpty()

    // `"skills": null` 是 sidecar **真会发**的形状（`usage?.skills ?? null`，老 CLI
    // 或问不到时就是它）。Gson 的 getAsJsonObject 撞上 JsonNull 直接抛
    // ClassCastException —— 用例逮住过一次，`obj()` 那层判空就是为它加的
    val usage = obj.obj("skills")?.arr("skillFrontmatter")?.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SkillUsageLine(name, o.str("source").orEmpty(), o.long("tokens") ?: 0L)
    }.orEmpty()

    return SkillsOnceReply.Ok(commands, usage)
}

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

private fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
