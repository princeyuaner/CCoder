package com.ccoder.ui

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import java.nio.file.Path

/**
 * 读一个后台任务的输出文件 —— **只读末尾**那一段。
 *
 * ## 为什么要自己写（2026-09-28）
 *
 * `task_notification.output_file` 是任务**完整输出**的落盘位置，SDK 一直在送，
 * 我们从前一个字节都没读过。而这份文件可以很大（一个跑十分钟的测试命令就是几万行），
 * 所以：
 *
 * - **不能整读**：`Files.readString` 在几十 MB 上是直接 OOM 的路（本仓对"大"的既有
 *   态度是截断 + 明说，见 `history-images.js` 的图预算）。
 * - **不能只在 EDT 上读**：调用方在 `TaskOutputDialog`，读盘一律走线程池（同
 *   [openInEditor] 的骨架）。
 *
 * 于是从**尾部反向**按块读：读到够 [maxLines] 行或够 [maxBytes] 字节就停。
 *
 * ## 三条边界（都有用例）
 *
 * 1. **头被切断**：从中间开始的那一行是残的，**丢掉** —— 半行日志比没有更误导。
 * 2. **多字节字符被切断**：块边界可能落在一个 UTF-8 字符中间。用带替换的
 *    解码（`String(bytes, UTF_8)` 的默认行为）扛掉，不因为一个坏字节整屏读不出来。
 * 3. **CRLF**：Windows 上逐行都要摘掉 `\r`（同 `ToolDiff.lines` 那条教训）。
 *
 * @param truncatedHead 前面还有内容没读进来 —— 界面必须**说出来**，不能装作这就是全部
 */
internal data class OutputTail(
    val lines: List<String>,
    val truncatedHead: Boolean,
)

/** 读到了，还是文件早就不在了（临时目录会被清）。 */
internal sealed interface OutputRead {
    data object Missing : OutputRead

    data class Ok(val tail: OutputTail) : OutputRead
}

/** 末尾留几行。两百行够看清"它最后卡在哪"，又不至于把框撑爆。 */
internal const val OUTPUT_TAIL_LINES = 200

/** 字节上限：行数没到也不读了（一行几十万字符的 minified 输出是真实存在的）。 */
internal const val OUTPUT_TAIL_BYTES = 256 * 1024

/** 反向读的块大小。 */
private const val CHUNK = 8 * 1024

internal fun readOutputTail(
    path: String,
    maxLines: Int = OUTPUT_TAIL_LINES,
    maxBytes: Int = OUTPUT_TAIL_BYTES,
): OutputRead {
    val file = runCatching { Path.of(path) }.getOrNull() ?: return OutputRead.Missing
    if (!Files.isRegularFile(file)) return OutputRead.Missing
    // 读不动（权限、被独占、盘掉了）一律按"不在"说 —— 反正结果都是"看不到输出"，
    // 两句话说给用户听没有区别（这句注释留在此处，是为了下次有人想细分类时先想一遍）
    return runCatching { OutputRead.Ok(readTailOf(file, maxLines, maxBytes)) as OutputRead }
        .getOrElse { OutputRead.Missing }
}

private fun readTailOf(file: Path, maxLines: Int, maxBytes: Int): OutputTail {
    RandomAccessFile(file.toFile(), "r").use { raf ->
        val size = raf.length()
        val blocks = ArrayDeque<ByteArray>()
        val chunk = ByteArray(CHUNK)
        var pos = size
        var bytes = 0L
        var newlines = 0

        while (pos > 0 && newlines <= maxLines && bytes < maxBytes) {
            val step = minOf(chunk.size.toLong(), pos).toInt()
            pos -= step
            raf.seek(pos)
            raf.readFully(chunk, 0, step)
            val block = chunk.copyOf(step)
            newlines += block.count { it == NL }
            bytes += step
            // 从后往前走，所以新块插在最前面 —— 拼出来仍是原来的顺序
            blocks.addFirst(block)
        }

        val wanted = ByteArrayOutputStream(bytes.toInt()).apply { blocks.forEach { write(it) } }.toByteArray()
        val text = String(wanted, StandardCharsets.UTF_8)

        val all = text.split('\n').toMutableList()
        // 文件以换行结尾时最后一个元素是空串，那不是"一行空行"
        if (all.size > 1 && all.last().isEmpty()) all.removeAt(all.size - 1)
        // 没读到文件开头 → 第一条是残的，丢掉
        if (pos > 0 && all.isNotEmpty()) all.removeAt(0)

        val tail = all.takeLast(maxLines).map { it.removeSuffix("\r") }
        return OutputTail(tail, truncatedHead = pos > 0 || all.size > maxLines)
    }
}

/** `\n` 的字节值；`ByteArray.count` 里拿它比，免得每处都写 `'\n'.code.toByte()`。 */
private val NL = '\n'.code.toByte()
