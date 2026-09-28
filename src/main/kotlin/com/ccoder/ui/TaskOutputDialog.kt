package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** 打开某个任务的输出。生产路径给 `ClaudePanel` 用；探针直接调 [TaskOutputContent]。 */
internal fun showTaskOutput(project: Project?, task: FinishedTask) {
    TaskOutputDialog(project, task).show()
}

/**
 * 「看输出」那张框（2026-09-28，选型稿 `docs/design/background-tasks.html` 丙）。
 *
 * ## 为什么是框而不是浮层
 *
 * 输出可能有几万行。浮层是"贴着卡看一眼"的东西，塞不下它；而真要看细，
 * 用户的下一步永远是**在编辑器里打开**（搜索、折叠、大文件都现成）——
 * 所以这个框只做两件事：给末尾一屏、给一个跳过去的按钮。
 *
 * ## 读盘**不在 EDT** 上
 *
 * 骨架照 [openInEditor]：先弹框（正文写"读取中…"），线程池里读，回来再填。
 * 反面做法（**在 EDT 上先读完再弹**）在几十 MB 的输出上是把 IDE 冻住几秒 ——
 * 用户看到的是"点了没反应"。框关掉了就不再填（[isVisible] 那一句）。
 */
internal class TaskOutputDialog(
    private val project: Project?,
    private val task: FinishedTask,
) : DialogWrapper(project) {

    /** 正文。声明在 [init] **之前**：`createCenterPanel` 是 protected，探针够不着（同 `ContextDetailDialog`）。 */
    internal val content = TaskOutputContent(task)

    private val copyAction = object : DialogWrapper.DialogWrapperAction(CcoderText.text("transcript.detail.output.copy")) {
        override fun doAction(e: ActionEvent) {
            copyToClipboard(content.body())
        }
    }

    private val openAction = object : DialogWrapper.DialogWrapperAction(CcoderText.text("transcript.detail.output.open")) {
        override fun doAction(e: ActionEvent) {
            val path = task.outputFile ?: return
            val target = project ?: return
            // 打不开时它会自己弹一句（file.open.notFound 那条路），不用在这里再写一遍
            openInEditor(target, OpenFileTarget(path, line = null))
        }
    }

    /** Esc 走平台的 cancel（`DialogWrapper.show()` 里注册），一样关得掉。 */
    private val closeAction = object : DialogWrapper.DialogWrapperAction(CcoderText.text("common.close")) {
        override fun doAction(e: ActionEvent) {
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = CcoderText.text("transcript.detail.output.title")
        isResizable = true
        // 没有 project 就开不了编辑器（探针那条路就是这样）—— 按钮留着但不能是"点了没反应"
        if (project == null) openAction.isEnabled = false
        init()
        load()
    }

    override fun createActions(): Array<Action> = arrayOf(copyAction, openAction, closeAction)

    override fun createCenterPanel(): JComponent = content.panel

    private fun load() {
        val path = task.outputFile
        if (path == null) {
            content.set(OutputState.Missing)
            disableFileActions()
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val state = when (val read = readOutputTail(path)) {
                OutputRead.Missing -> OutputState.Missing
                is OutputRead.Ok -> OutputState.Loaded(read.tail)
            }
            ApplicationManager.getApplication().invokeLater {
                // 用户可能已经把框关了 —— 那时不必再填，也不该再去动两个动作的可用性
                if (!isVisible) return@invokeLater
                content.set(state)
                if (state is OutputState.Missing) disableFileActions()
            }
        }
    }

    private fun disableFileActions() {
        copyAction.isEnabled = false
        openAction.isEnabled = false
    }
}

/** 正文的三种状态。读盘是异步的，所以它是**被换上去的**，不是构造时定的。 */
internal sealed interface OutputState {
    data object Reading : OutputState

    data object Missing : OutputState

    data class Loaded(val tail: OutputTail) : OutputState
}

/**
 * 那张框的正文：任务名一行、路径一行、输出一屏、脚注一句。
 *
 * 单独成类是为了让**渲染探针**能出图：探针直接把状态喂成 [OutputState.Loaded]，
 * 不必真去读磁盘、也不必弹一个真的模态框。
 */
internal class TaskOutputContent(private val task: FinishedTask) {

    private val body = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        // 等宽：输出是给人**对字符**看的（路径、列号、堆栈），比例字体对不齐
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(6, 8)
        text = CcoderText.text("transcript.detail.output.reading")
    }

    private val note = JBLabel().apply {
        foreground = UIUtil.getInactiveTextColor()
        font = font.deriveFont(font.size2D - 1f)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    val panel: JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(12, 14, 8, 14)

        add(headRow())
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(
            JBScrollPane(body).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = JBUI.size(AREA_WIDTH, AREA_HEIGHT)
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            },
        )
        add(Box.createVerticalStrut(JBUI.scale(8)))
        add(note)
    }

    /** 任务名 + 读数一行，路径一行（路径给完整的：它就是用户去编辑器里找的那个东西）。 */
    private fun headRow(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(
                    JBLabel(task.label?.takeIf { it.isNotBlank() } ?: task.id.take(8)),
                    BorderLayout.WEST,
                )
                add(
                    JBLabel(copyMetaOf(task)).apply {
                        foreground = UIUtil.getInactiveTextColor()
                        font = font.deriveFont(font.size2D - 1f)
                    },
                    BorderLayout.EAST,
                )
            },
        )
        task.outputFile?.let { path ->
            add(
                JBLabel(path).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    font = font.deriveFont(font.size2D - 1f)
                    toolTipText = path
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
        }
    }

    fun set(state: OutputState) {
        when (state) {
            OutputState.Reading -> {
                body.text = CcoderText.text("transcript.detail.output.reading")
                note.text = ""
            }

            OutputState.Missing -> {
                body.text = ""
                note.text = CcoderText.text("transcript.detail.output.missing")
            }

            is OutputState.Loaded -> {
                body.text = state.tail.lines.joinToString("\n")
                body.caretPosition = 0
                note.text = if (state.tail.truncatedHead) {
                    CcoderText.text("transcript.detail.output.tailCut", state.tail.lines.size)
                } else {
                    CcoderText.text("transcript.detail.output.tail", state.tail.lines.size)
                }
            }
        }
    }

    /** 「复制全部」复制的就是这一屏 —— 复制不到的东西不该让按钮显得能复制。 */
    fun body(): String = body.text

    private companion object {
        const val AREA_WIDTH = 640
        const val AREA_HEIGHT = 320
    }
}

/** 读数那串（时长 · 工具数）—— 与面板行上那颗用的是同一条口径。 */
private fun copyMetaOf(task: FinishedTask): String = listOfNotNull(
    durationTextOf(task.durationMs),
    if (task.toolUses > 0) CcoderText.text("transcript.detail.meta.tools", task.toolUses) else null,
    if (task.tokens > 0) formatTokenCount(task.tokens) else null,
).joinToString(" · ")
