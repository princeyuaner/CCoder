package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposerAttachmentsTest {

    @Test
    fun `超限时拼出提示行`() {
        val intake = ImageIntake(emptyList(), 2, "单张超过 5MB")
        assertEquals("已跳过 2 张：单张超过 5MB", attachmentNotice(intake))
    }

    @Test
    fun `没被拒就没有提示`() {
        assertNull(attachmentNotice(ImageIntake(emptyList(), 0, null)))
    }

    @Test
    fun `缩略图等比缩到框内`() {
        // 竖图按高度顶满，宽度按比例 —— 不拉伸变形
        assertEquals(32 to 64, thumbSize(1000, 2000, 64).let { it.width to it.height })
        assertEquals(64 to 32, thumbSize(2000, 1000, 64).let { it.width to it.height })
    }

    @Test
    fun `正方形顶满框`() {
        assertEquals(64 to 64, thumbSize(500, 500, 64).let { it.width to it.height })
    }

    @Test
    fun `退化尺寸不炸`() {
        assertEquals(1 to 1, thumbSize(0, 0, 64).let { it.width to it.height })
    }

    @Test
    fun `无图且无提示时整条隐藏 —— 否则输入区永远多一条空白`() {
        val strip = ComposerAttachments(onRemove = {})

        assertFalse(strip.isVisible, "刚构造就该是隐藏的")
        strip.setImages(listOf(ImageAttachment("image/png", "AAAA")))
        assertTrue(strip.isVisible, "有图就该出现")
        strip.setImages(emptyList())
        assertFalse(strip.isVisible, "清空后重新隐藏")
        strip.setNotice("已跳过 1 张：单张超过 5MB")
        assertTrue(strip.isVisible, "只有提示时也要显示（否则用户看不到拒绝）")
        strip.clear()
        assertFalse(strip.isVisible, "clear 之后回到隐藏")
    }

    @Test
    fun `换一批图会清掉上一次的提示`() {
        val strip = ComposerAttachments(onRemove = {})
        strip.setNotice("已跳过 2 张：单张超过 5MB")
        // 先证明 getter 读得到东西，否则下面那句 assertNull 靠一个永远返回 null 的实现也能过
        assertNotNull(strip.noticeText())

        strip.setImages(listOf(ImageAttachment("image/png", "AAAA")))

        // 只断言 isVisible 是不够的：有图时它本来就是 true，提示还在也照样通过
        assertNull(strip.noticeText())
    }
}
