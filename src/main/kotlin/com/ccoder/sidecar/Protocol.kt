package com.ccoder.sidecar

import com.ccoder.ui.ImageAttachment
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

    /**
     * 权限模式切换的回执。
     *
     * 界面**只**在收到它之后才更新标签：先改标签、后等结果的话，切换失败时
     * 标签会显示一个没生效的模式。这是个安全控件，显示错的比不好用严重。
     */
    data class PermissionModeChanged(val mode: String) : SidecarMessage

    /** 错误。fatal=true 表示会话已终止。 */
    data class Failure(val message: String, val code: String?, val fatal: Boolean) : SidecarMessage

    /** sidecar 进程退出。 */
    data class Exit(val code: Int, val signal: String?) : SidecarMessage

    /**
     * `listSessions` 的应答。
     *
     * 带 [requestId] 是为了配对 —— 这是协议里第一批请求-响应式消息。
     */
    data class SessionList(val requestId: String, val sessions: List<SessionInfo>) : SidecarMessage

    /**
     * `loadHistory` 的应答。
     *
     * [items] 是 SDK 的原始消息，**形状与流式事件同构** —— 所以回放能直接
     * 复用 MessageRenderer 与转写管线，不需要另一套渲染代码（spec §6.1）。
     */
    data class History(
        val requestId: String,
        val sessionId: String,
        val items: List<JsonObject>,
    ) : SidecarMessage

    /**
     * 删除会话的回执。
     *
     * `sessionDeleted` 缺 `sessionId` 时整条丢弃（见 [Protocol.parse]）——
     * 不知道删掉的是哪一个，就不知道该把哪一行从列表里去掉。
     */
    data class SessionDeleted(val requestId: String, val sessionId: String) : SidecarMessage

    /** 未知类型。与"解析失败"（null）区分开——这类要忽略而非报错。 */
    data class Unknown(val type: String) : SidecarMessage
}

/**
 * 会话列表中的一条。
 *
 * 字段是从 SDK 的 `SDKSessionInfo` 里**裁剪**出来的（sdk.d.ts:5154）：
 * 只留界面要用的四个。`gitBranch` 等刻意不带过来 —— 实测本机全部会话
 * 都在同一分支，没有信息量（spec §9.5）。
 */
data class SessionInfo(
    val sessionId: String,
    val summary: String?,
    val firstPrompt: String?,
    val lastModified: Long,
)

data class StartParams(
    val cwd: String,
    val permissionMode: String,
    val model: String? = null,
    val claudePath: String? = null,
    val extraDirs: List<String> = emptyList(),
    val envOverrides: Map<String, String> = emptyMap(),
    /** 非空则恢复该会话（SDK 的 `Options.resume`）。空 = 开新会话。 */
    val resumeSessionId: String? = null,
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

            // 缺 mode 就无从更新标签，按畸形丢弃（返回 null）。
            // 留着只会让界面显示一个空模式
            "permissionModeChanged" ->
                obj.str("mode")?.let { SidecarMessage.PermissionModeChanged(it) }

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

            // 缺 id 就无从配对，整条丢弃 —— 留着只会变成一个永远等不到结果的占位
            "sessions" -> obj.str("id")?.let { rid ->
                SidecarMessage.SessionList(rid, parseSessionList(obj.arr("sessions")))
            }

            "history" -> {
                val rid = obj.str("id")
                val sid = obj.str("sessionId")
                if (rid == null || sid == null) {
                    null
                } else {
                    SidecarMessage.History(
                        rid,
                        sid,
                        obj.arr("items")
                            ?.filter { it.isJsonObject }
                            ?.map { it.asJsonObject }
                            ?: emptyList(),
                    )
                }
            }

            "sessionDeleted" -> {
                // 两个字段缺一不可：requestId 用来配对，sessionId 用来知道删了哪个
                val requestId = obj.str("id")
                val sessionId = obj.str("sessionId")
                if (requestId == null || sessionId == null) null
                else SidecarMessage.SessionDeleted(requestId, sessionId)
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

    fun encodeListSessions(id: String, dir: String, limit: Int, offset: Int): String =
        line(id, "listSessions", JsonObject().apply {
            addProperty("dir", dir)
            addProperty("limit", limit)
            addProperty("offset", offset)
        })

    fun encodeLoadHistory(id: String, dir: String, sessionId: String): String =
        line(id, "loadHistory", JsonObject().apply {
            addProperty("dir", dir)
            addProperty("sessionId", sessionId)
        })

    fun encodeDeleteSession(id: String, sessionId: String): String =
        line(id, "deleteSession", JsonObject().apply { addProperty("sessionId", sessionId) })

    /**
     * 响应类消息的关联 id。非响应消息返回 null。
     *
     * 放在这里而不是 [com.ccoder.sidecar.SidecarClient]：客户端不必认识每一种
     * 消息类型，将来新增一种响应消息时也只改这一处。
     */
    fun responseIdOf(msg: SidecarMessage): String? = when (msg) {
        is SidecarMessage.SessionList -> msg.requestId
        is SidecarMessage.History -> msg.requestId
        is SidecarMessage.SessionDeleted -> msg.requestId
        else -> null
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
            params.resumeSessionId?.let { addProperty("resumeSessionId", it) }
        }
        return line(id, "start", p)
    }

    /**
     * 发送一条用户消息。
     *
     * [images] 为空时**一个字段都不多写** —— 纯文本走的就是老形状，
     * 老版本 sidecar 照常工作。
     *
     * 是 `internal` 而非 public：`ImageAttachment` 本身是 internal 的，
     * 公开函数签名里挂一个 internal 类型 Kotlin 直接报错。插件只有一个模块，
     * 调用方全在模块内，收窄可见性没有任何代价。
     */
    internal fun encodeSend(id: String, text: String, images: List<ImageAttachment> = emptyList()): String =
        line(id, "send", JsonObject().apply {
            addProperty("text", text)
            if (images.isNotEmpty()) {
                add("images", JsonArray().apply {
                    images.forEach { img ->
                        add(JsonObject().apply {
                            addProperty("mediaType", img.mediaType)
                            addProperty("data", img.base64)
                        })
                    }
                })
            }
        })

    fun encodeSimple(id: String, method: String): String =
        line(id, method, JsonObject())

    fun encodeSetPermissionMode(id: String, mode: String): String =
        line(id, "setPermissionMode", JsonObject().apply { addProperty("mode", mode) })

    /**
     * 权限决定。
     *
     * [updatedInput] 是 `AskUserQuestion` 回传答案的路：允许这个工具调用时
     * **改写它的入参**，把选中的答案塞进去（SDK 的 `PermissionResult`，
     * 见 sdk.d.ts:2340）。为 null 时不写这个字段 —— 传空对象等于
     * "显式把入参改写成空"，语义完全不同。
     */
    fun encodePermissionDecision(
        id: String,
        requestId: String,
        allow: Boolean,
        updatedPermissions: JsonArray?,
        message: String?,
        updatedInput: JsonObject? = null,
    ): String {
        val p = JsonObject().apply {
            addProperty("requestId", requestId)
            addProperty("behavior", if (allow) "allow" else "deny")
            updatedPermissions?.let { add("updatedPermissions", it) }
            updatedInput?.let { add("updatedInput", it) }
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

    /**
     * 逐条解析会话列表。
     *
     * 缺 `sessionId` 的条目**跳过而非废掉整个列表** —— 一条坏数据不该让
     * 另外 49 个会话都看不见。`lastModified` 缺失给 0，排序时自然沉底。
     */
    private fun parseSessionList(arr: JsonArray?): List<SessionInfo> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val sid = o.str("sessionId") ?: return@mapNotNull null
            SessionInfo(
                sessionId = sid,
                summary = o.str("summary"),
                firstPrompt = o.str("firstPrompt"),
                lastModified = o.long("lastModified") ?: 0L,
            )
        }
    }

    // ---- 容错取值：JSON 类型不符时返回 null 而非抛出 ClassCastException ----

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.num(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
