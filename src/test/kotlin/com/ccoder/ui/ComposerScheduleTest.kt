package com.ccoder.ui

import com.ccoder.text.CcoderText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.awt.Container
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.swing.JTextField

/**
 * 选时的几条纯函数。钉的是两件事：**认不认**（写法）与**收不收**（只收今天未来的）。
 *
 * 时间一律钉死在固定时区上（上海，无夏令时）—— 用系统时区的话，换个时区的机器
 * 上"跨零点"那条会各自算各自的，红起来全是噪音。
 *
 * 末了一条不是纯函数：输入框那一格的几何。外观问题本该靠探针出图看（见
 * [SchedulePickerRenderProbe]），但那张图 2026-10-13 就摆在眼前，"14:52" 被裁成
 * "14:!" 也没人看见 —— 所以这里补一条数字的，把"框要装得下自己的字"钉住。
 */
class ComposerScheduleTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int, day: Int = 13): ZonedDateTime =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)

    private fun millisOf(hour: Int, minute: Int, day: Int = 13): Long =
        at(hour, minute, day).toInstant().toEpochMilli()

    @Test
    fun `认 HH-MM 与 H-MM，前后空白也认`() {
        assertEquals(LocalTime.of(18, 0), parseClockTime("18:00"))
        assertEquals(LocalTime.of(9, 5), parseClockTime("9:05"))
        assertEquals(LocalTime.of(9, 5), parseClockTime("  09:05 "))
        assertEquals(LocalTime.of(0, 0), parseClockTime("00:00"))
    }

    @Test
    fun `认不出的写法一律 null —— 宁可不收，不许猜`() {
        val bad = listOf(
            "", "  ", "1800", "18.00", "18:0", "18：00",   // 全角冒号也是别的字符
            "24:00", "18:60", "-1:00", "8:5", "abc", "18:00:00", "1e2:00",
        )
        for (raw in bad) {
            assertNull(parseClockTime(raw), "\"$raw\" 不该被认成时刻")
        }
    }

    @Test
    fun `只收晚于现在的钟点 —— 已过与恰好现在都不收`() {
        val now = at(14, 22)
        assertEquals(millisOf(14, 23), resolveTodayFireAt(LocalTime.of(14, 23), now))
        assertNull(resolveTodayFireAt(LocalTime.of(14, 22), now), "恰好现在不算未来")
        assertNull(resolveTodayFireAt(LocalTime.of(9, 0), now), "上午已经过了")
        assertNull(resolveTodayFireAt(LocalTime.of(23, 59), at(23, 59)), "最后一分钟也按月/秒判")
    }

    @Test
    fun `今天与明天：跨零点才加明天，快捷行同一天只写钟点`() {
        val now = at(23, 30)
        val tonight = millisOf(23, 59)
        val tomorrow = millisOf(1, 20, day = 14)

        assertEquals(CcoderText.text("composer.schedule.today", "23:59"), scheduleTimeText(tonight, now))
        assertEquals(CcoderText.text("composer.schedule.tomorrow", "01:20"), scheduleTimeText(tomorrow, now))

        assertEquals("23:59", scheduleValueText(tonight, now), "同一天只写钟点")
        assertEquals(
            CcoderText.text("composer.schedule.tomorrow", "01:20"),
            scheduleValueText(tomorrow, now),
            "越过零点才加明天",
        )
    }

    @Test
    fun `钟点格式补零`() {
        assertEquals("09:05", clockText(millisOf(9, 5), zone))
        assertEquals("00:00", clockText(millisOf(0, 0, day = 14), zone))
    }

    @Test
    fun `判词四态：空着说回车、写错说样式、已过说换一个、合法给出时刻`() {
        val now = at(14, 22)

        val empty = clockHintOf("", now)
        assertFalse(empty.warn)
        assertNull(empty.fireAt)
        assertEquals(CcoderText.text("composer.schedule.enterHint"), empty.text)

        val badFormat = clockHintOf("1800", now)
        assertTrue(badFormat.warn)
        assertNull(badFormat.fireAt)
        assertEquals(CcoderText.text("composer.schedule.badFormat"), badFormat.text)

        val past = clockHintOf("14:05", now)
        assertTrue(past.warn)
        assertNull(past.fireAt)
        assertTrue(past.text.contains("14:05"), "判词里要写出那个已过的钟点：${past.text}")

        val ok = clockHintOf("18:00", now)
        assertFalse(ok.warn)
        assertEquals(millisOf(18, 0), ok.fireAt)
    }

    @Test
    fun `午夜边界：00-00 一过就是过去`() {
        // 同一天里的 00:00 永远不晚于现在（最早也就恰好等于）——两个边界都收不了
        assertNull(resolveTodayFireAt(LocalTime.of(0, 0), at(0, 0)), "恰好午夜不算未来")
        assertNull(resolveTodayFireAt(LocalTime.of(0, 0), at(0, 1)))
    }

    @Test
    fun `指定时刻的输入框：装得下 HH-MM 还要留出余量`() {
        // 5 列那次，首选宽 41px − 6px 边框内衬 = 35px 内容宽，恰好等于 "14:52"
        // 五个等宽字的字宽 —— 零余量，聚焦后光标把首字顶出可视区（用户见到的 ":08"）
        val now = at(14, 22)
        val picker = buildSchedulePicker(now, clockNow = { now }) {}
        val field = findTextField(picker) ?: fail("弹层里得有一个输入框")

        val fm = field.getFontMetrics(field.font)
        val textW = fm.stringWidth(field.text)
        val contentW = field.preferredSize.width - field.insets.left - field.insets.right
        assertTrue(
            contentW >= textW + fm.charWidth('0'),
            "内容宽 $contentW 至少要装下 \"${field.text}\"（${textW}px）再留一个字的余量",
        )
    }

    private fun findTextField(c: Container): JTextField? {
        for (child in c.components) {
            if (child is JTextField) return child
            if (child is Container) findTextField(child)?.let { return it }
        }
        return null
    }
}
