package com.ccoder.ui

import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 把转写操作序列化为推送用的 JSON。
 *
 * 契约的另一端在 `web/src/codec.ts`，两侧共用 `shared/transcript-ops.json`
 * 做测试 —— 任一端改了契约，另一端的测试就会变红。
 *
 * 输出由 [encodePushCall] 嵌进 `window.ccoder.pushBatch(...)` 调用，因此：
 * - 换行由 JSON 转义保证，不产生多行
 * - Gson 默认转义 HTML 敏感字符（`<` `>` `&` `=` `'`），
 *   避免内容里的 `</script>` 提前闭合脚本块
 */
object TranscriptOpCodec {

    /**
     * 构造注入页面的推送调用。
     *
     * 参数必须是 **JS 字符串字面量**，不能把 JSON 直接内联成对象字面量。
     * web 侧 `pushBatch(json: string)` 的契约是收字符串再 `JSON.parse`：
     *
     *   pushBatch('[{...}]')   → JSON.parse 得到数组 → 正常
     *   pushBatch([{...}])     → JSON.parse 收到数组，先被 String() 成
     *                            "[object Object],[object Object]" → SyntaxError
     *                            → 被 pushBatch 里的 catch 吞掉
     *
     * 后者不会报任何错：界面全空、状态栏照常显示"已连接"。
     * 2026-09-11 实际踩过这个坑，排查花了大半程。
     */
    fun encodePushCall(batchJson: String): String =
        "window.ccoder.pushBatch('${ThemeInjector.escapeForJsString(batchJson)}');"

    fun encodeBatch(ops: List<TranscriptOp>): String {
        val array = JsonArray()
        ops.forEach { array.add(encode(it)) }
        return array.toString()
    }

    /**
     * 把一批 op 切成"单次 `executeJavaScript` 吃得下"的小片。
     *
     * 为什么必须切：IDEA 2026.2 的 remote JCEF（CEF 144）对**大报文**极其脆 ——
     * 几 MB 的 `executeJavaScript` 会把 RPC 通道直接掐断，而且是静默的
     * （`RpcExecutor.exec` 自己 return）。用户看到的是「输出多一些内容就卡死」。
     *
     * 两层：
     * 1. **超大正文拆成增量**：`FinalizeDelta` / 大段 `Append` 正文改成一串
     *    `AppendDelta` + 一条**空文本**的 `FinalizeDelta`（web 侧约定：空 = 把
     *    live 缓冲落成条目，见 `codec.ts`）。字一个不少，报文却始终是小片。
     * 2. **按估算体积打包**：[DEFAULT_MAX_SCRIPT_CHARS] 一条上限，装不下就换下一拍。
     */
    fun splitForWire(
        ops: List<TranscriptOp>,
        maxScriptChars: Int = DEFAULT_MAX_SCRIPT_CHARS,
    ): List<List<TranscriptOp>> {
        val expanded = ops.flatMap { expandLarge(it) }
        val out = mutableListOf<List<TranscriptOp>>()
        var cur = mutableListOf<TranscriptOp>()
        var curSize = 2 // "[]"
        for (op in expanded) {
            val cost = estimateChars(op)
            if (cur.isNotEmpty() && curSize + cost > maxScriptChars) {
                out += cur
                cur = mutableListOf()
                curSize = 2
            }
            cur += op
            curSize += cost + 1
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** 单条 op 的 JSON 体积估算（比真实编码略偏大，宁可多切几片）。 */
    private fun estimateChars(op: TranscriptOp): Int = when (op) {
        is TranscriptOp.Reset -> 20
        is TranscriptOp.AppendDelta -> op.text.length + 60
        is TranscriptOp.FinalizeDelta -> op.text.length + 70
        is TranscriptOp.ClearDelta -> 40
        is TranscriptOp.Append -> when (val it = op.item) {
            is TranscriptItem.User -> it.text.length + it.images.sumOf { s -> s.length } + 80
            is TranscriptItem.Assistant -> it.text.length + 80
            is TranscriptItem.Thinking -> it.text.length + 80
            is TranscriptItem.ToolUse -> it.input.length + 120
            is TranscriptItem.ToolResult -> it.text.length + 100
            is TranscriptItem.Error -> it.text.length + 50
            is TranscriptItem.Result -> 150
            is TranscriptItem.SystemNote -> it.text.length + 50
        }
    }

    /**
     * 超大正文拆成"增量流 + 空收尾"。
     *
     * 阈值 [CHUNK_CHARS]：正常一拍的增量远小于此，不会走这条路；
     * 而一次完整回复 / 思考块轻松超过它。
     */
    private fun expandLarge(op: TranscriptOp): List<TranscriptOp> {
        if (op is TranscriptOp.FinalizeDelta && op.text.length > CHUNK_CHARS) {
            return op.text.chunked(CHUNK_CHARS).map { TranscriptOp.AppendDelta(op.target, it) } +
                TranscriptOp.FinalizeDelta(op.target, "")
        }
        if (op is TranscriptOp.AppendDelta && op.text.length > CHUNK_CHARS) {
            return op.text.chunked(CHUNK_CHARS).map { TranscriptOp.AppendDelta(op.target, it) }
        }
        // 无 parent 的正文 / 思考走 FinalizeDelta 那条同款路（拆增量 + 空收尾）。
        // **带 parent 的不拆** —— FinalizeDelta 表达不了归属，拆了会把子代理的话
        // 画进主流水；那种先靠 truncateForTranscript / shrinkToolInput 限住体积。
        if (op is TranscriptOp.Append) {
            val assistant = op.item as? TranscriptItem.Assistant
            if (assistant != null && assistant.parent == null && assistant.text.length > CHUNK_CHARS) {
                return assistant.text.chunked(CHUNK_CHARS)
                    .map { TranscriptOp.AppendDelta("assistant", it) } +
                    TranscriptOp.FinalizeDelta("assistant", "")
            }
            val thinking = op.item as? TranscriptItem.Thinking
            if (thinking != null && thinking.parent == null && thinking.text.length > CHUNK_CHARS) {
                return thinking.text.chunked(CHUNK_CHARS)
                    .map { TranscriptOp.AppendDelta("thinking", it) } +
                    TranscriptOp.FinalizeDelta("thinking", "")
            }
        }
        return listOf(op)
    }

    /** 单片脚本的字符上限。约 48KB JSON —— 远低于会掐断 remote JCEF 的量级。 */
    const val DEFAULT_MAX_SCRIPT_CHARS = 48_000

    /** 超过这个长度的正文拆片。一片 32KB，48KB 的脚本预算装得下。 */
    const val CHUNK_CHARS = 32_000

    private fun encode(op: TranscriptOp): JsonObject = when (op) {
        is TranscriptOp.Reset -> JsonObject().apply { addProperty("op", "reset") }

        is TranscriptOp.Append -> JsonObject().apply {
            addProperty("op", "append")
            add("item", encodeItem(op.item))
        }

        is TranscriptOp.AppendDelta -> JsonObject().apply {
            addProperty("op", "appendDelta")
            addProperty("target", op.target)
            addProperty("text", op.text)
        }

        is TranscriptOp.FinalizeDelta -> JsonObject().apply {
            addProperty("op", "finalizeDelta")
            addProperty("target", op.target)
            addProperty("text", op.text)
        }

        is TranscriptOp.ClearDelta -> JsonObject().apply {
            addProperty("op", "clearDelta")
            addProperty("target", op.target)
        }
    }

    private fun encodeItem(item: TranscriptItem): JsonObject {
        val obj = JsonObject().apply {
            addProperty("id", item.id)
            addProperty("ts", item.ts)
        }

        when (item) {
            is TranscriptItem.User -> {
                obj.addProperty("kind", "user")
                obj.addProperty("text", item.text)
                // **没图时一个字段都不加**：纯文字那条路上的报文与从前一字不差
                // （既有的编解码测试也就不用改）
                if (item.images.isNotEmpty()) {
                    obj.add("images", JsonArray().apply { item.images.forEach { add(it) } })
                }
            }

            is TranscriptItem.Assistant -> {
                obj.addProperty("kind", "assistant")
                obj.addProperty("text", item.text)
                // 只有子代理说的才带这个字段：主线程那条路上的报文与从前一字不差
                item.parent?.let { obj.addProperty("parent", it) }
            }

            is TranscriptItem.Thinking -> {
                obj.addProperty("kind", "thinking")
                obj.addProperty("text", item.text)
                item.parent?.let { obj.addProperty("parent", it) }
            }

            is TranscriptItem.Error -> {
                obj.addProperty("kind", "error")
                obj.addProperty("text", item.text)
            }

            is TranscriptItem.SystemNote -> {
                obj.addProperty("kind", "systemNote")
                obj.addProperty("text", item.text)
            }

            is TranscriptItem.ToolUse -> {
                obj.addProperty("kind", "toolUse")
                obj.addProperty("toolUseId", item.toolUseId)
                obj.addProperty("name", item.name)
                obj.addProperty("input", item.input)
                item.parent?.let { obj.addProperty("parent", it) }
            }

            is TranscriptItem.ToolResult -> {
                obj.addProperty("kind", "toolResult")
                obj.addProperty("toolUseId", item.toolUseId)
                obj.addProperty("text", item.text)
                obj.addProperty("isError", item.isError)
            }

            is TranscriptItem.Result -> {
                obj.addProperty("kind", "result")
                obj.addProperty("subtype", item.subtype)
                // 可选字段为 null 时整体省略，让 React 侧少一层判空
                item.costUsd?.let { obj.addProperty("costUsd", it) }
                item.durationMs?.let { obj.addProperty("durationMs", it) }
                item.inputTokens?.let { obj.addProperty("inputTokens", it) }
                item.outputTokens?.let { obj.addProperty("outputTokens", it) }
                item.cacheReadTokens?.let { obj.addProperty("cacheReadTokens", it) }
            }
        }
        return obj
    }
}
