package com.ccoder.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

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
 *
 * ## 灰的第二个原因（2026-09-16 实机反馈，也在这条动作上）
 *
 * 加完 DumbAware 之后**还是灰的**。这次日志直接给出了答案：`dumb=false`
 * （不在索引期），可 `array=null single=null navigatables=1` —— 平台一个
 * `VirtualFile` 都不给，只给一个我们当时读不懂的导航对象。见 [navigatableFile]。
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
        // 兜底：选中项以 Navigatable 形式给出来时（项目树对"当前节点"也给这个）。
        // **必须换一道**，不能 `filterIsInstance<VirtualFile>()` —— 见 [navigatableFile]
        e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.mapNotNull(::navigatableFile),
    )

    /**
     * 灰的时候留一行日志。
     *
     * "看得见、点不动"从界面上永远看不出为什么（2026-09-15 就是这么耗掉两轮的），
     * 而这一行把当时上下文里有什么、是不是在索引期间，一次说清。
     *
     * **2026-09-16 补：把导航对象的类名也记下来。** 上一次只记了个 `navigatables=1`，
     * 于是"平台给的那一个到底是什么"在日志里看不见，只能再猜一轮。现在每个对象
     * 后面跟一个"认得出/认不出"—— 认不出时类名就在眼前，不用再等第二次反馈。
     *
     * 读法：**`✓` 还原样灰着，只剩一种解释 —— 选中的是目录**（`@` 认的是文件，
     * 那是 [pickFiles] 的活，设计如此）；`✗` 则是我们真的没认出来，类名就在那一行。
     */
    private fun diagnose(e: AnActionEvent) {
        val navs = e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)
        LOG.info(
            "加文件动作置灰：place=${e.place} project=${e.project != null} " +
                "array=${e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.size} " +
                "single=${e.getData(CommonDataKeys.VIRTUAL_FILE)?.path} " +
                "navigatables=${navs?.size}${navs?.joinToString("", " → ", "") { describe(it) }.orEmpty()} " +
                "dumb=${e.project?.let { DumbService.isDumb(it) }}",
        )
    }

    /**
     * 日志里那一个导航对象长什么样：类名 + [navigatableFile] 认不认得出它。
     *
     * **认不出时给全名**：上一次日志里只有个 `PsiFileNode`，而平台里同名的类
     * 不止一处、在哪一个 jar 里也得现找 —— 多打几个包名省掉一整轮猜测。
     */
    private fun describe(nav: Any?): String {
        val cls = nav?.javaClass ?: return "null✗"
        val read = navigatableFile(nav) != null
        return (if (read) cls.simpleName else cls.name) + if (read) "✓" else "✗"
    }

    /** 选中项里**只有**目录 —— 用来把 tooltip 的话说准，不是用来判定的。 */
    private fun selectedIsOnlyDirectory(e: AnActionEvent): Boolean =
        pickFiles(
            e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList(),
            e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(::listOf),
            e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.mapNotNull { navigatableFile(it) },
            isDirectory = { false },
        ).isNotEmpty()
}

/**
 * 数据上下文里那个"可导航对象" → [VirtualFile]。
 *
 * ## 为什么不能只认 `VirtualFile`（2026-09-16 的日志铁证）
 *
 * 项目树右键里平台**一个 `VirtualFile` 都不给**：`VIRTUAL_FILE_ARRAY` 与
 * `VIRTUAL_FILE` 都是 null，只给一个 `NAVIGATABLE_ARRAY`，而那一个不是
 * VirtualFile —— 否则动作早就亮了。
 *
 * 也就是说 `filterIsInstance<VirtualFile>()` 那一手**从写下来就没生效过**：
 * 日志里 09-15 17:30 与 09-16 16:18/16:19 三次签名一模一样，全是
 * `array=null single=null navigatables=1`，而 09-15 那次已经带上了这个兜底。
 *
 * ## 那一个到底是什么（同一天稍后量出来了）
 *
 * 把类名记进日志之后，它是 **`PsiFileNode`** —— 项目树的**节点**，既不是
 * VirtualFile 也不是 PSI 元素。2026.1 的发行包里对过继承链：
 *
 * ```
 * PsiFileNode → BasePsiNode<PsiFile> → AbstractPsiBasedNode → ProjectViewNode → AbstractTreeNode
 * ```
 *
 * 所以这里顺着 [AbstractTreeNode] 往下问它的**值**（`PsiFileNode` 的值就是
 * `PsiFile`），一轮递归回到上面那几条分支。`PsiDirectoryNode` 同理 ——
 * 值是个 `PsiDirectory`，于是"选中的是文件夹"那句 tooltip 也有了依据。
 *
 * 挑基类挑 [AbstractTreeNode] 而不是内部的 `PsiFileNode`/`ProjectViewNode`：
 * 前者是**公开 API**（编译 SDK 与 2026.1 的 `app.jar` 里都有），后两者在
 * 编译用的 2025.3.1.1 里根本不存在 —— 写死了就编不过。
 *
 * ## 认哪几种
 *
 *  - [VirtualFile] 直接就是（老版本、以及别的弹层）
 *  - `PsiFile` / `PsiDirectory` → 它自己的 `virtualFile`
 *  - 别的 `PsiElement`（方法、类那种节点）→ **它所在的文件**。挑中一个节点时
 *    用户想加的本来就是"这个文件"，退到 `containingFile` 比丢掉强
 *  - 树节点 → 它包着的那个值，递归再认一次
 *
 * 都认不出来才返回 null —— 那时动作该是灰的，那是正确行为
 * （[AddFilesToChatAction.update] 会给一句为什么灰）。
 *
 * 目录**照旧在这里放行**：滤掉目录是 [pickFiles] 的活（`@` 认的是文件），
 * 两处混在一起会让"选中的是文件夹"那句 tooltip 失去依据。
 */
internal fun navigatableFile(
    navigatable: Any?,
    /**
     * 判定口子：怎么从树节点里取它的值。
     *
     * 真节点在无头测试里**造不出来** —— `AbstractTreeNode` 的构造器要
     * `Application`（`setInternalValue` → `TreeAnchorizer.getService`，
     * 实测 NPE）。所以这一层留个口子，测试拿一个普通对象当节点，
     * 就能把"节点 → 值 → 文件"这条递归钉住；剩下不可测的只有下面那个 cast。
     */
    nodeValue: (Any) -> Any? = ::nodeValueOf,
): VirtualFile? = when (navigatable) {
    null -> null
    is VirtualFile -> navigatable
    is PsiFile -> navigatable.virtualFile
    // PsiDirectory 不是 PsiFile，必须单列：它的 containingFile 是 null
    is PsiDirectory -> navigatable.virtualFile
    is PsiElement -> navigatable.containingFile?.virtualFile
    // 项目树给的就是这一类（见类注释里的继承链）。值可能是 PSI、也可能是
    // VirtualFile，所以**递归**回上面几条，而不是在这儿再判一遍
    else -> nodeValue(navigatable)
        // 自己的值是自己：停在这里。真出现这种节点，递归下去就是栈溢出
        ?.takeIf { it !== navigatable }
        ?.let { navigatableFile(it, nodeValue) }
}

/** 树节点包着的值，`PsiFileNode` 包的就是那个 `PsiFile`。 */
private fun nodeValueOf(nav: Any): Any? = (nav as? AbstractTreeNode<*>)?.value

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
