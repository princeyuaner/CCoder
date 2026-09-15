package com.ccoder.ui

/**
 * 排队中的一条输入（spec §3）。
 *
 * 前两个字段的分工是**不能合并**的：[text] 是发出去要用的正文，snippet 记号
 * 在入队那一刻就展开好了（那时候才拿得到 [ComposerReferences] 那张表）；
 * [typed] 是用户敲的原样，只用来认会话标题 —— 拿展开后的文本当标题会变成
 * 「```kotlin …」（见 ClaudePanel.sendCurrentInput 里同样的理由）。
 *
 * [images]（贴图，2026-09-15）**跟着这条消息走**，不是跟着输入框：撤掉这条时
 * 它的图一起没（留在输入框里的话，那几张图会串到下一条消息上）。
 */
internal data class QueuedInput(
    val text: String,
    val typed: String,
    val images: List<AttachedImage> = emptyList(),
)

/**
 * 排队中的输入。FIFO。
 *
 * **不依赖 Swing 与 Project**，所以能单测 —— 与 [MainButtonState] 同一个理由：
 * "什么时候该发下一条"是这次最容易写错的地方，而那部分不能靠人眼。
 *
 * 为什么不放在 sidecar：那边要"停干净"就得先知道回合什么时候结束，而它只知道
 * 消息发出去没有。队列在面板里，撤回与清空就都是本地动作 —— 不必求 SDK 开口子
 * （它也**没开**：`interrupt()` 不给收据，`cancel_async_message` 没暴露，
 * 见 spec §2 的事实 05）。
 */
internal class SendQueue {

    private val items = ArrayDeque<QueuedInput>()

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    fun enqueue(text: String, typed: String, images: List<AttachedImage> = emptyList()) {
        items.addLast(QueuedInput(text, typed, images))
    }

    /** 看一眼队首，**不**取走 —— flush 要先画转写区再发。 */
    fun peek(): QueuedInput? = items.firstOrNull()

    /**
     * 撤回单条（排队条上的 ✕）。
     *
     * 按内容找第一条，不做引用追踪：两条内容一模一样的消息在界面上一模一样，
     * 撤掉哪一条对用户是同一件事。**返回是否真的撤掉了一条**，调用方据此决定
     * 要不要重画。
     */
    fun remove(item: QueuedInput): Boolean = items.remove(item)

    /** 清空，返回被清掉的那些（停止 / 断线 / 切会话 / 重启）。 */
    fun drain(): List<QueuedInput> {
        val out = items.toList()
        items.clear()
        return out
    }

    /** 只读快照，给排队条排版用。 */
    fun snapshot(): List<QueuedInput> = items.toList()
}
