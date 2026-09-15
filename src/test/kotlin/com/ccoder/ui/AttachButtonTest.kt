package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent

/**
 * 工具栏最左那个附件按钮，以及"选中的文件怎么进输入框"那条分流。
 *
 * 按钮本身只负责**画**和"点没点"；会咬人的是分流规则（[splitChosenFiles]）——
 * 它写反的话，用户选一张图会拿到一个二进制的 `@` 引用，或者选了一堆代码文件
 * 却什么都没进输入框。
 */
class AttachButtonTest {

    private fun click(component: java.awt.Component) {
        component.dispatchEvent(
            MouseEvent(
                component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, 5, 5, 1, false, MouseEvent.BUTTON1,
            )
        )
    }

    @Test
    fun `点击把动作报出去`() {
        val button = AttachButton()
        var clicks = 0
        button.onClick = { clicks++ }

        click(button)

        assertEquals(1, clicks)
    }

    @Test
    fun `禁用时不报动作`() {
        val button = AttachButton()
        var clicks = 0
        button.onClick = { clicks++ }
        button.isEnabled = false

        click(button)

        assertEquals(0, clicks, "禁用状态不该响应点击")
    }

    @Test
    fun `尺寸钉死 —— 布局说什么都不算`() {
        // TopRowIconButton 那 72px 教训的直接继承：New UI 会给按钮兜一个最小宽度，
        // 一旦放任布局去算，图标周围就会白出一大截。这里自绘 + 三处尺寸写死
        IdeLaf.withRealLaf {
            val button = AttachButton()
            val size = button.preferredSize

            assertEquals(size, button.minimumSize)
            assertEquals(size, button.maximumSize)
            assertEquals(size.width, size.height, "正方形：图标按短边居中画")
            assertTrue(size.width >= 16, "太小了按不中：$size")
        }
    }

    @Test
    fun `有提示语 —— 图标不解释自己负责什么`() {
        assertTrue(AttachButton().toolTipText?.isNotBlank() == true)
    }

    // ---- 分流 ----

    @Test
    fun `图进附件带，其余插 @ 引用`() {
        val chosen = splitChosenFiles(
            listOf("/p/src/A.kt", "/p/shot.png", "/p/README.md", "/p/IMG.JPEG", "/p/noext"),
        )

        assertEquals(listOf("/p/shot.png", "/p/IMG.JPEG"), chosen.pictures)
        assertEquals(listOf("/p/src/A.kt", "/p/README.md", "/p/noext"), chosen.mentions)
    }

    @Test
    fun `各归各的，两边都空也不炸`() {
        val onlyPics = splitChosenFiles(listOf("/p/a.png"))
        assertEquals(listOf("/p/a.png"), onlyPics.pictures)
        assertTrue(onlyPics.mentions.isEmpty())

        val none = splitChosenFiles(emptyList())
        assertTrue(none.pictures.isEmpty())
        assertTrue(none.mentions.isEmpty())
    }

    @Test
    fun `判据与拖拽那条路是同一个 —— 认得出的扩展名一致`() {
        // 两处各写一套的话，拖进来的认、选进来的不认（或者反过来）
        for (name in listOf("a.png", "a.jpg", "a.jpeg", "a.gif", "a.webp", "a.bmp")) {
            assertEquals(listOf("/p/$name"), splitChosenFiles(listOf("/p/$name")).pictures, name)
        }
        for (name in listOf("a.psd", "a.pdf", "a.kt", "Dockerfile")) {
            assertEquals(listOf("/p/$name"), splitChosenFiles(listOf("/p/$name")).mentions, name)
        }
    }
}
