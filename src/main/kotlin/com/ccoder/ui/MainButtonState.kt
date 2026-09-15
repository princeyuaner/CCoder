package com.ccoder.ui

/** 输入区右侧那个按钮要做什么。 */
internal enum class MainAction { Send, Interrupt, Restart, Disabled }

internal data class MainButton(val text: String, val action: MainAction, val enabled: Boolean)

/**
 * 发送/停止合一按钮的状态。
 *
 * 抽成纯函数是因为 ClaudePanel 依赖 Swing 与平台、起不了单测，而
 * "什么时候该显示停止"正是这次改动里最容易写错的部分。
 *
 * 规则，按优先级：
 *  1. 会话已 fatal 断开 → "重启会话"，点击重连。
 *     **优先于"停止"**：进程都没了，没有东西可以中断
 *  2. 回合进行中 → "停止"，点击发 `interrupt` 让模型停下。
 *     注意不是 `stop` —— `stop` 会销毁整个会话（index.js 里把 session 置 null），
 *     用户会看到"已连接"却再也发不出消息。
 *     **队列非空时文案要带上条数**：点下去排队的一起没（spec §5.4），
 *     不说的话那两条是无声消失的
 *  3. 未就绪且未断开 → "启动中…"，禁用
 *  4. 其余 → "发送"
 */
internal fun mainButtonState(
    ready: Boolean,
    busy: Boolean,
    disconnected: Boolean,
    /** 队列里排着几条。默认 0 = 没排队，与从前一字不差。 */
    queued: Int = 0,
): MainButton = when {
    disconnected -> MainButton("重启会话", MainAction.Restart, enabled = true)
    busy && queued > 0 ->
        MainButton("停止（并清掉 $queued 条排队）", MainAction.Interrupt, enabled = true)
    busy -> MainButton("停止", MainAction.Interrupt, enabled = true)
    !ready -> MainButton("启动中…", MainAction.Disabled, enabled = false)
    else -> MainButton("发送", MainAction.Send, enabled = true)
}
