package com.ccoder.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

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

    /**
     * 思考深度切换的回执。
     *
     * 与 [PermissionModeChanged] 同一条规矩：界面**只**在收到它之后才更新标签，
     * 免得切换失败时标签显示一个没生效的档位。
     *
     * [level] 为 null 不是"缺数据"，而是**合法的「默认」档** —— 意思是已经从
     * flag 层清除、回落到模型自己的默认档。所以解析时不能把 null 当畸形丢掉
     * （见 [Protocol.parse] 里那个 `has("level")` 判断）。
     */
    data class EffortChanged(val level: String?) : SidecarMessage

    /**
     * 会话中途换模型的回执。
     *
     * 与 [PermissionChanged] 同一条规矩：界面**只**在收到它之后才更新标签。
     *
     * [model] 没有"清除"那一档（与 [EffortChanged] 正相反）—— 换模型永远是
     * 换到一个**具体名字**，所以这里不需要 `has()` 那套判断，缺字段就是畸形。
     *
     * 回执说的是"CLI 收下了"，不是"这个模型在网关上真的存在"。后者 CLI 不校验，
     * 认不出的名字要到下一轮请求才会响亮地失败 —— 那是一个看得见的错误，
     * 不是一条静默的谎。
     */
    data class ModelChanged(val model: String) : SidecarMessage

    /**
     * 上下文用量的应答。
     *
     * 是**请求-响应式**的（带 [requestId]），因为它总是"问一次答一次"：
     * 会话建立后问一次，每轮跑完再问一次。
     *
     * 与 `ui` 包里那个 [com.ccoder.ui.ContextUsage] 不是一回事：这个是协议报文，
     * 那个是卡片用的值对象。名字不同是为了别让读代码的人以为可以互换。
     *
     * [windowTokens] 是**算比例用的那个分母**（CLI 的 `rawMaxTokens`），不是模型
     * 硬上限 —— 它可能是按压缩策略收窄过的窗口。
     */
    data class ContextUsageReport(
        val requestId: String,
        val usedTokens: Long,
        val windowTokens: Long,
    ) : SidecarMessage

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
     * 改名 / 打标签的回执。
     *
     * 两者各是一个消息而不是共用一个"某字段变了"的信封：它们影响的是**不同**
     * 的字段（改名不动 tag，反之亦然），合起来会让人以为整行都可能变。
     *
     * [title]/[tag] 是**回读**来的权威值（sidecar 改完会读一次会话记录），
     * 不是回显我们刚发出去的东西 —— 回读才知道写入真的落下了。
     * tag 为 null 是有效取值，表示标签被清掉。
     */
    data class SessionRenamed(
        val requestId: String,
        val sessionId: String,
        val title: String?,
    ) : SidecarMessage

    data class SessionTagged(
        val requestId: String,
        val sessionId: String,
        val tag: String?,
    ) : SidecarMessage

    /** `listSubagents` 的应答。 */
    data class Subagents(val requestId: String, val agents: List<SubagentInfo>) : SidecarMessage

    /**
     * 某个子代理的转写。
     *
     * [items] 与 `loadHistory` 那份**同形**（都是原始会话消息），所以解析与渲染
     * 可以完全复用 —— 子代理的转写本来就是同一套消息格式。
     */
    data class SubagentMessages(
        val requestId: String,
        val agentId: String,
        val items: List<JsonObject>,
    ) : SidecarMessage

    /**
     * 删除会话的回执。
     *
     * `sessionDeleted` 缺 `sessionId` 时整条丢弃（见 [Protocol.parse]）——
     * 不知道删掉的是哪一个，就不知道该把哪一行从列表里去掉。
     */
    data class SessionDeleted(val requestId: String, val sessionId: String) : SidecarMessage

    /**
     * `clearSessions` 的回执：这个项目的历史会话被清了一批。
     *
     * [deleted] 与 [failed] **都要带 id 列表**，不只是计数：
     *  - [deleted] 决定界面上哪几行消失（不能只说"删了 35 条"就自己猜是哪 35 条）
     *  - [failed] 决定哪几行留着，并把没删掉的原因说出来
     *
     * 缺 `id` 的整条丢弃（同 [SessionDeleted]）—— 没有配对 id 就没有等它的那个回调。
     */
    data class SessionsCleared(
        val requestId: String,
        val deleted: List<String>,
        val failed: List<ClearFailure>,
    ) : SidecarMessage

    /**
     * `listCommands` 的应答。
     *
     * [commands] 是**显示信息**（名字、描述、参数提示、别名）；
     * [skills] 是其中的**技能子集**，只用来分组，不是另一份候选。
     */
    data class Commands(
        val requestId: String,
        val commands: List<CommandInfo>,
        val skills: List<CommandInfo>,
    ) : SidecarMessage

    /**
     * `mcpServerStatus` 的应答。
     *
     * 列的是**当前会话里**各 MCP server 的实时状态，含我们自己没配的那些 ——
     * [McpServerStatus.scope] 能分辨 `user` / `project`，面板靠它把"项目里的"
     * 与"你自己全局配的"分开显示（2026-09-15 探针实测）。
     */
    data class McpServers(
        val requestId: String,
        val servers: List<McpServerStatus>,
    ) : SidecarMessage

    /** 未知类型。与"解析失败"（null）区分开——这类要忽略而非报错。 */
    data class Unknown(val type: String) : SidecarMessage
}

/**
 * 一条 MCP server 的实时状态。
 *
 * 字段从 SDK 的 `McpServerStatus` 里**裁剪**出来，只留界面要用的：
 * [status] 是 `connected` / `failed` / `needs-auth` / `pending` / `disabled`
 * 之一（原样透传，不当枚举认 —— CLI 将来加一档不该让我们解析失败）；
 * [error] 只在失败时有意义；[tools] 是它提供的工具名。
 */
data class McpServerStatus(
    val name: String,
    val status: String,
    val scope: String?,
    val error: String?,
    val tools: List<String>,
)

/**
 * 会话列表中的一条。
 *
 * 字段是从 SDK 的 `SDKSessionInfo` 里**裁剪**出来的（sdk.d.ts:5455）：
 * 只留界面要用的四个。`gitBranch` 等刻意不带过来 —— 实测本机全部会话
 * 都在同一分支，没有信息量（spec §9.5）。
 */
data class SessionInfo(
    val sessionId: String,
    val summary: String?,
    val firstPrompt: String?,
    val lastModified: Long,
    /**
     * 用户自己起的名字。**显示时优先于 [summary]** —— 不然改完名回到列表
     * 看到的还是那句自动摘要，等于白改。
     */
    val customTitle: String? = null,
    /** 用户打的标签。null = 没打（或刚清掉）。 */
    val tag: String? = null,
)

/**
 * 批量清空时**没删掉**的一条。
 *
 * 带 [reason] 是为了把原因说出来：单条失败不中断整批（见 `clearSessions`），
 * 但"删了 35 条、1 条没删掉"必须能说清是哪条、为什么。
 */
data class ClearFailure(val sessionId: String, val reason: String?)

/**
 * 一个子代理。
 *
 * [toolUseId] 是它与界面上"运行中的任务"对上号的凭据 —— 任务那一侧的 id
 * 就是 tool_use id。对不上（读不到元信息）时为 null，界面就只显示 [agentId]。
 */
data class SubagentInfo(
    val agentId: String,
    val agentType: String?,
    val description: String?,
    val toolUseId: String?,
)

/**
 * 一条可补全的命令。
 *
 * 字段裁自 SDK 的 `SlashCommand`（sdk.d.ts:8843）。**`name` 是显示名，
 * 不一定是可发送的字符串** —— 实测 `/Debug Issue`（空格大写）对应的可发送名
 * 是 `debug-issue`。可发送名来自 init 事件，见设计稿 §4.1。
 */
data class CommandInfo(
    val name: String,
    val description: String?,
    val argumentHint: String?,
    val aliases: List<String>,
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

/**
 * 要发出去的一张图，base64 已经编好。
 *
 * 为什么不在这一层直接用 `AttachedImage`（UI 侧那个）：那边揣着 `BufferedImage`
 * 缩略图，是**界面**的东西。协议层只该认识"一个 mediaType 加一串 base64"——
 * 这样它既能被单测直接构造，也不必为了改缩略图尺寸而重新编译协议。
 */
data class OutgoingImage(val mediaType: String, val data: String)

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

            // 「默认」档的 level 就是 JSON null，所以这里**不能照抄上面那条**
            // 「缺字段即畸形」的规则：在 str() 眼里，"键不在"与"键在且为 null"
            // 长得一模一样，一把梭会把合法的默认档回执当成畸形丢掉 ——
            // 表现是选了「默认」标签不动，看着像点坏了。
            //
            // 要的是**键在不在**：键不在才是畸形（界面无从知道该显示什么）。
            "effortChanged" -> when {
                !obj.has("level") -> null
                obj.get("level").isJsonNull -> SidecarMessage.EffortChanged(null)
                else -> obj.str("level")?.let { SidecarMessage.EffortChanged(it) }
            }

            // 照 permissionModeChanged（**不是**上面那条）：模型没有"清除"这个
            // 状态，所以缺字段就是畸形，没有"键在且为 null 也合法"那种情况
            "modelChanged" -> obj.str("model")?.let { SidecarMessage.ModelChanged(it) }

            "sessionRenamed" -> {
                val rid = obj.str("id")
                val sid = obj.str("sessionId")
                if (rid == null || sid == null) null
                else SidecarMessage.SessionRenamed(rid, sid, obj.str("value"))
            }

            "sessionTagged" -> {
                val rid = obj.str("id")
                val sid = obj.str("sessionId")
                if (rid == null || sid == null) null
                else SidecarMessage.SessionTagged(rid, sid, obj.str("value"))
            }

            "subagents" -> {
                val rid = obj.str("id")
                if (rid == null) null else SidecarMessage.Subagents(rid, parseSubagents(obj.arr("agents")))
            }

            "subagentMessages" -> {
                val rid = obj.str("id")
                val agentId = obj.str("agentId")
                if (rid == null || agentId == null) {
                    null
                } else {
                    SidecarMessage.SubagentMessages(
                        rid,
                        agentId,
                        obj.arr("items")
                            ?.filter { it.isJsonObject }
                            ?.map { it.asJsonObject }
                            ?: emptyList(),
                    )
                }
            }

            // 缺 id 就无从配对，按畸形丢弃（同 permission 那条）。两个计数缺失
            // 记 0：卡片本来就有"没测量值就显示 0"这条路
            "contextUsage" -> {
                val requestId = obj.str("id")
                if (requestId == null) {
                    null
                } else {
                    SidecarMessage.ContextUsageReport(
                        requestId = requestId,
                        usedTokens = obj.long("usedTokens") ?: 0L,
                        windowTokens = obj.long("windowTokens") ?: 0L,
                    )
                }
            }

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

            // 缺 id 就无从配对，整条丢弃 —— 同 sessions / sessionDeleted。
            // 两个数组**缺了当空**（不是丢弃）：`deleted` 空 + `failed` 空是合法的
            // 回执（一条都没删，比如 keep 覆盖了全部），界面照实说"没有可清空的"
            "sessionsCleared" -> obj.str("id")?.let { rid ->
                SidecarMessage.SessionsCleared(
                    requestId = rid,
                    deleted = obj.arr("deleted")?.mapNotNull { it.strOrNull() } ?: emptyList(),
                    failed = obj.arr("failed")?.mapNotNull { el ->
                        if (!el.isJsonObject) return@mapNotNull null
                        val o = el.asJsonObject
                        // 没有 sessionId 的失败项毫无用处（不知道是哪条没删掉），跳过
                        o.str("sessionId")?.let { ClearFailure(it, o.str("reason")) }
                    } ?: emptyList(),
                )
            }

            // 缺 id 就无从配对，整条丢弃 —— 同 sessions
            "commands" -> obj.str("id")?.let { rid ->
                SidecarMessage.Commands(
                    rid,
                    parseCommands(obj.arr("commands")),
                    parseCommands(obj.arr("skills")),
                )
            }

            // 同上：缺 id 整条丢弃
            "mcpServers" -> obj.str("id")?.let { rid ->
                SidecarMessage.McpServers(rid, parseMcpServers(obj.arr("servers")))
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
     * 清空这个项目的历史会话。
     *
     * [keep] 是**要留下**的那些（正在被标签跑着的，见 [com.ccoder.ui.OpenSessions]）——
     * sidecar 只删这个列表以外的。空数组是合法取值（全删），字段照发。
     */
    fun encodeClearSessions(id: String, dir: String, keep: List<String>): String =
        line(id, "clearSessions", JsonObject().apply {
            addProperty("dir", dir)
            add("keep", JsonArray().apply { keep.forEach { add(it) } })
        })

    /**
     * 给会话改个名字。
     *
     * [title] 传**空串**等于恢复自动标题：CLI 把自定义名字单独存在
     * `custom-title.json` 里，而读的时候空名字会被当成"没有"
     * （SDK 内部是 `eZ(...) || void 0`），所以写个空名就回到自动摘要。
     */
    fun encodeRenameSession(id: String, sessionId: String, title: String): String =
        line(id, "renameSession", JsonObject().apply {
            addProperty("sessionId", sessionId)
            addProperty("title", title)
        })

    /**
     * 打标签 / 清标签。
     *
     * [tag] 为 null 表示**清掉**，所以要**显式**写 JSON null —— 省略字段只表示
     * "没提这件事"（同 [encodeSetEffort]）。
     */
    fun encodeTagSession(id: String, sessionId: String, tag: String?): String =
        line(id, "tagSession", JsonObject().apply {
            addProperty("sessionId", sessionId)
            add("tag", tag?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        })

    fun encodeListSubagents(id: String, dir: String, sessionId: String): String =
        line(id, "listSubagents", JsonObject().apply {
            addProperty("dir", dir)
            addProperty("sessionId", sessionId)
        })

    fun encodeSubagentMessages(id: String, dir: String, sessionId: String, agentId: String): String =
        line(id, "subagentMessages", JsonObject().apply {
            addProperty("dir", dir)
            addProperty("sessionId", sessionId)
            addProperty("agentId", agentId)
        })

    fun encodeListCommands(id: String): String = encodeSimple(id, "listCommands")

    /**
     * 问一次上下文用量。会话建立后问一次，每轮跑完再问一次。
     *
     * 这是**唯一的**用量来源：result 事件里的 `usage` / `modelUsage` 我们不再读
     * （前者只有主循环最后一次调用的三个 input 字段，后者是跨回合累计的总额，
     * 而且恢复会话时两个都拿不到窗口）。
     */
    fun encodeContextUsage(id: String): String = encodeSimple(id, "contextUsage")

    /**
     * 问一次各 MCP server 的实时状态（设置面板右侧那一栏）。
     *
     * 与 `contextUsage` 同一条：它是**会话的属性** —— 没有会话就没得报，
     * 而且它反映的只是**当前会话**连上了什么。用户刚改完 `.mcp.json` 时
     * 这里不会立刻变（改配置下次会话才生效），界面要把这句话说在明面上。
     */
    fun encodeMcpServerStatus(id: String): String = encodeSimple(id, "mcpServerStatus")

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
        is SidecarMessage.SessionsCleared -> msg.requestId
        is SidecarMessage.Commands -> msg.requestId
        is SidecarMessage.McpServers -> msg.requestId
        is SidecarMessage.ContextUsageReport -> msg.requestId
        is SidecarMessage.SessionRenamed -> msg.requestId
        is SidecarMessage.SessionTagged -> msg.requestId
        is SidecarMessage.Subagents -> msg.requestId
        is SidecarMessage.SubagentMessages -> msg.requestId
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

    fun encodeSend(id: String, text: String, images: List<OutgoingImage> = emptyList()): String =
        line(id, "send", JsonObject().apply {
            addProperty("text", text)
            // **没图时一个字段都不加**：报文与从前一字不差（测试里钉着）。
            // 这一条不是洁癖 —— 排队/补发那条路上有旧版本的侧车可能还在跑，
            // 多出来的键在老侧车那边是会被忽略，但"行为不变"要能**被证明**
            if (images.isNotEmpty()) {
                add("images", JsonArray().apply {
                    images.forEach { img ->
                        add(JsonObject().apply {
                            addProperty("mediaType", img.mediaType)
                            addProperty("data", img.data)
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
     * 会话中途改思考深度。
     *
     * [level] 为 null 表示**清除** flag 层、回落到模型默认档（即界面上的
     * 「默认」）—— 所以这个字段要**显式写成 JSON null**，不能像别的可选字段
     * 那样"为 null 就省略"。省略与 null 在 sidecar 侧是两种意思：
     * 前者是"没提这件事"，后者是"把它清掉"。
     */
    fun encodeSetEffort(id: String, level: String?): String =
        line(
            id,
            "setEffort",
            JsonObject().apply {
                add("level", level?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
            },
        )

    /**
     * 会话中途换模型。
     *
     * [model] **必填**，永远是个具体名字 —— 没有 [encodeSetEffort] 那种「清空」
     * 状态（`setModel(undefined)` 表达的不是清除），所以这里不需要 JSON null
     * 那条路，sidecar 侧也会把空串当参数错误挡下来。
     */
    fun encodeSetModel(id: String, model: String): String =
        line(id, "setModel", JsonObject().apply { addProperty("model", model) })

    /**
     * 终止一个正在跑的任务（"运行中"浮层每行右端那颗）。
     *
     * [taskId] 与事件里 `task_started.task_id` 同源 —— 界面拿的就是它，
     * 不需要另外对号（对子代理来说这个 id 同时就是它的 tool_use id）。
     */
    fun encodeStopTask(id: String, taskId: String): String =
        line(id, "stopTask", JsonObject().apply { addProperty("taskId", taskId) })

    /**
     * 权限决定。
     *
     * [updatedInput] 是 `AskUserQuestion` 回传答案的路：允许这个工具调用时
     * **改写它的入参**，把选中的答案塞进去（SDK 的 `PermissionResult`，
     * 见 sdk.d.ts:2380）。为 null 时不写这个字段 —— 传空对象等于
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
                customTitle = o.str("customTitle"),
                tag = o.str("tag"),
            )
        }
    }

    /**
     * 逐条解析子代理。
     *
     * 缺 `agentId` 的条目**跳过而非废掉整个列表** —— 与 [parseSessionList]
     * 同一条理由：一条坏数据不该让另外几个子代理都看不见。
     * 元信息（类型/描述/toolUseId）读不到是常事（文件可能不在），
     * 那几项给 null，界面退化成只显示 id。
     */
    private fun parseSubagents(arr: JsonArray?): List<SubagentInfo> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val agentId = o.str("agentId") ?: return@mapNotNull null
            SubagentInfo(
                agentId = agentId,
                agentType = o.str("agentType"),
                description = o.str("description"),
                toolUseId = o.str("toolUseId"),
            )
        }
    }

    /**
     * 逐条解析命令。
     *
     * 缺 `name` 的条目**跳过而非废掉整个列表** —— 与 [parseSessionList] 同一条
     * 理由：一条坏数据不该让另外 44 个命令都补全不出来。
     */
    /**
     * 解析 server 列表。
     *
     * `status` 与 `scope` **原样透传、不当枚举认**：CLI 将来加一档不该让我们
     * 解析失败（§3.3 那条"容忍未知"的同一个道理）。名字缺了就丢这一条 ——
     * 一条没有名字的 server 在界面上没有落点。
     */
    private fun parseMcpServers(arr: JsonArray?): List<McpServerStatus> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            McpServerStatus(
                name = name,
                // 认不出的一律当 pending：那是"还不知道"，不是"坏了"
                status = o.str("status") ?: "pending",
                scope = o.str("scope"),
                error = o.str("error"),
                tools = o.arr("tools")?.mapNotNull { t ->
                    t.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                }.orEmpty(),
            )
        }
    }

    private fun parseCommands(arr: JsonArray?): List<CommandInfo> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val name = o.str("name") ?: return@mapNotNull null
            CommandInfo(
                name = name,
                description = o.str("description"),
                argumentHint = o.str("argumentHint"),
                aliases = o.arr("aliases")
                    ?.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.map { it.asString }
                    ?: emptyList(),
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

    /** 数组元素版的容错取串（判定同 [str]）—— `deleted` 里混进非字符串时跳过那一个。 */
    private fun JsonElement.strOrNull(): String? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
