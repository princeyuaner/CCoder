package com.ccoder.ui

import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 节流器与 JCEF 解耦，用注入的执行函数测试。
 * 真实浏览器不在单测范围内（那属于手工冒烟）。
 *
 * **每个用例都必须释放 pump** —— 它内部持有 Timer，不释放会留下线程，
 * JUnit 的线程泄漏检测会直接判定失败。
 */
class TranscriptPumpTest {

    private fun withPump(
        throttleMs: Long = 10_000,
        maxBatch: Int = TranscriptPump.DEFAULT_MAX_BATCH,
        exec: (String) -> Unit,
        block: (TranscriptPump) -> Unit,
    ) {
        val pump = TranscriptPump(exec = exec, throttleMs = throttleMs, maxBatch = maxBatch)
        try {
            block(pump)
        } finally {
            pump.dispose()
        }
    }

    @Test
    fun `未 flush 前不执行`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.Reset)
            assertEquals(0, executed.size, "节流期内不应推送")
        }
    }

    @Test
    fun `flushNow 推送已入队的操作`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.Reset)
            pump.flushNow()

            assertEquals(1, executed.size)
            val array = JsonParser.parseString(executed[0]).asJsonArray
            assertEquals(1, array.size())
        }
    }

    @Test
    fun `多个操作合并为一次推送`() {
        // 这条守的是**节流压批**（一拍的多条压成一次跨边界调用），与增量合并
        // 是两回事 —— 所以刻意用 Reset：它不会被就地合并，颗粒度才量得准
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.Reset)
            pump.enqueue(TranscriptOp.ClearDelta("assistant"))
            pump.enqueue(TranscriptOp.Reset)
            pump.flushNow()

            assertEquals(1, executed.size, "三次入队必须压成一次跨边界调用")
            assertEquals(3, JsonParser.parseString(executed[0]).asJsonArray.size())
        }
    }

    // ---- 同拍内的增量合并 ----

    /**
     * 数一批推送里所有 delta 的文本，按到达顺序拼起来。
     *
     * 合并只改颗粒度、不改内容，所以断言要落在**文本**上而不是 op 数上 ——
     * 否则"合并生效"和"内容丢了"在测试里长得一模一样。
     */
    private fun deltaText(json: String): String =
        JsonParser.parseString(json).asJsonArray
            .filter { it.asJsonObject.get("op").asString == "appendDelta" }
            .joinToString("") { it.asJsonObject.get("text").asString }

    @Test
    fun `同一拍内的连续增量并成一条`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "你"))
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "好"))
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "呀"))
            pump.flushNow()

            assertEquals(1, executed.size, "三次入队仍要压成一次跨边界调用")
            assertEquals(
                1,
                JsonParser.parseString(executed[0]).asJsonArray.size(),
                "同 target 的连续增量应并成一条 —— 前端因此少做两次拼接、少解析两个对象",
            )
            assertEquals("你好呀", deltaText(executed[0]), "合并后文本必须一字不差")
        }
    }

    @Test
    fun `不同 target 的增量各流各的，不合并`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "答"))
            pump.enqueue(TranscriptOp.AppendDelta("thinking", "想"))
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "案"))
            pump.flushNow()

            assertEquals(
                3,
                JsonParser.parseString(executed[0]).asJsonArray.size(),
                "assistant 与 thinking 是两个独立的 live 缓冲，拼一起会串台",
            )
        }
    }

    /**
     * 合并唯一会出错的地方。
     *
     * `applyOps` 遇到 ClearDelta / FinalizeDelta 会 **delete live[target]**，
     * 于是清空前后的两段文本在语义上不相邻。跨过它们合并 = 无中生有，
     * 而且这种错在前端完全看不出来（文本就是多了几十个字）。
     */
    @Test
    fun `clearDelta 两侧的增量不合并`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "上一轮的残字"))
            pump.enqueue(TranscriptOp.ClearDelta("assistant"))
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "新的一轮"))
            pump.flushNow()

            val ops = JsonParser.parseString(executed[0]).asJsonArray
            assertEquals(3, ops.size(), "ClearDelta 会清掉 live 缓冲，两侧不能拼一起")
            assertEquals("上一轮的残字", ops[0].asJsonObject.get("text").asString)
            assertEquals("新的一轮", ops[2].asJsonObject.get("text").asString)
        }
    }

    @Test
    fun `finalizeDelta 两侧的增量不合并`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "流出来的"))
            pump.enqueue(TranscriptOp.FinalizeDelta("assistant", "定稿的完整文本"))
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "下一段"))
            pump.flushNow()

            assertEquals(
                3,
                JsonParser.parseString(executed[0]).asJsonArray.size(),
                "FinalizeDelta 同样会清掉 live 缓冲",
            )
        }
    }

    /**
     * 真实流式模式：思考先逐字流，完成后 SDK 再补一条**整块**的 thinking 消息。
     *
     * `applyOps` 收到整块思考会把 live['thinking'] 删掉（否则同一段思考显示两遍），
     * 所以它两侧的增量也不能合并。这一条不是靠"类型不同"兜住的，靠的正是
     * "末尾不是同类增量"这一条规则。
     */
    @Test
    fun `整块思考到达会打断思考增量`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.AppendDelta("thinking", "想了一半"))
            pump.enqueue(
                TranscriptOp.Append(
                    TranscriptItem.Thinking(id = "k1", ts = 0, text = "完整的思考"),
                ),
            )
            pump.enqueue(TranscriptOp.AppendDelta("thinking", "想了另一半"))
            pump.flushNow()

            assertEquals(
                3,
                JsonParser.parseString(executed[0]).asJsonArray.size(),
                "整块思考到达时 live 缓冲作废，两侧的增量不能合并",
            )
        }
    }

    @Test
    fun `空队列时 flush 不产生调用`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.flushNow()
            assertEquals(0, executed.size, "空闲时不该产生跨边界调用")
        }
    }

    @Test
    fun `flush 后队列清空`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.Reset)
            pump.flushNow()
            pump.flushNow()
            assertEquals(1, executed.size)
        }
    }

    @Test
    fun `dispose 后不再推送`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 10_000)
        pump.dispose()
        pump.enqueue(TranscriptOp.Reset)
        pump.flushNow()
        assertEquals(0, executed.size)
    }

    @Test
    fun `重复 dispose 不抛错`() {
        val pump = TranscriptPump(exec = {}, throttleMs = 10_000)
        pump.dispose()
        pump.dispose()
    }

    @Test
    fun `并发入队不丢内容`() {
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            val threads = (1..8).map { t ->
                Thread {
                    repeat(100) { i ->
                        pump.enqueue(TranscriptOp.AppendDelta("assistant", "$t-$i"))
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            // 批有上限，一次 flush 发不完 —— 冲到空为止
            repeat(10) { pump.flushNow() }

            // 数**内容**而不是 op 数：增量会被就地合并，颗粒度不再是 800。
            // 长度是充分的判据 —— 合并只做字符串拼接，没有任何路径能凭空造出
            // 字符，长度对上就是一片不少。交错顺序不必断言：applyOps 认的就是
            // 到达顺序，合并后与逐条拼接的结果逐字相同
            val expected = (1..8).sumOf { t -> (0 until 100).sumOf { i -> "$t-$i".length } }
            val text = executed.joinToString("") { deltaText(it) }
            assertEquals(expected, text.length, "并发入队不能丢内容")
        }
    }

    @Test
    fun `exec 抛错不影响后续推送`() {
        var failNext = true
        val executed = mutableListOf<String>()
        withPump(
            exec = {
                if (failNext) {
                    failNext = false
                    throw RuntimeException("桥断了")
                }
                executed += it
            },
        ) { pump ->
            pump.enqueue(TranscriptOp.Reset)
            runCatching { pump.flushNow() }
            pump.enqueue(TranscriptOp.Reset)
            pump.flushNow()

            assertEquals(1, executed.size, "一次推送失败不该让节流器永久失效")
        }
    }

    @Test
    fun `定时器按节流周期自动推送`() {
        val executed = mutableListOf<String>()
        withPump(throttleMs = 40, exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.Reset)
            val deadline = System.currentTimeMillis() + 3000
            while (executed.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(1, executed.size, "定时器应在节流周期后自动 flush")
        }
    }

    @Test
    fun `dispose 后定时器不再触发`() {
        val executed = mutableListOf<String>()
        val pump = TranscriptPump(exec = { executed += it }, throttleMs = 30)
        pump.enqueue(TranscriptOp.Reset)
        pump.dispose()
        Thread.sleep(300)
        assertEquals(0, executed.size, "dispose 后不该再有推送")
    }

    // ---- 批大小上限（Task 6）----

    /** 数一批推送里装了多少个操作。 */
    private fun opsIn(json: String): Int = JsonParser.parseString(json).asJsonArray.size()

    @Test
    fun `单批不超过上限`() {
        val batches = mutableListOf<Int>()
        withPump(maxBatch = 200, exec = { batches += opsIn(it) }) { pump ->
            // 用 Reset 而不是 AppendDelta：增量会被就地合并，量不出批大小。
            // 而上限要挡的正是**回放**——那里推的是 Append，一条都不会合并
            repeat(500) { pump.enqueue(TranscriptOp.Reset) }
            pump.flushNow()

            assertEquals(1, batches.size)
            assertEquals(200, batches[0], "回放的 804 条一次全发出去就是一次几 MB 的跨边界调用")
        }
    }

    @Test
    fun `超出的部分留到下一拍而不是丢弃`() {
        val batches = mutableListOf<Int>()
        withPump(maxBatch = 200, exec = { batches += opsIn(it) }) { pump ->
            repeat(500) { pump.enqueue(TranscriptOp.Reset) }
            pump.flushNow()
            pump.flushNow()
            pump.flushNow()
            pump.flushNow()

            assertEquals(listOf(200, 200, 100), batches, "一共 500 条，一条都不能少")
        }
    }

    @Test
    fun `不足一批时一次发完`() {
        val batches = mutableListOf<Int>()
        withPump(maxBatch = 200, exec = { batches += opsIn(it) }) { pump ->
            repeat(5) { pump.enqueue(TranscriptOp.Reset) }
            pump.flushNow()

            assertEquals(listOf(5), batches)
        }
    }
}
