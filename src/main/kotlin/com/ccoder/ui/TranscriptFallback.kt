package com.ccoder.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.plaf.basic.BasicHTML
import javax.swing.text.View

/**
 * 转写区起不来 / 中途断掉时那张页：一段说明 +（值得重试的那几种）一个「重试」，
 * 而在 JCEF 跑着 out-of-process 的那几种上，再给一颗真正了结它的键。
 *
 * ## 为什么要有「重试」
 *
 * JCEF 服务器断过线之后，平台建 message router 会一路 NPE（见 `ClaudeTranscriptView`
 * 顶上那段），而这个状态**可能自己好**：服务器重连之后 CefApp 那边会换一套 RPC 上下文，
 * 再建就建得出来了。没有这颗键，用户只剩"重启 IDE"一条路 —— 2026-09-23 用户就是
 * 连点五次「新建会话」，每次都被那个异常挡回去的。
 *
 * 「未启用 JCEF」那一种**不给**键：那是设置，重试一百次也一样，指路才是对的
 * （`transcript.fallback.noJcef` 那句里带着 Registry 键名）。
 *
 * ## 为什么还有一颗「关掉 out-of-process JCEF」（2026-09-23 补）
 *
 * 因为「重试」在那一族失败上其实是**拖时间**：通道坏掉的根是那个模式本身
 * （JBR-9234，见 [JcefRemoteMode]），重试换回来的是几十秒到一分钟的可看时间，
 * 然后照旧断。所以 [Reason.OutOfProcessBroken] 那张页上多一颗键，直说了结的办法。
 *
 * 它排在「重试」**前面**：先给能了结的，再给能拖的。
 *
 * ## 两个回调都点名传
 *
 * [onDisableOutOfProcess] 是最后一个参数，所以 `TranscriptFallback(reason) { … }` 会把
 * 那个 lambda 绑到**它**身上（Kotlin 的尾随 lambda 只认最后一个）—— 想传重试就得写
 * `onRetry = { … }`。名字点出来，免得又踩一次。
 *
 * ## 文字为什么是 HTML 片段
 *
 * 词表里那几段是 `<br><br>` 分段的（`TextCatalog` 的规矩：看不出来的空白留在代码里拼），
 * 而 `JLabel` 只有拿到整份 `<html>…</html>` 才认标签 —— 整份文档的壳由 [HintLabel] 补，
 * 值里只管分段。**壳补在 `setText` 里**：换语言走的正是 `text = …` 那条路
 * （`LocalizedText.kt` 的 `applyLocalizedText`），补在构造里的话切一次语言壳就没了。
 */
internal class TranscriptFallback(
    reason: Reason,
    private val onRetry: () -> Unit,
    private val onDisableOutOfProcess: (() -> Unit)? = null,
) : JPanel() {

    /** 起不来的原因：决定用哪条文案、以及给哪几颗键。 */
    internal enum class Reason(val hintKey: String, val retryable: Boolean) {
        /** IDE 没启用 JCEF（设置里关着）：重试没有意义。 */
        NoJcef("transcript.fallback.noJcef", retryable = false),

        /** 启用着，但这一把没建起来：多半是 RPC 通道断过，值得重试。 */
        StartFailed("transcript.fallback.startFailed", retryable = true),

        /**
         * 跑着跑着断了：`executeJavaScript` 被 `RpcExecutor` 静默丢掉，
         * 输出区停在一帧上不动（见 `ClaudeTranscriptView` 心跳那一段）。
         * 与 [StartFailed] 同一条出路 —— 通道可能已经自己好了。
         */
        ChannelLost("transcript.fallback.channelLost", retryable = true),

        /**
         * [StartFailed] / [ChannelLost] 的**真身**：这一趟 JCEF 跑在独立进程里
         * （JBR-9234，见 [JcefRemoteMode]）。比前两者多说一句"根在哪"，
         * 并且多给一颗能真的了结它的键 —— 关掉那个模式。
         *
         * **仍然留着重试**：重启 IDE 是要中断手头活儿的，有人只想先把这一轮跑完。
         */
        OutOfProcessBroken("transcript.fallback.outOfProcessBroken", retryable = true),

        /** 已经关掉了：等完全重启。这一页上没有什么可点的。 */
        OutOfProcessDisabled("transcript.fallback.outOfProcessDisabled", retryable = false),

        /** 关不掉（Registry 写失败）：只剩手动改 vmoptions 那条路。 */
        OutOfProcessFailed("transcript.fallback.outOfProcessFailed", retryable = false),
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(16)

        // alignmentX 必须自己压到 0：`JComponent` 默认是 0.5，混在 BoxLayout(Y) 里
        // 会让"左对齐"看着像缩进了一截（仓库里几处 alignmentX 的教训都是它）
        add(HintLabel().localizedText(reason.hintKey).apply { alignmentX = Component.LEFT_ALIGNMENT })

        // 了结的那颗排在拖时间的那颗前面（理由见类注释）
        if (reason == Reason.OutOfProcessBroken && onDisableOutOfProcess != null) {
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(
                LinkButton("")
                    .localizedText("transcript.fallback.disableOutOfProcess")
                    .apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        addActionListener { onDisableOutOfProcess() }
                    },
            )
        }

        if (reason.retryable) {
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(
                LinkButton("")
                    .localizedText("transcript.fallback.retry")
                    .apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        addActionListener { onRetry() }
                    },
            )
        }
    }
}

/**
 * 把 JCEF 那一族的失败**问到根上**：这一趟要是正跑在 out-of-process 模式上，
 * 就把原因换成 [TranscriptFallback.Reason.OutOfProcessBroken] —— 那张页上多一条出路。
 *
 * 抽成纯函数是为了用例能喂：真机的判据 [JcefRemoteMode.isEnabled] 要 IDE 才读得到，
 * 而"该不该多嘴"这件事本身跟 IDE 无关，值得单独钉住。
 *
 * `remoteEnabled` 为 `false` 时**原样放行** —— 没在跑那个模式就别把人往"重启 IDE"上引。
 * `retryable` 是"这是 JCEF 那条路"的现成判据：三种 JCEF 失败为真，
 * 而已经关掉 / 关不掉那两种为假，所以它们不会绕回 [TranscriptFallback.Reason.OutOfProcessBroken]。
 */
internal fun fallbackReasonFor(
    reason: TranscriptFallback.Reason,
    remoteEnabled: Boolean,
): TranscriptFallback.Reason =
    if (remoteEnabled && reason.retryable) TranscriptFallback.Reason.OutOfProcessBroken else reason

/**
 * 降级页那段说明：值是 HTML 片段，整份文档的壳在这儿补（理由见类注释）。
 *
 * ## 高度得**按当前宽度现量**
 *
 * HTML 的 JLabel 报的首选尺寸是"不折行那一版"的：落到窄一点的宽度上，文字会折成
 * 更多行，而高度还是旧的那个 —— 多出来的行**被裁掉，一声不吭**。2026-09-23 出探针
 * 图的当场看见（`noJcef` 那段第二句的最后半句没了；同一张图上 `startFailed` 是好的，
 * 因为它的首选项恰好没超过 420 的窗口宽）。这与 `SettingsPage.wrappedHint` 注释里
 * 记的是同一个坑，那边靠"宽度是常量"绕开，这边的宽度跟着工具窗口走，只能现量。
 */
private class HintLabel : JBLabel() {

    init {
        // 第一遍量的时候宽度还是 0（组件刚建出来），父级会拿着那个旧高度排完这一轮；
        // 宽度一落定就重新报一次，让父级按新高度再排一遍（收敛：第二遍宽度没变）
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = revalidate()
        })
    }

    override fun setText(t: String?) {
        super.setText(if (t == null) null else "<html><body>$t</body></html>")
        measuredAt = -1
    }

    /** 换字体（含换 LAF，`updateComponentTreeUI` 会给每个控件重设字体）就得重新量。 */
    override fun setFont(f: Font?) {
        super.setFont(f)
        measuredAt = -1
    }

    /** 量过的宽度与那个宽度下的高度（同一个宽度不重复量）。 */
    private var measuredAt = -1
    private var measuredHeight = 0

    override fun getPreferredSize(): Dimension {
        val w = width
        if (w <= 0) return super.getPreferredSize()
        // **量的是"待会儿要画的那一份视图"**（`BasicHTML.propertyKey` 那一份），不是新造一份：
        // 视图只在它自己的 setSize 时才重排，新造的那份量得再准，画的那份照样停在旧宽度上
        // —— 探针图上"报 85、画出来只有 4 行"就是这么来的，而这一行 setSize 同时把画的
        // 那一份也排正了，第一帧就不会裁。
        val view = getClientProperty(BasicHTML.propertyKey) as? View ?: return super.getPreferredSize()
        if (w != measuredAt) {
            view.setSize(w.toFloat(), 0f)
            measuredHeight = view.getPreferredSpan(View.Y_AXIS).toInt()
            measuredAt = w
        }
        return Dimension(w, measuredHeight)
    }

    /**
     * 高度上**不许被夹回"不折行那一版"**。
     *
     * 这是同一个坑的另一半：`JLabel` 的 `min` / `max` 都直接返回 UI 算出来的那个首选
     * （不折行的），所以只重写 [getPreferredSize] 的话，布局照样把高度压在旧值上 ——
     * 探针图上"preferred 是 68、实际还是 51"就是这么来的。
     */
    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}
