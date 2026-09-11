package com.ccoder.settings

import com.ccoder.sidecar.StartParams
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * 权限模式。
 *
 * wireValue 必须与 SDK 的 PermissionMode 联合类型**逐字**一致：
 * `'default' | 'acceptEdits' | 'bypassPermissions' | 'plan' | 'dontAsk' | 'auto'`
 * （sdk.d.ts:2327）。拼错不会报错，只会被 CLI 静默忽略。
 */
enum class PermissionModeSetting(
    val wireValue: String,
    val requiresDangerousOptIn: Boolean = false,
) {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    DONT_ASK("dontAsk"),

    /** SDK 要求同时设置 allowDangerouslySkipPermissions（sdk.d.ts:1852-1856）。 */
    BYPASS_PERMISSIONS("bypassPermissions", requiresDangerousOptIn = true),
}

@State(name = "CCoderSettings", storages = [Storage("ccoder.xml")])
@Service(Service.Level.PROJECT)
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {

    data class State(
        var claudePath: String = "",
        var permissionMode: String = PermissionModeSetting.DEFAULT.name,
        var model: String = "",
        var extraDirs: MutableList<String> = mutableListOf(),
        var envOverrides: MutableMap<String, String> = mutableMapOf(),
        var pendingReminderSeconds: Int = 30,
    )

    private var myState = State()

    var claudePath: String
        get() = myState.claudePath
        set(value) { myState.claudePath = value }

    var model: String
        get() = myState.model
        set(value) { myState.model = value }

    var extraDirs: MutableList<String>
        get() = myState.extraDirs
        set(value) { myState.extraDirs = value }

    var envOverrides: MutableMap<String, String>
        get() = myState.envOverrides
        set(value) { myState.envOverrides = value }

    var pendingReminderSeconds: Int
        get() = myState.pendingReminderSeconds
        set(value) { myState.pendingReminderSeconds = value }

    var permissionMode: PermissionModeSetting
        get() = PermissionModeSetting.entries.firstOrNull { it.name == myState.permissionMode }
            ?: PermissionModeSetting.DEFAULT
        set(value) { myState.permissionMode = value.name }

    override fun getState(): State = myState

    /**
     * 显式逐字段复制而非 XmlSerializerUtil.copyBean —— 后者对集合可能只复制引用，
     * 导致两个 State 实例共享同一份可变列表，一处修改影响另一处。
     */
    override fun loadState(state: State) {
        myState = State(
            claudePath = state.claudePath,
            permissionMode = state.permissionMode,
            model = state.model,
            extraDirs = state.extraDirs.toMutableList(),
            envOverrides = state.envOverrides.toMutableMap(),
            pendingReminderSeconds = state.pendingReminderSeconds,
        )
    }

    /**
     * 空字符串一律映射为 null —— 空路径传给 sidecar 会被当成"显式指定了空路径"，
     * 触发 CLAUDE_NOT_FOUND 而非回退到 PATH 解析。
     */
    fun toStartParams(cwd: Path): StartParams = StartParams(
        cwd = cwd.absolutePathString(),
        permissionMode = permissionMode.wireValue,
        model = model.ifBlank { null },
        claudePath = claudePath.ifBlank { null },
        extraDirs = extraDirs.filter { it.isNotBlank() },
        envOverrides = envOverrides.filterValues { it.isNotBlank() },
    )

    companion object {
        /** 项目级服务必须经 Project 获取，不能用 ApplicationManager。 */
        fun getInstance(project: Project): ClaudeSettings =
            project.getService(ClaudeSettings::class.java)
    }
}
