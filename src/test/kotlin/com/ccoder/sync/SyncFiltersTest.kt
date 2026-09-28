package com.ccoder.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 过滤三层（2026-09-24）。
 *
 * 这一层是"我改的东西怎么没同步"与"目标端怎么多了个奇怪的文件"两类问题的**唯一**出处
 * ——过滤宽了会漏同步，窄了会把不该动的删掉。所以它必须是纯函数，且每个判定分支都得
 * 有个名字说得清的用例。
 *
 * 语义全部照抄参考实现（`Desktop\sync\sync_remote.py`），只有一处**刻意的修正**：
 * 含 `/` 又带通配符的排除项（见 [isExcluded] 的注释），参考实现那条路是静默失效的。
 */
class SyncFiltersTest {

    private val junk = JunkRules.DEFAULT

    // ---------------------------------------------------------------- 归一

    @Test
    fun `归一：反斜杠、首尾斜杠、大小写`() {
        assertEquals("trunk/a.kt", normalizeRelPath("\\Trunk\\A.KT"))
        assertEquals("a/b", normalizeRelPath("/a/b/"))
        assertEquals("", normalizeRelPath("   "))
    }

    // ---------------------------------------------------------------- 同步范围

    @Test
    fun `范围表为空 = 整个源目录都在范围内`() {
        assertTrue(inScope("anything/at/all.kt", emptyList()))
        assertTrue(inScopeDir("anything", emptyList()))
    }

    @Test
    fun `范围按路径分量比 —— trunk 不命中 trunkish`() {
        assertTrue(inScope("trunk/a.kt", listOf("trunk")))
        assertTrue(inScope("trunk", listOf("trunk")), "范围根自身算在范围内")
        assertFalse(inScope("trunkish/a.kt", listOf("trunk")), "名字前缀相同但不是同一层路径")
    }

    @Test
    fun `嵌套范围：只命中所属的那一支`() {
        val roots = listOf("trunk/defines")
        assertTrue(inScope("trunk/defines/x.h", roots))
        assertFalse(inScope("trunk/src/x.cpp", roots), "兄弟分支不属于这个范围")
    }

    @Test
    fun `嵌套范围时祖先目录必须下探 —— 否则扫描结果直接空掉`() {
        val roots = listOf("trunk/defines")
        assertTrue(
            inScopeDir("trunk", roots),
            "trunk 是 trunk/defines 的祖先。用 inScope 判它是不在范围内，于是根本不下探，" +
                "那个范围就永远扫不到东西 —— 表现是「文件明明改了却不同步」",
        )
        assertTrue(inScopeDir("", roots), "根永远要下探")
        assertTrue(inScopeDir("trunk/defines/deep", roots), "范围内更深的目录当然要下探")
        assertFalse(inScopeDir("config", roots), "兄弟目录不属于任何范围，不必下探")
    }

    // ---------------------------------------------------------------- 排除项

    @Test
    fun `含斜杠的按相对路径匹配：等于它或在其下，而且是锚在源目录根上的`() {
        val pats = listOf("trunk/temp")
        assertTrue(isExcluded("trunk/temp", pats))
        assertTrue(isExcluded("trunk/temp/a.txt", pats))
        assertFalse(
            isExcluded("other/trunk/temp/a.txt", pats),
            "路径写法锚在根上，不在任意一层 —— 那正是它与「不含斜杠」写法的分工",
        )
    }

    @Test
    fun `不含斜杠的写法在任意一层都成立 —— 参考实现的 README 把它当成路径写法了`() {
        // 参考实现的 README 把 `.claude` 写在「含 /」那一条下面，但 `.claude` 里根本没有
        // 斜杠，所以它实际走的是「任意一层」那条路。**效果上正合人们的期望**（哪儿都排掉），
        // 但用例要把这个事实钉住，免得有人以为写 `.claude` 是锚定根部的。
        val pats = listOf(".claude")
        assertTrue(isExcluded(".claude", pats))
        assertTrue(isExcluded(".claude/settings.json", pats))
        assertTrue(isExcluded("src/.claude/x", pats), "不含斜杠 = 任意一层，所以这里也命中")
    }

    @Test
    fun `不含斜杠的匹配任意一层 —— 位置无关`() {
        val pats = listOf("build")
        assertTrue(isExcluded("build", pats))
        assertTrue(isExcluded("trunk/build/x.obj", pats))
        assertFalse(isExcluded("rebuild/x.obj", pats), "层内是整名匹配，不是子串匹配")
    }

    @Test
    fun `层内通配：星号任意串，问号恰好一个字符`() {
        assertTrue(isExcluded("a/b/note.log", listOf("*.log")))
        assertTrue(isExcluded("main.kt", listOf("main.?t")))
        assertFalse(isExcluded("main.ktx", listOf("main.?t")))
        assertFalse(isExcluded("a/b/n.log.txt", listOf("*.log")), "匹配的是整层名字，不是子串")
    }

    @Test
    fun `含斜杠又带通配符的按整条路径匹配 —— 这是对参考实现的修正`() {
        // 参考实现里凡是含 / 的模式只做字面前缀匹配，于是这类写法**永远匹配不上**：
        // 用户以为排掉了，实际一个文件都没排掉，而且没有任何地方会告诉他。
        assertTrue(isExcluded("trunk/temp/a.tmp", listOf("trunk/temp/*")))
        assertTrue(isExcluded("trunk/a/b.kt", listOf("trunk/*")), "此时的星号可以跨层（同 fnmatch）")
        assertFalse(isExcluded("other/temp/a.tmp", listOf("trunk/temp/*")))
    }

    @Test
    fun `左方括号是普通字符，不当字符集`() {
        assertTrue(isExcluded("a[1].kt", listOf("a[1].kt")))
        assertFalse(
            isExcluded("a1.kt", listOf("a[1].kt")),
            "排除项是手写的，多一种括号语义只会多一处惊喜",
        )
    }

    @Test
    fun `排除项为空表时什么都不排`() {
        assertFalse(isExcluded("anything.kt", emptyList()))
    }

    // ---------------------------------------------------------------- 通配匹配本身

    @Test
    fun `通配匹配：星号能吃空串也能跨层`() {
        assertTrue(globMatch("abc", "a*"))
        assertTrue(globMatch("abc", "*"))
        assertTrue(globMatch("", "*"))
        assertTrue(globMatch("a/b/c.log", "*c.log"))
        assertFalse(globMatch("abc", "a?"))
        assertTrue(globMatch("abc", "a?c"))
    }

    @Test
    fun `一串星号不会指数爆炸`() {
        // 递归写法在「一堆星号 + 一个匹配不上的尾巴」上会指数级回溯。这个函数在扫描时
        // 每个文件都要调，所以那条路必须是 O(n×m) 的双指针 —— 这个用例就是那只哨兵。
        assertFalse(globMatch("a".repeat(40) + "b", "*".repeat(20) + "c"))
        assertTrue(globMatch("a".repeat(40) + "b", "*".repeat(20) + "b"))
    }

    // ---------------------------------------------------------------- 内置垃圾规则

    @Test
    fun `祖先目录名判垃圾 —— 目录自身不算，它的子路径算`() {
        assertFalse(
            isJunk(".git", junk),
            "目录自身要留给 isJunkDir 判。这条不对称是刻意的：目录剪枝与文件过滤是两个判断",
        )
        assertTrue(isJunk(".git/x", junk))
        assertTrue(isJunk("trunk/node_modules/pkg/index.js", junk))
    }

    @Test
    fun `叶子按文件名、后缀、前缀三组判，且大小写不敏感`() {
        assertTrue(isJunk("a/Thumbs.db", junk))
        assertTrue(isJunk("a/x.log", junk))
        assertTrue(isJunk("a/gslog20260924.txt", junk), "前缀组：M71 那几支日志家族")
        assertFalse(isJunk("a/main.kt", junk))
    }

    @Test
    fun `isJunkEntry 让目录自身也能被挡掉 —— 监听那条路靠它`() {
        assertTrue(isJunkEntry("trunk/.git", junk, isDirectory = true))
        assertTrue(isJunkEntry("trunk/node_modules", junk, isDirectory = true))
        assertTrue(isJunkEntry("trunk/node_modules/pkg/x.js", junk, isDirectory = false))
        assertFalse(
            isJunkEntry("trunk/.git", junk, isDirectory = false),
            "当成文件问就不是垃圾 —— 正是那个不对称。所以目录事件必须走 isDirectory = true",
        )
    }

    @Test
    fun `自己新增的 JVM 生态项也在默认表里`() {
        assertTrue(isJunkEntry("proj/.gradle", junk, isDirectory = true))
        assertTrue(isJunkEntry("proj/build", junk, isDirectory = true))
        assertTrue(isJunkEntry("proj/out", junk, isDirectory = true))
        assertTrue(isJunkEntry("proj/target", junk, isDirectory = true))
        assertTrue(isJunkEntry("proj/dist", junk, isDirectory = true))
    }

    // ---------------------------------------------------------------- 三层串起来

    @Test
    fun `syncable：范围 + 排除 + 垃圾，任一命中就不同步`() {
        val cfg = SyncConfig(syncRoots = listOf("trunk"), exclude = listOf("temp"))
        assertTrue(syncable("trunk/src/main.kt", cfg), "三层都没命中")
        assertFalse(syncable("config/branch.links", cfg), "范围外")
        assertFalse(syncable("trunk/temp/a.txt", cfg), "被排除项挡掉")
        assertFalse(syncable("trunk/node_modules/x.js", cfg), "垃圾目录的子路径")
    }
}
