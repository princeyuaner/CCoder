package com.ccoder.ui

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 右键「加文件到聊天框」里**取哪几个文件**那一步。
 *
 * 2026-09-15 实机反馈：项目树里选中文件右键，那一项**看得见但是灰的**。
 * 灰 = 这一层返回了空 —— 动作于是把自己禁掉，界面上只表现为"点不动"。
 * 根因见 [pickFiles] 的注释（2026.1 的项目树弹层不给 `VIRTUAL_FILE_ARRAY`）。
 * 2026-09-16 又灰了一次，日志把第二个原因钉死：连 `NAVIGATABLE_ARRAY` 里那一个
 * 都不是 VirtualFile，见 [navigatableFile]（PSI 那两条分支在这里测不了 ——
 * 无头 JVM 里没有 Application，造不出 PsiElement）。
 *
 * 两条取数的 `getData` 是胶水，这里测的是**判定**：谁的优先级高、
 * 空的怎么退、目录怎么滤、认不出的导航对象怎么办。
 */
class AddFileToChatTest {

    private fun file(name: String): VirtualFile = LightVirtualFile(name)

    @Test
    fun `第一个有内容的来源说了算`() {
        val picked = pickFiles(listOf(file("a.kt")), listOf(file("b.kt")))

        assertEquals(listOf("a.kt"), picked.map { it.name })
    }

    @Test
    fun `多选没了就退回单选`() {
        // 这一条就是这次的 bug：只认多选那个键的话，项目树里可能永远是灰的
        val picked = pickFiles(null, listOf(file("a.kt")), listOf(file("b.kt")))

        assertEquals(listOf("a.kt"), picked.map { it.name }, "退回这条路断了")
    }

    @Test
    fun `空数组和'没给'是一回事，都要往下退`() {
        val picked = pickFiles(emptyList(), null, listOf(file("a.kt")))

        assertEquals(listOf("a.kt"), picked.map { it.name })
    }

    @Test
    fun `一路都没有就是空 —— 那时动作该是灰的`() {
        assertTrue(pickFiles(null, null).isEmpty())
        assertTrue(pickFiles(emptyList(), null, emptyList()).isEmpty())
    }

    @Test
    fun `目录被滤掉 —— @ 认的是文件`() {
        // 目录判定的口子在参数里：无头测试造不出 isDirectory=true 的 VirtualFile
        val picked = pickFiles(
            listOf(file("a.kt"), file("src")),
            isDirectory = { it.name == "src" },
        )

        assertEquals(listOf("a.kt"), picked.map { it.name })
    }

    @Test
    fun `多选时按顺序全留着`() {
        val picked = pickFiles(listOf(file("a.kt"), file("b.kt"), file("c.kt")))

        assertEquals(listOf("a.kt", "b.kt", "c.kt"), picked.map { it.name })
    }

    @Test
    fun `导航对象是 VirtualFile 就直接用`() {
        val f = file("a.kt")

        assertEquals(f, navigatableFile(f))
    }

    @Test
    fun `认不出的导航对象给 null —— 不抛，也不硬塞`() {
        // 2026-09-16 的真身就在这一类里：平台给的那一个不是 VirtualFile，
        // 旧代码 `filterIsInstance<VirtualFile>()` 把它**静默丢掉**，动作于是灰着，
        // 界面上一点解释都没有。这里是"认不出"这条路的下限：给 null，别抛。
        assertNull(navigatableFile(null))
        assertNull(navigatableFile("既不是文件也不是 PSI"))
        assertNull(navigatableFile(42))
    }

    @Test
    fun `认不出的导航对象走完整条取数 —— 结果是空，也就是灰`() {
        val picked = pickFiles(
            null,
            null,
            listOf("认不出的东西").mapNotNull { navigatableFile(it) },
        )

        assertTrue(picked.isEmpty(), "认不出就该是空，而不是塞个假文件进去")
    }

    @Test
    fun `项目树给的是一个节点 —— 顺着它包着的值认`() {
        // 2026-09-16 的真身：`PsiFileNode`，项目树的节点，既不是 VirtualFile
        // 也不是 PSI 元素 —— 它包着的那个值才是有用的东西
        val f = file("a.kt")

        assertEquals(f, navigatableFile(FakeNode(f), ::valueOfFake))
    }

    @Test
    fun `节点包着认不出的东西 —— 还是给 null`() {
        assertNull(navigatableFile(FakeNode("一个字符串"), ::valueOfFake))
    }

    @Test
    fun `节点的值指向自己 —— 停下，别绕成死循环`() {
        val loop = FakeNode("先占位").apply { value = this }

        assertNull(navigatableFile(loop, ::valueOfFake))
    }
}

/** 取假节点的值。真节点那个 `AbstractTreeNode.getValue()` 在这里是同一件事。 */
private fun valueOfFake(node: Any): Any? = (node as? FakeNode)?.value

/**
 * 一个假的树节点 —— **普通对象**，不是 `AbstractTreeNode`。
 *
 * 真的那个（`PsiFileNode`）在无头 JVM 里造不出来：`AbstractTreeNode` 的构造器
 * 会去要 `Application`（`setInternalValue` → `TreeAnchorizer.getService`，
 * 实测 NPE）。所以生产代码那边留了 `nodeValue` 这个口子，这里拿普通对象顶上，
 * 验的是**"顺着节点问它的值"这条递归**接得对不对；值本身怎么认，由上面几条分支管。
 */
private class FakeNode(var value: Any?)
