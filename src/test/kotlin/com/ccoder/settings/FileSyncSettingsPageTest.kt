package com.ccoder.settings

import com.ccoder.sync.SyncConfig
import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncSettings
import com.ccoder.sync.SyncSnapshot
import com.ccoder.sync.SyncStatus
import com.ccoder.text.CcoderText
import com.intellij.ui.components.JBTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.JTextField

/**
 * 同步设置页（2026-09-24）。
 *
 * 这一页几乎全是"改动即写"，所以用例钉的是**写没写进去**、以及两处**静默事故**：
 *
 * 1. **打开对话框把排除项清空**：`reload()` 灌值那一刻本地副本还是空的，没有
 *    `loading` 那道闸，控件的变更监听器就会把空表写回服务 —— 用户看到的只是
 *    "我的排除项不见了"，而且下一次打开还是空的
 * 2. **半填的东西落库**：静默期框里把数字全选删掉的那一瞬间，`toLongOrNull()` 是 null。
 *    不判它就会写进一个 0，而 0 在 `normalizeConfig` 里会被夹成下限 100 毫秒 ——
 *    表现是"我还没改完它自己变了"
 *
 * 定位控件走共用的 [FieldLookup]（按标签找），所以那两个复选框**不带自己的文字**：
 * 文字在它们各自那一行的标签上（见页里的注释）。
 */
class FileSyncSettingsPageTest {

    private fun label(key: String, vararg args: Any): String = CcoderText.text(key, *args)

    private fun open(seed: SyncSettings = SyncSettings()): Pair<FileSyncSettingsPage, SyncSettings> {
        val page = FileSyncSettingsPage(null, seed, SyncStatus())
        val comp = page.component()
        comp.setSize(SYNC_FORM_WIDTH + SYNC_LIST_WIDTH, 700)
        layoutAll(comp)
        return page to seed
    }

    // ---------------------------------------------------------------- 字段在不在

    @Test
    fun `那几个字段都在`() {
        val (page, _) = open()
        val root = page.component()

        listOf("sync.page.enable", "sync.page.src", "sync.page.dst", "sync.page.roots", "sync.page.settle", "sync.page.sweep", "sync.page.deleteMissing")
            .forEach { key -> assertNotNull(findLabel(root, label(key)), "少了一个字段：$key") }
    }

    @Test
    fun `列表栏那三张卡在`() {
        val (page, _) = open()
        val root = page.component()

        listOf("sync.page.statusCard", "sync.page.excludeCard", "sync.page.logCard")
            .forEach { key -> assertNotNull(findLabel(root, label(key)), "少了一张卡：$key") }
    }

    // ---------------------------------------------------------------- 状态卡那两行值

    @Test
    fun `没配置时说出缺什么 —— 那句话必须在树上`() {
        val (page, _) = open()

        assertNotNull(
            findLabel(page.component(), label("sync.problem.disabled")),
            "「未配置」旁边那句解释曾经**建了却没加进布局**（出图时才发现它一次都没显示过）",
        )
    }

    @Test
    fun `在跑的时候不显示「打开开关」那句`() {
        // `problem` 在跑的时候是 null，而映射把 null 当成 DISABLED —— 不特判就会在
        // 「运行中」底下写着「打开开关并选好两端目录」
        val page = FileSyncSettingsPage(
            null,
            SyncSettings(),
            SyncStatus().apply { set(SyncSnapshot(run = SyncRun.RUNNING, lastRoundAtMs = 1L)) },
        )
        val comp = page.component()
        layoutAll(comp)

        assertNull(findLabel(comp, label("sync.problem.disabled")))
    }

    @Test
    fun `空日志给一句话，不留空框`() {
        // 日志框是 `JTextArea`，不是 JLabel —— 所以按文本找它得遍历多行框，
        // 不能走 `findLabel`（那本书只认 JLabel）
        val (page, _) = open()
        val areas = mutableListOf<JBTextArea>()
        fun collect(c: Container) {
            for (child in c.components) {
                if (child is JBTextArea) areas += child
                if (child is Container) collect(child)
            }
        }
        collect(page.component())

        assertTrue(
            areas.any { it.text == label("sync.page.logEmpty") },
            "框里没字时要写一句「暂无」—— 空框会让用户分不清是「还没有」还是「坏了」",
        )
    }

    // ---------------------------------------------------------------- 改动即写

    @Test
    fun `勾上开关就写进配置 —— 没有保存按钮`() {
        val (page, settings) = open()

        (inputOf(page.component(), label("sync.page.enable")) as JCheckBox).doClick()

        assertTrue(settings.config.enabled)
    }

    @Test
    fun `改本地目录就写进去`() {
        val (page, settings) = open()

        (inputOf(page.component(), label("sync.page.src")) as JTextField).text = "C:\\M71\\server"

        assertEquals("C:\\M71\\server", settings.config.src)
    }

    @Test
    fun `同步范围按逗号切 —— 顺手去掉空白项`() {
        val (page, settings) = open()

        (inputOf(page.component(), label("sync.page.roots")) as JTextField).text = " trunk , config ,, "

        assertEquals(listOf("trunk", "config"), settings.config.syncRoots)
    }

    @Test
    fun `静默期框里打了半个字符也不落库`() {
        val (page, settings) = open()
        val before = settings.config.settleMs

        (inputOf(page.component(), label("sync.page.settle")) as JTextField).text = ""

        assertEquals(before, settings.config.settleMs, "半填落库的话，用户还没打完它自己就变了")
    }

    // ---------------------------------------------------------------- 两处静默事故

    @Test
    fun `打开对话框不会把排除项清空`() {
        val seeded = SyncSettings().apply {
            update(SyncConfig(enabled = true, src = "C:\\a", dst = "Z:\\b", exclude = listOf("temp", "*.log")))
        }

        open(seeded)   // component() → reload()

        assertEquals(
            listOf("temp", "*.log"),
            seeded.config.exclude,
            "reload 灌值那一刻本地副本还是空的 —— 没有 loading 那道闸，打开一次就清一次",
        )
    }

    @Test
    fun `打开对话框也不会把时长改掉`() {
        val seeded = SyncSettings().apply {
            update(SyncConfig(enabled = true, src = "C:\\a", dst = "Z:\\b", settleMs = 4_321))
        }

        open(seeded)

        assertEquals(4_321L, seeded.config.settleMs)
    }

    // ---------------------------------------------------------------- 排除项列表

    @Test
    fun `加一行先不落库 —— 空行只在界面上活着`() {
        val (page, settings) = open()
        val root = page.component()

        clickLabel(root, label("sync.page.addExclude"))

        assertTrue(
            settings.config.exclude.isEmpty(),
            "空行落库的话，读回来时会被丢掉 —— 用户会以为「我加的没了」",
        )
    }

    @Test
    fun `填上那一行才落库`() {
        val (page, settings) = open()
        val root = page.component()
        clickLabel(root, label("sync.page.addExclude"))
        layoutAll(root)

        excludeFields(root).single().text = "temp"

        assertEquals(listOf("temp"), settings.config.exclude)
    }

    @Test
    fun `删一行会落库`() {
        val seeded = SyncSettings().apply {
            update(SyncConfig(enabled = true, src = "C:\\a", dst = "Z:\\b", exclude = listOf("temp")))
        }
        val (page, settings) = open(seeded)
        val root = page.component()
        assertEquals(1, excludeFields(root).size)

        clickLabel(root, DELETE_LABEL)
        layoutAll(root)

        assertTrue(settings.config.exclude.isEmpty())
        assertTrue(excludeFields(root).isEmpty(), "界面上那一行也要跟着消失")
    }
}

/** 摆版。`invalidate()` 不能少 —— 理由见探针里同名函数。 */
private fun layoutAll(c: Container) {
    c.invalidate()
    c.doLayout()
    for (child in c.components) {
        if (child is Container) layoutAll(child)
    }
}

/**
 * 点一个 `actionLabel`。
 *
 * 它就是"灰字可点"的那种标签，所以把点击事件直接喂给它身上的监听器 ——
 * 与页签那两处（`SettingsDialogTest.clickTab` / 探针）同一个做法。
 */
private fun clickLabel(root: Container, text: String) {
    val lab = findLabel(root, text) ?: error("找不到「$text」")
    val event = MouseEvent(lab, MouseEvent.MOUSE_CLICKED, 0L, 0, 0, 0, 1, false, MouseEvent.BUTTON1)
    assertTrue(lab.mouseListeners.isNotEmpty(), "「$text」上没有监听器 —— 那它点了没反应")
    lab.mouseListeners.forEach { it.mouseClicked(event) }
}

/**
 * 排除项列表里那几行的输入框。
 *
 * **做法是"整页的输入框减去表单那五个"**，不是"在排除项那张卡的子树里找" ——
 * 后者按卡片标题往上找一层拿到的是**标题栏**，而输入框在兄弟的正文里，一个都摸不到
 * （用例里踩过：`List is empty`，而报错的地方离病因很远）。
 *
 * 表单那五个按标签取，取得很死（`FieldLookup` 保证的）。
 */
private fun excludeFields(root: Container): List<JTextField> {
    val formFields = listOf(
        "sync.page.src", "sync.page.dst", "sync.page.roots", "sync.page.settle", "sync.page.sweep",
    ).map { inputOf(root, CcoderText.text(it)) }.toSet()

    val out = mutableListOf<JTextField>()
    fun collect(c: Container) {
        for (child in c.components) {
            if (child is JTextField) out += child
            if (child is Container) collect(child)
        }
    }
    collect(root)
    return out.filter { it !in formFields }
}
