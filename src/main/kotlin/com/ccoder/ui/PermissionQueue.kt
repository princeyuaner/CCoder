package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray

data class PermissionDecision(
    val allow: Boolean,
    val updatedPermissions: JsonArray?,
    val message: String?,
)

/**
 * 权限询问的串行化队列。
 *
 * SDK 支持并行工具调用（一条 assistant 消息可含多个 tool_use），
 * 因此 canUseTool 可能被并发调用多次。一次只展示一张卡片，
 * 其余排队 —— 全堆出来会变成弹窗风暴（spec §6.4）。
 *
 * 注意这里只管理插件的本地 UI 队列。真正把挂起的 canUseTool 承诺
 * resolve 掉的是 sidecar 侧的 denyAllPending —— 插件的 cancelAll 只是
 * 清掉界面上的待确认卡片，两者的触发点都是会话终止。
 */
class PermissionQueue(
    private val onActivate: (SidecarMessage.Permission, queuedCount: Int) -> Unit,
) {
    private val queue = ArrayDeque<SidecarMessage.Permission>()
    private var active: SidecarMessage.Permission? = null

    /** 排队等待的项数，不含当前已激活的那张卡片。卡片用它显示"还有 N 个待确认"。 */
    val pendingCount: Int get() = queue.size

    /** 待决总数 = 激活的 + 排队的。状态栏用它显示总待办量。 */
    val totalPending: Int get() = queue.size + if (active != null) 1 else 0

    val activeRequestId: String? get() = active?.requestId

    fun enqueue(permission: SidecarMessage.Permission) {
        queue.addLast(permission)
        activateNextIfIdle()
    }

    fun resolve(requestId: String, decision: PermissionDecision) {
        // 已解决或非当前项，静默忽略 —— 重复决定不该产生副作用
        if (active?.requestId != requestId) return
        active = null
        activateNextIfIdle()
    }

    /** 作废所有待决项。对应 spec §6.2 规则① 的终止路径。 */
    fun cancelAll() {
        queue.clear()
        active = null
    }

    private fun activateNextIfIdle() {
        if (active != null) return
        val next = queue.removeFirstOrNull() ?: return
        active = next
        onActivate(next, queue.size)
    }
}

object PermissionOptions {

    /**
     * 是否显示"总是允许"。
     *
     * sdk.d.ts:249-253 原文要求：某些请求写一条持久规则会授予比本次询问
     * 更大的权限，此时不该提供"不再问"选项。因此必须同时满足
     * suppressAlwaysAllowRule=false 且 suggestions 非空。
     */
    fun allowsAlwaysAllow(p: SidecarMessage.Permission): Boolean =
        !p.suppressAlwaysAllowRule && (p.suggestions?.size() ?: 0) > 0

    /**
     * 卡片主文案。
     *
     * sdk.d.ts:228-233：SDK 已把 title 渲染为完整问句，
     * 应优先使用而非从 toolName+input 重拼。缺失时才逐级降级。
     */
    fun primaryText(p: SidecarMessage.Permission): String =
        p.title?.takeIf { it.isNotBlank() }
            ?: p.displayName?.takeIf { it.isNotBlank() }
            ?: p.toolName
}
