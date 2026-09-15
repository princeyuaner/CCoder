package com.ccoder.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.JComponent

/** 「权限模式」那一栏的标签。用例要按它找控件。 */
internal const val PERMISSION_MODE_LABEL = "权限模式"

/**
 * 「我明白风险」那句话。
 *
 * 与 [PermissionModeSetting.BYPASS_PERMISSIONS] 的 `requiresDangerousOptIn` 配对：
 * 勾了它 `bypassPermissions` 才真的落库，不勾就降级成 [PermissionModeSetting.DEFAULT]。
 *
 * 不再复述"所有操作都不再询问" —— 那句话就写在这个框**正上方**
 * （模式说明跟着下拉走），同一屏里说两遍反而显得像两个不同的警告。
 */
internal const val DANGEROUS_OPT_IN_LABEL = "我明白风险，仍要使用「绕过权限」"

/**
 * 权限页：权限模式 + 「我明白风险」。
 *
 * ## 那个复选框**真的参与决定**
 *
 * 它曾经是个摆设：`apply()` 从头到尾没读过 `isSelected`，勾不勾都照写
 * `bypassPermissions`。今天由 [effectivePermissionMode] 管 —— 那是唯一一处
 * 判断"降不降级"的地方，界面只是它的调用方。
 *
 * ## 落库的永远是**降级后**的值
 *
 * 于是"显示着绕过、实际是标准"这种错位不可能出现。`save()` 在降级发生时
 * 把下拉也拉回来，界面与真相始终一致（这条有测试钉着）。
 *
 * ## 改了只影响**下次**开会话
 *
 * 这一页写的是设置里的默认值。正在跑的那个会话要跟着变，由 `ClaudePanel`
 * 在对话框关掉之后对账（见 `ui/SettingsApply.kt`）—— 热切那条路只有那一处出口。
 */
internal class PermissionSettingsPage(private val settings: ClaudeSettings) : SettingsPage {

    override val title: String = "权限"

    private val modeBox = ComboBox(PermissionModeSetting.entries.toTypedArray())
    private val dangerousOptIn = JBCheckBox(DANGEROUS_OPT_IN_LABEL)

    /** 跟着下拉走的那句说明。与输入框左下角那个弹层是**同一份文本**（枚举上的）。 */
    private val modeHint = JBLabel().apply {
        foreground = UIUtil.getInactiveTextColor()
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(2)
    }

    private var built: JComponent? = null

    /** reload 期间不写回。理由见 [GeneralSettingsPage.save]。 */
    private var loading = false

    override fun component(): JComponent = built ?: build().also { built = it }

    private fun build(): JComponent {
        modeBox.addActionListener { save() }
        dangerousOptIn.addActionListener { save() }

        val column = settingsColumn().apply {
            add(labeledField(PERMISSION_MODE_LABEL, modeBox))
            add(modeHint)
            // 复选框自己是一条，不套 labeledField —— 它没有"上面的标签"，
            // 于是也拿不到 labeledField 底下那 10px 的间距，得自己留出下面一格
            dangerousOptIn.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(10, 0, 13, 0)
            }
            add(dangerousOptIn)
            add(
                wrappedHint(
                    "这一项改了只影响下次建立会话；输入框左下角那个标签能随时改，" +
                        "那边改了对正在跑的会话立刻生效",
                    PAGE_CONTENT_WIDTH,
                )
            )
        }
        return settingsPageBody(column).also { reload() }
    }

    override fun reload() {
        loading = true
        try {
            modeBox.selectedItem = settings.permissionMode
            // 存着绕过模式就说明当初确认过（没勾的话 save() 会把它降级掉），
            // 所以复位时把勾也还原上
            dangerousOptIn.isSelected = settings.permissionMode.requiresDangerousOptIn
        } finally {
            loading = false
        }
        refresh()
    }

    /**
     * 写回**降级后**的值 —— 界面显示什么、`effectivePermissionMode` 说了算。
     *
     * ## 这里踩过一跤，别再改回去
     *
     * 第一版写的是"被降级就把下拉也拉回标准"（那是旧 IDE 那页的做法，它攒到
     * 「应用」才降级，所以没问题）。**改成即时保存之后那条路是死的**：
     * 选了「绕过权限」→ 立刻降级 → 下拉回到标准 → 复选框跟着消失 →
     * 用户**再没有任何地方可以勾确认**，绕过权限永远选不上。
     *
     * 所以现在的规矩是：**下拉留着用户选的那一项**（他要的就是它），而把
     * "现在实际算哪个"交给下面那行说明去说 —— 见 [refresh]。
     */
    private fun save() {
        if (loading) return
        val picked = modeBox.selectedItem as PermissionModeSetting
        settings.permissionMode = effectivePermissionMode(picked, dangerousOptIn.isSelected)
        refresh()
    }

    /**
     * 一处刷新两样东西：那句说明，以及那个复选框。
     *
     * - **复选框**在"选了绕过"或"实际就是绕过"时都在 —— 少了前者，
     *   用户就没有勾确认的地方（见 [save] 上那一跤）。
     * - **说明**顺便承担"现在到底算哪个"：两者不一致时它明说会按哪个跑。
     *   没有这一句就是"控件撒谎"，有它就是"控件在说人话"。
     */
    private fun refresh() {
        val picked = modeBox.selectedItem as? PermissionModeSetting ?: return
        val effective = effectivePermissionMode(picked, dangerousOptIn.isSelected)

        dangerousOptIn.isVisible = picked.requiresDangerousOptIn || effective.requiresDangerousOptIn
        modeHint.text = if (effective != picked) {
            "还没确认 —— 实际会按「${effective.label}」运行"
        } else {
            picked.description
        }
    }
}
