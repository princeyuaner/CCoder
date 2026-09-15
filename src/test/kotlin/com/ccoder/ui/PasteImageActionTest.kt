package com.ccoder.ui

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ctrl+V 贴图那个动作（见 [PasteImageAction]）。
 *
 * 要钉住的其实只有一件事：**它只在"焦点在输入框、剪贴板里只有图"时出现**。
 * 那条是安全绳 —— 无脑抢 Ctrl+V 会让"粘代码"变成"贴图"，而那是用户每天用
 * 几十次的操作。
 *
 * `AnActionEvent` 要 Application（单测里没有），所以规则抽成了纯函数
 * [shouldOfferPasteImage]；动作的 `update` 只是把它接到 presentation 上。
 */
class PasteImageActionTest {

    private fun contextWithAttach(attach: (() -> Unit)?): DataContext =
        DataContext { dataId -> if (IMAGE_PASTE_ATTACH.`is`(dataId)) attach else null }

    @Test
    fun `焦点在输入框 + 剪贴板里只有图 → 才出现`() {
        assertTrue(shouldOfferPasteImage(hasAttachTarget = true, hasImageOnClipboard = true))
    }

    @Test
    fun `剪贴板里不是图 → 不出现，Ctrl+V 归平台`() {
        // 用户粘代码时，这个动作绝不能是候选
        assertFalse(shouldOfferPasteImage(hasAttachTarget = true, hasImageOnClipboard = false))
    }

    @Test
    fun `焦点不在输入框 → 不出现`() {
        // 转写区（JCEF）、编辑器、终端里按 Ctrl+V 都落在这条上
        assertFalse(shouldOfferPasteImage(hasAttachTarget = false, hasImageOnClipboard = true))
        assertFalse(shouldOfferPasteImage(hasAttachTarget = false, hasImageOnClipboard = false))
    }

    @Test
    fun `promoter 只在自己的上下文里插手`() {
        // 同一条快捷键上平台也有一个（$Paste）：不把顺序改对，按下去轮到谁听天由命
        val ours = PasteImageAction { true }
        val other = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        val promoter: ActionPromoter = ImagePastePromoter()

        assertEquals(
            listOf(ours),
            promoter.promote(listOf(other, ours), contextWithAttach {}),
            "焦点在输入框时该把我们排前面",
        )
        assertEquals(
            emptyList<AnAction>(),
            promoter.promote(listOf(other, ours), contextWithAttach(null)),
            "别的地方一个都不许动",
        )
    }
}
