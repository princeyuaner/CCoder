package com.ccoder.ui

import com.ccoder.sidecar.ContextDetail
import com.ccoder.sidecar.ContextOverLimit
import com.ccoder.sidecar.ContextRow
import com.ccoder.text.CcoderText
import com.ccoder.text.TextCatalog
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * 上下文明细的**显示层模型**：判类、求和、文案折中。全是纯函数，不碰 Swing。
 *
 * 三条分别是不同性质的东西，所以分开钉：
 * - **判类**只认 `kind`（SDK 原话 "Classify on this, never on the English name"）——
 *   名字是展示串，说改就改；拿它判类就是"CLI 改一个词，界面少一段"。
 * - **求和**里 `deferred` 不参与（它压根不占窗口）。
 * - **文案**是折中：认得给键、认不出原样 —— 这条最容易被后人"顺手统一"掉，所以正反都钉。
 */
class ContextDetailTest {

    /** 这一组用例断言的是中文那份词表（同 RunDetailTest 的口径）。 */
    @BeforeEach
    fun pinChinese() = CcoderText.setOverride(Locale.SIMPLIFIED_CHINESE)

    @AfterEach
    fun unpin() = CcoderText.setOverride(null)

    private fun row(name: String, tokens: Long, kind: String = "", sub: String = "") =
        ContextRow(label = name, sub = sub, tokens = tokens, kind = kind)

    private fun detail(
        categories: List<ContextRow> = emptyList(),
        mcpTools: List<ContextRow> = emptyList(),
        memoryFiles: List<ContextRow> = emptyList(),
        agents: List<ContextRow> = emptyList(),
        skills: List<ContextRow> = emptyList(),
        overLimit: ContextOverLimit? = null,
        model: String = "deepseek-v4-flash",
    ) = ContextDetail(model, 27, overLimit, categories, mcpTools, memoryFiles, agents, skills)

    // ---- 判类 ----

    @Test
    fun `判类只认 kind，不认名字`() {
        // 名字故意与类别"对不上"：叫 Free space 但 kind=used 的行**必须**算内容。
        // 这条钉的就是"别拿名字判"—— 谁改成按名字判，这里当场红
        val lines = contextLinesOf(
            listOf(
                row("Free space", 100, kind = "used"),
                row("Messages", 200, kind = "free"),
            )
        )

        assertEquals(ContextKind.Used, lines[0].kind)
        assertEquals(ContextKind.Free, lines[1].kind)
    }

    @Test
    fun `认不出的 kind 当内容`() {
        // SDK 明说这结构只做加法。真来了第四种类别，宁可多算一段，也不要把它画成
        // 没占窗口的样子（那会让条与总数对不上）
        assertEquals(ContextKind.Used, contextKindOf("reserved_by_someone_new"))
        assertEquals(ContextKind.Used, contextKindOf(""))
    }

    @Test
    fun `零 token 的行不显示`() {
        // SDK 原话：分类行可能带 0，"renderers typically hide those"
        val lines = contextLinesOf(listOf(row("Messages", 250), row("Skills", 0)))

        assertEquals(listOf(250L), lines.map { it.tokens })
    }

    // ---- 求和 ----

    @Test
    fun `deferred 不进堆叠条，也不进用量`() {
        val lines = contextLinesOf(
            listOf(
                row("Messages", 250, kind = "used"),
                row("Compaction reserve", 33, kind = "buffer"),
                row("Free space", 699, kind = "free"),
                row("MCP tools (deferred)", 42, kind = "deferred"),
            )
        )

        assertEquals(250L, contextUsedTokensOf(lines), "窗口外的行不该算进用量")
        assertEquals(
            listOf(contextRowLabel("Messages"), contextRowLabel("Compaction reserve")),
            contextBarOf(lines).map { it.label },
            "堆叠条只画内容与预留 —— 自由空间填剩下那截，窗口外的不画",
        )
    }

    // ---- 文案折中 ----

    @Test
    fun `认得的英文名走词表`() {
        assertEquals(CcoderText.text("context.row.messages"), contextRowLabel("Messages"))
        assertEquals(CcoderText.text("context.row.mcpToolsDeferred"), contextRowLabel("MCP tools (deferred)"))
    }

    @Test
    fun `认不出的英文名原样透传`() {
        // 折中的另一半：CLI 改了措辞的那天，那一行显示英文（看得见），
        // 而不是空着、也不是显示键名
        assertEquals("Some new category", contextRowLabel("Some new category"))
        assertTrue(contextRowLabel("Some new category").isNotEmpty())
    }

    @Test
    fun `映射表里的每个键，两份词表里都在`() {
        // TextKeysTest 那个方向只管 CcoderText.text("…") 的字面调用点，而这些键是
        // map 的值 —— 漏一条词表就会在界面上显示成 context.row.freeSpace，没人拦得住
        val missing = CONTEXT_ROW_KEYS.filterNot { key ->
            TextCatalog.has(TextCatalog.bundleFor(Locale.ENGLISH), key) &&
                TextCatalog.has(TextCatalog.bundleFor(Locale.SIMPLIFIED_CHINESE), key)
        }
        assertEquals(emptyList<String>(), missing, "这些键缺在词表里")
    }

    // ---- 超窗 ----

    @Test
    fun `超窗两种措辞分开`() {
        val hard = contextOverLimitText(ContextOverLimit(12_400, ContextOverLimit.HARD_LIMIT))
        val soft = contextOverLimitText(ContextOverLimit(12_400, ContextOverLimit.COMPACTION_WINDOW))

        assertTrue(hard != null && "拒绝" in hard, "硬顶要说清会被拒绝：$hard")
        assertTrue(soft != null && "压缩" in soft, "压缩窗要说清会自动压缩：$soft")
        assertTrue(hard != soft)
        // 认不出的 kind 宁可不说
        assertNull(contextOverLimitText(ContextOverLimit(1, "something_new")))
        assertNull(contextOverLimitText(null))
    }

    // ---- 清单 ----

    @Test
    fun `空的清单不给页签`() {
        val lists = contextListsOf(
            detail(mcpTools = listOf(row("mcp__a__b", 8100, sub = "server")))
        )

        assertEquals(listOf(ContextListKind.McpTools), lists.map { it.kind })
        assertEquals(8100L, contextListTokensOf(lists[0]))
    }

    // ---- 复制 ----

    @Test
    fun `复制出来的明细自带标题行`() {
        val text = contextDetailMarkdownOf(
            detail(
                categories = listOf(row("Messages", 250_000, kind = "used")),
                mcpTools = listOf(row("mcp__codegraph__explore", 8100, sub = "codegraph")),
            ),
            ContextUsage(usedTokens = 268_000, windowTokens = 1_000_000),
        )

        assertTrue(text.startsWith(CcoderText.text("context.title")), "第一行该是标题：$text")
        assertTrue("268k / 1M" in text, "带上总量：$text")
        assertTrue("deepseek-v4-flash" in text, "带上模型：$text")
        assertTrue(CcoderText.text("context.row.messages") in text, "分类行要在：$text")
        assertTrue("mcp__codegraph__explore" in text, "清单条目要在：$text")
    }
}
