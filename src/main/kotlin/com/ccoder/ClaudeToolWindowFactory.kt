package com.ccoder

import com.ccoder.ui.SessionTabs
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * 工具窗口工厂。
 *
 * **实现了 [DumbAware]，这一条是必需的**：平台对没声明它的工具窗口内容，
 * 会在索引期间把整块内容换成一句占位文案
 * （`empty.text.this.view.is.not.available.until.indices.are.built`，
 * 中文语言包显示为"在构建索引前，此视图不可用"）。
 *
 * 我们面板的主体不依赖索引 —— 转写区、权限卡、状态卡都是自绘的，跟 IDE 的
 * 索引无关。被换成占位的话，用户正打字时会话整个被抽走，比"补全晚几秒"难受得多。
 * 唯一碰索引的是 @ 文件补全（`ProjectFileIndex`），那一处自己挡了 dumb 态，
 * 见 `ClaudePanel.refreshCompletion`。
 *
 * 索引在本地项目里是**常态**而不是例外：Gradle 构建、`npm install`、
 * 各种插件解包 node_modules 都会触发重建索引。
 */
class ClaudeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 面板的创建、上限、关闭确认、标题都归 SessionTabs —— 这里只负责"把容器接上、
        // 开第一批标签"（有存档就照存档开，见 openInitialTabs）。面板自己仍然只吃
        // 一个 project（见 SessionTabs 的类注释）
        SessionTabs.getInstance(project).apply { attach(project, toolWindow) }.openInitialTabs()
    }
}
