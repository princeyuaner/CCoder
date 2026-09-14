package com.ccoder.ui

import com.google.gson.JsonObject

internal enum class TodoState { Pending, InProgress, Completed }

internal data class TodoItem(val text: String, val state: TodoState)

/**
 * 模型声明的工作清单。
 *
 * 条目用 `content`（新一代是 `subject`）而不是 `activeForm`：后者是"正在做…"
 * 的进行时措辞，整套列出来会变成每条都"正在…"，读起来像全都在跑。
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
 * 这是**老一代**的形状：一次交一整张清单。新一代见 [taskIdOfCreated] /
 * [taskEntriesOf] —— CLI 2.1.268 只提供新一代，老这条留着是为了兼容
 * 还在给 `TodoWrite` 的版本（两代形状与那道闸门见
 * `docs/superpowers/specs/2026-09-13-status-cards-design.md` §6.1）。
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
 * 新一代任务工具（`TaskCreate` / `TaskUpdate` / `TaskList`）的解析。
 *
 * 与 `TodoWrite` 的根本区别是**增量**：一次只建一条，改状态靠另一个调用打补丁。
 * 所以光看 tool_use 不够 —— id 在**结果文本**里回来，整表快照也在结果里。
 * 下面三种形状都是 2026-09-14 在真实会话里抓的（CLI 2.1.268）：
 *
 * ```
 * Task #1 created successfully: 写文档     ← TaskCreate 的结果（[taskIdOfCreated]）
 * Updated task #1 status                   ← TaskUpdate 的结果：不解析，只说明形状
 * #1 [completed] 写文档                    ← TaskList 的结果，一行一条（[taskEntriesOf]）
 * #2 [pending] 跑测试
 * ```
 */

/** `Task #1 created successfully: 写文档` → `1`。认不出给 null，调用方保留占位条目。 */
internal fun taskIdOfCreated(result: String): String? =
    CREATED_ID.find(result)?.groupValues?.get(1)

private val CREATED_ID = Regex("""Task #(\S+) created successfully""")

/**
 * `#1 [completed] 写文档` 逐行 → (id, 条目)。
 *
 * **一行都认不出时返回 null，不返回空表**：空表会被读成"清单空了"，
 * 把界面上好好的一张卡抹掉 —— 那是在说一件我们并不知道的事。
 */
internal fun taskEntriesOf(result: String): List<Pair<String, TodoItem>>? {
    val entries = result.lineSequence().mapNotNull { line ->
        val m = SNAPSHOT_LINE.find(line.trim()) ?: return@mapNotNull null
        val text = m.groupValues[3].trim()
        if (text.isEmpty()) return@mapNotNull null
        m.groupValues[1] to TodoItem(text, stateOf(m.groupValues[2]))
    }.toList()

    return entries.takeIf { it.isNotEmpty() }
}

/** `#<id> [<status>] <subject>`。id 不假定是数字 —— 团队模式下的 id 不是。 */
private val SNAPSHOT_LINE = Regex("""^#(\S+)\s+\[([a-z_]+)]\s+(.+)$""")

/**
 * 未知状态当作待办，**不丢掉这一条**。
 *
 * SDK 目前只有三种状态（新一代多一个 `deleted`，那个由调用方处理成删除），
 * 但这个联合类型会随版本增长。丢掉整条会让清单看起来"少了几项"，
 * 比状态不准更难察觉。
 */
internal fun stateOf(raw: String?): TodoState = when (raw) {
    "completed" -> TodoState.Completed
    "in_progress" -> TodoState.InProgress
    else -> TodoState.Pending
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
