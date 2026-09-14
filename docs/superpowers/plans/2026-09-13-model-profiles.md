# 设置入口与多模型配置 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 CCoder 能配置多条自带端点与密钥的模型、在输入框左下角一键切换，并新增一个带页签的设置对话框作为它们的家。

**Architecture:** 配置与密钥分家 —— 配置进一个 **Application 级** `PersistentStateComponent`（全局共享），密钥进 `PasswordSafe`，配置里只留 `id` 做引用。选中的 profile 在 `toStartParams` 里被翻译成 `ANTHROPIC_BASE_URL` / `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN`，**合并进已有的 `envOverrides` 字段** —— 因为 `Protocol.encodeStart` 已经会序列化它，所以协议、`sidecar/env.js`、`sidecar/session.js` 全都不用动。

**Tech Stack:** Kotlin（IntelliJ Platform 插件）、`DialogWrapper`、`PasswordSafe`、JUnit 5、JCEF/React（仅既有，本次不改前端）

**Spec:** `docs/superpowers/specs/2026-09-13-model-profiles-design.md`
**设计稿:** `docs/design/model-settings.html`（选定方案 A）

## Global Constraints

- **列表为空时行为与今天完全一致。** 没有选中任何 profile → `model` 与 `envOverrides` 原样透传，不产生任何新环境变量。这不是一次迁移。
- **密钥绝不进 XML。** `ModelProfile` 会被序列化，所以它不能有密钥字段；密钥只经 `PasswordSafe`。
- **profile 的 env 覆盖 `envOverrides`**（`Map.plus` 的右侧优先）。
- **认证方式显式存，不运行时猜。** 推断只用于新建时给默认值。
- **`sidecar/env.js` 与 `sidecar/session.js` 不动。** `env.js` 的黑名单只拦 `ANTHROPIC_MODEL`，不碰我们要用的三个变量。
- **持久化属性一律 `var` + 默认值，枚举存 `.name` 字符串** —— 照 `ClaudeSettings.State` 的先例（`ClaudeSettings.kt:82-90`），`XmlSerializer` 对 `val` 和枚举类型的支持不可靠。
- **服务类必须是 public `class`**，不能用 `internal` —— 平台靠反射实例化（照 `ClaudeSettings.kt:80`）。
- 测试命令（三侧分别跑，互不覆盖）：
  - Kotlin：`./gradlew test --console=plain`
  - 侧车：`cd sidecar && node --test`（**不带参数**，`node --test test/` 在 Node 24 上必挂）
  - web：`cd web && node node_modules/vitest/vitest.mjs run`
- 提交信息：conventional commits + 中文正文，结尾 `Co-Authored-By: Claude Code <noreply@anthropic.com>`

---

### Task 1: 数据模型与纯函数

**Files:**
- Create: `src/main/kotlin/com/ccoder/settings/ModelProfile.kt`
- Test: `src/test/kotlin/com/ccoder/settings/ModelProfileTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class AuthKind { API_KEY, AUTH_TOKEN }`
  - `data class ModelProfile(id, name, baseUrl, modelId, authKind)` —— 全部 `var` + 默认值
  - `fun defaultAuthKind(baseUrl: String): AuthKind`
  - `fun AuthKind.envVarName(): String`
  - `fun modelProfileEnv(profile: ModelProfile, secret: String): Map<String, String>`
  - `class ModelProfileIncomplete(message: String) : Exception`
  - `fun ModelProfile.displayName(): String`

- [ ] **Step 1: 写失败的测试**

新建 `src/test/kotlin/com/ccoder/settings/ModelProfileTest.kt`：

```kotlin
package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelProfileTest {

    @Test
    fun `官方端点不填密钥不产出任何变量`() {
        // 用户可能已经 claude login 过 —— 官方端点下这是完全合法的配置，
        // 不是"缺了密钥"。什么都不设，让 CLI 走它自己的登录态
        val env = modelProfileEnv(ModelProfile(name = "官方"), secret = "")

        assertTrue(env.isEmpty(), "官方端点 + 无密钥 = 用 CLI 登录态，实际：$env")
    }

    @Test
    fun `官方端点填了密钥产出 API_KEY 且不产出 BASE_URL`() {
        val env = modelProfileEnv(ModelProfile(name = "官方"), secret = "sk-ant-x")

        assertEquals(mapOf("ANTHROPIC_API_KEY" to "sk-ant-x"), env)
        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"), "空 baseUrl 不该产出 BASE_URL")
    }

    @Test
    fun `第三方端点产出 BASE_URL 与按 authKind 选的变量`() {
        val env = modelProfileEnv(
            ModelProfile(
                name = "中转",
                baseUrl = "https://api.example.com",
                authKind = AuthKind.AUTH_TOKEN.name,
            ),
            secret = "tok",
        )

        assertEquals(
            mapOf(
                "ANTHROPIC_BASE_URL" to "https://api.example.com",
                "ANTHROPIC_AUTH_TOKEN" to "tok",
            ),
            env,
        )
    }

    @Test
    fun `API_KEY 与 AUTH_TOKEN 产出不同的变量名`() {
        // 这两个变量语义不同（x-api-key vs Authorization: Bearer），
        // 猜错就是 401，而 401 看起来和"密钥填错了"一模一样
        val base = ModelProfile(name = "中转", baseUrl = "https://api.example.com")

        val asKey = modelProfileEnv(base.copy(authKind = AuthKind.API_KEY.name), "k")
        val asToken = modelProfileEnv(base.copy(authKind = AuthKind.AUTH_TOKEN.name), "k")

        assertTrue(asKey.containsKey("ANTHROPIC_API_KEY"), asKey.toString())
        assertFalse(asKey.containsKey("ANTHROPIC_AUTH_TOKEN"), asKey.toString())
        assertTrue(asToken.containsKey("ANTHROPIC_AUTH_TOKEN"), asToken.toString())
        assertFalse(asToken.containsKey("ANTHROPIC_API_KEY"), asToken.toString())
    }

    @Test
    fun `第三方端点没有密钥时报错并点名是哪个模型`() {
        val ex = assertThrows(ModelProfileIncomplete::class.java) {
            modelProfileEnv(ModelProfile(name = "中转站", baseUrl = "https://x"), secret = "")
        }

        assertTrue(ex.message!!.contains("中转站"), "错误信息要点名模型，实际：${ex.message}")
    }

    @Test
    fun `默认认证方式按 baseUrl 是否为空推断`() {
        assertEquals(AuthKind.API_KEY, defaultAuthKind(""))
        assertEquals(AuthKind.API_KEY, defaultAuthKind("   "))
        assertEquals(AuthKind.AUTH_TOKEN, defaultAuthKind("https://api.example.com"))
    }

    @Test
    fun `两端空白被裁掉`() {
        val env = modelProfileEnv(
            ModelProfile(name = "x", baseUrl = "  https://api.example.com  "),
            secret = "  tok  ",
        )

        assertEquals("https://api.example.com", env["ANTHROPIC_BASE_URL"])
        assertEquals("tok", env["ANTHROPIC_AUTH_TOKEN"])
    }

    @Test
    fun `authKind 存的名字坏了时不崩，退回 API_KEY`() {
        // XML 是手可改的，坏了不该让整个面板起不来
        val p = ModelProfile(name = "x", baseUrl = "https://y", authKind = "BOGUS")

        assertEquals(AuthKind.API_KEY, p.authKindEnum())
    }

    @Test
    fun `displayName 的两级回退`() {
        assertEquals("中转 Opus", ModelProfile(name = "中转 Opus").displayName())
        assertEquals(
            "deepseek-flash",
            ModelProfile(name = "  ", modelId = "deepseek-flash").displayName(),
            "只填了 modelId 就存了的配置，标签不该是空的",
        )
        assertEquals("未命名", ModelProfile().displayName())
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `./gradlew test --tests "*ModelProfileTest*" --console=plain`
Expected: 编译失败 —— `ModelProfile` / `modelProfileEnv` 未定义

- [ ] **Step 3: 实现**

新建 `src/main/kotlin/com/ccoder/settings/ModelProfile.kt`：

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests "*ModelProfileTest*" --console=plain`
Expected: BUILD SUCCESSFUL，9 条用例

- [ ] **Step 5: 确认用例真的跑了**

Run: `python -c "import glob,xml.etree.ElementTree as ET; [print(ET.parse(f).getroot().get('tests'), ET.parse(f).getroot().get('failures')) for f in glob.glob('build/test-results/test/TEST-*ModelProfileTest*.xml')]"`
Expected: `9 0 0 0`（tests / failures / errors / skipped）—— BUILD SUCCESSFUL 不等于用例被
选中执行，这一步是防它被 Gradle 过滤掉

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/ccoder/settings/ModelProfile.kt src/test/kotlin/com/ccoder/settings/ModelProfileTest.kt
git commit -m "feat(settings): 模型配置的数据模型与环境变量映射"
```

---

### Task 2: 持久化与密钥

**Files:**
- Create: `src/main/kotlin/com/ccoder/settings/ModelProfiles.kt`
- Test: `src/test/kotlin/com/ccoder/settings/ModelProfilesTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ModelProfile`、`AuthKind`
- Produces:
  - `class ModelProfiles : PersistentStateComponent<ModelProfiles.State>`（Application 级）
  - `ModelProfiles.getInstance(): ModelProfiles`
  - `fun profiles(): List<ModelProfile>`
  - `fun selected(): ModelProfile?`
  - `fun select(id: String?)`
  - `fun upsert(profile: ModelProfile)`
  - `fun remove(id: String)`
  - `fun secretOf(id: String): String`
  - `fun setSecret(id: String, secret: String)`

- [ ] **Step 1: 写失败的测试**

新建 `src/test/kotlin/com/ccoder/settings/ModelProfilesTest.kt`。**注意**：`PasswordSafe` 在纯单测环境里没有实现，所以核心逻辑要能脱离它测 —— 这正是把密钥读写包成 `SecretStore` 接口的原因。

```kotlin
package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 内存版密钥库，让单测不必碰 PasswordSafe。 */
private class FakeSecretStore : SecretStore {
    val map = mutableMapOf<String, String>()
    override fun read(id: String): String = map[id] ?: ""
    override fun write(id: String, secret: String) {
        if (secret.isEmpty()) map.remove(id) else map[id] = secret
    }
}

class ModelProfilesTest {

    private fun fresh(store: SecretStore = FakeSecretStore()): ModelProfiles =
        ModelProfiles(store)

    @Test
    fun `增删改查走一遍`() {
        val m = fresh()
        val a = ModelProfile(name = "A", baseUrl = "https://a", modelId = "m-a")

        m.upsert(a)
        assertEquals(1, m.profiles().size)
        assertEquals("A", m.profiles().first().name)

        m.upsert(a.copy(name = "A2"))
        assertEquals(1, m.profiles().size, "同 id 是修改不是新增")
        assertEquals("A2", m.profiles().first().name)

        m.remove(a.id)
        assertTrue(m.profiles().isEmpty())
    }

    @Test
    fun `删掉当前选中的那条时选中态跟着清空`() {
        val m = fresh()
        val a = ModelProfile(name = "A")
        m.upsert(a)
        m.select(a.id)
        assertEquals(a.id, m.selected()?.id)

        m.remove(a.id)

        assertNull(m.selected(), "选中一条已经不存在的配置会让启动路径读到一个空引用")
        assertNull(m.selectedId())
    }

    @Test
    fun `删掉一条时密钥一并清掉`() {
        // 留着密钥不只是垃圾 —— 以后新增一条会拿到新的 UUID，
        // 旧密钥永远没人再读，也永远不会被发现
        val store = FakeSecretStore()
        val m = fresh(store)
        val a = ModelProfile(name = "A")
        m.upsert(a)
        m.setSecret(a.id, "top-secret")
        assertEquals("top-secret", m.secretOf(a.id))

        m.remove(a.id)

        assertEquals("", store.read(a.id), "密钥要跟着配置一起删")
    }

    @Test
    fun `选一个不存在的 id 等于没选`() {
        val m = fresh()
        m.select("nope")
        assertNull(m.selected())
    }

    @Test
    fun `persist 出去的字符串里没有密钥`() {
        // spec §4 的硬要求：密钥不落盘明文。这条对着**产出的数据**断言，
        // 不是对着字段名断言 —— 后者证明不了什么
        val m = fresh()
        val a = ModelProfile(name = "A", baseUrl = "https://a")
        m.upsert(a)
        m.setSecret(a.id, "sk-should-not-appear")

        val serialized = m.serializedForTest()

        assertFalse(
            serialized.contains("sk-should-not-appear"),
            "密钥出现在了持久化数据里：$serialized",
        )
        assertTrue(serialized.contains(a.id), "id 必须留下 —— 密钥靠它找回")
    }

    @Test
    fun `空白密钥不写入`() {
        val store = FakeSecretStore()
        val m = fresh(store)
        val a = ModelProfile(name = "A")
        m.upsert(a)

        m.setSecret(a.id, "   ")

        assertEquals("", store.read(a.id), "空白密钥该当成清除，不是一个空口令")
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `./gradlew test --tests "*ModelProfilesTest*" --console=plain`
Expected: 编译失败 —— `SecretStore` / `ModelProfiles` 未定义

- [ ] **Step 3: 实现**

新建 `src/main/kotlin/com/ccoder/settings/ModelProfiles.kt`：

```kotlin
package com.ccoder.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 密钥的读写口。
 *
 * 抽成接口是为了让单测不必碰 PasswordSafe —— 它在纯单测环境里没有实现，
 * 直接调用会让所有用例一起挂掉。生产实现是 [PasswordSafeSecretStore]。
 */
interface SecretStore {
    fun read(id: String): String
    fun write(id: String, secret: String)
}

/** 生产实现。key 由 profile 的 id 生成，与配置一一对应。 */
object PasswordSafeSecretStore : SecretStore {

    private fun attrs(id: String) =
        CredentialAttributes(generateServiceName("CCoder", id))

    override fun read(id: String): String =
        PasswordSafe.instance.getPassword(attrs(id)) ?: ""

    override fun write(id: String, secret: String) {
        // 空口令与"没有这条"在界面上是两回事，但对读取方是一回事 —— 统一存成"没有"
        if (secret.isBlank()) PasswordSafe.instance.set(attrs(id), null)
        else PasswordSafe.instance.set(attrs(id), Credentials(null, secret))
    }
}

/**
 * 模型配置列表。**Application 级** —— 同一套中转站通常到处都在用，
 * 换个项目不该重配端点与密钥（spec §4 已定）。
 *
 * 类必须是 public：平台靠反射实例化它（照 `ClaudeSettings` 的先例）。
 */
@State(name = "CCoderModelProfiles", storages = [Storage("ccoderModelProfiles.xml")])
@Service(Service.Level.APP)
class ModelProfiles(private val secrets: SecretStore) : PersistentStateComponent<ModelProfiles.State> {

    /**
     * 平台靠**无参构造**实例化服务（反射）。
     *
     * 不能图省事写成 `secrets: SecretStore = PasswordSafeSecretStore` —— Kotlin 的
     * 默认参数只生成合成构造器，反射找不到无参那一份，服务注册会静默失败，
     * 症状是 `getService` 直接抛异常。
     */
    constructor() : this(PasswordSafeSecretStore)

    data class State(
        var profiles: MutableList<ModelProfile> = mutableListOf(),
        var selectedId: String? = null,
    )

    private var myState = State()

    /**
     * 密钥的内存缓存。
     *
     * `toStartParams` 会在 EDT 上被调用（`sendStart` 的两条路里有一条在 EDT），
     * 每次去 PasswordSafe 同步读一次是拿 UI 线程在等 I/O。读一次记住即可 ——
     * 唯一的写入方是本类的 [setSecret]，它负责让缓存与存储同步。
     */
    private val secretCache = mutableMapOf<String, String>()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        secretCache.clear()
    }

    fun profiles(): List<ModelProfile> = myState.profiles.toList()

    fun selectedId(): String? = myState.selectedId

    fun selected(): ModelProfile? =
        myState.profiles.firstOrNull { it.id == myState.selectedId }

    /** 传一个不存在的 id 等于没选 —— 免得启动路径读到一个空引用。 */
    fun select(id: String?) {
        myState.selectedId = id?.takeIf { wanted -> myState.profiles.any { it.id == wanted } }
    }

    fun upsert(profile: ModelProfile) {
        val i = myState.profiles.indexOfFirst { it.id == profile.id }
        if (i >= 0) myState.profiles[i] = profile else myState.profiles.add(profile)
    }

    fun remove(id: String) {
        myState.profiles.removeAll { it.id == id }
        // 选中态跟着走 —— 留一个指向已删配置的 selectedId，
        // 启动时会静默地什么都不应用
        if (myState.selectedId == id) myState.selectedId = null
        setSecret(id, "")
    }

    fun secretOf(id: String): String =
        secretCache.getOrPut(id) { secrets.read(id) }

    fun setSecret(id: String, secret: String) {
        val trimmed = secret.trim()
        secrets.write(id, trimmed)
        if (trimmed.isEmpty()) secretCache.remove(id) else secretCache[id] = trimmed
    }

    /** 只给测试用：看看真正会被写进 XML 的东西长什么样。 */
    internal fun serializedForTest(): String =
        myState.profiles.joinToString("\n") {
            "${it.id}|${it.name}|${it.baseUrl}|${it.modelId}|${it.authKind}"
        } + "\nselected=${myState.selectedId}"

    companion object {
        fun getInstance(): ModelProfiles =
            ApplicationManager.getApplication().getService(ModelProfiles::class.java)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests "*ModelProfilesTest*" --console=plain`
Expected: BUILD SUCCESSFUL，6 条用例

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/ccoder/settings/ModelProfiles.kt src/test/kotlin/com/ccoder/settings/ModelProfilesTest.kt
git commit -m "feat(settings): 全局模型配置存储，密钥走 PasswordSafe"
```

---

### Task 3: 会话启动集成

**Files:**
- Modify: `src/main/kotlin/com/ccoder/settings/ClaudeSettings.kt`（`toStartParams`，约 :145-152）
- Test: `src/test/kotlin/com/ccoder/settings/ProfileEnvMergeTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `modelProfileEnv()`、Task 2 的 `ModelProfiles.selected()` / `secretOf()`
- Produces: `fun mergeProfileEnv(base: Map<String, String>, profileEnv: Map<String, String>): Map<String, String>`

- [ ] **Step 1: 写失败的测试**

新建 `src/test/kotlin/com/ccoder/settings/ProfileEnvMergeTest.kt`：

```kotlin
package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileEnvMergeTest {

    @Test
    fun `profile 赢过 envOverrides`() {
        // profile 是显式选择（"现在用这个"），envOverrides 是背景设置。
        // 背景不该盖过当次选择
        val merged = mergeProfileEnv(
            base = mapOf("ANTHROPIC_BASE_URL" to "https://old", "OTHER" to "kept"),
            profileEnv = mapOf("ANTHROPIC_BASE_URL" to "https://new"),
        )

        assertEquals("https://new", merged["ANTHROPIC_BASE_URL"])
        assertEquals("kept", merged["OTHER"], "无关的键必须原样留着")
    }

    @Test
    fun `空 profileEnv 时原样返回`() {
        val base = mapOf("A" to "1")

        assertEquals(base, mergeProfileEnv(base, emptyMap()))
    }

    @Test
    fun `合并不产生重复键`() {
        val merged = mergeProfileEnv(
            base = mapOf("ANTHROPIC_API_KEY" to "old"),
            profileEnv = mapOf("ANTHROPIC_API_KEY" to "new"),
        )

        assertEquals(1, merged.size)
        assertEquals("new", merged["ANTHROPIC_API_KEY"])
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `./gradlew test --tests "*ProfileEnvMergeTest*" --console=plain`
Expected: 编译失败 —— `mergeProfileEnv` 未定义

- [ ] **Step 3: 实现纯函数**

在 `src/main/kotlin/com/ccoder/settings/ModelProfile.kt` 末尾追加：

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests "*ProfileEnvMergeTest*" --console=plain`
Expected: BUILD SUCCESSFUL，3 条用例

- [ ] **Step 5: 接进 toStartParams**

修改 `src/main/kotlin/com/ccoder/settings/ClaudeSettings.kt` 的 `toStartParams`。**先读一遍它现在的样子**（约 :145-152），然后改成：

```kotlin
    /**
     * 打包成 `start` 消息的参数。
     *
     * 选中的模型配置在这里翻成环境变量，并进 `envOverrides` —— 那条通路
     * `Protocol.encodeStart` 已经在序列化了，所以协议与 sidecar 都不用动。
     *
     * 没有选中任何配置时，这里产出的东西与从前**一字不差**。
     *
     * @param profiles 模型配置的来源。**默认 null 就代表"一条都没配"**，而不是
     *   自己去 `getInstance()` —— 单测环境里没有 Application 服务，而 Kotlin 的
     *   默认参数**照样会被求值**，那种 `runCatching` 兜底兜不住任何东西，还会顺手
     *   把生产路径上真实的注册失败也吞掉。生产侧由 `sendStart` 显式传入（Task 6）。
     */
    fun toStartParams(cwd: Path, profiles: ModelProfiles? = null): StartParams {
        val picked = profiles?.selected()
        val env = picked?.let { profile ->
            // 官方端点下密钥为空是合法的，modelProfileEnv 自己会处理
            modelProfileEnv(profile, profiles.secretOf(profile.id))
        } ?: emptyMap()

        return StartParams(
            cwd = cwd.absolutePathString(),
            permissionMode = permissionMode.wireValue,
            // 选中了配置就用它的模型；没选中则回退到老字段
            model = picked?.modelId?.ifBlank { null } ?: model.ifBlank { null },
            claudePath = claudePath.ifBlank { null },
            extraDirs = extraDirs.filter { it.isNotBlank() },
            envOverrides = mergeProfileEnv(
                envOverrides.filterValues { it.isNotBlank() },
                env,
            ).filterValues { it.isNotBlank() },
        )
    }
```

**注意 `ModelProfileIncomplete` 的传播**：它在 `modelProfileEnv` 里抛出，会一路冒到 `sendStart` 的调用点。`ClaudePanel.startSession` 的 `catch (e: Exception)`（约 :1012）会接住它并走 `fail()` —— 这是**想要的行为**：配置不全时应该看到一条说人话的错误，而不是一个空令牌换来 401。

- [ ] **Step 6: 跑全部 Kotlin 测试**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL。**既有用例一条都不该挂** —— `profiles` 默认 null 走的就是
"没有配置任何模型"那条路，产出的 `StartParams` 与从前一字不差。挂了就是真的回归。

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/com/ccoder/settings/ClaudeSettings.kt src/main/kotlin/com/ccoder/settings/ModelProfile.kt src/test/kotlin/com/ccoder/settings/ProfileEnvMergeTest.kt
git commit -m "feat(settings): 启动时把选中的模型配置翻成环境变量"
```

---

### Task 4: 左下角标签与切换弹层

**Files:**
- Create: `src/main/kotlin/com/ccoder/ui/ComposerModel.kt`
- Test: `src/test/kotlin/com/ccoder/ui/ComposerModelTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `ModelProfile`、Task 2 的 `ModelProfiles`
- Produces:
  - `class ModelLabel(private val onOpen: () -> Unit) : JLabel`
  - `fun modelLabelText(profile: ModelProfile?): String`
  - `fun buildModelList(profiles: List<ModelProfile>, currentId: String?, onPick: (ModelProfile) -> Unit, onManage: () -> Unit): JComponent`

文件放 `ComposerModel.kt` 而不是 spec 写的两个文件：项目里 `ComposerMode.kt` 就是把 `ModeLabel` 与列表构建放在一起的，对称着来。

- [ ] **Step 1: 写失败的测试**

新建 `src/test/kotlin/com/ccoder/ui/ComposerModelTest.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComposerModelTest {

    @Test
    fun `没选中配置时标签写无模型`() {
        assertEquals("无模型", modelLabelText(null))
    }

    @Test
    fun `选中时标签写配置名`() {
        assertEquals("中转 Opus", modelLabelText(ModelProfile(name = "中转 Opus")))
    }

    @Test
    fun `名字空白的配置退回写模型 ID`() {
        // 用户可能只填了 modelId 就存了 —— 标签不该是空的
        val p = ModelProfile(name = "  ", modelId = "deepseek-flash")
        assertEquals("deepseek-flash", modelLabelText(p))
    }

    @Test
    fun `名字与 ID 都空时写未命名`() {
        assertEquals("未命名", modelLabelText(ModelProfile()))
    }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `./gradlew test --tests "*ComposerModelTest*" --console=plain`
Expected: 编译失败 —— `modelLabelText` 未定义

- [ ] **Step 3: 实现**

新建 `src/main/kotlin/com/ccoder/ui/ComposerModel.kt`。**先读 `ComposerMode.kt`**，`ModelLabel` 与 `buildModelList` 都照它写 —— 同一行里两个标签，行为不一致会很明显。

```kotlin
package com.ccoder.ui

import com.ccoder.settings.ModelProfile
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** 标签上那个展开小箭头，与 [ModeLabel] 用同一个字符。 */
private const val EXPAND_CARET = " ▾"

/**
 * 模型标签。
 *
 * 照 `ModeLabel`（`ComposerMode.kt:39`）做 —— 同一行里两个标签，
 * 一个能点一个不能会很扎眼。它只负责**显示与转发点击**：
 * 弹层内容与选中逻辑都在 [ClaudePanel] 那边。
 */
internal class ModelLabel(private val onOpen: () -> Unit) : JLabel() {

    private var hovered = false

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = UIUtil.getInactiveTextColor()
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onOpen()
                }

                // ModeLabel 现在没有悬停反馈，这里补上；等它一起改
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    applyForeground()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false
                    applyForeground()
                }
            }
        )
    }

    private var accent = false

    /** 当前是否是有配置被选中。没配置时用更淡的色，表示"这条路的尽头是设置"。 */
    fun setProfile(profile: ModelProfile?) {
        text = modelLabelText(profile) + EXPAND_CARET
        accent = profile != null
        applyForeground()
        repaint()
    }

    private fun applyForeground() {
        foreground = when {
            hovered -> UIUtil.getLabelForeground()
            accent -> UIUtil.getInactiveTextColor()
            else -> UIUtil.getInactiveTextColor()
        }
    }
}

/**
 * 标签上写什么。
 *
 * null 是"一条配置都没配 / 没选中"，与"选中了一条没名字的"是两回事 ——
 * 后者由 [ModelProfile.displayName] 回退到模型 ID。
 */
internal fun modelLabelText(profile: ModelProfile?): String =
    profile?.displayName() ?: "无模型"

/**
 * 切换弹层的内容。
 *
 * **只负责"切"，不负责"改"** —— 编辑是设置那一页的事。把表单塞进来会让
 * "点一下切换"变成"点一下进表单"，高频动作被低频动作拖累（spec §8）。
 */
internal fun buildModelList(
    profiles: List<ModelProfile>,
    currentId: String?,
    onPick: (ModelProfile) -> Unit,
    onManage: () -> Unit,
): JComponent = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    border = JBUI.Borders.empty(4, 4)

    if (profiles.isEmpty()) {
        add(
            JLabel("还没有配置任何模型").apply {
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.empty(6, 6)
            }
        )
    } else {
        profiles.forEach { add(modelRow(it, it.id == currentId, onPick)) }
    }

    add(manageRow(onManage))
}
```

`modelRow` 与 `manageRow` 照 `ComposerMode.kt` 的 `modeRow` 写：显式取 `UIUtil.getLabelFont()`（未挂到层级上时 `getFont()` 可能是 null，`RunStripView` 上踩过这个坑）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests "*ComposerModelTest*" --console=plain`
Expected: BUILD SUCCESSFUL，4 条用例

- [ ] **Step 5: 渲染探针看一眼**

照 `StatusCardsRenderProbe.kt` 的写法加一个 `ComposerModelRenderProbe.kt`，把标签 + 弹层离屏画成 PNG 到 `build/probe/`。

Run: `./gradlew test --tests "*ComposerModelRenderProbe*" --console=plain`，然后把产出的 PNG 打开看。

**这一步不能省** —— 单测只能证明文字对，证明不了它好不好看（这个项目在圆点上吃过一次亏）。重点看：标签在 420px 里够不够短、弹层宽度合不合适。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/ComposerModel.kt src/test/kotlin/com/ccoder/ui/ComposerModelTest.kt src/test/kotlin/com/ccoder/ui/ComposerModelRenderProbe.kt
git commit -m "feat(ui): 可点击的模型标签与切换弹层"
```

---

### Task 5: 设置对话框

**Files:**
- Create: `src/main/kotlin/com/ccoder/settings/ModelProfilesDialog.kt`
- Test: `src/test/kotlin/com/ccoder/settings/ModelProfilesDialogProbe.kt`

**Interfaces:**
- Consumes: Task 1、Task 2 的全部
- Produces: `fun showModelProfilesDialog(project: Project)`

- [ ] **Step 1: 实现对话框**

新建 `src/main/kotlin/com/ccoder/settings/ModelProfilesDialog.kt`。照设计稿 `docs/design/model-settings.html` 的方案 A 画：860×540、左页签 132px、中列表 250px、右表单。

`DialogWrapper` 骨架：

```kotlin
package com.ccoder.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 打开设置对话框，停在「模型」页。 */
fun showModelProfilesDialog(project: Project) {
    ModelProfilesDialog(project).show()
}

/**
 * 设置对话框（设计稿方案 A）。860×540，三栏：左页签 | 模型列表 | 编辑表单。
 *
 * **改动即时保存** —— 没有"应用"按钮，也没有"取消"回滚。所以字段的监听器
 * 直接写回 [ModelProfiles]，不攒 pending 副本：没有副本，就没有"忘了保存"。
 *
 * 它只读写配置，**不碰当前会话** —— 选中态改掉之后由 [ClaudePanel] 决定
 * 要不要重开会话。设置界面自己去动会话会把两处的生命周期缠在一起。
 */
internal class ModelProfilesDialog(private val project: Project) : DialogWrapper(true) {

    private val profiles = ModelProfiles.getInstance()

    /** 当前正在编辑的副本。null = 一条都没选中。 */
    private var editing: ModelProfile? = null

    private val listSlot = JPanel()
    private val formSlot = JPanel()

    init {
        title = "设置"
        setSize(860, 540)
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(tabsColumn(), BorderLayout.WEST)
        add(listColumn(), BorderLayout.CENTER)
        add(formColumn(), BorderLayout.EAST)
    }

    /**
     * 左栏 132px。
     *
     * 本版**只放「模型」一项** —— 不摆"通用/权限/关于"的空壳。空壳点不动，
     * 用户会先以为是自己点错了，再以为是坏的（spec §7）。
     */
    private fun tabsColumn(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(10, 8)
        preferredSize = Dimension(JBUI.scale(132), 0)
        add(JBLabel("模型").apply {
            border = JBUI.Borders.empty(6, 9)
            foreground = UIUtil.getLabelForeground()
        })
        add(Box.createVerticalGlue())
    }

    private fun listColumn(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(14, 12)
        preferredSize = Dimension(JBUI.scale(250), 0)
        conflictWarning()?.let {
            add(it)
            add(Box.createVerticalStrut(JBUI.scale(8)))
        }
        add(listSlot.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        })
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(addButton())
        add(Box.createVerticalGlue())
    }

    /**
     * `envOverrides` 与模型配置抢同一批变量时的警告条（spec §6）。
     *
     * 没有冲突就返回 null —— 一条"一切正常"的常驻提示只会变成噪音。
     * 放**列表上方**而不是表单里：冲突是整页的事，与当前在编辑哪一条无关。
     */
    private fun conflictWarning(): JComponent? {
        val keys = conflictingEnvKeys(ClaudeSettings.getInstance(project).envOverrides)
        if (keys.isEmpty()) return null
        return JBLabel("<html>设置里的 <b>${keys.joinToString("、")}</b> 会被选中的模型配置覆盖</html>")
            .apply { foreground = UIUtil.getInactiveTextColor() }
    }

    private fun formColumn(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(14, 16)
        preferredSize = Dimension(JBUI.scale(440), 0)
        add(formSlot, BorderLayout.NORTH)
    }

    /** 「＋ 添加模型」：建一条空配置并立刻进入编辑。 */
    private fun addButton(): JComponent = JBLabel("＋ 添加模型").apply {
        foreground = UIUtil.getInactiveTextColor()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(8, 10)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                editing = ModelProfile()
                refresh()
            }
        })
    }

    /**
     * 两栏整体重建。
     *
     * 列表和表单都很小（几条配置、五个字段），重建比增量同步可靠得多 ——
     * 增量同步要处理"删掉当前项之后表单显示什么"这类边界，正是 bug 的温床。
     */
    private fun refresh() {
        rebuildList()
        rebuildForm()
    }

    private fun rebuildList() = Unit   // Step 2 实现

    private fun rebuildForm() = Unit   // Step 3 实现
}
```

- [ ] **Step 1b: 确认骨架能编译**

Run: `./gradlew compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL。两个 `rebuild*` 是空实现，这一步只验骨架接线

- [ ] **Step 2: 列表栏**

把 `rebuildList()` 的 `= Unit` 换成：

```kotlin
    /** 中栏内容：一条配置一行，正在编辑的那条高亮。 */
    private fun rebuildList() {
        listSlot.removeAll()
        profiles.profiles().forEach { p ->
            listSlot.add(JBLabel(p.displayName()).apply {
                isOpaque = true
                border = JBUI.Borders.empty(6, 9)
                foreground = UIUtil.getLabelForeground()
                background = if (p.id == editing?.id) {
                    UIUtil.getListSelectionBackground(true)
                } else {
                    UIUtil.getPanelBackground()
                }
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        editing = p
                        refresh()
                    }
                })
            })
        }
        listSlot.revalidate()
        listSlot.repaint()
    }
```

用 `ModelProfile.displayName()`（Task 1 定义，与 `settings` 同包）—— 左下角标签
走的是**同一个**函数，所以同一个配置在列表里和在标签上一定叫同一个名。
（对话框属 `settings` 包，不该反向依赖 `ui` —— 这也是 `displayName()` 当初
放在数据类那边、而不是放在标签类里的原因。）

- [ ] **Step 3: 表单栏**

把 `rebuildForm()` 的 `= Unit` 换成：

```kotlin
    /**
     * 右栏表单。
     *
     * **字段顺序不能变**：名称 → Base URL → 认证方式 → API Key → 模型 ID。
     * 「认证方式」必须在密钥**之前** —— 它决定密钥填的是哪一种，
     * 先填密钥再选方式是反着的（spec §7）。
     */
    private fun rebuildForm() {
        formSlot.removeAll()
        formSlot.layout = BoxLayout(formSlot, BoxLayout.Y_AXIS)

        val p = editing
        if (p == null) {
            formSlot.add(JBLabel("选一条配置，或点「＋ 添加模型」").apply {
                foreground = UIUtil.getInactiveTextColor()
            })
            formSlot.revalidate()
            formSlot.repaint()
            return
        }

        val name = JBTextField(p.name)
        val url = JBTextField(p.baseUrl)
        val modelId = JBTextField(p.modelId)
        val authKind = ComboBox(AuthKind.entries.toTypedArray()).apply {
            selectedItem = p.authKindEnum()
        }
        // 密钥不从 ModelProfile 取 —— 它住在 PasswordSafe 里
        val secret = JBTextField(profiles.secretOf(p.id))

        fun save() {
            val next = p.copy(
                name = name.text,
                baseUrl = url.text,
                modelId = modelId.text,
                authKind = (authKind.selectedItem as AuthKind).name,
            )
            editing = next
            profiles.upsert(next)
            profiles.setSecret(next.id, secret.text)
            rebuildList()   // 改名要立刻反映到列表
        }

        listOf(name, url, modelId, secret).forEach { f ->
            f.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = save()
            })
        }
        authKind.addActionListener { save() }

        formSlot.add(field("名称", name))
        formSlot.add(field("Base URL", url))
        formSlot.add(field("认证方式", authKind))
        formSlot.add(field("API Key", secret))
        formSlot.add(field("模型 ID", modelId))

        // 删除放**底部左**，与"关闭"分开 —— 它和"保存这次编辑"不是一类动作
        formSlot.add(Box.createVerticalStrut(JBUI.scale(16)))
        formSlot.add(JBLabel("删除").apply {
            foreground = JBColor.namedColor("Component.errorFocusColor", JBColor.RED)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    profiles.remove(p.id)   // remove() 负责一并清掉密钥
                    editing = null
                    refresh()
                }
            })
        })

        formSlot.revalidate()
        formSlot.repaint()
    }

    /** 一个字段：上面标签、下面输入框。 */
    private fun field(label: String, input: JComponent): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.emptyBottom(10)
        add(JBLabel(label).apply {
            foreground = UIUtil.getInactiveTextColor()
            border = JBUI.Borders.emptyBottom(4)
        })
        add(input)
    }
```

补这几个 import（其余已在 Step 1 的骨架里）：

```kotlin
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import javax.swing.event.DocumentEvent
```

`displayName()` 与 `conflictingEnvKeys()` 都在 `com.ccoder.settings`，同包不用 import。

- [ ] **Step 3b: 编译**

Run: `./gradlew compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 渲染探针看一眼**

照 `ComposerRenderProbe.kt` 的写法加 `ModelProfilesDialogProbe.kt`，把对话框离屏画成 PNG。

Run: `./gradlew test --tests "*ModelProfilesDialogProbe*" --console=plain`，然后打开 PNG 看。

重点看三处：**Base URL 有没有折行**（这是选方案 A 的全部理由）、**API Key 打码后长度合不合适**、**左页签只有一项时会不会显得空**。

> 列表栏**不复用** Task 4 的 `buildModelList()` —— 那个是给弹层用的，底部带一条
> 「管理模型…」。而这一页**本身就是**管理页，再放一条通向自己的入口就成了循环。
> 两处长得像，但职责不同：弹层是"切"，这里是"管"。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/ccoder/settings/ModelProfilesDialog.kt src/test/kotlin/com/ccoder/settings/ModelProfilesDialogProbe.kt
git commit -m "feat(settings): 模型配置对话框"
```

---

### Task 6: 接线到 ClaudePanel

**Files:**
- Modify: `src/main/kotlin/com/ccoder/ui/ClaudePanel.kt`（顶行 :307、工具栏 :322、切换逻辑）
- Modify: `src/main/kotlin/com/ccoder/ui/ComposerToolbar.kt`（`buildModelLabel()` :158）

**Interfaces:**
- Consumes: Tasks 1-5 的全部
- Produces: 无（终端接线）

- [ ] **Step 1: 换掉 buildModelLabel**

`ComposerToolbar.kt:158` 的 `buildModelLabel()` 返回的是裸 `JLabel`。改成由 `ClaudePanel` 构造 `ModelLabel` 并传进来 —— 与 `modeLabel` 现在的做法一致（`ClaudePanel.kt:128` 就是这么构造 `ModeLabel` 的）。

`buildStatusRow`（:148）的两个参数类型都是 `JComponent`，`ModelLabel` 继承自 `JLabel`，所以这里不用改。

- [ ] **Step 2: 顶行加齿轮**

`ClaudePanel.kt:307` 的 `top` 是 `BorderLayout`，`sessionLabel` 在 CENTER、`newSessionButton` 在 EAST。在 EAST 放一个横向容器，齿轮排在「＋」**左边**：

```kotlin
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(sessionLabel, BorderLayout.CENTER)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(settingsButton)   // 齿轮在左
                    add(newSessionButton) // 「＋」仍在最右 —— 用户最初要的就是右上角
                },
                BorderLayout.EAST,
            )
        }
```

齿轮按钮的造型照 `newSessionButton` 现有的写法，`toolTipText = "设置"`，点击 → `showModelProfilesDialog(project)`。

- [ ] **Step 3: 接标签点击与切换**

在 `ClaudePanel` 里加：

```kotlin
    /** 点模型标签：弹切换列表。 */
    private fun toggleModelChooser() {
        val profiles = ModelProfiles.getInstance()
        modelPopup = showTogglePopup(modelLabel, buildModelList(
            profiles = profiles.profiles(),
            currentId = profiles.selectedId(),
            onPick = { pick -> switchModel(pick) },
            onManage = { showModelProfilesDialog(project) },
        ), centerOverPanel = true) { modelPopup = null }
    }

    /**
     * 切换模型 = 重开会话。
     *
     * 模型是会话启动参数（`toStartParams` → `start` → `options.model`），
     * 没有热切换这条路。转写历史留着 —— 走 [restartSession] 那条既有路径。
     */
    private fun switchModel(pick: ModelProfile) {
        val profiles = ModelProfiles.getInstance()
        if (profiles.selectedId() == pick.id) return

        // 会话进行中先把"上下文会丢"说清楚，别让用户切完才发现。
        // **确认放在改选中态之前**：用户点了取消，选中态就该原样不动。
        // 先改后回滚会留下"标签闪了一下又变回去"的中间态，而且回滚那一步
        // 一旦忘了写，选中态就永久跑偏 —— 这里干脆不给它跑偏的机会
        if (busy && !confirmModelSwitch(pick)) return

        profiles.select(pick.id)
        refreshModelLabel()
        restartSession()
    }
```

`confirmModelSwitch` 用 `Messages.showYesNoDialog`，文案照设计稿：

```kotlin
    /**
     * 会话进行中切换时的确认。
     *
     * 这条提示**不能省** —— 少了它用户会以为切完还能接着聊，
     * 等发现上下文没了已经晚了（spec §9）。
     */
    private fun confirmModelSwitch(pick: ModelProfile): Boolean =
        Messages.showYesNoDialog(
            project,
            "切换会重开会话，这段对话的上下文不保留。",
            "切换到「${pick.displayName()}」",
            "切换并重开",
            "取消",
            null,
        ) == Messages.YES
```

需要 `import com.intellij.openapi.ui.Messages`。

同时补 `modelPopup` 的声明（照 `sessionPopup` 的写法，它已经在了）：

```kotlin
    private var modelPopup: JBPopup? = null
```

**还有一处改漏了会让整个功能静默失效**：`sendStart`（约 :1026）现在写的是

```kotlin
ClaudeSettings.getInstance(project).toStartParams(Path.of(base))
```

Task 3 给 `toStartParams` 加了第二个参数，默认 `null` = "一条配置都没配"。**忘传不会报错** ——
它会安静地退回旧行为，症状是"配了模型却不生效"。改成：

```kotlin
    private fun sendStart(c: SidecarClient, base: String) {
        c.sendLine(
            Protocol.encodeStart(
                nextId(),
                ClaudeSettings.getInstance(project)
                    .toStartParams(Path.of(base), ModelProfiles.getInstance())
                    .copy(resumeSessionId = resumeTargetId),
            )
        )
    }
```

补 import：`com.ccoder.settings.ModelProfiles`、`com.ccoder.settings.displayName`。

- [ ] **Step 4: 刷新标签**

在 `refreshStatusCards()`（:461 附近）旁边加 `refreshModelLabel()`：

```kotlin
    private fun refreshModelLabel() {
        modelLabel.setProfile(ModelProfiles.getInstance().selected())
    }
```

并确保它在面板初始化（:321 `refreshModeLabel()` 旁边）和每次切换后都被调用。

- [ ] **Step 5: 跑全部 Kotlin 测试**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL，无回归

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/ccoder/ui/ClaudePanel.kt src/main/kotlin/com/ccoder/ui/ComposerToolbar.kt
git commit -m "feat(ui): 右上角设置入口与模型一键切换"
```

---

### Task 7: 打包冒烟

**Files:** 无

- [ ] **Step 1: 三侧测试全跑**

```bash
./gradlew test --console=plain
cd sidecar && node --test
cd ../web && node node_modules/vitest/vitest.mjs run
```

三条都必须绿。侧车与 web 本不该受影响，但 `envOverrides` 的产出变了 —— 要确认没有既有用例依赖它的旧形状。

- [ ] **Step 2: 打包**

```bash
./gradlew --stop
PATH="/c/Program Files/nodejs:$PATH" ./gradlew buildPlugin --console=plain
```

**不能用 `-x buildWebUi`** —— 那会沿用上一次的前端产物。成功标志是日志里有 `web UI 已打包：NNNNNN 字节`；若是 `UP-TO-DATE`，先确认 `web/src` 自上次构建以来没改过。

- [ ] **Step 3: 装机**

关掉 PyCharm（jar 被锁时替换会失败），然后：

```bash
python .install-plugin.py
```

回读校验要确认包内有 `com/ccoder/settings/ModelProfiles.class` 与 `ModelProfilesDialog.class`。

- [ ] **Step 4: 手工冒烟（spec §11 那五条）**

| # | 操作 | 期望 |
|---|---|---|
| 1 | 齿轮 → 新建两条配置（一条官方、一条第三方） | 列表出现两条；官方那条不填密钥能存 |
| 2 | 左下角标签点开 | 两条都在，当前项打勾，底部有「管理模型…」 |
| 3 | 会话**空闲**时切换 | 直接重开，无确认框 |
| 4 | 发一条消息、等它跑完，再切换 | 弹确认框；点取消后**标签回到原来那条** |
| 5 | 第三方那条故意填错密钥 → 发消息 | 报错说得清是哪个端点、哪种认证方式，不是干巴巴的 401 |
| 6 | 重启 IDE | 选中项还在、密钥还在；删掉一条后密钥一并没了 |

- [ ] **Step 5: 更新 spec 的实现后注记**

照本项目惯例（`docs/superpowers/specs/` 里几份都有"⚠️ 实现后修订"），把实现过程中与 spec 不符的地方补进去。

- [ ] **Step 6: 提交**

```bash
git add docs/superpowers/specs/2026-09-13-model-profiles-design.md
git commit -m "docs(spec): 记录模型配置的实现后修订"
```
