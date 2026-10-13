package com.ccoder.ui

/**
 * 定时待发的一条（2026-10-13，定时发送设计稿乙案 → 用户选丙）。
 *
 * [payload] 与排队同一种载荷（[QueuedInput]）：正文的展开在**定下去的那一刻**
 * 就做完了 —— 那时候才拿得到 [ComposerReferences] 那张表，等 18:00 到了再展开，
 * 记号早就不在文本里了。`typed` 是给转写区看的那份（气泡里画用户敲的原样）。
 */
internal data class ScheduledSend(
    val id: String,
    /** 到点时刻（epoch millis）。到点判据是 `fireAt <= now`。 */
    val fireAt: Long,
    val payload: QueuedInput,
)

/**
 * 定时待发的那张表。**不依赖 Swing 与 Project**，所以能单测 ——
 * 与 [SendQueue] 同一个理由："什么时候该发、先发谁"正是这里最容易写错的部分。
 *
 * 与 [SendQueue] 的分工：排队是"回合一结束就发"，定时是"墙上钟到点再发"。
 * 两条寿命一样（都只活这次会话），清空时机也一样（见 ClaudePanel.clearScheduled）。
 */
internal class ScheduledSends {

    private val items = mutableListOf<ScheduledSend>()

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    fun add(item: ScheduledSend) {
        items.add(item)
    }

    /**
     * 撤回单条（气泡上的 ✕）。按 id 找 —— 与排队那条按内容找不同：
     * 两条内容一样的定时在屏幕上是两枚气泡，按内容找会撤错那一枚。
     *
     * **返回是否真的撤掉了一条**，调用方据此决定要不要重画与重排定时器。
     */
    fun removeById(id: String): Boolean = items.removeAll { it.id == id }

    /** 清空，返回被清掉的那些（停止会话 / 断线 / 重启 / 切会话）。 */
    fun drain(): List<ScheduledSend> {
        val out = items.toList()
        items.clear()
        return out
    }

    /** 只读快照，**按到点时刻排序** —— 界面上的显示顺序与发送顺序都以它为准。 */
    fun snapshot(): List<ScheduledSend> = items.sortedBy { it.fireAt }

    /** 到点该发的那批（`fireAt <= now`），按时刻升序。 */
    fun due(now: Long): List<ScheduledSend> = snapshot().filter { it.fireAt <= now }

    /** 下一次触发的时刻；表空给 null（调用方据此停掉定时器）。 */
    fun nextFireAt(): Long? = items.minOfOrNull { it.fireAt }
}
