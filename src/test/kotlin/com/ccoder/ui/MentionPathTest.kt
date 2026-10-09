package com.ccoder.ui

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * 引用里的路径怎么写（[mentionPathOf] / [relativePathWithin]，2026-10-09 加）。
 *
 * 起念是真机逮到的一个：拖进来一个**项目外**的文件，输入框里躺着一个
 * `@../../../../../../…` —— `FileUtil.getRelativePath` 对**同盘的项目外**路径
 * **不返回 null**，而是造一条 `..` 链，而注释里一直写着"相对不了就别硬凑"。
 * 这里把那几种形状都钉住（`..` 链一律退回绝对路径）。
 */
class MentionPathTest {

    /** 只应答 `basePath` 的 Project 替身（同 `layoutProbeProject` 的写法）。 */
    private fun projectAt(base: String?): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> base
                "toString" -> "mention path probe"
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as Project

    @Test
    fun `项目内 → 相对路径（斜杠分隔）`() {
        assertEquals("src/Main.kt", relativePathWithin("C:/proj", "C:/proj/src/Main.kt"))
        assertEquals("src/Main.kt", mentionPathOf(projectAt("C:/proj"), "C:/proj/src/Main.kt"))
    }

    @Test
    fun `同盘的项目外路径判否 —— getRelativePath 会造上跳链，不能认`() {
        // 真机那一笔：base 六层深、文件在别处，算出六个 `..` 再加一截路径
        assertNull(relativePathWithin("C:/a/b/c/d/e/f", "C:/Users/CY/Desktop/x.py"))
        // 相邻目录也一样（会算出 `../CCoder2/x.py` 那种）
        assertNull(relativePathWithin("C:/Users/CY/Desktop/CCoder", "C:/Users/CY/Desktop/CCoder2/x.py"))
    }

    @Test
    fun `项目外的引用退成绝对路径`() {
        assertEquals(
            "C:/elsewhere/x.py",
            mentionPathOf(projectAt("C:/a/b/c/d/e/f"), "C:/elsewhere/x.py"),
        )
    }

    @Test
    fun `不同盘 → 绝对路径`() {
        assertNull(relativePathWithin("C:/proj", "D:/work/x.py"), "不同的根本来就算不出来")
        assertEquals("D:/work/x.py", mentionPathOf(projectAt("C:/proj"), "D:/work/x.py"))
    }

    @Test
    fun `拿不到项目根 → 原样绝对路径`() {
        assertEquals("C:/x/y.py", mentionPathOf(projectAt(null), "C:/x/y.py"))
    }

    @Test
    fun `路径为空 → 占位`() {
        assertEquals(UNKNOWN_PATH, mentionPathOf(projectAt("C:/proj"), null))
    }
}
