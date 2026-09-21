package com.ccoder.settings

import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "重启后照原样回来"这件事里**能单测的那一半**。
 *
 * 另一半（`SessionTabs` 怎么装配标签、`ClaudePanel` 怎么认回自己那条会话）要平台
 * 服务与 JCEF 才建得起来，只能靠真机验；所以这里把规矩一条条钉在纯函数上 ——
 * 存档的形状、读回来的收敛规则、以及"字段别漏"那两条老坑。
 */
class OpenTabsTest {

    private fun tab(
        sessionId: String = "s1",
        profileId: String = "p1",
        modelId: String = "flash",
        title: String? = null,
        selected: Boolean = false,
    ) = OpenTab(sessionId, profileId, modelId, title, selected)

    // ---- 读回来时的收敛 ----

    @Test
    fun `同一条会话只留一次`() {
        // 两条标签开同一条会话 = 两边同时写同一个 jsonl（运行时由 OpenSessions 挡，
        // 这里挡的是存档里那份自相矛盾的数据）
        val pruned = pruneOpenTabs(listOf(tab("s1"), tab("s2"), tab("s1")), max = 5)

        assertEquals(listOf("s1", "s2"), pruned.map { it.sessionId })
    }

    @Test
    fun `空会话的标签不去重`() {
        // 空 sessionId = "还没起过会话"。开三个「＋」标签都是这样，它们是三条标签，
        // 不是同一条的三份 —— 去重的话用户回来只剩一个空标签
        val pruned = pruneOpenTabs(listOf(tab(""), tab(""), tab("")), max = 5)

        assertEquals(3, pruned.size)
    }

    @Test
    fun `至多留一条当前`() {
        // 两条都写着"我才是当前的"时，恢复回来该选谁没有答案 —— 取靠前的那条
        val pruned = pruneOpenTabs(
            listOf(tab("s1", selected = true), tab("s2", selected = true)),
            max = 5,
        )

        assertEquals(listOf(true, false), pruned.map { it.selected })
    }

    @Test
    fun `一条当前都没有也认`() {
        // 手改过 XML、或者上一次存盘正好落在"刚关掉当前那条"的中间态上。
        // 调用方据此退回第一条，不抛错
        val pruned = pruneOpenTabs(listOf(tab("s1"), tab("s2")), max = 5)

        assertTrue(pruned.none { it.selected })
    }

    @Test
    fun `超过上限从尾部裁`() {
        // 上限是界面侧的产品上限（MAX_SESSION_TABS）。越靠后的标签越是"开完没怎么
        // 用"的，要裁就裁它们 —— 反过来会把用户正开着的那几条裁掉
        val many = (1..7).map { tab("s$it") }

        val pruned = pruneOpenTabs(many, max = 5)

        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), pruned.map { it.sessionId })
    }

    @Test
    fun `会话号两边的空白按不存在处理`() {
        // 存档是文本文件，手改一下就会带上空格 —— 带着空格去 listSessions 里找，
        // 找不着，而症状是"这条标签回来了但会话没了"，看不出是空格的事
        val pruned = pruneOpenTabs(listOf(tab("  s1  "), tab("s1")), max = 5)

        assertEquals(listOf("s1"), pruned.map { it.sessionId }, "trim 之后该认出是同一条")
    }

    // ---- 落盘那一层 ----

    /**
     * 按平台写盘的路子序列化一份 state。
     *
     * 必须带 [SkipDefaultsSerializationFilter]：文件存储的默认过滤器就是它的子类，
     * 而"跳过等于默认值的字段"恰恰是最可能把东西吞掉的那一步 —— 用不过滤的写法测，
     * 等于绕开唯一有风险的地方（同 `ModelProfilesTest` 的写法）。
     *
     * 这里真正要钉的是：`OpenTab` 这个**嵌套在列表里的自定义类型** XmlSerializer
     * 认不认。不认的话写盘静默留空，症状就是"重启后页签又只剩一个"。
     */
    private fun roundTrip(state: ClaudeSettings.State): ClaudeSettings.State {
        val element = Element("component")
        XmlSerializer.serializeInto(state, element, SkipDefaultsSerializationFilter())
        return XmlSerializer.deserialize(element, ClaudeSettings.State::class.java)
    }

    @Test
    fun `整个标签表经 XmlSerializer 往返后逐字不变`() {
        val saved = mutableListOf(
            tab("s1", "p1", "flash", title = "改个按钮"),
            tab("s2", "p2", "pro", title = null, selected = true),
            tab("", "", ""),
        )

        val back = roundTrip(ClaudeSettings.State(openTabs = saved))

        assertEquals(saved, back.openTabs)
    }

    @Test
    fun `标题也一起存下来`() {
        // 没上屏的标签**问不到**会话列表（要等用户点它才起 sidecar），胶囊行却
        // 现在就要画 —— 不存名字的话，一开 IDE 会看到一排「新会话」，
        // 而它们其实各有名字
        val saved = mutableListOf(tab("s1", title = "把设置页重新排一下"))

        assertEquals("把设置页重新排一下", roundTrip(ClaudeSettings.State(openTabs = saved)).openTabs.first().title)
        assertEquals("把设置页重新排一下", pruneOpenTabs(saved, max = 5).first().title)
    }

    @Test
    fun `一个标签都没有时不留空壳`() {
        // 默认值不该写进 XML —— 否则每个项目一打开就多一段空元素
        val element = Element("component")
        XmlSerializer.serializeInto(ClaudeSettings.State(), element, SkipDefaultsSerializationFilter())

        assertNull(element.getChild("openTabs"))
    }

    @Test
    fun `读回来的表是一份快照，改它不碰状态`() {
        // 交出去的是活的那一份的话，调用方一次 trim/prune 就改了"真相"，
        // 而写盘那边还以为自己存的是原来那份（同 loadState 里那段注释的坑）
        val settings = ClaudeSettings().apply {
            rememberOpenTabs(listOf(tab("s1"), tab("s2")))
        }

        val handed = settings.openTabs().toMutableList()
        handed.clear()

        assertEquals(2, settings.openTabs().size, "外面清空把里面也清了")
    }

    @Test
    fun `重启 IDE 之后还记得开着哪些标签`() {
        // loadState 是**逐字段复制**的（见那边的注释）：新字段忘了写就会静默丢掉，
        // 症状是"重启后页签又只剩一个"
        val saved = mutableListOf(tab("s1", "p1", "flash"), tab("s2", "p2", "pro", selected = true))
        val loaded = ClaudeSettings().apply {
            loadState(ClaudeSettings.State(openTabs = saved))
        }

        assertEquals(saved, loaded.openTabs())
    }
}
