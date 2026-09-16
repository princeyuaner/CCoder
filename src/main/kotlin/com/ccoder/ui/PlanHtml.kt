package com.ccoder.ui

/**
 * 计划文本（Markdown）→ **Swing 的 HTML 3.2**，供权限卡里的 `JEditorPane` 渲染。
 *
 * ## 为什么有这个东西
 *
 * 2026-09-16 用户截了 `ExitPlanMode` 的审批框：里面的计划是模型写的 **Markdown**，
 * 而我们把它塞进 `JBTextArea`（纯文本组件）—— 于是 `#`、`**`、反引号原样铺出来，
 * 那不是"排版朴素"，是**在显示源码**。效果图与四个方案的取舍见
 * `docs/design/plan-markdown.html`，选的是**方案 B（文档式）**。
 *
 * ## 只认计划里真会出现的那些记号
 *
 * 标题（`#`/`##`/`###`）、`**粗**`、`` `行内代码` ``、围栏代码块、两种列表、分隔线。
 * 表格、图片、链接、引用块一律**不解析** —— 解析不了就原样当文字留着，
 * 而不是吃掉：这一屏是给人审批用的，宁可丑也不能少字。
 *
 * ## HTML 3.2 的边界（`java.swing.text.html` 只做到这里）
 *
 * 没有圆角、阴影、flex、`border-left`。所以方案 B 里那条"小节色条"改用字符
 * `▌` 顶上 —— 一个字符，随字体缩放，也不用拿表格去凑。
 */
internal fun planHtml(markdown: String, accentHex: String, dimHex: String): String {
    val out = StringBuilder()
    var inFence = false
    var listTag: String? = null
    val paragraph = StringBuilder()

    /**
     * 段落攒够了就吐出去。列表项与标题会把这件事提前做掉。
     *
     * **转义在这里做**：`inlineHtml` 的约定是"传进来的已经转义过" ——
     * 少了这一步，计划里的 `List<Int>` 会被 JEditorPane 当成标签吃掉
     * （单测里那条"尖括号一律转义"就是盯它的）。
     */
    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        out.append("<p>").append(inlineHtml(escapeHtml(paragraph.toString()), accentHex)).append("</p>")
        paragraph.setLength(0)
    }

    fun closeList() {
        listTag?.let { out.append("</").append(it).append(">") }
        listTag = null
    }

    for (raw in markdown.split('\n')) {
        val line = raw.trimEnd()

        if (line.trimStart().startsWith("```")) {
            if (inFence) {
                out.append("</pre>")
                inFence = false
            } else {
                flushParagraph()
                closeList()
                out.append("<pre>")
                inFence = true
            }
            continue
        }
        if (inFence) {
            out.append(escapeHtml(raw)).append('\n')
            continue
        }

        val text = line.trimStart()
        when {
            text.isBlank() -> {
                flushParagraph()
                closeList()
            }

            text.startsWith("### ") || text.startsWith("## ") || text.startsWith("# ") -> {
                flushParagraph()
                closeList()
                val level = text.takeWhile { it == '#' }.length
                val title = text.drop(level).trim()
                if (level == 1) {
                    // 一级标题下一道分隔线 —— 方案 B 里那条"压线"
                    out.append("<b><font size=\"+1\">").append(inlineHtml(escapeHtml(title), accentHex))
                        .append("</font></b><hr>")
                } else {
                    // 小节：色条 + 小号大写。Swing 没有 border-left，用字符 `▌` 顶
                    out.append("<div><font color=\"").append(accentHex).append("\">▌</font> ")
                        .append("<b><font size=\"-1\" color=\"").append(dimHex).append("\">")
                        .append(inlineHtml(escapeHtml(title.uppercase()), accentHex))
                        .append("</font></b></div>")
                }
            }

            text == "---" || text == "***" || text == "___" -> {
                flushParagraph()
                closeList()
                out.append("<hr>")
            }

            BULLET.matchesAt(text, 0) -> {
                flushParagraph()
                if (listTag != "ul") {
                    closeList()
                    out.append("<ul>")
                    listTag = "ul"
                }
                out.append("<li>").append(inlineHtml(escapeHtml(text.drop(2)), accentHex)).append("</li>")
            }

            ORDERED.matchesAt(text, 0) -> {
                flushParagraph()
                if (listTag != "ol") {
                    closeList()
                    out.append("<ol>")
                    listTag = "ol"
                }
                // 正则已经保证标记后面是个空格，所以内容从第一个空格之后开始
                out.append("<li>")
                    .append(inlineHtml(escapeHtml(text.substring(text.indexOf(' ') + 1)), accentHex))
                    .append("</li>")
            }

            else -> {
                closeList()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(text)
            }
        }
    }

    flushParagraph()
    closeList()
    if (inFence) out.append("</pre>") // 围栏没闭合：收尾时补上，别把后面全吞进代码块
    return out.toString()
}

private val BULLET = Regex("^[-*+] ")
private val ORDERED = Regex("^\\d+[.)] ")

/** 行内记号：先转义再替换，两条都只认最简单的形状。 */
internal fun inlineHtml(escaped: String, accentHex: String): String =
    escaped
        .replace(INLINE_CODE) { "<code><font color=\"$accentHex\">${it.groupValues[1]}</font></code>" }
        .replace(BOLD) { "<b>${it.groupValues[1]}</b>" }

private val INLINE_CODE = Regex("`([^`]+)`")
private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")

/**
 * 转义。**这一步绝不能省**：计划里出现 `<` `>` `&` 是常事（泛型、箭头、`&&`），
 * 不转义的话 JEditorPane 会把它们当标签吃掉 —— 而这一屏是给人审批用的，
 * 少一个字都比排版难看严重。
 *
 * 补全弹层加粗命中字符时也用这一份（原先它在 [CompletionPopup] 里另有一份私有实现，
 * 同包同名同签名会编译不过 —— 正好并成一份）。
 */
internal fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
