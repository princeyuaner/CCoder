package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import com.ccoder.text.CcoderText

/**
 * 非模态权限卡片。
 *
 * 落实 spec §6.2 的三条 SDK 明文规则：
 *
 * 规则②（sdk.d.ts:263-267）：不能被误触批准。批准只能显式点击按钮，
 * **不绑任何键盘快捷键**（按钮刻意不设 mnemonic —— 助记符就是键盘捷径）；
 * 卡片获得焦点时焦点落在"拒绝"上。
 *
 * 规则③（sdk.d.ts:268-272）：那个"不再询问"的入口仅在
 * [PermissionOptions.allowsAlwaysAllow] 为真时**渲染**（不是渲染后禁用）。
 *
 * 规则①由调用方保证 —— 任何终止路径都要 resolve，见 [PermissionQueue.cancelAll]。
 *
 * 按钮写的是「本会话不再询问」，回传的也就必须只是本会话的东西（[PermissionDecision.stopAsking]）
 * —— 按钮上写的话和它真正授权的范围不一致，比按钮不好用严重得多。
 */
class PermissionCard(
    private val permission: SidecarMessage.Permission,
    queuedCount: Int,
    private val onDecide: (PermissionDecision) -> Unit,
) : JPanel(BorderLayout()) {

    /** 对话框拿它当首选焦点组件（规则②）。 */
    internal val denyButton = JButton(CcoderText.text("permission.deny")).apply {
        // 不设 mnemonic：助记符等于键盘捷径，违反规则②
        addActionListener { onDecide(deniedByUser()) }
    }

    private val allowButton = JButton(PermissionOptions.allowLabel(permission)).apply {
        addActionListener {
            onDecide(PermissionDecision(allow = true, updatedPermissions = null, message = null))
        }
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT, 1),
            JBUI.Borders.empty(8),
        )
        // 与 AskQuestionCard 同一处改动（2026-09-15）：去掉那层琥珀底，只留描边，
        // 并且是"透出父容器"而不是"铺面板色"。两张卡是一套视觉语言，改一张留一张
        // 会更怪。
        isOpaque = false

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(PermissionOptions.primaryText(permission)).apply {
                font = font.deriveFont(font.style or Font.BOLD)
                alignmentX = LEFT_ALIGNMENT
            })
            permission.description?.takeIf { it.isNotBlank() }?.let { text ->
                add(JBLabel(text).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            // blockedPath 单独高亮 —— 这是"为什么问我"的关键信息（spec §6.5）
            permission.blockedPath?.let { path ->
                add(JBLabel(CcoderText.text("permission.triggerPath", path)).apply {
                    foreground = WARN
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            permission.decisionReason?.let { reason ->
                add(JBLabel(CcoderText.text("permission.reason", reason)).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            if (queuedCount > 0) {
                add(JBLabel(CcoderText.text("permission.queued", queuedCount)).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
        }

        // 入参按"能读"的样子铺开：长正文（比如 ExitPlanMode 那份计划）走文本，
        // 短入参走缩进 JSON —— 见 permissionBody 里那段"看不到内容的审批不是审批"
        val body = permissionBody(permission.toolName, permission.input)
        // 三条路，按"这份内容最该被怎么读"分：
        //  - 改动预览（Edit/Write/MultiEdit）：一张带底色的删/加表，HTML
        //  - 计划：Markdown → HTML（纯文本组件只会把 `#`、`**` 原样铺出来 ——
        //    那不是排版朴素，是在显示源码）
        //  - 其余：纯文本。那些是命令与 JSON，按 Markdown 渲染只会平白吃掉字符
        //
        // 后两条都会走 JEditorPane，所以"富文本"这件事在一处收口（下面 sizing 那几处
        // 也按它分支）：新增一种富文本时不必再去追第二个布尔
        val rich = body.diff != null || body.markdown
        val inputArea: JComponent = if (rich) {
            val html = body.diff?.let {
                diffHtml(it, diffAddBg(), diffDelBg(), diffAddFg(), diffDelFg(), dimHex())
            } ?: planHtml(body.text, accentHex(), dimHex())
            JEditorPane("text/html", html).apply {
                isEditable = false
                isOpaque = false // 透出卡片背景，与旁边那些标签一个底色
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                border = JBUI.Borders.empty()
            }
        } else {
            JBTextArea(body.text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                rows = body.rows
                foreground = UIUtil.getInactiveTextColor()
            }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            // 规则③：不满足条件时**不渲染**，而非渲染后禁用。
            //
            // 注意这个闸卡的性质变了：按钮不再回传 suggestions，所以
            // "suggestions 非空"那一半不再是**能不能**持久化的判断，
            // 而是沿用 CLI 那句"这个请求值不值得给不再问的入口"。
            // 保守留着：放宽它等于多给入口，宁可少给。
            if (PermissionOptions.allowsAlwaysAllow(permission)) {
                add(JButton(AUTO_ALLOW_LABEL).apply {
                    addActionListener {
                        onDecide(
                            PermissionDecision(
                                allow = true,
                                // 不传 suggestions：按钮承诺的范围是"本会话"，
                                // 往 settings.local.json 落一条持久规则比承诺的大
                                updatedPermissions = null,
                                message = null,
                                stopAsking = true,
                            )
                        )
                    }
                })
            }
            add(denyButton)
            add(allowButton)
        }

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(Box.createVerticalStrut(6))
            add(JBLabel(body.caption).apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = LEFT_ALIGNMENT
            })
            add(JBScrollPane(inputArea).apply {
                border = JBUI.Borders.empty()
                alignmentX = LEFT_ALIGNMENT
                // 长正文（计划那种）给得高一些：80px 只够四行，而那是要读的东西。
                // 仍然封顶 —— 卡片再高也不该把按钮顶出屏幕。
                //
                // 例外是富文本那两档（计划、改动预览）：**不封顶**。框现在可以拉伸
                // （方案 B），拖大时这块要跟着长；封着的话用户拖半天只有空白在长。
                // 其它字段照旧封住，免得一条长命令把窗口直接顶到屏幕外。
                //
                // 改动预览跟着计划一起不封顶，理由一样也更强：那一屏是**批准的依据**
                // （要看得出改了哪一句才谈得上批准），给它 320px 等于逼人滚着读。
                maximumSize = Dimension(
                    Int.MAX_VALUE,
                    if (rich) Int.MAX_VALUE else JBUI.scale(body.maxHeight),
                )
                if (rich) {
                    // HTML 面板不认 rows：高度**按屏幕算**（方案 B）——
                    // 45% 屏高，夹在 260~520 之间（见 planAreaHeight）。
                    //
                    // 但那是**上限**不是定额：短的按内容给，否则一个六行的计划
                    // 底下留一片空白（离屏渲染第一版就是这样，图里一眼看见）。
                    // 量不准（量出 0）才退回屏幕份额 —— 超出的照样在框里滚。
                    val cap = JBUI.scale(planAreaHeight(JBUI.unscale(screenHeightPx())))
                    val content = runCatching { inputArea.preferredSize.height }.getOrDefault(0)
                    preferredSize = Dimension(
                        JBUI.scale(PERMISSION_CARD_WIDTH - 40),
                        if (content > 0) content.coerceAtMost(cap) else cap,
                    )
                }
            })
            // 「其余参数」单独一块：它偏代码/JSON，不该混进上面那段被渲染的正文
            body.footer?.let { footer ->
                add(Box.createVerticalStrut(4))
                add(JBScrollPane(JBTextArea(footer).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    rows = 3
                    foreground = UIUtil.getInactiveTextColor()
                }).apply {
                    border = JBUI.Borders.empty()
                    alignmentX = LEFT_ALIGNMENT
                    // 80px 同 PermissionQueue 里 GENERIC_MAX_HEIGHT 那条：附注再长也不该顶掉按钮
                    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))
                })
            }
            add(Box.createVerticalStrut(6))
            add(buttons.apply { alignmentX = LEFT_ALIGNMENT })
        }

        add(center, BorderLayout.CENTER)

        // 自然宽度见 getPreferredSize —— 卡片在窗口里得有一份"够读"的宽度，
        // 而里面那个换行的 JSON 文本区只会报出"最窄也能活"的 152px。

        // 规则②：焦点默认落在"拒绝"。延迟到布局完成后再请求，
        // 否则卡片还未加入组件树，requestFocusInWindow 会静默失败。
        //
        // 进对话框之后这一句是多余的（对话框有自己的 getPreferredFocusedComponent），
        // 但留着：卡片还能被单独渲染与测试，那时它是唯一的焦点来源。
        SwingUtilities.invokeLater { denyButton.requestFocusInWindow() }
    }

    /**
     * 宽度写死、**高度随内容**。
     *
     * 不写成 `preferredSize = Dimension(...)` 那样的一次性赋值：那会把高度也冻在
     * 构造那一刻。权限卡片的内容确实是静态的，但提问卡片会长高（选中「其它…」
     * 冒出一个输入框），而两张卡片共用同一套写法 —— 冻过一次就会有人照着抄。
     *
     * 宽度这一版换成 [PERMISSION_CARD_WIDTH]（640，方案 B）：计划正文里全是长路径，
     * 420 宽下一行折三段。提问框没跟着动 —— 见那个常量的注释。
     */
    override fun getPreferredSize(): Dimension {
        val natural = super.getPreferredSize()
        return Dimension(PERMISSION_CARD_WIDTH, natural.height)
    }

    /**
     * 最小宽度也钉住。
     *
     * 框改成可拉伸之后必须有这条下限：DialogWrapper 拖到比它小就会被挡住，
     * 否则能一路拖成一条缝。高度取布局自己的最小 —— 里面的滚动区最小很小，
     * 所以框仍然能拖矮，只是拖不成零。
     */
    override fun getMinimumSize(): Dimension =
        Dimension(PERMISSION_CARD_WIDTH, super.getMinimumSize().height)

    private companion object {
        val ACCENT = JBColor(0xFFA000, 0xFFB74D)
        // 与权限模式标签共用 —— 两者说的是同一件事（"这里要留意"），
        // 各存一份迟早会漂移
        val WARN = warningColor()
    }
}

/**
 * 强调色 → HTML 里能用的 `#rrggbb`（行内代码、小节那条 `▌`）。
 *
 * ## 2026-09-16 改过一次，原因值得留着
 *
 * 原先直接取 `UIUtil.getTreeSelectionBackground`。那是个**底色** —— 深色主题下
 * 本来就是个深蓝（它生来是要被白字压着的）。我们拿它去当**文字色**，于是计划里的
 * 代码块变成深蓝印在深灰上：对底色约 1.6:1，用户截图来问"深蓝很难看清楚"。
 *
 * 现在改成**按对比度挑**：主题那个色排第一（跟 IDE 一致），不够 4.5:1 就往后走 ——
 * 兜底两个蓝里，深色主题会挑中亮的那个、浅色主题挑中深的那个。
 * 换主题（Darcula / Material / 高对比）都不用重新挑色，也不会再犯"拿了底色当文字色"。
 *
 * `JBColor` 的取值随当前 LaF 走，所以卡片在浅色/深色下会自动拿到各自那一份；
 * 代价是**建好之后换主题不会重画**（HTML 里的颜色已经定死）。权限卡是短命的
 * 一张卡，这个代价收下。
 */
private fun accentHex(): String = hexOf(
    pickReadable(
        listOf(UIUtil.getTreeSelectionBackground(true), CODE_BLUE_BRIGHT, CODE_BLUE_DEEP),
        UIUtil.getPanelBackground(),
    ),
)

/**
 * 小节标题色：从正常前景往底色方向压一档。
 *
 * 不用 `InactiveTextColor`（主题的"次要文字色"在深色下常常只有 3.4:1 —— 那就是
 * 用户截图里另一处发闷的地方）：层级仍在，但不掉到读不清。
 */
private fun dimHex(): String = hexOf(blend(UIUtil.getLabelForeground(), UIUtil.getPanelBackground(), 0.85))

private fun hexOf(color: java.awt.Color): String = "#%06x".format(color.rgb and 0xFFFFFF)

/**
 * 改动预览的四色（加/删各自的底色与文字色）。
 *
 * ## 与转写区**同一套配方**
 *
 * 那边由 `ThemeInjector` 把这四个值算出来喂给 CSS 变量（`--diff-add-bg` 一族）。
 * 这里算的是同一个式子：底色 = 面板底向锚色混 16%（`mix(base, fg, ratio)` 与
 * `blend(fg, bg, keep)` 是同一个式子，只是变量名反着叫），文字色从**正常前景**
 * 出发混 60%。不写死绿/红：硬编码的那种在浅色主题下要么看不见、要么刺眼。
 *
 * 为什么不直接读那四个变量：它们在 `ThemeColors` 里，读一次要 `EditorColorsManager`
 * 与 EDT，而这里只要四个颜色。**配方**才是要对齐的东西 —— 换主题两边各自跟着变。
 *
 * 底色从**面板背景**出发（卡片 `isOpaque = false`，透出的就是它），转写区从气泡底
 * 出发 —— 底不一样，混出来的值本来就不该一样。
 *
 * 这两对颜色**只在这张卡里用**：权限卡是短命的一张卡（每次询问重建），
 * 所以"HTML 里的颜色建好即定死、换主题不重画"这个代价收下，同 [accentHex]。
 */
private fun diffAddBg(): String = hexOf(blend(DIFF_ADD_ANCHOR, UIUtil.getPanelBackground(), 0.16))

private fun diffDelBg(): String = hexOf(blend(DIFF_DEL_ANCHOR, UIUtil.getPanelBackground(), 0.16))

private fun diffAddFg(): String = hexOf(blend(DIFF_ADD_ANCHOR, UIUtil.getLabelForeground(), 0.60))

private fun diffDelFg(): String = hexOf(blend(DIFF_DEL_ANCHOR, UIUtil.getLabelForeground(), 0.60))

/**
 * 屏幕高（真实像素），给 [planAreaHeight] 用。
 *
 * 取不到就按 1080：无头测试里 `Toolkit` 会抛 `HeadlessException`，而那时这个数
 * 只影响"计划区首选多高"一个值。**不往构造函数外面传** —— 调用方没有更好的答案。
 */
private fun screenHeightPx(): Int =
    runCatching { java.awt.Toolkit.getDefaultToolkit().screenSize.height }.getOrDefault(1080)
