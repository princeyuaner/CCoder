package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把权限审批那张卡片画成 PNG。
 *
 * 没有断言，也不该有 —— 单测钉得住"按钮文字等于「允许」"，钉不住
 * "这张卡片看起来像不像一个能一眼读懂的问句"。后者只有**看**才知道。
 *
 * **画的是卡片，不是整个框**：框（标题栏、模态、关窗语义）由 `DialogWrapper`
 * 负责，是平台画的标准外观；我们自己的样式全在这张卡片里
 * （`PermissionDialog.createCenterPanel()` 返回的就是它）。
 *
 * 夹具**照真机抄**（2026-09-15 用户截图）：`displayName` 是 CLI 给的 `"Bash"`，
 * 也就是工具名本身。原先各处的夹具都写 `displayName = "允许"`，比现实好看，
 * 于是"按钮上写着 Bash"这件事一直没人发现。留着这条现实一点的样本。
 *
 * 产物在 `build/permission-dialog-probe.png`。改了这张卡片的观感就跑一下看一眼。
 */
class PermissionDialogRenderProbe {

    private fun permission(displayName: String) = SidecarMessage.Permission(
        requestId = "r1",
        toolName = "Bash",
        input = JsonParser.parseString(
            """{"command":"grep -rn \"model-profiles-dialog\" src/test/kotlin/"}"""
        ).asJsonObject,
        // title 为 null 也是照真机：CLI 对内置工具没给 title，
        // 卡片于是回落到 displayName → toolName，标题就显示「Bash」
        title = null,
        displayName = displayName,
        description = "Find how existing probes render dialogs",
        blockedPath = null,
        decisionReason = "Contains command_substitution",
        defaultToNo = false,
        suppressAlwaysAllowRule = false,
        suggestions = null,
    )

    /**
     * 真机那份 `ExitPlanMode` 入参的形状（从会话记录里抄的字段名与量级）：
     * `{"plan": "<几千字的计划>", "planFilePath": "…"}`。
     */
    /**
     * 计划里带表格的那一版（2026-09-17 用户截图）。
     *
     * 四列、带 `code`、格子里是英文标识符 —— 要看的是"竖线有没有变成真表格"
     * 与"这张表会不会把卡片挤爆"。入参用 [JsonObject] 直接搭，不走 JSON 文本：
     * 计划里有换行、引号、反引号，手写转义迟早错一个（这份探针以前就那么错过）。
     */
    private fun tablePlanPermission() = SidecarMessage.Permission(
        requestId = "r3",
        toolName = "ExitPlanMode",
        input = JsonObject().apply {
            addProperty(
                "plan",
                """
                # 收获判定

                ## 三类重置

                | 分类 | 配表判定 | 注册键 | 重置时机 |
                | --- | --- | --- | --- |
                | 不重置 | 两个 bool 都 False | `mutantFarmTarget_never` | 永不 |
                | 每日 | isDailyCondition=True | `mutantFarmTarget_daily` | 跨天 |
                | 每周 | isWeekCondition=True | `mutantFarmTarget_weekly` | 跨周 |

                两个都勾按"每日"处理。
                """.trimIndent(),
            )
            addProperty("planFilePath", "C:\\Users\\CY\\.claude\\plans\\harvest.md")
        },
        title = "ExitPlanMode",
        displayName = "ExitPlanMode",
        description = null,
        blockedPath = null,
        decisionReason = null,
        defaultToNo = false,
        suppressAlwaysAllowRule = false,
        suggestions = null,
    )

    /**
     * 改动预览那一档（2026-09-24）。
     *
     * 照一份**真改动**抄：改的是这个仓库里真有过的一处（`permissionBody` 多收一个
     * 工具名），有缩进、有中文注释、有 `+N −N`。看的是三件事：底色带读不读得出删和加、
     * 缩进有没有被 HTML 压掉、行尾有没有多出脏东西。
     */
    private fun editPermission() = SidecarMessage.Permission(
        requestId = "r4",
        toolName = "Edit",
        input = JsonObject().apply {
            addProperty("file_path", "src/main/kotlin/com/ccoder/ui/PermissionQueue.kt")
            addProperty(
                "old_string",
                """
                internal fun permissionBody(input: JsonObject): PermissionBody {
                    val field = longTextField(input)
                    if (field == null) {
                        // 没有正文型字段就整份缩进 JSON
                        return PermissionBody(INPUT_CAPTION, prettyJson(input), GENERIC_ROWS, GENERIC_MAX_HEIGHT)
                    }
                """.trimIndent(),
            )
            addProperty(
                "new_string",
                """
                internal fun permissionBody(toolName: String, input: JsonObject): PermissionBody {
                    val field = longTextField(input)
                    if (field == null) {
                        // 没有正文型字段就整份缩进 JSON
                        return PermissionBody(INPUT_CAPTION, prettyJson(input), GENERIC_ROWS, GENERIC_MAX_HEIGHT)
                    }
                    // 编辑类工具：先把改动算出来（见 ToolDiff.kt）
                    return diffOrPlain(toolName, input, plain)
                """.trimIndent(),
            )
            addProperty("replace_all", false)
        },
        title = "Edit",
        displayName = "Edit",
        description = null,
        blockedPath = null,
        decisionReason = null,
        defaultToNo = false,
        suppressAlwaysAllowRule = false,
        suggestions = null,
    )

    /** 新建文件那一档：整份都是新增，没有一行删除。 */
    private fun writePermission() = SidecarMessage.Permission(
        requestId = "r5",
        toolName = "Write",
        input = JsonObject().apply {
            addProperty("file_path", "src/main/kotlin/com/ccoder/ui/ToolDiff.kt")
            addProperty(
                "content",
                """
                package com.ccoder.ui

                /** 改动预览：把编辑类工具的入参算成"删了哪些行、加了哪些行"。 */
                internal fun toolDiff(toolName: String, input: JsonObject): List<DiffLine>? =
                    when (toolName) {
                        "Edit" -> editDiff(input)
                        else -> null
                    }
                """.trimIndent(),
            )
        },
        title = "Write",
        displayName = "Write",
        description = null,
        blockedPath = null,
        decisionReason = null,
        defaultToNo = false,
        suppressAlwaysAllowRule = false,
        suggestions = null,
    )

    /** 一处文件里改两处（MultiEdit）：一条平铺的删/加列表，不标"第几处"。 */
    private fun multiEditPermission() = SidecarMessage.Permission(
        requestId = "r7",
        toolName = "MultiEdit",
        input = JsonObject().apply {
            addProperty("file_path", "src/main/kotlin/com/ccoder/ui/ToolDiff.kt")
            add(
                "edits",
                com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("old_string", "private const val DIFF_MAX_LINES = 200")
                        addProperty("new_string", "private const val DIFF_MAX_LINES = 120")
                    })
                    add(JsonObject().apply {
                        addProperty(
                            "old_string",
                            "    val field = longTextField(input)\n    if (field == null) return plain",
                        )
                        addProperty(
                            "new_string",
                            "    val field = longTextField(input)\n    if (field == null) return plain\n    // 编辑类工具先算改动",
                        )
                    })
                },
            )
        },
        title = "MultiEdit",
        displayName = "MultiEdit",
        description = null,
        blockedPath = null,
        decisionReason = null,
        defaultToNo = false,
        suppressAlwaysAllowRule = false,
        suggestions = null,
    )

    /**
     * 贴近上限的那一档（180 行，上限 200）。
     *
     * **这一格是来量耗时的**：那一屏是 N 行的 HTML 表格，而 `JEditorPane` 的表格
     * 排版在 EDT 上按单元格现算。`DIFF_MAX_LINES` / `DIFF_MAX_CHARS` 那两个数不是
     * 拍的，就是拿这一格量出来的 —— 见下面那条 println。
     */
    private fun bigEditPermission(lines: Int) = SidecarMessage.Permission(
        requestId = "r6",
        toolName = "Edit",
        input = JsonObject().apply {
            addProperty("file_path", "big.txt")
            addProperty("old_string", (1..lines).joinToString("\n") { "    old line $it" })
            addProperty("new_string", (1..lines).joinToString("\n") { "    new line $it" })
        },
        title = "Edit",
        displayName = "Edit",
        description = null,
        blockedPath = null,
        decisionReason = null,
        defaultToNo = false,
        suppressAlwaysAllowRule = false,
        suggestions = null,
    )

    private fun planPermission() = SidecarMessage.Permission(
        requestId = "r2",
        toolName = "ExitPlanMode",
        input = JsonParser.parseString(
            """
            {"plan":"# 提问弹框：长文本换行 + 最小化\n\n## Context\n\n用户报了两个问题，都在提问弹框这条链上（AskQuestionCard / AskQuestionDialog / AskSequence）：\n\n1. **长题干不换行，把整个弹框撑宽。** 根因是两件事叠在一起：题干、选项 label、选项说明全是 JLabel（Swing 的 JLabel 从不自动换行），而 AskQuestionCard.getPreferredSize() 只把 preferred 宽度钉在 CARD_WIDTH(420)，没有覆写 getMinimumSize()。\n\n2. **没有最小化。** 框是全模态、且唯一的退路是 X/Esc，而 spec 6.2 明确把\"关窗\"定义成整条拒绝。\n\n## 验证\n\n- Kotlin 1081 全过\n- 渲染探针出图并量了宽度\n","planFilePath":"C:\\Users\\CY\\.claude\\plans\\swirling-finding-kettle.md"}
            """
        ).asJsonObject,
        // 真机就是这样：title 与 displayName 都把工具名念了一遍
        title = "ExitPlanMode",
        displayName = "ExitPlanMode",
        description = null,
        blockedPath = null,
        decisionReason = null,
        defaultToNo = false,
        suppressAlwaysAllowRule = false,
        suggestions = null,
    )

    @Test
    fun `把权限卡片画成图片`() = SwingUtilities.invokeAndWait {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(10)
        }

        fun caption(text: String) = column.add(
            JLabel(text).apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(4)
            }
        )

        caption("① 真机那种：displayName = \"Bash\"（工具名）—— 按钮应写「允许」")
        column.add(PermissionCard(permission(displayName = "Bash"), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        caption("② MCP 工具那种：displayName 是真的动作短语 —— 按钮用它")
        column.add(PermissionCard(permission(displayName = "Read file"), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        caption("③ 队列里还排着两个（queuedCount > 0 时多一行说明）")
        column.add(PermissionCard(permission(displayName = "Bash"), queuedCount = 2) {})
        column.add(Box.createVerticalStrut(14))

        // ④ 2026-09-15 用户截图那一张：ExitPlanMode 的审批框。入参里装的是一份
        // 三千多字的计划，改之前它被压成一行转义 JSON 塞在 3 行高的框里 ——
        // 用户的原话是"里面的内容都看不到"。这一格就是盯着这件事的
        caption("④ ExitPlanMode：入参是整份计划 —— 标题说人话、正文按段落铺开")
        column.add(PermissionCard(planPermission(), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        // ⑤ 2026-09-17 用户截图那一张：计划里有一张四列的 Markdown 表格。
        // 改之前它被拍成流水文字（`| 分类 | 配表判定 |` 与 `|---|---|---|` 铺在脸上），
        // 转写区那边却早就会渲染表格 —— 同一份计划两处长得很不一样
        caption("⑤ 计划里的表格：竖线变真表格，不再铺在脸上")
        column.add(PermissionCard(tablePlanPermission(), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        // ⑥⑦ 2026-09-24 加的：市场的第一句卖点是"批准前看到 diff"，而这一格
        // 从前给的是一坨 `{"old_string":…}`。看的是底色带读不读得出删与加、
        // 缩进有没有被 HTML 压掉
        caption("⑥ 改动预览（Edit）：删/加各带底色，缩进原样")
        column.add(PermissionCard(editPermission(), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        caption("⑦ 新建文件（Write）：整份都是新增，没有一行删除")
        column.add(PermissionCard(writePermission(), queuedCount = 0) {})
        column.add(Box.createVerticalGlue())

        // 画布跟着卡片宽度走（2026-09-16 方案 B：卡片 420 → 640）。
        // 写死数字的话，改宽度那次会把右边整条裁掉 —— 而探针恰恰是唯一能看见这件事的地方
        val w = PERMISSION_CARD_WIDTH + JBUI.scale(20)
        column.setSize(w, column.preferredSize.height)
        layoutAll(column)

        val h = column.preferredSize.height
        println("[权限卡片探针] 首选尺寸 ${column.preferredSize}")

        // 量一下"贴近上限"那一档在 EDT 上排一次要多久。**故意不画进图里** ——
        // 180 行画出来是一张几千像素高的图，看不动。
        //
        // 那两个上限（DIFF_MAX_LINES / DIFF_MAX_CHARS）就是这么定的：超过它宁可
        // 退回纯文本，也不让审批框排版把界面卡住。改那两个数之前先看这几个数。
        for (perSide in listOf(5, 15, 30, 60, 100)) {
            val t0 = System.nanoTime()
            val heavy = PermissionCard(bigEditPermission(perSide), queuedCount = 0) {}
            heavy.setSize(PERMISSION_CARD_WIDTH, 10)
            layoutAll(heavy)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("[权限卡片探针] diff ${perSide * 2} 行（上限 200 行 / 40000 字）构造+布局 ${ms}ms")
        }

        // 高度留一点余量：preferred 量的是内容，画的时候卡片描边还占几个像素，
        // 正好贴边时最后一行会被切掉一条
        val img = BufferedImage(w, h + JBUI.scale(10), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        column.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File("build/permission-dialog-probe.png"))
    }

    private fun layoutAll(c: java.awt.Container) {
        c.doLayout()
        for (child in c.components) {
            if (child is java.awt.Container) layoutAll(child)
        }
    }

    /**
     * 改动预览那一档单独出一张图（`build/permission-diff-probe.png`）。
     *
     * 全景图里卡片太多，⑥⑦ 缩在中间看不清；这张把 Edit / Write / MultiEdit 三档
     * 并排放着，好一眼看出：底色带读不读得出删和加、缩进有没有被 HTML 压掉、
     * 空行有没有塌掉。同 `ComposerRenderProbe` 一个探针出多张图的做法。
     */
    @Test
    fun `把改动预览单独画成图片`() = SwingUtilities.invokeAndWait {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(10)
        }

        fun caption(text: String) = column.add(
            JLabel(text).apply {
                foreground = UIUtil.getInactiveTextColor()
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(4)
            }
        )

        caption("① Edit：删的行在上、加的行在下，缩进原样")
        column.add(PermissionCard(editPermission(), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        caption("② Write（新建文件）：整份都是新增")
        column.add(PermissionCard(writePermission(), queuedCount = 0) {})
        column.add(Box.createVerticalStrut(14))

        caption("③ MultiEdit：两处编辑按顺序平铺")
        column.add(PermissionCard(multiEditPermission(), queuedCount = 0) {})
        column.add(Box.createVerticalGlue())

        val w = PERMISSION_CARD_WIDTH + JBUI.scale(20)
        column.setSize(w, column.preferredSize.height)
        layoutAll(column)
        val h = column.preferredSize.height

        val img = BufferedImage(w, h + JBUI.scale(10), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        column.paint(g)
        g.dispose()
        ImageIO.write(img, "png", File("build/permission-diff-probe.png"))
        println("[权限卡片探针] 改动预览那张图 ${w}x${h}")

        // 长行**不许看不见结尾**。diff 不能换行（一换就看不出对齐了），所以出路是
        // 横滚条 —— 网页那边 `.tool__line` 是 `overflow-x: auto`，同一个意思。
        // 这一格就是量它出没出的：真机上"看得见开头、够不着结尾"是最难自查的那种坏
        val long = "x".repeat(400)
        val wide = PermissionCard(
            editPermission().copy(
                input = JsonObject().apply {
                    addProperty("file_path", "wide.txt")
                    addProperty("old_string", long)
                    addProperty("new_string", "$long y")
                },
            ),
            queuedCount = 0,
        ) {}
        wide.setSize(PERMISSION_CARD_WIDTH, JBUI.scale(300))
        layoutAll(wide)
        val scroll = findScroll(wide)
        println(
            "[权限卡片探针] 400 字的超长行：横滚条可见=${scroll?.horizontalScrollBar?.isVisible}" +
                " 视口宽=${scroll?.viewport?.extentSize?.width}" +
                " 内容宽=${scroll?.viewport?.viewSize?.width}",
        )
    }

    /** 卡片里那个滚动区（树里第一个 `JBScrollPane`）。 */
    private fun findScroll(c: java.awt.Container): JBScrollPane? {
        for (child in c.components) {
            if (child is JBScrollPane) return child
            if (child is java.awt.Container) findScroll(child)?.let { return it }
        }
        return null
    }
}
