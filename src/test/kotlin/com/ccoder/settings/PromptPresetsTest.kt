package com.ccoder.settings

import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptPresetsTest {

    /**
     * 按平台写盘的路子序列化一份 state。
     *
     * 必须带 [SkipDefaultsSerializationFilter]：文件存储的默认过滤器就是它的子类，
     * 而"跳过等于默认值的字段"恰恰是最可能把内容吞掉的那一步 —— 用不过滤的写法测，
     * 等于绕开唯一有风险的地方（照 `ModelProfilesTest` 的先例）。
     */
    private fun serializeState(p: PromptPresets): Element {
        val element = Element("component")
        XmlSerializer.serializeInto(p.getState(), element, SkipDefaultsSerializationFilter())
        return element
    }

    @Test
    fun `增删改查走一遍`() {
        val p = PromptPresets()
        val a = PromptPreset(name = "写测试", content = "给这段代码补单测")

        p.upsert(a)
        assertEquals(1, p.presets().size)
        assertEquals("写测试", p.presets().first().name)

        p.upsert(a.copy(name = "写单测"))
        assertEquals(1, p.presets().size, "同 id 是修改不是新增")
        assertEquals("写单测", p.presets().first().name)

        p.remove(a.id)
        assertTrue(p.presets().isEmpty())
    }

    @Test
    fun `名字空着就用内容首行顶上`() {
        val p = PromptPresets()
        p.upsert(PromptPreset(name = "  ", content = "解释这段报错\n尽量说人话"))

        assertEquals("解释这段报错", p.presets().first().name)
    }

    @Test
    fun `只填了名字、还没写内容也算一条`() {
        // 用户正在写：列表里得看得见它，否则"我明明加了"会变成一条无从解释的现象
        val p = PromptPresets()
        p.upsert(PromptPreset(name = "待写", content = ""))

        assertEquals(1, p.presets().size)
        assertFalse(isBlankPromptPreset(p.presets().first()))
    }

    @Test
    fun `读盘时滤掉彻底空的那几条`() {
        val p = PromptPresets()
        val state = PromptPresets.State(
            presets = mutableListOf(
                PromptPreset(name = "留着我", content = "有内容"),
                PromptPreset(name = " ", content = "  \n "),   // 点了「添加」没填就关了
            ),
        )

        p.loadState(state)

        assertEquals(1, p.presets().size)
        assertEquals("留着我", p.presets().first().name)
    }

    @Test
    fun `写盘再读回来不丢东西（含多行内容）`() {
        val p = PromptPresets()
        p.upsert(PromptPreset(name = "加日志", content = "请加日志\n- 用项目的 logger\n- 不要 println"))

        val element = serializeState(p)
        val back = XmlSerializer.deserialize(element, PromptPresets.State::class.java)

        assertEquals(1, back.presets.size, "序列化把条目丢了")
        assertEquals("加日志", back.presets.first().name)
        assertEquals("请加日志\n- 用项目的 logger\n- 不要 println", back.presets.first().content)
    }

    @Test
    fun `摘要取首个非空行而不是第 0 行`() {
        // prompt 常常以空行或缩进开头，直接取第 0 行会得到一条空摘要
        assertEquals("正文", summarizePrompt("\n\n   正文\n第二行"))
    }

    @Test
    fun `摘要封顶且带省略号`() {
        val long = "一".repeat(80)
        val summary = summarizePrompt(long)

        assertEquals(40, summary.length)
        assertTrue(summary.endsWith("…"), "截断了就该看得出来被截断了：$summary")
    }

    @Test
    fun `空内容没有摘要`() {
        assertEquals("", summarizePrompt("  \n  "))
    }

    @Test
    fun `upsert 存的是收敛后的样子，不是原样`() {
        val p = PromptPresets()
        p.upsert(PromptPreset(name = "  留白  ", content = "  内容  "))

        val stored = p.presets().first()
        assertEquals("留白", stored.name)
        assertEquals("内容", stored.content)
    }
}
