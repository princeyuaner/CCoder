package com.ccoder.ui

import com.ccoder.settings.showSettingsDialog
import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncService
import com.ccoder.sync.SyncSnapshot
import com.ccoder.sync.SyncStatus
import com.ccoder.sync.SyncTexts
import com.ccoder.text.CcoderText
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 状态栏上那一段同步状态。
 *
 * ## 它顺带是**同步的启动器**
 *
 * 注解式服务的实例化是惰性的，得有人先取它一次。这里在 [SyncStatusBar.install] 里取
 * [SyncService] —— 而状态栏组件是项目打开时平台就会创建的（`PendingPermissionCount`
 * 的显示依赖同一件事，这是仓库里既有的、已经跑通的路）。于是"打开项目就开始同步"
 * 这条链是通的，不必在 `plugin.xml` 里加一个我一时验不了的启动钩子。
 *
 * ## 显示口径
 *
 * **没配置就什么都不显示**（不占状态栏），配好了才出现。
 * 状态栏塞不进一句话解释，所以详情全在 tooltip 里，点击打开设置。
 */
class SyncStatusBarFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = CcoderText.text("sync.status.widgetName")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = SyncStatusBar(project)

    companion object {
        const val WIDGET_ID = "CCoderSync"
    }
}

class SyncStatusBar(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var listener: (() -> Unit)? = null

    override fun ID(): String = SyncStatusBarFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar

        // 顺带把同步服务拉起来 —— 见类头注。**必须在 install 里做**：这是项目打开之后
        // 我们确定会被叫到的一处。
        SyncService.getInstance(project)

        val onChanged = { statusBar.updateWidget(ID()) }
        listener = onChanged
        SyncStatus.getInstance(project).addListener(onChanged)
    }

    override fun dispose() {
        listener?.let { SyncStatus.getInstance(project).removeListener(it) }
        listener = null
        statusBar = null
    }

    override fun getText(): String = syncStatusBarText(snapshot())

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String = syncStatusBarTooltip(snapshot())

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        // 点开设置。对话框总是开在第一页（`pages.first()`），到同步那一页还得点一下页签 ——
        // 平台没给"打开到指定页"的口子，不值得为这个改对话框的骨架。
        showSettingsDialog(project)
    }

    private fun snapshot(): SyncSnapshot = SyncStatus.getInstance(project).snapshot
}

/**
 * 状态栏那一行字。纯函数，好单测（同 `statusBarText`）。
 *
 * **没配置（或者停了）返回空串** —— 不占状态栏。一个永远显示"同步：未配置"的组件
 * 只是噪音：没配置的人根本不关心这个功能存在。
 */
internal fun syncStatusBarText(snapshot: SyncSnapshot): String = when (snapshot.run) {
    SyncRun.DISABLED, SyncRun.STOPPED -> ""
    SyncRun.OCCUPIED -> CcoderText.text("sync.status.occupied")
    SyncRun.RUNNING -> CcoderText.text("sync.status.on")
    SyncRun.FAILED -> CcoderText.text("sync.status.failed")
}

/** tooltip：状态栏塞不下的那句解释放这儿。 */
internal fun syncStatusBarTooltip(snapshot: SyncSnapshot): String = when (snapshot.run) {
    SyncRun.DISABLED, SyncRun.STOPPED ->
        CcoderText.text("sync.status.offTip", SyncTexts.problemText(snapshot.problem))

    SyncRun.OCCUPIED ->
        CcoderText.text("sync.status.occupiedTip", snapshot.occupiedBy ?: "")

    SyncRun.RUNNING, SyncRun.FAILED -> {
        val at = snapshot.lastRoundAtMs
        if (at == null) {
            CcoderText.text("sync.status.notYetTip")
        } else {
            CcoderText.text(
                "sync.status.onTip",
                clockOf(at),
                snapshot.copied,
                snapshot.deleted,
                snapshot.failed.size,
            ) + if (snapshot.run == SyncRun.FAILED) "\n" + CcoderText.text("sync.status.failedTip") else ""
        }
    }
}

/** `HH:mm:ss`。只用于显示，不做时区转换之外的事。 */
private fun clockOf(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
