package com.ccoder.ui

import com.ccoder.sync.Failed
import com.ccoder.sync.SyncRun
import com.ccoder.sync.SyncSnapshot
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把**点同步卡弹出来的那一屏**离屏画成 PNG（2026-09-28）。
 *
 * 加它的原因就是用户这一句"弹框里的字体颜色太暗了"：这一屏从做出来到现在
 * **一张图都没出过**（气泡有图、它没有），于是"哪几行字发闷"只能靠猜。
 * 单测盯得住"空日志给一句话""状态词跟着快照走"，盯不住"这一屏看着亮不亮"。
 *
 * 底取 `popupBackdrop()` 那一个（弹层列表那一族，与 `SyncLogDetail.kt` 里挑字色
 * 用的是同一个）—— 图上的字色就是真机上那个。
 *
 * 两张图：
 *
 * 1. **跑完一轮**：十几行日志、含一条 `[失败]`（最长、最该看清的一屏）
 * 2. **还没开跑**：空日志那句话 + 状态词（刚打开时的一屏）
 *
 * 产物在 `build/probe/sync-log*.png`。改了这层的观感就跑一下看一眼。
 */
class SyncLogDetailRenderProbe {

    @Test
    fun `把日志浮层画成图片`() = render(
        "build/probe/sync-log.png",
        SyncSnapshot(
            run = SyncRun.RUNNING,
            lastRoundAtMs = 1L,
            lastCostMs = 480L,
            copied = 12,
            deleted = 3,
            failed = listOf(Failed("trunk/src/net/old.kt", "被占用")),
            // 照引擎真实吐的那几句写（`SyncEngine.logPending` / `logResult`），
            // 乱编的短句会把折行与宽度都看错
            log = listOf(
                "[开始] 基线轮",
                "[内容对账] 尚无基线（首次运行，或基线被删）：要对 412 个文件做一次全量核对，可能要一两分钟",
                "[待复制] 12 个文件，[待删除] 3 项",
                "  (新增) trunk/src/net/battle.kt",
                "  (内容变了) trunk/config/gateway.json",
                "  (本地已删) trunk/src/net/old_gateway.kt",
                "  … 其余 10 个复制略",
                "[已核实] 两侧一致 398 个",
                "[完成] 复制 12/12，删除 3 项（0 项目标端本不存在），顺带清掉 1 个空目录，失败 1 项（用时 480 ms）",
                "  [失败] 复制 trunk/src/net/old.kt — 被占用",
            ),
        ),
    )

    @Test
    fun `把还没开跑那一版画成图片`() = render(
        "build/probe/sync-log-empty.png",
        SyncSnapshot(run = SyncRun.DISABLED),
    )

    private fun render(path: String, snapshot: SyncSnapshot) = IdeLaf.withRealLaf {
        SwingUtilities.invokeAndWait {
            val view = SyncLogDetail().apply { setSnapshot(snapshot) }

            val outer = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getListBackground()
                border = JBUI.Borders.empty(10)
                add(view, BorderLayout.CENTER)
            }

            val w = outer.preferredSize.width
            val h = outer.preferredSize.height
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            outer.paint(g)
            g.dispose()
            ImageIO.write(img, "png", File(path))
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is Container) layoutAll(child)
        }
    }
}
