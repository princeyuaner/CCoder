package com.ccoder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

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
        val count = PendingPermissionCount.getInstance(project).count
        return if (count > 0) "Claude 待确认：$count" else ""
    }

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String =
        "有 ${PendingPermissionCount.getInstance(project).count} 个授权请求在等待处理，点击前往"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    private companion object {
        const val TOOL_WINDOW_ID = "CCoder"
    }
}
