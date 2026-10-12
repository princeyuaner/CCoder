#!/usr/bin/env node
/**
 * 使用量 · 真实金额（供应商账单）· 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-12 用户："使用量这个功能，能否加个今日消耗的真实金额，使用 token 的信息，
 * 比如如果我选的是 dmx 的话，他的查询地址是 https://tokenlog.dmxapi.cn/，
 * 不要直接做，先给我出几个方案放浏览器给我选择"。
 *
 * 这一页就是"几个方案"：A 对账层 / B 人民币为纲 / C 真账账本 / D 零联网。
 * 三条保真规则照抄 tools/usage-view.mjs（色值取自 New UI 真实常量、几何照真机
 * 620 弹窗 / 420 工具窗口、深浅两套都渲染）。蓝色虚线 = 相对现状的新增。
 *
 * 数字是**示意**（与使用量那页同一套种子生成，"缓存读占大头"的真实形状）；
 * 人民币金额在示意上乘了一个常数折算（页面上也这么写）—— 落地前会先写探针拿
 * 真令牌把单位与字段口径对一遍（额度 ÷ 500000 = 元，照站点自己的算法）。
 *
 * DMXAPI 账单 API 的事实（2026-10-12 从 tokenlog.dmxapi.cn 站点上的"API 文档"
 * 弹窗里抄的）：GET、免登录、key 传令牌；默认查当天；start_date/end_date 可查
 * 区间（跨度上限 366 天）；一次返回 quota_yuan（消耗金额-元）、prompt/completion
 * tokens、缓存读/写（other 里）、model_stats[]、token_balance / account_balance；
 * 限速 10 次/分钟（超了 429）；错误 400/405/429/500。
 *
 * 用法：
 *   node web/tools/usage-cost-view.mjs --open        # 生成并用默认浏览器打开
 *   node web/tools/usage-cost-view.mjs --shot        # 生成并截图（深色）
 *   node web/tools/usage-cost-view.mjs --shot=light  # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'usage-cost-view.html')

// ---------------------------------------------------------------- 颜色运算

const rgb = (hex) => {
  const h = hex.replace('#', '')
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}
const hex = ([r, g, b]) =>
  '#' + [r, g, b].map((c) => Math.round(c).toString(16).padStart(2, '0')).join('')

/** 复刻 ThemeInjector.mix(base, fg, ratio)。 */
const mix = (base, fg, ratio) => {
  const b = rgb(base)
  const f = rgb(fg)
  return hex(b.map((c, i) => c + (f[i] - c) * ratio))
}

const THEMES = {
  dark: {
    pageBg: '#161719', pageFg: '#e8eaed', pageDim: '#8b8f97', pageLine: '#26282c',
    pageCard: '#1c1e21', code: '#2b2d30',
    bg: '#2b2d30', surface: '#1e1f22', text: '#dfe1e5', dim: '#9da0a8', border: '#393b40',
    accent: '#2f65ca', accentText: '#7fb0ff',
    ok: '#5fad65', warn: '#d9a343', danger: '#db5c5c', net: '#3fa7c4', task: '#a06bd4',
  },
  light: {
    pageBg: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageLine: '#e0e2e7',
    pageCard: '#ffffff', code: '#f2f3f5',
    bg: '#f7f8fa', surface: '#ffffff', text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', accentText: '#3574f0',
    ok: '#3d8b43', warn: '#b07d1a', danger: '#c0392b', net: '#2b7c93', task: '#7a4fb0',
  },
}

function tokens(t) {
  return `
    --page-bg:${t.pageBg}; --page-fg:${t.pageFg}; --page-dim:${t.pageDim};
    --page-line:${t.pageLine}; --page-card:${t.pageCard};
    --bg:${t.bg}; --surface:${t.surface}; --text:${t.text}; --dim:${t.dim};
    --border:${t.border}; --code:${t.code};
    --accent:${t.accent}; --accent-text:${t.accentText};
    --ok:${t.ok}; --warn:${t.warn}; --danger:${t.danger};
    --tone-net:${t.net}; --tone-task:${t.task};`
}

// ---------------------------------------------------------------- 示意数据
//
// 同 usage-view.mjs 的种子生成："缓存读占大头、周末轻、偶尔猛一天"的形状。
// 人民币不在生成器里 —— 它乘一个常数折算（示意），页面上写清了。

// 取**周日**：本周（周一–周日）是完整一周，不与今日重合、较上周的百分比也正常
// —— 示意数据要"像真的"，周一的话（本周=今日）两块砖会顶牛。
const TODAY = new Date(2026, 9, 11) // 2026-10-11（周日）
const MODEL_ORDER = ['opus', 'sonnet', 'haiku']
const MODEL_NAME = { opus: 'Opus 4.8', sonnet: 'Sonnet 5', haiku: 'Haiku 5.5' }
const MODEL_RATES = { opus: [5, 25], sonnet: [2, 10], haiku: [0.1, 0.5] }
const WD = ['一', '二', '三', '四', '五', '六', '日']

function mulberry32(a) {
  return function () {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const rnd = mulberry32(20261012)

const pad2 = (n) => String(n).padStart(2, '0')
const addD = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x }
const dowMon = (d) => (d.getDay() + 6) % 7
const keyOf = (d) => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
const labelOf = (d) => `${d.getMonth() + 1}/${d.getDate()}`
const dayNameOf = (d) => `周${WD[dowMon(d)]}`

const costOf = (m, x) => {
  const [rin, rout] = MODEL_RATES[m]
  return (x.cr / 1e6) * rin * 0.1 + (x.inp / 1e6) * rin + (x.cc / 1e6) * rin * 1.25 + (x.out / 1e6) * rout
}

const DAYS_N = 420
const days = []
for (let i = DAYS_N - 1; i >= 0; i--) {
  const d = addD(TODAY, -i)
  let base = 6.2e6 + rnd() * 5.6e6
  if (dowMon(d) >= 5) base *= 0.35
  if (rnd() < 0.07) base *= 1.6 + rnd() * 0.6
  base *= 1 + ((DAYS_N - i) / DAYS_N) * 0.25

  const opusShare = 0.34 + rnd() * 0.16
  const sonnetShare = 0.32 + rnd() * 0.16
  const shares = { opus: opusShare, sonnet: sonnetShare, haiku: 1 - opusShare - sonnetShare }

  const models = {}
  let total = 0
  let cost = 0
  let inp = 0
  let out = 0
  let cr = 0
  let cc = 0
  for (const m of MODEL_ORDER) {
    const tt = base * shares[m]
    const c = tt * (0.035 + rnd() * 0.03)
    const i2 = tt * (0.12 + rnd() * 0.05)
    const o = tt * (0.02 + rnd() * 0.02)
    const r = tt - c - i2 - o
    const x = { inp: i2, out: o, cr: r, cc: c }
    x.total = x.inp + x.out + x.cr + x.cc
    x.cost = costOf(m, x)
    models[m] = x
    total += x.total
    cost += x.cost
    inp += x.inp
    out += x.out
    cr += x.cr
    cc += x.cc
  }
  days.push({ d, key: keyOf(d), label: labelOf(d), dow: dowMon(d), models, total, cost, inp, out, cr, cc, sessions: 1 + Math.floor(rnd() * 6) })
}

function agg(list) {
  const r = { total: 0, cost: 0, inp: 0, out: 0, cr: 0, cc: 0, sessions: 0, models: {} }
  for (const m of MODEL_ORDER) r.models[m] = { total: 0, cost: 0, inp: 0, out: 0, cr: 0, cc: 0 }
  for (const x of list) {
    r.total += x.total; r.cost += x.cost; r.sessions += x.sessions
    r.inp += x.inp; r.out += x.out; r.cr += x.cr; r.cc += x.cc
    for (const m of MODEL_ORDER) {
      const mm = r.models[m]
      const which = x.models[m]
      mm.total += which.total; mm.cost += which.cost
      mm.inp += which.inp; mm.out += which.out; mm.cr += which.cr; mm.cc += which.cc
    }
  }
  return r
}

const rangeDays = (a, b) => days.filter((x) => x.d >= a && x.d <= b)
const LAST = days[days.length - 1]
const weekMon = addD(TODAY, -dowMon(TODAY))
const monthStart = new Date(TODAY.getFullYear(), TODAY.getMonth(), 1)

const A_today = agg([LAST])
const A_week = agg(rangeDays(weekMon, TODAY))
const A_lastWeek = agg(rangeDays(addD(weekMon, -7), addD(weekMon, -1)))
const A_month = agg(rangeDays(monthStart, TODAY))
const A30 = agg(days.slice(-30))
const weekDelta = A_lastWeek.total > 0 ? (A_week.total - A_lastWeek.total) / A_lastWeek.total : 0

/** ¥ 折算的示意常数（真实的汇率×网关折扣里外里凑出来的 —— 只用于这一页）。 */
const Y_PER_USD = 1.58
const Y_today = A_today.cost * Y_PER_USD
const Y_week = A_week.cost * Y_PER_USD
const Y_month = A_month.cost * Y_PER_USD
const Y_30 = A30.cost * Y_PER_USD
const BAL_Y = 123.40 // 令牌余额（示意）

// ---------------------------------------------------------------- 格式

const trim1 = (x) => String(Math.round(x * 10) / 10)
const fmtT = (v) => {
  if (v < 1000) return String(Math.round(v))
  if (v < 100000) return trim1(v / 1000) + 'k'
  if (v < 1000000) return String(Math.round(v / 1000)) + 'k'
  if (v < 100000000) return trim1(v / 1e6) + 'M'
  return String(Math.round(v / 1e6)) + 'M'
}
const fmtUsd = (v) => '$' + (v < 1000 ? (Math.round(v * 10) / 10).toFixed(1) : String(Math.round(v)))
const fmtY = (v) => '¥' + (v < 10000 ? v.toFixed(2) : String(Math.round(v)))
const pct = (x) => Math.round(x * 100) + '%'

// 账单 tok 口径：提示（含缓存读/写 + 纯输入）与补全 —— 站点自己的卡片就这么写。
const BILL_PROMPT = A_today.total - A_today.out
const billLine = () =>
  `账单 tok ${fmtT(A_today.total)}：提示 ${fmtT(BILL_PROMPT)}（含缓存读 ${fmtT(A_today.cr)} / 写 ${fmtT(A_today.cc)}）· 补全 ${fmtT(A_today.out)}`

// ---------------------------------------------------------------- 片段

const seg = (opts, on = 0) =>
  `<span class="seg">${opts.map((o, i) => `<i class="${i === on ? 'on' : ''}">${o}</i>`).join('')}</span>`

const tile = (label, val, sub, isNew = false) => `
      <div class="tile${isNew ? ' badge-new' : ''}">
        <div class="tile__lbl">${label}</div>
        <div class="tile__val">${val}</div>
        <div class="tile__sub">${sub}</div>
      </div>`

/** 账单对账条（A/B/C 共用；line1 主数字 + 余额/更新，line2 账单 tokens）。 */
const recon = (l1, isNew = true) => `
      <div class="recon${isNew ? ' badge-new' : ''}">
        <div class="recon__l1">${l1}</div>
        <div class="recon__l2">${billLine()}</div>
      </div>`

function stackedChart(list, { h = 110, labels = 5 } = {}) {
  const max = Math.max(...list.map((x) => x.total), 1)
  const cols = list.map((x) => {
    const H = Math.max(3, Math.round((x.total / max) * h))
    let used = 0
    const order = ['haiku', 'sonnet', 'opus']
    const segs = order.map((m, i) => {
      const share = x.total > 0 ? x.models[m].total / x.total : 0
      const px = i === order.length - 1 ? H - used : Math.round(H * share)
      used += px
      return `<i class="m-${m}" style="height:${Math.max(0, px)}px"></i>`
    })
    return `<span class="ccol">${segs.join('')}</span>`
  })
  const axis = labels > 0
    ? `<div class="axis">${list
        .map((x, i) => `<span>${(list.length - 1 - i) % labels === 0 ? x.label : ''}</span>`)
        .join('')}</div>`
    : ''
  return `<div class="chart" style="height:${h}px">${cols.join('')}</div>${axis}`
}

/** C 的金额版趋势：单色柱（¥ 一天一根）。 */
function moneyChart(list, { h = 110, labels = 5 } = {}) {
  const max = Math.max(...list.map((x) => x.cost), 1)
  const cols = list.map((x) => {
    const H = Math.max(3, Math.round((x.cost / max) * h))
    return `<span class="ccol"><i class="yy" style="height:${H}px"></i></span>`
  })
  const axis = labels > 0
    ? `<div class="axis">${list
        .map((x, i) => `<span>${(list.length - 1 - i) % labels === 0 ? x.label : ''}</span>`)
        .join('')}</div>`
    : ''
  return `<div class="chart" style="height:${h}px">${cols.join('')}</div>${axis}`
}

const modelLegend = (a) =>
  `<div class="legend">${MODEL_ORDER.map(
    (m) => `<span><i class="sw m-${m}"></i>${MODEL_NAME[m]} ${fmtT(a.models[m].total)}</span>`,
  ).join('')}</div>`

const chartCaption = (left, right) => `
      <div class="caption"><span>${left}</span><span class="spring"></span><span>${right}</span></div>`

const modelTable = (a, moneyCol = false) => {
  const rows = MODEL_ORDER.map((m) => {
    const mm = a.models[m]
    const share = mm.total / a.total
    const y = `<td class="tn">${fmtY(mm.cost * Y_PER_USD)}</td>`
    return `
          <tr>
            <td><i class="sw m-${m}"></i>${MODEL_NAME[m]}</td>
            <td><span class="pbar"><i class="m-${m}" style="width:${Math.max(2, Math.round(share * 100))}%"></i></span>${pct(share)}</td>
            <td class="tn">${fmtT(mm.total)}</td>
            <td class="tn">${fmtT(mm.cr)}</td>
            <td class="tn">≈${fmtUsd(mm.cost)}</td>
            ${moneyCol ? y : ''}
          </tr>`
  }).join('')
  return `
      <table class="led">
        <tr><th>模型</th><th>占比</th><th class="tn">用量</th><th class="tn">缓存读</th><th class="tn">≈成本</th>${moneyCol ? '<th class="tn">实扣</th>' : ''}</tr>
        ${rows}
      </table>`
}

const dlg = (title, body, foot) => `
      <div class="dlg">
        <div class="dlg-bar"><span>${title}</span><span class="dlg-x">✕</span></div>
        <div class="dlg-body">${body}</div>
        <div class="dlg-foot"><span class="dim">${foot}</span><span class="spring"></span><span class="btn">复制 CSV</span><span class="btn btn--p">关闭</span></div>
      </div>`

const toolbar = () =>
  `<div class="toolbar">${seg(['按日', '按周', '按月'])}<span class="spring"></span><span class="chip">本项目 ▾</span></div>`

const FOOT_CURRENT = `含子代理与压缩 · 成本为按 API 单价的估算，非账单`
const FOOT_A = `含子代理与压缩 · ≈ 列为 API 单价估算 · 实扣为 DMXAPI 令牌账（本令牌 · 与项目无关）`
const FOOT_B = `金额为 DMXAPI 实扣（¥）· token 为本地账本 · 表中 ≈ 列为 API 单价估算`
const FOOT_C = `金额为 DMXAPI 实扣（¥，已本地落盘）· token 为本地账本 · 表中 ≈ 列为 API 单价估算`

// ---- 统计框：四个方案 ----

const statsBody = (tilesHtml, midHtml, chartHtml, a) =>
  [toolbar(), `<div class="tiles">${tilesHtml}</div>`, midHtml,
    chartCaption('近 30 天 · 按日', `合计 ${fmtT(a.total)} · ≈${fmtUsd(a.cost)}`),
    chartHtml, modelLegend(a), modelTable(a)].join('')

// A：三块砖不动；中间多一条对账条（今日实扣）。
const dA = () => dlg(
  '使用量统计',
  statsBody(
    tile('今日', fmtT(A_today.total), `${pct(A_today.cr / A_today.total)} 缓存 · ≈${fmtUsd(A_today.cost)}`) +
    tile('本周', fmtT(A_week.total), `较上周 ${weekDelta >= 0 ? '+' : '−'}${Math.abs(Math.round(weekDelta * 100))}%`) +
    tile('本月', fmtT(A_month.total), `${A_month.sessions} 个会话`),
    recon(
      `今日实扣 <b>${fmtY(Y_today)}</b> · 令牌余额 ${fmtY(BAL_Y)}<span class="rfr">更新 14:32 · ↻</span>`,
    ),
    stackedChart(days.slice(-30), { h: 110, labels: 5 }),
    A30,
  ),
  FOOT_A,
)

// B：三块砖主数字直接是 ¥ 实扣；token 退到副行。
const dB = () => dlg(
  '使用量统计',
  statsBody(
    tile('今日', fmtY(Y_today), `${fmtT(A_today.total)} tok · ≈${fmtUsd(A_today.cost)}`, true) +
    tile('本周', fmtY(Y_week), `${fmtT(A_week.total)} tok · ≈${fmtUsd(A_week.cost)}`, true) +
    tile('本月', fmtY(Y_month), `${fmtT(A_month.total)} tok · ≈${fmtUsd(A_month.cost)}`, true),
    recon(`DMXAPI 账单（本令牌）· 令牌余额 ${fmtY(BAL_Y)}<span class="rfr">更新 14:32 · ↻</span>`),
    stackedChart(days.slice(-30), { h: 110, labels: 5 }),
    A30,
  ),
  FOOT_B,
)

// C：B + 历史趋势也换成 ¥（每日已同步进本地账本的画法）。
const dC = () => dlg(
  '使用量统计',
  [
    toolbar(),
    `<div class="tiles">${[
      tile('今日', fmtY(Y_today), `${fmtT(A_today.total)} tok · ≈${fmtUsd(A_today.cost)}`, true),
      tile('本周', fmtY(Y_week), `${fmtT(A_week.total)} tok · ≈${fmtUsd(A_week.cost)}`, true),
      tile('本月', fmtY(Y_month), `${fmtT(A_month.total)} tok · ≈${fmtUsd(A_month.cost)}`, true),
    ].join('')}</div>`,
    recon(`实扣账本：已同步 32 天 · 令牌余额 ${fmtY(BAL_Y)}<span class="rfr">10-11 14:31 更新 · ↻</span>`),
    chartCaption('近 30 天 · 按日（实扣 ¥）', `合计 ${fmtY(Y_30)} · ≈${fmtUsd(A30.cost)}`),
    moneyChart(days.slice(-30), { h: 110, labels: 5 }),
    modelLegend(A30),
    modelTable(A30, true),
  ].join(''),
  FOOT_C,
)

// D：一切照旧；脚注多一个跳转链接。
const dD = () => dlg(
  '使用量统计',
  statsBody(
    tile('今日', fmtT(A_today.total), `${pct(A_today.cr / A_today.total)} 缓存 · ≈${fmtUsd(A_today.cost)}`) +
    tile('本周', fmtT(A_week.total), `较上周 ${weekDelta >= 0 ? '+' : '−'}${Math.abs(Math.round(weekDelta * 100))}%`) +
    tile('本月', fmtT(A_month.total), `${A_month.sessions} 个会话`),
    '',
    stackedChart(days.slice(-30), { h: 110, labels: 5 }),
    A30,
  ),
  `${FOOT_CURRENT} · <span class="lnk on-new">在 DMXAPI 查看今日账单 ↗</span>`,
)

// ---- 常驻条：三个变体（D 不变） ----

const strip = (valueHtml) => `
      <div class="usestrip">
        <span class="usestrip__t">使用量</span>
        <span class="usestrip__v">${valueHtml}</span>
        <span class="spark">${days.slice(-14).map((x, i) => {
          const max = Math.max(...days.slice(-14).map((y) => y.total))
          const h = Math.max(3, Math.round((x.total / max) * 15))
          return `<i class="${i === 13 ? 'on' : ''}" style="height:${h}px"></i>`
        }).join('')}</span>
        <span class="usestrip__chev">›</span>
      </div>`

const twMini = (inner) => `
      <div class="tw tw--mini">
        <div class="tw-bar"><span class="ic"></span>CCoder</div>
        <div class="tr tr--tight">${inner}</div>
      </div>`

const stripA = twMini(strip(
  `今日 <b>${fmtT(A_today.total)}</b> · ≈${fmtUsd(A_today.cost)} · <span class="on-new">实扣${fmtY(Y_today)}</span>`,
))
const stripB = twMini(strip(
  `今日 <b>${fmtT(A_today.total)}</b> · <span class="on-new">实扣${fmtY(Y_today)}</span>`,
))

// ---------------------------------------------------------------- 页面

const ORIGIN = 'https://tokenlog.dmxapi.cn/'

function page(theme) {
  const T = THEMES[theme]
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>使用量 · 真实金额 · 选型台</title>
<style>
:root { --ui: -apple-system, "Segoe UI", "Microsoft YaHei UI", system-ui, sans-serif;
        --mono: "JetBrains Mono", "Cascadia Mono", Consolas, "Sarasa Mono SC", monospace;
        --serif: Georgia, "Songti SC", "Noto Serif SC", "SimSun", serif; }
[data-theme="dark"] {${tokens(THEMES.dark)} }
[data-theme="light"] {${tokens(THEMES.light)} }

* { box-sizing: border-box; }
html, body { margin: 0; }
body { background: var(--page-bg); color: var(--page-fg); font-family: var(--ui);
       font-size: 14px; line-height: 1.6; -webkit-font-smoothing: antialiased; }
.wrap { max-width: 1500px; margin: 0 auto; padding: 52px 32px 120px; }

.masthead { display:flex; align-items:flex-end; justify-content:space-between; gap:32px;
            flex-wrap:wrap; padding-bottom: 22px; border-bottom: 1px solid var(--page-line); }
h1 { font-family: var(--serif); font-size: 36px; font-weight: 600; margin: 0 0 10px; line-height:1.15; }
.lede { color: var(--page-dim); max-width: 78ch; margin: 0; font-size: 14.5px; }
.lede b { color: var(--page-fg); }
.eyebrow { font-family: var(--mono); font-size: 11px; letter-spacing:.14em; text-transform: uppercase;
           color: var(--page-dim); margin: 0 0 14px; }
.toggle { display:inline-flex; border:1px solid var(--page-line); border-radius: 8px; overflow:hidden; background: var(--page-card); }
.toggle button { font-family: var(--mono); font-size: 11px; letter-spacing:.08em; text-transform:uppercase;
                 padding: 9px 16px; border:0; background:transparent; color: var(--page-dim); cursor:pointer; }
.toggle button[aria-pressed="true"] { background: var(--accent); color: #fff; }

.brief { margin-top: 30px; padding: 20px 24px; border:1px solid var(--page-line);
         border-left: 3px solid var(--accent); border-radius: 0 10px 10px 0; background: var(--page-card); }
.brief h3 { margin: 0 0 8px; font-size: 14px; font-weight: 600; }
.brief p { margin: 0 0 8px; color: var(--page-dim); font-size: 13.5px; max-width: 100ch; }
.brief p:last-child { margin-bottom: 0; }
.brief code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
.brief b { color: var(--page-fg); }

h2.sect { font-family: var(--serif); font-size: 25px; font-weight: 600; margin: 54px 0 4px; }
.sect-note { color: var(--page-dim); font-size: 13.5px; margin: 0 0 22px; max-width: 100ch; }
.sect-note b { color: var(--page-fg); }

.grid--wide { display:grid; grid-template-columns: repeat(auto-fit, minmax(680px, 1fr)); gap: 26px; align-items:start; }
.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px; padding: 18px 18px 20px; }
.opt__head { display:flex; gap: 12px; margin-bottom: 8px; }
.opt__pick { font-family: var(--mono); font-size: 12px; letter-spacing:.08em; color:#fff; background: var(--accent);
             width: 30px; height: 30px; border-radius: 8px; display:flex; align-items:center; justify-content:center; flex:0 0 auto; }
.opt__name { font-size: 15px; font-weight: 600; }
.opt__note { color: var(--page-dim); font-size: 12.5px; margin-top: 3px; max-width: 76ch; }
.opt__note code { font-family: var(--mono); font-size: 11.5px; background: var(--code); padding: 1px 4px; border-radius: 3px; color: var(--text); }
.opt__gain { margin: 10px 0 0; padding: 0; list-style: none; font-size: 12.5px; color: var(--page-dim); }
.opt__gain li { margin: 2px 0 2px 0; padding-left: 18px; position: relative; }
.opt__gain li::before { content:'·'; position:absolute; left:6px; color: var(--accent-text); font-weight:700; }
.opt__gain b { color: var(--page-fg); }
.mocklabel { font-size: 11px; color: var(--page-dim); margin: 14px 0 6px; }
.stack { display:flex; flex-direction:column; gap: 12px; align-items:flex-start; }

/* ---- 新增标注 ---- */
.badge-new { position: relative; }
.badge-new::after { content:'新增'; position:absolute; top:-8px; right:8px; background: var(--accent); color:#fff;
                    font-family: var(--mono); font-size:9px; line-height:14px; padding:0 5px; border-radius:7px; letter-spacing:.05em; }
.on-new { outline: 1.5px dashed var(--accent); outline-offset: 2px; border-radius: 6px; }

/* ---- 统计框壳（620，真机配色）---- */
.dlg { width: 620px; max-width: 100%; border:1px solid var(--border); border-radius: 10px;
       overflow:hidden; background: var(--bg); box-shadow: 0 10px 30px rgba(0,0,0,.22); }
.dlg-bar { display:flex; align-items:center; padding: 8px 12px; background: var(--surface);
           border-bottom:1px solid var(--border); font-size: 12.5px; color: var(--text); }
.dlg-x { margin-left:auto; color: var(--dim); font-family: var(--mono); font-size: 12px; }
.dlg-body { padding: 12px 14px; display:flex; flex-direction:column; gap: 10px; }
.dlg-foot { display:flex; align-items:center; gap:8px; padding: 9px 12px; border-top:1px solid var(--border);
            background: var(--bg); font-size: 11px; color: var(--text); }
.dlg-foot span.btn { color: var(--text); }
.btn { font-size: 12px; padding: 4px 14px; border-radius: 6px; border:1px solid var(--border);
       color: var(--text); background: transparent; }
.btn--p { background: var(--accent); border-color: var(--accent); color:#fff !important; }
.dim { color: var(--dim); }
.spring { flex: 1 1 auto; }

.toolbar { display:flex; align-items:center; gap: 8px; }
.seg { display:inline-flex; border:1px solid var(--border); border-radius: 6px; overflow:hidden; background: var(--bg); }
.seg i { font-style: normal; font-size: 11px; padding: 3px 11px; color: var(--dim); border-right: 1px solid var(--border); }
.seg i:last-child { border-right: 0; }
.seg i.on { background: color-mix(in srgb, var(--accent) 26%, transparent); color: var(--text); }
.chip { border:1px solid var(--border); border-radius: 5px; padding: 2px 8px; font-size: 11px; color: var(--dim); }

.tiles { display:flex; gap: 10px; }
.tile { flex:1; border:1px solid var(--border); border-radius: 8px; padding: 7px 10px 8px; background: var(--surface); }
.tile__lbl { font-size: 10.5px; color: var(--dim); }
.tile__val { font-size: 19px; line-height: 1.25; color: var(--text); font-family: var(--mono); }
.tile__sub { font-size: 10.5px; color: var(--dim); }

.recon { border:1.5px dashed var(--accent); border-radius: 7px; padding: 5px 10px 6px;
         background: color-mix(in srgb, var(--accent) 9%, transparent); }
.recon__l1 { display:flex; align-items:center; gap: 6px; font-size: 11.5px; color: var(--text); }
.recon__l1 b { font-family: var(--mono); font-size: 13px; }
.recon__l2 { font-size: 10.5px; color: var(--dim); }
.recon .rfr { margin-left:auto; font-size: 10.5px; color: var(--dim); }

.caption { display:flex; align-items:center; font-size: 11px; color: var(--dim); }
.chart { display:flex; align-items:flex-end; gap: 3px; border-bottom: 1px solid var(--border); }
.ccol { flex:1; display:flex; flex-direction:column; justify-content:flex-end; height:100%; min-width: 0; }
.ccol i { display:block; width: 100%; }
.ccol > i:first-child { border-radius: 2px 2px 0 0; }
i.m-opus { background: var(--accent-text); }
i.m-sonnet { background: var(--tone-task); }
i.m-haiku { background: var(--tone-net); }
i.yy { background: var(--accent-text); opacity: .85; border-radius: 2px 2px 0 0; }
.axis { display:flex; gap: 3px; }
.axis span { flex:1; text-align:center; font-size: 9px; font-family: var(--mono); color: var(--dim); min-width:0; }
.legend { display:flex; gap: 14px; font-size: 10.5px; color: var(--dim); align-items:center; }
.sw { display:inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 5px; vertical-align: middle; }
.sw.m-opus { background: var(--accent-text); }
.sw.m-sonnet { background: var(--tone-task); }
.sw.m-haiku { background: var(--tone-net); }

table.led { width:100%; border-collapse: collapse; font-size: 12px; }
table.led th { text-align:left; font-size: 10px; color: var(--dim); font-family: var(--mono);
               font-weight: 500; padding: 3px 6px; border-bottom: 1px solid var(--border); }
table.led td { padding: 3px 6px; border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent); color: var(--text); }
table.led td.tn, table.led th.tn { text-align:right; font-family: var(--mono); font-size: 11px; }
table.led .pbar { display:inline-block; width: 64px; height: 5px; border-radius: 3px; margin-right: 8px;
                  background: color-mix(in srgb, var(--border) 45%, transparent); overflow:hidden; vertical-align: middle; }
table.led .pbar i { display:block; height:100%; }

/* ---- 420 工具窗口（真机宽度）---- */
.tw { width: 420px; max-width:100%; background: var(--bg);
      border:1px solid var(--border); border-radius: 8px; overflow:hidden; display:flex; flex-direction:column; }
.tw--mini { width: 420px; }
.tw-bar { display:flex; align-items:center; gap:7px; padding: 6px 10px; background: var(--surface);
          border-bottom:1px solid var(--border); font-size: 11.5px; color: var(--dim); }
.tw-bar .ic { width:11px; height:11px; border-radius:2px; background: var(--border); }
.tr { padding: 12px; background: var(--bg); display:flex; flex-direction:column; gap: 9px; min-height: 40px; }
.tr--tight { padding: 8px; gap: 7px; }

.usestrip { display:flex; align-items:center; gap: 8px; border:1px solid var(--border);
            border-radius: 7px; background: var(--surface); padding: 4px 9px; cursor: pointer; }
.usestrip__t { font-size: 10.5px; color: var(--dim); flex: 0 0 auto; }
.usestrip__v { font-size: 11px; color: var(--text); flex: 0 0 auto; }
.usestrip__v b { font-family: var(--mono); font-size: 12px; }
.spark { display:flex; align-items:flex-end; gap: 2px; height: 16px; margin-left: auto; }
.spark i { width: 3px; border-radius: 1px; background: color-mix(in srgb, var(--accent-text) 55%, transparent); }
.spark i.on { background: var(--accent-text); }
.usestrip__chev { color: var(--dim); font-family: var(--mono); font-size: 12px; }

.lnk { color: var(--accent-text); text-decoration: underline dotted; }

/* ---- 代价表 ---- */
table.cmp { width:100%; border-collapse: collapse; margin-top: 20px; font-size: 12.5px; }
table.cmp th, table.cmp td { text-align:left; padding: 9px 10px; border-bottom:1px solid var(--page-line); vertical-align: top; }
table.cmp th { color: var(--page-dim); font-family: var(--mono); font-size: 10.5px; letter-spacing:.1em;
               text-transform:uppercase; font-weight:500; }
table.cmp td { color: var(--page-dim); }
table.cmp td:first-child { color: var(--text); white-space:nowrap; }
table.cmp code { font-family: var(--mono); font-size: 11.5px; background: var(--code); padding: 1px 4px; border-radius: 3px; color: var(--text); }
.yes { color: var(--ok); }

.verdict { margin-top: 46px; padding: 24px 26px; border:1px solid var(--page-line); border-radius: 14px;
           background: var(--page-card); border-left: 3px solid var(--ok); }
.verdict h2 { font-family: var(--serif); font-size: 24px; margin: 0 0 12px; }
.verdict p { color: var(--page-dim); margin: 0 0 10px; max-width: 92ch; }
.verdict b { color: var(--page-fg); }
.verdict code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
</style>
</head>
<body>
<div class="wrap">
  <div class="masthead">
    <div>
      <p class="eyebrow">2026-10-12 · 选型台 · 不参与构建</p>
      <h1>使用量 · 真实金额：四个方案</h1>
      <p class="lede">给「使用量」接上<b>供应商账单的真实金额</b> —— 先支持 DMXAPI
        （<code>tokenlog.dmxapi.cn</code> 的令牌日志 API：免登录、返回 JSON）。四个方案由轻到重；
        <b>蓝色虚线 = 相对现状的新增</b>。数字是示意（同一套种子生成 + 一个示意折算常数）。
        深色打底，右上角切浅色。</p>
    </div>
    <div class="toggle">
      <button id="dk" aria-pressed="true">深色</button><button id="lt" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先把口径摆清楚</h3>
    <p><b>账单源（站点自带 API，2026-10-12 实测它的文档弹窗）</b>：<code>GET ${ORIGIN}?key=sk-…</code>
      —— 免登录、JSON；默认查<b>当天</b>，也能按 <code>start_date / end_date</code> 查区间（跨度上限 366 天）。
      一次查询给：<b>消耗金额（元）</b>、提示/补全 tokens、缓存读/写、按模型汇总（<code>model_stats</code>）、
      令牌余额 / 账户余额，以及逐条请求明细。限制：<b>10 次/分钟</b>（超了 429）。</p>
    <p><b>密钥从哪来</b>：就用<b>当前选中的模型配置</b>那把 —— <code>baseUrl</code> 命中 <code>dmxapi.cn</code>
      时自动认定 DMXAPI，密钥从 PasswordSafe 现取（就是会话在用的那枚），不新增存储、不给 sidecar；
      查询在插件进程里发，失败就退回现状。</p>
    <p><b>实扣 ≠ 估算，界面上要写清</b>：估算（现状）＝本地账本 × API 单价，<b>美元、只算这台机器</b>、离线可用；
      实扣＝DMXAPI 的账，<b>人民币、算这枚令牌下所有来源</b>、含网关自己的缓存计价。对不上是正常的 ——
      本页所有方案都不把两个数糊成一个，各带各的口径字样。</p>
    <p><b>先钉死预期（做不到的）</b>：账单<b>分不了「本项目」</b>（接口没有项目字段）—— 口径只能是「本令牌 · 今天」；
      历史逐日要按天拉（受 10 次/分限速，30 天 ≈ 3 分钟）；官方 Anthropic 的账单要 Admin Key（本期不做，留槽位）；
      请求级明细（站点整页那种）本期不做 —— 要看跳过去看（方案 D）。</p>
    <p><b>数字全是示意</b>：人民币金额是一个示意折算常数乘出来的；落地前先写探针拿真令牌把
      单位与字段口径对一遍（<code>额度 ÷ 500000 = 元</code> 是站点自己的算法）。</p>
  </div>

  <h2 class="sect">一、四个方案</h2>
  <p class="sect-note">每个方案画两处：<b>统计框（620 宽）</b>与<b>常驻条（420 工具窗口里那条矮行）</b>。</p>

  <div class="grid--wide">
    <div class="opt">
      <div class="opt__head"><span class="opt__pick">A</span><div>
        <div class="opt__name">对账层：今日实扣，与估算并列</div>
        <div class="opt__note">改动最小：统计框三块砖下面多一条「DMXAPI 账单」对账条（今日实扣 + 令牌余额 +
          账单 tokens），常驻条尾部多一段 <code>实扣${fmtY(Y_today)}</code>。会话就绪后查一次、之后每 60 秒至多一次
          （在 tick 上搭车）；统计框打开时缓存过期就重查，条上带 ↻ 手动刷新。查不到就退回今天的样子 ——
          估算永远在，实扣是叠加。</div>
      </div></div>
      <ul class="opt__gain">
        <li><b>得到</b>：今日真实金额（¥）+ 账单 tokens（提示/补全/缓存）+ 余额，一眼可见；</li>
        <li><b>不动</b>：三块砖、图、表、CSV 全是现状，估算跨所有供应商照旧；</li>
        <li><b>代价</b>：一个适配器类 + 一条 UI 行；口径文案要写清「本令牌 ≠ 本项目」。</li>
      </ul>
      <p class="mocklabel">统计框（620px）：</p>
      ${dA()}
      <p class="mocklabel">常驻条（420px 工具窗口内）：</p>
      ${stripA}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">B</span><div>
        <div class="opt__name">人民币为纲：三块砖直接是 ¥</div>
        <div class="opt__note">今日 / 本周 / 本月三个主数字全部换成 DMXAPI 实扣（打开时三个范围查询：
        今天 / 本周一以来 / 本月以来，同样节流缓存）；token 退到副行，估算以小字保留。
        趋势图仍是 token 口径（金额趋势要 C）。</div>
      </div></div>
      <ul class="opt__gain">
        <li><b>得到</b>：「这周花了多少钱」一屏回答，全是人民币；</li>
        <li><b>不动</b>：常驻条的走势、模型表、CSV 结构；</li>
        <li><b>代价</b>：估算退居次要（换口味，有人会怀念 $ 口径）；每次刷新 3 个查询。</li>
      </ul>
      <p class="mocklabel">统计框（620px）：</p>
      ${dB()}
      <p class="mocklabel">常驻条（估算换掉，只留实扣）：</p>
      ${stripB}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">C</span><div>
        <div class="opt__name">真账账本：历史趋势也变人民币</div>
        <div class="opt__note">在 B 之上，把每日实扣同步进本地账本（首刷近 30 天逐日拉 ≈ 3 分钟 ——
        10 次/分钟的限速摆着，之后每天只补 1 次），趋势图 / 表格 / CSV 全出 ¥，重启不丢。</div>
      </div></div>
      <ul class="opt__gain">
        <li><b>得到</b>：金额趋势、按模型实扣、导出的都是真实账；不再依赖「打开时现查」；</li>
        <li><b>不动</b>：现有账本（token 那本）原样，新加一列来源标记；</li>
        <li><b>代价</b>：多一个落盘/同步子系统（sidecar 背）+ 首刷等待；新装机时历史金额段是空的。</li>
      </ul>
      <p class="mocklabel">统计框（620px，趋势也换成 ¥）：</p>
      ${dC()}
      <p class="mocklabel">常驻条（同 B）：</p>
      ${stripB}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">D</span><div>
        <div class="opt__name">零联网：只放一个跳转</div>
        <div class="opt__note">插件不再发任何账单请求：统计框脚注多一个「在 DMXAPI 查看今日账单 ↗」，
        点击用系统浏览器打开站点（可选「带上 key 预填」开关，默认不带 —— 密钥不进浏览器历史）。
        可选再加一个「手动抄录今日 ¥」的小入口。</div>
      </div></div>
      <ul class="opt__gain">
        <li><b>得到</b>：想知道就去站点看全文（含请求级明细）；</li>
        <li><b>不动</b>：一切照旧，密钥不出插件进程；</li>
        <li><b>代价</b>：还是手动；常驻条永远没有实扣。</li>
      </ul>
      <p class="mocklabel">统计框（620px，只动脚注）：</p>
      ${dD()}
      <p class="mocklabel">常驻条：与现状相同，不用改。</p>
    </div>
  </div>

  <h2 class="sect">二、代价对照</h2>
  <table class="cmp">
    <tr><th>方案</th><th>界面得到</th><th>数据来源</th><th>实现量</th><th>代价 / 风险</th></tr>
    <tr>
      <td>A 对账层</td>
      <td>常驻条 + 统计框各多一段「实扣（今日）」</td>
      <td>打开时 1 次查询 + 60s 节流缓存</td>
      <td>一个适配器 + 一条 UI 行（插件侧）</td>
      <td>文案要说清「本令牌 ≠ 本项目」；窄条要留降级</td>
    </tr>
    <tr>
      <td>B 人民币为纲</td>
      <td>三块砖主数字 = ¥ 实扣（今日/本周/本月）</td>
      <td>打开时 3 次范围查询</td>
      <td>A + 2 个范围查询 + 砖的改画</td>
      <td>估算退居次要；换口味要接受</td>
    </tr>
    <tr>
      <td>C 真账账本</td>
      <td>历史趋势 / 表 / CSV 全 ¥</td>
      <td>后台逐日同步 + 本地落盘</td>
      <td>B + 账本子系统（sidecar）</td>
      <td>首刷等 ≈3 分钟；新装机历史金额段空着</td>
    </tr>
    <tr>
      <td>D 零联网</td>
      <td>只多一个外链（+ 可选手动抄录）</td>
      <td>无（跳去站点看）</td>
      <td>一个链接</td>
      <td>手动；常驻条永远没有实扣</td>
    </tr>
  </table>

  <div class="verdict">
    <h2>我的建议</h2>
    <p><b>A</b>：正好回答「今天花了多少真钱」—— 估算一条不丢，实扣是叠加层，改动是一个适配器 + 一条 UI 行。
      <span class="yes">推荐从这里起步。</span></p>
    <p><b>B</b> 是把同一个适配器再切深一档（打开时多两个范围查询，实现差得不多）；想要「周/月的钱也看人民币」就选 B。
      <b>C</b> 只在「历史趋势也要全人民币」时值得 —— 要多养一个落盘/同步子系统。 <b>D</b> 是不想让插件再碰密钥的退路。</p>
    <p>可单独勾的：常驻条三档（不显示实扣 / 并列 / 替换估算 —— 图里画的是并列与替换）、余额显不显、
      ↻ 刷新键、差额百分比、按模型实扣（今天的 <code>model_stats</code> 就有）、请求级明细（后置）。</p>
    <p>看完回一个字母就行：<code>A</code>、<code>B</code>、<code>C</code>、<code>D</code>，
      也可以带修饰，比如 <code>A + 常驻条替换估算</code>、<code>B + 不要余额</code>。</p>
  </div>
</div>

<script>
  const swap = (theme) => {
    document.documentElement.setAttribute('data-theme', theme)
    document.getElementById('dk').setAttribute('aria-pressed', String(theme === 'dark'))
    document.getElementById('lt').setAttribute('aria-pressed', String(theme === 'light'))
  }
  document.getElementById('dk').onclick = () => swap('dark')
  document.getElementById('lt').onclick = () => swap('light')
</script>
</body>
</html>
`
}

mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, page('dark'))
console.log('已生成', outFile)

const CHROMIUM_CANDIDATES = [
  process.env.CCoder_CHROMIUM,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
].filter(Boolean)

const shotArg = process.argv.find((a) => a.startsWith('--shot'))
const theme = shotArg && shotArg.includes('light') ? 'light' : 'dark'
const url = 'file:///' + outFile.replace(/\\/g, '/')

if (shotArg) {
  const chromium = CHROMIUM_CANDIDATES.find(existsSync)
  if (!chromium) {
    console.error('找不到 Chromium，截图跳过')
    process.exitCode = 2
  } else {
    // 浅色那张另存一份把 data-theme 写死的 html —— headless 里靠脚本切换实测不生效
    let target = outFile
    let name = 'usage-cost-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'usage-cost-view-light.html')
      writeFileSync(target, page('light'))
      name = 'usage-cost-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1500,5600',
       '--virtual-time-budget=2500', `--screenshot=${join(outDir, name)}`,
       'file:///' + target.replace(/\\/g, '/')],
      { stdio: 'ignore' },
    )
    console.log('截图 →', join(outDir, name))
  }
}
if (process.argv.includes('--open')) {
  execFileSync('cmd', ['/c', 'start', '', url], { stdio: 'ignore' })
  console.log('已在默认浏览器打开')
}
