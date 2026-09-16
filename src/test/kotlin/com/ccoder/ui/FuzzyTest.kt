package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 子序列匹配 —— 文件补全与符号补全**共用**的那一把尺子。
 *
 * 钉的是三样：命中的**位置**（弹层拿它加粗）、顺序（乱了就不算命中）、
 * 以及打分里那几条偏好（连着 > 散着，边界 > 中间，文件名 > 目录）。
 */
class FuzzyTest {

    @Test
    fun `子序列命中，下标按升序给出来`() {
        // C0 o1 m2 p3 o4 s5 e6 r7 M8 o9 d10 e11 .12 k13 t14
        val m = fuzzyMatch("ComposerMode.kt", "cmprk")

        assertEquals(listOf(0, 2, 3, 7, 13), m?.hits)
    }

    @Test
    fun `顺序不对就不算命中`() {
        assertNull(fuzzyMatch("abc", "ba"))
    }

    @Test
    fun `连着命中比散着的分高`() {
        val tight = fuzzyMatch("lib/xabc.kt", "abc")!!.score
        val loose = fuzzyMatch("lib/aXbXc.kt", "abc")!!.score

        assertTrue(tight > loose, "连着 $tight 应当高于散着 $loose")
    }

    @Test
    fun `词边界上的命中加分`() {
        // `a_b` 里那个 b 在 `_` 之后（边界）；`axb` 里两边都是字母（不是边界）
        val boundary = fuzzyMatch("lib/a_b.kt", "ab")!!.score
        val plain = fuzzyMatch("lib/axb.kt", "ab")!!.score

        assertTrue(boundary > plain, "边界 $boundary 应当高于中间 $plain")
    }

    @Test
    fun `空查询与超长查询都给 null —— 那等于什么都命中`() {
        assertNull(fuzzyMatch("abc", ""))
        assertNull(fuzzyMatch("ab", "abc"))
    }

    @Test
    fun `文件名里的命中比只在目录里命中高一个量级`() {
        val inBase = fuzzyMatchPath("src/x/xAbcThing.kt", "abc")!!
        val inDir = fuzzyMatchPath("src/abc/Foo.kt", "abc")!!

        assertTrue(inBase.score > inDir.score + 500, "文件名 ${inBase.score} 应当远高于目录 ${inDir.score}")
    }

    @Test
    fun `下标是接着整条路径数的 —— 高亮要直接能用在整行上`() {
        val path = "src/x/xAbcThing.kt"

        val m = fuzzyMatchPath(path, "abc")!!

        assertEquals("Abc", path.substring(m.hits.first(), m.hits.last() + 1))
    }
}
