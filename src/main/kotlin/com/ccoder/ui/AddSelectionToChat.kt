package com.ccoder.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ToolWindowManager

// 编辑器选区 → 聊天框片段。
//
// 排版规则抽成纯函数（不碰 Project、不碰 Action）是为了可测：动作本身
// 依赖 ActionEvent 与工具窗口，起不了单测；而"选区算哪几行、片段长什么样"
// 正是最容易写错的部分。

/** 选区落在哪几行（1 起、含两端）。 */
internal fun selectionLineRange(text: CharSequence, startOffset: Int, endOffset: Int): IntRange {
    val startLine = lineNumberOf(text, startOffset)
    var endLine = lineNumberOf(text, endOffset)
    // 整行整行地选时，选区带着行尾那个 \n，落点已经站到下一行行首了。
    // 不减回去的话行号会整体偏大一行 —— 而 Claude 是按行号找位置的
    if (endOffset > startOffset && text[endOffset - 1] == '\n') endLine--
    return startLine..endLine
}

private fun lineNumberOf(text: CharSequence, offset: Int): Int {
    var line = 1
    for (i in 0 until offset) if (text[i] == '\n') line++
    return line
}

/**
 * 发送时**真正发出去**的那段（路径 + 围栏 + 代码全文）。
 *
 * 2026-09-14 起它不再出现在输入框里 —— 输入框里放的是一行记号（[refToken]），
 * 由 [SnippetRefs] 在发送的那一刻换成这一段。**它的形状一个字节都没变**，
 * 所以模型收到的东西与改版前完全一致。
 */
internal fun formatSnippet(
    path: String,
    lines: IntRange,
    fileTypeName: String?,
    code: String,
): String {
    val where =
        if (lines.first == lines.last) "$path:${lines.first}"
        else "$path:${lines.first}-${lines.last}"

    // 选区常以行尾的 \n 收尾，直接拼会在收尾的 ``` 前多出一个空行。
    // trimEnd 只吃末尾空白 —— 代码自身的缩进不受影响
    val body = code.trimEnd()
    val tag = languageTagOf(fileTypeName).orEmpty()
    return "$where\n\n```$tag\n$body\n```"
}

/**
 * 围栏的语言标签。
 *
 * IDE 的文件类型名里，真正的语言是 "Kotlin" 这种单个词；而 "PLAIN_TEXT"
 * 这种带下划线的是内部类型名 —— 把它小写塞进围栏，等于告诉模型
 * "这是 plain_text 语言写的"，不如不写（退化成无标签的 ```）。
 */
private fun languageTagOf(fileTypeName: String?): String? =
    fileTypeName?.lowercase()?.takeIf { it.isNotBlank() && '_' !in it }

/**
 * 编辑器右键 →「添加到 CCoder 聊天框」。
 *
 * 只做三件事：取选区、拼出**记号**与**完整片段**、交给面板
 * （记号进输入框，片段存进展开表等发送时用）。
 */
class AddSelectionToChatAction : AnAction() {

    /**
     * 在 EDT 上更新。
     *
     * 这里要读编辑器的选区，而选区是 EDT 上的数据 —— 换成 BGT 得自己
     * 判断哪些 data key 在后台线程合法，这种廉价判断不值得冒那个险。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** 没有选区就没有可添加的东西：**置灰而不是藏起来**，用户才知道这里有个动作。 */
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = e.project != null && editor?.selectionModel?.hasSelection() == true
        e.presentation.isVisible = true
        e.presentation.isEnabled = hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return

        val code = selection.selectedText ?: return
        val virtualFile = editor.virtualFile
        val path = mentionPathOf(project, virtualFile?.path)
        val lines = selectionLineRange(
            editor.document.charsSequence,
            selection.selectionStart,
            selection.selectionEnd,
        )

        // 输入框里放记号，片段留在表里 —— 两样东西都从同一份 code 与 lines 出来，
        // 不会出现"显示的是这几行、发出去的是另外几行"
        withPanel(project) {
            it.addSnippetToComposer(
                token = refToken(path, lines),
                snippet = formatSnippet(path, lines, virtualFile?.fileType?.name, code),
            )
        }
    }
}

/**
 * 拿到面板并执行 [action]。
 *
 * 工具窗口没开过时，`show()` 才会去创建内容，而内容不一定在这一拍就建好 ——
 * 取不到就推迟一拍再试**一次**（只重试一次：重试路径不设上限就成了死循环）。
 *
 * 三个右键动作（加选区 / 加文件 / 项目树加文件）共用它。
 */
internal fun withPanel(
    project: com.intellij.openapi.project.Project,
    retry: Boolean = true,
    action: (ClaudePanel) -> Unit,
) {
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
    toolWindow.show()
    val panel = toolWindow.contentManager.contents.firstOrNull()?.component as? ClaudePanel
    if (panel != null) {
        action(panel)
        return
    }
    if (retry) {
        ApplicationManager.getApplication().invokeLater {
            withPanel(project, retry = false, action)
        }
    }
}

/** 工具窗口 id。与 plugin.xml 里的 `id="CCoder"` 必须一致。 */
internal const val TOOL_WINDOW_ID = "CCoder"

/** 拿不到文件路径时的占位。聊胜于无：至少格式不塌。 */
internal const val UNKNOWN_PATH = "未命名"

/**
 * 引用里写哪个路径。
 *
 * 项目内的文件写相对项目根的路径（Claude 在项目目录下工作，实测相对路径
 * 会被 CLI 展开）；项目外的退化成绝对路径 —— 相对不了就别硬凑。
 */
internal fun mentionPathOf(
    project: com.intellij.openapi.project.Project,
    absolutePath: String?,
): String {
    if (absolutePath == null) return UNKNOWN_PATH
    val base = project.basePath ?: return absolutePath
    return FileUtil.getRelativePath(base, absolutePath, '/') ?: absolutePath
}
