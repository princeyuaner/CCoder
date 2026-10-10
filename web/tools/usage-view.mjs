#!/usr/bin/env node
/**
 * 使用量统计 · 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-10 用户："sdk 支持 token 使用量的统计吗，比如我想看每日每周每月的
 * token 使用情况" → "直接写代码吧。先出个效果图给我选择"。这一页就是"效果图"：
 * 三套画法（V1 仪表盘 / V2 热力图 / V3 账目表）+ 两个入口（E1 常驻条 / E2 菜单项）。
 *
 * 三条保真规则（照抄 tools/subagent-view.mjs 的做法）：
 *
 *   1. 色值不是手填的：`--bg/--surface/--text/--border` 取自 New UI 的真实常量，
 *      热力图那五档按 `ThemeInjector.mix()` 的**同一条公式**现算；
 *   2. 几何照真机：工具窗口 420、状态卡一行 404（四张 97 + 间距 5）、弹窗 620
 *      （比现有 CARD_WIDTH=420 宽一档 —— 图表要地方，这正是要选的事之一）；
 *   3. 深色 / 浅色两套都渲染 —— 热力图深浅、成本那些小字换一套主题就可能看不见。
 *
 * 数字是**示意数据**（固定种子生成，模了"缓存读占大头"的真实形状）；
 * 成本按 API 单价折算的估算，不是账单 —— 页面上也这么写。
 *
 * 用法：
 *   node tools/usage-view.mjs --open          # 生成并用默认浏览器打开
 *   node tools/usage-view.mjs --shot          # 生成并截图（深色）
 *   node tools/usage-view.mjs --shot=light    # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'usage-view.html')

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

/**
 * 两套主题。`bg` = 面板底（ThemeInjector 的 `--bg` 取的就是 getPanelBackground），
 * `surface` = 输入框底（气泡底），`text`/`dim` = 正文与次要色。
 */
const THEMES = {
  dark: {
    pageBg: '#161719', pageFg: '#e8eaed', pageDim: '#8b8f97', pageLine: '#26282c',
    pageCard: '#1c1e21', code: '#2b2d30',
    bg: '#2b2d30', surface: '#1e1f22', text: '#dfe1e5', dim: '#9da0a8', border: '#393b40',
    accent: '#2f65ca', accentText: '#7fb0ff',
    ok: '#5fad65', warn: '#d9a343', danger: '#db5c5c', run: '#d9a343', net: '#3fa7c4',
    task: '#a06bd4', write: '#5fad65',
    thinkAnchor: '#cdc5b2',
  },
  light: {
    pageBg: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageLine: '#e0e2e7',
    pageCard: '#ffffff', code: '#f2f3f5',
    bg: '#f7f8fa', surface: '#ffffff', text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', accentText: '#3574f0',
    ok: '#3d8b43', warn: '#b07d1a', danger: '#c0392b', run: '#b07d1a', net: '#2b7c93',
    task: '#7a4fb0', write: '#3d8b43',
    thinkAnchor: '#706a5c',
  },
}

/** 一份主题 → 一串 CSS 变量。 */
function tokens(t) {
  return `
    --page-bg:${t.pageBg}; --page-fg:${t.pageFg}; --page-dim:${t.pageDim};
    --page-line:${t.pageLine}; --page-card:${t.pageCard};
    --bg:${t.bg}; --surface:${t.surface}; --text:${t.text}; --dim:${t.dim};
    --border:${t.border}; --code:${t.code};
    --accent:${t.accent}; --accent-text:${t.accentText};
    --ok:${t.ok}; --warn:${t.warn}; --danger:${t.danger};
    --tone-run:${t.run}; --tone-net:${t.net}; --tone-task:${t.task}; --tone-write:${t.write};`
}

// ---------------------------------------------------------------- 示意数据
//
// 固定种子 —— 深色/浅色两次生成是同一份数字。形状照着真实使用来：缓存读占大头、
// 周末明显轻、偶尔有"猛"的一天（长会话/大重构）。

const TODAY = new Date(2026, 9, 10) // 2026-10-10
const MODEL_ORDER = ['opus', 'sonnet', 'haiku']
const MODEL_NAME = { opus: 'Opus 4.8', sonnet: 'Sonnet 5', haiku: 'Haiku 5.5' }
/** [输入单价, 输出单价]（$/M）—— 缓存读按输入的 0.1 倍、缓存写按 1.25 倍折算。 */
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
const rnd = mulberry32(20261010)

const pad2 = (n) => String(n).padStart(2, '0')
const addD = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x }
const dowMon = (d) => (d.getDay() + 6) % 7 // 0=周一
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
  if (dowMon(d) >= 5) base *= 0.35 // 周末轻
  if (rnd() < 0.07) base *= 1.6 + rnd() * 0.6 // 偶尔猛一天
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
  days.push({
    d, key: keyOf(d), label: labelOf(d), dow: dowMon(d), models,
    total, cost, inp, out, cr, cc,
    sessions: 1 + Math.floor(rnd() * 6),
  })
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

const weeks12 = []
for (let w = 11; w >= 0; w--) {
  const mon = addD(weekMon, -w * 7)
  weeks12.push(agg(rangeDays(mon, addD(mon, 6))))
}
const months12 = []
for (let k = 11; k >= 0; k--) {
  const start = new Date(TODAY.getFullYear(), TODAY.getMonth() - k, 1)
  const end = new Date(start.getFullYear(), start.getMonth() + 1, 0)
  months12.push(agg(days.filter((x) => x.d >= start && x.d <= end && x.d <= TODAY)))
}

// 热力图：近 12 个月（53 周，最后一列含今天）
const hmStart = addD(weekMon, -52 * 7)
const hmDays = days.filter((x) => x.d >= hmStart)
const hmMax = Math.max(...hmDays.map((x) => x.total))
const selDay = days[days.length - 3] // 10/8 —— 画出"选中某一天"的样子
const busiest = hmDays.reduce((a, b) => (b.total > a.total ? b : a))
const cacheShare30 = A30.cr / A30.total

// ---------------------------------------------------------------- 数字格式
//
// 与 ResultLine 同一条规矩：最多三位有效数字（12432 → 12.4k、124456 → 124k）。

const trim1 = (x) => String(Math.round(x * 10) / 10)
const fmtT = (v) => {
  if (v < 1000) return String(Math.round(v))
  if (v < 100000) return trim1(v / 1000) + 'k'
  if (v < 1000000) return String(Math.round(v / 1000)) + 'k'
  if (v < 100000000) return trim1(v / 1e6) + 'M'
  return String(Math.round(v / 1e6)) + 'M'
}
const fmtUsd = (v) => '$' + (v < 1000 ? (Math.round(v * 10) / 10).toFixed(1) : String(Math.round(v)))
const pct = (x) => Math.round(x * 100) + '%'

// ---------------------------------------------------------------- 片段

/** 分段控件（按日/按周/按月那种）。 */
const seg = (opts, on = 0) =>
  `<span class="seg">${opts.map((o, i) => `<i class="${i === on ? 'on' : ''}">${o}</i>`).join('')}</span>`

/** 一块统计砖。 */
const tile = (label, a, extra) => `
      <div class="tile">
        <div class="tile__lbl">${label}</div>
        <div class="tile__val">${fmtT(a.total)}</div>
        <div class="tile__sub">≈${fmtUsd(a.cost)} · ${extra}</div>
      </div>`

/** 堆叠柱：每根 = 一天/一周/一月，段 = 模型。list 元素要带 {total, models[m].total}。 */
function stackedChart(list, { h = 110, labels = 5 } = {}) {
  const max = Math.max(...list.map((x) => x.total), 1)
  const cols = list.map((x) => {
    const H = Math.max(3, Math.round((x.total / max) * h))
    let used = 0
    const order = ['haiku', 'sonnet', 'opus'] // DOM 上→下（opus 在底）
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

/** 模型图例（带这一段各自的合计）。 */
const modelLegend = (a) =>
  `<div class="legend">${MODEL_ORDER.map(
    (m) => `<span><i class="sw m-${m}"></i>${MODEL_NAME[m]} ${fmtT(a.models[m].total)}</span>`,
  ).join('')}</div>`

/** 对话框壳。620 宽 —— 比现有 CARD_WIDTH=420 宽一档（图要地方，这也是一件要选的事）。 */
const dlg = (title, body) => `
      <div class="dlg">
        <div class="dlg-bar"><span>${title}</span><span class="dlg-x">✕</span></div>
        <div class="dlg-body">${body}</div>
        <div class="dlg-foot"><span class="dim">成本为 API 单价估算 · 全部数字单位 tokens</span><span class="spring"></span><span class="btn">导出 CSV</span><span class="btn btn--p">关闭</span></div>
      </div>`

const chartCaption = (left, a) => `
      <div class="caption"><span>${left}</span><span class="spring"></span><span>合计 ${fmtT(a.total)} · ≈${fmtUsd(a.cost)}</span></div>`

// ---- V1 仪表盘 ----

function v1() {
  const rows = MODEL_ORDER.map((m) => {
    const mm = A30.models[m]
    const share = mm.total / A30.total
    return `
          <tr>
            <td><i class="sw m-${m}"></i>${MODEL_NAME[m]}</td>
            <td><span class="pbar"><i class="m-${m}" style="width:${Math.max(2, Math.round(share * 100))}%"></i></span>${pct(share)}</td>
            <td class="tn">${fmtT(mm.total)}</td>
            <td class="tn">${fmtT(mm.cr)}</td>
            <td class="tn">≈${fmtUsd(mm.cost)}</td>
          </tr>`
  }).join('')
  const bits = []
  bits.push(`<div class="toolbar">${seg(['按日', '按周', '按月'])}<span class="spring"></span><span class="chip">本项目 ▾</span></div>`)
  bits.push(`
      <div class="tiles">
        ${tile('今日', A_today, `${pct(A_today.cr / A_today.total)} 缓存`)}
        ${tile('本周', A_week, `较上周 ${weekDelta >= 0 ? '+' : '−'}${Math.abs(Math.round(weekDelta * 100))}%`)}
        ${tile('本月', A_month, `${A_month.sessions} 个会话`)}
      </div>`)
  bits.push(chartCaption('近 30 天 · 按日', A30))
  bits.push(stackedChart(days.slice(-30), { h: 110, labels: 5 }))
  bits.push(modelLegend(A30))
  bits.push(`
      <table class="led">
        <tr><th>模型</th><th>占比</th><th class="tn">用量</th><th class="tn">缓存读</th><th class="tn">≈成本</th></tr>
        ${rows}
      </table>`)
  return dlg('使用量统计', bits.join(''))
}

// ---- V2 热力图 ----

function v2(T) {
  const byKey = new Map(days.map((x) => [x.key, x]))
  const HC = [
    mix(T.bg, T.border, 0.7),
    mix(T.bg, T.accentText, 0.24),
    mix(T.bg, T.accentText, 0.48),
    mix(T.bg, T.accentText, 0.72),
    mix(T.bg, T.accentText, 1),
  ]
  const level = (v) => (v === 0 ? 0 : Math.min(4, Math.ceil((v / hmMax) * 4)))

  // 格子**平铺**进网格（grid-auto-flow: column 按 7 行一列往下排）。别在外面再包
  // 一层 span —— 包了的话每个 span 只当一个 8px 的格子算，里面那七块会溢出叠在
  // 一起（第一版截图里那块"糊"就是这么来的）。
  const cells = []
  const monthRuns = []
  let curMonth = -1
  for (let c = 0; c < 53; c++) {
    const mon = addD(hmStart, c * 7)
    for (let r = 0; r < 7; r++) {
      const d = addD(mon, r)
      const x = d <= TODAY ? byKey.get(keyOf(d)) : null
      if (!x) { cells.push('<i class="f"></i>'); continue }
      const lv = level(x.total)
      const cls = x.key === selDay.key ? 'sel' : x.key === keyOf(TODAY) ? 'today' : ''
      cells.push(`<i class="${cls}" style="background:${lv === 0 ? HC[0] : HC[lv]}"></i>`)
    }
    const mm = mon.getMonth()
    if (mm !== curMonth) { monthRuns.push({ m: mm, n: 1 }); curMonth = mm }
    else monthRuns[monthRuns.length - 1].n += 1
  }
  // 月标签按"这一月占了几列 × 列距 10px"给宽，末尾 −4 给右边留出余量（否则
  // 最后那截会把强度图例顶出对话框）
  const months = monthRuns
    .map((x) => `<span style="width:${x.n * 10 - 4}px">${x.m + 1}月</span>`)
    .join('')
  const leg = `<span class="hmleg">少${HC.map((c) => `<i style="background:${c}"></i>`).join('')}多</span>`

  const sel = selDay
  const strip = `
      <div class="selstrip">
        <span class="dim">选中</span>
        <b>${sel.label}（${dayNameOf(sel.d)}）</b>
        <span>${fmtT(sel.total)} tok · ≈${fmtUsd(sel.cost)} · ${sel.sessions} 个会话</span>
        <span class="spring"></span>
        <span class="dim">${MODEL_ORDER.map((m) => `${MODEL_NAME[m]} ${fmtT(sel.models[m].total)}`).join(' · ')}</span>
      </div>`

  const mini = (list, name, tail) => `
      <div class="mini">
        <div class="mini__cap"><span>${name}</span><span class="spring"></span><span>${tail} ${fmtT(list[list.length - 1].total)}</span></div>
        ${stackedChart(list, { h: 46, labels: 0 })}
      </div>`

  const body = []
  body.push(`<div class="caption"><span>近 12 个月 · 每天一格</span><span class="spring"></span><span>点格子看当天明细</span></div>`)
  body.push(`
      <div class="hmouter">
        <div class="hmonths">${months}${leg}</div>
        <div class="hmwrap">
          <div class="hwd"><span>一</span><span></span><span>三</span><span></span><span>五</span><span></span><span></span></div>
          <div class="hm">${cells.join('')}</div>
        </div>
      </div>`)
  body.push(strip)
  body.push(`
      <div class="minis">
        ${mini(weeks12, '按周 · 近 12 周', '本周')}
        ${mini(months12, '按月 · 近 12 个月', '本月')}
      </div>`)
  body.push(`<div class="foot">最忙的一天：${busiest.label}（${fmtT(busiest.total)} tok） · 含子代理与压缩 · 成本为估算</div>`)
  return dlg('使用量统计', body.join(''))
}

// ---- V3 账目表 ----

function v3() {
  const recent = days.slice(-13).reverse()
  const maxTotal = Math.max(...days.slice(-30).map((x) => x.total))
  const rows = recent.map((x) => `
          <tr>
            <td>${x.label} ${dayNameOf(x.d)}</td>
            <td class="tn"><span class="tbar"><i style="width:${Math.round((x.total / maxTotal) * 100)}%"></i></span>${fmtT(x.total)}</td>
            <td class="tn">${fmtT(x.inp)}</td>
            <td class="tn">${fmtT(x.out)}</td>
            <td class="tn">${fmtT(x.cr)}</td>
            <td class="tn">${fmtUsd(x.cost)}</td>
          </tr>`).join('')
  const body = []
  body.push(`<div class="toolbar">${seg(['按日', '按周', '按月'])}<span class="spring"></span><span class="chip">本项目 ▾</span></div>`)
  body.push(chartCaption('近 30 天 · 按日（一行一个周期，可滚）', A30))
  body.push(`
      <table class="led">
        <tr><th>日期</th><th class="tn">总量</th><th class="tn">输入</th><th class="tn">输出</th><th class="tn">缓存读</th><th class="tn">≈成本</th></tr>
        ${rows}
        <tr class="sum">
          <td>近 30 天合计</td>
          <td class="tn">${fmtT(A30.total)}</td>
          <td class="tn">${fmtT(A30.inp)}</td>
          <td class="tn">${fmtT(A30.out)}</td>
          <td class="tn">${fmtT(A30.cr)}</td>
          <td class="tn">≈${fmtUsd(A30.cost)}</td>
        </tr>
      </table>`)
  return dlg('使用量统计', body.join(''))
}

// ---- E1 常驻条（420 工具窗口里） ----

const spark = (() => {
  const list = days.slice(-14)
  const max = Math.max(...list.map((x) => x.total))
  return `<span class="spark">${list.map((x, i) => {
    const h = Math.max(3, Math.round((x.total / max) * 15))
    const last = i === list.length - 1 ? ' on' : ''
    return `<i class="${last.trim()}" style="height:${h}px"></i>`
  }).join('')}</span>`
})()

const useStrip = `
      <div class="usestrip">
        <span class="usestrip__t">使用量</span>
        <span class="usestrip__v">今日 <b>${fmtT(A_today.total)}</b> · ≈${fmtUsd(A_today.cost)}</span>
        ${spark}
        <span class="usestrip__chev">›</span>
      </div>`

function e1() {
  return `
      <div class="tw">
        <div class="tw-bar"><span class="ic"></span>CCoder</div>
        <div class="tr tr--tight">
          <div class="row">
            <div class="sc"><div class="sc__top"><span class="sc__ico"></span><span class="sc__lbl">连接</span></div><div class="sc__val">已连接</div></div>
            <div class="sc"><div class="sc__top"><span class="sc__ico"></span><span class="sc__lbl">任务列表</span></div><div class="sc__val">2/5</div><div class="sc__pips"><i class="on"></i><i class="on"></i><i></i><i></i><i></i></div></div>
            <div class="sc"><div class="sc__top"><span class="sc__ico"></span><span class="sc__lbl">子代理</span></div><div class="sc__val">1</div><div class="sc__pips"><i class="run"></i></div></div>
            <div class="sc sc--meter"><div class="sc__top"><span class="sc__ico"></span><span class="sc__lbl">上下文</span></div><div class="sc__val">34%</div></div>
          </div>
          ${useStrip}
          <div class="bub bub--u">帮我看看这个月的用量</div>
          <div class="bub bub--a">行 —— 点上面那条「使用量」就能开面板。</div>
        </div>
        <div class="bottom">
          <div class="comp">
            <div class="ph">继续指挥 Claude…</div>
            <div class="bar"><span class="m">Opus 4.8</span><span class="m">自动接受编辑</span><span class="r"></span></div>
          </div>
        </div>
      </div>`
}

// ---- E2 菜单项 ----

const e2 = `
      <div class="menu">
        <div>历史会话…</div>
        <div class="on">使用量统计…</div>
        <div class="sep"></div>
        <div>设置…</div>
      </div>`

// ---------------------------------------------------------------- 页面

function page(theme) {
  const T = THEMES[theme]
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>使用量统计 · 选型台</title>
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
.brief p { margin: 0 0 8px; color: var(--page-dim); font-size: 13.5px; max-width: 96ch; }
.brief p:last-child { margin-bottom: 0; }
.brief code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
.brief b { color: var(--page-fg); }

h2.sect { font-family: var(--serif); font-size: 25px; font-weight: 600; margin: 54px 0 4px; }
.sect-note { color: var(--page-dim); font-size: 13.5px; margin: 0 0 22px; max-width: 96ch; }
.sect-note b { color: var(--page-fg); }

.grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(460px, 1fr)); gap: 26px; align-items:start; }
.grid--wide { display:grid; grid-template-columns: repeat(auto-fit, minmax(680px, 1fr)); gap: 26px; align-items:start; }
.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px; padding: 18px 18px 20px; }
.opt__head { display:flex; gap: 12px; margin-bottom: 14px; }
.opt__pick { font-family: var(--mono); font-size: 12px; letter-spacing:.08em; color:#fff; background: var(--accent);
             width: 30px; height: 30px; border-radius: 8px; display:flex; align-items:center; justify-content:center; flex:0 0 auto; }
.opt__name { font-size: 15px; font-weight: 600; }
.opt__note { color: var(--page-dim); font-size: 12.5px; margin-top: 3px; max-width: 68ch; }
.opt__note code { font-family: var(--mono); font-size: 11.5px; background: var(--code); padding: 1px 4px; border-radius: 3px; color: var(--text); }

/* ---- 对话框壳（620，真机配色）---- */
.dlg { width: 620px; max-width: 100%; border:1px solid var(--border); border-radius: 10px;
       overflow:hidden; background: var(--bg); box-shadow: 0 10px 30px rgba(0,0,0,.22); }
.dlg-bar { display:flex; align-items:center; padding: 8px 12px; background: var(--surface);
           border-bottom:1px solid var(--border); font-size: 12.5px; color: var(--text); }
.dlg-x { margin-left:auto; color: var(--dim); font-family: var(--mono); font-size: 12px; }
.dlg-body { padding: 12px 14px; display:flex; flex-direction:column; gap: 10px; }
.dlg-foot { display:flex; align-items:center; gap:8px; padding: 9px 12px; border-top:1px solid var(--border);
            background: var(--bg); font-size: 11px; }
.btn { font-size: 12px; padding: 4px 14px; border-radius: 6px; border:1px solid var(--border);
       color: var(--text); background: transparent; }
.btn--p { background: var(--accent); border-color: var(--accent); color:#fff; }
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

.caption { display:flex; align-items:center; font-size: 11px; color: var(--dim); }
.chart { display:flex; align-items:flex-end; gap: 3px; border-bottom: 1px solid var(--border); }
.ccol { flex:1; display:flex; flex-direction:column; justify-content:flex-end; height:100%; min-width: 0; }
.ccol i { display:block; width: 100%; }
.ccol > i:first-child { border-radius: 2px 2px 0 0; }
i.m-opus { background: var(--accent-text); }
i.m-sonnet { background: var(--tone-task); }
i.m-haiku { background: var(--tone-net); }
.axis { display:flex; gap: 3px; }
.axis span { flex:1; text-align:center; font-size: 9px; font-family: var(--mono); color: var(--dim); min-width:0; }
.legend { display:flex; gap: 14px; font-size: 10.5px; color: var(--dim); align-items:center; }
.sw { display:inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 5px; vertical-align: middle; }

/* ---- 表（两个变体共用）---- */
table.led { width:100%; border-collapse: collapse; font-size: 12px; }
table.led th { text-align:left; font-size: 10px; color: var(--dim); font-family: var(--mono);
               font-weight: 500; padding: 3px 6px; border-bottom: 1px solid var(--border); }
table.led td { padding: 3px 6px; border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent); color: var(--text); }
table.led td.tn, table.led th.tn { text-align:right; font-family: var(--mono); font-size: 11px; }
table.led tr.sum td { border-top: 1px solid var(--border); border-bottom: 0; font-weight: 600; }
table.led .pbar { display:inline-block; width: 64px; height: 5px; border-radius: 3px; margin-right: 8px;
                  background: color-mix(in srgb, var(--border) 45%, transparent); overflow:hidden; vertical-align: middle; }
table.led .pbar i { display:block; height:100%; }
table.led .tbar { display:inline-block; width: 46px; height: 4px; border-radius: 2px; margin-right: 7px;
                  background: color-mix(in srgb, var(--border) 45%, transparent); overflow:hidden; vertical-align: middle; }
table.led .tbar i { display:block; height:100%; background: var(--accent-text); }

/* ---- 热力图 ---- */
.hmouter { display:flex; flex-direction:column; gap: 4px; }
.hmonths { display:flex; margin-left: 18px; font-size: 9px; color: var(--dim); overflow:hidden; }
.hmonths span { flex: 0 0 auto; }
.hmwrap { display:flex; gap: 6px; }
.hwd { display:flex; flex-direction:column; gap: 2px; width: 12px; }
.hwd span { height: 8px; line-height: 8px; font-size: 8px; color: var(--dim); text-align: right; }
.hm { display:grid; grid-auto-flow: column; grid-template-rows: repeat(7, 8px); gap: 2px; }
.hm i { width: 8px; height: 8px; border-radius: 2px; display:block; }
.hm i.f { background: transparent; box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--border) 70%, transparent); }
.hm i.sel { box-shadow: 0 0 0 1.5px var(--text); }
.hm i.today { box-shadow: 0 0 0 1px var(--dim); }
.hmleg { margin-left: auto; display:flex; align-items:center; gap: 3px; font-size: 9px; color: var(--dim); }
.hmleg i { width: 8px; height: 8px; border-radius: 2px; display:inline-block; }
.selstrip { display:flex; align-items:center; gap: 8px; font-size: 11px; color: var(--text);
            border:1px solid var(--border); border-radius: 7px; padding: 4px 9px; background: var(--surface); }
.selstrip b { font-weight: 600; }
.minis { display:flex; gap: 14px; }
.mini { flex: 1 1 0; min-width: 0; }
.mini__cap { display:flex; align-items:center; font-size: 10.5px; color: var(--dim); margin-bottom: 3px; }
.foot { font-size: 10.5px; color: var(--dim); }

/* ---- 420 工具窗口（真机宽度）---- */
.tw { width: 420px; max-width:100%; background: var(--bg);
      border:1px solid var(--border); border-radius: 8px; overflow:hidden; display:flex; flex-direction:column; }
.tw-bar { display:flex; align-items:center; gap:7px; padding: 6px 10px; background: var(--surface);
          border-bottom:1px solid var(--border); font-size: 11.5px; color: var(--dim); }
.tw-bar .ic { width:11px; height:11px; border-radius:2px; background: var(--border); }
.tr { padding: 12px; background: var(--bg); display:flex; flex-direction:column; gap: 9px; min-height: 190px; }
.tr--tight { padding: 8px; gap: 7px; }

.bub { border-radius: 9px; padding: 7px 10px; font-size: 12px; line-height:1.5; }
.bub--u { align-self:flex-end; background: var(--accent); color:#fff; max-width: 85%; }
.bub--a { align-self:flex-start; background: var(--surface); border:1px solid var(--border); color: var(--text); max-width: 92%; }

.row { display:flex; gap:5px; width: 404px; max-width:100%; }
.sc { width: 97px; height: 56px; border:1px solid var(--border); border-radius: 8px; background: var(--bg);
      padding: 5px 8px 6px; display:flex; flex-direction:column; justify-content:center; gap:1px; position:relative; }
.sc__top { display:flex; align-items:center; gap:4px; }
.sc__ico { width:10px; height:10px; border-radius:50%; border:1px solid var(--ok); flex: 0 0 auto; }
.sc__lbl { font-size: 10.5px; color: var(--dim); }
.sc__val { font-size: 13px; color: var(--text); line-height:1.3; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.sc__pips { display:flex; gap:3px; margin-top:1px; }
.sc__pips i { width:4px; height:4px; border-radius:50%; background: var(--border); }
.sc__pips i.on { background: var(--ok); }
.sc__pips i.run { background: var(--accent); }
.sc--meter::after { content:''; position:absolute; left:1px; right:1px; bottom:1px; height:16px;
                    border-radius: 0 0 7px 7px; background: color-mix(in srgb, var(--accent) 22%, transparent); }

/* 常驻条：28px 的矮行 */
.usestrip { display:flex; align-items:center; gap: 8px; border:1px solid var(--border);
            border-radius: 7px; background: var(--surface); padding: 4px 9px; cursor: pointer; }
.usestrip__t { font-size: 10.5px; color: var(--dim); flex: 0 0 auto; }
.usestrip__v { font-size: 11px; color: var(--text); flex: 0 0 auto; }
.usestrip__v b { font-family: var(--mono); font-size: 12px; }
.spark { display:flex; align-items:flex-end; gap: 2px; height: 16px; margin-left: auto; }
.spark i { width: 3px; border-radius: 1px; background: color-mix(in srgb, var(--accent-text) 55%, transparent); }
.spark i.on { background: var(--accent-text); }
.usestrip__chev { color: var(--dim); font-family: var(--mono); font-size: 12px; }

.bottom { padding: 8px 10px; background: var(--bg); border-top: 1px solid var(--border); }
.comp { border:1px solid var(--border); border-radius: 8px; background: var(--surface); padding: 6px 9px; }
.comp .ph { font-size: 11.5px; color: var(--dim); margin-bottom: 5px; }
.comp .bar { display:flex; gap: 5px; align-items:center; }
.comp .bar .m { font-size: 10px; color: var(--dim); border:1px solid var(--border); border-radius: 4px; padding: 1px 6px; }
.comp .bar .r { flex: 1; }

/* ---- 菜单 ---- */
.menu { width: 220px; border:1px solid var(--border); border-radius: 8px; background: var(--bg);
        box-shadow: 0 8px 22px rgba(0,0,0,.28); padding: 5px 0; }
.menu div { padding: 5px 14px; font-size: 12px; color: var(--text); }
.menu .on { background: color-mix(in srgb, var(--accent) 22%, transparent); }
.menu .sep { height:1px; background: var(--border); padding:0; margin: 4px 0; }

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
.verdict p { color: var(--page-dim); margin: 0 0 10px; max-width: 88ch; }
.verdict b { color: var(--page-fg); }
.verdict code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
</style>
</head>
<body>
<div class="wrap">
  <div class="masthead">
    <div>
      <p class="eyebrow">2026-10-10 · 选型台 · 不参与构建</p>
      <h1>使用量统计：三套画法，两个入口</h1>
      <p class="lede">看每日 / 每周 / 每月的 token 用量（和估算成本）。三套画法<b>共用同一条数据管线</b>
        （侧车账本 + 历史回填）—— 这一页选的是<b>怎么画</b>、<b>从哪进</b>。深色打底，右上角切浅色；
        数字是示意（固定种子生成，模了"缓存读占大头"的真实形状）。</p>
    </div>
    <div class="toggle">
      <button id="dk" aria-pressed="true">深色</button><button id="lt" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先把口径摆清楚</h3>
    <p><b>数据从哪来（两条路）</b>：① 以后 —— 侧车在每次 <code>result</code> 上记一笔，用
      <code>modelUsage</code> 做<b>差分</b>（全量口径：主循环 + 子代理 + 压缩都在内；旧字段
      <code>usage</code> 只含主循环，拿它做统计会系统性偏小）。差分要处理 <code>/clear</code> 重置
      与 resume 续数两个断点 —— 先写探针钉死，再入库。② 历史 —— 回填本机 CLI 会话文件
      （<code>~/.claude/projects/**/*.jsonl</code>，assistant 条目自带用量），一次扫完。</p>
    <p><b>成本是估算</b>：按 API 单价折算；订阅档下它不是账单。界面一律带 <code>≈</code> 并在脚注写明 ——
      免得被当成付款依据。</p>
    <p><b>范围与分桶</b>：可切「本项目 / 全部项目」（会话文件按目录归类）；分桶「按日 / 按周 / 按月」。
      图里默认「本项目 · 按日」。</p>
    <p><b>三套画法共用同一条数据管线</b> —— 管线（账本 + 回填 + 聚合）才是工作量的大头，画法都长在它上面。</p>
  </div>

  <h2 class="sect">一、三套画法</h2>
  <p class="sect-note">同一份数据的三种面貌。弹窗都画成 <b>620 宽</b>（现有弹窗是 420 —— 图表要地方；
    这也是一件可以否的事，说一声就行）。</p>

  <div class="grid--wide">
    <div class="opt">
      <div class="opt__head"><span class="opt__pick">V1</span><div>
        <div class="opt__name">仪表盘：先结论，再细节</div>
        <div class="opt__note">今日 / 本周 / 本月三块砖打头，然后是近 30 天逐日堆叠柱（按模型）+ 按模型分解表。
          「烧了多少、趋势怎样、谁在烧」一屏回答。</div>
      </div></div>
      ${v1()}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">V2</span><div>
        <div class="opt__name">热力图：以天为骨</div>
        <div class="opt__note">近一年每天一格（颜色 = 当天用量），点一格看当天明细；下面两块是同一份数据的
          周 / 月画法 —— <b>不用切来切去，三个粒度一屏看全</b>。适合"找模式"：哪几天猛、周末多轻。</div>
      </div></div>
      ${v2(T)}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">V3</span><div>
        <div class="opt__name">账目表：数字优先</div>
        <div class="opt__note">一行一个周期（图里是按日，切按周/按月就是一周/一月一行），可滚、可导出；
          趋势只剩总量那一截小条。对账、贴数、复制给别人看都用这个 —— 实现也最省。</div>
      </div></div>
      ${v3()}
    </div>
  </div>

  <h2 class="sect">二、从哪进</h2>
  <p class="sect-note">入口独立于画法，可以组合（V1+E1、V3+E2……）。</p>

  <div class="grid">
    <div class="opt">
      <div class="opt__head"><span class="opt__pick">E1</span><div>
        <div class="opt__name">常驻条：工具窗口里一条矮行</div>
        <div class="opt__note">约 28px 高：今日总量 + 14 天走势，<b>点开就是对话框</b>。日常瞄一眼不用开窗。
          位置画在状态卡下、转写之上（放别处也行）。</div>
      </div></div>
      ${e1()}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">E2</span><div>
        <div class="opt__name">菜单项：不占地方</div>
        <div class="opt__note">工具窗口的菜单里加一项（也可绑快捷键）。省一条常驻行的高度，
          代价是平时看不见、想不起来点。</div>
      </div></div>
      ${e2}
    </div>
  </div>

  <h2 class="sect">三、代价对照</h2>
  <table class="cmp">
    <tr><th>方案</th><th>一眼回答什么</th><th>实现</th><th>代价 / 风险</th></tr>
    <tr>
      <td>V1 仪表盘</td>
      <td>烧了多少、趋势、谁在烧</td>
      <td>自绘堆叠柱（同 <code>StackBar</code> 那套自绘手艺）+ 一张表</td>
      <td>弹窗要宽一档（图里 620）；宽度你定</td>
    </tr>
    <tr>
      <td>V2 热力图</td>
      <td>哪天猛（找模式）</td>
      <td>自绘年历网格 + 选中明细 + 两块小柱</td>
      <td>交互最多（点选 / 悬停）；比 V1 多几天前端</td>
    </tr>
    <tr>
      <td>V3 账目表</td>
      <td>精确数字、对账、导出</td>
      <td>最省：行 + 数字，mini 条可选</td>
      <td>趋势感弱，要配一句注</td>
    </tr>
    <tr>
      <td>E1 常驻条</td>
      <td>日常瞄一眼</td>
      <td>一条矮行 + sparkline，数据同管线</td>
      <td>占工具窗口约 28px 高度</td>
    </tr>
    <tr>
      <td>E2 菜单项</td>
      <td>不占地方</td>
      <td>一个菜单项</td>
      <td>平时想不起来点</td>
    </tr>
  </table>

  <div class="verdict">
    <h2>我的建议</h2>
    <p><b>V1 + E1</b>：常驻条点开就是仪表盘 —— 日常和月度复盘共用一个入口，也最像"一个功能已经做完"的样子。
      <span class="yes">这是推荐组合。</span></p>
    <p>想先省人力就先上 <b>V3 + E1</b>（表实现最省，先让数据管线跑起来）；热力图以后想要再加，管线不白干。</p>
    <p>可改的：按钮（导出 CSV / 复制明细 / 干脆只要关闭）、弹窗宽度（现在画的 620）、常驻条放哪一行、
      成本显不显。落地文案全部走词表（中英两套）。</p>
    <p>看完回一个组合就行，比如 <code>V1+E1</code>、<code>V3</code>、<code>V2+E2</code>；都不要也直接说。</p>
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
    let name = 'usage-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'usage-view-light.html')
      writeFileSync(target, page('light'))
      name = 'usage-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1500,3450',
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
