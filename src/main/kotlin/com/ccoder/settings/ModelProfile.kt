package com.ccoder.settings

import java.util.UUID

/**
 * 认证变量的两种语义。
 *
 * 猜错就是 401，而 401 看起来和"密钥填错了"一模一样 —— 用户会去反复检查密钥。
 * 所以存成显式字段：推断只用于新建时给个默认值（见 [defaultAuthKind]）。
 *
 * [label] 是给下拉框看的。**不覆写 `toString()`** —— 那会把日志和调试输出里的
 * `API_KEY` 也一起改成"API Key"，出问题时反而少了线索。
 */
enum class AuthKind(val label: String) {
    /** `x-api-key`。官方 Anthropic 用。 */
    API_KEY("API Key"),

    /** `Authorization: Bearer`。多数第三方网关用。 */
    AUTH_TOKEN("Bearer"),
}

/**
 * 一条模型配置。
 *
 * **密钥不在这里。** 这个结构会被 XmlSerializer 写进 XML，密钥只进 PasswordSafe，
 * 这里留 [id] 做引用 —— 名称和端点都可改，改名不该让密钥失联。
 *
 * 属性一律 `var` + 默认值、枚举存 `.name` 字符串：这是 XmlSerializer 认的形状，
 * 照 `ClaudeSettings.State` 的先例（那边 `permissionMode` 也是存名字的）。
 */
data class ModelProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var baseUrl: String = "",
    /**
     * 这条配置可用的模型，第一个是新建/兜底时的那个。
     *
     * 空 = 这条配置**不指定模型** —— 官方端点上这是合法的（用 CLI 默认模型），
     * 第三方端点上则要靠 [modelId] 与路由变量，所以别把空当成"没配好"。
     *
     * 类型是 [MutableList] 而不是 `List`：XmlSerializer 认的是可变集合。
     */
    var modelIds: MutableList<String> = mutableListOf(),
    /**
     * 当前用的那个。**不变量：要么是空串，要么落在 [modelIds] 里** ——
     * 见 [normalizeModelProfile]。
     *
     * 它同时是**老 XML 的迁移落点**：改版前一条配置只有一个模型，就存在这个
     * 字段里，且没有 [modelIds]。那份数据不能丢（用户配好的模型名会静默消失），
     * 所以这个字段的名字与类型都没动，只在 [ModelProfiles.loadState] 里多一步
     * 把它补进列表。
     */
    var modelId: String = "",
    var authKind: String = AuthKind.API_KEY.name,
)

/**
 * 把一条配置收敛成不变量成立的样子。
 *
 * 不变量只有一条：**[ModelProfile.modelId] 要么空、要么在 [ModelProfile.modelIds] 里**。
 * 破了它的后果不是崩溃，是标签显示一个候选列表里根本没有的模型 —— 用户点开
 * 弹层找不到自己正在用的那个。
 *
 * 由 [ModelProfiles.loadState] 与 [ModelProfiles.upsert] 两个入口调用。别处不许
 * 自己维护这条不变量：入口只有一个，才谈得上"绕不过去"。
 *
 * **列表为空时 `modelId` 会被清掉**，不是被当成唯一的候选。这一条是刻意的：
 * 反过来做（空列表时把 `modelId` 补进列表）看着更"宽容"，实际会让"删掉最后
 * 一个模型"当场把它复活 —— 用户删不掉东西是最难解释的一类 bug。
 * 代价是老 XML 的迁移**不能指望这个函数**，必须在 [ModelProfiles.loadState]
 * 里显式做那一步。
 */
fun normalizeModelProfile(profile: ModelProfile): ModelProfile {
    val current = profile.modelId.trim()
    val ids = profile.modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return profile.copy(
        modelIds = ids.toMutableList(),
        modelId = if (current in ids) current else ids.firstOrNull() ?: "",
    )
}

/**
 * 能不能**不重开会话**就换过去。
 *
 * 判据只有一条：端点与凭证那批环境变量变不变。`options.env` 在子进程 spawn 时就
 * 烤死了，中途改不了 —— 所以只要它一样，换模型就只是把 `set_model` 发下去，
 * 会话、上下文、转写全都留着。
 *
 * 同一条配置内换模型必然为 true。两条**填了同样端点与密钥**的配置之间也是 true，
 * 这是有意的：把一个网关拆成几条来管是完全合理的用法，那种切换没理由丢上下文。
 *
 * [from] 为 null 表示"起会话时没选中任何配置"（走 CLI 自己的登录态与 settings），
 * 它的环境变量按空集算 —— 所以从"没配置"切到"一条什么都没给的官方配置"同样是热切换。
 *
 * [modelProfileEnv] 在"第三方端点没填密钥"时会抛。这里捕获后返回 false，即
 * "证明不了它一样就重开"—— 重开那条路本来就会把这条错误报出来（见
 * `ClaudePanel.startSession` 的 catch），不会吞掉。**两个都抛也不能算相等**，
 * 所以不能写成 `runCatching{}.getOrNull() == runCatching{}.getOrNull()`。
 *
 * **目标模型是空串时也不热切**：`setModel` 表达不了"不要模型"这个状态 ——
 * `setModel(undefined)` 不是"清除"，空串会变成一个空的模型名发出去。所以
 * "切到一条没配模型的配置"只能走重开（那条路的 `toStartParams` 会把它变成
 * "不传 `--model`"，正是想要的语义）。
 */
fun canHotSwitch(
    from: ModelProfile?,
    fromSecret: String,
    to: ModelProfile,
    toSecret: String,
): Boolean {
    if (to.modelId.isBlank()) return false
    val before = if (from == null) {
        emptyMap()
    } else {
        runCatching { modelProfileEnv(from, fromSecret) }.getOrNull() ?: return false
    }
    val after = runCatching { modelProfileEnv(to, toSecret) }.getOrNull() ?: return false
    return before == after
}

/** [authKind] 那份字符串对应的枚举。坏值退回 [AuthKind.API_KEY] —— XML 是手可改的。 */
fun ModelProfile.authKindEnum(): AuthKind =
    AuthKind.entries.firstOrNull { it.name == authKind } ?: AuthKind.API_KEY

/**
 * 新建时的默认认证方式。
 *
 * 空 `baseUrl` 是官方端点（用 API_KEY），非空多半是第三方网关（用 Bearer）。
 * **只是默认值** —— 存下来之后可以改。
 */
fun defaultAuthKind(baseUrl: String): AuthKind =
    if (baseUrl.isBlank()) AuthKind.API_KEY else AuthKind.AUTH_TOKEN

/** 这个认证方式该设哪个环境变量。 */
fun AuthKind.envVarName(): String = when (this) {
    AuthKind.API_KEY -> "ANTHROPIC_API_KEY"
    AuthKind.AUTH_TOKEN -> "ANTHROPIC_AUTH_TOKEN"
}

/** [modelProfileEnv] 拒绝了一条第三方配置 —— 它有端点却没密钥。 */
class ModelProfileIncomplete(message: String) : Exception(message)

/**
 * profile → 环境变量（spec §5 / §5.1）。
 *
 * 四支行里有两支刻意不产出任何认证变量，见 spec 的那张表。**官方端点不填密钥
 * 是合法的**（用户可能已经 `claude login` 过），而第三方端点没密钥必须报错 ——
 * 否则我们会安静地发一个空令牌出去，症状是一次没有解释的 401。
 *
 * @param secret 从 PasswordSafe 解出的密钥；官方端点下可为空
 */
fun modelProfileEnv(profile: ModelProfile, secret: String): Map<String, String> {
    val url = profile.baseUrl.trim()
    val key = secret.trim()

    if (url.isEmpty()) {
        // 官方端点：不填密钥 = 用 CLI 登录态，什么都不设
        return if (key.isEmpty()) emptyMap() else mapOf("ANTHROPIC_API_KEY" to key)
    }

    if (key.isEmpty()) {
        // 名字用 displayName() 而不是 name：只填了 modelId 的配置（很常见 ——
        // 名字是想起来才补的）会显示成「模型「」填了 Base URL…」，
        // 而这是产品里最需要"说人话"的那条消息
        throw ModelProfileIncomplete(
            "模型「${profile.displayName()}」填了 Base URL（$url）却没有密钥。\n" +
                "第三方端点必须有密钥 —— 否则请求会安静地发出去然后失败。"
        )
    }

    return mapOf(
        "ANTHROPIC_BASE_URL" to url,
        profile.authKindEnum().envVarName() to key,
    )
}

/**
 * 黑名单里**唯一**允许插件重新引入的那一项（名单见 `sidecar/env.js`）。
 *
 * 拼在 Kotlin 这一侧而不是写在 env.js：决定"这条会话归谁管"的是选中的配置，
 * 而 sidecar 只负责放行 —— 两边都不许私自改名。
 */
const val HOST_MANAGED_PROVIDER_VAR = "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST"

/**
 * "这条会话的端点与凭证归选中的配置管" 的开关（spec §6.1）。
 *
 * 为什么需要它：CLI 会拿 `~/.claude/settings.json` 里的 `env` **覆盖**进程环境
 * （overwrite 语义）。选中一条指向 A 网关的配置、而 settings.json 里写着 B 网关时，
 * 端点被 settings.json 抢走、配置的密钥却照样发得出去 —— 症状是一次
 * `401 Invalid token`（2026-09-14 实测，见 spec §6.1）。
 *
 * 打开它之后 CLI 反过来把 settings 来源的 `ANTHROPIC_BASE_URL` /
 * `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_CUSTOM_HEADERS`
 * 一律剥掉，选中的配置即唯一来源。
 *
 * **只在配置真的给了东西时才设**：一条什么都没给的配置（官方端点 + 没填密钥，
 * 即 [profileEnv] 为空）要保持"用 CLI 登录态 / 跟着 settings.json 走"的旧行为 ——
 * 那种情况下打这个开关会把 settings 里的凭证也一并剥掉，只剩
 * authentication_failed（spec §11.1 那次实测）。
 */
fun providerOwnershipEnv(profileEnv: Map<String, String>): Map<String, String> =
    if (profileEnv.isEmpty()) emptyMap() else mapOf(HOST_MANAGED_PROVIDER_VAR to "1")

/**
 * 要显式给全的那几个名字（spec §6.1）。
 *
 * 与 CCG（`idea-claude-code-gui`）的 `MODEL_ROUTING_ENV_VARS` 同一批，
 * 只少了 `ANTHROPIC_MODEL`：主模型走 `options.model`，而这个变量在 `env.js`
 * 的宿主隔离黑名单里，不该由插件重新引入（实测 `--model` 不会被 settings 里的
 * `ANTHROPIC_MODEL` 顶掉，见 spec §6.1 的对照表）。
 */
internal val ROUTING_MODEL_ENV_VARS = listOf(
    "ANTHROPIC_DEFAULT_FABLE_MODEL",
    "ANTHROPIC_DEFAULT_OPUS_MODEL",
    "ANTHROPIC_DEFAULT_SONNET_MODEL",
    "ANTHROPIC_DEFAULT_HAIKU_MODEL",
    "ANTHROPIC_SMALL_FAST_MODEL",
    "CLAUDE_CODE_SUBAGENT_MODEL",
)

/**
 * 别名与后台任务的"等价模型名"（spec §6.1）。
 *
 * 选中配置后 CLI 会把 settings 来源的模型路由变量一并剥掉，于是
 * `ANTHROPIC_DEFAULT_*` / `ANTHROPIC_SMALL_FAST_MODEL` 就没人给了 —— CLI 落回
 * 它自带的 Claude 模型名，而第三方网关上多半没有那些名字。症状会很有欺骗性：
 * **主对话一切正常**，只有起标题 / 压缩上下文 / 子代理这类后台活儿报模型不存在。
 *
 * 所以这里显式给全：一整族别名 + 小快模型 + 子代理，全部指到配置的 `modelId`。
 * 网关后面通常就一个模型，"等价的模型名"就是它。
 *
 * **只对第三方端点做**（`baseUrl` 非空）：官方端点上那些 Claude 名字本来就有效，
 * 把 haiku 指到 opus 只会让后台小活儿变贵。
 *
 * @return 空 map = "没得给"：没有 `baseUrl`，或者配置压根没填 `modelId`
 */
fun routingModelEnv(profile: ModelProfile): Map<String, String> {
    val id = profile.modelId.trim()
    if (profile.baseUrl.isBlank() || id.isEmpty()) return emptyMap()
    return ROUTING_MODEL_ENV_VARS.associateWith { id }
}

/**
 * 任务清单工具的开关（spec §6.1，`2026-09-13-status-cards-design.md`）。
 *
 * 拼在 Kotlin 侧的理由同 [HOST_MANAGED_PROVIDER_VAR]：决定给不给的是插件，
 * sidecar 只负责别挡（这个键不在黑名单里，本来也挡不着）。
 */
internal const val TASK_TOOLS_VAR = "CLAUDE_CODE_ENABLE_TODO_TOOLS"

/**
 * 让模型拿到任务清单工具（`TaskCreate` / `TaskUpdate` / `TaskList` / `TaskGet`）。
 *
 * CLI 2.1.268 默认**只对它能识别的模型**给这套工具；第三方网关上那些名字
 * （`deepseek-*` 之类）在它眼里属于"不认识"，要这个变量为真才给。实测：
 * 不加 24 个工具，加了 28 个（多出来的正是那四个）。
 *
 * 不给的话「任务列表」卡永远是空的 —— 不是模型不用，是它根本调不到。
 *
 * **语义是默认值，不是硬规则**：手填过这个键就原样听手填的（用户想关就关）。
 * 这一点与端点/凭证那批刻意不同 —— 那些是正确性（混搭会 401），这个是工具面偏好。
 */
internal fun taskToolsEnv(userOverrides: Map<String, String>): Map<String, String> =
    if (TASK_TOOLS_VAR in userOverrides) emptyMap() else mapOf(TASK_TOOLS_VAR to "true")

/**
 * 界面上写什么。两级回退：名字 → 模型 ID → "未命名"。
 *
 * 放在这里而不是 ui 包的标签类里：显示名是数据的属性，而且 `settings` 包
 * **不该反向依赖 `ui`** —— 设置对话框（同属 settings）也要用它。
 */
fun ModelProfile.displayName(): String =
    name.trim().ifEmpty { modelId.trim() }.ifEmpty { "未命名" }

/**
 * 把选中的模型配置产生的环境变量并进用户的 `envOverrides`。
 *
 * **profile 优先** —— `Map.plus` 的右侧覆盖左侧。理由见 spec §6：
 * profile 是显式选择，`envOverrides` 是背景设置，背景不该盖过当次选择。
 */
fun mergeProfileEnv(
    base: Map<String, String>,
    profileEnv: Map<String, String>,
): Map<String, String> = base + profileEnv

/**
 * `envOverrides` 里和模型配置抢同一批变量的键。
 *
 * 有冲突不是错误（profile 会赢，见上），但用户得知道 —— 否则他会对着一个
 * "改了却不生效"的 envOverrides 发懵。spec §6 要求把这条提示做进模型页。
 *
 * 名单含路由变量（[ROUTING_MODEL_ENV_VARS]）：选中第三方配置时它们也会被
 * 指到配置的 `modelId`，手填的值同样会被盖掉。
 */
fun conflictingEnvKeys(envOverrides: Map<String, String>): List<String> =
    envOverrides.keys.filter { it in MODEL_ENV_KEYS }.sorted()

private val MODEL_ENV_KEYS = setOf(
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
) + ROUTING_MODEL_ENV_VARS
