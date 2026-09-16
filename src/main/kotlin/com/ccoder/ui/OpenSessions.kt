package com.ccoder.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * 会话占用登记：**哪条会话正被哪个面板跑着**。
 *
 * ## 为什么必须有（多标签引入的必然）
 *
 * 开着面板时每个标签都会各自 `listSessions` 然后恢复"最近那条"
 * （`ClaudePanel` 的 `openPick` 分支）。多标签之下两个标签很可能挑中**同一条**——
 * 两边同时写同一个 jsonl。这条风险在设计稿里早被点名过
 * （`2026-09-12-session-switch-design.md`：`resume` 一个正被占用的会话，
 * "本版不做检测"），多标签把它从"理论上"变成了"必然"。
 *
 * ## 两道信号，各有各的用处
 *
 * - **预占用**我们发出去的 `resumeSessionId`：`listSessions → 挑选 → start`
 *   之间那段异步窗口里，除了我们自己发的东西没有任何权威信号
 * - **确认**用 `init` 事件回的 `session_id`：`resume` 未必按我们传的 id 成立，
 *   权威信号是 init 那个（见 `ClaudePanel` 里那段"真正的会话 id 只在这里"）
 *
 * ## 线程
 *
 * 全部落点都在 EDT 上（openPick 的回调、switchToSession、init 处理、stop/dispose），
 * 所以这里不上锁 —— 加锁反而会掩盖"谁在别的线程上碰了它"这种错误。
 *
 * 纯逻辑在 [ClaimTable] 里，可在纯 JVM 单测（这个类自己不行：要 Project）。
 */
internal class ClaimTable {

    private val owners = LinkedHashMap<String, Any>()

    /**
     * 占住一条会话。
     *
     * 已被**别人**占着 → false，且**不动原主**；同一个 owner 重复占 → true（幂等，
     * 因为"确认"那一步会拿 init 的 id 再占一次自己已经预占的）。
     */
    fun reserve(sessionId: String, owner: Any): Boolean {
        val current = owners[sessionId]
        if (current != null && current !== owner) return false
        owners[sessionId] = owner
        return true
    }

    fun ownerOf(sessionId: String): Any? = owners[sessionId]

    fun isTaken(sessionId: String): Boolean = owners.containsKey(sessionId)

    /** 放掉一条。**只放自己占的** —— 别人的登记不能被误删。 */
    fun release(sessionId: String, owner: Any) {
        if (owners[sessionId] === owner) owners.remove(sessionId)
    }

    /** 面板停会话 / 被销毁时一次放干净。 */
    fun releaseAll(owner: Any) {
        owners.entries.removeAll { it.value === owner }
    }

    /** 被人占着的那些 id（会话列表据此把行标成不可点）。 */
    fun takenIds(): Set<String> = owners.keys.toSet()

    /** 快照，给测试与探针用。改它不影响内部。 */
    fun snapshot(): Map<String, Any> = LinkedHashMap(owners)
}

/** [ClaimTable] 的项目级壳子。 */
@Service(Service.Level.PROJECT)
class OpenSessions {

    private val table = ClaimTable()

    fun reserve(sessionId: String, owner: Any): Boolean = table.reserve(sessionId, owner)

    fun ownerOf(sessionId: String): Any? = table.ownerOf(sessionId)

    fun isTaken(sessionId: String): Boolean = table.isTaken(sessionId)

    fun release(sessionId: String, owner: Any) = table.release(sessionId, owner)

    fun releaseAll(owner: Any) = table.releaseAll(owner)

    fun takenIds(): Set<String> = table.takenIds()

    companion object {
        fun getInstance(project: Project): OpenSessions = project.getService(OpenSessions::class.java)
    }
}
