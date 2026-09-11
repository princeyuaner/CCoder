package com.ccoder.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * sidecar 与插件之间的消息。
 *
 * 与 sidecar 侧 sidecar/ndjson.js 对称：分帧只负责切行，合法性判定在这里。
 */
sealed interface SidecarMessage {

    /** 会话已建立。 */
    data class Ready(val sessionId: String?, val model: String?) : SidecarMessage

    /** SDK 事件，**原样透传**。插件按 event["type"] 分发，未知类型静默忽略。 */
    data class Event(val event: JsonObject) : SidecarMessage

    /** 权限询问。 */
    data class Permission(
        val requestId: String,
        val toolName: String,
        val input: JsonObject,
        val title: String?,
        val displayName: String?,
        val description: String?,
        val blockedPath: String?,
        val decisionReason: String?,
        // 安全默认值：字段缺失时偏向拒绝而非放行
        val defaultToNo: Boolean = true,
        val suppressAlwaysAllowRule: Boolean = true,
        val suggestions: JsonArray? = null,
    ) : SidecarMessage

    /** 错误。fatal=true 表示会话已终止。 */
    data class Failure(val message: String, val code: String?, val fatal: Boolean) : SidecarMessage

    /** sidecar 进程退出。 */
    data class Exit(val code: Int, val signal: String?) : SidecarMessage

    /** 未知类型。与"解析失败"（null）区分开——这类要忽略而非报错。 */
    data class Unknown(val type: String) : SidecarMessage
}

data class StartParams(
    val cwd: String,
    val permissionMode: String,
    val model: String? = null,
    val claudePath: String? = null,
    val extraDirs: List<String> = emptyList(),
    val envOverrides: Map<String, String> = emptyMap(),
)

object Protocol {

    /**
     * 解析一行输入。
     *
     * @return 解析成功返回消息对象；空行/非法 JSON/类型未知以下的情形返回 null；
     *         JSON 合法但 `type` 不认识时返回 [SidecarMessage.Unknown]
     */
    fun parse(line: String): SidecarMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val parsed = try {
            JsonParser.parseString(trimmed)
        } catch (_: Exception) {
            // 实测 stdout 会混入 "[claude-code:...] {...}" 这类前缀行（spec §11.2）
            return null
        }

        // 协议规定消息必须是对象；数组与裸标量属于畸形输入
        if (!parsed.isJsonObject) return null
        val obj = parsed.asJsonObject

        return when (val type = obj.str("type")) {
            null -> null

            "ready" -> SidecarMessage.Ready(
                sessionId = obj.str("sessionId"),
                model = obj.str("model"),
            )

            "event" -> obj.obj("event")?.let { SidecarMessage.Event(it) }

            "permission" -> {
                // requestId 是关联权限决定的唯一凭据，缺了就无法回传决定，
                // 只能当畸形消息丢弃 —— 否则会留下一个永远无法 resolve 的挂起项
                val requestId = obj.str("requestId")
                if (requestId == null) {
                    null
                } else {
                    SidecarMessage.Permission(
                        requestId = requestId,
                        toolName = obj.str("toolName") ?: "",
                        input = obj.obj("input") ?: JsonObject(),
                        title = obj.str("title"),
                        displayName = obj.str("displayName"),
                        description = obj.str("description"),
                        blockedPath = obj.str("blockedPath"),
                        decisionReason = obj.str("decisionReason"),
                        defaultToNo = obj.bool("defaultToNo") ?: true,
                        suppressAlwaysAllowRule = obj.bool("suppressAlwaysAllowRule") ?: true,
                        suggestions = obj.arr("suggestions"),
                    )
                }
            }

            "error" -> SidecarMessage.Failure(
                message = obj.str("message") ?: "未知错误",
                code = obj.str("code"),
                fatal = obj.bool("fatal") ?: false,
            )

            "exit" -> SidecarMessage.Exit(
                code = obj.num("code") ?: -1,
                signal = obj.str("signal"),
            )

            else -> SidecarMessage.Unknown(type)
        }
    }

    fun encodeStart(id: String, params: StartParams): String {
        val p = JsonObject().apply {
            addProperty("cwd", params.cwd)
            addProperty("permissionMode", params.permissionMode)
            params.model?.let { addProperty("model", it) }
            params.claudePath?.let { addProperty("claudePath", it) }
            if (params.extraDirs.isNotEmpty()) {
                add("extraDirs", JsonArray().apply { params.extraDirs.forEach { add(it) } })
            }
            if (params.envOverrides.isNotEmpty()) {
                add(
                    "envOverrides",
                    JsonObject().apply { params.envOverrides.forEach { (k, v) -> addProperty(k, v) } }
                )
            }
        }
        return line(id, "start", p)
    }

    fun encodeSend(id: String, text: String): String =
        line(id, "send", JsonObject().apply { addProperty("text", text) })

    fun encodeSimple(id: String, method: String): String =
        line(id, method, JsonObject())

    fun encodeSetPermissionMode(id: String, mode: String): String =
        line(id, "setPermissionMode", JsonObject().apply { addProperty("mode", mode) })

    fun encodePermissionDecision(
        id: String,
        requestId: String,
        allow: Boolean,
        updatedPermissions: JsonArray?,
        message: String?,
    ): String {
        val p = JsonObject().apply {
            addProperty("requestId", requestId)
            addProperty("behavior", if (allow) "allow" else "deny")
            updatedPermissions?.let { add("updatedPermissions", it) }
            message?.let { addProperty("message", it) }
        }
        return line(id, "permissionDecision", p)
    }

    private fun line(id: String, method: String, params: JsonObject): String =
        JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }.toString() + "\n"

    // ---- 容错取值：JSON 类型不符时返回 null 而非抛出 ClassCastException ----

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.num(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
