package com.ccoder.update

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * 「这一版更新了什么」那个框（2026-09-17）。
 *
 * ## 只在**更新之后**弹一次
 *
 * 该不该弹由 [maybeShowChangelog] 判断（纯函数 + 应用级标记），这里只管画。
 * 所以这个框不需要"以后不再提示"这种勾 —— "每个版本一次"已经把那个语义覆盖了。
 *
 * ## 为什么不用 `Messages.showInfoMessage`
 *
 * 它渲染 HTML 的能力很有限（`<ul>` 的缩进与 `<h3>` 的层级会散架），而且那一屏
 * 只能放一段文字，没有"标题 / 正文 / 一颗按钮"的结构。change-notes 本身就是
 * 一段 HTML（市场页显示的是同一份），`JEditorPane` 能照着渲染出来。
 *
 * ## 按钮只有一颗
 *
 * 照 `SettingsDialog` 的写法自己给 `createActions()`（不用平台的 OK/Cancel 对）：
 * 这一屏没有任何"另一个选择"，关掉就是唯一出路。回车与 Esc 都关掉它 ——
 * 不存在"按错了"的分支，所以不必像权限框那样把默认按钮摘掉。
 */
internal class ChangelogDialog(
    project: Project?,
    private val changelog: PluginChangelog,
) : DialogWrapper(project) {

    /**
     * 正文那一块。
     *
     * 做成属性（而不是只在 [createCenterPanel] 里现 new 一个）是为了**渲染探针**：
     * 平台把 `createCenterPanel` 声明成了 `protected`，探针够不着 ——
     * 本仓 `SettingsDialog.contentPanel` 是同一个写法。
     *
     * 必须声明在 [init] **之前**：属性初始化器与 `init` 块按书写顺序执行，
     * 而 `init()` 里就会走到 `createCenterPanel`（同 SettingsDialog 那条注释）。
     */
    internal val contentPanel: JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
        add(intro(), BorderLayout.NORTH)
        add(notesPane(), BorderLayout.CENTER)
        preferredSize = JBUI.size(DIALOG_WIDTH, DIALOG_HEIGHT)
    }

    init {
        title = "CCoder 已更新到 ${changelog.version}"
        isResizable = true
        init()
    }

    /** 唯一那颗按钮。**不给它 `DEFAULT_ACTION` 那个名字**（同 SettingsDialog 的理由）。 */
    private val knownAction = object : DialogWrapper.DialogWrapperAction(KNOWN_BUTTON_TEXT) {
        override fun doAction(e: ActionEvent) {
            close(OK_EXIT_CODE)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(knownAction)

    override fun createCenterPanel(): JComponent = contentPanel

    /**
     * 正文上面那一句。**写清"这是哪一版"** —— 框的标题在窗口装饰上，
     * 而截图、录屏、或者别人帮看的时候，正文里没有版本号就认不出来。
     */
    private fun intro(): JComponent = JBLabel(
        "这一版的变化（完整清单也写在插件页上）：",
    ).apply {
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.emptyBottom(6)
    }

    /**
     * change-notes 的 HTML。
     *
     * 建一次就不动了（`JEditorPane` 自己管滚动），所以外面套 `JBScrollPane` 并关掉横向条
     * —— 横向条吃的是右侧内边距，且这里的 HTML 本来就该按宽度折行。
     */
    private fun notesPane(): JComponent = JBScrollPane(
        JEditorPane("text/html", wrapHtml(changelog.notesHtml)).apply {
            isEditable = false
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty()
            // 光标别变成文本编辑那个 I 形：这一屏是"读"，不是"改"
            cursor = Cursor.getDefaultCursor()
        },
    ).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        viewport.isOpaque = true
        viewport.background = UIUtil.getPanelBackground()
    }

    private companion object {
        const val KNOWN_BUTTON_TEXT = "知道了"

        /**
         * 框的初始尺寸。
         *
         * 宽度取 560：change-notes 里最长的一行（带 `<code>` 的那几条）在这个宽度下
         * 不用横向滚；高度 420 让最常见的四五条更新一屏放得下，长了就滚。
         * 两个数都会随内容被 `pack` 抬上去，但不会低于它们 —— 只有两三条时框不会
         * 缩成一个小方块。
         */
        val DIALOG_WIDTH = 560
        val DIALOG_HEIGHT = 420

        /**
         * 给 change-notes 的 HTML 补上外壳与一点点排版。
         *
         * 描述符里那段是**片段**（`<h3>` / `<ul>` 起步），直接喂给 `JEditorPane`
         * 也能渲染，但默认字体与四周留白都不成样子。这里只做三件事：包一层
         * `<body>`、用当前主题的界面字体、把边距收掉（留白由外面的边框给）。
         *
         * **别给 `<code>` 指定字体**：JEditorPane 只认 HTML 3.2 那一套 CSS，
         * `font-family` 基本被忽略，写一条 `code { font-family: … }` 反而会盖掉
         * 它自带的等宽样式（试过：d342e45 那样的 commit 号变成了正文字体）。
         */
        fun wrapHtml(fragment: String): String {
            val font = UIUtil.getLabelFont()
            val family = font.family
            val size = font.size
            return """
                <html><head><style>
                  body { font-family: '$family'; font-size: ${size}px; margin: 0 2px; }
                  h3 { font-size: ${size + 2}px; margin: 2px 0 6px 0; }
                  h4 { font-size: ${size}px; margin: 10px 0 4px 0; }
                  ul { margin: 0 0 0 16px; padding: 0; }
                  li { margin: 0 0 3px 0; }
                </style></head><body>$fragment</body></html>
            """.trimIndent()
        }
    }
}

/** 弹一次这个框。生产路径给 [ClaudePanel] 用；探针与测试换掉它。 */
internal fun showChangelogDialog(project: Project?, changelog: PluginChangelog) {
    ChangelogDialog(project, changelog).show()
}
