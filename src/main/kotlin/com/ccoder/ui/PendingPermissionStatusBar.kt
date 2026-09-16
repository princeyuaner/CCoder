package com.ccoder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * 状态栏的待决权限计数（spec §6.3）。
 *
 * 非模态卡片的核心风险是"用户没注意 → Claude 无限等待"，而 SDK 的
 * 权限询问没有超时机制（"no park deadline"，spec §6.2 规则①）。
 * 状态栏常驻是让待决状态无法被忽略的第一道补偿。
 *
 * 计数来自 [PendingPermissionCount] 服务而非面板直接推送 ——
 * 平台会按需创建/销毁状态栏组件，订阅模式对两端的生命周期都无所谓。
 */
class PendingPermissionStatusBarFactory : StatusBarWidgetFactory {

    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "CCoder 待确认权限"
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget =
        PendingPermissionStatusBar(project)

    companion object {
        const val WIDGET_ID = "CCoderPendingPermissions"
    }
}

class PendingPermissionStatusBar(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private var listener: (() -> Unit)? = null

    override fun ID(): String = PendingPermissionStatusBarFactory.WIDGET_ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val onCountChanged = { statusBar.updateWidget(ID()) }
        listener = onCountChanged
        PendingPermissionCount.getInstance(project).addListener(onCountChanged)
    }

    override fun dispose() {
        listener?.let { PendingPermissionCount.getInstance(project).removeListener(it) }
        listener = null
        statusBar = null
    }

    override fun getText(): String {
        val service = PendingPermissionCount.getInstance(project)
        return statusBarText(service.count, service.askSuspended)
    }

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String {
        val service = PendingPermissionCount.getInstance(project)
        return statusBarTooltip(service.count, service.askSuspended)
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        val service = PendingPermissionCount.getInstance(project)
        val restore = service.restoreAsk
        // 挂起提问优先：这一行此刻说的是"有提问待回答"，点它却去开工具窗口
        // 就牛头不对马嘴了 —— 用户要找的是他刚才收起来的那个框。
        if (service.askSuspended && restore != null) {
            // 多标签：先切到**登记它的那个标签**再回去 —— 否则用户会被送到当前标签，
            // 而那个框在另一个标签里
            (service.restoreOwner as? JComponent)?.let { owner ->
                SessionTabs.getInstance(project).selectOwner(owner)
            }
            restore()
        }
        else ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }
}

/**
 * 状态栏那一行字。纯函数，好单测 —— 组件本身要真实 Project 才活得起来。
 *
 * 挂起提问**优先于计数**：被最小化的那条提问本来就计在 [pending] 里（提问也走
 * PermissionQueue），但"待确认：1"说不清点下去会发生什么。
 */
internal fun statusBarText(pending: Int, askSuspended: Boolean): String = when {
    askSuspended -> "Claude 有提问待回答"
    pending > 0 -> "Claude 待确认：$pending"
    else -> ""
}

/**
 * 悬停说明。口径要准：队列里既有授权请求也有提问，只说"授权请求"是错的
 * （2026-09-15 之前就是这么写的，提问混在里面）。
 */
internal fun statusBarTooltip(pending: Int, askSuspended: Boolean): String = when {
    askSuspended -> "有一个提问被最小化，等着你回答 —— 点一下回到那个框"
    pending > 0 -> "有 $pending 个请求在等待处理（授权 / 提问），点击前往"
    else -> ""
}
