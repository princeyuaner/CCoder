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
 * 转写区起不来时那张页：一段说明 +（"建不起来"那一种）一个「重试」。
 *
 * ## 为什么要有「重试」
 *
 * JCEF 服务器断过线之后，平台建 message router 会一路 NPE（见 `ClaudeTranscriptView`
 * 顶上那段），而这个状态**可能自己好**：服务器重连之后 CefApp 那边会换一套 RPC 上下文，
 * 再建就建得出来了。没有这颗键，用户只剩"重启 IDE"一条路 —— 2026-09-23 用户就是
 * 连点五次「新建会话」、每次都被那个异常挡回去的。
 *
 * 「未启用 JCEF」那一种**不给**键：那是设置，重试一百次也一样，指路才是对的
 * （`transcript.fallback.noJcef` 那句里带着 Registry 键名）。
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
) : JPanel() {

    /** 起不来的原因：决定用哪条文案、以及要不要给「重试」。 */
    internal enum class Reason(val hintKey: String) {
        /** IDE 没启用 JCEF（设置里关着）：重试没有意义。 */
        NoJcef("transcript.fallback.noJcef"),

        /** 启用着，但这一把没建起来：多半是 RPC 通道断过，值得重试。 */
        StartFailed("transcript.fallback.startFailed"),
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(16)

        // alignmentX 必须自己压到 0：`JComponent` 默认是 0.5，混在 BoxLayout(Y) 里
        // 会让"左对齐"看着像缩进了一截（仓库里几处 alignmentX 的教训都是它）
        add(HintLabel().localizedText(reason.hintKey).apply { alignmentX = Component.LEFT_ALIGNMENT })

        if (reason == Reason.StartFailed) {
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
