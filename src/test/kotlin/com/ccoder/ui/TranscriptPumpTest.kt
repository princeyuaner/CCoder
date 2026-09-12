package com.ccoder.ui

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
        val executed = mutableListOf<String>()
        withPump(exec = { executed += it }) { pump ->
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "你"))
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "好"))
            pump.enqueue(TranscriptOp.AppendDelta("assistant", "呀"))
            pump.flushNow()

            assertEquals(1, executed.size, "三次入队必须压成一次跨边界调用")
            assertEquals(3, JsonParser.parseString(executed[0]).asJsonArray.size())
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
    fun `并发入队不丢操作`() {
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

            // 批有上限，一次 flush 发不完 —— 冲到空为止。这条守的是
            // "并发入队一条都不丢"，不是"一次发得完"；顺带也就守住了
            // 加上限之后仍然不丢
            repeat(10) { pump.flushNow() }

            val total = executed.sumOf { JsonParser.parseString(it).asJsonArray.size() }
            assertEquals(800, total, "并发入队不能丢操作")
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
            repeat(500) { pump.enqueue(TranscriptOp.AppendDelta("assistant", "$it")) }
            pump.flushNow()

            assertEquals(1, batches.size)
            assertEquals(200, batches[0], "回放的 804 条一次全发出去就是一次几 MB 的跨边界调用")
        }
    }

    @Test
    fun `超出的部分留到下一拍而不是丢弃`() {
        val batches = mutableListOf<Int>()
        withPump(maxBatch = 200, exec = { batches += opsIn(it) }) { pump ->
            repeat(500) { pump.enqueue(TranscriptOp.AppendDelta("assistant", "$it")) }
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
            repeat(5) { pump.enqueue(TranscriptOp.AppendDelta("assistant", "$it")) }
            pump.flushNow()

            assertEquals(listOf(5), batches)
        }
    }
}
