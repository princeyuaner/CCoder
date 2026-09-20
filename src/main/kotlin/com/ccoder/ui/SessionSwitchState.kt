package com.ccoder.ui

import com.ccoder.sidecar.ClearFailure
import com.ccoder.sidecar.RequestOutcome
import com.ccoder.sidecar.SessionInfo
import com.ccoder.sidecar.SidecarMessage
import com.ccoder.text.CcoderText

/** 会话切换被什么挡住了。 */
internal enum class SwitchBlock {
    /** 可以切 */
    None,

    /** 回合进行中 */
    TurnRunning,

    /** 有权限询问挂着 */
    PermissionPending,
}

/**
 * 忙时能否切换会话。
 *
 * 抽成纯函数的原因同 [mainButtonState]：ClaudePanel 依赖 Swing 与平台、
 * 起不了单测，而"什么时候该拦住"正是这次改动里最容易写错的部分。
 *
 * 拦住的理由不是体验而是正确性 —— 切换要 `stopSession()`：
 *  - 正在跑的回合会被腰斩
 *  - 挂着的权限询问会一并作废（sidecar 收到 stop 后的 denyAllPending）
 *
 * 权限挂起**优先于**回合进行：有询问挂着时 busy 通常也是 true，但
 * "先处理那条询问"才是用户该做的动作。提示得更具体才有用。
 */
internal fun switchBlock(busy: Boolean, pendingPermissions: Int): SwitchBlock = when {
    pendingPermissions > 0 -> SwitchBlock.PermissionPending
    busy -> SwitchBlock.TurnRunning
    else -> SwitchBlock.None
}

/**
 * 拦住时给用户看的一句话。可切时返回 null。
 *
 * 两句话都必须指向**具体动作** —— 光说"不能切"用户不知道下一步做什么。
 */
internal fun switchBlockNotice(block: SwitchBlock): String? = when (block) {
    SwitchBlock.None -> null

    SwitchBlock.TurnRunning ->
        CcoderText.text("session.switch.busyTurn")

    SwitchBlock.PermissionPending ->
        CcoderText.text("session.switch.busyPermission")
}

/** 会话标题最多留几个字。 */
internal const val SESSION_TITLE_MAX = 24

/** 压一行用的：任何一段空白（含换行、制表）都算一个分隔。 */
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * 会话标题 = 一段文本的**开头**。
 *
 * ## 为什么必须压成一行
 *
 * 第一条消息常常是带换行的多行文本，而顶上那个标签只有一行 ——
 * 不压的话换行符会把它撑成两行，把「＋」和齿轮挤出去。
 *
 * ## 为什么截断
 *
 * 一句话可以是一整段。标题的用处是"让我认出这是哪条会话"，开头几个字就够。
 *
 * `SESSION_TITLE_MAX = 24` 是**量出来的**，不是估的：在 `TopRowRenderProbe`
 * 那张 3 倍图上，24 个字 + 省略号 + 展开三角占到 x≈328，而「＋」在 x≈383 ——
 * 中间还剩 55px。先把 20 试着画了一遍，右边空着一大截，才加到 24。
 *
 * 列表行比它宽，但也用同一个数 —— 两处显示的名字必须是同一个，
 * 不然就成了"列表里叫 A、顶上写 B"。
 *
 * 用户自己起的名字（`customTitle`）也走这一条：标题的规则只有一个。
 */
internal fun titleSnippet(text: String): String {
    val flat = text.replace(WHITESPACE_RUN, " ").trim()
    return if (flat.length <= SESSION_TITLE_MAX) flat else flat.take(SESSION_TITLE_MAX) + "…"
}

/**
 * 列表行的标题。firstPrompt 优先，退回 summary，都没有给占位。
 *
 * 空白行看起来像渲染坏了，不如直说。列表行与删除确认语共用这一个 ——
 * 两处各写一遍，改一处漏一处，确认语里说的名字就会和那一行显示的不是同一个。
 */
internal fun sessionTitle(session: SessionInfo): String =
    sessionLabelTitle(session) ?: CcoderText.text("session.untitled")

/**
 * 会话标签上的名字：有标题给标题，没有给 null。
 *
 * 顺序是 **customTitle > firstPrompt > summary**（2026-09-15 用户要求）。
 *
 * **用户自己起的名字压过一切** —— 改名之后回到列表还显示那句自动摘要，
 * 那个改名就白改了。
 *
 * 自动那一路**自己说的第一句优先**，与 [titleFromFirstMessage] 是同一条规矩
 * （用户当时的原话：「会话的标题应该用聊天的第一个字」）。而 `summary` 是
 * **CLI 给的**、并且可能随着会话内容变化 —— 那样名字会飘，认不出是哪条会话。
 * 自己说的第一句是稳的。
 *
 * 与 [sessionTitle] 分开，是因为"没有标题"在两处的含义不同：列表行需要一个
 * 占位（空白行看起来像渲染坏了），标签需要的是 null —— 它显示的是斜体的
 * 「新会话」。给标签一个「（无标题）」的话，打开一个无标题的会话，标签上会
 * 出现一个看着像真标题的东西。
 */
internal fun sessionLabelTitle(session: SessionInfo): String? =
    session.customTitle?.takeIf { it.isNotBlank() }?.let(::titleSnippet)
        ?: session.firstPrompt?.takeIf { it.isNotBlank() }?.let(::titleSnippet)
        ?: session.summary?.takeIf { it.isNotBlank() }?.let(::titleSnippet)

/**
 * 刚发出去的那条消息要不要认成会话标题。null = 不认。
 *
 * 用户原话（2026-09-15）：「会话的标题应该用聊天的第一个字」。在此之前
 * **全新会话的标签一直是斜体的「新会话」**，聊一小时也还是它（那是刻意的，
 * 见 `ClaudePanel.sendCurrentInput` 里那段说明）—— 等于没有标题。
 *
 * 两条不认：
 *  - **已经有标题了**（恢复的会话、或者这条会话已经发过消息）—— 第一条才算数
 *  - **斜杠命令** —— 标题会变成 `/help` 这种对不上号的东西，而它根本不是聊天内容
 */
internal fun titleFromFirstMessage(text: String, current: String?): String? =
    if (current != null || text.startsWith("/")) null else titleSnippet(text)

/**
 * 第一次上屏要不要恢复"最近那条会话"。
 *
 * 只有**开工具窗口时建的那个面板**该恢复（用户要的是"接着上次聊"）；
 * 点「＋」开出来的标签必须是**空的** —— 那个面板的"第一次上屏"是它被创建的那一刻，
 * 与"用户第一次打开这个工具窗口"不是一回事。
 *
 * 2026-09-16 的 bug 就在这儿：`＋` 出来的标签"第一次上屏"也成立，于是它悄悄
 * resume 了最近那条会话，用户截图来问"新建会话不应该是空的吗"。
 */
internal fun resumeOnFirstShow(firstShow: Boolean, openedByPlus: Boolean): Boolean =
    firstShow && !openedByPlus

/**
 * 打开面板时该恢复哪一条：修改时间最新的那条；没有历史会话时给 null
 * （调用方据此退回"开新会话"）。
 *
 * 用 `maxByOrNull` 而不是 `first()` —— 实测 SDK 是新的排在上面，但它的文档
 * 只说了返回会话元信息，**没有承诺排序**。哪天顺序变了，`first()` 会安静地
 * 恢复错的那一条，而界面上看不出任何异常。
 *
 * 并列（两次改动落在同一毫秒）时取列表里靠前的那条 —— `maxByOrNull` 只在
 * 严格更大时才替换，规则是确定的。
 */
internal fun mostRecentSession(
    sessions: List<SessionInfo>,
    /**
     * 这条会话是不是已经被别的标签占着（[OpenSessions]）。被占的**跳过** ——
     * 两条标签开同一条会话，两边会同时写同一个 jsonl（见那个类的说明）。
     *
     * 默认恒 false：老调用点与老用例逐字段不变。
     */
    isTaken: (String) -> Boolean = { false },
): SessionInfo? = sessions.filterNot { isTaken(it.sessionId) }.maxByOrNull { it.lastModified }

/**
 * 打开面板时，从 `listSessions` 的回执里读出的结论。
 *
 * 三种结局的**区别在界面动作上**：前两种都开新会话，但只有 [Unavailable]
 * 要说一句。抽出来是因为"空列表"和"问不出来"在这里长得很像
 * （都是"没挑出会话"），而混起来的后果正好相反 —— 要么每开一个新项目都
 * 报一次并不存在的错误，要么把一次真的超时静默吞掉。
 */
internal sealed interface OpenPick {
    /** 恢复这一条。 */
    data class Resume(val session: SessionInfo) : OpenPick

    /** 没有历史会话。开新会话，**不提示** —— 首次使用就是这样，什么都没出错。 */
    data object None : OpenPick

    /** 问不出来。开新会话，但要跟用户说一句，别让他以为"上次那条不见了"。 */
    data class Unavailable(val reason: String) : OpenPick
}

/** [OpenPick] 的读法。见它的说明。 */
internal fun openPick(
    outcome: RequestOutcome,
    /** 跳过已被别的标签占住的会话（见 [mostRecentSession]）。 */
    isTaken: (String) -> Boolean = { false },
): OpenPick = when (outcome) {
    is RequestOutcome.Failed -> OpenPick.Unavailable(outcome.reason)

    is RequestOutcome.Answered -> {
        val msg = outcome.message as? SidecarMessage.SessionList
        if (msg == null) {
            OpenPick.Unavailable(CcoderText.text("session.list.unexpected"))
        } else {
            mostRecentSession(msg.sessions, isTaken)?.let { OpenPick.Resume(it) } ?: OpenPick.None
        }
    }
}

/**
 * 「＋」能不能点。
 *
 * **2026-09-16 语义变了**：多标签之后它只是"再加一个标签"，不再 `stopSession()` ——
 * 手上这条会话继续跑，正在进行的回合与挂着的权限询问都不受影响。所以"忙"与
 * "有待决权限"**都不再是理由**，唯一的闸是标签数到上限。
 *
 * 旧的那对谓词（`newSessionEnabled(SwitchBlock)` / `newSessionTooltip(SwitchBlock)`）
 * 随这次改动删掉：留着会让下一个人以为"忙时不能新建"还是条规矩。
 */
internal fun newTabEnabled(tabCount: Int, max: Int = MAX_SESSION_TABS): Boolean =
    tabCount < max

/**
 * 「＋」的提示语。
 *
 * 能点时说明它做什么；到上限时说明**先做什么** —— 光说"不能新建"没用。
 */
internal fun newTabTooltip(tabCount: Int, max: Int = MAX_SESSION_TABS): String =
    if (tabCount >= max) CcoderText.text("session.tabs.limit", max) else CcoderText.text("session.tabs.new")

/**
 * `init` 带进来的 session id 是否意味着**换了会话**（`/clear` 走这条路）。
 *
 * 两条都必须判：
 *  - **不能靠"收到 init"** —— 实测 init 每个回合都发一次（设计稿 §2 事实 6），
 *    那样每回合都会清一次转写区。
 *  - **`current` 为 null 时不算** —— 全新会话的第一个 init 就是这种情况。
 *    那时该做的是"填上 id"，不是"清空转写区"：用户刚看到「会话已就绪」，
 *    紧接着转写区被清空会像是崩了。
 *
 * 真正的切换信号是同一个进程里 id **变了**（实测 `/clear`，设计稿 §10.4）。
 */
internal fun isSessionSwitch(current: String?, incoming: String?): Boolean =
    current != null && incoming != null && current != incoming

/**
 * 删除的确认语。
 *
 * 删当前会话时**不写"要不要删"，写"删了会怎样"** —— 用户已经知道自己点了
 * 哪一行，他需要知道的是后果：转写区会清空、回到新会话。
 *
 * 注意这里不负责截断：行宽由布局决定，长标题在组件里省略。
 */
internal fun deleteConfirmPrompt(session: SessionInfo, isCurrent: Boolean): String =
    if (isCurrent) {
        CcoderText.text("session.delete.confirmCurrent")
    } else {
        CcoderText.text("session.delete.confirmTitle", sessionTitle(session))
    }

/**
 * 「清空全部」的确认语。
 *
 * 三件事要说清：**几条**、**哪几条不会删**（[keptCount] 条正被标签跑着）、
 * 以及列表被截断时"更早的也会删"。
 *
 * ## 为什么这么短（2026-09-17 出图之后改的）
 *
 * 这一句与两颗按钮**挤在同一行**里 —— 而弹层的尺寸是**打开那一刻定死的**
 * （`showTogglePopup` 到 `createComponentPopupBuilder`，`setResizable(false)`），
 * 长出来的那部分直接看不见。试过"问句自己占一行、按钮另起一行"，出图那一版
 * 因为那一行变高被挤成了负数高度（图上就是缺一块）。
 *
 * 所以：**必须在一行里放下**，剩下的交给另外两处 ——
 * 入口那颗的 tooltip 写全了范围（"清空这个项目的历史会话"），
 * 结果那句话（[clearAllResultText]）里也再说一遍。
 */
internal fun clearAllConfirmPrompt(count: Int, keptCount: Int, moreThanListed: Boolean): String {
    // 截断时**不能报条数**：列出来的 50 条不是要删的全部，报了就是个错数
    val scope = if (moreThanListed) CcoderText.text("session.clear.scopeAll") else CcoderText.text("session.clear.scopeCount", count)
    return if (keptCount > 0) scope + " " + CcoderText.text("session.clear.keptTail", keptCount) else scope
}

/**
 * 清空之后那句话。
 *
 * **保留了几条必须说出来**（[kept]）：不说的话，用户数一遍列表发现还剩几条，
 * 会读成"没删干净"；而它其实是安全约束 —— 那几条正被标签跑着。
 *
 * 一条都没删也要说出来，且原因不同（[deleted] 为 0 而 [kept] 非 0 是"都被占着"，
 * 静默的话用户会以为点了没反应）。
 */
internal fun clearAllResultText(deleted: Int, kept: Int): String = when {
    deleted == 0 && kept > 0 -> CcoderText.text("session.clear.doneNone", kept)
    kept > 0 -> CcoderText.text("session.clear.doneKept", deleted, kept)
    else -> CcoderText.text("session.clear.doneAll", deleted)
}

/**
 * 有没删掉的那些时的错误项。
 *
 * 条数要说（"1 条"与"8 条"是两件事），原因只说第一条 —— 一批删除里
 * 通常只有一个原因（磁盘只读、文件被占），把 N 条原因全铺开反而看不清。
 */
internal fun clearAllFailedText(failed: List<ClearFailure>): String {
    val reason = failed.firstOrNull()?.reason ?: CcoderText.text("session.clear.unknownReason")
    return if (failed.size == 1) {
        CcoderText.text("session.clear.failedOne", reason)
    } else {
        CcoderText.text("session.clear.failedMany", failed.size, reason)
    }
}

