package com.ccoder.settings

import com.ccoder.sidecar.McpServerStatus
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 当前会话里各 MCP server 的**最新一份**状态。
 *
 * 为什么做成项目级服务、而不是让设置页自己去问会话：会话与 `SidecarClient`
 * 都住在 `ClaudePanel` 里，而设置对话框拿不到它们。服务做中立的发布点 ——
 * 面板收到回执就写进来，页面订阅它。同 `PendingPermissionCount` 那条理由。
 *
 * **[known] 与"列表是空的"是两件事**：前者是还没问过（或这台 CLI 太老问不到），
 * 后者是真的一个 server 都没配。界面必须分开说，否则用户会去查一个不存在的毛病。
 */
@Service(Service.Level.PROJECT)
class McpStatus {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var servers: List<McpServerStatus> = emptyList()
        private set

    /** 拿到过一份真实回执没有。 */
    var known: Boolean = false
        private set

    /**
     * 更新一份状态。**必须在 EDT 上调用** —— 监听器是同步调的（同
     * [PendingPermissionCount]），而它们会去动 Swing 组件。
     */
    fun set(newServers: List<McpServerStatus>) {
        servers = newServers
        known = true
        notifyListeners()
    }

    /**
     * 会话断了或换了 —— 上一份状态**不再作数**。
     *
     * 不清的话，用户切到一个新会话后，右侧那一栏还在显示上一个会话的 server：
     * 那比空着更糟，因为它看起来是"当前"的。
     */
    fun clear() {
        if (!known && servers.isEmpty()) return
        servers = emptyList()
        known = false
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 复制一份再遍历：监听器可能在回调里退订（同 [PendingPermissionCount]）。 */
    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    companion object {
        fun getInstance(project: Project): McpStatus = project.getService(McpStatus::class.java)
    }
}
