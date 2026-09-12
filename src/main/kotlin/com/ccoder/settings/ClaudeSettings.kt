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
    /** 界面上的显示名。枚举名（DEFAULT / ACCEPT_EDITS）是给代码看的。 */
    val label: String,
    val requiresDangerousOptIn: Boolean = false,
) {
    DEFAULT("default", "标准"),
    ACCEPT_EDITS("acceptEdits", "自动接受编辑"),
    PLAN("plan", "仅规划"),
    DONT_ASK("dontAsk", "不询问"),

    /** SDK 要求同时设置 allowDangerouslySkipPermissions（sdk.d.ts:1852-1856）。 */
    BYPASS_PERMISSIONS("bypassPermissions", "绕过权限", requiresDangerousOptIn = true),
    ;

    /** `ComboBox` 拿 `toString` 当显示文本，覆盖它省得处处传 label。 */
    override fun toString(): String = label
}

/**
 * 复选框真正参与决定 —— 这是"我明白风险"那个框的意义所在。
 *
 * 改之前它是个摆设：`apply()` 从头到尾没读过 `isSelected`，勾不勾都照写
 * `BYPASS_PERMISSIONS`。凡是"用户必须明确确认"的设计，确认动作就得真的
 * 参与计算，否则它只是安慰剂。
 */
internal fun effectivePermissionMode(
    selected: PermissionModeSetting,
    optedIn: Boolean,
): PermissionModeSetting =
    if (selected.requiresDangerousOptIn && !optedIn) PermissionModeSetting.DEFAULT else selected

/**
 * 发送键的两种约定。
 *
 * 用户习惯差异很大（聊天工具是 Enter 发送，编辑器是 Enter 换行），
 * 所以做成设置项而不是替他选一个。
 *
 * 放在 settings 包而不是 ui：项目里已有的模式是"持久化的枚举与它的 State
 * 同处 settings"（见 [PermissionModeSetting]）。放 ui 会引入 settings → ui
 * 的反向依赖。
 */
enum class SendShortcut {
    /** Enter 发送，Shift+Enter 换行。聊天工具惯例。 */
    ENTER,

    /** Enter 换行，Ctrl+Enter 发送。编辑器惯例。 */
    CTRL_ENTER,
    ;

    companion object {
        val DEFAULT = ENTER

        /** 从持久化的名字还原；认不出来就回退默认值，不抛异常。 */
        fun fromName(name: String?): SendShortcut =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
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
        var sendShortcut: String = SendShortcut.DEFAULT.name,
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

    var sendShortcut: SendShortcut
        get() = SendShortcut.fromName(myState.sendShortcut)
        set(value) { myState.sendShortcut = value.name }

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
            sendShortcut = state.sendShortcut,
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
