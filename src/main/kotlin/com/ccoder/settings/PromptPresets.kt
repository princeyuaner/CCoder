package com.ccoder.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 预置 prompt 列表。**Application 级**。
 *
 * 为什么不是项目级（[ClaudeSettings] 那样）：`.idea/` 整个被 gitignore，
 * 项目级设置既不能提交、也没法跟团队共享；而预置 prompt 是个人工作流快捷方式，
 * 跨项目复用才对。规格见设计稿第二章。
 *
 * 类必须是 public：平台靠反射实例化它（照 `ModelProfiles` / `ClaudeSettings` 的先例）。
 */
@State(name = "CCoderPromptPresets", storages = [Storage("ccoderPromptPresets.xml")])
@Service(Service.Level.APP)
class PromptPresets : PersistentStateComponent<PromptPresets.State> {

    data class State(
        var presets: MutableList<PromptPreset> = mutableListOf(),
    )

    private var myState = State()

    override fun getState(): State = myState

    /**
     * 读盘时逐条收敛，并滤掉彻底空的那几条。
     *
     * 收敛放在这里而不是只放在 [upsert]：手改过 XML 的用户也可能写出
     * 名字与内容对不上的状态，读的时候就该修好（同 `ModelProfiles.loadState`）。
     */
    override fun loadState(state: State) {
        state.presets.replaceAll { normalizePromptPreset(it) }
        state.presets.removeAll { isBlankPromptPreset(it) }
        myState = state
    }

    /**
     * 快照。
     *
     * 补全那条路在**输入框的文档监听**里读它，而设置对话框在 EDT 上往列表里增删；
     * 两边虽然都在 EDT，但读到一个正在被 `replaceAll` 改写的列表仍是不可取的
     * —— 症状不是崩溃，是"补全弹层里少一条"这类最难查的形态。
     */
    fun presets(): List<PromptPreset> = myState.presets.toList()

    /** 写入前一律收敛，让不变量只有一个出处（见 [normalizePromptPreset]）。 */
    fun upsert(preset: PromptPreset) {
        val next = normalizePromptPreset(preset)
        val i = myState.presets.indexOfFirst { it.id == next.id }
        if (i >= 0) myState.presets[i] = next else myState.presets.add(next)
    }

    fun remove(id: String) {
        myState.presets.removeAll { it.id == id }
    }

    companion object {
        fun getInstance(): PromptPresets =
            ApplicationManager.getApplication().getService(PromptPresets::class.java)
    }
}
