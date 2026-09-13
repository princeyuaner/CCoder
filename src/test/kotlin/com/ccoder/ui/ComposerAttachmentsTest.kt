package com.ccoder.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
