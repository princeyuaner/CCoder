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

    override fun loadState(state: State) {
        myState = state
        secretCache.clear()
    }

    /** 快照。理由同 [selected]。 */
    fun profiles(): List<ModelProfile> = myState.profiles.toList()

    fun selectedId(): String? = myState.selectedId

    /**
     * 先对整个列表取快照再找。
     *
     * 这个读会在**池化线程**上发生（见 [secretCache]），而 EDT 上的 [upsert] /
     * [remove] 正同时往 `myState.profiles` 里增删。直接遍历那个活 `ArrayList`
     * 可能读到半个列表 —— 症状不是崩溃，是"密钥时有时无"这类最难查的形态。
     */
    fun selected(): ModelProfile? =
        myState.profiles.toList().firstOrNull { it.id == myState.selectedId }

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

    companion object {
        fun getInstance(): ModelProfiles =
            ApplicationManager.getApplication().getService(ModelProfiles::class.java)
    }
}
