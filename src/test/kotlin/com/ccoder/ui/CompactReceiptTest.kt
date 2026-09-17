package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 压缩回执的文案（spec `2026-09-17-card-actions-design.md` §3.7）。
 *
 * 两条最容易写错的：**两种字段形状都得认**（实时 snake_case、落盘 camelCase ——
 * 回放同样走 renderer，只认一种就是"现场有、历史里没有"的半边功能），
 * 以及**缺字段时降级照写、不编数字**。
 *
 * 数字来自 2026-09-17 两次真实压缩（`probe-compact.mjs`），不是编的。
 */
class CompactReceiptTest {

    private fun receipt(json: String) = compactReceiptOf(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `齐全的手动回执（实时事件的 snake_case 形状）`() {
        assertEquals(
            "已压缩上下文：30.4k → 1.7k（用时 17s）",
            receipt(
                """{"type":"system","subtype":"compact_boundary",
                    "compact_metadata":{"trigger":"manual","pre_tokens":30409,"post_tokens":1666,"duration_ms":17219}}""",
            ),
        )
    }

    @Test
    fun `落盘历史里的 camelCase 形状也认`() {
        // 这一条是照 ~/.claude/projects/…/<id>.jsonl 里的原文写的（spec 事实 12）。
        // 恢复会话时重放的就是它 —— 认不出来，压缩过的会话恢复后就没有这条回执
        assertEquals(
            "已压缩上下文：30.4k → 1.2k（用时 10s）",
            receipt(
                """{"type":"system","subtype":"compact_boundary","content":"Conversation compacted",
                    "compactMetadata":{"trigger":"manual","preTokens":30402,"postTokens":1182,"durationMs":9704}}""",
            ),
        )
    }

    @Test
    fun `缺 post_tokens —— 只说压缩前多少`() {
        assertEquals(
            "已压缩上下文（压缩前 30.4k）",
            receipt("""{"subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":30409}}"""),
        )
    }

    @Test
    fun `缺 duration_ms —— 省略括号，数字照给`() {
        assertEquals(
            "已压缩上下文：30.4k → 1.7k",
            receipt(
                """{"subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":30409,"post_tokens":1666}}""",
            ),
        )
    }

    @Test
    fun `自动压缩加 CLI 前缀 —— 用户得知道这不是自己点的`() {
        assertEquals(
            "CLI 自动压缩了上下文：30.4k → 1.7k（用时 17s）",
            receipt(
                """{"subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":30409,"post_tokens":1666,"duration_ms":17219}}""",
            ),
        )
    }

    @Test
    fun `连 pre_tokens 都没有 —— 只说压过了，不编数字`() {
        assertEquals(
            "已压缩上下文",
            receipt("""{"subtype":"compact_boundary","compact_metadata":{"trigger":"manual"}}"""),
        )
        assertEquals("已压缩上下文", receipt("""{"subtype":"compact_boundary"}"""))
    }

    @Test
    fun `不是 boundary 的事件给 null`() {
        assertNull(receipt("""{"type":"result","subtype":"success"}"""))
        assertNull(receipt("""{"type":"system","subtype":"status","status":"compacting"}"""))
    }

    @Test
    fun `一千以下不加 k —— 与 formatTokenCount 同一条线`() {
        assertEquals(
            "已压缩上下文：900 → 120",
            receipt("""{"subtype":"compact_boundary","compact_metadata":{"pre_tokens":900,"post_tokens":120}}"""),
        )
    }
}
