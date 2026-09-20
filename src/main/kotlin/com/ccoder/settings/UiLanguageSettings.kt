package com.ccoder.settings

import com.ccoder.text.CcoderText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 界面语言。**Application 级**。
 *
 * 为什么不跟项目走（[ClaudeSettings] 那样）：界面语言是个人偏好，不是仓库的属性 ——
 * `.idea/` 整个被 gitignore（同 [PromptPresets] 的理由），跨项目复用才对。
 *
 * ## 它怎么被注册、谁把它读起来
 *
 * `<applicationService>` 在 `plugin.xml` 里注册（不是 `@Service` 注解：注解表达不了
 * "启动时就要读起来"）。**启动时那一次推送由 [LanguageStartup] 负责** —— 从前的
 * `preload="true"` 被市场拒收（2026-09-20，计划文档 §八），改成显式监听，语义不变。
 *
 * 为什么必须在界面出现前读一次：这个值最早会被右键菜单（`update()`）与状态栏
 * （`getDisplayName()`）取到，那两处都**早于**工具窗口，而平台服务是懒实例化的。
 *
 * 类必须是 public：平台靠反射实例化它（照 [PromptPresets] / `ModelProfiles` 的先例）。
 *
 * ## 生效时机：热切换（2026-09-20 起）
 *
 * **写值 = 立刻生效**：setter 把选择推给 [CcoderText]，再通知订阅者重译自己。
 * 面板那一遍在 `ClaudePanel.retranslate()`（带键的控件走树重取 + 刷新家族 +
 * 转写区重推语言 + sidecar 收一条 `setUiLang`）。
 *
 * 从前这里刻意"设值不推"，怕的是"卡片英文、面板中文"—— 那是在**没有重译这条路**
 * 时的取舍：晚生效好过一半生效。现在每个取词点要么带键（走树会重取）、要么在
 * 刷新家族里（refresh* 会重算），"一半生效"不再可能，于是晚生效反而成了缺陷
 * （2026-09-20 用户就是这么问上来的：改完没变，还得重启）。
 */
@State(name = "CCoderUiLanguage", storages = [Storage("ccoderUiLanguage.xml")])
class UiLanguageSettings : PersistentStateComponent<UiLanguageSettings.State> {

    data class State(var language: String = UiLanguage.FOLLOW_IDE.id)

    private var myState = State()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        applyToText()
    }

    var language: UiLanguage
        get() = UiLanguage.fromId(myState.language)
        set(value) {
            val changed = value != language
            myState.language = value.id
            // **写值 = 立刻生效**（2026-09-20 改成热切换）：推给词表层，再喊一声
            // 让界面上的东西自己重译。以前这里刻意不推（怕"卡片英文、面板中文"），
            // 那是因为当时没有"重译"这条路 —— 现在有了，晚生效反而成了缺陷。
            applyToText()
            if (changed) notifyListeners()
        }

    /**
     * 界面上的东西订阅这里，语言一变就重译自己（目前只有 [SessionTabs]：
     * 它替自己开着的每个面板跑一遍 `ClaudePanel.retranslate()`）。
     *
     * **订阅者必须记得退订**：这是 APP 级服务，跨项目活着 —— 项目关掉时退订，
     * 不然那个项目的面板会被一个永远活着的列表钉住（照 `PendingPermissionCount`
     * 的写法，遍历前先复制一份：回调里可能退订）。
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    /**
     * 把当前选择推给 [CcoderText]。三处会调它：
     *
     * - [loadState]：启动时（`preload="true"`）定下基线 —— 右键菜单、状态栏都早于
     *   工具窗口，那时还没有面板可重译；
     * - setter：改一次设置就推一次，并通知订阅者重译 —— 热切换的正路；
     * - `SessionTabs.addTab`：新开一个面板时再推一次，防的是"有人直接手改了
     *   `ccoderUiLanguage.xml`"：那种改法没有通知，至少下一个面板会读到新值。
     */
    internal fun applyToText() = CcoderText.setOverride(language.localeOrNull())

    companion object {
        // 服务是在 plugin.xml 里注册的（不是 @Service 注解，理由见类注释），
        // 但取法一样：`getService(Class)` 认 XML 注册的描记符。
        fun getInstance(): UiLanguageSettings =
            ApplicationManager.getApplication().getService(UiLanguageSettings::class.java)

        /**
         * 取服务，取不到给 `null`。
         *
         * 与 [getInstance] 的分工：[getInstance] 用在"本该取得到"的地方（设置对话框
         * 那类 —— 取不到就该响亮地失败）；这里用在**可选**的场合：热切换的监听订阅、
         * 启动路径上推语言。那两处取不到就不做那件事 —— 界面必须能打开，语言只是偏好。
         */
        fun getInstanceOrNull(): UiLanguageSettings? =
            ApplicationManager.getApplication()?.getService(UiLanguageSettings::class.java)

        /**
         * 把语言推给词表层，**取不到服务也不抛**。
         *
         * 这是**启动路径**（建工具窗口 / 新开标签）上的调用点，所以口径与 [getInstance]
         * 相反：服务万一没注册上，正确行为是"退回跟随 IDE + 记一条 warn"，
         * 而不是让窗口打不开 —— 界面必须能打开，语言只是一个偏好。
         * warn 保证它不是静默的（2026-09-20：那次正是服务没注册上，
         * 而当时走的是 `getInstance()`，于是整个工具窗口 NPE 起不来）。
         */
        fun applyLanguageToText() {
            val service = getInstanceOrNull()
            if (service == null) {
                Logger.getInstance(UiLanguageSettings::class.java).warn(
                    "CCoder 界面语言：服务没取到（plugin.xml 里那个 applicationService 注册？），本次回退「跟随 IDE」"
                )
                CcoderText.setOverride(null)
                return
            }
            service.applyToText()
        }
    }
}
