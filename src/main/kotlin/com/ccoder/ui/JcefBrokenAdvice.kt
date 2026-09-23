package com.ccoder.ui

import com.ccoder.text.CcoderText
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.jcef.JBCefApp
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话一打开就提前说一句"这台机器的 JCEF 是坏的"——**赶在它卡死之前**。
 *
 * ## 为什么要有这一条（2026-09-23 用户问"打开会话时不会提示我吗"）
 *
 * [TranscriptFallback] 那条路是**事后**的：转写区已经挂了才给按键。对
 * [TranscriptFallback.Reason.StartFailed] 那样当场建不起来的情况够用，但
 * **静默断掉那种恰恰是"看着好好的"** —— 页面正常渲染、用起来一切如常，
 * 几十秒后整块不动（见 `ClaudeTranscriptView` 顶上那段）。那时用户已经在打字了，
 * 提示来得越晚越像"你这插件怎么又坏了"。所以这里把话提前：视图**起来之后**立刻说。
 *
 * 视图没起来时不弹气球：那种情况降级页本身就带着同一颗键，同一个意思不用讲两遍。
 *
 * ## 判据为什么要卡版本（不只看模式）
 *
 * "跑在 out-of-process 上"**不是**证据：用户的 PyCharm 2026.1 同样跑在 remote
 * （JCEF 137）上，一点事没有。JBR-9234 是** 144 那条构建线**上的事。只看模式就弹，
 * 会在好好的 IDE 上乱报 —— 那种人一旦被白指一次，以后就不看我们的提示了。
 *
 * 所以判据是**模式开着 且 主版本正好是 [AFFECTED_MAJOR]**，两样都要。
 * 版本串由 [nativeVersion] 取（`JBCefApp.getNativeBundleVersionString()` ——
 * 平台里唯一 public 的那个；`getVersionDetails` / `isRemoteEnabled` 都是包私有，
 * 我们够不着）。取不到就**什么都不说**：不知道的事不猜。
 *
 * ## 烦不着人
 *
 * 每个 IDE 进程最多一条（[shown]），并且「不再提示」会记在 IDE 级的
 * `PropertiesComponent` 里（这是**跟机器走**的判断，不是跟项目走）。
 */
internal object JcefBrokenAdvice {

    private const val NOTIFICATION_GROUP = "CCoder"

    /** 「不再提示」记在这儿。IDE 级 —— 见类注释。 */
    private const val DISMISSED_KEY = "ccoder.jcef.outOfProcessAdvice.dismissed"

    /** 每个 IDE 进程只弹一条。**只在真要弹的时候置位** —— 见 [adviseOnce]。 */
    private val shown = AtomicBoolean(false)

    /**
     * 该说的话现在说。**幂等**，每个进程有效一次；不满足判据时什么都不做。
     *
     * 顺序是刻意的：**先过闸门再占位**。反过来的话，头一次调用的时机不巧
     * （JCEF 还没起来、[nativeVersion] 取回 null）就会把这一趟唯一的发言权烧掉，
     * 之后真该说的时候反而沉默了。
     */
    fun adviseOnce(project: Project) {
        if (shown.get()) return
        if (!shouldWarnAboutOutOfProcessJcef(
                remoteEnabled = JcefRemoteMode.isEnabled(),
                nativeVersion = nativeVersion(),
                dismissed = isDismissed(),
            )
        ) {
            return
        }
        // 抢占：并发的两次会话打开里只有一条弹出来
        if (!shown.compareAndSet(false, true)) return

        LOG.info(
            "CCoder：本机 JCEF ${nativeVersion()} 跑在 out-of-process 上，落在这条已知缺陷线上，" +
                "提示用户关掉它（JBR-9234）",
        )
        show(project)
    }

    /** 本机 JCEF 的版本串。**取不到给 null**（还没起来、平台换了 API），调用方按"不知道"办。 */
    private fun nativeVersion(): String? =
        runCatching { JBCefApp.getNativeBundleVersionString() }.getOrNull()

    private fun isDismissed(): Boolean =
        runCatching { PropertiesComponent.getInstance().getBoolean(DISMISSED_KEY, false) }
            .getOrDefault(false)

    private fun show(project: Project) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                CcoderText.text("transcript.jcefBroken.title"),
                CcoderText.text("transcript.jcefBroken.body"),
                NotificationType.WARNING,
            )
            // 这是**待办**，不是"这一次没成"：别让它自己飘走。
            // （同组的 OpenFileTarget 那条走非粘性是另一回事 —— 那里留个撕不掉的气球才是打扰。）
            .setImportant(true)

        notification.addAction(
            NotificationAction.createSimpleExpiring(
                CcoderText.text("transcript.fallback.disableOutOfProcess"),
            ) { applyFix(project, notification) },
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                CcoderText.text("transcript.jcefBroken.dontAsk"),
            ) {
                rememberDismissed()
                notification.expire()
            },
        )
        notification.notify(project)
    }

    /**
     * 用户点了「关掉它」。写失败照样要说 —— 那就得把人指到手动那条（改 vmoptions），
     * 见 [JcefRemoteMode.disable] 的注释：报"已关闭"然后让人白重启一次更糟。
     */
    private fun applyFix(project: Project, notification: Notification) {
        if (JcefRemoteMode.disable()) {
            LOG.info("CCoder：已关闭 out-of-process JCEF，待 IDE 完全重启后生效")
            notify(project, "transcript.jcefBroken.disabled", NotificationType.INFORMATION)
        } else {
            LOG.warn("CCoder：out-of-process JCEF 没关掉（Registry 写失败或读不回），给手动改 vmoptions 的提示")
            notify(project, "transcript.jcefBroken.failed", NotificationType.WARNING)
        }
        notification.expire()
    }

    private fun notify(project: Project, key: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                CcoderText.text("transcript.jcefBroken.title"),
                CcoderText.text(key),
                type,
            )
            .notify(project)
    }

    private fun rememberDismissed() {
        runCatching { PropertiesComponent.getInstance().setValue(DISMISSED_KEY, true) }
            .onFailure { LOG.warn("CCoder：记不住「不再提示」，下次启动还会说一遍", it) }
    }

    private val LOG = Logger.getInstance(JcefBrokenAdvice::class.java)
}

/**
 * JBR-9234 落在这条 JCEF 构建线上：IntelliJ 2025.2 起捆的 **144**。
 *
 * 比的是**主版本号**，不认整串 —— 同一个版本在不同场合写法不一样
 * （`144.0.15.1191+gbecc5ec` / `remote_144.0.15.3416_becc5ec…` 两种都出现过）。
 */
internal const val AFFECTED_MAJOR = 144

/**
 * 从版本串里取主版本号：`remote_144.0.15.3416_x` → `144`。
 *
 * 要求点后面还有个数字（`144.0` 才算），免得把串里随便一个整数当版本。
 * 取不到给 `null` —— 调用方按"不知道"办。
 */
internal fun jcefMajorVersion(version: String?): Int? =
    version?.let { Regex("""(\d+)\.\d""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

/**
 * 该不该弹那条提前提示：**模式开着 + 版本正好是坏的那条 + 没说过不再提示**。
 *
 * 抽成纯函数是为了用例喂得进来 —— 真机上的三个入参各自要 IDE / JCEF / 用户设置，
 * 而"该不该多嘴"这件事本身跟它们都无关，值得单独钉住。三个条件缺一不可：
 * 少了版本这一条，PyCharm（137，同模式，没事）会被白指；少了模式这一条，
 * 会把已经关掉的人再劝一遍；少了 dismissed，用户说了不算。
 */
internal fun shouldWarnAboutOutOfProcessJcef(
    remoteEnabled: Boolean,
    nativeVersion: String?,
    dismissed: Boolean,
): Boolean = remoteEnabled &&
    !dismissed &&
    jcefMajorVersion(nativeVersion) == AFFECTED_MAJOR
