package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey

/**
 * 数据上下文的 key：**谁是"能收粘贴的输入框"**，值是"把这一次粘贴收下"的回调。
 *
 * 两边都要看得见：输入框把它放进上下文（见 [ComposerTextArea.uiDataSnapshot]），
 * Ctrl+V 那个动作和它的 promoter 读它。所以是顶层 `val`，不是谁私有的。
 */
val COMPOSER_PASTE_ATTACH: DataKey<() -> Unit> = DataKey.create("ccoder.composerPasteAttach")

/**
 * Ctrl+V：贴图（截图进附件带）与"粘贴文件变 `@` 引用"共用这一个动作（2026-09-24）。
 *
 * ## 为什么最后是自己占住 Ctrl+V
 *
 * IDE 里 Ctrl+V 的画面是：keymap 的 `$Paste` → `com.intellij.ide.actions.PasteAction`
 * → 从**数据上下文**取 `PasteProvider` 再调。2026-09-15 那天把这条路走到了底：
 *
 * - `TransferHandler`：装上了，日志里连判定都没有 —— 平台根本不走 Swing 那条（✗）
 * - `DataProvider`：平台一次都没问过（要 `UiCompatibleDataProvider` ✓ 见下）
 * - `UiCompatibleDataProvider` + `PasteProvider`：**平台来问了、判断也对**
 *   （日志有「把 PASTE_PROVIDER 交给平台」），可按下 Ctrl+V 时那个动作没有被执行
 *   —— 焦点稍不在输入框就轮不到我们
 *
 * 与其继续猜平台的上下文，不如自己把 Ctrl+V 占住：**[update] 只在"焦点在输入框、
 * 且剪贴板里有文件或只有图"时让它可见可用** —— 其余时候它根本不是候选，Ctrl+V 交给
 * 平台原来那套，文字粘贴一个字不变（这也是为什么必须做成"只在那种时候可见"，
 * 而不是无脑抢快捷键）。
 *
 * 平台那个 `PasteProvider` 的路子留着没删：两条都在，谁先到都能用。
 */
internal class ComposerPasteAction(
    /** 抽出来只为可测：真机上问的是平台剪贴板（外加项目根，纯文本路径那条要用）。 */
    private val takeover: (com.intellij.openapi.project.Project?) -> Boolean = ::clipboardTakeoverFor,
) : AnAction() {

    /** 这个动作碰剪贴板与组件状态 —— 必须在 EDT 上问。 */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            shouldOfferPaste(
                hasAttachTarget = e.getData(COMPOSER_PASTE_ATTACH) != null,
                takeover = takeover(e.project),
            )
        // 同 AddSelectionToChatAction：这一句才让设置里的「界面语言」生效
        e.presentation.setText(CcoderText.text("action.paste.text"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(COMPOSER_PASTE_ATTACH)?.invoke()
    }
}

/**
 * 真机上的判据：剪贴板里有文件清单 / 只有图 / 那段文本是一串真实存在的文件路径
 * （最后一条要项目根，见 [projectFileResolver]）。
 */
internal fun clipboardTakeoverFor(project: com.intellij.openapi.project.Project?): Boolean =
    clipboardPasteTakeover(projectFileResolver(project))

/**
 * 这个动作该不该出现。
 *
 * 抽成纯函数是为了可测：`AnActionEvent` 要 Application，单测里没有；而这条规则
 * 是安全绳（见 [ComposerPasteAction] 的说明），必须能被钉住。
 *
 * [takeover] 由 [clipboardPasteTakeover] 算出来（有文件、或只有图）—— 那一位
 * 真机上要问剪贴板，不在这一层。
 */
internal fun shouldOfferPaste(hasAttachTarget: Boolean, takeover: Boolean): Boolean =
    hasAttachTarget && takeover

/**
 * 同一击键上有两个候选时（平台的 `$Paste` 和我们这个），把我们这个排前面。
 *
 * 同一条快捷键绑了两个动作时，平台按 keymap 里的顺序挑第一个"可用的"——
 * 那个顺序不是我们能说了算的（`$Paste` 是平台自己绑的）。`ActionPromoter`
 * 就是干这个的官方口子：只在自己的上下文里提升自己的动作，其余情况返回空表，
 * 顺序原样不动。
 */
internal class ComposerPastePromoter : ActionPromoter {

    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        // 焦点不在我们的输入框上：什么都不改（Ctrl+V 归平台）
        if (context.getData(COMPOSER_PASTE_ATTACH) == null) return emptyList()
        return actions.filterIsInstance<ComposerPasteAction>()
    }
}
