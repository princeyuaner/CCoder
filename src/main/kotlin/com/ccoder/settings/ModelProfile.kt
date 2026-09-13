package com.ccoder.settings

import java.util.UUID

/**
 * 认证变量的两种语义。
 *
 * 猜错就是 401，而 401 看起来和"密钥填错了"一模一样 —— 用户会去反复检查密钥。
 * 所以存成显式字段：推断只用于新建时给个默认值（见 [defaultAuthKind]）。
 */
enum class AuthKind {
    /** `x-api-key`。官方 Anthropic 用。 */
    API_KEY,

    /** `Authorization: Bearer`。多数第三方网关用。 */
    AUTH_TOKEN,
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
    var modelId: String = "",
    var authKind: String = AuthKind.API_KEY.name,
)

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
        throw ModelProfileIncomplete(
            "模型「${profile.name}」填了 Base URL（$url）却没有密钥。\n" +
                "第三方端点必须有密钥 —— 否则请求会安静地发出去然后失败。"
        )
    }

    return mapOf(
        "ANTHROPIC_BASE_URL" to url,
        profile.authKindEnum().envVarName() to key,
    )
}

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
 */
fun conflictingEnvKeys(envOverrides: Map<String, String>): List<String> =
    envOverrides.keys.filter { it in MODEL_ENV_KEYS }.sorted()

private val MODEL_ENV_KEYS = setOf(
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
)
