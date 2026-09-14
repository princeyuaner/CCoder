package com.ccoder.ui

import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 转写区里"点文件名 → 在编辑器里打开"这条链路。
 *
 * 前半段（解析与路径判定）是纯函数，能起单测；后半段 [openInEditor] 碰
 * 平台的 VFS 与编辑器，起不了（项目里没有 BasePlatformTestCase，同
 * [FileCandidates] / [AddSelectionToChat] 的拆分理由）。
 *
 * 行号只有一处基准换算：[OpenFileTarget.line] 是**1 基**（与前端送来的
 * Claude 的 `offset` 同基准），减一只在 [lineIndex] 里做一次。
 */
internal data class OpenFileTarget(val path: String, val line: Int?)

/**
 * 从桥消息里读出一次打开请求。识别不出给 null，调用方不动作。
 *
 * 形状：`{"op":"openFile","path":"...","line":120}`，`line` 可以没有。
 * `line` 不是正经行号（非数字、0、负数）时**当没有行号**处理，而不是
 * 丢掉整个请求 —— 路径是对的，能打开文件本身就有用。
 */
internal fun parseOpenFileTarget(obj: JsonObject): OpenFileTarget? {
    val path = obj.str("path")?.takeIf { it.isNotBlank() } ?: return null
    val line = obj.num("line")?.toInt()?.takeIf { it >= 1 }
    return OpenFileTarget(path, line)
}

/**
 * 行号基准换算：1 基 → `OpenFileDescriptor` 要的 0 基。
 *
 * 单独抽出来是因为"差一行"是这类改动最经典的 bug，而它值得一条独立用例。
 */
internal fun lineIndex(line: Int?): Int = if (line == null || line < 1) 0 else line - 1

/**
 * 把工具给的路径解析成绝对路径（全正斜杠）。解析不出来给 **null**。
 *
 * 输入的真实形状（都在实测里出现过）：`C:\p\a.kt`、`C:/p/a.kt`、
 * `/c/Users/CY/a.kt`（Git Bash 风格）、`web/src/App.tsx`（项目内相对）、
 * `\\srv\share\a.kt`（UNC）。
 *
 * 认不出来时**宁可返回 null，也不猜**：在 Windows 上把 `/etc/hosts`
 * 当成项目内的相对路径，会把用户送到一个毫不相干的文件里 ——
 * 弹一句"打不开"（再点一次就好）比静默开错地方强得多。
 */
internal fun resolveAbsolutePath(raw: String, basePath: String?, isWindows: Boolean): String? {
    val path = collapse(raw.trim().replace('\\', '/'))
    if (path.isEmpty()) return null

    if (isWindows) {
        // 盘符有两种写法：C:/... 与 Git Bash 的 /c/...
        drivePath(path)?.let { return it }
        // UNC（\\srv\share → //srv/share）是绝对路径
        if (path.startsWith("//")) return path
        // 其余的前导 / 不猜盘符：见上面"不猜"那条
        if (path.startsWith("/")) return null
    } else if (path.startsWith("/")) {
        return path
    }

    val base = basePath?.let { collapse(it.replace('\\', '/')) }?.takeIf { it.isNotEmpty() }
        ?: return null
    // 拼完再折一次：相对路径里的 `..` 会翻出 base 之外，而不带折好的 `..`
    // 的路径在 VFS 里查不到（VFS 按规范化后的路径建索引）
    return collapse("$base/$path")
}

/** 折掉 `.` 与 `..` 段（`/a/./b/../c` → `/a/c`）。 */
private fun collapse(path: String): String {
    val absolute = path.startsWith("/")
    // UNC 的前导 `//` 必须原样保住，否则会被折成一个相对路径
    val unc = path.startsWith("//")

    val segments = mutableListOf<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit

            ".." -> when {
                // 绝对路径折到根就到头了，再往上没有意义
                segments.isNotEmpty() && segments.last() != ".." -> segments.removeAt(segments.size - 1)
                !absolute && !unc -> segments.add("..")
                else -> Unit
            }

            else -> segments.add(segment)
        }
    }

    val body = segments.joinToString("/")
    return when {
        unc -> "//$body"
        absolute -> "/$body"
        else -> body
    }
}

/**
 * `C:/...` 与 Git Bash 风格的 `/c/...` → 盘符路径；都不是给 null。
 *
 * `/c/` 那条只影响 Windows：Claude 在 Git Bash 下报出来的路径就是那个形状。
 */
private fun drivePath(path: String): String? {
    if (path.length >= 3 && path[0].isLetter() && path[1] == ':' && path[2] == '/') return path
    if (path.length >= 3 && path[0] == '/' && path[1].isLetter() && path[2] == '/') {
        return path[1].uppercaseChar() + ":" + path.substring(2)
    }
    return null
}

/**
 * 在编辑器里打开，定位到 [OpenFileTarget.line]。
 *
 * 线程：桥的回调来自 CEF 线程，**不保证是 EDT**（同 `handleFromJs` 里
 * `ready` 分支的处境）。所以这里不猜自己在哪条线程上：
 * VFS 那半进池化线程，碰编辑器与气球一律回 EDT。
 */
internal fun openInEditor(project: Project, target: OpenFileTarget) {
    val absolute = resolveAbsolutePath(target.path, project.basePath, SystemInfo.isWindows)
    if (absolute == null) {
        warn(project, "这个路径认不出来", target.path)
        return
    }

    ApplicationManager.getApplication().executeOnPooledThread {
        val nio = runCatching { Path.of(absolute) }.getOrNull()
        val file = nio?.let(::findRefreshed)

        ApplicationManager.getApplication().invokeLater {
            // 面板可能已经被关掉/项目已关闭 —— 那时再导航是往虚空里点
            if (project.isDisposed) return@invokeLater
            when {
                file == null -> warn(project, "文件还不存在或已被移动", absolute)
                file.isDirectory -> warn(project, "这是个目录，打不开编辑器", absolute)
                else -> OpenFileDescriptor(project, file, lineIndex(target.line), 0).navigate(true)
            }
        }
    }
}

/**
 * 刷新之后再找文件。
 *
 * 为什么必须刷新：Claude 刚用 Write 建出来的文件，VFS 可能还不知道它存在 ——
 * 那一刻点开就会得到一句"文件不存在"，而文件就在磁盘上。
 *
 * 刷新是**同步**的（`async = false`），所以这一条只能在 EDT 之外跑：
 * 平台在 EDT 上会直接断言失败。latch 是超时兜底 —— 最坏的结果是这一次
 * 没刷新到（界面提示文件不存在，再点一下就成），而不是永久占住一个池化线程。
 */
private fun findRefreshed(nio: Path): VirtualFile? {
    val fs = LocalFileSystem.getInstance()
    val latch = CountDownLatch(1)
    fs.refreshNioFiles(listOf(nio), false, false) { latch.countDown() }
    latch.await(REFRESH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return fs.findFileByNioFile(nio)
}

/**
 * 打不开时说一句。
 *
 * 不能只写日志：点了没反应与"这个功能坏了"在屏幕上完全一样。
 * 用**非粘性**气球（`CCoder` 组，见 plugin.xml）—— 这是"这一次没打开"，
 * 不是需要用户处理的待办，留着撕不掉的气球是打扰。
 */
private fun warn(project: Project, reason: String, path: String) {
    LOG.warn("CCoder 打开文件失败（$reason）：$path")
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification("打不开这个文件", "$reason：$path", NotificationType.WARNING)
        .notify(project)
}

private const val NOTIFICATION_GROUP = "CCoder"

/** 刷新等待上限。超时只影响这一次点击，见 [findRefreshed]。 */
private const val REFRESH_TIMEOUT_MS = 2000L

private val LOG = Logger.getInstance(OpenFileTarget::class.java)

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.num(key: String): Double? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
