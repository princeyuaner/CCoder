package com.ccoder.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 一件正在跑的后台工作。
 *
 * 不叫"子代理"是因为它不只装子代理：SDK 的 task 族里还有 `local_bash`
 * （后台命令）、`local_workflow` 等。只按子代理来设计的话，后台跑的命令
 * 会整个消失 —— 而它们一样占着机器、一样该被看见。
 *
 * @param kind `subagent_type`（explore / general-purpose …）或 `task_type`（local_bash …）
 * @param detail `task_progress` 里模型生成的一行进度，比最初的 description 更贴近"现在在干嘛"
 */
internal data class RunningTask(
    val id: String,
    val kind: String?,
    val label: String?,
    val detail: String?,
    val tokens: Long,
    val durationMs: Long,
)

/**
 * 维护"现在在跑什么"和"任务清单"。
 *
 * ## 为什么成员集合用电平信号而不是加减
 *
 * `background_tasks_changed` 带的是**当前全部**在跑的任务（sdk.d.ts:3422），
 * SDK 文档专门警告过要用它**整集替换**，而不是拿 `task_started` 加一、
 * `task_notification` 减一：
 *
 * > consumers that only need 'is background work running' should replace their
 * > set with each payload rather than pairing edges, so a missed bookend cannot
 * > wedge a stale running indicator.
 *
 * 加减法漏掉任何一个结束事件，指示器就永远停在错误的数字上，而且**看不出它错了**
 * —— 这正是最该避免的一类失败。
 *
 * ## reset 的必要性
 *
 * 电平信号"在启动时不发任何东西"，所以文档要求消费者在 CLI 进程重启时
 * 自行清空。新会话走 [reset]。
 */
internal class RunStatusTracker {

    private val byId = LinkedHashMap<String, RunningTask>()

    /** 模型通过 `TodoWrite` 声明的工作清单。取不到就是 null，不造空清单。 */
    var todos: TaskList? = null
        private set

    val running: List<RunningTask> get() = byId.values.toList()

    fun reset() {
        byId.clear()
        todos = null
    }

    fun consume(event: JsonObject) {
        when (event.str("type")) {
            "assistant" -> consumeAssistant(event)
            "system" -> consumeSystem(event)
            // 其余类型与这里无关 —— 未知即忽略（spec §3.3）
        }
    }

    /**
     * 清单来自助手消息里的 `TodoWrite` 工具调用。
     *
     * 这里重新走一遍 content 而**不复用** [MessageRenderer] 的结果，是刻意的：
     * MessageRenderer 的契约是"未知即忽略"的**显示**逻辑，它有权丢掉任何它
     * 不认识的东西；这里要的是**状态**，两者的失败方式完全不同。把状态挂在
     * 显示逻辑上，将来一次显示层的取舍就会静默地让清单不再更新。
     */
    private fun consumeAssistant(event: JsonObject) {
        val content = event.obj("message")?.arr("content") ?: return
        for (block in content) {
            if (!block.isJsonObject) continue
            val b = block.asJsonObject
            if (b.str("type") != "tool_use" || b.str("name") != "TodoWrite") continue
            // 写成空清单等于清空，同样落到 null
            todos = b.obj("input")?.let(::todoListOf)
        }
    }

    private fun consumeSystem(event: JsonObject) {
        when (event.str("subtype")) {
            "background_tasks_changed" -> replaceMembership(event.arr("tasks"))

            "task_started" -> {
                val id = event.str("task_id") ?: return
                if (isAmbient(event)) {
                    byId.remove(id)
                    return
                }
                byId[id] = (byId[id] ?: blankTask(id)).with(
                    kind = event.str("subagent_type") ?: event.str("task_type"),
                    label = event.str("description"),
                )
            }

            "task_progress" -> {
                val id = event.str("task_id") ?: return
                // 只补充，不凭空造：否则一个 ambient 任务只要冒出一次 progress
                // 就会重新出现在列表里
                val prev = byId[id] ?: return
                val usage = event.obj("usage")
                byId[id] = prev.copy(
                    detail = event.str("summary") ?: prev.detail,
                    tokens = usage?.long("total_tokens") ?: prev.tokens,
                    durationMs = usage?.long("duration_ms") ?: prev.durationMs,
                )
            }

            "task_notification" -> event.str("task_id")?.let(byId::remove)

            "task_updated" -> {
                val id = event.str("task_id") ?: return
                event.obj("patch")?.str("status")?.let { status ->
                    // 终态才移除。中间态（running / paused / pending）不动
                    if (status in TERMINAL_STATUSES) byId.remove(id)
                }
            }
        }
    }

    /**
     * 整集替换成员。
     *
     * 只重定**成员**，保留已学到的 token 与时长：电平信号不带 usage，
     * 一并丢掉的话每次成员变动浮层里的数字都会闪回 0。
     */
    private fun replaceMembership(tasks: JsonArray?) {
        val next = LinkedHashMap<String, RunningTask>()
        tasks?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val o = element.asJsonObject
            val id = o.str("task_id") ?: return@forEach
            if (isAmbient(o)) return@forEach

            val prev = byId[id] ?: blankTask(id)
            next[id] = prev.with(
                kind = o.str("task_type"),
                label = o.str("description"),
            )
        }
        byId.clear()
        byId.putAll(next)
    }

    private fun blankTask(id: String) =
        RunningTask(id, kind = null, label = null, detail = null, tokens = 0, durationMs = 0)

    /** 只覆盖非 null 的字段：电平信号往往比 started 携带的信息少。 */
    private fun RunningTask.with(kind: String? = null, label: String? = null) = copy(
        kind = kind ?: this.kind,
        label = label ?: this.label,
    )

    /**
     * 文档对 ambient 的定义是"不是活动的任务"，并点名 **每一个 skip_transcript
     * 任务都算 ambient**（sdk.d.ts:5318）。不过滤的话监视类任务会让指示器永不归零。
     */
    private fun isAmbient(o: JsonObject): Boolean = o.bool("ambient") || o.bool("skip_transcript")

    private companion object {
        val TERMINAL_STATUSES = setOf("completed", "failed", "killed")
    }
}

// ---- 容错取值 ----

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
