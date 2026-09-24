package com.ccoder.ui

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.awt.Color

/**
 * 改动预览：把编辑类工具的入参算成"删了哪些行、加了哪些行"。
 *
 * ## 为什么有这个东西（2026-09-24）
 *
 * 市场的第一句卖点一直是 *see every file edit as a diff **before it lands***
 * —— 而批准前那个框里躺的是一坨 JSON：`{"old_string":"…","new_string":"…"}`。
 * 用户要在一屏转义字符里自己找"到底改了哪一句"，那不是审批，是解谜。
 * 事后（转写区）倒是画得好好的，**偏偏"before it lands"那一次没有**。
 *
 * ## 规则与网页侧**逐字一致**
 *
 * 同一件事在转写区早有一份实现：`web/src/tools.ts` 的 `toolDiff`。两边必须给出
 * 同一个答案 —— 否则同一次改动，批准前和批准后长得不一样，用户会以为批错了。
 * 所以：
 *
 * - 规则照抄，**连边界怪癖一起抄**（见 [str] 与 [lines] 的注释）；
 * - `shared/tool-diff.json` 是一份两端共读的用例表，任一侧改规则都会让另一侧变红
 *   （这是本仓库钉跨语言契约的老办法，另两份是 `transcript-ops.json` 与
 *   `deny-message.json`，见 `docs/superpowers/plans/2026-09-11-ccoder-ui-redesign.md`）。
 *
 * ## 这不是 LCS
 *
 * 就是"旧的全删、新的全加"，跟网页侧一样（那边也没有任何 diff 库）。对 `Edit`
 * 来说这**不是近似**：`old_string` 正是会被删掉的那一段，`new_string` 正是会写进去的
 * 那一段 —— CLI 已经替我们把差异圈出来了，没有公共行可合并。真正会骗人的是 `Write`
 * 覆盖一个已存在的文件（整份都画成新增），见 [writeDiff] 的注释。
 */
internal enum class DiffKind { Add, Del }

internal data class DiffLine(val kind: DiffKind, val text: String)

/**
 * diff 的两个**锚色**：绿 = 新增、红 = 删除。
 *
 * 两个渲染层都从它们出发，谁都不许自己写死一对：权限卡（[diffHtml] 的四个十六进制色，
 * 见 `PermissionCard` 的 `diffAddBg()` 一族）与转写区（`ThemeInjector` 混成 CSS 变量
 * 喂给网页）。这两对颜色只在**一处**定义，两边才不会再各走各的。
 */
internal val DIFF_ADD_ANCHOR = Color(0x4C, 0xAF, 0x50)
internal val DIFF_DEL_ANCHOR = Color(0xE0, 0x54, 0x54)

/**
 * 认得出的编辑类工具 → 行列表；认不出（或入参缺斤少两）给 `null`。
 *
 * `null` 与空列表是两件事：`null` = "这个工具没有 diff 可看"，空列表 = "算出来一行
 * 都没有"，后者也归成 `null`（[toolDiff] 末尾那一条）—— 一张空表格没有意义。
 *
 * ## 为什么只认三个（`NotebookEdit` 故意不认）
 *
 * `Edit` / `Write` / `MultiEdit` 的入参形状是一样的：一段旧文本、一段新文本。
 * `NotebookEdit` 不是 —— 它的 `new_source` 到底是"插入"还是"删除"，取决于另一个
 * 字段（`edit_mode`）。照搬"全当新增"会把**删除画成绿色的新增**，那比不画严重得多。
 * 要做就得单独认它的 `edit_mode`，那是一件独立的事，不混在这里猜。
 */
internal fun toolDiff(toolName: String, input: JsonObject): List<DiffLine>? {
    val lines = when (toolName) {
        "Edit" -> editDiff(input)
        "Write" -> writeDiff(input)
        "MultiEdit" -> multiEditDiff(input)
        else -> null
    }
    return lines?.takeIf { it.isNotEmpty() }
}

/**
 * 这些工具入参里**已经被 diff 吃掉**的字段 —— 它们不该再在「其余参数」里出现一次。
 *
 * 和上面那个 `when` 是两张表，改一张就得改另一张（放在同一个文件里，至少看得见）。
 * `ToolDiffTest` 有一条用例拿 `DIFF_CONSUMED` 的每个 key 去喂 [toolDiff]，保证两者
 * 不会一边认、另一边不认。
 */
private val DIFF_CONSUMED: Map<String, Set<String>> = mapOf(
    "Edit" to setOf("old_string", "new_string"),
    "Write" to setOf("content"),
    "MultiEdit" to setOf("edits"),
)

/** 认不出的工具给空集（它本来也没有 diff，谈不上"被吃掉"）。 */
internal fun diffConsumedFields(toolName: String): Set<String> = DIFF_CONSUMED[toolName].orEmpty()

/** [toolDiff] 认得的工具名。给用例与探针用，产品代码不拿它当白名单（走的是 `when`）。 */
internal val DIFF_TOOLS: Set<String> = DIFF_CONSUMED.keys

/**
 * `Edit`：旧文本全删、新文本全加。
 *
 * 这两个字段是 CLI 圈出来的**确切差异**，所以删/加就是字面事实，不是估算
 * —— 与 `Write` 那种"整份都算新增"不同（见下）。
 */
private fun editDiff(input: JsonObject): List<DiffLine>? {
    val before = str(input.get("old_string")) ?: return null
    val after = str(input.get("new_string")) ?: return null
    return remap(before, after)
}

/**
 * `Write`：整份正文都算新增。
 *
 * **覆盖一个已存在的文件时这会夸大**（看着像全文重写，其实大半没动）—— 因为
 * 入参里根本没有旧内容可比。要么去磁盘上读一份（那会把权限流程拖进"读用户文件"
 * 这件事，还得处理路径解析与编码），要么就这么老实地标"将要写入这些行"。
 * 现在选后者，跟网页侧一致；要改是两边一起改。
 */
private fun writeDiff(input: JsonObject): List<DiffLine>? {
    val content = str(input.get("content")) ?: return null
    return lines(content).map { DiffLine(DiffKind.Add, it) }
}

/**
 * `MultiEdit`：一串编辑，按顺序铺成一条一条的删/加。
 *
 * **不标"第几处编辑"**：网页侧也这么画（那边认了 `MultiEdit` 但只给出一个平铺
 * 列表），两边的形状必须一样。真要分段，得先给 [DiffLine] 加一个"第几组"的维度 ——
 * 那是一次数据结构改动，等有人真的抱怨"看不出改了几处"再说。
 *
 * 网页侧原先**完全没认** `MultiEdit`（它明明在 `FILE_TOOLS` 与徽标表里，卡片认得
 * 它的文件名和铅笔图标，就是不给 diff 与 `+N −N`）。这次两边一起补上。
 */
private fun multiEditDiff(input: JsonObject): List<DiffLine>? {
    val edits = input.get("edits") as? JsonArray ?: return null
    val out = mutableListOf<DiffLine>()
    for (edit in edits) {
        val obj = edit as? JsonObject ?: return null
        val before = str(obj.get("old_string")) ?: return null
        val after = str(obj.get("new_string")) ?: return null
        out += lines(before).map { DiffLine(DiffKind.Del, it) }
        out += lines(after).map { DiffLine(DiffKind.Add, it) }
    }
    return out
}

private fun remap(before: String, after: String): List<DiffLine> =
    lines(before).map { DiffLine(DiffKind.Del, it) } + lines(after).map { DiffLine(DiffKind.Add, it) }

/**
 * 取字符串字段。**空串当"没有"** —— 这条是照抄网页侧 `tools.ts` 的 `str()`，
 * 连它那个副作用一起：`Edit` 的 `old_string` 为 `""`（新建/在最前面插入）时整条
 * diff 给 `null`，退回纯文本。算不上好，但**两边一致**比"哪边更讲道理"重要；
 * 真要改，改的是规则本身，两个实现加那份 fixture 一起动。
 */
private fun str(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.asString.takeIf { it.isNotEmpty() }
}

/**
 * 按行切，并丢掉末尾那一个空行 —— 文件内容几乎总以换行结尾（照抄网页侧 `lines()`）。
 *
 * 只丢**一个**：`"a\n\n"` 留下的 `['a', '']` 是对的（中间那个空行是真内容）。
 *
 * ## 行尾的 `\r` 要去掉（2026-09-24 两侧一起加的）
 *
 * Windows 上的文件是 CRLF，CLI 把两行之间的 `\r\n` 原样塞进 `old_string` —— 于是
 * 每行末尾都拖着一个不可见的 `\r`。它在两个渲染层各惹一次事：网页那边
 * `.tool__line` 是 `white-space: pre`，**一个 `\r` 就是一次换行**（行里多出一截空白）；
 * Swing 的 HTML 里它更说不清会画成什么。这是权限卡这边新开的一屏才暴露出来的 ——
 * 从前没人把 `old_string` 送进 HTML 渲染。
 *
 * 两个实现一起改（fixture 里有一条 CRLF 用例钉着），所以它不是"哪边顺手擦一下"。
 */
private fun lines(text: String): List<String> {
    val out = text.split('\n').toMutableList()
    if (out.size > 1 && out.last() == "") out.removeAt(out.size - 1)
    return out.map { it.removeSuffix("\r") }
}

/**
 * 把行列表画成 **Swing 的 HTML 3.2**，供权限卡里那个 `JEditorPane` 渲染
 * （与 [planHtml] 同一条路、同一套边界：没有圆角、没有 `border-left`）。
 *
 * ## 为什么是表格
 *
 * 要的是**整行的底色带**（跟转写区一个观感），而 HTML 3.2 里能给一行区域上底色的
 * 只有表格单元格 —— `<div bgcolor>` 不吃。底色铺在 `td` 上而不是 `tr` 上：
 * `bgcolor` 在 `td` 上是稳的，在 `tr` 上各家实现不一。
 *
 * ## 空白靠 `<pre>` 保
 *
 * 缩进是这个屏上最要紧的信息之一（改的是哪个代码块全看缩进），而 HTML 会把连续空格
 * 压成一个。`<pre>` 是唯一能原样留住缩进的东西 —— 顺带还给了等宽字体，正合适。
 *
 * 空行给一个空格：`<pre>` 里什么都没有的单元格会塌成零高，一行空行就"消失"了，
 * 而那通常正是"这里少了一行"的证据。
 *
 * ## 表格要占满整宽（`width="100%"`）
 *
 * 表格默认只占内容那么宽，于是底色带**只有最长那一行那么长**，右边参差不齐 ——
 * 转写区那边 `.tool__line` 是块级元素，带子是铺满整宽的，两边看起来就不像同一个东西
 * （离屏渲染第一版就是这样，图里一眼看见）。所以表格拉满，标记那一格钉一个窄宽，
 * 剩下的都给正文格。
 *
 * ## 颜色是传进来的
 *
 * 和 [planHtml] 一样收 `#rrggbb`：这一层不认识主题，取色在卡片那边
 * （见 `PermissionCard` 的 `diffAddBg()` 一族）。
 */
internal fun diffHtml(
    lines: List<DiffLine>,
    addBg: String,
    delBg: String,
    addFg: String,
    delFg: String,
    signFg: String,
): String = buildString {
    append("<table border=\"0\" cellspacing=\"0\" cellpadding=\"1\" width=\"100%\">")
    for (line in lines) {
        val add = line.kind == DiffKind.Add
        val bg = if (add) addBg else delBg
        val fg = if (add) addFg else delFg
        append("<tr>")
        // 标记单独一格（与网页侧 `.tool__sign` 同一个位置关系）：它自己一个颜色，
        // 正文另一个颜色，两者不该混在一个 `font` 里。格里的那个 `&nbsp;` 是间距
        // —— 网页那边靠 flex 的 `gap: 5px`，HTML 3.2 没有 gap，只能拿字符顶
        append("<td width=\"14\" bgcolor=\"").append(bg).append("\"><pre><font color=\"")
            .append(signFg).append("\">").append(if (add) "+" else "−")
            .append("&nbsp;</font></pre></td>")
        append("<td bgcolor=\"").append(bg).append("\"><pre><font color=\"").append(fg)
            .append("\">").append(escapeHtml(line.text.ifEmpty { " " })).append("</font></pre></td>")
        append("</tr>")
    }
    append("</table>")
}
