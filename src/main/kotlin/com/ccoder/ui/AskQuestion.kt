package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * `AskUserQuestion` 的入参模型。
 *
 * 形状照抄 SDK 的 `AskUserQuestionInput`（sdk-tools.d.ts:1051）——
 * 那是权威来源，不是猜的。
 */
internal data class AskOption(
    val label: String,
    val description: String,
    /** SDK 可选给的预览：代码片段、mockup，用来横向比方案。 */
    val preview: String?,
)

internal data class AskQuestion(
    val question: String,
    /** 那个 12 字以内的小芯片。缺了就是空串，芯片不显示。 */
    val header: String,
    val options: List<AskOption>,
    val multiSelect: Boolean,
)

internal data class AskRequest(val questions: List<AskQuestion>)

/** 题目文本 → 选中的 label 列表。自由文本也走这个形状（就一项）。 */
internal typealias Picked = Map<String, List<String>>

/**
 * 「其它…」这一项在界面上的文字。
 *
 * **它不是 SDK 给的选项** —— SDK 在 `options` 的注释里明确写着
 * "There should be no 'Other' option, that will be provided automatically"，
 * 也就是这是宿主的活（sdk-tools.d.ts:1070）。所以它由界面自己加上去。
 *
 * ## 它也被当身份用（[answersFor] 里按它挑出"用户自己写的那条"）
 *
 * 那是一处**常量与常量比**：这个标签是我们自己插进选项里的，两边取的是同一个键、
 * 同一份词表，而语言在一个会话的生命周期里是定的 —— 所以成立。
 * 但它是隐含前提：哪天有人让语言在一个会话中途变，这里会静默失配
 * （同 `ConnectionState` 注释里那类，已有的用例 `AskQuestionTest` 盯着它）。
 */
internal val OTHER_LABEL: String get() = CcoderText.text("ask.otherLabel")

/**
 * 一道题的作答状态。与 Swing 无关，可单独测。
 *
 * 「其它…」和普通选项放在**同一个集合**里管理 —— 它就是一个额外的选项，
 * 单选时享受同样的互斥，多选时可以和别的共存。特判它反而会多出一堆分支。
 * 只在 [answer] 里把它换成用户打的字。
 */
internal class QuestionState(val question: AskQuestion) {

    private val selected = linkedSetOf<String>()

    /** 「其它…」那一栏里用户打的字。 */
    var custom: String = ""

    val multiSelect: Boolean get() = question.multiSelect

    fun isSelected(label: String): Boolean = label in selected

    fun toggle(label: String) {
        if (!selected.remove(label)) {
            // 单选时先清空再放进去；多选就只是加
            if (!question.multiSelect) selected.clear()
            selected.add(label)
        }
    }

    /**
     * 这一题的答案。
     *
     * 「其它…」被换成用户打的字；**空的自由文本会被滤掉** ——
     * 否则点一下「其它…」就凑出一个空字符串答案，看着像答了。
     */
    fun answer(): List<String> = selected
        .map { label -> if (label == OTHER_LABEL) custom.trim() else label }
        .filter { it.isNotEmpty() }

    val answered: Boolean get() = answer().isNotEmpty()
}

/** 整张卡片的作答状态。 */
internal class AskState(private val request: AskRequest) {

    /** 按位置对应 [AskRequest.questions]，不用 Map —— 两道题文本相同时不会互相覆盖。 */
    val states: List<QuestionState> = request.questions.map(::QuestionState)

    fun picked(): Picked =
        request.questions.mapIndexed { i, q -> q.question to states[i].answer() }.toMap()

    val complete: Boolean get() = states.all { it.answered }
}

/**
 * 解析 `AskUserQuestion` 的入参；解析不出来返回 null。
 *
 * 原则是**输入宽容、判定严格**：
 *
 * - 字段缺了给合理默认值（缺 header 就不显示芯片，缺 description 就是空说明），
 *   不至于因为少个小字段就整条不可用；
 * - 但只要有东西**没法渲染**，就整个返回 null，让调用方退回通用卡片 ——
 *   半截的提问卡片比没有更糟，因为用户会以为剩下的问题不存在，
 *   而我们会把一个不完整的答案送回去。
 *
 * 返回 null 的调用方（[ClaudePanel]）必须真的退回通用卡片，
 * 而不是显示一张空的。
 */
internal fun askRequestOf(input: JsonObject): AskRequest? {
    val raw = input.arr("questions") ?: return null
    if (raw.size() == 0) return null

    val questions = raw.mapNotNull { parseQuestion(it) }
    // 数量对不上 = 有题没解析出来。这是"半截"，不是"部分成功"
    if (questions.size != raw.size()) return null

    return AskRequest(questions)
}

private fun parseQuestion(element: JsonElement): AskQuestion? {
    if (!element.isJsonObject) return null
    val obj = element.asJsonObject

    // 题目文本是这张卡片唯一一定要显示的东西，缺了就没得渲染
    val text = obj.str("question")?.takeIf { it.isNotBlank() } ?: return null

    val rawOptions = obj.arr("options") ?: return null
    val options = rawOptions.mapNotNull { parseOption(it) }
    if (options.isEmpty()) return null

    return AskQuestion(
        question = text,
        header = obj.str("header").orEmpty(),
        options = options,
        multiSelect = obj.bool("multiSelect") ?: false,
    )
}

private fun parseOption(element: JsonElement): AskOption? {
    if (!element.isJsonObject) return null
    val obj = element.asJsonObject

    // label 是回传答案时唯一能表示"选了哪个"的东西。
    // 没有它的选项渲染得出来却答不回去 —— 那是个骗人的按钮，直接丢掉
    val label = obj.str("label")?.takeIf { it.isNotBlank() } ?: return null

    return AskOption(
        label = label,
        description = obj.str("description").orEmpty(),
        preview = obj.str("preview")?.takeIf { it.isNotBlank() },
    )
}

/**
 * 组装 SDK 要的 `answers`。
 *
 * 值是 **string 而不是数组**（sdk-tools.d.ts:3861 的
 * `answers: { [k: string]: string }`），所以多选时要把若干 label 连成一个 ——
 * 连接符是**逗号加空格**：那是 SDK 写明的形状（sdk-tools.d.ts:3873：
 * "multi-select answers are comma-separated"）。
 *
 * 从前用「、」，理由是"读起来是并列，而逗号会和 label 自带的逗号混淆"。
 * 读起来确实顺，但它偏了契约 —— 别的宿主/界面按逗号切答案时切不开（2026-09-20
 * 改回）。label 真带逗号的多选答案是这条契约自带的歧义，不值得为它另立一套；
 * 选项本来就该是短标签（SDK 的 header 甚至限 12 字）。
 */
internal fun answersFor(request: AskRequest, picked: Picked): JsonObject {
    val out = JsonObject()
    request.questions.forEach { q ->
        val chosen = picked[q.question].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        if (chosen.isNotEmpty()) out.addProperty(q.question, chosen.joinToString(", "))
    }
    return out
}

/** `AskUserQuestion` 的工具名。SDK 那边的字面量，拼错就永远匹配不上。 */
internal const val ASK_TOOL_NAME = "AskUserQuestion"

/**
 * 把答案并进工具入参，作为 `updatedInput` 送回去。
 *
 * **是整份入参的副本加一个 `answers`，不是只有答案** —— SDK 把 updatedInput
 * 当作这个工具的完整新入参，只给 answers 的话 `questions` 就没了，工具那边不认。
 *
 * 不改动传进来的对象：那份 `JsonObject` 是 sidecar 消息的一部分，
 * 别处可能还在读它。
 */
internal fun updatedInputFor(input: JsonObject, request: AskRequest, picked: Picked): JsonObject {
    val out = input.deepCopy()
    out.add("answers", answersFor(request, picked))
    return out
}

/**
 * 每道题都答了才允许提交。
 *
 * 少答一题就提交，等于替用户答了一题 —— 而这道题的回答会被当成
 * "用户的选择"喂回模型。
 */
internal fun allAnswered(request: AskRequest, picked: Picked): Boolean =
    request.questions.all { q -> picked[q.question].orEmpty().any { it.isNotBlank() } }

// ---- 容错取值：类型不符时返回 null 而非抛 ClassCastException ----

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun JsonObject.arr(key: String): com.google.gson.JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
