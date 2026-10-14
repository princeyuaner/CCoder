package com.ccoder.ui

import com.ccoder.sidecar.UsageDay
import com.ccoder.sidecar.UsageModelTokens
import com.ccoder.text.CcoderText
import java.awt.Color
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * 使用量统计的**显示层模型** —— 全是纯函数，用例直接打（对话框那层只负责摆）。
 *
 * 三件事，各有各的规矩：
 *
 * 1. **分桶**：日 = 近 30 天、周 = 近 12 周（周一起）、月 = 近 12 个月；空洞
 *    **补零** —— 一条断掉的时间轴会把"那几天没干活"读成"数据丢了"。
 * 2. **合并**：模型按总量**降序**排，而且配色用的是**全局**顺序（[usageModelsOrdered]）
 *    而不是单根柱的顺序 —— 同一根柱里换个位置颜色就变，会读成两个模型。
 * 3. **文案**：所有界面字走词表，这里只出数字与结构（同 MessageRenderer 的取舍）。
 */

internal enum class UsageGran { Day, Week, Month }

/** 一个柱：一根柱 = 一天/一周/一月。[models] 已按总量降序。 */
internal data class UsageBucket(
    val label: String,
    val start: LocalDate,
    val models: List<Pair<String, UsageModelTokens>>,
    val tokens: Long,
    val costUsd: Double,
)

/** 今日/本周/本月三块砖 + 本周相对上周的变化率（上周没有数据时给 null，不造百分比）。 */
internal data class UsageSummary(
    val today: UsageBucket,
    val week: UsageBucket,
    val month: UsageBucket,
    val weekDelta: Double?,
)

/** 常驻条要画的东西：请求回升与实时推送（usageTick）折算出来的是同一个东西。 */
internal data class UsageStripData(
    val todayTokens: Long,
    val todayCost: Double?,
    /** 近 14 天（含今天）的每日总量，最右是今天。 */
    val spark: List<Long>,
    /**
     * 今日实扣（[UsageBill.yuanToday]，元）。null = 没配 DMXAPI / 没查到 —— 条上不出现那段。
     *
     * **与 todayTokens / todayCost 不是一回事**：那两个是"本项目 · 这台机器"的估算，
     * 这个是"本令牌 · 全来源"的账 —— 界面上并排放是为了对账，别把两个数并成一个。
     */
    val todayBillYuan: Double? = null,
    /**
     * opencode Go 的三档额度（已用百分比，2026-10-13 加）。null = 没选 opencode 配置 /
     * 没查到 —— 条上不出现那段。与 [todayBillYuan] **互斥**（一条配置只可能命中一家）。
     *
     * 口径与估算的 todayTokens 也不是一回事：那是"本项目"的量，这是**本账号**三档
     * 窗口各自的已用比例 —— 并排是为了对账，别把两个数并成一个。
     */
    val opencodeQuota: OpencodeQuota? = null,
    /**
     * DeepSeek 官方账号余额（2026-10-13 加）。null = 非 deepseek 官方配置 / 没查到
     * —— 条上不出现那段。与另外两家**互斥**（一条配置只命中一家）。
     *
     * 口径：账户里还剩多少钱（含赠金）—— 与"本项目估的量""本令牌的实扣"都不是一回事。
     */
    val deepseekBalance: DeepseekBalance? = null,
)

internal fun UsageModelTokens.total(): Long = inp + out + cacheRead + cacheWrite

internal operator fun UsageModelTokens.plus(other: UsageModelTokens): UsageModelTokens =
    UsageModelTokens(
        inp + other.inp,
        out + other.out,
        cacheRead + other.cacheRead,
        cacheWrite + other.cacheWrite,
        costUsd + other.costUsd,
    )

/** 若干天合成一个桶（模型降序、合计、成本合计）。 */
internal fun usageBucketOf(start: LocalDate, label: String, list: List<UsageDay>): UsageBucket {
    val acc = LinkedHashMap<String, UsageModelTokens>()
    for (d in list) {
        for ((m, t) in d.models) {
            acc[m] = if (acc.containsKey(m)) acc.getValue(m) + t else t
        }
    }
    val ordered = acc.entries.sortedByDescending { it.value.total() }.map { it.key to it.value }
    return UsageBucket(
        label = label,
        start = start,
        models = ordered,
        tokens = ordered.sumOf { it.second.total() },
        costUsd = ordered.sumOf { it.second.costUsd },
    )
}

/** 主序列。空洞补零（没柱子的那格 = 那天没人干活）。 */
internal fun usageBuckets(days: List<UsageDay>, gran: UsageGran, today: LocalDate): List<UsageBucket> {
    val byDate = days.associateBy { it.date }
    return when (gran) {
        UsageGran.Day -> (29 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            usageBucketOf(d, "${d.monthValue}/${d.dayOfMonth}", listOfNotNull(byDate[d]))
        }

        UsageGran.Week -> (11 downTo 0).map { back ->
            val start = today.minusWeeks(back.toLong()).with(DayOfWeek.MONDAY)
            usageBucketOf(
                start,
                "${start.monthValue}/${start.dayOfMonth}",
                (0..6).mapNotNull { byDate[start.plusDays(it.toLong())] },
            )
        }

        UsageGran.Month -> (11 downTo 0).map { back ->
            val start = today.minusMonths(back.toLong()).withDayOfMonth(1)
            val end = start.plusMonths(1)
            usageBucketOf(start, "${start.monthValue}", days.filter { !it.date.isBefore(start) && it.date.isBefore(end) })
        }
    }
}

/** 三块砖。周按周一起（与分桶口径一致 —— 两处不一致时砖与柱会对不上）。 */
internal fun usageSummary(days: List<UsageDay>, today: LocalDate): UsageSummary {
    val byDate = days.associateBy { it.date }
    val weekStart = today.with(DayOfWeek.MONDAY)
    val lastWeekStart = weekStart.minusWeeks(1)
    val monthStart = today.withDayOfMonth(1)
    val inRange = { from: LocalDate, to: LocalDate, includeTo: Boolean ->
        days.filter { !it.date.isBefore(from) && (if (includeTo) !it.date.isAfter(to) else it.date.isBefore(to)) }
    }
    val todayBucket = usageBucketOf(today, "", listOfNotNull(byDate[today]))
    val week = usageBucketOf(weekStart, "", inRange(weekStart, today, true))
    val lastWeek = usageBucketOf(lastWeekStart, "", inRange(lastWeekStart, weekStart, false))
    val month = usageBucketOf(monthStart, "", inRange(monthStart, today, true))
    val delta = if (lastWeek.tokens > 0) (week.tokens - lastWeek.tokens).toDouble() / lastWeek.tokens else null
    return UsageSummary(todayBucket, week, month, delta)
}

/**
 * 从 rows 折出常驻条要的：今天的量 + 近 14 天走势。
 * 与 sidecar 的 tick 同构 —— 两条来源画出来必须是同一个东西。
 */
internal fun stripDataOf(days: List<UsageDay>, today: LocalDate): UsageStripData {
    val byDate = days.associateBy { it.date }
    val todayDay = byDate[today]
    return UsageStripData(
        todayTokens = todayDay?.models?.values?.sumOf { it.total() } ?: 0L,
        todayCost = todayDay?.models?.values?.sumOf { it.costUsd },
        spark = (13 downTo 0).map { back ->
            byDate[today.minusDays(back.toLong())]?.models?.values?.sumOf { it.total() } ?: 0L
        },
    )
}

/**
 * 这段时间里出现过的全部模型，按总量降序 —— 配色/图例的**全局**顺序。
 * 单根柱里的顺序（[UsageBucket.models]）会随天波动，颜色必须钉在这个顺序上。
 */
internal fun usageModelsOrdered(days: List<UsageDay>): List<String> {
    val acc = LinkedHashMap<String, Long>()
    for (d in days) {
        for ((m, t) in d.models) acc[m] = (acc[m] ?: 0L) + t.total()
    }
    return acc.entries.sortedByDescending { it.value }.map { it.key }
}

/** 一段柱序列里各模型的合计（图例、分解表用）。 */
internal fun usageTotalsByModel(buckets: List<UsageBucket>): Map<String, UsageModelTokens> {
    val acc = LinkedHashMap<String, UsageModelTokens>()
    for (b in buckets) {
        for ((m, t) in b.models) acc[m] = if (acc.containsKey(m)) acc.getValue(m) + t else t
    }
    return acc
}

/** 变化率 → 界面串（"+21%" / "-6%"）。null 原样（没上周数据时那段不显示）。 */
internal fun usageDeltaText(delta: Double?): String? {
    if (delta == null) return null
    // 四舍五入**按绝对值**再补符号：Math.round(-5.5) = -5（向正无穷取整），
    // 直接用它会出现 "-5%" 这种少跳一格的显示
    val pct = Math.round(Math.abs(delta) * 100)
    if (pct == 0L) return "0%"
    return if (delta > 0) "+$pct%" else "-$pct%"
}

/**
 * 模型 → 颜色。**顺序 = [usageModelsOrdered] 的顺序**：第一个用强调文字色
 * （与链接/文件名同一个蓝），其余四个固定锚色；超过五个循环。深浅两套照的是
 * 选型台那份调色板（与 web 侧 tone 同源），不是另拍一套。
 */
internal fun usageModelColors(models: List<String>, accentText: Color, dark: Boolean): Map<String, Color> {
    val palette = if (dark) {
        listOf(
            accentText,
            Color(0xa0, 0x6b, 0xd4), Color(0x3f, 0xa7, 0xc4),
            Color(0xd9, 0xa3, 0x43), Color(0x5f, 0xad, 0x65),
        )
    } else {
        listOf(
            accentText,
            Color(0x7a, 0x4f, 0xb0), Color(0x2b, 0x7c, 0x93),
            Color(0xb0, 0x7d, 0x1a), Color(0x3d, 0x8b, 0x43),
        )
    }
    return models.mapIndexed { i, m -> m to palette[i % palette.size] }.toMap()
}

/**
 * 统计框自己的「纸」：正文底 / 砖底 / 描边。
 *
 * **不跟宿主面板色走** —— 2026-10-10 用户按着选型台比对出来的：选型台的深色框是
 * 「近黑画布上的一张灰卡」（页底 #161719、框底 #2b2d30），而新 UI 的 Islands 系
 * 主题（用户当前那套）面板本身就是近黑（实测 #191A1C）—— 照主题上色，整个框
 * 黑掉，和当初选的不是一个东西。
 *
 * 所以这两套就是选型台那两行常量本身（web/tools/usage-view.mjs 的 THEMES），
 * 与 [usageModelColors] 同一个出处。文字与强调色仍从主题来 —— 只有「纸」是自己的。
 */
internal data class UsagePalette(val dark: Boolean, val bg: Color, val surface: Color, val border: Color)

internal fun usagePaletteFor(panelBg: Color): UsagePalette = if (isDarkTheme(panelBg)) {
    UsagePalette(true, Color(0x2b, 0x2d, 0x30), Color(0x1e, 0x1f, 0x22), Color(0x39, 0x3b, 0x40))
} else {
    UsagePalette(false, Color(0xf7, 0xf8, 0xfa), Color(0xff, 0xff, 0xff), Color(0xd1, 0xd1, 0xd1))
}

/** 主题是深是浅（背景亮度）。配色两套二选一用。 */
internal fun isDarkTheme(bg: Color): Boolean = luminance(bg) < 128

/** 成本数字（不含 `$`）：<1000 一位小数，再大取整。Locale.ROOT 防小数点变逗号。 */
internal fun formatUsageCost(v: Double): String =
    if (v < 1000) String.format(Locale.ROOT, "%.1f", v) else Math.round(v).toString()

/** 真实金额（元）：两位小数、ROOT locale —— 站点自己也这么显（`toFixed(2)`）。 */
internal fun formatBillYuan(v: Double): String = "¥" + String.format(Locale.ROOT, "%.2f", v)

/** 余额金额：按币种给符号（CNY→¥、USD→$）；认不出的币种把代码缀在后面（不再猜符号）。 */
internal fun formatBalanceAmount(currency: String, v: Double): String {
    val amount = String.format(Locale.ROOT, "%.2f", v)
    return when (currency.uppercase(Locale.ROOT)) {
        "CNY" -> "¥$amount"
        "USD" -> "\$$amount"
        else -> "$amount $currency".trim()
    }
}

/** 账单更新时间（HH:mm），对账条右上角那颗「更新 …」用。 */
internal fun formatBillTime(t: LocalTime): String =
    String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)

/**
 * 已用百分比（整数 + `%`）：12.4 → "12%"。**取整**——三档并排时小数点只是噪音，
 * 真正看的是"离满还有多远"。
 */
internal fun formatQuotaPercent(p: Double): String = "${Math.round(p).toInt()}%"

/**
 * 额度重置时刻 → 本机时间串：**来得及就说钟点**（"20:46"），超过一天带上日子（"10/19 08:00"）。
 *
 * [now] / [zone] 可注入 —— 用例里的"现在"是钉死的。时刻已过（小时数为负）也走钟点形，
 * 那是站点给了个过去的点，不值得为它多一行分支。
 */
internal fun formatQuotaReset(
    at: Instant,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val t = at.atZone(zone)
    return if (Duration.between(now, at).toHours() in 0..23) {
        String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    } else {
        String.format(Locale.ROOT, "%d/%d %02d:%02d", t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }
}

/**
 * 额度档的长名（统计框那三行的行首）。三分支**直写 `CcoderText.text("…")` 字面调用**
 * —— 词表扫描（TextKeysTest）只认这种，包一层变量它就看不见了。
 */
internal fun quotaWindowLabel(kind: QuotaWindowKind): String = when (kind) {
    QuotaWindowKind.Rolling -> CcoderText.text("usage.quota.window.rolling")
    QuotaWindowKind.Weekly -> CcoderText.text("usage.quota.window.weekly")
    QuotaWindowKind.Monthly -> CcoderText.text("usage.quota.window.monthly")
}

/** 额度档的短名（常驻条那三段："5h 12% · 周 4% · 月 2%"）。同上，字面调用。 */
internal fun quotaShortLabel(kind: QuotaWindowKind): String = when (kind) {
    QuotaWindowKind.Rolling -> CcoderText.text("usage.quota.short.rolling")
    QuotaWindowKind.Weekly -> CcoderText.text("usage.quota.short.weekly")
    QuotaWindowKind.Monthly -> CcoderText.text("usage.quota.short.monthly")
}

/**
 * 项目键比对：`\` → `/`、去尾斜杠（与 sidecar 的 `normalizeProj` 同一条规矩）。
 *
 * 为什么不能直接 `==`：`usageTick` 的 project 来自会话启动参数
 * （`Path.absolutePathString()` 在 Windows 上是**反斜杠**形），而面板手里的
 * basePath 是**正斜杠**形 —— 直接比，每一条 tick 都会被丢掉。症状是"账在记、
 * 条不动：只有新会话 / 重启 / 开统计框（那些走 report 的时机）才更新"
 *（2026-10-10 用户实机按出来的）。sidecar 那边 0.2.34 起也回规范形 ——
 * 这里再归一次是纵深防御。
 */
internal fun usageSameProject(a: String?, b: String?): Boolean {
    fun norm(s: String?): String? = s?.replace('\\', '/')?.trimEnd('/')
    val x = norm(a)
    val y = norm(b)
    return x != null && x.isNotEmpty() && x == y
}

/**
 * 「复制 CSV」的内容。表头**走词表**（复制出去的东西同样是界面文案，同
 * ContextDetail 的 Markdown）；行按日期升序、模型降序 —— 与屏幕上的顺序一致。
 */
internal fun usageCsvOf(days: List<UsageDay>): String {
    val sb = StringBuilder()
    sb.append(CcoderText.text("usage.col.date")).append(',')
        .append(CcoderText.text("usage.col.model")).append(',')
        .append(CcoderText.text("usage.col.input")).append(',')
        .append(CcoderText.text("usage.col.output")).append(',')
        .append(CcoderText.text("usage.col.cacheRead")).append(',')
        .append(CcoderText.text("usage.col.cacheWrite")).append(',')
        .append(CcoderText.text("usage.col.cost")).append('\n')
    for (day in days.sortedBy { it.date }) {
        val ordered = day.models.entries.sortedByDescending { it.value.total() }
        for ((model, t) in ordered) {
            sb.append(day.date).append(',')
                .append(csvField(model)).append(',')
                .append(t.inp).append(',')
                .append(t.out).append(',')
                .append(t.cacheRead).append(',')
                .append(t.cacheWrite).append(',')
                .append(String.format(Locale.ROOT, "%.4f", t.costUsd)).append('\n')
        }
    }
    return sb.toString()
}

/** 模型名里若有逗号/引号，按 CSV 规矩包一层 —— 真出现一次就毁整张表。 */
private fun csvField(s: String): String =
    if (s.contains(',') || s.contains('"')) '"' + s.replace("\"", "\"\"") + '"' else s

/**
 * 从**表**（而不是字面 `CcoderText.text("…")` 调用点）引用的那几个键。
 *
 * `TextKeysTest` 的"代码→词表"方向只认字面调用；这几个键藏在 when 分支与
 * 列表里，没有这一条，键打错了会一路静默到界面上显示成键名（同
 * `CONTEXT_ROW_KEYS` 那条）。对账用例见 `UsagePeriodsTest`。
 */
internal val USAGE_INDIRECT_KEYS: Set<String> = setOf(
    "usage.gran.day", "usage.gran.week", "usage.gran.month",
    "usage.range.day", "usage.range.week", "usage.range.month",
    "usage.scope.project", "usage.scope.all",
)
