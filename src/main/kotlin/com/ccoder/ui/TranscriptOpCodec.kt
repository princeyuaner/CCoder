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
                // 零值不写：老前端只认 text，多余字段它本来就会忽略，
                // 但契约 fixture 是两侧共用的，多写等于把噪音钉进契约
                if (item.images.isNotEmpty()) {
                    obj.add("images", JsonArray().apply {
                        item.images.forEach { img ->
                            add(JsonObject().apply {
                                addProperty("mediaType", img.mediaType)
                                addProperty("data", img.base64)
                            })
                        }
                    })
                }
                if (item.omittedImages > 0) obj.addProperty("omittedImages", item.omittedImages)
            }

            is TranscriptItem.Assistant -> {
                obj.addProperty("kind", "assistant")
                obj.addProperty("text", item.text)
            }

            is TranscriptItem.Thinking -> {
                obj.addProperty("kind", "thinking")
                obj.addProperty("text", item.text)
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
                obj.addProperty("name", item.name)
                obj.addProperty("input", item.input)
            }

            is TranscriptItem.Result -> {
                obj.addProperty("kind", "result")
                obj.addProperty("subtype", item.subtype)
                // 可选字段为 null 时整体省略，让 React 侧少一层判空
                item.costUsd?.let { obj.addProperty("costUsd", it) }
                item.durationMs?.let { obj.addProperty("durationMs", it) }
            }
        }
        return obj
    }
}
