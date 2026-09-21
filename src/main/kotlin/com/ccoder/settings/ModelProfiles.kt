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
import java.util.concurrent.ConcurrentHashMap

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
        /**
         * **最近一次选择**（全应用）。
         *
         * 2026-09-21 之前它是"当前选中的那条"，是唯一的选中态 —— 于是两个 IDE
         * 窗口、同一个窗口的两个会话标签全共用它，而模型是起 sidecar 时烤进进程
         * 环境的：改设置根本到不了别人那个跑着的会话，标签于是会说着一个别的
         * 会话在用的模型。现在选中态按**会话标签**各记各的（见
         * [ClaudeSettings.lastModel]），这个字段只剩一个用途：**给还没选过的
         * 项目兜底**（升级上来的、以及新建的项目不会突然变成"无模型"）。
         *
         * **字段名不改**：它写在 `ccoderModelProfiles.xml` 里，改了就读不回老值，
         * 而那个值正是这次迁移要借的东西。方法名改成了 [recent]，免得有人以为
         * 它还是"当前选中的那条"。
         */
        var selectedId: String? = null,
    )

    private var myState = State()

    /**
     * 密钥的内存缓存。
     *
     * **它会被两种线程同时碰**：读它的 `toStartParams` 由 `sendStart` 调用，
     * 而那两条路一条在 EDT、一条在**池化线程**上（`ClaudePanel.startSession` 的
     * `executeOnPooledThread`）；写它的 [setSecret] 由设置对话框在 EDT 上调用。
     * 对话框是 application-modal，**挡不住池化线程** —— 所以"EDT 写 / 后台读"
     * 同时发生是可达的，缓存因此是 [ConcurrentHashMap]（读一次记住，别拿 UI 线程
     * 同步等 PasswordSafe 的 I/O 这件事本身没变）。
     *
     * 同一批线程问题也适用于本类对 `myState.profiles` 的那些遍历：它们都先取快照，
     * 理由见 [selected]。
     */
    private val secretCache = ConcurrentHashMap<String, String>()

    override fun getState(): State = myState

    /**
     * 读盘时**逐条迁移并收敛**。
     *
     * 迁移那一步是为老 XML：改版前一条配置只有一个模型，存在 `modelId` 里、
     * 没有 `modelIds`。反序列化之后列表是空的，直接收敛会把那个模型名丢掉
     * （`normalizeModelProfile` 会把空的 `modelId` 落成空串）—— 症状是用户
     * 升级完发现模型没了。所以先把它补成单项列表，再收敛。
     *
     * 收敛放在这里而不是只放在 [upsert]：手改过 XML 的用户也可能写出
     * "模型不在列表里"这种状态，读的时候就该修好。
     */
    override fun loadState(state: State) {
        state.profiles.replaceAll { p ->
            val migrated =
                if (p.modelIds.isEmpty() && p.modelId.isNotBlank()) {
                    p.copy(modelIds = mutableListOf(p.modelId.trim()))
                } else {
                    p
                }
            normalizeModelProfile(migrated)
        }
        myState = state
        secretCache.clear()
    }

    /** 快照。理由同 [selected]。 */
    fun profiles(): List<ModelProfile> = myState.profiles.toList()

    fun recentId(): String? = myState.selectedId

    /**
     * 最近一次选择的那条配置（见 [State.selectedId]）。
     *
     * **别拿它当"当前在用的那条"** —— 那是每个会话标签各自的事
     * （`ClaudeSettings.lastModel`）。这里只在两处用得上：给还没选过的项目
     * 兜底、以及设置页显示"最近用的是哪条"。
     *
     * 先对整个列表取快照再找。这个读会在**池化线程**上发生（见 [secretCache]），
     * 而 EDT 上的 [upsert] / [remove] 正同时往 `myState.profiles` 里增删。
     * 直接遍历那个活 `ArrayList` 可能读到半个列表 —— 症状不是崩溃，
     * 是"密钥时有时无"这类最难查的形态。
     */
    fun recent(): ModelProfile? =
        myState.profiles.toList().firstOrNull { it.id == myState.selectedId }

    /** 传一个不存在的 id 等于没选 —— 免得启动路径读到一个空引用。 */
    fun select(id: String?) {
        myState.selectedId = id?.takeIf { wanted -> myState.profiles.any { it.id == wanted } }
    }

    /**
     * 记下"这条配置最近用的就是这个模型"：**把它当前用的模型指过去，并记成最近一次选择**。
     *
     * 2026-09-21 起这**不是**"选中态"—— 选中态按会话标签各记各的（见
     * [State.selectedId]）。这里做两件事：把模型写回配置（设置页要显示它，
     * [normalizeModelProfile] 也要求 `modelId` 落在 `modelIds` 里），
     * 以及更新全应用的"最近一次选择"（新项目的兜底值）。
     *
     * 两件事必须一起做。分开写会留下"最近用的是 A、而它的模型还指着 B"的中间态，
     * 而设置页读的正是这两个字段 —— 那一瞬间它会显示一个不存在的东西。
     *
     * 配置不存在、或模型不在它的列表里，**整个不动**（不是只做一半）：
     * 这两件事都发生在"回执到达时用户已经改过设置了"那条竞态上，
     * 那时候宁可什么都不改，也不要写进一个半截状态。
     */
    fun pick(profileId: String, modelId: String) {
        val i = myState.profiles.indexOfFirst { it.id == profileId }
        if (i < 0) return
        if (modelId !in myState.profiles[i].modelIds) return
        myState.profiles[i] = myState.profiles[i].copy(modelId = modelId)
        myState.selectedId = profileId
    }

    /** 写入前一律收敛，让不变量只有一个出处（见 [normalizeModelProfile]）。 */
    fun upsert(profile: ModelProfile) {
        val next = normalizeModelProfile(profile)
        val i = myState.profiles.indexOfFirst { it.id == next.id }
        if (i >= 0) myState.profiles[i] = next else myState.profiles.add(next)
    }

    fun remove(id: String) {
        myState.profiles.removeAll { it.id == id }
        // 兜底值也得跟着走 —— 留一个指向已删配置的 id，
        // 新项目会静默地什么都借不到
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

    companion object {
        fun getInstance(): ModelProfiles =
            ApplicationManager.getApplication().getService(ModelProfiles::class.java)
    }
}
