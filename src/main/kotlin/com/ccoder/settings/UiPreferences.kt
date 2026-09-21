package com.ccoder.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 界面偏好。**Application 级**。
 *
 * 为什么不跟项目走（[ClaudeSettings] 那样）：这些是个人外观偏好，不是仓库的属性 ——
 * `.idea/` 整个被 gitignore（同 [PromptPresets] / [UiLanguageSettings] 的理由），
 * 跨项目复用才对：换个项目、换台机器，思考块该长什么样是同一个答案。
 *
 * ## 注册方式：注解，**不进 plugin.xml**
 *
 * 同 [PromptPresets] / `ModelProfiles`。`UiLanguageSettings` 之所以进 XML，只是因为它
 * 必须"界面出现之前"就被读起来（右键菜单、状态栏都早于工具窗口）；这份偏好直到转写区
 * 建起来才有人读，懒实例化就够。少写一条 XML 就少一处能写错的地方
 * （2026-09-20 那次服务没注册上的事故就出在 XML 那半边）。
 *
 * 类必须是 public：平台靠反射实例化它（照 [PromptPresets] 的先例）。
 *
 * ## 这里只存"用户要什么"
 *
 * 每一项在界面上对应哪个控件是设置页的事（今天只有 `GeneralSettingsPage` 里那个
 * 「思考折叠」复选框）；往页面里推是 `ClaudeTranscriptView.setPreferences()` 的事。
 */
@State(name = "CCoderUiPreferences", storages = [Storage("ccoderUiPreferences.xml")])
@Service(Service.Level.APP)
class UiPreferences : PersistentStateComponent<UiPreferences.State> {

    data class State(
        /**
         * 思考块默认收起（2026-09-21）。
         *
         * **默认 false**：2026-09-14 用户定下的是"完成态与进行态都展开"，这条偏好是
         * 可选项 —— 不勾就是那个原样，升级不会偷袭任何人的观感。
         * 勾上之后进行中那块也收起，标题里的转圈与秒表照旧（`ThinkingBlock.tsx`）。
         */
        var collapseThinking: Boolean = false,

        /**
         * 转录区的正文档与字号档（2026-09-21），存的是**枚举名**。
         *
         * 默认「跟随 IDE」+「标准」：设计稿从 2026-09-11 起就写着"用户的编辑器字体设置
         * 会被尊重"（见 `docs/superpowers/specs/2026-09-11-...-design.md` §--font-ui），
         * 只是那条注入一直是坏的、从没生效过 —— 这一版把它修好，于是"跟随 IDE"
         * 第一次真的跟随。真要退回今天的样子，选「系统默认」。
         */
        var fontChoice: String = FontChoice.DEFAULT.name,
        var fontScale: String = FontScale.DEFAULT.name,
    )

    private var myState = State()

    override fun getState(): State = myState

    /**
     * 读盘时**逐字段复制**，不用 `XmlSerializerUtil.copyBean`。
     *
     * 形状照 `ClaudeSettings.loadState`（那里的教训写得很直白：新加的字段忘了写就会
     * **静默**丢掉，症状是"重启 IDE 之后设置又变回去了"）。今天只有一个字段也照这个
     * 形状写 —— B/C 切片（字号、密度、配色）要往这个 State 里加字段时，
     * 那一行就在眼前。
     */
    override fun loadState(state: State) {
        myState = State(
            collapseThinking = state.collapseThinking,
            fontChoice = state.fontChoice,
            fontScale = state.fontScale,
        )
    }

    var collapseThinking: Boolean
        get() = myState.collapseThinking
        set(value) { myState.collapseThinking = value }

    /**
     * 字体与字号。**兜底在 getter 里**（`fromName`），不在 [loadState] ——
     * 手改过 XML 的人写出一个不存在的名字时，设置页得照常开
     * （`combo.selectedItem = <不存在>` 是 `null`，`save()` 里那个 `as` 会当场抛）。
     * 而放 `loadState` 里"修好"它，就等于把非法值抹掉，探针再也测不到这一路。
     */
    var fontChoice: FontChoice
        get() = FontChoice.fromName(myState.fontChoice)
        set(value) { myState.fontChoice = value.name }

    var fontScale: FontScale
        get() = FontScale.fromName(myState.fontScale)
        set(value) { myState.fontScale = value.name }

    companion object {
        fun getInstance(): UiPreferences =
            ApplicationManager.getApplication().getService(UiPreferences::class.java)

        /**
         * 取服务，取不到给 `null`。
         *
         * 分工照 `UiLanguageSettings`：那边用在"本该取得到"的地方（设置对话框构造 ——
         * 取不到就该响亮地失败）；这里用在**界面必须能开**的路径上 —— 往转写区推偏好
         * （`ClaudeTranscriptView.setPreferences`）跑在 ready 分支/CEF 回调那一侧，
         * 抛出去会把后面"补推转写"整段吞掉，症状是**界面全空、零报错**。
         * 取不到就按默认档走（不折叠）：偏好只是偏好。
         */
        fun getInstanceOrNull(): UiPreferences? =
            ApplicationManager.getApplication()?.getService(UiPreferences::class.java)
    }
}
