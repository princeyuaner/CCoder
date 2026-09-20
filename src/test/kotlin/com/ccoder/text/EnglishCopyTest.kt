package com.ccoder.text

import com.ccoder.settings.PermissionModeSetting
import com.ccoder.ui.ConnectionState
import com.ccoder.ui.failureHintText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * 英文那份的**定点金标**：钉的是语义，不是排版。
 *
 * 为什么需要它：整套用例钉的是中文（`ccoder.lang=zh`，文案在这个仓库是产品），
 * 于是英文那份的覆盖只剩下"结构"（键对齐、无 CJK、占位符连续）——
 * 那几样拦得住"漏了一条"，拦不住"这句英文把状态说错了"。而这个仓库最不能忍的
 * 就是界面撒谎，所以把最容易撒谎的几处单独钉住。
 *
 * 手法：`CcoderText.setOverride(Locale.ENGLISH)`，与设置层用的是同一条路。
 */
class EnglishCopyTest {

    @AfterEach
    fun reset() {
        CcoderText.setOverride(null)
    }

    private fun inEnglish() = CcoderText.setOverride(Locale.ENGLISH)

    // ---- 处置正文里的可操作记号：用户要拿它们去搜、去改 ----

    @Test
    fun `认证那段的四个记号逐字保留`() {
        inEnglish()
        val text = failureHintText("AUTH_FAILED", "authentication_failed")

        for (token in listOf(
            "~/.claude/settings.json",
            "ANTHROPIC_AUTH_TOKEN",
            "ANTHROPIC_BASE_URL",
            "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST",
        )) {
            assertTrue(text.contains(token), "英文那段丢了记号「$token」：$text")
        }
    }

    @Test
    fun `绕过权限那段要说出被什么禁用了`() {
        inEnglish()
        val text = failureHintText("SET_MODE_FAILED", "boom")

        assertTrue(text.contains("permissions.disableBypassPermissionsMode"), "丢了设置项名：$text")
        // 模式名必须与权限页那个下拉里显示的一模一样 —— 用户在设置里找的是那个词。
        // 抄一份到句子里就迟早会漂移，所以这条比的是**枚举取出来的那个值**。
        assertTrue(
            text.contains(PermissionModeSetting.BYPASS_PERMISSIONS.label),
            "句子里引的模式名与下拉里的对不上：$text",
        )
    }

    @Test
    fun `找不到 claude 那段指的页面与页签真名一致`() {
        inEnglish()
        val text = failureHintText("CLAUDE_NOT_FOUND", "not found")

        // 页签名取自词表本身：哪天有人改了页签，这条会红 —— 而不是让用户按图索骥找不到
        val page = CcoderText.text("settings.page.environment")
        val block = CcoderText.text("settings.deps.title")
        assertTrue(text.contains(page), "没指到「$page」页：$text")
        assertTrue(text.contains(block), "没指到「$block」那一块：$text")

        // 注：这一段的后半句（中英都一样）说"也可以在那里手填 claude 可执行文件的完整路径"——
        // **那句话说错了地方**：那个路径栏在「通用」页，不在运行依赖那块
        // （RuntimeDepsService 自己的处置段落写的就是「设置 → 通用 → claude 可执行文件」）。
        // 这是翻译之前就有的指错路，本次刻意**不**在 i18n 的 diff 里顺手改中文，
        // 另开一条修（同「已结束」色调那条的处理）。修的时候这条用例要跟着加一句。
    }

    // ---- 名字必须分得开 ----

    @Test
    fun `六种权限模式的英文名两两不同`() {
        inEnglish()
        val labels = PermissionModeSetting.entries.map { it.label }

        assertEquals(labels.size, labels.toSet().size, "有两个模式叫同一个名字：$labels")
        for (label in labels) {
            assertFalse(label.startsWith("settings."), "有个模式的名字是裸键：$label")
            assertTrue(label.isNotBlank())
        }
    }

    @Test
    fun `四种危险或失败的连接状态英文名分得开`() {
        inEnglish()
        val failed = listOf(
            ConnectionState.RestoreFailed,
            ConnectionState.StartFailed,
            ConnectionState.Disconnected,
            ConnectionState.Ended,
        ).map { it.text() }

        assertEquals(failed.size, failed.toSet().size, "两个失败状态同名，用户分不清：$failed")
    }

    @Test
    fun `动作词不与连接状态撞词`() {
        inEnglish()
        // 同一格会显示这两族词（空闲时是连接状态、忙时是动作词）—— 撞词会让"在跑"与"断了"看起来一样
        val states = ConnectionState.entries.map { it.text() }.toSet()
        val activities = com.ccoder.ui.Activity.entries.map { it.text() }

        assertEquals(emptyList<String>(), activities.filter { it in states }, "这两族里有同名项")
    }
}
