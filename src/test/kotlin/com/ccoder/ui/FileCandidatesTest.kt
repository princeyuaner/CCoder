package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 文件候选。只测匹配那半段 —— `collectProjectFiles` 依赖 Project，
 * 和 ClaudePanel 一样起不了单测（同 [ComposerRulesTest] 的拆分理由）。
 */
class FileCandidatesTest {

    private val paths = listOf(
        "src/main/kotlin/com/ccoder/ui/Composer.kt",
        "src/main/kotlin/com/ccoder/ui/Completion.kt",
        "src/test/kotlin/com/ccoder/ui/CompletionTest.kt",
        "README.md",
    )

    @Test
    fun `空前缀不给候选 —— 裸 at 在正常行文里也会出现，不该闪一个列表`() {
        assertTrue(fileCandidates(paths, "").isEmpty())
    }

    @Test
    fun `按路径前缀匹配`() {
        val out = fileCandidates(paths, "src/test/")
        assertEquals(listOf("src/test/kotlin/com/ccoder/ui/CompletionTest.kt"), out.map { it.display })
    }

    @Test
    fun `按文件名前缀匹配 —— 想找某个文件时是这么敲的`() {
        val out = fileCandidates(paths, "Completion")
        assertEquals(
            listOf(
                "src/main/kotlin/com/ccoder/ui/Completion.kt",
                "src/test/kotlin/com/ccoder/ui/CompletionTest.kt",
            ),
            out.map { it.display },
        )
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(1, fileCandidates(paths, "readme").size)
    }

    @Test
    fun `插入文本与显示都是相对路径，不带触发字符`() {
        val item = fileCandidates(paths, "README").single()
        assertEquals("README.md", item.insert)
        assertEquals("README.md", item.display)
    }

    @Test
    fun `超出上限就截断`() {
        val many = (1..100).map { "src/file$it.kt" }
        assertEquals(50, fileCandidates(many, "src/").size)
        assertEquals(3, fileCandidates(many, "src/", limit = 3).size)
    }

    // ---- 模糊（子序列）那一层，2026-09-16 加 ----
    //
    // 起因：想换用 CLI 的 `file_suggestions`，量完发现是降级
    // （见 sidecar/tools/probe-file-suggestions.mjs）—— 它唯一比我们强的是
    // 模糊匹配，所以只把这一条搬了过来。

    @Test
    fun `子序列也能命中 —— cmprk 找得到 ComposerMode kt`() {
        val out = fileCandidates(
            listOf(
                "src/main/kotlin/com/ccoder/ui/ComposerMode.kt",
                "src/main/kotlin/com/ccoder/ui/CommandCandidates.kt",
            ),
            "cmprk",
        )

        assertEquals(listOf("src/main/kotlin/com/ccoder/ui/ComposerMode.kt"), out.map { it.display })
    }

    @Test
    fun `前缀那批永远排在模糊那批前面`() {
        // 用户敲对了开头就该看到它第一，不该被"子序列恰好更像"的东西挤下去
        val out = fileCandidates(listOf("lib/xabc.kt", "abc/thing.kt"), "abc")

        assertEquals(listOf("abc/thing.kt", "lib/xabc.kt"), out.map { it.display })
    }

    @Test
    fun `连着命中的排在散着的命中前面`() {
        // 两条都进不了前缀层；`xabc` 里三个字母连着，`aXbXc` 里散着。
        // 顺序刻意写成"散的那条在前"—— 排对了才说明是打分排的，不是原顺序
        val out = fileCandidates(listOf("lib/aXbXc.kt", "lib/xabc.kt"), "abc")

        assertEquals(listOf("lib/xabc.kt", "lib/aXbXc.kt"), out.map { it.display })
    }

    @Test
    fun `文件名里的命中排在"只在目录里命中"前面`() {
        // 敲的是文件名片段时，`xAbcThing.kt` 比"躺在 abc 目录里的 Foo.kt"更像要找的
        val out = fileCandidates(listOf("src/abc/Foo.kt", "src/x/xAbcThing.kt"), "abc")

        assertEquals(listOf("src/x/xAbcThing.kt", "src/abc/Foo.kt"), out.map { it.display })
    }

    @Test
    fun `单字符不走模糊 —— 那会命中几乎所有路径`() {
        // `az.kt` 不是任何形式的前缀命中，但 `z` 是它的子序列
        assertTrue(fileCandidates(listOf("src/az.kt"), "z").isEmpty())
    }

    @Test
    fun `模糊也不分大小写`() {
        assertEquals(1, fileCandidates(listOf("lib/xAbcThing.kt"), "abct").size)
    }

    // ---- 命中下标：弹层拿它加粗（2026-09-16）----

    @Test
    fun `路径前缀命中的下标从 0 数`() {
        val item = fileCandidates(listOf("src/main/A.kt"), "src").single()

        assertEquals(listOf(0, 1, 2), item.hits)
    }

    @Test
    fun `文件名前缀命中的下标要加上文件名的起点偏移`() {
        // src/main/A.kt → s0 r1 c2 /3 m4 a5 i6 n7 /8 A9 .10 k11 t12
        val item = fileCandidates(listOf("src/main/A.kt"), "A.").single()

        assertEquals(listOf(9, 10), item.hits)
    }

    @Test
    fun `模糊命中的下标就是子序列在路径里的位置`() {
        val path = "src/main/kotlin/com/ccoder/ui/ComposerMode.kt"

        val item = fileCandidates(listOf(path), "cmprk").single()

        assertEquals(
            listOf('c', 'm', 'p', 'r', 'k'),
            item.hits.map { path[it].lowercaseChar() },
            "下标指到的那几个字符应当拼出查询本身",
        )
    }

    /**
     * 规模下的耗时。**这是哨兵不是基准**：量级变了就说明匹配里塞进了不该塞的
     * 东西（比如给每条路径 `lowercase()` 一次 —— 两万条就是两万个临时字符串，
     * 而这是每敲一个字都要跑一遍的地方）。
     *
     * 实测（2026-09-16）：两万条 × 5 次 = **126ms**（约 25ms/次）。其中前缀那类
     * （`Feat`）因为"收满 50 条就返回"几乎不花时间，**几乎全花在模糊那一层**：
     * 前缀匹配不出来时才轮到它，而那时要把两万条都扫一遍。
     * 本仓库真实规模是 **389 条** —— 同一段代码约 0.6ms，用户感觉不到。
     * 这个合成场景是给"哪天有人往匹配里塞了每字符分配"预备的闸门。
     *
     * 预算给到实测的三倍：机器一忙不该红，但翻十倍就该红。
     */
    @Test
    fun `两万条路径下的耗时`() {
        val many = (1..20_000).map { "src/main/kotlin/com/ccoder/mod$it/Feature$it.kt" }
        val queries = listOf("Feat", "feat12", "mod7/Fea", "cmprk", "zzz")

        fileCandidates(many, "Feat") // 预热：让 JIT 先把匹配那几段编出来

        val started = System.nanoTime()
        var hits = 0
        for (q in queries) hits += fileCandidates(many, q).size
        val ms = (System.nanoTime() - started) / 1_000_000
        println("probe: 两万条路径 × ${queries.size} 次查询 = ${ms}ms（命中 $hits 条）")

        assertTrue(ms < 400, "两万条路径 × ${queries.size} 次查询用了 ${ms}ms —— 匹配里可能塞进了不该塞的东西")
    }
}
