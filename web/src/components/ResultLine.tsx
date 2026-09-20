import { t, useLang } from '../i18n'

/**
 * 回合结束那一行。
 *
 * 2026-09-15 用户要求改成"本次 token 消耗 + 耗时"（此前是 `success · $4.5460 · 33818ms`）。
 * 三个决定：
 *
 *  1. **不显示花费**：CLI 给的 `total_cost_usd` 是**累计值**（每次 result 给的是"到目前为止
 *     的总和"，`/clear` 还会清零），摆在某一回合下面会被读成"本次花费" —— 与其标一堆"累计"
 *     的限定语，不如不显示（数据仍在转写项里，留给将来的成本面板）。
 *  2. **token 取 `usage`**：它按回合给（`modelUsage` 是累计的）。代价是它只含主循环 ——
 *     子代理与压缩那几次调用不在里面，这条写在 Kotlin 侧 `renderResult` 的注释里。
 *  3. **数字与耗时要一眼读得懂**：`12.4k` 而不是 `12432`，`33.8s` 而不是 `33818ms`。
 */

export interface ResultParts {
  subtype: string
  durationMs?: number
  inputTokens?: number
  outputTokens?: number
  cacheReadTokens?: number
}

/**
 * token 数：最多三位有效数字。
 *
 * 阅读者要的是"量级"，不是个位（12432 与 12400 对判断"这轮贵不贵"没有区别），
 * 而一行里可能排着四个这样的数字。
 */
export function formatTokens(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '0'
  if (n < 1000) return String(Math.round(n))
  if (n < 100_000) return `${(n / 1000).toFixed(1)}k`
  if (n < 1_000_000) return `${Math.round(n / 1000)}k`
  return `${(n / 1_000_000).toFixed(1)}M`
}

/**
 * 耗时。
 *
 * 与进行中的秒表（[formatElapsed]）**故意不同**：那个每秒跳一次，给小数会看得眼花；
 * 这一行是静态的，一位小数正好（33.8s），而超过一分钟换成 `1m18s` ——
 * `78.3s` 要心算，`1m18s` 不用。
 */
export function formatResultDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '0s'
  // 先舍到一位小数再判断：59999ms 直接比 60 秒的话会写成「60.0s」，
  // 而读者看到的是"一分钟"却按秒记 —— 规整到 1m0s
  const seconds = Math.round(ms / 100) / 10
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  const total = Math.round(seconds)
  return `${Math.floor(total / 60)}m${total % 60}s`
}

/**
 * subtype 说人话。
 *
 * **只有 `success` 走目录**，其余 subtype（`error_max_turns`、将来 CLI 新加的）
 * 一律**原样透传**：编一个中文名（或换一个英文说法）比留着一串看不懂的英文更糟 ——
 * 那串是 CLI 的词汇，去搜它搜得到，编出来的名字搜不到。这条是既有的决定
 * （docs/superpowers/specs/2026-09-15-result-line-design.md §3），换语言也不动它。
 */
function subtypeText(subtype: string): string {
  return subtype === 'success' ? t('result.success') : subtype
}

/**
 * 那一行的文字。测试与渲染共用 —— 免得两边各拼一遍然后漂移。
 *
 * 读的是**当前**语言（t 走 i18n 的当前值），所以组件那边必须订阅语言，
 * 否则换语言之后这一行不会重画。
 */
export function resultLineText(parts: ResultParts): string {
  const out = [subtypeText(parts.subtype)]
  if (typeof parts.inputTokens === 'number') {
    out.push(t('result.input', [formatTokens(parts.inputTokens)]))
  }
  // 缓存命中为 0 时不显示：一行里多一个「缓存 0」是纯噪音
  if (typeof parts.cacheReadTokens === 'number' && parts.cacheReadTokens > 0) {
    out.push(t('result.cache', [formatTokens(parts.cacheReadTokens)]))
  }
  if (typeof parts.outputTokens === 'number') {
    out.push(t('result.output', [formatTokens(parts.outputTokens)]))
  }
  if (typeof parts.durationMs === 'number') out.push(formatResultDuration(parts.durationMs))
  return out.join(' · ')
}

export function ResultLine(props: ResultParts) {
  // 订阅：换语言时这一行要重画。`Item` 那边 memo 挡着，所以这里得自己订
  useLang()
  return <div className="result-line">{resultLineText(props)}</div>
}
