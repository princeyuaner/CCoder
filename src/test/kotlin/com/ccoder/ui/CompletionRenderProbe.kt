package com.ccoder.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.ccoder.settings.PromptPreset
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 渲染探针：把补全弹层画成 PNG，好让人眼看一眼。
 *
 * 没有断言，也不该有 —— 单测能钉住"分组标题只出一条""长描述被截断"、
 * "选中行有底色"，钉不住三件事：
 *
 *  1. 长描述截到 110 字之后还读不读得懂
 *  2. 分组标题的分量会不会跟候选抢注意力
 *  3. 一行里"名字 · 描述"的间隔读起来累不累
 *
 * 产物在 `build/completion-probe*.png`。改了弹层的观感就跑一下看一眼。
 *
 * **只出浅色两张。** 项目现有的 [ComposerRenderProbe] 也不切主题 ——
 * 离屏渲染拿不到 IDE 的深色 LaF，硬造一个只会画出一个谁也没见过的配色。
 * 深色下的观感在沙箱里切主题看。
 */
class CompletionRenderProbe {

    @Test
    fun `命令组（分组标题与长描述）画成图片`() =
        render("build/completion-probe-commands.png", commands())

    @Test
    fun `文件组（长路径）画成图片`() =
        render("build/completion-probe-files.png", files())

    /**
     * 命中加粗（2026-09-16）：**前缀命中与模糊命中并排**画一张。
     *
     * 要看的正是两件事：这一列本身很长，加粗会不会把行读得更乱；以及模糊命中
     * （字符散着）在真实字体下加粗之后，还认不认得出原来是哪个词。
     * 单测能钉住"该包的包上了、该转义的转义了"，钉不住"看着乱不乱"。
     */
    @Test
    fun `命中加粗画成图片 —— 前缀与模糊各几条`() = render(
        "build/completion-probe-file-hits.png",
        fileCandidates(files().map { it.display }, "Command") +
            fileCandidates(files().map { it.display }, "cmpl"),
    )

    /**
     * **带命中**的命令行 —— 2026-09-24 用户截图报的那个毛病。
     *
     * 上面那张命令图里的行**都没有 hits**，于是走纯文本；而真实使用中几乎总有命中
     * （打的字就是前缀命中），行文本一进 HTML 排版就换一套规则：**在空格处折行**。
     * 命令行是 `名字  ·  描述`，两处都是空格 —— 折成两行，而弹层高度是按一行算的。
     *
     * 数据照用户那张截图取：`/code` 打在 `code-review:code-review` 上，描述里
     * `(code-review) Code review a pull request` 在 "Code" 后面折断。
     */
    @Test
    fun `带命中的命令行（长描述会不会折行）画成图片`() = render(
        "build/completion-probe-command-hits.png",
        listOf(
            CompletionItem(
                "code-review:code-review", "code-review:code-review",
                "(code-review) Code review a pull request",
                hits = listOf(0, 1, 2, 3), group = GROUP_PLUGIN,
            ),
            CompletionItem(
                "brainstorming", "superpowers:brainstorming",
                "You MUST use this before any creative work - creating features, building components, " +
                    "adding functionality, or modifying behavior.",
                hits = listOf(0, 1, 2, 3, 4, 5, 6), group = GROUP_PLUGIN,
            ),
            CompletionItem(
                "写测试", "写测试", "给这段代码补单测：覆盖边界与失败路径",
                hits = listOf(0), group = GROUP_OTHER,
            ),
            CompletionItem("compact", "compact", "参数 <optional custom summarization instructions>", group = GROUP_OTHER),
        ),
    )

    /**
     * 预设组**混着命令组**画一张。
     *
     * 光看预设组看不出问题 —— 要看的正是**两组交界处**：
     * 分组标题的分量会不会跟候选抢注意力、以及预设那一组里"名字 · 正文首行"
     * 这种新组合读起来累不累（命令那边副标题是描述，语气不一样）。
     */
    @Test
    fun `预设组与命令组交界画成图片`() =
        render(
            "build/completion-probe-presets.png",
            promptCandidates(
                listOf(
                    PromptPreset(name = "写测试", content = "给这段代码补单测：覆盖边界与失败路径"),
                    PromptPreset(name = "解释报错", content = "解释这段报错：先给结论，再说为什么"),
                    PromptPreset(name = "重命名", content = "把这个变量改个更准确的名字，并同步所有引用"),
                ),
            ) + commands().take(3),
        )

    /**
     * 符号组：行型是「名字 · 相对路径」，这张图要看三件事 ——
     *
     *  1. 名字长短不一时，` · ` 那一列会不会显得参差；
     *  2. 深路径会不会把宽度撑爆（弹层宽度是定死的，`setResizable(false)`）；
     *  3. 同名不同文件的那两行，一眼分不分得开。
     */
    @Test
    fun `符号组（名字 · 路径）画成图片`() = render(
        "build/completion-probe-symbols.png",
        symbolCandidates(
            listOf(
                symbol("parse_config", "src/app/config.py", 24..27),
                symbol("Parser", "src/app/parse/parser.py", 1..40),
                symbol("parse", "src/app/parse/__init__.py", 1..3),
                symbol("parse_all", "src/app/parse/batch.py", 12..30),
                symbol("parse_raw", "tests/fixtures/legacy/parse_raw.py", 5..19),
                symbol("parse_stream", "src/app/parse/streaming/reader.py", 88..140),
                symbol("parse_stream", "src/app/parse/streaming/writer.py", 7..12),
                symbol("parseWithConfigAndOverridesForTests", "tests/app/parse_test.py", 3..9),
            ),
        ),
    )

    /**
     * 状态行：一个候选都没有时弹层里唯一的那一行（正在搜 / 搜不成）。
     *
     * 要看的正是它**空得可不可疑** —— 这一行是"它还在动"的全部线索。
     */
    @Test
    fun `符号搜索中的状态行画成图片`() =
        render("build/completion-probe-symbol-status.png", emptyList(), status = "正在搜索符号…")

    /** 弹层里只用到名字与路径；code 进的是记号与展开，不影响这张图。 */
    private fun symbol(name: String, path: String, lines: IntRange) =
        SymbolHit(name, path, lines, code = "# 略", fileTypeName = "Python")

    /** 数据取自实测：`brainstorming` 那条是本例里最长的真实描述。 */
    private fun commands() = listOf(
        CompletionItem("compact", "compact", "参数 <optional custom summarization instructions>", group = GROUP_OTHER),
        CompletionItem(
            "clear", "clear", "Start a new session with empty context · 别名 reset、new",
            group = GROUP_OTHER,
        ),
        CompletionItem("context", "context", "Show current context usage", group = GROUP_OTHER),
        CompletionItem("usage", "usage", "Show the total cost and duration · 别名 cost、stats", group = GROUP_OTHER),
        CompletionItem(
            "Debug Issue", "debug-issue",
            "Systematically debug issues using graph-powered code navigation (user)",
            group = GROUP_OTHER,
        ),
        CompletionItem(
            "caveman", "caveman",
            "Upstream: github.com/JuliusBrussee/caveman",
            group = GROUP_OTHER,
        ),
        CompletionItem(
            "brainstorming", "superpowers:brainstorming",
            "You MUST use this before any creative work - creating features, building components, " +
                "adding functionality, or modifying behavior. Explores user intent, requirements " +
                "and design before implementation.",
            group = GROUP_PLUGIN,
        ),
    )

    private fun files() = listOf(
        "src/main/kotlin/com/ccoder/ui/Completion.kt",
        "src/main/kotlin/com/ccoder/ui/CompletionPopup.kt",
        "src/main/kotlin/com/ccoder/ui/CommandCandidates.kt",
        "src/main/kotlin/com/ccoder/ui/ClaudePanel.kt",
        "src/test/kotlin/com/ccoder/ui/CompletionTest.kt",
    ).map { CompletionItem(it, it) }

    private fun render(path: String, items: List<CompletionItem>, status: String? = null) {
        SwingUtilities.invokeAndWait {
            val content = buildCompletionList(items, selected = 1, status = status)
            val bg: Color = UIUtil.getPanelBackground()

            val outer = JPanel(BorderLayout()).apply {
                background = bg
                border = JBUI.Borders.empty(8)
                add(content, BorderLayout.CENTER)
            }

            val ps = content.preferredSize
            val w = ps.width + 16
            val h = ps.height + 16
            outer.setSize(w, h)
            layoutAll(outer)

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = bg
            g.fillRect(0, 0, w, h)
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
