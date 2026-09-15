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
    /**
     * 一句话说清这个模式到底干什么。
     *
     * 放在枚举上而不是界面层：输入框左下角那个弹层与设置页的「权限」页都要用它，
     * 两处**必须一个字不差** —— 而 `settings` 包不许反过来依赖 `ui`
     * （见 `SendShortcut` 上那条），所以唯一的公共落点就是这里。
     */
    val description: String,
    val requiresDangerousOptIn: Boolean = false,
) {
    DEFAULT("default", "标准", "危险操作会先询问"),
    ACCEPT_EDITS("acceptEdits", "自动接受编辑", "文件改动自动接受，其余仍询问"),
    PLAN("plan", "仅规划", "只读：只做计划，不执行工具"),
    DONT_ASK("dontAsk", "不询问", "不询问；未预先允许的一律拒绝"),

    /** SDK 要求同时设置 allowDangerouslySkipPermissions（sdk.d.ts:1852-1856）。 */
    BYPASS_PERMISSIONS(
        "bypassPermissions",
        "绕过权限",
        "所有操作都不再询问",
        requiresDangerousOptIn = true,
    ),
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
 * 思考深度。
 *
 * wireValue 必须与 SDK 的 `EffortLevel` 联合类型**逐字**一致：
 * `'low' | 'medium' | 'high' | 'xhigh' | 'max'`（sdk.d.ts:594）。
 * 拼错不会报错，只会被 CLI 静默忽略 —— 与 [PermissionModeSetting] 同一个坑。
 *
 * [DEFAULT] 的 wireValue 是 **null**，语义是「不干预」：不下发这个字段，
 * 由 CLI 按模型默认档来。所以没碰过这个设置的用户，行为与从前一字不差。
 *
 * 后两档**分模型**（见各自说明）—— 这个事实由弹层的第二行说明如实讲给用户，
 * 而不是在这里假装它们总能生效。
 */
enum class EffortSetting(
    /** 下发给 CLI 的值。null = 不干预，不下发。 */
    val wireValue: String?,
    /** 界面上的显示名。枚举名（DEFAULT / XHIGH）是给代码看的。 */
    val label: String,
) {
    DEFAULT(null, "默认"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),

    /** 比「高」更深。SDK 原话：不支持的模型上**静默降级为 high**（sdk.d.ts:597）。 */
    XHIGH("xhigh", "极高"),

    /** 只有少数模型认（sdk.d.ts:598）。 */
    MAX("max", "最大"),
    ;

    /** `ComboBox` 拿 `toString` 当显示文本，覆盖它省得处处传 label。 */
    override fun toString(): String = label

    companion object {
        /**
         * 从**下发给 CLI 的那个值**还原，用于读回执。
         *
         * 认不出来返回 null 而不是退回 [DEFAULT]：调用方（标签更新那条路）
         * 的正确反应是「什么都不改」，退回默认值会让一个我们不认识的档位
         * 把标签悄悄拨到「默认」—— 而用户明明什么都没选。
         *
         * `level == null` **是**认得出来的情况：那正是 [DEFAULT]，
         * 回执里 null 表示已经从 flag 层清除。
         */
        fun fromWire(level: String?): EffortSetting? =
            entries.firstOrNull { it.wireValue == level }
    }
}

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
enum class SendShortcut(
    /**
     * 界面上显示什么。
     *
     * **完整写出两个方向**（哪个键发送、哪个键换行），不是只写"Enter 发送" ——
     * 选它的人真正想知道的就是"那 Shift+Enter 呢"。写全了，设置页那句解释也就不必再写。
     *
     * 显示名是**数据的属性**，同 [PermissionModeSetting] / [EffortSetting] 与
     * `ModelProfile.displayName()` 的规矩；`override toString()` 是给 `ComboBox` 用的，
     * 与那两个枚举一致（这个枚举不进日志，改了不会连日志里的名字一起改）。
     */
    val label: String,
) {
    /** Enter 发送，Shift+Enter 换行。聊天工具惯例。 */
    ENTER("Enter 发送，Shift+Enter 换行"),

    /** Enter 换行，Ctrl+Enter 发送。编辑器惯例。 */
    CTRL_ENTER("Ctrl+Enter 发送，Enter 换行"),
    ;

    override fun toString(): String = label

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
        var sendShortcut: String = SendShortcut.DEFAULT.name,
        var effort: String = EffortSetting.DEFAULT.name,
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

    var permissionMode: PermissionModeSetting
        get() = PermissionModeSetting.entries.firstOrNull { it.name == myState.permissionMode }
            ?: PermissionModeSetting.DEFAULT
        set(value) { myState.permissionMode = value.name }

    var sendShortcut: SendShortcut
        get() = SendShortcut.fromName(myState.sendShortcut)
        set(value) { myState.sendShortcut = value.name }

    var effort: EffortSetting
        get() = EffortSetting.entries.firstOrNull { it.name == myState.effort }
            ?: EffortSetting.DEFAULT
        set(value) { myState.effort = value.name }

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
            sendShortcut = state.sendShortcut,
            effort = state.effort,
        )
    }

    /**
     * 打包成 `start` 消息的参数。
     *
     * 空字符串一律映射为 null —— 空路径传给 sidecar 会被当成"显式指定了空路径"，
     * 触发 CLAUDE_NOT_FOUND 而非回退到 PATH 解析。
     *
     * 选中的模型配置在这里翻成环境变量，并进 `envOverrides` —— 那条通路
     * `Protocol.encodeStart` 已经在序列化了，所以协议与 sidecar 都不用动。
     *
     * 配置给了东西时还会带上 [HOST_MANAGED_PROVIDER_VAR]（见 [providerOwnershipEnv]）：
     * 少了它，`~/.claude/settings.json` 的 `env` 会把端点抢走而密钥留下，
     * 症状是一次 `401 Invalid token`（spec §6.1）。
     *
     * 第三方配置还会把别名与后台任务的模型名显式给全（见 [routingModelEnv]）：
     * 那批变量同样会被 settings 那一层剥掉，不补的话主对话正常、后台活儿报模型不存在。
     *
     * 另外默认给上任务清单工具的开关（见 [taskToolsEnv]）—— 那套工具 CLI 只对
     * 它认识的模型开放，第三方网关上不给的话「子任务」卡永远是空的。
     * 它是**默认值**：`envOverrides` 里手填过这个键就听手填的。
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
        val pickedEnv = picked?.let { profile ->
            // 官方端点下密钥为空是合法的，modelProfileEnv 自己会处理
            modelProfileEnv(profile, profiles.secretOf(profile.id))
        } ?: emptyMap()
        // 别名与后台任务的等价模型名。它也是"配置那一侧"的产出 —— 手填的
        // envOverrides 盖不过它，与 §6 同一条规矩（冲突的键由模型页列出来）
        val routing = picked?.let(::routingModelEnv) ?: emptyMap()
        // 端点与凭证归配置管 —— 前提是它真的给了（见 providerOwnershipEnv）
        val env = pickedEnv + routing + providerOwnershipEnv(pickedEnv)
        // 任务清单工具：配置给了东西才需要它（这类会话的模型名 CLI 不认识，
        // 默认不给那套工具）。手填过就听手填的 —— 它是默认值，不是路由
        val tools = if (pickedEnv.isEmpty()) emptyMap() else taskToolsEnv(envOverrides)

        return StartParams(
            cwd = cwd.absolutePathString(),
            permissionMode = permissionMode.wireValue,
            // 选中了配置就**只**用它的模型；老字段只在没选中任何配置时生效（spec §6）。
            // 写成 `picked?.modelId?.ifBlank { null } ?: model.ifBlank { null }` 会把
            // "选中了一条没填模型 ID 的第三方配置"变成"回退到当初给官方端点写的那
            // 个模型名"，然后把它发给网关
            model = if (picked != null) picked.modelId.ifBlank { null } else model.ifBlank { null },
            claudePath = claudePath.ifBlank { null },
            extraDirs = extraDirs.filter { it.isNotBlank() },
            envOverrides = mergeProfileEnv(
                envOverrides.filterValues { it.isNotBlank() } + tools,
                env,
            ),
        )
    }

    companion object {
        /** 项目级服务必须经 Project 获取，不能用 ApplicationManager。 */
        fun getInstance(project: Project): ClaudeSettings =
            project.getService(ClaudeSettings::class.java)
    }
}
