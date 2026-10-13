package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.ccoder.text.CcoderText
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「本轮改动」卡：网页发起的那两条 op 的落点（2026-10-13）。
 *
 *  - `openTurnDiff` —— 点文件行：在 IDE 的对比视图里打开「本轮改动前 ↔ 当前」。
 *    左侧内容是**侧车快照**（[SidecarMessage.FileSnapshotReport]），右侧是磁盘上的
 *    当前文件。看与回滚因此同源：都是同一份存档。
 *  - `revertFiles` —— 卡上勾选后回滚：转发侧车，逐文件回执回推给网页
 *    （[com.ccoder.sidecar.TranscriptOp.RevertReport]）并追加一条系统提示。
 *
 * 前半段（解析桥消息）是纯函数，能起单测；后半段碰 VFS / 编辑器 / DiffManager，
 * 起不了 —— 与 [OpenFileTarget] 同一条拆法（同 [FileCandidates] 的理由）。
 *
 * 行号在这里**没有戏份**：对比是整文件的，不需要定位。
 */
internal data class TurnDiffRequest(val path: String)

/**
 * 从桥消息里读出一次「打开对比」请求。识别不出给 null，调用方不动作。
 *
 * 形状：`{"op":"openTurnDiff","path":"..."}`。
 */
internal fun parseTurnDiffRequest(obj: JsonObject): TurnDiffRequest? {
    val path = obj.str("path")?.takeIf { it.isNotBlank() } ?: return null
    return TurnDiffRequest(path)
}

/**
 * 从桥消息里读出要回滚的路径列表。
 *
 * 形状：`{"op":"revertFiles","paths":["...","..."]}`。空列表 = null（一条都没有的
 * 回滚没有意义，调用方据此不动作）；列表里非字符串/空串的条目**跳过**，不让一条
 * 坏数据废掉整次请求。
 */
internal fun parseRevertPaths(obj: JsonObject): List<String>? {
    val arr = obj.get("paths")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val paths = arr.mapNotNull { el ->
        el.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    }
    return paths.ifEmpty { null }
}

/**
 * 网页那两条 op 要落到面板上做的事 —— 转写视图只转交，不自己够侧车。
 *
 * 为什么做成接口而不是让视图直接持有面板：视图与面板已经互相认识（视图是面板
 * 建的），再加一条"视图 → 面板"的字段依赖，会让测试与拆装都多一处顺序问题；
 * 一个 setter 注入的小接口，谁建谁设（同 `StatusCardView` 那类先例）。
 */
internal interface TurnChangesHost {
    fun openTurnDiff(req: TurnDiffRequest)
    fun revertFiles(paths: List<String>)
}

/**
 * 打开一个文件的「本轮改动前 ↔ 当前」对比。
 *
 * - `kind=file`：左 = 快照文本（只读），右 = 磁盘上的当前文件（映射到 IDE 的对比视图）
 * - `kind=absent`：改动前这个文件**不存在**（本轮新建）—— 左 = 空，全绿
 * - `kind=missing`：没有存档 —— 弹一句原因，并把文件本身打开（不静默，也不空点）
 */
internal fun showTurnDiff(project: Project, report: SidecarMessage.FileSnapshotReport) {
    if (report.kind == "missing") {
        notify(
            project,
            CcoderText.text("file.diff.noSnapshotTitle"),
            CcoderText.text("file.diff.noSnapshotBody"),
            NotificationType.INFORMATION,
        )
        openInEditor(project, OpenFileTarget(report.path, null))
        return
    }

    val before = if (report.kind == "absent") "" else report.content.orEmpty()
    val leftTitle = if (report.kind == "absent") {
        CcoderText.text("file.diff.absent")
    } else {
        CcoderText.text("file.diff.before")
    }

    // 线程：桥回调来自 CEF 线程，VFS 那半进池化线程、碰编辑器与 diff 一律回 EDT
    //（同 openInEditor）。刷新是同步的，不能在 EDT 上跑。
    ApplicationManager.getApplication().executeOnPooledThread {
        val absolute = resolveAbsolutePath(report.path, project.basePath, SystemInfo.isWindows)
        val file = absolute?.let { path -> runCatching { Path.of(path) }.getOrNull() }
            ?.let(::findRefreshed)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (file == null || file.isDirectory) {
                notify(
                    project,
                    CcoderText.text("file.open.failedTitle"),
                    CcoderText.text("file.open.notFound", absolute ?: report.path),
                    NotificationType.WARNING,
                )
                return@invokeLater
            }
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                report.path,
                // 左侧文本给上文件类型：语法高亮跟着右侧文件走，一眼能读
                factory.create(project, before, file.fileType),
                factory.create(project, file),
                leftTitle,
                CcoderText.text("file.diff.current"),
            )
            DiffManager.getInstance().showDiff(project, request)
        }
    }
}

/**
 * 回滚落地之后把 IDE 里的视图拉回真身。
 *
 * 侧车是**用 Node 直接写盘**的（它手里才有快照），所以这里要替用户把 VFS 刷新：
 * 存在的文件刷新它自己；被删掉的（本轮新建 → 回滚即删）刷新它所在的目录 ——
 * 不然项目树里还会挂着一个幽灵文件。
 *
 * 同步刷新只能在 EDT 之外跑（平台会断言），所以整段进池化线程。
 */
internal fun refreshAfterRevert(project: Project, paths: List<String>) {
    if (paths.isEmpty()) return
    ApplicationManager.getApplication().executeOnPooledThread {
        val fs = LocalFileSystem.getInstance()
        val existing = mutableListOf<Path>()
        val dirs = mutableListOf<Path>()
        for (raw in paths) {
            val absolute = resolveAbsolutePath(raw, project.basePath, SystemInfo.isWindows) ?: continue
            val nio = runCatching { Path.of(absolute) }.getOrNull() ?: continue
            if (nio.toFile().exists()) existing.add(nio) else nio.parent?.let(dirs::add)
        }
        val targets = (existing + dirs).distinct()
        if (targets.isEmpty()) return@executeOnPooledThread
        val latch = CountDownLatch(1)
        fs.refreshNioFiles(targets, false, false) { latch.countDown() }
        // 刷新是尽力而为：超时只影响这一次显示，下一次文件事件会补上
        latch.await(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
}

/**
 * 回滚之后的系统提示文案。
 *
 * [success] 与 [failed] 都来自侧车的逐文件回执 —— 不来自"我们发了几个"。
 * 三种样子：全成 / 部分败 / 全败，全都说得出个数（不静默）。
 */
internal fun revertNoteText(success: Int, failed: Int): String = when {
    failed == 0 -> CcoderText.text("chat.note.reverted", success)
    success == 0 -> CcoderText.text("chat.note.revertAllFailed", failed)
    else -> CcoderText.text("chat.note.revertedPartial", success, failed)
}

/** 打不开时说一句。调用方可能在任何线程（桥回调/侧车读线程），这里统一回 EDT。 */
private fun notify(
    project: Project,
    title: String,
    body: String,
    type: NotificationType,
) {
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, body, type)
            .notify(project)
    }
}

/** 刷新等待上限（同 [REFRESH_TIMEOUT_MS] 一族）：超时只影响这一次显示。 */
private const val REFRESH_TIMEOUT_MS = 2000L

private const val NOTIFICATION_GROUP = "CCoder"

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
