package com.ccoder.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile

// 右键「加文件到聊天框」的两个入口。
//
// 与选区那条（[AddSelectionToChatAction]）分工明确：
//   · 选区 → 一段代码，输入框里放一行**记号**，发送时展开成代码围栏
//   · 文件 → 一个 `@相对路径`，插件不读文件、也不内联内容，**CLI 自己展开**
//
// 后者这条是实测过的（2026-09-14）：`@路径` 会让模型零工具调用就拿到文件内容；
// 而 `@路径:24-27` 那种带行范围的引用**不会**被展开（模型只好自己去读整份文件）。
// 所以"加文件"能走 `@`，而"加选中的那几行"不能 —— 这也是为什么选区那条要展开。

/**
 * 把当前编辑器里的整个文件加到聊天框。
 *
 * **没有选区时也能用** —— 这正是原来缺的那个入口：想引用一整个文件，以前只能
 * 开文件、全选、再右键（而全选中之后 `selectionLineRange` 还会把行号算出来，
 * 出来的是"这个文件的全部行"而不是"这个文件"）。
 */
class AddFileToChatAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** 置灰而不是藏起来：用户才知道这里有个动作（同选区那条）。 */
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = e.project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        withPanel(project) { it.addToComposer(fileMention(mentionPathOf(project, file.path))) }
    }
}

/**
 * 项目树里加文件。**多选一次全加** —— 在树里挑文件比"开文件再右键"自然得多，
 * 而挑的时候往往一挑就是几个。
 */
class AddFilesToChatAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = e.project != null && files(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val mentions = files(e).joinToString(" ") { fileMention(mentionPathOf(project, it.path)) }
        if (mentions.isBlank()) return
        withPanel(project) { it.addToComposer(mentions) }
    }

    /** 目录会被顺带选上，这里只取文件 —— 目录不是 `@` 认得的东西。 */
    private fun files(e: AnActionEvent): List<VirtualFile> =
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.filter { !it.isDirectory } ?: emptyList()
}
