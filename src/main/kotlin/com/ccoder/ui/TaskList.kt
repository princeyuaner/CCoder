package com.ccoder.ui

import com.google.gson.JsonObject

internal enum class TodoState { Pending, InProgress, Completed }

internal data class TodoItem(val text: String, val state: TodoState)

/**
 * 模型通过 `TodoWrite` 声明的工作清单。
 *
 * 条目用 `content` 而不是 `activeForm`：后者是"正在做…"的进行时措辞，
 * 整套列出来会变成每条都"正在…"，读起来像全都在跑。
 * `activeForm` 只适合给进行中那一项当标题，因此单独由 [current] 提供。
 */
internal data class TaskList(val items: List<TodoItem>) {
    val total: Int get() = items.size
    val completed: Int get() = items.count { it.state == TodoState.Completed }

    /** 进行中那一项的 content；模型偶尔同时标两项，取第一个——条上只放得下一个。 */
    val current: String? get() = items.firstOrNull { it.state == TodoState.InProgress }?.text
}

/**
 * 从 `TodoWrite` 的 tool_use input 里解析清单（`sdk-tools.d.ts:1017`）。
 *
 * **空清单返回 null，不返回空 TaskList**：显示"0/0"是假信息，
 * 与 [contextUsageOf] 取不到就隐藏是同一条规则。
 *
 * 非 todo 事件的 input 也返回 null —— 别的工具恰好带一个 `todos` 字段
 * 不该被当清单。
 */
internal fun todoListOf(input: JsonObject): TaskList? {
    val raw = input.get("todos")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val items = raw.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val obj = element.asJsonObject
        // 没有文字的条目跳过：留下它会渲染成一个空行
        val text = obj.str("content")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        TodoItem(text, stateOf(obj.str("status")))
    }
    return items.takeIf { it.isNotEmpty() }?.let(::TaskList)
}

/**
 * 未知状态当作待办，**不丢掉这一条**。
 *
 * SDK 目前只有三种状态，但这个联合类型会随版本增长。丢掉整条会让清单
 * 看起来"少了几项"，比状态不准更难察觉。
 */
private fun stateOf(raw: String?): TodoState = when (raw) {
    "completed" -> TodoState.Completed
    "in_progress" -> TodoState.InProgress
    else -> TodoState.Pending
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
