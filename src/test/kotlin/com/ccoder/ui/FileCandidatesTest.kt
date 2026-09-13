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
}
