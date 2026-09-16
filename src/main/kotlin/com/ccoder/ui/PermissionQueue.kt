package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class PermissionDecision(
    val allow: Boolean,
    val updatedPermissions: JsonArray?,
    val message: String?,
    /**
     * 允许时改写工具调用的入参。
     *
     * `AskUserQuestion` 的答案就走这里 —— 那个工具没有别的办法把"用户选了哪个"
     * 送回去（见 [AskQuestion.answersFor]）。其余工具都是 null。
     */
    val updatedInput: com.google.gson.JsonObject? = null,
    /**
     * 同时打开"本会话不再询问"。
     *
     * 只有卡片上那个按钮会给它 —— 它授权的不只是这一次调用，而是**整个会话**。
     * 因此它和 [updatedPermissions] 是两条不同的路：那条写的是持久规则，作用
     * 范围到设置文件为止；这条只活在当前会话里，不落任何文件。
     */
    val stopAsking: Boolean = false,
)

/**
 * 「本会话不再询问」的文案。
 *
 * 卡片按钮与模式标签共用 —— 同一个状态在两处显示，各写一份迟早会漂移。
 */
internal const val AUTO_ALLOW_LABEL = "本会话不再询问"

/** 用户没答应的那条消息。会原样喂回模型，所以有且只有一处拼写。 */
internal const val DENY_MESSAGE = "用户拒绝"

/**
 * 「用户拒绝」的决定。
 *
 * 三处要造它：卡片上的拒绝按钮、对话框的**关窗与 Esc**（规则①：关掉即拒绝）、
 * 提问弹窗的拒绝。各拼一份的话 `message` 迟早有一处漏掉 —— 而那句话决定了
 * 模型看到的是"用户拒绝了"还是"一个没有理由的失败"。
 */
internal fun deniedByUser(): PermissionDecision =
    PermissionDecision(allow = false, updatedPermissions = null, message = DENY_MESSAGE)

/**
 * 权限询问的串行化队列。
 *
 * SDK 支持并行工具调用（一条 assistant 消息可含多个 tool_use），
 * 因此 canUseTool 可能被并发调用多次。一次只展示一张卡片，
 * 其余排队 —— 全堆出来会变成弹窗风暴（spec §6.4）。
 *
 * 注意这里只管理插件的本地 UI 队列。真正把挂起的 canUseTool 承诺
 * resolve 掉的是 sidecar 侧的 denyAllPending —— 插件的 cancelAll 只是
 * 清掉界面上的待确认卡片，两者的触发点都是会话终止。
 */
class PermissionQueue(
    private val onActivate: (SidecarMessage.Permission, queuedCount: Int) -> Unit,
) {
    private val queue = ArrayDeque<SidecarMessage.Permission>()
    private var active: SidecarMessage.Permission? = null

    /** 排队等待的项数，不含当前已激活的那张卡片。卡片用它显示"还有 N 个待确认"。 */
    val pendingCount: Int get() = queue.size

    /** 待决总数 = 激活的 + 排队的。状态栏用它显示总待办量。 */
    val totalPending: Int get() = queue.size + if (active != null) 1 else 0

    val activeRequestId: String? get() = active?.requestId

    fun enqueue(permission: SidecarMessage.Permission) {
        queue.addLast(permission)
        activateNextIfIdle()
    }

    fun resolve(requestId: String, decision: PermissionDecision) {
        // 已解决或非当前项，静默忽略 —— 重复决定不该产生副作用
        if (active?.requestId != requestId) return
        active = null
        activateNextIfIdle()
    }

    /** 作废所有待决项。对应 spec §6.2 规则① 的终止路径。 */
    fun cancelAll() {
        queue.clear()
        active = null
    }

    private fun activateNextIfIdle() {
        if (active != null) return
        val next = queue.removeFirstOrNull() ?: return
        active = next
        onActivate(next, queue.size)
    }
}

object PermissionOptions {

    /**
     * 是否显示"总是允许"。
     *
     * sdk.d.ts:249-253 原文要求：某些请求写一条持久规则会授予比本次询问
     * 更大的权限，此时不该提供"不再问"选项。因此必须同时满足
     * suppressAlwaysAllowRule=false 且 suggestions 非空。
     */
    fun allowsAlwaysAllow(p: SidecarMessage.Permission): Boolean =
        !p.suppressAlwaysAllowRule && (p.suggestions?.size() ?: 0) > 0

    /**
     * 「本会话不再询问」要不要作用于这条询问。
     *
     * 只有一条例外：[ASK_TOOL_NAME]。它不是授权请求，是在问你要答案 ——
     * 自动"允许"等于把那个问题吞掉，用户永远看不到它，而 Claude 会拿着
     * 一个没人回答过的提问继续往下走。
     */
    fun autoAllowApplies(p: SidecarMessage.Permission): Boolean =
        p.toolName != ASK_TOOL_NAME

    /**
     * 卡片主文案。
     *
     * sdk.d.ts:228-233：SDK 已把 title 渲染为完整问句，
     * 应优先使用而非从 toolName+input 重拼。缺失时才逐级降级。
     *
     * 2026-09-15 补一条：「把工具名念一遍」不算问句。用户截了 `ExitPlanMode` 的框
     * 来问"这个是什么审批" —— 因为标题就是 `ExitPlanMode` 这一个词。CLI 对内置工具
     * 给的 title/displayName 常常就是工具名本身，那种情况下要自己说人话
     * （见 [friendlyToolName]）。认不出的工具仍退回工具名：宁可给一个生词，
     * 也不要给一句编出来的话。
     */
    fun primaryText(p: SidecarMessage.Permission): String =
        p.title?.takeIf { it.isNotBlank() && !isBareToolName(it, p) }
            ?: p.displayName?.takeIf { it.isNotBlank() && !isBareToolName(it, p) }
            ?: friendlyToolName(p.toolName)

    private fun isBareToolName(text: String, p: SidecarMessage.Permission): Boolean =
        text.trim().equals(p.toolName, ignoreCase = true)

    /**
     * 工具名 → 人话。只认确知的那几个；其余原样。
     */
    private fun friendlyToolName(toolName: String): String = when (toolName) {
        // 模型写完计划、请求退出"仅规划"模式开始干活。它的入参就是那份计划，
        // 用户此刻要做的就是读计划 + 决定是否放行
        EXIT_PLAN_MODE_TOOL -> "退出计划模式"
        else -> toolName
    }

    /**
     * 「允许」那颗按钮上写什么。
     *
     * sdk.d.ts:234-238 说 displayName 是"给按钮用的动作短语"（例子是 `"Read file"`），
     * 所以原先直接拿它当按钮文案。但 2026-09-15 用户看到的按钮上写着**「Bash」** ——
     * CLI 对内置工具给的就是工具名本身，于是那颗按钮读起来像"点它就运行 Bash"，
     * 而不是"允许这一次"。（同一天 AutoAllowRenderProbe 与 PermissionCardTest 的
     * 夹具里都写着 `displayName = "允许"`，所以两处都没能发现 —— 夹具比现实好看。）
     *
     * 规则：displayName 只有**确实在描述动作**（与工具名不同）时才用它。这样 MCP
     * 工具那种 `_meta['anthropic/permissionDisplay'].displayName` 的价值留着，
     * 而退化成工具名的情况回落到「允许」。
     */
    fun allowLabel(p: SidecarMessage.Permission): String =
        p.displayName?.takeIf { it.isNotBlank() && !it.equals(p.toolName, ignoreCase = true) }
            ?: "允许"
}

/** `ExitPlanMode` 的工具名。SDK 那边的字面量，拼错就永远匹配不上。 */
internal const val EXIT_PLAN_MODE_TOOL = "ExitPlanMode"

/** 计划文本在入参里的字段名（实测：`{"plan":"…","planFilePath":"…"}`）。 */
internal const val PLAN_FIELD = "plan"

/**
 * 权限框里那段「输入」该怎么显示。
 *
 * @param caption 顶部那行小字（「原始输入」/「计划内容」）
 * @param text 文本区里的正文 —— 换行是**真换行**
 * @param rows 文本区的行数
 * @param maxHeight 文本区的高度上限（未缩放 px）：长正文给得高一些，仍可滚动
 */
internal data class PermissionBody(
    val caption: String,
    val text: String,
    val rows: Int,
    val maxHeight: Int,
    /**
     * 正文是 **Markdown**（目前只有 `plan` 那一档）：卡片改走 HTML 渲染
     * （见 [planHtml] 与 `docs/design/plan-markdown.html` 的方案 B）。
     *
     * 只有计划走这条：别的长字段（Bash 的 command 那种）是**代码或 JSON**，
     * 按 Markdown 渲染只会平白把 `#`、`*` 当记号 —— 那比不渲染更糟。
     */
    val markdown: Boolean = false,
    /**
     * 附在正文**下方**的那一段（「其余参数」的缩进 JSON）；没有就是 null。
     *
     * 从前它拼在 `text` 尾巴上。分开是因为计划改走 HTML 之后，它会以**正文字体**
     * 混在被渲染的段落里 —— 一段 JSON 看着像计划正文，比排版难看严重。
     */
    val footer: String? = null,
)

/** 超过这个长度就算"正文"，不再塞进 JSON 里当一行字符串。 */
private const val LONG_FIELD_MIN_CHARS = 200

/**
 * 把权限询问的入参翻译成**能读的一段字**。
 *
 * ## 为什么不能直接 `input.toString()`
 *
 * 2026-09-15 用户截了 `ExitPlanMode` 的审批框来问"里面的内容都看不到"。
 * 那条入参是 `{"plan": "<3408 字的计划，含真换行>", "planFilePath": "…"}` ——
 * Gson 的 `toString()` 把它压成**一行**、换行变成字面 `\n`，再塞进一个 3 行高的
 * 滚动框。而那个框里装的正是用户要批准的东西：**看不到内容的审批不是审批，
 * 是让人闭眼点按钮。**
 *
 * ## 规则
 *
 * 入参里最长的那个字符串字段，只要够长或带换行，就把它**按文本铺开**
 * （`plan` 这类字段本来就是给人读的段落）；其余字段按缩进 JSON 附在后面，
 * 一样都不藏 —— 审批框里截断信息比排版难看严重得多。没有这样的字段
 * （比如 Bash 那种 `command` + `description` 的短入参）就整份缩进 JSON。
 */
internal fun permissionBody(input: JsonObject): PermissionBody {
    val field = longTextField(input)
    if (field == null) {
        return PermissionBody(INPUT_CAPTION, prettyJson(input), GENERIC_ROWS, GENERIC_MAX_HEIGHT)
    }

    val rest = JsonObject().apply {
        input.entrySet().filter { it.key != field.key }.forEach { add(it.key, it.value) }
    }
    return PermissionBody(
        // 计划是这一档里唯一有专名的：它同时回答了"这是在批准什么"
        caption = if (field.key == PLAN_FIELD) PLAN_CAPTION else INPUT_CAPTION,
        text = field.value.asString,
        rows = LONG_ROWS,
        maxHeight = LONG_MAX_HEIGHT,
        markdown = field.key == PLAN_FIELD,
        footer = if (rest.size() > 0) "其余参数：\n${prettyJson(rest)}" else null,
    )
}

/** 长正文按文本铺开时用的那几个数。 */
private const val INPUT_CAPTION = "原始输入"
private const val PLAN_CAPTION = "计划内容"
private const val GENERIC_ROWS = 3
private const val GENERIC_MAX_HEIGHT = 80
private const val LONG_ROWS = 12
private const val LONG_MAX_HEIGHT = 320

/**
 * 入参里最长的那个字符串字段 —— 但只有它够长或带换行时才认。
 *
 * 判据不写死字段名：`plan` 是实测到的那一个，而这类"正文型入参"以后还会有
 * （写文件、多行脚本）。按形状认，比重一个白名单耐得住。
 */
private fun longTextField(input: JsonObject): Map.Entry<String, JsonElement>? =
    input.entrySet()
        .filter { it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString }
        .maxByOrNull { it.value.asString.length }
        ?.takeIf { (it.value.asString.length >= LONG_FIELD_MIN_CHARS) || ('\n' in it.value.asString) }

/**
 * 缩进 JSON。
 *
 * `disableHtmlEscaping`：Gson 默认把 `<` `>` `&` 转成 `<` 之类，那是给
 * 网页用的防御。这里是 Swing 的文本区，转义只会让命令和代码更难读。
 */
private fun prettyJson(element: JsonElement): String =
    PRETTY_GSON.toJson(element)

private val PRETTY_GSON: Gson =
    GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
