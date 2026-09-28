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
 * @param toolUses `task_progress.usage.tool_uses`
 * @param lastTool `task_progress.last_tool_name` —— 它此刻正在用的那个工具
 * @param paused `task_updated.patch.status == "paused"`（2026-09-28 起不再忽略）
 * @param toolUseId SDK 在 task 族事件里同时给 `task_id` 与 `tool_use_id`；后者才是
 *   与子代理记录（`SubagentInfo.toolUseId`）对号的那把钥匙 —— 从前只存 task_id，
 *   靠"两者相等"这个**没验过的假设**在对号
 */
internal data class RunningTask(
    val id: String,
    val kind: String?,
    val label: String?,
    val detail: String?,
    val tokens: Long,
    val durationMs: Long,
    val toolUses: Int = 0,
    val lastTool: String? = null,
    val paused: Boolean = false,
    val toolUseId: String? = null,
)

/** 一条任务的结局。`killed` 与 `stopped` 都归到 [Stopped]：对用户是同一件事。 */
internal enum class TaskOutcome { Done, Failed, Stopped }

/**
 * 刚结束的那一条 —— 面板"刚结束"那段的原料。
 *
 * 为什么留：`task_notification` 自带 `status` / `summary` / `usage` / **`output_file`**，
 * 而从前这条事件只做一件事 —— 把那一行从表里删掉（`byId::remove`）。于是"成了没有、
 * 败在哪、输出去哪看"在界面上是零。
 *
 * @param detail 结局摘要（`task_notification.summary`，取不到时退到跑着时的最后一句进度）
 * @param error `task_updated.patch.error` —— 失败的原因（通知事件里没有这个字段）
 * @param outputFile 这个任务**完整输出**的落盘位置（`task_notification.output_file`）
 * @param toolUseId 同 [RunningTask.toolUseId]：与子代理记录对号用
 */
internal data class FinishedTask(
    val id: String,
    val kind: String?,
    val label: String?,
    val detail: String?,
    val outcome: TaskOutcome,
    val error: String?,
    val durationMs: Long,
    val toolUses: Int,
    val tokens: Long,
    val outputFile: String?,
    val toolUseId: String?,
)

/**
 * 维护"现在在跑什么"和"任务清单"。
 *
 * ## 为什么成员集合用电平信号而不是加减
 *
 * `background_tasks_changed` 带的是**当前全部**在跑的任务（sdk.d.ts:3478），
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

    /**
     * 刚结束的那几条，**新的在前**。
     *
     * 有界（[MAX_FINISHED]）：这是"刚才那一下怎么了"的账，不是历史。要回看整段历史，
     * 那是会话文件的事，不是这张浮层的事。
     */
    private val finished = ArrayDeque<FinishedTask>()

    /**
     * 还活着的 ambient 任务（看盘、live-update 看门狗那些）。
     *
     * 它们不进 [running]（SDK 明说别拿它们当活动指示，见 [isAmbient]），
     * 但面板里要能说一句"还有 N 个后台维护任务"—— 只留一个数，不逐条画。
     */
    private val ambient = mutableSetOf<String>()

    /**
     * 任务清单的条目，顺序 = 创建顺序。
     *
     * 新一代任务工具是**增量**的（一次一条 + 打补丁），所以这里存的是逐条累积的
     * 结果，而不是某一次调用的快照。对外只暴露 [todos]。
     */
    private val items = mutableListOf<TaskItem>()

    /** `TaskCreate` 的 tool_use id → 刚建的那条。id 要等结果文本回来才认领得到。 */
    private val awaitingCreate = mutableMapOf<String, TaskItem>()

    /** `TaskList` 的 tool_use id —— 只有它的结果是一份整表快照。 */
    private val awaitingSnapshot = mutableSetOf<String>()

    /** 清单条目。[id] 是新一代任务工具的身份；老一代（`TodoWrite`）没有，为 null。 */
    private class TaskItem(var id: String?, var text: String, var state: TodoState)

    /** 模型声明的工作清单。取不到就是 null，不造空清单。 */
    var todos: TaskList? = null
        private set

    val running: List<RunningTask> get() = byId.values.toList()

    /** 刚结束的那几条（新的在前，最多 [MAX_FINISHED] 条）。 */
    val recentFinished: List<FinishedTask> get() = finished.toList()

    /** 还活着的 ambient 任务数 —— 面板里那句"还有 N 个后台维护任务"。 */
    val ambientCount: Int get() = ambient.size

    fun reset() {
        byId.clear()
        finished.clear()
        ambient.clear()
        items.clear()
        awaitingCreate.clear()
        awaitingSnapshot.clear()
        todos = null
    }

    fun consume(event: JsonObject) {
        when (event.str("type")) {
            "assistant" -> consumeAssistant(event)
            "system" -> consumeSystem(event)
            // 新一代任务工具把 id 与整表快照藏在**工具结果**里（见 TaskList.kt 那张表）——
            // 光看 assistant 侧的 tool_use 是拼不出清单的
            "user" -> consumeToolResults(event)
            // 其余类型与这里无关 —— 未知即忽略（spec §3.3）
        }
    }

    /**
     * 清单来自助手消息里的工具调用，两代都要认：
     *
     * - `TodoWrite`（老）：一次交一整张清单
     * - `TaskCreate` / `TaskUpdate` / `TaskList`（新，CLI 2.1.268 只有这代）：
     *   增量建与改，整表快照要等 `TaskList` 的结果（[consumeToolResults]）
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
            if (b.str("type") != "tool_use") continue
            val input = b.obj("input")

            when (b.str("name")) {
                // 老一代：写成空清单等于清空，同样落到 null
                "TodoWrite" -> adopt(input?.let(::todoListOf)?.items.orEmpty().map { null to it })

                "TaskCreate" -> {
                    // 入参里没有 id（sdk-tools.d.ts:2717），先建条目，id 等结果回来认领
                    val subject = input?.str("subject")?.takeIf { it.isNotBlank() } ?: continue
                    val item = TaskItem(id = null, text = subject, state = TodoState.Pending)
                    items.add(item)
                    b.str("id")?.let { awaitingCreate[it] = item }
                    syncTodos()
                }

                "TaskUpdate" -> {
                    val id = input?.str("taskId") ?: continue
                    // 认不出 id 就什么都不做：清单是跨会话续着的，可能本来就有
                    // 这条任务，只是我们没见过它（等一次 TaskList 的快照补上）
                    val item = items.firstOrNull { it.id == id } ?: continue
                    input.str("status")?.let { status ->
                        // deleted 不是状态而是"删掉"（sdk-tools.d.ts:2760 的联合类型）
                        if (status == "deleted") items.remove(item) else item.state = stateOf(status)
                    }
                    input.str("subject")?.takeIf { it.isNotBlank() }?.let { item.text = it }
                    syncTodos()
                }

                // 结果才是整表快照，这里只记下"待会儿要看它的结果"
                "TaskList" -> b.str("id")?.let { awaitingSnapshot.add(it) }
            }
        }
    }

    /**
     * 工具结果里藏着两样东西：`TaskCreate` 要认领的 id，和 `TaskList` 的整表快照。
     *
     * 两者都只认**自己发出过的**那些 tool_use（[awaitingCreate] / [awaitingSnapshot]）——
     * 照文本硬猜的话，别的工具只要恰好打印了 `#1 [completed] 写文档`，清单就会被它接管。
     */
    private fun consumeToolResults(event: JsonObject) {
        val content = event.obj("message")?.arr("content") ?: return
        for (block in content) {
            if (!block.isJsonObject) continue
            val b = block.asJsonObject
            if (b.str("type") != "tool_result") continue
            val useId = b.str("tool_use_id") ?: continue
            val text = resultText(b) ?: continue

            awaitingCreate.remove(useId)?.let { item ->
                val id = taskIdOfCreated(text)
                // 建失败时（is_error）没有 id，那条占位就不能留下 ——
                // 留着等于凭空多出一条并不存在的任务
                if (id == null && b.bool("is_error")) items.remove(item)
                else item.id = id
                syncTodos()
            }

            if (awaitingSnapshot.remove(useId)) {
                // 认不出的快照什么都不动：宁可留着旧清单，也不要把它抹成空的
                taskEntriesOf(text)?.let(::adopt)
            }
        }
    }

    /** 结果有两种形状：一个字符串，或一串 text block。真实样本是前者，两种都收。 */
    private fun resultText(block: JsonObject): String? {
        val raw = block.get("content") ?: return null
        if (raw.isJsonPrimitive && raw.asJsonPrimitive.isString) return raw.asString
        if (!raw.isJsonArray) return null
        return raw.asJsonArray
            .mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject?.str("text") }
            .joinToString("\n")
            .ifBlank { null }
    }

    /**
     * 整表替换。
     *
     * 挂着的"待认领"也一并清掉：条目都换了，认领到旧对象上等于写进虚空。
     */
    private fun adopt(entries: List<Pair<String?, TodoItem>>) {
        items.clear()
        items.addAll(entries.map { (id, item) -> TaskItem(id, item.text, item.state) })
        awaitingCreate.clear()
        syncTodos()
    }

    /** 条目变了就重算对外的 [todos]：空清单是 null，不造"0/0"。 */
    private fun syncTodos() {
        todos = items.takeIf { it.isNotEmpty() }
            ?.let { list -> TaskList(list.map { TodoItem(it.text, it.state) }) }
    }

    private fun consumeSystem(event: JsonObject) {
        when (event.str("subtype")) {
            "background_tasks_changed" -> replaceMembership(event.arr("tasks"))

            "task_started" -> {
                val id = event.str("task_id") ?: return
                if (isAmbient(event)) {
                    byId.remove(id)
                    ambient.add(id)
                    return
                }
                ambient.remove(id)
                byId[id] = (byId[id] ?: blankTask(id)).with(
                    kind = event.str("subagent_type") ?: event.str("task_type"),
                    label = event.str("description"),
                    toolUseId = event.str("tool_use_id"),
                )
            }

            "task_progress" -> {
                val id = event.str("task_id") ?: return
                // 只补充，不凭空造：否则一个 ambient 任务只要冒出一次 progress
                // 就会重新出现在列表里
                val prev = byId[id] ?: return
                val usage = event.obj("usage")
                byId[id] = prev.copy(
                    // 进行时优先用 summary（模型写的一句话，每 ~30s 一条，要 CLI 开着
                    // agentProgressSummaries），没有就用 **description** —— 那是
                    // "当前这一步"（"Reading x.kt"），**每一步都来、不要钱**，
                    // 于是短子代理也有第二行（B2 的两行读数就靠这两级）。
                    detail = event.str("summary") ?: event.str("description") ?: prev.detail,
                    tokens = usage?.long("total_tokens") ?: prev.tokens,
                    durationMs = usage?.long("duration_ms") ?: prev.durationMs,
                    toolUses = usage?.long("tool_uses")?.toInt() ?: prev.toolUses,
                    lastTool = event.str("last_tool_name") ?: prev.lastTool,
                )
            }

            // 结束：留一条"刚结束"的账，再把人从在跑的表里摘掉
            "task_notification" -> finishFromNotification(event)

            "task_updated" -> {
                val id = event.str("task_id") ?: return
                val patch = event.obj("patch") ?: return
                // 描述被改（"移到了后台"之类）也认 —— 面板上那行名字要跟着走
                patch.str("description")?.let { text ->
                    byId[id]?.let { byId[id] = it.copy(label = text) }
                }
                when (patch.str("status")) {
                    // 暂停：从前明确不动（那时面板只有"在跑"一档，暂停没有落点）
                    "paused" -> byId[id]?.let { byId[id] = it.copy(paused = true) }
                    "running", "pending" -> byId[id]?.let { byId[id] = it.copy(paused = false) }
                    in TERMINAL_STATUSES -> finishFromUpdate(id, patch)
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
        val nextAmbient = mutableSetOf<String>()
        tasks?.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val o = element.asJsonObject
            val id = o.str("task_id") ?: return@forEach
            if (isAmbient(o)) {
                nextAmbient.add(id)
                return@forEach
            }

            val prev = byId[id] ?: blankTask(id)
            next[id] = prev.with(
                kind = o.str("task_type"),
                label = o.str("description"),
            )
        }
        byId.clear()
        byId.putAll(next)
        // ambient 也整集替换：这一版里就有、下一版里没了，那个数跟着回落
        ambient.clear()
        ambient.addAll(nextAmbient)
    }

    /**
     * `task_updated` 报到终态：把手上这条挪进"刚结束"。
     *
     * 认不出的 id（没 started 过）就什么都不做 —— 没有名字可画，记一条空行只是噪声。
     */
    private fun finishFromUpdate(id: String, patch: JsonObject) {
        // 先把它从管家任务里摘掉，**再**看有没有可记的：一个 ambient 任务报了终态，
        // 我们不记它（它本来就不是活动），但"它结束了"这件事必须让那个计数跟着回落
        ambient.remove(id)
        val prev = byId.remove(id) ?: return
        record(
            FinishedTask(
                id = id,
                kind = prev.kind,
                label = prev.label,
                detail = prev.detail,
                outcome = outcomeOf(patch.str("status")),
                error = patch.str("error"),
                durationMs = prev.durationMs,
                toolUses = prev.toolUses,
                tokens = prev.tokens,
                outputFile = null,
                toolUseId = prev.toolUseId,
            ),
        )
    }

    /**
     * `task_notification`：结局 + 输出文件都在这条事件上，是"刚结束"那段的主来源。
     *
     * 它与 `task_updated` 的终态**会先后到**（谁先谁后不定），所以同一个 id 认两回：
     * 后到的把先到的那条补齐（通知带来 `output_file`，更新带来 `error`），不新增第二行。
     */
    private fun finishFromNotification(event: JsonObject) {
        val id = event.str("task_id") ?: return
        val usage = event.obj("usage")
        val prev = byId.remove(id)
        ambient.remove(id)
        val existing = finished.firstOrNull { it.id == id }
        // started 没见过、更新的终态也没见过 —— 那就真没有名字，不留空行
        if (prev == null && existing == null) return

        record(
            FinishedTask(
                id = id,
                kind = prev?.kind ?: existing?.kind,
                label = prev?.label ?: existing?.label,
                detail = event.str("summary") ?: prev?.detail ?: existing?.detail,
                outcome = outcomeOf(event.str("status")),
                // 通知里没有 error —— 那一条来自 task_updated
                error = existing?.error,
                durationMs = usage?.long("duration_ms") ?: prev?.durationMs ?: existing?.durationMs ?: 0,
                toolUses = usage?.long("tool_uses")?.toInt() ?: prev?.toolUses ?: existing?.toolUses ?: 0,
                tokens = usage?.long("total_tokens") ?: prev?.tokens ?: existing?.tokens ?: 0,
                // **空白当没有**：实测（2026-09-28）CLI 对某些任务给的是空串而不是省略，
                // 拿它去开文件只会得到一句"路径认不出来"，而且那颗「看输出」按钮
                // 是照 outputFile != null 画的 —— 空串会让按钮出现却点不动
                outputFile = event.str("output_file")?.takeIf { it.isNotBlank() } ?: existing?.outputFile,
                toolUseId = event.str("tool_use_id") ?: prev?.toolUseId ?: existing?.toolUseId,
            ),
        )
    }

    /** 同 id 的替换掉（两路事件都会来），新的排前面，只留 [MAX_FINISHED] 条。 */
    private fun record(task: FinishedTask) {
        finished.removeAll { it.id == task.id }
        finished.addFirst(task)
        while (finished.size > MAX_FINISHED) finished.removeLast()
    }

    /**
     * 终态词 → 那个勾/叉。
     *
     * 两套词表要合着认：`task_updated.patch.status` 是
     * `completed / failed / killed`，`task_notification.status` 是
     * `completed / failed / stopped`（sdk.d.ts:6011 与 :5906）。少认一个，
     * 那类结束就会落到"完成"那一档 —— 比不显示更糟。
     */
    private fun outcomeOf(status: String?): TaskOutcome = when (status) {
        "failed" -> TaskOutcome.Failed
        "killed", "stopped" -> TaskOutcome.Stopped
        else -> TaskOutcome.Done
    }

    private fun blankTask(id: String) =
        RunningTask(id, kind = null, label = null, detail = null, tokens = 0, durationMs = 0)

    /** 只覆盖非 null 的字段：电平信号往往比 started 携带的信息少。 */
    private fun RunningTask.with(
        kind: String? = null,
        label: String? = null,
        toolUseId: String? = null,
    ) = copy(
        kind = kind ?: this.kind,
        label = label ?: this.label,
        toolUseId = toolUseId ?: this.toolUseId,
    )

    /**
     * 文档对 ambient 的定义是"不是活动的任务"，并点名 **每一个 skip_transcript
     * 任务都算 ambient**（sdk.d.ts:3487）。不过滤的话监视类任务会让指示器永不归零。
     */
    private fun isAmbient(o: JsonObject): Boolean = o.bool("ambient") || o.bool("skip_transcript")

    private companion object {
        val TERMINAL_STATUSES = setOf("completed", "failed", "killed")

        /** "刚结束"那段留几条。两条：够看见"刚才那一下怎么了"，又不至于变成历史列表。 */
        const val MAX_FINISHED = 2
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
