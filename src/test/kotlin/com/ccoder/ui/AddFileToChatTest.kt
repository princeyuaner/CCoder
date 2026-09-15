package com.ccoder.ui

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 右键「加文件到聊天框」里**取哪几个文件**那一步。
 *
 * 2026-09-15 实机反馈：项目树里选中文件右键，那一项**看得见但是灰的**。
 * 灰 = 这一层返回了空 —— 动作于是把自己禁掉，界面上只表现为"点不动"。
 * 根因见 [pickFiles] 的注释（2026.1 的项目树弹层不给 `VIRTUAL_FILE_ARRAY`）。
 *
 * 两条取数的 `getData` 是胶水，这里测的是**判定**：谁的优先级高、
 * 空的怎么退、目录怎么滤。
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
}
