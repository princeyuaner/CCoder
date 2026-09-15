package com.ccoder.settings

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.AbstractButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import javax.swing.text.JTextComponent

/** 只应答 `isDisposed` 的 Project 替身（同探针那份；`DialogWrapper(project)` 会问它）。 */
private fun layoutProbeProject(): Project =
    Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isDisposed" -> false
            "toString" -> "layout probe"
            "hashCode" -> 0
            "equals" -> false
            else -> null
        }
    } as Project

private fun emptyStore(): SecretStore = object : SecretStore {
    override fun read(id: String): String = ""
    override fun write(id: String, secret: String) = Unit
}

/** 摆版。`invalidate()` 不能少 —— 理由见探针里同名函数。 */
private fun layoutAll(c: Container) {
    c.invalidate()
    c.doLayout()
    for (child in c.components) {
        if (child is Container) layoutAll(child)
    }
}

private fun buttonsIn(root: Container): List<AbstractButton> {
    val out = mutableListOf<AbstractButton>()
    fun walk(c: Container) {
        for (child in c.components) {
            if (child is AbstractButton) out += child
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out
}

private fun labelsIn(root: Container): List<String> {
    val out = mutableListOf<String>()
    fun walk(c: Container) {
        for (child in c.components) {
            if (child is JBLabel) out += child.text
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out
}

/**
 * 树里所有**看得见的文字**。
 *
 * 比 [labelsIn] 多收一样 `JTextArea` —— 折行的那几句说明（冲突警告、字段提示）
 * 都是 `wrappedHint` 产的，它们是 `JTextArea` 不是 `JBLabel`。
 * 只用 `labelsIn` 找那句话会永远找不到，然后看起来像"提示没出现"。
 */
private fun textsIn(root: Container): List<String> {
    val out = mutableListOf<String>()
    fun walk(c: Container) {
        when (c) {
            is JBLabel -> out += c.text
            is javax.swing.JTextArea -> out += c.text
        }
        for (child in c.components) {
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out
}

/** 树里所有文本等于 [text] 的标签，按出现顺序。两张表各有一个「＋ 添加一行」，得按顺序取。 */
private fun labelsTagged(root: Container, text: String): List<JBLabel> {
    val out = mutableListOf<JBLabel>()
    fun walk(c: Container) {
        for (child in c.components) {
            if (child is JBLabel && child.text == text) out += child
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out
}

private fun <T : java.awt.Component> findAll(root: Container, cls: Class<T>): List<T> {
    val out = mutableListOf<T>()
    fun walk(c: Container) {
        for (child in c.components) {
            if (cls.isInstance(child)) out += cls.cast(child)
            if (child is Container) walk(child)
        }
    }
    walk(root)
    return out
}

private fun clickOn(target: java.awt.Component) {
    val e = java.awt.event.MouseEvent(
        target, java.awt.event.MouseEvent.MOUSE_CLICKED,
        System.currentTimeMillis(), 0, 5, 5, 1, false,
    )
    target.mouseListeners.forEach { it.mouseClicked(e) }
}

/**
 * 外壳的**几何**与**按钮** —— 两条都是"量出来钉住"，不是风格检查。
 *
 * 为什么值得钉：宽度算错了界面只会悄悄变挤一点，**没有任何东西会红**
 * （2026-09-15 就踩过一次：表单栏压过列表栏 24px，把列表右边那列「使用中」盖住了，
 * 单测全绿）。而按钮错了的后果更重 —— 回车会把整个设置框关掉。
 */
class SettingsDialogLayoutTest {

    private fun open(): SettingsDialog {
        lateinit var dialog: SettingsDialog
        SwingUtilities.invokeAndWait {
            dialog = SettingsDialog(
                layoutProbeProject(),
                ClaudeSettings(),
                ModelProfiles(emptyStore()),
                PromptPresets(),
                McpStatus(),
            )
            val pane = dialog.contentPane ?: dialog.contentPanel
            pane.setSize(DIALOG_WIDTH, DIALOG_HEIGHT)
            layoutAll(pane)
        }
        return dialog
    }

    /** 当前这一页的两栏。模型页 = 列表 + 表单。 */
    private fun columns(dialog: SettingsDialog): List<JComponent> {
        val page = dialog.pageHost.components.filterIsInstance<JComponent>().single()
        return page.components.filterIsInstance<JComponent>()
    }

    @Test
    fun `页宿主拿到的宽度就是 PAGE_WIDTH 算出来的那个数`() {
        val dialog = open()

        // 红了 = 平台对话框内容区的边框变了（DIALOG_CONTENT_INSET 不再成立），
        // 或者页签栏宽度动了 —— 两种都会让下面两栏的分配跟着错
        assertEquals(
            PAGE_WIDTH,
            dialog.pageHost.width,
            "页宿主宽度对不上：对话框两侧的边框还是 ${DIALOG_CONTENT_INSET}px 吗",
        )
    }

    @Test
    fun `表单栏与列表栏各占自己的宽度，谁也不压谁`() {
        val cols = columns(open())

        assertEquals(2, cols.size, "模型页应当正好是列表 + 表单两栏")
        val form = cols.single { it.bounds.width == MODEL_FORM_WIDTH }
        val list = cols.single { it !== form }

        assertEquals(MODEL_LIST_WIDTH, list.width, "列表栏宽度")
        assertEquals(0, list.bounds.x, "列表栏该贴着左边")
        assertEquals(
            list.bounds.x + list.width,
            form.bounds.x,
            "两栏接不上或叠上了 —— 叠上的症状是列表右边的「使用中」被表单盖住",
        )
    }

    @Test
    fun `底部只有一颗「关闭」`() {
        val dialog = open()

        val south = dialog.contentPane.components.last() as Container
        val names = buttonsIn(south).map { it.text }

        assertTrue(
            names.any { it.contains("关闭") },
            "找不到「关闭」—— 它是唯一的出口，缺了就只能点右上角的 ✕。实际：$names",
        )
        assertEquals(1, names.size, "底部只该有一颗按钮（「取消」在这页上什么都不回滚）。实际：$names")
    }

    /**
     * 「关闭」**不能**是默认按钮。
     *
     * 给了 `DEFAULT_ACTION` 这个名字，平台就把 rootPane 的默认按钮指过去，
     * 于是**回车会关掉整个设置框** —— 而环境页的表格里回车是"提交这一格"，
     * 那一下会顺手把框关了。同 PermissionDialog 那条"回车不批准"。
     */
    @Test
    fun `「关闭」不是默认按钮 —— 回车不关框`() {
        assertNull(open().rootPane.defaultButton, "有默认按钮了：回车会关掉整个设置框")
    }

    @Test
    fun `页签栏里画着每一页的标题`() {
        val dialog = open()

        val drawn = labelsIn(dialog.contentPane)
        dialog.pages.forEach {
            assertTrue(drawn.contains(it.title), "页签栏里没有「${it.title}」。实际：$drawn")
        }
    }

    @Test
    fun `页签点一下能切页`() {
        val dialog = open()
        // 只有一页时"切页"没有意义（点当前页不该换东西）—— 四页签到齐后这条自动生效
        org.junit.jupiter.api.Assumptions.assumeTrue(dialog.pages.size >= 2, "还没到两页")
        val second = dialog.pages[1]
        val before = dialog.pageHost.components.singleOrNull()

        SwingUtilities.invokeAndWait { clickTab(dialog, second.title) }
        layoutAll(dialog.contentPane)

        val after = dialog.pageHost.components.singleOrNull()
        assertTrue(before !== after, "点了「${second.title}」页宿主里还是原来那一页")
        assertTrue(
            labelsIn(dialog.contentPane).isNotEmpty(),
            "切过去的页是空的",
        )
    }

}

/** 点页签。监听器挂在页签那个 `JBLabel` 上，所以直接喊它。 */
private fun clickTab(dialog: SettingsDialog, title: String) {
    val tab = findLabelDeep(dialog.contentPane, title) ?: error("页签栏里找不到「$title」")
    clickOn(tab)
}

private fun findLabelDeep(root: Container, text: String): JBLabel? {
    for (child in root.components) {
        if (child is JBLabel && child.text == text) return child
        if (child is Container) findLabelDeep(child, text)?.let { return it }
    }
    return null
}

/**
 * 三个新页（通用 / 权限 / 环境）各自写哪个字段。
 *
 * 这一批用例守的是**同一件事**：控件一变，服务里那个字段就变了。
 * 四页统一即时保存之后，"某页忘了接监听器"是唯一会静默失败的地方 ——
 * 界面上看不出任何异常，用户以为改了、实际没改。
 *
 * 为什么都是"从服务里读回来断言"而不是"断言控件状态"：真相在服务里，
 * 控件只是个视图；断言控件等于把视图当成事实，写反了也照样绿。
 */
class SettingsPagesTest {

    private fun open(settings: ClaudeSettings = ClaudeSettings()): Pair<SettingsDialog, ClaudeSettings> {
        lateinit var dialog: SettingsDialog
        SwingUtilities.invokeAndWait {
            dialog = SettingsDialog(
                layoutProbeProject(), settings, ModelProfiles(emptyStore()), PromptPresets(), McpStatus(),
            )
            layoutAll(dialog.contentPane)
        }
        return dialog to settings
    }

    /** 切到某一页，返回那一页的组件。 */
    private fun pageBody(dialog: SettingsDialog, title: String): Container {
        SwingUtilities.invokeAndWait { clickTab(dialog, title) }
        layoutAll(dialog.contentPane)
        return dialog.pageHost.components.filterIsInstance<JComponent>().single()
    }

    private fun fieldOf(body: Container, label: String): JComponent {
        val lab = findLabelDeep(body, label) ?: error("这一页没有「$label」")
        val panel = lab.parent as? Container ?: error("「$label」不在容器里")
        return panel.components.filterIsInstance<JComponent>().first { it !== lab }
    }

    private fun textOf(body: Container, label: String): JTextComponent =
        fieldOf(body, label) as? JTextComponent
            ?: findAll(fieldOf(body, label), JTextComponent::class.java).firstOrNull()
            ?: error("「$label」下面没有输入框")

    @Suppress("UNCHECKED_CAST")
    private fun comboOf(body: Container, label: String): JComboBox<Any> =
        fieldOf(body, label) as? JComboBox<Any> ?: error("「$label」下面不是下拉框")

    private fun checkboxOf(body: Container, text: String): JCheckBox =
        findAll(body, JCheckBox::class.java).firstOrNull { it.text == text }
            ?: error("找不到勾选框「$text」")

    private fun tablesOf(body: Container): List<JTable> = findAll(body, JTable::class.java)

    private fun setText(body: Container, label: String, value: String) {
        SwingUtilities.invokeAndWait { textOf(body, label).text = value }
    }

    private fun pick(body: Container, label: String, item: Any) {
        SwingUtilities.invokeAndWait { comboOf(body, label).selectedItem = item }
    }

    private fun typeInTable(table: JTable, row: Int, col: Int, value: String) {
        SwingUtilities.invokeAndWait { (table.model as DefaultTableModel).setValueAt(value, row, col) }
    }

    // ---- 打开一次设置，什么都别写 ----

    /**
     * **打开一次设置不该改任何东西。**
     *
     * 这条是"从攒着一起写改成写通"新引入的坑：`reload()` 要设 `selectedItem` 与
     * `text`，而它们都会触发监听器 —— 顺序反了（先挂监听器、后 reload 且没有
     * `loading` 挡着）就会把用户配置原样重写一遍。
     *
     * 检测手法：放进去的值**都是会被 trim 掉的**。真发生了写回，它们会变成
     * 没有空格的样子 —— 那正是 `save()` 干的事。比"断言没变"更锐利：
     * 写回同样的值本来也看不出来。
     */
    @Test
    fun `打开一次设置不写任何设置`() {
        val settings = ClaudeSettings().apply {
            claudePath = "  C:\\x\\claude.exe  "
            model = "  m-1  "
            extraDirs = mutableListOf("  D:\\dir  ")
            envOverrides = mutableMapOf("  KEY  " to "  VALUE  ")
        }

        open(settings)

        assertEquals("  C:\\x\\claude.exe  ", settings.claudePath, "打开设置把 claudePath 写回去了")
        assertEquals("  m-1  ", settings.model, "打开设置把 model 写回去了")
        assertEquals(listOf("  D:\\dir  "), settings.extraDirs, "打开设置把 extraDirs 写回去了")
        assertEquals(mapOf("  KEY  " to "  VALUE  "), settings.envOverrides, "打开设置把 envOverrides 写回去了")
    }

    // ---- 通用页 ----

    @Test
    fun `通用页改路径立刻落库`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "通用")

        setText(body, CLAUDE_PATH_LABEL, "C:\\bin\\claude.exe")

        assertEquals("C:\\bin\\claude.exe", settings.claudePath)
    }

    @Test
    fun `通用页改兜底模型立刻落库`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "通用")

        setText(body, FALLBACK_MODEL_LABEL, "claude-sonnet-5")

        assertEquals("claude-sonnet-5", settings.model)
    }

    @Test
    fun `通用页改发送快捷键立刻落库`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "通用")

        pick(body, SEND_SHORTCUT_LABEL, SendShortcut.CTRL_ENTER)

        assertEquals(SendShortcut.CTRL_ENTER, settings.sendShortcut)
    }

    /**
     * 发送快捷键那个下拉得**说人话**。
     *
     * `SendShortcut` 原来没有 `label` 也不覆写 `toString()`，下拉里显示的是
     * `ENTER` / `CTRL_ENTER` —— 用户要自己猜哪个键发送。
     */
    @Test
    fun `发送快捷键下拉显示的是人话`() {
        val (dialog, _) = open()
        val body = pageBody(dialog, "通用")

        val texts = SendShortcut.entries.map { it.toString() }

        assertTrue(texts.all { it.contains("发送") && it.contains("换行") }, "实际：$texts")
        assertTrue(comboOf(body, SEND_SHORTCUT_LABEL).itemCount == SendShortcut.entries.size)
    }

    @Test
    fun `通用页改思考深度立刻落库`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "通用")

        pick(body, EFFORT_LABEL, EffortSetting.HIGH)

        assertEquals(EffortSetting.HIGH, settings.effort)
    }

    // ---- 权限页 ----

    @Test
    fun `权限页改模式立刻落库`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "权限")

        pick(body, PERMISSION_MODE_LABEL, PermissionModeSetting.ACCEPT_EDITS)

        assertEquals(PermissionModeSetting.ACCEPT_EDITS, settings.permissionMode)
    }

    /**
     * 没勾确认就降级 —— 那个复选框**真的参与决定**，不是安慰剂。
     *
     * 而"下拉留在绕过上"是刻意的：**把它拉回标准就等于把复选框也一起藏了**
     * （复选框只在绕过下出现），用户再没有地方勾确认，绕过权限永远选不上。
     * 代价由下面那条说明承担 —— 它明说此刻会按哪个跑。
     */
    @Test
    fun `没勾危险确认时落库的是标准，界面说清会按标准跑`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "权限")

        pick(body, PERMISSION_MODE_LABEL, PermissionModeSetting.BYPASS_PERMISSIONS)

        assertEquals(PermissionModeSetting.DEFAULT, settings.permissionMode, "没勾确认却落库了绕过")
        assertTrue(
            textsIn(body).any { it.contains("还没确认") && it.contains(PermissionModeSetting.DEFAULT.label) },
            "没人告诉用户现在实际按哪个跑。实际：${textsIn(body)}",
        )
    }

    @Test
    fun `勾了确认才落 bypass`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "权限")
        pick(body, PERMISSION_MODE_LABEL, PermissionModeSetting.BYPASS_PERMISSIONS)

        // 用 `doClick()` 而不是 `isSelected = true`：后者只动模型、**不发 ActionEvent**
        // （那正是监听器在等的东西），于是这条用例会红得让你以为接线断了
        SwingUtilities.invokeAndWait { checkboxOf(body, DANGEROUS_OPT_IN_LABEL).doClick() }

        assertEquals(PermissionModeSetting.BYPASS_PERMISSIONS, settings.permissionMode)
    }

    @Test
    fun `危险确认框只在需要它的模式上出现`() {
        val (dialog, _) = open()
        val body = pageBody(dialog, "权限")
        val box = checkboxOf(body, DANGEROUS_OPT_IN_LABEL)

        assertFalse(box.isVisible, "「标准」模式下不该显示风险确认")

        pick(body, PERMISSION_MODE_LABEL, PermissionModeSetting.BYPASS_PERMISSIONS)

        assertTrue(box.isVisible, "选了绕过却不给确认框")
    }

    /** 存进 `State` 的必须是**枚举名**。写成 `wireValue` 的话重启后模式静默回默认。 */
    @Test
    fun `权限模式存的是枚举名不是 wireValue`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "权限")

        pick(body, PERMISSION_MODE_LABEL, PermissionModeSetting.ACCEPT_EDITS)

        assertEquals("ACCEPT_EDITS", settings.state.permissionMode)
    }

    @Test
    fun `思考深度存的是枚举名不是 wireValue`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "通用")

        pick(body, EFFORT_LABEL, EffortSetting.XHIGH)

        assertEquals("XHIGH", settings.state.effort)
    }

    // ---- 环境页 ----

    @Test
    fun `环境页加一行额外目录立刻落库`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "环境")
        val dirs = tablesOf(body).first()

        clickOn(labelsTagged(body, ADD_ROW_LABEL).first())
        typeInTable(dirs, 0, 0, "D:\\shared")

        assertEquals(listOf("D:\\shared"), settings.extraDirs)
    }

    @Test
    fun `环境页改一格环境变量立刻落库`() {
        val (dialog, settings) = open()
        val body = pageBody(dialog, "环境")
        val vars = tablesOf(body)[1]

        clickOn(labelsTagged(body, ADD_ROW_LABEL)[1])
        typeInTable(vars, 0, 0, "MY_VAR")
        typeInTable(vars, 0, 1, "my-value")

        assertEquals(mapOf("MY_VAR" to "my-value"), settings.envOverrides)
    }

    @Test
    fun `环境页删一行立刻落库`() {
        val settings = ClaudeSettings().apply { extraDirs = mutableListOf("D:\\a", "D:\\b") }
        val (dialog, _) = open(settings)
        val body = pageBody(dialog, "环境")
        val dirs = tablesOf(body).first()

        SwingUtilities.invokeAndWait {
            dirs.setRowSelectionInterval(0, 0)
            clickOn(labelsTagged(body, REMOVE_ROW_LABEL).first())
        }

        assertEquals(listOf("D:\\b"), settings.extraDirs)
    }

    /**
     * 环境页加了那把会被覆盖的键，**模型页那条警告要跟着出现**。
     *
     * 不重算的话用户得关掉重开才看得见 —— 看起来就像那个提示坏了
     * （它本来就是 spec §6 要求常驻的那一条）。
     */
    @Test
    fun `环境页改了键，模型页那条冲突警告跟着出现`() {
        val (dialog, _) = open()
        val env = pageBody(dialog, "环境")
        val vars = tablesOf(env)[1]

        assertFalse(
            textsIn(dialog.contentPane).any { it.contains("会被选中的模型配置覆盖") },
            "还没加键就冒出了冲突警告",
        )

        clickOn(labelsTagged(env, ADD_ROW_LABEL)[1])
        typeInTable(vars, 0, 0, "ANTHROPIC_BASE_URL")
        typeInTable(vars, 0, 1, "https://old.example.com")

        val model = pageBody(dialog, "模型")
        assertTrue(
            textsIn(model).any { it.contains("会被选中的模型配置覆盖") },
            "环境页加了键，模型页却没有警告条。实际：${textsIn(model)}",
        )
    }

    // ---- 读表的规则（纯函数，直接打） ----

    @Test
    fun `读表时空白行丢掉、值两边去空格`() {
        val m = DefaultTableModel(arrayOf("目录"), 0)
        m.addRow(arrayOf<Any>("  D:\\a  "))
        m.addRow(arrayOf<Any>(""))
        m.addRow(arrayOf<Any>("   "))
        m.addRow(arrayOf<Any>("D:\\b"))

        assertEquals(listOf("D:\\a", "D:\\b"), readColumn(m, 0))
    }

    @Test
    fun `读键值对时键为空的那一行丢掉`() {
        val m = DefaultTableModel(arrayOf("名", "值"), 0)
        m.addRow(arrayOf<Any>("K1", " v1 "))
        m.addRow(arrayOf<Any>("", "没人要的值"))
        m.addRow(arrayOf<Any>("K2", ""))

        // 半填的一行不该变成一个空变量名；值可以为空（那是有意义的）
        assertEquals(mapOf("K1" to "v1", "K2" to ""), readPairs(m))
    }
}
