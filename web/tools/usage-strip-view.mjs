#!/usr/bin/env node
/**
 * 「使用量」常驻条 · 重画选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-14 用户："这卡片不太美观，出几个方案放浏览器给我选择"（附真机截图）。
 * 这页就是"几个方案"：甲 静字 / 乙 分舱 / 丙 额度条 / 丁 疏排，外加两组可选件
 * （走势四画法、表面三选），可叠加。
 *
 * **用户当天选了丙**（可选件全走默认：实条走势 + 保持卡面），落在
 * `src/main/kotlin/com/ccoder/ui/UsageStrip.kt` 与 `UsageRenderProbe` 的
 * usage-strip-probe-quota-wide(-dark).png。
 *
 * 现状的毛病是**量出来的**（从截图里，1x 尺寸）：
 *   条本体 739×28；文字段只走到 x≈330，走势从 x≈650 起 —— 中间**空 320px**；
 *   走势是 14 根 3px 小柱、最低 1.5px、55% 透明度 —— 远看就是一排点；
 *   › 是裸字符，孤悬在 x≈730；行首「使用量」弱；数据段里词、数、点同一个色。
 *
 * 几何照实（不是手填）：条高 28（STRIP_HEIGHT）、内边距 9、半径 8（CARD_CORNER_ARC
 * 16 的一半 —— RoundRectangle2D 的 arc 是直径，选型台别再用 7 那个错数）、
 * 走势 14 根、柱宽/缝照现状 3/2。窗片宽 760（用户这台机器截图里条本体 739，
 * 取 760-16 的近似；比别的选型台的 560 宽 —— 空腔就是这个宽度才显出来的）。
 * 颜色照 theme 常量（深/浅两套都渲染；accentText 与截图里 55% 混出的 #516D99
 * 对上了，说明这族色没问题）。
 *
 * 口径：只动"怎么画"。数据、点击开统计框、悬停反馈、窄窗降级、第一份数据之前
 * 不显示 —— 全部沿用现状。数字是示意（今日 274.4M · ≈$185.4 · 5h 11% · 周 11%
 * · 月 5%，同截图）。
 *
 * 用法：
 *   node tools/usage-strip-view.mjs --open          # 生成并用默认浏览器打开
 *   node tools/usage-strip-view.mjs --shot          # 生成并截图（深色）
 *   node tools/usage-strip-view.mjs --shot=light    # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'usage-strip-view.html')

// ---------------------------------------------------------------- 颜色运算

const rgb = (hex) => {
  const h = hex.replace('#', '')
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}
const hexOf = (arr) => '#' + arr.map((c) => Math.round(Math.min(255, Math.max(0, c))).toString(16).padStart(2, '0')).join('')
/** 复刻 ThemeInjector.mix(base, fg, ratio)。 */
const mix = (base, fg, ratio) => {
  const b = rgb(base)
  const f = rgb(fg)
  return hexOf(b.map((c, i) => c + (f[i] - c) * ratio))
}

const THEMES = {
  dark: {
    pageBg: '#161719', pageFg: '#e8eaed', pageDim: '#8b8f97', pageLine: '#26282c',
    pageCard: '#1c1e21', code: '#2b2d30',
    bg: '#2b2d30',            // 底区（Swing 一侧）面板底 —— 条自己填的也是它
    transcript: '#1e1f22',
    text: '#dfe1e5', dim: '#9da0a8', border: '#393b40',
    accent: '#2f65ca', accentText: '#7fb0ff',
    ok: '#5fad65', warn: '#ff8a65', danger: '#db5c5c',
    labelFg: '#dfe1e5',
  },
  light: {
    pageBg: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageLine: '#e0e2e7',
    pageCard: '#ffffff', code: '#f2f3f5',
    bg: '#f7f8fa',
    transcript: '#ffffff',
    text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', accentText: '#3574f0',
    ok: '#3d8b43', warn: '#d84315', danger: '#c0392b',
    labelFg: '#000000',
  },
}

/** 悬停（整条底色）与胶囊/进度条底色：都由 mix 派生，不新造颜色。 */
function tokens(t) {
  return `
    --page-bg:${t.pageBg}; --page-fg:${t.pageFg}; --page-dim:${t.pageDim};
    --page-line:${t.pageLine}; --page-card:${t.pageCard};
    --bg:${t.bg}; --transcript:${t.transcript};
    --text:${t.text}; --dim:${t.dim}; --border:${t.border}; --code:${t.code};
    --accent:${t.accent}; --accent-text:${t.accentText};
    --ok:${t.ok}; --warn:${t.warn}; --danger:${t.danger};
    --hover:${mix(t.bg, t.accentText, 0.10)};
    --chip:${mix(t.bg, t.labelFg, 0.07)};
    --track:${mix(t.bg, t.labelFg, 0.10)};`
}

// ---------------------------------------------------------------- 示意数据

const DATA = {
  opencode: { today: '274.4M', cost: '$185.4', quota: [['5h', 11], ['周', 11], ['月', 5]] },
  dmx: { today: '274.4M', cost: '$185.4', bill: '¥863.42' },
  deepseek: { today: '274.4M', cost: '$185.4', balance: '¥110.00' },
  // 吃紧态（只给丙看配色语言：>=70 琥珀、>=90 红，与统计框同一套阈值）
  tight: { today: '274.4M', cost: '$185.4', quota: [['5h', 72], ['周', 96], ['月', 11]] },
}

/** 14 天走势（同一组数给所有画法，才比得出来）。 */
const SPARK = [4, 5, 4, 6, 5, 8, 6, 5, 6, 7, 9, 6, 14, 9]

// ---------------------------------------------------------------- 小件

const dot = '<span class="d">·</span>'
/** 词与数之间：词后带空格（今日␣/5h␣，同真串）；≈/实扣/余额 的格式里本就没有空格。 */
const seg = (w, n, gap) => `<span class="w">${w}</span>${gap ? '<span class="ws"></span>' : ''}<b class="n">${n}</b>`

const runToday = (d) => seg('今日', d.today, true)
const runCost = (d) => seg('≈', d.cost)
const runBill = (d) => seg('实扣', d.bill)
const runBal = (d) => seg('余额', d.balance)
const quotaPlain = (d) => d.quota.map(([k, p]) => seg(k, p + '%', true)).join(dot)

/** 甲 的尾段：额度与账目只有一家在场，各自接法不同（组间留 12px，不点数）。 */
const tailA = (d) =>
  d.quota ? `<span class="gg"></span>${quotaPlain(d)}`
  : d.bill ? dot + runBill(d)
  : d.balance ? dot + runBal(d)
  : ''

/**
 * 走势四画法：now=现状（3px 点阵）/ solid=实条+基线 / line=走势线（拉伸）/
 * none=不画。柱高同一组数、按各自最大 15px 归一。
 */
const sparkBars = (kind) => {
  const max = Math.max(...SPARK)
  const bars = SPARK.map((v, i) => {
    const h = Math.max(2, Math.round((v / max) * 15))
    return `<i class="${i === SPARK.length - 1 ? 'on' : ''}" style="height:${h}px"></i>`
  }).join('')
  return `<span class="spark spark--${kind}">${bars}</span>`
}

const sparkLine = () => {
  const w = 340
  const h = 18
  const max = Math.max(...SPARK)
  const step = (w - 8) / (SPARK.length - 1)
  const pts = SPARK.map((v, i) => [4 + i * step, 3 + (1 - v / max) * (h - 9)])
  const d = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ')
  const [lx, ly] = pts[pts.length - 1]
  return `<svg class="sline" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" aria-hidden="true">
    <path d="${d}" fill="none" stroke="var(--accent-text)" stroke-width="1.5"
          stroke-linecap="round" stroke-linejoin="round"/>
    <circle cx="${lx.toFixed(1)}" cy="${ly.toFixed(1)}" r="2.5" fill="var(--accent-text)"/>
  </svg>`
}

const spark = (kind) => (kind === 'none' ? '' : kind === 'line' ? sparkLine() : sparkBars(kind))

const chev = () => '<span class="chev"><span>›</span></span>'

// ---------------------------------------------------------------- 四条方案 + 现状

/** 现状原样：label 12px 暗字、数据段一个色（词/数/点全是 labelForeground）、3px 点阵、裸 ›。 */
const stripNow = (d) => `
  <div class="strip strip--now">
    <span class="t t--now">使用量</span>
    <span class="run">${runToday(d)}${dot}${runCost(d)}${d.quota ? dot + quotaPlain(d) : ''}${d.bill ? dot + runBill(d) : ''}${d.balance ? dot + runBal(d) : ''}</span>
    ${spark('now')}
    <span class="chev chev--bare">›</span>
  </div>`

/** 甲 · 静字：词暗数亮；「使用量」缩一号 + 发丝竖线；实条走势 + 基线；› 进小圈。 */
const stripA = (d, sparkKind = 'solid') => `
  <div class="strip">
    <span class="t">使用量</span><span class="rail"></span>
    <span class="run">${runToday(d)}${dot}${runCost(d)}${tailA(d)}</span>
    ${spark(sparkKind)}${chev()}
  </div>`

/** 乙 · 分舱：两枚胶囊分踞两舷 —— 今日账靠左、额度/账单账贴右。 */
const stripB = (d) => {
  const tail = d.quota ? quotaPlain(d) : d.bill ? runBill(d) : d.balance ? runBal(d) : ''
  return `
  <div class="strip">
    <span class="t">使用量</span><span class="rail"></span>
    <span class="chip">${runToday(d)}${dot}${runCost(d)}</span>
    <span class="chip chip--tail">${tail}</span>
    ${spark('solid')}${chev()}
  </div>`
}

/** 丙 · 额度条：三根可伸的进度条摆满中段（无额度时回落成 甲）。 */
const stripC = (d) => {
  if (!d.quota) return stripA(d)
  const bars = d.quota.map(([k, p]) => {
    const tone = p >= 90 ? ' danger' : p >= 70 ? ' warn' : ''
    return `<span class="qb"><span class="l">${k}</span><span class="tk"><span class="fl${tone}" style="width:${p}%"></span></span><span class="pv">${p}%</span></span>`
  }).join('')
  return `
  <div class="strip">
    <span class="t">使用量</span><span class="rail"></span>
    <span class="run">${runToday(d)}${dot}${runCost(d)}</span>
    <span class="qbars">${bars}</span>
    ${chev()}
  </div>`
}

/** 丁 · 疏排：中点全去，数据等距铺开（表格排法），走势与 › 仍钉右端。 */
const stripD = (d) => {
  const cells = [runToday(d), runCost(d)]
    .concat(d.quota ? d.quota.map(([k, p]) => seg(k, p + '%', true)) : [])
    .concat(d.bill ? [runBill(d)] : []).concat(d.balance ? [runBal(d)] : [])
    .map((c) => `<span class="cell">${c}</span>`).join('')
  return `
  <div class="strip">
    <span class="t">使用量</span><span class="rail"></span>
    <span class="run run--cells">${cells}</span>
    ${spark('solid')}${chev()}
  </div>`
}

// ---------------------------------------------------------------- 窗片

/** 状态卡行裁掉上半、输入卡裁掉下半 —— 条就夹在这两者之间（真布局：5px / 条 / 7px）。 */
const win = (stripHtml, caption) => `
  <div class="fig">
    ${caption ? `<div class="figcap">${caption}</div>` : ''}
    <div class="win">
      <div class="stubs"><i style="width:150px"></i><i style="width:224px"></i><i style="width:188px"></i></div>
      ${stripHtml}
      <div class="comp"><div class="comp__ph">@ 文件 · # 符号 · / 命令 · Enter 发送 · Shift+Enter 换行</div></div>
    </div>
  </div>`

/** 裸条（不带窗片）：换一家账 / 可选件用。 */
const bare = (stripHtml, caption) => `
  <div class="fig fig--bare">
    ${caption ? `<div class="figcap">${caption}</div>` : ''}
    <div class="win win--tight">${stripHtml}</div>
  </div>`

// ---------------------------------------------------------------- 页面

const O = DATA.opencode, D = DATA.dmx

function page(theme) {
  const T = THEMES[theme]
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>使用量常驻条 · 重画选型台</title>
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
.wrap { max-width: 1010px; margin: 0 auto; padding: 52px 32px 120px; }

.masthead { display:flex; align-items:flex-end; justify-content:space-between; gap:32px;
            flex-wrap:wrap; padding-bottom: 22px; border-bottom: 1px solid var(--page-line); }
h1 { font-family: var(--serif); font-size: 36px; font-weight: 600; margin: 0 0 10px; line-height:1.15; }
.lede { color: var(--page-dim); max-width: 96ch; margin: 0; font-size: 14.5px; }
.lede b { color: var(--page-fg); }
.eyebrow { font-family: var(--mono); font-size: 11px; letter-spacing:.14em; text-transform: uppercase;
           color: var(--page-dim); margin: 0 0 14px; }
.toggle { display:inline-flex; border:1px solid var(--page-line); border-radius: 8px; overflow:hidden; background: var(--page-card); }
.toggle button { font-family: var(--mono); font-size: 11px; letter-spacing:.08em; text-transform:uppercase;
                 padding: 9px 16px; border:0; background:transparent; color:var(--page-dim); cursor:pointer; }
.toggle button[aria-pressed="true"] { background: var(--accent); color: #fff; }

.brief { margin-top: 30px; padding: 20px 24px; border:1px solid var(--page-line);
         border-left: 3px solid var(--accent); border-radius: 0 10px 10px 0; background: var(--page-card); }
.brief h3 { margin: 0 0 8px; font-size: 14px; font-weight: 600; }
.brief p { margin: 0 0 8px; color: var(--page-dim); font-size: 13.5px; max-width: 110ch; }
.brief p:last-child { margin-bottom: 0; }
.brief b { color: var(--page-fg); }
.brief code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--page-fg); }

h2.sect { font-family: var(--serif); font-size: 25px; font-weight: 600; margin: 54px 0 4px; }
.sect-note { color: var(--page-dim); font-size: 13.5px; margin: 0 0 22px; max-width: 110ch; }
.sect-note b { color: var(--page-fg); }

.grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(810px, 1fr)); gap: 26px; align-items:start; }
.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px; padding: 18px 18px 20px; }
.opt__head { display:flex; gap: 12px; margin-bottom: 8px; }
.opt__pick { font-family: var(--mono); font-size: 12px; letter-spacing:.08em; color:#fff; background: var(--accent);
             width: 30px; height: 30px; border-radius: 8px; display:flex; align-items:center; justify-content:center; flex:0 0 auto; }
.opt__name { font-size: 15px; font-weight: 600; }
.opt__note { color: var(--page-dim); font-size: 12.5px; margin-top: 3px; max-width: 96ch; }
.opt__note b { color: var(--page-fg); }
.opt__note code { font-family: var(--mono); font-size: 11.5px; background: var(--code); padding: 1px 4px; border-radius: 3px; color: var(--page-fg); }
.opt__gain { margin: 8px 0 12px; padding: 0; list-style: none; font-size: 12.5px; color: var(--page-dim); }
.opt__gain li { margin: 2px 0; padding-left: 16px; position: relative; }
.opt__gain li::before { content:'·'; position:absolute; left:4px; color: var(--accent-text); font-weight:700; }

/* ---- 窗片与条 ---- */
.fig { margin: 12px 0 0; }
.figcap { font-family: var(--mono); font-size: 10.5px; letter-spacing:.08em; color: var(--page-dim);
          text-transform: uppercase; margin: 0 0 6px 2px; }
.win { width: 760px; max-width: 100%; background: var(--bg); padding: 0 8px 12px; border-radius: 6px; }
.win--tight { padding: 10px 8px; }
.stubs { display:flex; gap:8px; justify-content:space-between; }
.stubs i { display:block; height:12px; border:1px solid var(--border); border-top:0; border-radius:0 0 8px 8px; }
.comp { height:34px; margin-top:7px; border:1px solid var(--border); border-bottom:0;
        border-radius:8px 8px 0 0; background:var(--bg); padding:7px 10px; overflow:hidden; }
.comp__ph { font-size:11.5px; color:var(--dim); }

.strip { height:28px; margin-top:5px; border:1px solid var(--border); border-radius:8px;
         background:var(--bg); display:flex; align-items:center; padding:0 9px; cursor:pointer; }
.win--tight .strip { margin-top:0; }

.strip .t { font-size:11px; color:var(--dim); flex:0 0 auto; }
.strip .t--now { font-size:12px; margin-right:8px; }
.rail { flex:0 0 auto; width:1px; height:14px; background:var(--border); margin:0 9px; }
.strip .run { display:flex; align-items:baseline; flex:0 0 auto; min-width:0; }
.strip .w { color:var(--dim); }
.strip .n { color:var(--text); font-weight:400; }
.strip .d { color:var(--dim); margin:0 6px; font-size:11px; }
.ws { display:inline-block; width:3px; }
.gg { width:12px; flex:0 0 auto; }

/* 现状：数据段一个色、无竖线 */
.strip--now .w, .strip--now .d, .strip--now .n { color:var(--text); }

/* 走势 */
.spark { margin-left:auto; display:flex; align-items:flex-end; gap:2px; height:15px;
         border-bottom:1px solid color-mix(in srgb, var(--border) 65%, transparent); }
.spark i { display:block; border-radius:1px 1px 0 0;
           background: color-mix(in srgb, var(--accent-text) 55%, transparent); }
.spark i.on { background: var(--accent-text); }
.spark--now { height:15px; border-bottom-color: transparent; gap:2px; }
.spark--now i { width:3px; border-radius:1px; }
.spark--solid i { width:4px; }
.sline { margin-left:auto; flex:0 0 auto; }

/* › 两种形态：裸字符（现状）/ 小圈（四方案） */
.chev--bare { color:var(--dim); font-family:var(--mono); font-size:12px; flex:0 0 auto;
              margin-left:8px; }
.chev { flex:0 0 auto; width:16px; height:16px; margin-left:8px; border:1px solid var(--border);
        border-radius:50%; display:flex; align-items:center; justify-content:center;
        color:var(--dim); font-size:12px; line-height:1; }
.chev span { transform:translateX(1px); }

/* 乙 的胶囊 */
.chip { height:20px; display:flex; align-items:center; padding:0 8px; border-radius:4px;
        background:var(--chip); flex:0 0 auto; }
.chip--tail { margin-left:auto; }
.chip + .spark { margin-left:8px; }

/* 丙 的进度条（可伸：轨道吃掉剩余宽） */
.qbars { flex:1 1 auto; min-width:0; display:flex; gap:16px; margin:0 4px 0 14px; }
.qb { flex:1 1 0; min-width:0; display:flex; align-items:center; gap:5px; }
.qb .l { font-size:11px; color:var(--dim); flex:0 0 auto; }
.qb .tk { flex:1 1 auto; min-width:24px; height:6px; border-radius:3px;
          background:var(--track); overflow:hidden; }
.qb .fl { display:block; height:100%; border-radius:3px; background:var(--accent-text); }
.qb .fl.warn { background:var(--warn); }
.qb .fl.danger { background:var(--danger); }
.qb .pv { font-size:11px; color:var(--dim); flex:0 0 auto; }

/* 丁 的等距排（选择器带 .strip 前缀 —— 要比 .strip .run 的 flex:0 0 auto 更具体） */
.strip .run--cells { flex:1 1 auto; min-width:0; justify-content:space-between; margin-right:12px; }
.run--cells .cell { flex:0 0 auto; }

/* 表面可选件 */
.strip--hairline { border:0; border-radius:0; border-top:1px solid var(--border); background:transparent; }
.strip--soft { border:0; border-radius:8px; background:var(--chip); }

/* ---- 表 ---- */
table.cmp { width:100%; border-collapse: collapse; margin-top: 20px; font-size: 12.5px; }
table.cmp th, table.cmp td { text-align:left; padding: 9px 10px; border-bottom:1px solid var(--page-line); vertical-align: top; }
table.cmp th { color: var(--page-dim); font-family: var(--mono); font-size: 10.5px; letter-spacing:.1em;
               text-transform:uppercase; font-weight:500; }
table.cmp td { color: var(--page-dim); }
table.cmp td:first-child { color: var(--text); white-space:nowrap; }
table.cmp code { font-family: var(--mono); font-size: 11.5px; background: var(--code); padding: 1px 4px; border-radius: 3px; color: var(--text); }

.verdict { margin-top: 46px; padding: 24px 26px; border:1px solid var(--page-line); border-radius: 14px;
           background: var(--page-card); border-left: 3px solid var(--ok); }
.verdict h2 { font-family: var(--serif); font-size: 24px; margin: 0 0 12px; }
.verdict p { color: var(--page-dim); margin: 0 0 10px; max-width: 100ch; }
.verdict b { color: var(--page-fg); }
.verdict code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }

.stack { display:flex; flex-direction:column; gap: 4px; }
.stack .fig { margin-top: 8px; }
</style>
</head>
<body>
<div class="wrap">
  <div class="masthead">
    <div>
      <p class="eyebrow">2026-10-14 · 选型台 · 不参与构建</p>
      <h1>「使用量」常驻条：四个排法，两组可选件</h1>
      <p class="lede">只动"怎么画"：<b>数据、点击开统计框、悬停反馈、窄窗降级、第一份数据之前不显示</b>
        —— 全部沿用现状。下面的窗片都是 760px（你这台机器截图里条本体 739px）；条高 28、
        半径 8、走势 14 根，几何照实；深浅两套都渲染，右上角切换。</p>
    </div>
    <div class="toggle">
      <button data-theme="dark" aria-pressed="true">深色</button><button data-theme="light" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先说现状哪里不对（从你这张截图量的，不是感觉）</h3>
    <p>1. <b>空腔</b>：条本体 739px，文字段只走到 x≈330，走势从 x≈650 才起 —— 中间<b>空着 320px</b>，
       窗口越宽越空。2. <b>走势像灰</b>：14 根 3px 柱、最低 1.5px、55% 透明度，远看就是一排点，
       还接在「月 5%」后面 300px 处，与数据脱节。3. <b>› 孤悬</b>：裸字符、无承托，
       读者看不出"整条可点"。4. <b>行首弱、无层级</b>：12px 暗字「使用量」+ 8px 空格，
       数据段里词、数、点同一个色，读起来一粒一粒没有主次。</p>
    <p>四个排法分别对治；<b>走势画法与表面处理另有两组可选件</b>，与任一排法叠着选。回一个组合就行
       （例：<code>丙 + 走势线 + 软底</code>）。</p>
  </div>

  <h2 class="sect">零 · 现状</h2>
  <p class="sect-note">截图里那条，原样重画（走势按截图里"矮多高一"的形状示意）。</p>
  ${win(stripNow(O), '现状 · opencode 账（同你的截图）')}

  <h2 class="sect">一、四个排法</h2>
  <p class="sect-note">从最保守到最"重排"。四者都不改高度、圆角、底色、边框（与上下卡片同一套 —— 这是
    2026-10-13 定的规矩）；变的只是<b>内容怎么摆、凭什么占住这条 739px</b>。</p>

  <div class="grid">
    <div class="opt">
      <div class="opt__head"><span class="opt__pick">甲</span><div>
        <div class="opt__name">静字 —— 不填物，修两端</div>
      </div></div>
      <div class="opt__note">同一套排布，把笔画理出来：「使用量」缩一号退成路牌，后接一根发丝竖线；
        数据段<b>词暗、数亮</b>（今日/≈/5h 这些是词），组间 12px；右端走势改<b>实条 + 基线</b>
        （4px 宽、最后一根满色），› 收进一枚小圆。</div>
      <ul class="opt__gain">
        <li>治 2/3/4；空腔不动 —— 中央留白是"状态条"的常规，读起来安静</li>
        <li>Swing 实现量最小：现结构上换几笔颜色 + 竖线/圆/基线三处新绘制</li>
      </ul>
      ${win(stripA(O))}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">乙</span><div>
        <div class="opt__name">分舱 —— 两枚胶囊分踞两舷</div>
      </div></div>
      <div class="opt__note">「今日账」（token + 估算）与「额度账」各装进一枚胶囊（底色 = 前景 7% 混在面板上，
        无边框），一枚靠左、一枚贴右，中间的空当读作分隔而不是断裂。</div>
      <ul class="opt__gain">
        <li>治 1/4：账目各归各舱，一眼能数出"今天花了多少、额度还剩多少"</li>
        <li>DMX / DeepSeek 账时右舱换成「实扣 ¥…」「余额 ¥…」，形状不变</li>
      </ul>
      ${win(stripB(O))}
      ${bare(stripB(D), '换 DMX 账：右舱 = 实扣')}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">丙</span><div>
        <div class="opt__name">额度条 —— 三根可伸的进度条摆满中段</div>
      </div></div>
      <div class="opt__note">额度那三段从文字变<b>三根进度条</b>：轨道吃掉剩余宽度（窗口越宽条越长），
        已用比例直接画出来，颜色沿用统计框的阈值（≥70 琥珀、≥90 红）。有额度条时走势让位
        （信息不重复）；<b>没有额度</b>的账（DMX / DeepSeek）回落成 甲 的排版 + 实条走势。</div>
      <ul class="opt__gain">
        <li>治 1 治得最实：320px 空腔被三条轨道填掉，「离满多远」不用读数字</li>
        <li>Swing：三组 fillRoundRect（弧 6 / 12 各一处），轨道宽 = 剩余宽三等分</li>
      </ul>
      ${win(stripC(O))}
      ${bare(stripC(D), '无额度账：回落成 甲（实条走势）')}
      ${bare(stripC(DATA.tight), '吃紧态：72% 琥珀 / 96% 红（阈值同统计框）')}
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">丁</span><div>
        <div class="opt__name">疏排 —— 去掉中点，等距铺开</div>
      </div></div>
      <div class="opt__note">一个点都不画：数据段当一行小表格排 —— 各段<b>等距</b>分布在左区，
        距离本身就是分隔符。走势与 › 仍钉右端。</div>
      <ul class="opt__gain">
        <li>治 1/4：空被均分成四五口气，行读起来"开阔"；窄窗时等距自动收紧</li>
        <li>代价：组别感最弱（今日账与额度账不再抱团），缺段时间距会变大</li>
      </ul>
      ${win(stripD(O))}
    </div>
  </div>

  <h2 class="sect">二、可选件一 · 走势四画法</h2>
  <p class="sect-note">走势长什么样独立于排法（下面用 甲 的排版演示；乙/丙/丁 同样可换）。同一组 14 天数据。</p>
  <div class="stack">
    ${win(stripA(O, 'now'), '点阵（现状参数：3px 宽 / 最低 1.5px / 55%）')}
    ${win(stripA(O, 'solid'), '实条 + 基线（甲乙丁 默认）')}
    ${win(stripA(O, 'line'), '走势线（拉伸；宽度吃剩余空腔，尾点加圆点）')}
    ${win(stripA(O, 'none'), '不画（空腔全留白；数据仍是数字，走势只在统计框里看）')}
  </div>

  <h2 class="sect">三、可选件二 · 表面三选</h2>
  <p class="sect-note">条"卡"到什么程度，独立于排法（下面用 甲 的排版演示）。注意第二、三种会打破
    2026-10-13「与上下卡片同一套边/底」的规矩 —— 列出来是给你权衡，不是推荐。</p>
  <div class="stack">
    ${win(stripA(O), '现状卡面：1px 描边 + 面板底 + 半径 8（与邻居卡同一套，默认）')}
    ${win(stripA(O).replace('class="strip"', 'class="strip strip--hairline"'), '发丝线：无框无底，只留一条上沿发丝线（最轻，也最"不像卡"）')}
    ${win(stripA(O).replace('class="strip"', 'class="strip strip--soft"'), '软底：无描边、微底色（前景 5%）—— 卡感弱一档，仍是一条')}
  </div>

  <h2 class="sect">四、对照表</h2>
  <table class="cmp">
    <tr><th>方案</th><th>治哪条</th><th>一眼看到的变化</th><th>Swing 实现（画什么）</th><th>代价 / 风险</th></tr>
    <tr><td>甲 静字</td><td>2/3/4</td><td>数字先亮；走势从"点"变成"图"；› 有了承托</td>
        <td>现结构 + 换色 drawString + 竖线/圆/基线三处新绘制，量最小</td>
        <td>空腔原样（320px）；变化偏细，第一眼可能觉得"没变"</td></tr>
    <tr><td>乙 分舱</td><td>1/4</td><td>两枚胶囊分踞两舷，账目清清楚楚</td>
        <td>两处 fillRoundRect（弧 8）+ 内含现有 drawString；文字测量同现状</td>
        <td>胶囊底色在浅色主题要够淡（已按 7% 算）；窄窗时先叠回一行文字</td></tr>
    <tr><td>丙 额度条</td><td>1（最实）</td><td>三条轨道吃掉空腔；吃紧自动变色</td>
        <td>三组 track/fill 的 fillRoundRect；轨道宽 = (剩余宽 − 标签/百分比)/3</td>
        <td>有额度 = 无走势（形态两分）；与统计框的信息重复一次（无妨）</td></tr>
    <tr><td>丁 疏排</td><td>1/4</td><td>无点、等距，行读起来开阔</td>
        <td>先量后算 gap；删掉中点绘制</td>
        <td>组别感最弱；缺段（只剩今日 + 额度）时间距会变大，得设上限</td></tr>
  </table>

  <div class="verdict">
    <h2>怎么回</h2>
    <p>回一个组合就行：排法 <b>甲 / 乙 / 丙 / 丁</b> 选一，可选件按需叠 —— 例：<code>丙 + 走势线</code>、
       <code>乙 + 软底</code>、<code>甲（全默认）</code>。都不对也直接说，我按你的方向再出一轮。</p>
    <p>另：悬停（整条底色变亮）四者都不动；窄窗（放不下）沿用现状"先丢走势、再丢额度段"的降级，
       选了丙之后窄窗先丢的就是三根轨道；这些到了实现阶段都能再调。</p>
  </div>
</div>

<script>
const root = document.documentElement
const btns = Array.from(document.querySelectorAll('.toggle button'))
function setTheme(t) {
  root.setAttribute('data-theme', t)
  btns.forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.theme === t)))
}
btns.forEach((b) => b.addEventListener('click', () => setTheme(b.dataset.theme)))
setTheme(root.getAttribute('data-theme') || 'dark')
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

if (shotArg) {
  const chromium = CHROMIUM_CANDIDATES.find(existsSync)
  if (!chromium) {
    console.error('找不到 Chromium，截图跳过')
    process.exitCode = 2
  } else {
    let target = outFile
    let name = 'usage-strip-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'usage-strip-view-light.html')
      writeFileSync(target, page('light'))
      name = 'usage-strip-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1120,5200',
       '--virtual-time-budget=2500', `--screenshot=${join(outDir, name)}`,
       'file:///' + target.replace(/\\/g, '/')],
      { stdio: 'ignore' },
    )
    console.log('截图 →', join(outDir, name))
  }
}
if (process.argv.includes('--open')) {
  const url = 'file:///' + outFile.replace(/\\/g, '/')
  execFileSync('cmd', ['/c', 'start', '', url], { stdio: 'ignore' })
  console.log('已在默认浏览器打开')
}
