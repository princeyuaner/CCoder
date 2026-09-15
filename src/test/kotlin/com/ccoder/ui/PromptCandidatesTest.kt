package com.ccoder.ui

import com.ccoder.sidecar.CommandInfo
import com.ccoder.settings.PromptPreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptCandidatesTest {

    private fun preset(name: String, content: String) = PromptPreset(name = name, content = content)

    @Test
    fun `插入的是正文，且标记为原样插入`() {
        val out = promptCandidates(listOf(preset("写测试", "给这段代码补单测\n覆盖边界")))

        assertEquals(1, out.size)
        assertEquals("写测试", out[0].display)
        assertEquals("给这段代码补单测\n覆盖边界", out[0].insert)
        assertTrue(
            out[0].verbatim,
            "不标原样插入的话，写进输入框的会是「/给这段代码补单测」",
        )
        assertEquals(GROUP_PRESET, out[0].group)
    }

    @Test
    fun `名字与内容首行相同时不再重复一遍摘要`() {
        // 名字空着会用内容首行顶上：那种情况下 description 就等于 display，
        // 一列里会把同样的话显示两遍
        val out = promptCandidates(listOf(preset("解释这段报错", "解释这段报错")))

        assertNull(out[0].description)
    }

    @Test
    fun `有名字时内容首行进副标题`() {
        val out = promptCandidates(listOf(preset("解释报错", "帮我解释这段报错\n尽量说人话")))

        assertEquals("帮我解释这段报错", out[0].description)
    }

    @Test
    fun `预设整组排在命令之前，标题不会被切成两段`() {
        // 分组标题只在**换组**时插一条（CompletionPopup.buildCompletionList），
        // 所以预设必须整组连续 —— 这条把调用方的拼接顺序钉住
        val presets = promptCandidates(
            listOf(preset("A", "A 的内容"), preset("B", "B 的内容")),
        )
        val commands = commandCandidates(
            commands = listOf(CommandInfo("compact", "压缩上下文", null, emptyList())),
            sendable = setOf("compact"),
        )

        val merged = presets + commands

        assertEquals(
            listOf(GROUP_PRESET, GROUP_PRESET, GROUP_OTHER),
            merged.map { it.group },
        )
    }

    @Test
    fun `空列表给空候选，不抛也不补位`() {
        assertTrue(promptCandidates(emptyList()).isEmpty())
    }
}
