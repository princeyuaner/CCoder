package com.ccoder.sync

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 目录同步的配置（**项目级**）。存在 `ccoderSync.xml` 里。
 *
 * 为什么是项目级而不是 IDE 级：源目录通常就是"我正在干的这个项目"，而且"打开哪个项目
 * 就同步哪个"是用户的直觉。IDE 级配置会在换项目时把上一对目录接着同步下去，那更容易出事。
 *
 * ## 落盘的形状
 *
 * 用 [SyncState]（可变镜像）而不是直接存 [SyncConfig]：`XmlSerializer` 要的是
 * "无参构造 + 可变字段"，而领域对象是不可变的。搬运由 `toState` / `toConfig` 负责，
 * 那对函数由一条**往返用例**钉着 —— 这里**再抄一遍字段清单是没有意义的**，
 * 抄错了同样不会有人发现（这正是 `loadState` 走 `toConfig().toState()` 的原因：
 * 只有一份字段清单）。
 *
 * ## 平台不需要在 plugin.xml 里登记
 *
 * `@Service` + `@State` 注解就够了；`PluginXmlShapeTest` 那几条规矩（服务 EP 必须在
 * `<extensions>` 里、不许带 `preload`）都是给 XML 注册那条路的，这里不涉及。
 */
@State(name = "CCoderSync", storages = [Storage("ccoderSync.xml")])
@Service(Service.Level.PROJECT)
class SyncSettings : PersistentStateComponent<SyncState> {

    private var myState = SyncState()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): SyncState = myState

    /**
     * 平台读盘时调。
     *
     * **走 `toConfig().toState()` 而不是逐字段抄**：只有一份字段清单，于是不可能出现
     * "加了字段却忘了在 loadState 里搬"（那个故障是静默的：重启之后设置自己变回去）。
     * 顺带把落盘的值归一了一遍 —— 手改过 XML 的人也不会塞进畸形配置。
     */
    override fun loadState(state: SyncState) {
        myState = state.toConfig().toState()
    }

    /**
     * 当前配置（已归一）。
     *
     * **`internal` 是有意的**：领域类型 [SyncConfig] 不公开，于是服务的公开面只剩平台
     * 真正要的那两个（`getState` / `loadState`）。同 `PendingPermissionCount` 的做法。
     */
    internal val config: SyncConfig get() = myState.toConfig()

    /**
     * 改配置。写盘由平台在合适的时候做（`getState()` 被调时）。
     *
     * 通知是**同步**的、在调用线程上跑的 —— 订阅方（[SyncService]）要碰 UI 得自己回 EDT。
     * 而且**每次改动都会通知**（设置页是"改动即写"的，敲一个字符就是一次），
     * 所以订阅方必须自己去抖 —— 见 `SyncService` 里那个防抖定时器。
     */
    internal fun update(cfg: SyncConfig) {
        val next = cfg.toState()
        if (next == myState) return
        myState = next
        notifyListeners()
    }

    internal fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    internal fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        // 先快照再遍历：监听器可能在回调里退订（同 PendingPermissionCount 那条规矩）
        for (l in listeners.toList()) {
            runCatching { l() }
        }
    }

    companion object {
        fun getInstance(project: Project): SyncSettings = project.getService(SyncSettings::class.java)

        /**
         * 无项目上下文时的兜底取法（插件卸载、项目已关之类的路径）。
         *
         * `project.getService` 在项目已 dispose 时会抛，所以调用点要先判 `project.isDisposed`。
         */
        fun getInstanceOrNull(project: Project?): SyncSettings? =
            if (project == null || project.isDisposed) null else runCatching { getInstance(project) }.getOrNull()
    }
}
