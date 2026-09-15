package com.ccoder.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

/** `.mcp.json` 在项目根。 */
internal fun mcpJsonPath(projectBase: Path): Path = projectBase.resolve(".mcp.json")

/**
 * server 的三种形状。
 *
 * `sdk`（带 `instance`，不可序列化）与 `claudeai-proxy`（云端专用）**明确不做**
 * —— 但它们在文件里出现时**必须原样留着**，见 [McpConfig.preserved]。
 */
enum class McpKind(val label: String, val wireType: String?) {
    /** `type` 可省，省了文件更干净 —— 这也是 CLI 自己的写法。 */
    STDIO("命令行", null),
    SSE("SSE", "sse"),
    HTTP("HTTP", "http"),
}

/**
 * 一条 MCP server 配置。
 *
 * 三种形状共用一个类（`kind` 决定哪几个字段有意义），照 `ModelProfile` 的先例：
 * 拆成密封类会让读写两侧都多一层 when，而字段总共就这么几个。
 */
data class McpServer(
    var name: String = "",
    var kind: String = McpKind.STDIO.name,
    var command: String = "",
    var args: MutableList<String> = mutableListOf(),
    var env: MutableMap<String, String> = mutableMapOf(),
    var url: String = "",
    var headers: MutableMap<String, String> = mutableMapOf(),
)

/** [kind] 那份字符串对应的枚举。坏值退回 [McpKind.STDIO] —— 文件是手可改的。 */
fun McpServer.mcpKind(): McpKind =
    McpKind.entries.firstOrNull { it.name == kind } ?: McpKind.STDIO

/**
 * 读出来的结果：**能编辑的**与**读不懂的**分开装。
 *
 * 分开装是这份文件存在的理由：用户可能装了插件不认识的形状（`sdk`、
 * `claudeai-proxy`，或者将来新增的第四种）。面板看不见它们，但**改别的
 * 绝不能把它们弄丢** —— 那是在删用户的配置。
 */
data class McpConfig(
    val servers: List<McpServer>,
    /** 名字 → 原始 JSON，逐字保留。 */
    val preserved: JsonObject,
)

/** 读 `mcpServers`。没有这一块就是空的（不是错误）。 */
fun mcpServersOf(root: JsonObject): McpConfig {
    val block = root.getAsJsonObject("mcpServers") ?: return McpConfig(emptyList(), JsonObject())
    val servers = mutableListOf<McpServer>()
    val preserved = JsonObject()
    for ((name, value) in block.entrySet()) {
        val obj = value.takeIf { it.isJsonObject }?.asJsonObject
        val parsed = obj?.let { parseMcpServer(name, it) }
        if (parsed != null) servers += parsed else preserved.add(name, value)
    }
    return McpConfig(servers, preserved)
}

/**
 * 写回 `mcpServers`。
 *
 * **按原文件的顺序重建**：改一条不该让它在文件里挪位置 —— 挪了就是一片
 * 与内容无关的 diff。新加的排最后。
 *
 * 用户删掉的条目（既不在 [servers] 也不在 [preserved] 里）就不写出去，
 * 那正是"删除"的意思。
 */
fun withMcpServers(root: JsonObject, servers: List<McpServer>, preserved: JsonObject): JsonObject {
    val original = root.getAsJsonObject("mcpServers") ?: JsonObject()
    val byName = servers.associateBy { it.name }
    val block = JsonObject()
    for ((name, value) in original.entrySet()) {
        when {
            byName.containsKey(name) -> block.add(name, byName.getValue(name).toMcpJson())
            preserved.has(name) -> block.add(name, value)
            else -> Unit   // 用户删掉了
        }
    }
    servers.filter { !original.has(it.name) }.forEach { block.add(it.name, it.toMcpJson()) }
    return root.deepCopy().apply { add("mcpServers", block) }
}

/** 一条 server 写成 JSON。空的可选字段一律不写 —— 别往用户文件里塞 `"args": []`。 */
fun McpServer.toMcpJson(): JsonObject = JsonObject().apply {
    val k = mcpKind()
    // stdio 的 type 是可省的，省掉更接近手写的文件
    k.wireType?.let { addProperty("type", it) }
    if (k == McpKind.STDIO) {
        addProperty("command", command.trim())
        val trimmedArgs = args.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmedArgs.isNotEmpty()) add("args", trimmedArgs.toJsonArray())
        if (env.isNotEmpty()) add("env", env.toJsonObject())
    } else {
        addProperty("url", url.trim())
        if (headers.isNotEmpty()) add("headers", headers.toJsonObject())
    }
}

private fun parseMcpServer(name: String, obj: JsonObject): McpServer? {
    val type = obj.str("type")
    val kind = when (type) {
        null -> McpKind.STDIO            // 没有 type 就是 stdio，这是 CLI 的规矩
        else -> McpKind.entries.firstOrNull { it.wireType == type } ?: return null
    }
    return McpServer(
        name = name,
        kind = kind.name,
        command = obj.str("command").orEmpty(),
        args = obj.stringList("args").toMutableList(),
        env = obj.stringMap("env").toMutableMap(),
        url = obj.str("url").orEmpty(),
        headers = obj.stringMap("headers").toMutableMap(),
    )
}

// ---- 本文件私有的 JSON 取值辅助（仓库里已有三份同样的，各自私有）----

// ---- 界面上那两栏多行文本的转换（纯函数，单测直接打）----
//
// 用多行文本而不是表格：表格在 240px 宽的栏里要三列才放得下（键、值、删），
// 而这两栏的内容通常只有两三行。代价是用户得知道格式 —— 所以页面上有提示语。

/** 一行一条参数，空行丢掉。 */
fun argsOf(text: String): MutableList<String> =
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

fun textOfArgs(args: List<String>): String = args.joinToString("\n")

/**
 * 一行一条 `KEY=VALUE`，**没有等号的行丢掉**。
 *
 * 丢掉而不是当成"值是空"：用户多半是在打字中间态（`FOO` 还没敲完等号），
 * 当成键会凭空多出一条空值记录。与 `EnvironmentSettingsPage.readPairs` 同一条规矩。
 */
fun pairsOf(text: String): MutableMap<String, String> {
    val out = linkedMapOf<String, String>()
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        val at = trimmed.indexOf('=')
        if (at > 0) {
            val key = trimmed.substring(0, at).trim()
            if (key.isNotEmpty()) out[key] = trimmed.substring(at + 1).trim()
        }
    }
    return out
}

fun textOfPairs(pairs: Map<String, String>): String =
    pairs.entries.joinToString("\n") { (k, v) -> "$k=$v" }

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.stringList(key: String): List<String> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
        .orEmpty()

private fun JsonObject.stringMap(key: String): Map<String, String> =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        ?.entrySet()
        ?.mapNotNull { (k, v) ->
            v.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let { k to it.asString }
        }
        ?.toMap()
        .orEmpty()

private fun List<String>.toJsonArray(): JsonArray =
    JsonArray().apply { this@toJsonArray.forEach { add(it) } }

private fun Map<String, String>.toJsonObject(): JsonObject =
    JsonObject().apply { this@toJsonObject.forEach { (k, v) -> addProperty(k, v) } }
