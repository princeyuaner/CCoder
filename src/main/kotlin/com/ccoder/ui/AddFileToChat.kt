package com.ccoder.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile

// 三个右键动作都实现了 DumbAware —— 见 AddFilesToChatAction 的类注释：
// 不实现的话，**索引期间平台会把它们灰掉**，而"灰"在界面上没有任何解释。
private val LOG = Logger.getInstance("com.ccoder.ui.AddFileToChat")

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
class AddFileToChatAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /** 置灰而不是藏起来：用户才知道这里有个动作（同选区那条）。 */
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        val file = fileOf(e)
        e.presentation.isEnabled = e.project != null && file != null
        // 同 AddFilesToChatAction：灰的时候给一句为什么，别让人对着一个点不动的项猜
        e.presentation.description =
            if (file == null) "先打开一个文件，再右键" else "把这个文件作为 @ 引用加到 CCoder 输入框（内容由 CLI 展开）"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = fileOf(e) ?: return
        withPanel(project) { it.addToComposer(fileMention(mentionPathOf(project, file.path))) }
    }

    /**
     * 当前文件：先问 `VIRTUAL_FILE`，取不到再退到 PSI 那一份。
     *
     * 两处必须**同一个出口**：只在 update 里退、不在 actionPerformed 里退，会出现
     * "看着能点、点了没反应" —— 比灰着更糟。
     */
    private fun fileOf(e: AnActionEvent): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE) ?: e.getData(CommonDataKeys.PSI_FILE)?.virtualFile
}

/**
 * 项目树里加文件。**多选一次全加** —— 在树里挑文件比"开文件再右键"自然得多，
 * 而挑的时候往往一挑就是几个。
 *
 * ## 为什么必须 `DumbAware`
 *
 * **2026-09-15 实机反馈**：这一项在项目树里看得见但**是灰的、点不动**。
 * 平台对没声明 [DumbAware] 的动作，会在**索引期间**统一灰掉 —— 不管
 * `update()` 怎么说。而索引在这个项目里是常态而不是例外（Gradle 构建、
 * `npm install`、解包 node_modules 都会触发重建）。
 *
 * 我们这两个动作只碰输入框与工具窗口，**一个字都不依赖索引**，所以声明
 * DumbAware 不只是"绕开限制"，它本来就是对的。工具窗口的工厂
 * （`ClaudeToolWindowFactory`）当初也是为同一件事加的。
 */
class AddFilesToChatAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        val picked = files(e)
        e.presentation.isEnabled = e.project != null && picked.isNotEmpty()
        if (picked.isEmpty()) diagnose(e)
        // **灰的时候要说为什么。** 这一类"看得见、点不动"从界面上看不出原因
        // （2026-09-15 就是这么被问到的：项目树里选中文件，那一项是灰的）。
        // 置灰仍然是置灰，但 tooltip 里给出下一步。
        e.presentation.description = when {
            e.project == null -> null
            picked.isNotEmpty() -> "把这个文件作为 @ 引用加到 CCoder 输入框（内容由 CLI 展开）"
            selectedIsOnlyDirectory(e) -> "选中的是文件夹 —— 这一项只加文件（@ 认的是文件）"
            else -> "先在上面选中要加的文件"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val mentions = files(e).joinToString(" ") { fileMention(mentionPathOf(project, it.path)) }
        if (mentions.isBlank()) return
        withPanel(project) { it.addToComposer(mentions) }
    }

    /**
     * 选中的文件。**几个来源依次问**，见 [pickFiles]。
     *
     * 取数那几行是胶水；判定与过滤在 [pickFiles] 里，那一半能单测。
     */
    private fun files(e: AnActionEvent): List<VirtualFile> = pickFiles(
        // 多选：项目树里平台自己的动作（OverrideFileType、Synchronize）走的就是它
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList(),
        // 单选：有些弹层只给这一个
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(::listOf),
        // 兜底：选中项以 Navigatable 形式给出来时（项目树对"当前节点"也给这个）
        e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.filterIsInstance<VirtualFile>(),
    )

    /**
     * 灰的时候留一行日志。
     *
     * "看得见、点不动"从界面上永远看不出为什么（2026-09-15 就是这么耗掉两轮的），
     * 而这一行把当时上下文里有什么、是不是在索引期间，一次说清。
     */
    private fun diagnose(e: AnActionEvent) {
        LOG.info(
            "加文件动作置灰：place=${e.place} project=${e.project != null} " +
                "array=${e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.size} " +
                "single=${e.getData(CommonDataKeys.VIRTUAL_FILE)?.path} " +
                "navigatables=${e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.size} " +
                "dumb=${e.project?.let { DumbService.isDumb(it) }}",
        )
    }

    /** 选中项里**只有**目录 —— 用来把 tooltip 的话说准，不是用来判定的。 */
    private fun selectedIsOnlyDirectory(e: AnActionEvent): Boolean =
        pickFiles(
            e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList(),
            e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(::listOf),
            e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.filterIsInstance<VirtualFile>(),
            isDirectory = { false },
        ).isNotEmpty()
}

/**
 * 从若干候选来源里挑出这一次要加的文件：**取第一个非空的来源**，再把目录滤掉。
 *
 * ## 为什么不能只认一个键
 *
 * **2026-09-15 实机反馈**：项目树里选中文件、右键，那一项**看得见但是灰的** ——
 * 灰 = 这里返回了空，动作于是把自己禁掉，界面上只表现为"点不动"，一句解释都没有。
 *
 * 平台自己的项目树动作（`OverrideFileTypeAction`、`SynchronizeCurrentFileAction`）
 * 读的是 `VIRTUAL_FILE_ARRAY`，所以那一处**应该**有；但具体到某个版本、某个
 * 弹层的上下文给了哪几个键，是平台的实现细节，而它不止一次变过。与其赌一个键，
 * 不如把合理的几个都列上、按优先级取第一个非空的：[sources] 的顺序就是优先级。
 *
 * 一处都没给才返回空 —— 那时动作该是灰的，那是正确行为（[AddFilesToChatAction.update]
 * 会给一句为什么灰）。
 *
 * 目录一律滤掉：`@` 认的是文件，给过去一个目录名 CLI 展开不出东西。
 */
internal fun pickFiles(
    vararg sources: List<VirtualFile>?,
    // 判定口子：无头测试里造不出 isDirectory=true 的 VirtualFile
    // （`LightVirtualFile` 恒定返回 false，真造一个目录要起 Application）
    isDirectory: (VirtualFile) -> Boolean = { it.isDirectory },
): List<VirtualFile> {
    val picked = sources.firstOrNull { !it.isNullOrEmpty() }.orEmpty()
    return picked.filterNot(isDirectory)
}
