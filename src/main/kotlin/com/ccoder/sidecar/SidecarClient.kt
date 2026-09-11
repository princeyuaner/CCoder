package com.ccoder.sidecar

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NDJSON 分帧器（插件侧）。
 *
 * 与 sidecar/ndjson.js 的 NdjsonDecoder 对称 —— 两侧都必须容忍消息被切开。
 */
class NdjsonFramer {
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val idx = buffer.indexOf("\n")
            if (idx < 0) break
            val line = buffer.substring(0, idx)
            buffer.delete(0, idx + 1)
            out.add(line.removeSuffix("\r"))
        }
        return out
    }

    fun flush(): List<String> {
        if (buffer.isEmpty()) return emptyList()
        val rest = buffer.toString().removeSuffix("\r")
        buffer.setLength(0)
        return listOf(rest)
    }
}

interface SidecarListener {
    fun onMessage(msg: SidecarMessage)
}

/**
 * sidecar 的 stdin/stdout 通道。
 *
 * 只负责收发与分帧；消息含义由调用方解释。
 */
class SidecarClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: SidecarListener,
) {
    private val closed = AtomicBoolean(false)
    private var readerThread: Thread? = null

    fun start() {
        val thread = Thread({
            val framer = NdjsonFramer()
            try {
                // InputStreamReader 在字节层做 UTF-8 解码，
                // 多字节字符不会因为读取块边界而损坏
                input.reader(StandardCharsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    while (!closed.get()) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        dispatch(framer.feed(String(buf, 0, n)))
                    }
                }
                dispatch(framer.flush())
            } catch (_: Exception) {
                // 流被关闭（进程退出或主动 close）是预期路径，不向上传播
            }
        }, "ccoder-sidecar-reader")
        thread.isDaemon = true
        thread.start()
        readerThread = thread
    }

    private fun dispatch(lines: List<String>) {
        for (line in lines) {
            // parse 返回 null 表示非 JSON、空行或畸形消息 —— 跳过即可
            Protocol.parse(line)?.let { listener.onMessage(it) }
        }
    }

    /** 写出单行 JSON。已关闭或写入失败时静默忽略 —— sidecar 可能已退出。 */
    @Synchronized
    fun sendLine(json: String) {
        if (closed.get()) return
        try {
            output.write(json.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } catch (_: Exception) {
            // sidecar 已退出，写入失败是预期情况
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        readerThread?.interrupt()
    }
}
