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
 * 标题（`#`/`##`/`###`）、`**粗**`、`` `行内代码` ``、围栏代码块、两种列表、分隔线、
 * **表格**。图片、链接、引用块一律**不解析** —— 解析不了就原样当文字留着，
 * 而不是吃掉：这一屏是给人审批用的，宁可丑也不能少字。
 *
 * ## 表格是 2026-09-17 补的（用户截图："计划里这个展示的格式很难看"）
 *
 * 那一版把一张 GFM 表格拍成了流水文字：`| 分类 | 配表判定 |` 与 `|---|---|---|`
 * 原样铺在脸上，四列数据挤成一行。转写区（web）那边**早就会渲染表格**，
 * 于是同一个计划在两处长得完全不一样。
 *
 * 认的规则很窄（宁可漏认，也不要把普通文字吃成表格）：**当前行能切出两格以上、
 * 且下一行是分隔行**（`|---|:--:|`）才算。分隔行不成立就整段退回普通文字。
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
    // 用下标而不是 for-in：表格要**向后看一行**（分隔行），还要一口气吃掉后面几行
    val lines = markdown.split('\n')
    var i = 0

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

    while (i < lines.size) {
        val raw = lines[i]
        // **下标在循环体开头就推进**，而不是在末尾 —— 这样每一处 `continue`
        // 都天然是安全的。写成"末尾自增"的话，围栏那两条 `continue` 会跳过自增：
        // 一遇到代码块就原地死循环，一路把 StringBuilder 撑到 OOM（真踩过：
        // 12 条用例跑完，`围栏代码块` 那条直接把测试 JVM 干掉了）
        i++
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

            // 表格：当前行能切出两格以上，且下一行是分隔行（GFM 的形状）。
            // 注意下标已经指向**下一行**了（循环体开头推进过），所以这里看的是 lines[i]
            isTableRow(text) && i < lines.size && isDelimiterRow(lines[i].trim()) -> {
                flushParagraph()
                closeList()
                val aligns = alignmentsOf(lines[i])
                out.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"3\">")
                out.append(rowHtml("th", tableCells(text), aligns, accentHex))
                i++ // 分隔行
                while (i < lines.size && isTableRow(lines[i].trim())) {
                    out.append(rowHtml("td", tableCells(lines[i].trim()), aligns, accentHex))
                    i++
                }
                out.append("</table>")
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

/**
 * 切一行表格的单元格。
 *
 * 首尾那两条竖线**可以省**（GFM 两种都收）；`\|` 是转义，写在格子里就是真竖线。
 *
 * 手写循环而不是 `split('|')`：转义那条用 split 做不干净 —— 先换成占位符再换回来，
 * 而占位符选谁都不安全（选空格，切完每个空格都变成竖线；选 NUL，文件里就多了个
 * 不可见字符）。直接扫一遍最省事。
 */
private fun tableCells(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.substring(1)
    // 结尾那条：`\|`（转义的）不算
    if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)

    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var k = 0
    while (k < body.length) {
        val c = body[k]
        when {
            c == '\\' && k + 1 < body.length && body[k + 1] == '|' -> {
                cur.append('|')
                k += 2
            }
            c == '|' -> {
                cells += cur.toString().trim()
                cur.setLength(0)
                k++
            }
            else -> {
                cur.append(c)
                k++
            }
        }
    }
    cells += cur.toString().trim()
    return cells
}

/** 分隔行的一格：`---`、`:--`、`--:`、`:-:` 都算。 */
private val DELIMITER_CELL = Regex("^:?-+:?$")

/**
 * 这一行是不是表格的**分隔行**。
 *
 * 至少两格：只有一格的话，单独一个 `-`（比如没写完的列表）也会被认成表格，
 * 而一条竖线的"表格"本来也没有意义。
 */
private fun isDelimiterRow(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { DELIMITER_CELL.matches(it) }
}

/** 一行能切出两格以上才算表格行 —— `a | b` 这种省了首尾竖线的也认。 */
private fun isTableRow(text: String): Boolean = tableCells(text).size >= 2

/** 分隔行里的冒号表示对齐：`:--` 左、`--:` 右、`:-:` 居中、不写跟默认。 */
private fun alignmentsOf(delimiter: String): List<String?> =
    tableCells(delimiter).map {
        when {
            it.startsWith(":") && it.endsWith(":") -> "center"
            it.endsWith(":") -> "right"
            it.startsWith(":") -> "left"
            else -> null
        }
    }

/** 表格的一行。单元格里的记号（粗体、行内代码）照样解析，照样先转义。 */
private fun rowHtml(
    cellTag: String,
    cells: List<String>,
    aligns: List<String?>,
    accentHex: String,
): String = buildString {
    append("<tr>")
    cells.forEachIndexed { idx, cell ->
        val align = aligns.getOrNull(idx)?.let { " align=\"$it\"" }.orEmpty()
        append("<").append(cellTag).append(align).append(">")
        append(inlineHtml(escapeHtml(cell), accentHex))
        append("</").append(cellTag).append(">")
    }
    append("</tr>")
}

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
