package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent

/**
 * 补全的纯逻辑：触发、过滤、高亮、按键意图、插入。
 *
 * 抽出来的理由同 [ComposerRulesTest]：ClaudePanel 依赖 Project 起不了单测，
 * 而这五处正是最容易写错的地方。
 */
class CompletionTest {

    private fun q(text: String) = completionQuery(text, text.length)

    // ---- 触发 ----

    @Test
    fun `斜杠只在消息开头触发`() {
        assertEquals(CompletionQuery(Trigger.Command, "comp", 0), q("/comp"))
    }

    @Test
    fun `路径与日期里的斜杠不触发 —— 任何位置都弹会一直打断打字`() {
        assertNull(q("C:/Users"), "盘符路径")
        assertNull(q("1/2"), "分数")
        assertNull(q("and/or"), "词组")
        assertNull(q("看看 /comp"), "句子中间的斜杠不算消息开头")
    }

    @Test
    fun `at 只在词边界触发`() {
        assertEquals(CompletionQuery(Trigger.File, "Composer", 6), q("hello @Composer"))
        assertEquals(CompletionQuery(Trigger.File, "x", 0), q("@x"))
    }

    @Test
    fun `邮箱里的 at 不触发`() {
        assertNull(q("foo@bar.com"))
    }

    @Test
    fun `打了空格就收 —— 那之后是参数或正文，不是候选的一部分`() {
        assertNull(q("/compact 自定义说明"))
        assertNull(q("@Composer.kt 看一下这个文件"))
    }

    @Test
    fun `命令的参数里还能再触发文件补全`() {
        assertEquals(CompletionQuery(Trigger.File, "Comp", 9), q("/compact @Comp"))
    }

    @Test
    fun `光标不在末尾时只看光标之前`() {
        val text = "/comp 后面还有字"
        assertEquals(CompletionQuery(Trigger.Command, "comp", 0), completionQuery(text, 5))
    }

    @Test
    fun `光标越界不抛`() {
        assertEquals(CompletionQuery(Trigger.Command, "comp", 0), completionQuery("/comp", 99))
        assertNull(completionQuery("", -5))
    }

    // ---- 过滤 ----

    private val compact = CompletionItem("compact", "compact", "压缩上下文")
    private val usage = CompletionItem("usage", "usage", "花费", aliases = listOf("cost", "stats"))
    private val debug = CompletionItem("Debug Issue", "debug-issue", "查问题")

    @Test
    fun `空前缀给全部`() {
        assertEquals(3, filterCandidates(listOf(compact, usage, debug), "").size)
    }

    @Test
    fun `前缀匹配大小写不敏感`() {
        assertEquals(listOf(debug), filterCandidates(listOf(compact, usage, debug), "deb"))
        assertEquals(listOf(debug), filterCandidates(listOf(compact, usage, debug), "DEBUG"))
    }

    @Test
    fun `别名也参与匹配 —— 用户在终端里敲惯的是 cost`() {
        assertEquals(listOf(usage), filterCandidates(listOf(compact, usage, debug), "cost"))
        assertEquals(listOf(usage), filterCandidates(listOf(compact, usage, debug), "sta"))
    }

    @Test
    fun `匹配的是前缀不是子串`() {
        assertEquals(emptyList<CompletionItem>(), filterCandidates(listOf(compact, usage, debug), "pact"))
    }

    // ---- 高亮 ----

    @Test
    fun `高亮到头就停，不循环`() {
        assertEquals(0, nextHighlight(0, -1, 5), "第一项再往上还是第一项")
        assertEquals(4, nextHighlight(4, 1, 5), "最后一项再往下还是最后一项")
        assertEquals(2, nextHighlight(1, 1, 5))
    }

    @Test
    fun `没有候选时高亮恒为 0`() {
        assertEquals(0, nextHighlight(3, 1, 0))
    }

    // ---- 按键意图 ----

    @Test
    fun `弹层开着时上下键移动、回车与 Tab 采纳、Esc 关闭`() {
        assertEquals(CompletionKey.Up, completionKey(KeyEvent.VK_UP))
        assertEquals(CompletionKey.Down, completionKey(KeyEvent.VK_DOWN))
        assertEquals(CompletionKey.Accept, completionKey(KeyEvent.VK_ENTER))
        assertEquals(CompletionKey.Accept, completionKey(KeyEvent.VK_TAB))
        assertEquals(CompletionKey.Dismiss, completionKey(KeyEvent.VK_ESCAPE))
    }

    @Test
    fun `其余按键一律不接管`() {
        assertEquals(CompletionKey.Ignore, completionKey(KeyEvent.VK_A))
        assertEquals(CompletionKey.Ignore, completionKey(KeyEvent.VK_SHIFT))
    }

    // ---- 插入 ----

    @Test
    fun `文件插入 at 加路径加一个尾随空格`() {
        val (text, caret) = applyCompletion(
            "@Comp", 5, completionQuery("@Comp", 5)!!,
            CompletionItem("src/Composer.kt", "src/Composer.kt"),
        )
        assertEquals("@src/Composer.kt ", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun `命令插入斜杠加可发送名，不加空格也不自动发送`() {
        val (text, caret) = applyCompletion(
            "/deb", 4, completionQuery("/deb", 4)!!,
            CompletionItem("Debug Issue", "debug-issue"),
        )
        assertEquals("/debug-issue", text)
        assertEquals(text.length, caret)
    }

    @Test
    fun `光标之后的文字原样保留 —— 在中间采纳不该吃掉后半句`() {
        val text = "看看 @Comp 这个文件"
        // 光标停在 @Comp 之后、空格之前
        val (out, caret) = applyCompletion(
            text, 8, completionQuery(text, 8)!!,
            CompletionItem("src/Composer.kt", "src/Composer.kt"),
        )
        assertEquals("看看 @src/Composer.kt  这个文件", out)
        assertEquals("看看 @src/Composer.kt ".length, caret)
    }

    @Test
    fun `替换的是从触发字符起那一段，不是插在光标处`() {
        // 用户已经敲了 /comp，那五个字符是查询词，采纳后不该剩下
        val (text, _) = applyCompletion(
            "/comp", 5, completionQuery("/comp", 5)!!,
            CompletionItem("compact", "compact"),
        )
        assertEquals("/compact", text)
    }

    @Test
    fun `原样插入的候选不拼触发字符、也不加尾随空格`() {
        // 预置 prompt 走的正是这条：要写进输入框的是 prompt 正文，不是「/正文」
        val (text, caret) = applyCompletion(
            "/写测试", 4, completionQuery("/写测试", 4)!!,
            CompletionItem("写测试", "给这段代码补单测", verbatim = true),
        )
        assertEquals("给这段代码补单测", text)
        assertEquals(text.length, caret)
    }
}
