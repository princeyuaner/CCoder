package com.ccoder.ui

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 复制键的收端解析（[parseCopyText]）。
 *
 * [copyToClipboard] 那一半碰平台的 [com.intellij.openapi.ide.CopyPasteManager]，
 * 无头测试里起不了（同 [OpenFileTarget] 的拆分理由）—— 所以"读什么"与"放进剪贴板"
 * 分在两处，能测的那半在这里钉住。
 */
class CopyTextTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `读出要复制的文本`() {
        assertEquals("abc", parseCopyText(obj("""{"op":"copy","text":"abc"}""")))
        // 代码块里换行是常态，不能被当成"多行就不复制"
        assertEquals("a\nb", parseCopyText(obj("""{"op":"copy","text":"a\nb"}""")))
    }

    @Test
    fun `空文本不复制 —— 别拿一个空串去覆盖用户的剪贴板`() {
        assertNull(parseCopyText(obj("""{"op":"copy","text":""}""")))
    }

    @Test
    fun `缺字段或类型不对给 null，不崩`() {
        assertNull(parseCopyText(obj("""{"op":"copy"}""")))
        assertNull(parseCopyText(obj("""{"op":"copy","text":42}""")))
        assertNull(parseCopyText(obj("""{"op":"copy","text":null}""")))
    }
}
