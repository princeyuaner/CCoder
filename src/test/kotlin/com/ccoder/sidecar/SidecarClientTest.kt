package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NdjsonFramerTest {

    @Test
    fun `单块含多行`() {
        val f = NdjsonFramer()
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), f.feed("{\"a\":1}\n{\"b\":2}\n"))
    }

    @Test
    fun `跨块重组`() {
        val f = NdjsonFramer()
        assertEquals(emptyList<String>(), f.feed("{\"a\":"))
        assertEquals(listOf("{\"a\":1}"), f.feed("1}\n"))
    }

    @Test
    fun `CRLF 被剥离`() {
        val f = NdjsonFramer()
        assertEquals(listOf("{\"a\":1}"), f.feed("{\"a\":1}\r\n"))
    }

    @Test
    fun `CR 落在块边界时不误判`() {
        // \r 可能是 \r\n 的前半，必须留在缓冲里等下一个块
        val f = NdjsonFramer()
        assertEquals(emptyList<String>(), f.feed("{\"a\":1}\r"))
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), f.feed("\n{\"b\":2}\r\n"))
    }

    @Test
    fun `flush 吐出无换行结尾的残留`() {
        val f = NdjsonFramer()
        f.feed("{\"a\":1}")
        assertEquals(listOf("{\"a\":1}"), f.flush())
        assertEquals(emptyList<String>(), f.flush(), "flush 后缓冲应清空")
    }

    @Test
    fun `逐字符 feed 也能重组`() {
        val f = NdjsonFramer()
        val payload = "{\"text\":\"中文内容\"}\n"
        val out = payload.flatMap { f.feed(it.toString()) }
        assertEquals(1, out.size)
        assertTrue(out[0].contains("中文内容"), "实际：${out[0]}")
    }

    @Test
    fun `空行产出空字符串项`() {
        val f = NdjsonFramer()
        assertEquals(listOf("", ""), f.feed("\n\n"))
    }
}

class SidecarClientTest {

    private class Recorder : SidecarListener {
        val messages = CopyOnWriteArrayList<SidecarMessage>()
        val latch = CountDownLatch(1)
        override fun onMessage(msg: SidecarMessage) {
            messages.add(msg)
            latch.countDown()
        }
    }

    private fun clientFrom(input: String, rec: SidecarListener, out: ByteArrayOutputStream = ByteArrayOutputStream()) =
        SidecarClient(ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), out, rec)

    @Test
    @Timeout(30)
    fun `从输入流读取并分派消息`() {
        val rec = Recorder()
        val client = clientFrom("{\"type\":\"ready\",\"sessionId\":\"s1\"}\n", rec)
        client.start()
        assertTrue(rec.latch.await(5, TimeUnit.SECONDS), "5 秒内未收到消息")

        val msg = rec.messages[0] as SidecarMessage.Ready
        assertEquals("s1", msg.sessionId)
        client.close()
    }

    @Test
    @Timeout(30)
    fun `非 JSON 行被跳过而不中断读取`() {
        val input = "[claude-code:unrecognized_model] {\"model\":\"x\"}\n" +
            "{\"type\":\"ready\",\"sessionId\":\"s2\"}\n"
        val rec = Recorder()
        val client = clientFrom(input, rec)
        client.start()
        assertTrue(rec.latch.await(5, TimeUnit.SECONDS))

        assertTrue(
            rec.messages[0] is SidecarMessage.Ready,
            "噪声行不该产生消息，也不该阻止后续解析"
        )
        client.close()
    }

    @Test
    @Timeout(30)
    fun `中文字符跨读取块不损坏`() {
        // InputStreamReader 在字节层做 UTF-8 解码，字符边界不会被打断
        val payload = "{\"type\":\"ready\",\"sessionId\":\"中文会话ID\"}\n"
        val rec = Recorder()
        val client = clientFrom(payload, rec)
        client.start()
        assertTrue(rec.latch.await(5, TimeUnit.SECONDS))
        assertEquals("中文会话ID", (rec.messages[0] as SidecarMessage.Ready).sessionId)
        client.close()
    }

    @Test
    fun `sendLine 原样写出`() {
        val out = ByteArrayOutputStream()
        val client = SidecarClient(ByteArrayInputStream(ByteArray(0)), out, Recorder())
        client.sendLine(Protocol.encodeSend("r1", "hello"))
        assertEquals(
            """{"id":"r1","method":"send","params":{"text":"hello"}}""",
            out.toString("UTF-8").trim()
        )
    }

    @Test
    fun `输出流关闭后 sendLine 不抛错`() {
        val out = ByteArrayOutputStream()
        val client = SidecarClient(ByteArrayInputStream(ByteArray(0)), out, Recorder())
        client.close()
        // sidecar 可能已退出，写入失败是预期情况，不该把异常抛给调用方
        client.sendLine(Protocol.encodeSend("r1", "x"))
    }

    @Test
    @Timeout(30)
    fun `输入流为空时读取线程正常结束`() {
        val rec = Recorder()
        val client = SidecarClient(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), rec)
        client.start()
        Thread.sleep(200)
        assertTrue(rec.messages.isEmpty())
        client.close()
    }

    // ---- 待决表（Task 4）----

    /** 一条会一直读到流末尾的输入。用来驱动配对逻辑。 */
    private fun respondAfter(input: String, rec: SidecarListener, timeoutMs: Long = 10_000) =
        SidecarClient(
            ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)),
            ByteArrayOutputStream(),
            rec,
            requestTimeoutMs = timeoutMs,
        )

    @Test
    @Timeout(30)
    fun `请求收到同 id 响应时回调`() {
        val outcome = CompletableFuture<RequestOutcome>()
        val client = respondAfter(
            "{\"type\":\"sessions\",\"id\":\"r1\",\"sessions\":[]}\n",
            Recorder(),
        )
        // 先登记再启动读线程：ByteArrayInputStream 会被一发读完，反过来的话
        // 响应可能在登记之前就被消费掉。生产里不会 —— 管道上响应只可能在
        // 请求写出去之后才到，而 request() 是先登记后 sendLine
        client.request("r1", Protocol.encodeListSessions("r1", "/p", 50, 0)) { outcome.complete(it) }
        client.start()

        val got = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(got is RequestOutcome.Answered, "实际：$got")
        assertTrue((got as RequestOutcome.Answered).message is SidecarMessage.SessionList)
        client.close()
    }

    @Test
    @Timeout(30)
    fun `已被配对的响应不再送给 listener`() {
        val rec = Recorder()
        val outcome = CompletableFuture<RequestOutcome>()
        val client = respondAfter("{\"type\":\"sessions\",\"id\":\"r1\",\"sessions\":[]}\n", rec)
        client.request("r1", "{}") { outcome.complete(it) }
        client.start()
        outcome.get(5, TimeUnit.SECONDS)

        assertTrue(
            rec.messages.none { it is SidecarMessage.SessionList },
            "被 request 消费掉的响应不该再走 listener，否则会被处理两遍",
        )
        client.close()
    }

    @Test
    @Timeout(30)
    fun `id 对不上的响应仍走 listener`() {
        // 宁可让它流到 listener 被看见，也不要静默丢弃 —— 静默丢弃会让
        // "请求超时"和"消息丢了"两种故障长得一模一样
        val rec = Recorder()
        val client = respondAfter("{\"type\":\"sessions\",\"id\":\"other\",\"sessions\":[]}\n", rec)
        client.start()

        assertTrue(rec.latch.await(5, TimeUnit.SECONDS), "5 秒内未收到消息")
        assertTrue(rec.messages[0] is SidecarMessage.SessionList)
        client.close()
    }

    @Test
    @Timeout(30)
    fun `超时后以 Failed 回调`() {
        val outcome = CompletableFuture<RequestOutcome>()
        // 输入流不含任何响应；超时设成 150ms 免得测试等太久
        val client = respondAfter("", Recorder(), timeoutMs = 150)
        client.start()
        client.request("r1", "{}") { outcome.complete(it) }

        val got = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(got is RequestOutcome.Failed, "实际：$got")
        client.close()
    }

    @Test
    @Timeout(30)
    fun `close 时待决请求全部以 Failed 回调`() {
        val outcome = CompletableFuture<RequestOutcome>()
        val client = respondAfter("", Recorder(), timeoutMs = 60_000)
        client.start()
        client.request("r1", "{}") { outcome.complete(it) }
        client.close()

        val got = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(got is RequestOutcome.Failed, "通道关掉后不能有请求永远挂着")
    }

    @Test
    @Timeout(30)
    fun `输入流结束时待决请求以 Failed 回调`() {
        // sidecar 进程退出 = 读线程走到流末尾。这之后不可能再有响应，
        // 挂着的请求必须立刻失败，否则界面会永远停在"载入中"
        val outcome = CompletableFuture<RequestOutcome>()
        val rec = Recorder()
        val client = SidecarClient(
            ByteArrayInputStream("{\"type\":\"ready\"}\n".toByteArray(Charsets.UTF_8)),
            ByteArrayOutputStream(),
            rec,
            requestTimeoutMs = 60_000,
        )
        // 先登记请求再启动读线程 —— 这条流一到末尾就会结束，
        // 不先登记的话 failAllPending 扫不到它，只能干等超时
        client.request("r1", "{}") { outcome.complete(it) }
        client.start()
        assertTrue(rec.latch.await(5, TimeUnit.SECONDS))

        val got = outcome.get(5, TimeUnit.SECONDS)
        assertTrue(got is RequestOutcome.Failed, "实际：$got")
        client.close()
    }
}
