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
        exec: (String) -> Unit,
        block: (TranscriptPump) -> Unit,
    ) {
        val pump = TranscriptPump(exec = exec, throttleMs = throttleMs)
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
            pump.flushNow()

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
}
