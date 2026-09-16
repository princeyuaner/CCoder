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
 *
 * ## 为什么按 owner 聚合（2026-09-16，多标签）
 *
 * 原先是**单槽**：`set(Int)` 是覆盖、`restoreAsk` 只有一处。多标签之下两个面板
 * 会互相擦 —— 而状态栏是"回到那个被最小化的提问"的**唯一入口**，权限询问又
 * **没有超时**（`PermissionQueue` 里没有任何 deadline），被擦掉就等于那条路
 * 永远回不去，人一直等。
 *
 * 于是：计数**求和**、`askSuspended` 取"或"、`restoreAsk` 取**最近登记**的那一个，
 * 并记住它是谁的（[restoreOwner]，状态栏点击先切到那个标签再回去）。
 * 对外只读属性的**签名没变** —— 状态栏组件与它的用例一行都不用改。
 *
 * 记账在 [PendingAggregate] 里（纯 JVM 可测，这个类自己不行：要 Project）。
 */
@Service(Service.Level.PROJECT)
class PendingPermissionCount {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val aggregate = PendingAggregate()

    /** 所有标签的待决请求总数。 */
    val count: Int get() = aggregate.total

    /** 有没有**任何一个**标签挂着被最小化的提问。 */
    val askSuspended: Boolean get() = aggregate.anySuspended

    /** 回到那个被最小化的提问。null = 现在没有可回去的。 */
    val restoreAsk: (() -> Unit)? get() = aggregate.lastRestore

    /**
     * 最近登记挂起提问的那个面板。
     *
     * 多标签下"回到那个框"必须是"先回到那个标签、再回到那个框" —— 否则用户会被
     * 送到这个标签、而框在另一个标签里。
     */
    val restoreOwner: Any? get() = aggregate.restoreOwner

    fun set(owner: Any, newCount: Int) {
        if (aggregate.set(owner, newCount)) notifyListeners()
    }

    /** 更新挂起提问那两件事。值没变就不惊动状态栏（同 [set] 的短路）。 */
    fun setSuspended(owner: Any, suspended: Boolean, restore: (() -> Unit)?) {
        if (aggregate.setSuspended(owner, suspended, restore)) notifyListeners()
    }

    /** 这个面板退场（关标签 / 销毁）—— 不清的话它的计数与"回来的路"会永远留着。 */
    fun clear(owner: Any) {
        if (aggregate.clear(owner)) notifyListeners()
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

/**
 * 每个 owner 一份计数、一份挂起状态、一条"回来的路"，读数是它们的聚合。
 *
 * `seq` 记的是"谁最后改过"：`restoreAsk` 取最后登记的那一个，于是两个标签各挂着
 * 一个被最小化的提问时，状态栏会先带去**最近那个**（与"最后动过的东西在眼前"
 * 这个常识一致）。同一个 owner 重复登记只更新，不插队。
 *
 * 每次写都返回"对外读数有没有变"——没变就不该惊动状态栏（沿用原来那条短路）。
 */
internal class PendingAggregate {

    private class Entry(val owner: Any) {
        var count: Int = 0
        var suspended: Boolean = false
        var restore: (() -> Unit)? = null
        var seq: Long = 0
    }

    private val byOwner = LinkedHashMap<Any, Entry>()
    private var seq = 0L

    private fun entry(owner: Any): Entry = byOwner.getOrPut(owner) { Entry(owner) }

    private fun readouts(): List<Any?> = listOf(total, anySuspended, lastRestore, restoreOwner)

    fun set(owner: Any, newCount: Int): Boolean {
        val before = readouts()
        val e = entry(owner)
        if (e.count != newCount) {
            e.count = newCount
            e.seq = ++seq
        }
        return before != readouts()
    }

    fun setSuspended(owner: Any, suspended: Boolean, restore: (() -> Unit)?): Boolean {
        val before = readouts()
        val e = entry(owner)
        if (e.suspended != suspended || e.restore !== restore) {
            e.suspended = suspended
            e.restore = restore
            e.seq = ++seq
        }
        return before != readouts()
    }

    fun clear(owner: Any): Boolean {
        if (!byOwner.containsKey(owner)) return false
        val before = readouts()
        byOwner.remove(owner)
        return before != readouts()
    }

    val total: Int get() = byOwner.values.sumOf { it.count }

    val anySuspended: Boolean get() = byOwner.values.any { it.suspended && it.restore != null }

    val lastRestore: (() -> Unit)? get() = lastEntry()?.restore

    val restoreOwner: Any? get() = lastEntry()?.owner

    private fun lastEntry(): Entry? = byOwner.values
        .filter { it.restore != null && it.suspended }
        .maxByOrNull { it.seq }
}
