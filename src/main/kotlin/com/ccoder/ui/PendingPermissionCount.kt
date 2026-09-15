package com.ccoder.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 状态栏要显示的两件事（spec §6.3）：待决请求的数量、有没有被最小化的提问。
 *
 * 做成项目级服务而非让面板直接持有状态栏组件引用：平台会按需创建和销毁
 * 状态栏组件，从面板里 lookup 一个组件引用既脆弱又依赖具体 API。
 * 服务做中立的状态源，组件订阅它。
 *
 * 为什么挂起提问也放这儿、而不是另开一个服务：状态栏那**一个**组件要同时
 * 表达这两件事（挂起提问本来也计在 [count] 里），两个服务等于让组件订阅两份、
 * 两边各存一半真相。
 */
@Service(Service.Level.PROJECT)
class PendingPermissionCount {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    var count: Int = 0
        private set

    /**
     * 有没有一个被最小化的提问在等回答。点状态栏该回到那个框，而不是去开工具窗口。
     */
    var askSuspended: Boolean = false
        private set

    /**
     * 回到那个被最小化的提问。
     *
     * 状态栏是个平台组件，够不着面板；面板在挂起时把"回来的路"挂在这里
     * （见 `ClaudePanel.updateStatusBar`）。null = 现在没有可回去的提问。
     */
    var restoreAsk: (() -> Unit)? = null
        private set

    fun set(newCount: Int) {
        if (count == newCount) return
        count = newCount
        notifyListeners()
    }

    /** 更新挂起提问那两件事。值没变就不惊动状态栏（同 [set] 的短路）。 */
    fun setSuspended(suspended: Boolean, restore: (() -> Unit)?) {
        if (askSuspended == suspended && restoreAsk === restore) return
        askSuspended = suspended
        restoreAsk = restore
        notifyListeners()
    }

    private fun notifyListeners() {
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
