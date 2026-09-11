package com.ccoder

import com.ccoder.ui.ClaudePanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ClaudeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClaudePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)

        // 关闭时按 spec §7.4 的顺序清理进程树，否则 claude 会变孤儿继续消耗额度
        Disposer.register(content) { panel.dispose() }
    }
}
