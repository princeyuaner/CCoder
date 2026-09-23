package com.ccoder.ui

import com.ccoder.sidecar.TranscriptItem
import com.ccoder.sidecar.TranscriptOp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 大报文切片（2026-09-23：IDEA 的 remote JCEF 吃不下几 MB 的 executeJavaScript，
 * 通道会静默断掉 —— 用户报「输出多一些内容就很容易卡死」）。
 *
 * 钉三件事：超大正文拆片后**字一个不少**；收尾是空文本的 FinalizeDelta（web 侧
 * 约定）；单片体积绝不超预算。
 */
class TranscriptOpWireSplitTest {

    @Test
    fun `小批原样通过，不拆`() {
        val ops = listOf(
            TranscriptOp.AppendDelta("assistant", "你好"),
            TranscriptOp.FinalizeDelta("assistant", "你好呀"),
        )
        val pieces = TranscriptOpCodec.splitForWire(ops)
        assertEquals(1, pieces.size)
        assertEquals(ops, pieces[0])
    }

    @Test
    fun `超大 FinalizeDelta 拆成增量流 + 空收尾，字一个不少`() {
        val big = "长".repeat(TranscriptOpCodec.CHUNK_CHARS * 2 + 5)
        val pieces = TranscriptOpCodec.splitForWire(
            listOf(TranscriptOp.FinalizeDelta("assistant", big)),
        )
        val flat = pieces.flatten()

        assertEquals(4, flat.size, "3 片增量 + 1 条空收尾（${big.length} 字 / ${TranscriptOpCodec.CHUNK_CHARS}）")
        assertTrue(flat.drop(1).all { it is TranscriptOp.AppendDelta || it is TranscriptOp.FinalizeDelta })
        val rebuilt = flat.filterIsInstance<TranscriptOp.AppendDelta>().joinToString("") { it.text }
        assertEquals(big, rebuilt, "拼回去必须与原文一字不差")
        val tail = flat.last() as TranscriptOp.FinalizeDelta
        assertEquals("", tail.text, "收尾必须是空文本（web 侧约定：落 live 缓冲）")
    }

    @Test
    fun `无 parent 的大段 Assistant 也拆 —— 带 parent 的不拆`() {
        val big = "x".repeat(TranscriptOpCodec.CHUNK_CHARS + 1)

        val main = TranscriptOpCodec.splitForWire(
            listOf(TranscriptOp.Append(TranscriptItem.Assistant("id", 1L, big, parent = null))),
        ).flatten()
        assertTrue(main.any { it is TranscriptOp.AppendDelta }, "主线程大段正文该拆")
        assertTrue(main.none { it is TranscriptOp.Append }, "拆完不该再留整条 Append")

        val sub = TranscriptOpCodec.splitForWire(
            listOf(TranscriptOp.Append(TranscriptItem.Assistant("id", 1L, big, parent = "toolu_task"))),
        ).flatten()
        assertEquals(1, sub.size, "子代理的不许拆 —— FinalizeDelta 表达不了归属")
    }

    @Test
    fun `按体积打包：单片估算体积不超预算`() {
        val budget = 2_000
        val ops = (1..50).map {
            TranscriptOp.Append(TranscriptItem.SystemNote("n$it", it.toLong(), "内容$it"))
        }
        val pieces = TranscriptOpCodec.splitForWire(ops, maxScriptChars = budget)
        assertTrue(pieces.size > 1, "50 条在 $budget 预算下该切成多片")
        assertEquals(50, pieces.sumOf { it.size }, "一条都不能丢")
    }
}
