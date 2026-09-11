package com.ccoder.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 待决权限计数（spec §6.3）。
 *
 * 做成项目级服务而非让面板直接持有状态栏组件引用：平台会按需创建和销毁
 * 状态栏组件，从面板里 lookup 一个组件引用既脆弱又依赖具体 API。
 * 服务做中立的计数源，组件订阅它。
 */
@Service(Service.Level.PROJECT)
class PendingPermissionCount {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var count: Int = 0
        private set

    fun set(newCount: Int) {
        if (count == newCount) return
        count = newCount
        // 复制一份再遍历：监听器可能在回调里退订
        listeners.toList().forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        fun getInstance(project: Project): PendingPermissionCount =
            project.getService(PendingPermissionCount::class.java)
    }
}
