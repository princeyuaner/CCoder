#!/usr/bin/env node
/**
 * 思考配色的选型台 —— 一次性工具，不参与构建。
 *
 * 2026-09-15 用户第三次说"思考输出的颜色还是不好看"。前两轮都在**同一个色相**
 * （奶油黄）里调亮调暗（042bddc → 17ed647），所以这次把方向摊开：暖 / 中性 / 冷、
 * 字色 / 底纹，一次性并排摆出来，由用户在浏览器里挑一个，再动 Kotlin 的常量。
 *
 * 三条保真规则（照抄 tools/layout-probe.mjs 的做法）：
 *
 *   1. 链接**真实**的 src/styles.css —— 卡片里的 DOM 就是 Transcript 那套类名，
 *      不是照着截图描的近似品；
 *   2. 色值不是手填的十六进制，而是**复刻 ThemeInjector 的 mix()** ——
 *      页面上写着的那个 #rrggbb，就是选了该方案后 Kotlin 侧会算出来的值。
 *      于是"在这里挑中的颜色"可以直接换成常量，中间没有一道手工翻译；
 *   3. 深色 / 浅色两套都渲染。现方案在浅色主题下本来就有个坑（#d5c69d 压在白底上
 *      约 1.6:1，等于看不见）—— 摆出来的证据比在注释里写一句管用。
 *
 * 用法：
 *   node tools/thinking-palette.mjs --open      # 生成并用默认浏览器打开
 *   node tools/thinking-palette.mjs --shot      # 生成并截图（给自己看的）
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const cssPath = join(webDir, 'src', 'styles.css')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'thinking-palette.html')

// ---------------------------------------------------------------- 颜色运算

const rgb = (hex) => {
  const h = hex.replace('#', '')
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}
const hex = ([r, g, b]) =>
  '#' + [r, g, b].map((c) => Math.round(c).toString(16).padStart(2, '0')).join('')

/** 复刻 PlatformTheme.mix(base, fg, ratio)：把 fg 按比例混进 base。 */
const mix = (base, fg, ratio) => {
  const b = rgb(base)
  const f = rgb(fg)
  return hex(b.map((c, i) => c + (f[i] - c) * ratio))
}

/** WCAG 相对亮度 —— 对比度两个色都要算，写成一份。 */
const lum = (c) => {
  const [r, g, b] = rgb(c).map((v) => {
    const s = v / 255
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const contrast = (a, b) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p)
  return (x + 0.05) / (y + 0.05)
}

// ---------------------------------------------------------------- 两套主题
//
// 值取自 ThemeInjector 会读到的平台色（Darcula 新 UI / Light）。diff 那几组
// 是按同样的 mix 现算的 —— 它们要出现在工具卡里，缺了就不是完整的画面。

function theme(bg, text, textDim, border, accent, surface, codeBg) {
  return {
    bg, text, textDim, border, accent, surface, codeBg,
    errorBg: mix(text, '#D84315', 0.85),
    diffAddBg: mix(bg, '#4CAF50', 0.16),
    diffDelBg: mix(bg, '#E05454', 0.16),
    diffAddFg: mix(text, '#4CAF50', 0.6),
    diffDelFg: mix(text, '#E05454', 0.6),
  }
}

const THEMES = {
  dark: theme('#1e1f22', '#dcdcdc', '#8c8c8c', '#393b40', '#4a88c7', '#2b2d30', '#191a1c'),
  light: theme('#f7f8fa', '#000000', '#7a7a7a', '#d1d1d1', '#2f6fb5', '#ffffff', '#f2f2f2'),
}

// ---------------------------------------------------------------- 方案表
//
// 每个方案 = 一个**锚色 + 混合比例**（字色类），或者一组显式色值 + 追加 CSS
// （底纹类）。锚色就是将来要写进 ThemeInjector 的那个常量。

/** `--only=G` 只看一个（放大看细节用；也给用户留一条"只摆这两个给我比"的路）。 */
const ONLY = (process.argv.find((a) => a.startsWith('--only=')) || '').slice(7)

const ALL_OPTIONS = [
  {
    id: 'A',
    name: '现状 · 奶油黄',
    tag: '对照',
    why: '现在装着的就是这个。摆进来当基准，你可以直接说"就它"。',
    warn: '浅色主题下 #d5c69d 压在白底上约 1.6:1 —— 这一档是真的看不见',
    dark: { anchor: '#F5E3B3', ratio: 0.85 },
    light: { anchor: '#F5E3B3', ratio: 0.85 },
  },
  {
    id: 'B',
    name: '亚麻 · 去黄',
    tag: '字色',
    why: '暖调留着，把饱和度抽掉大半。还是"纸"的感觉，但不再是荧光笔。',
    dark: { anchor: '#CDC5B2', ratio: 0.85 },
    light: { anchor: '#706A5C', ratio: 0.85 },
  },
  {
    id: 'C',
    name: '中性 · 不染色',
    tag: '字色',
    why: '一个颜色都不给：比正文暗一档的灰，靠斜体和左边框说明"这段是思考"。',
    dark: { anchor: '#B3B8C0', ratio: 0.85 },
    light: { anchor: '#5D636C', ratio: 0.85 },
  },
  {
    id: 'D',
    name: '薰衣草',
    tag: '字色',
    why: '推理界面里的常见色。紫和工具卡的绿、报错的红、强调的蓝都不打架。',
    dark: { anchor: '#C0ABED', ratio: 0.85 },
    light: { anchor: '#6B50BC', ratio: 0.85 },
  },
  {
    id: 'E',
    name: '青瓷',
    tag: '字色',
    why: '冷静的一档冷色。比紫色更"技术"，也和成功态的绿离得够远。',
    dark: { anchor: '#94D5C5', ratio: 0.85 },
    light: { anchor: '#2D7C6F', ratio: 0.85 },
  },
  {
    id: 'F',
    name: '琥珀沙',
    tag: '字色',
    why: '还是黄，但往橙棕压了：像旧纸，不像高亮笔。纵深比奶油黄足。',
    dark: { anchor: '#D7AD73', ratio: 0.85 },
    light: { anchor: '#906A23', ratio: 0.85 },
  },
  {
    id: 'G',
    name: '底纹块',
    tag: '结构',
    why:
      '思路反过来：不给字染色。正文该多亮就多亮，思考的标记交给容器 ——' +
      '淡底色 + 一道色条。读长段落时最不容易累，代价是要多几条 CSS。',
    css: true,
    dark: { fg: '#d3d7de', bg: '#312e2c', bar: '#8a7452' },
    light: { fg: '#2f343b', bg: '#efece6', bar: '#b09155' },
  },
]

const OPTIONS = ONLY
  ? ALL_OPTIONS.filter((o) => ONLY.split(',').some((id) => o.id === id.trim()))
  : ALL_OPTIONS

/** 方案在两套主题下的实际色值 —— 字色类走 mix，结构类直接给。 */
function colorsOf(opt) {
  const out = {}
  for (const name of ['dark', 'light']) {
    const t = THEMES[name]
    const spec = opt[name]
    if (spec.fg) out[name] = { fg: spec.fg, bg: spec.bg, bar: spec.bar }
    else out[name] = { fg: mix(t.text, spec.anchor, spec.ratio) }
  }
  return out
}

// ---------------------------------------------------------------- 页面

const THINK_TEXT =
  '用户说的是状态卡在子代理跑任务时整排会变高。那问题应该出在高度是跟着内容算的，' +
  '而不是定死的 —— 先把 StatusCardsTest 里那几条断言翻一遍，确认这排高度到底是谁在撑。'

const LIVE_TEXT = '还在想这一段：先确认是算出来的还是写死的……'

const BODY_TEXT = '复现了：整排高度跟着最长的那个子任务走。我把它定死，再补一条断言守着。'

const CHECK_SVG =
  '<span class="tool__status"><svg class="tool__check" viewBox="0 0 16 16">' +
  '<path d="M3.5 8.5l3 3 6.5-7.5"/></svg></span>'

const RUN_GLYPH =
  '<span class="tool__badge tool__badge--run"><svg class="tool__glyph" viewBox="0 0 12 12">' +
  '<path d="M2.6 3.2 L5.4 5.6 L2.6 8"/><path d="M6.4 8.4 h3.2"/></svg></span>'

/** DOM 结构照抄 Transcript.tsx / ThinkingBlock.tsx —— 类名一个不少。 */
function sample() {
  const head = (inner, open) =>
    '<button type="button" class="collapsible__head" aria-expanded="' + open + '">' +
    '<span class="collapsible__chevron is-open">▸</span>' +
    '<span class="collapsible__title">' + inner + '</span></button>'

  return (
    '<div class="collapsible">' +
    head('思考过程', 'true') +
    '<div class="collapsible__body"><div class="thinking-text">' + THINK_TEXT + '</div></div>' +
    '</div>' +
    '<div class="collapsible">' +
    head(
      '<span class="spin live-think__spin"></span><span>思考中</span>' +
        '<span class="live-think__time">12s</span>',
      'true',
    ) +
    '<div class="collapsible__body"><div class="thinking-text">' + LIVE_TEXT + '</div></div>' +
    '</div>' +
    '<div class="entry"><div class="row row--assistant">' +
    '<div class="bubble bubble--assistant"><div class="bubble__text">' + BODY_TEXT + '</div></div>' +
    '</div></div>' +
    '<div class="tool"><div class="tool__head" role="button" tabindex="-1" aria-expanded="false">' +
    '<span class="tool__chevron">▸</span>' +
    RUN_GLYPH +
    '<span class="tool__name">Bash</span>' +
    '<span class="tool__title">node --test test/status.test.js</span>' +
    CHECK_SVG +
    '</div></div>'
  )
}

function themeVars(t) {
  return Object.entries(t)
    .map(([k, v]) => `--${k.replace(/[A-Z]/g, (m) => '-' + m.toLowerCase())}:${v}`)
    .join(';')
}

function optionCss(opt) {
  const r = colorsOf(opt)
  let css =
    `[data-opt="${opt.id}"]{--thinking-fg:${r.dark.fg}}` +
    `html[data-theme="light"] [data-opt="${opt.id}"]{--thinking-fg:${r.light.fg}}`
  if (opt.css) {
    // 结构类：字色之外还要底色与色条。这几条就是将来要往 styles.css 里加的
    // 东西 —— 摆在页面上，代价看得见。
    css +=
      `[data-opt="${opt.id}"]{--think-bg:${r.dark.bg};--think-bar:${r.dark.bar}}` +
      `html[data-theme="light"] [data-opt="${opt.id}"]{--think-bg:${r.light.bg};--think-bar:${r.light.bar}}` +
      `[data-opt="${opt.id}"] .thinking-text{background:var(--think-bg);border-radius:6px;padding:5px 9px}` +
      `[data-opt="${opt.id}"] .collapsible{border-left-color:var(--think-bar)}`
  }
  return css
}

function chip(fg, bg, label) {
  const c = contrast(fg, bg)
  const bad = c < 4.5
  return (
    `<span class="num${bad ? ' num--bad' : ''}">` +
    `<i style="background:${fg}"></i>${label} <code>${fg}</code> ${c.toFixed(1)}:1` +
    `${bad ? ' ⚠' : ''}</span>`
  )
}

function card(opt) {
  const r = colorsOf(opt)
  const nums =
    chip(r.dark.fg, THEMES.dark.bg, '深色') + chip(r.light.fg, THEMES.light.bg, '浅色')
  return (
    `<article class="opt" data-opt="${opt.id}" tabindex="0" aria-label="方案 ${opt.id}">` +
    `<div class="opt__head"><span class="opt__id">${opt.id}</span>` +
    `<h2>${opt.name}</h2><span class="tag">${opt.tag}</span></div>` +
    `<p class="why">${opt.why}</p>` +
    (opt.warn ? `<p class="warn">⚠ ${opt.warn}</p>` : '') +
    `<div class="nums">${nums}</div>` +
    `<div class="sample"><div class="transcript">${sample()}</div></div>` +
    (opt.css ? `<p class="cost">代价：多两条 CSS 变量 + 三条规则（页面上这几条就是将来要加的那几条）</p>` : '') +
    `<button type="button" class="pick">选这个</button>` +
    `</article>`
  )
}

const SUMMARY = OPTIONS.map((o) => {
  const r = colorsOf(o)
  const line =
    `  ${o.id}  ${o.name.padEnd(10, '　')} 深 ${r.dark.fg}` +
    ` (${contrast(r.dark.fg, THEMES.dark.bg).toFixed(1)}:1)` +
    `  浅 ${r.light.fg} (${contrast(r.light.fg, THEMES.light.bg).toFixed(1)}:1)`
  return o.warn ? line + '  ⚠ 浅色偏淡' : line
}).join('\n')

const page = `<!doctype html>
<html lang="zh-CN" data-theme="dark">
<head>
<meta charset="utf-8">
<title>思考输出 · 配色选型</title>
<link rel="stylesheet" href="file:///${cssPath.replace(/\\/g, '/')}">
<style>
html{${themeVars(THEMES.dark)};--chrome-bg:#101215}
html[data-theme="light"]{${themeVars(THEMES.light)};--chrome-bg:#e9ebee}
body{background:var(--chrome-bg);color:var(--text);margin:0;padding:24px 24px 96px;
  font-family:"Segoe UI","Microsoft YaHei",sans-serif;font-size:13px;line-height:1.55;
  height:auto;overflow:auto}
${OPTIONS.map(optionCss).join('\n')}
header{max-width:1460px;margin:0 auto 18px}
h1{font-size:19px;margin:0 0 6px}
.sub{color:var(--textDim);margin:0 0 14px;max-width:920px}
.controls{display:flex;gap:14px;align-items:center;flex-wrap:wrap}
.seg{display:inline-flex;border:1px solid var(--border);border-radius:7px;overflow:hidden}
.seg button{background:none;border:none;color:var(--textDim);font:inherit;padding:5px 14px;cursor:pointer}
.seg button.on{background:var(--accent);color:#fff}
label.chk{display:inline-flex;gap:6px;align-items:center;color:var(--textDim);cursor:pointer}
button.ghost{background:none;border:1px solid var(--border);border-radius:7px;color:var(--text);
  font:inherit;padding:5px 12px;cursor:pointer}
button.ghost:hover{border-color:var(--accent)}
#grid{max-width:1460px;margin:0 auto;display:grid;gap:18px;
  grid-template-columns:repeat(auto-fill,minmax(430px,1fr))}
.opt{border:1px solid var(--border);border-radius:10px;padding:14px;background:var(--surface);
  display:flex;flex-direction:column;gap:9px;cursor:pointer}
.opt:hover{border-color:var(--accent)}
.opt.is-picked{border-color:var(--accent);box-shadow:0 0 0 1px var(--accent)}
.opt__head{display:flex;align-items:center;gap:8px}
.opt__id{width:22px;height:22px;border-radius:6px;background:var(--border);color:var(--text);
  display:grid;place-items:center;font-weight:600;font-size:12px}
.opt.is-picked .opt__id{background:var(--accent);color:#fff}
h2{font-size:14px;margin:0}
.tag{font-size:11px;color:var(--textDim);border:1px solid var(--border);border-radius:10px;padding:1px 8px}
.why{margin:0;color:var(--textDim)}
.warn{margin:0;color:#e0a030}
.nums{display:flex;gap:10px;flex-wrap:wrap;font-size:12px}
.num{display:inline-flex;align-items:center;gap:5px;color:var(--textDim)}
.num code{color:var(--text)}
.num i{width:10px;height:10px;border-radius:3px;border:1px solid var(--border);display:inline-block}
.num--bad{color:#e06c60}
.sample{background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:8px}
.sample .transcript{height:auto;overflow:visible}
.cost{margin:0;color:var(--textDim);font-size:12px}
.pick{align-self:flex-start;background:none;border:1px solid var(--border);border-radius:7px;
  color:var(--text);font:inherit;padding:4px 12px;cursor:pointer}
.opt.is-picked .pick{background:var(--accent);color:#fff;border-color:var(--accent)}
#bar{position:fixed;left:0;right:0;bottom:0;padding:12px 24px;background:var(--chrome-bg);
  border-top:1px solid var(--border);display:flex;gap:12px;align-items:center;flex-wrap:wrap}
#bar b{font-weight:600}
#out{flex:1;min-width:280px;background:var(--bg);border:1px solid var(--border);border-radius:7px;
  color:var(--text);font:inherit;padding:6px 10px}
html.no-italic .thinking-text{font-style:normal}
</style>
</head>
<body>
<header>
  <h1>思考输出 · 配色选型</h1>
  <p class="sub">底色、字体、间距都是真的（直接链的 src/styles.css，DOM 照抄组件）。<b>变的是思考正文那一块</b>。
  挑一个，把底下那行发我。A 是现在的样子，摆着当基准。</p>
  <div class="controls">
    <div class="seg" id="seg"><button data-theme="dark" class="on">深色主题</button
      ><button data-theme="light">浅色主题</button></div>
    <label class="chk"><input type="checkbox" id="italic" checked> 斜体（中文没有真斜体，是浏览器拉斜的）</label>
    <span class="sub" style="margin:0">点卡片 = 选中；键盘 1–${OPTIONS.length} 也行</span>
  </div>
</header>
<main id="grid">${OPTIONS.map(card).join('')}</main>
<footer id="bar">
  <span>当前选择：<b id="who">—</b></span>
  <input id="out" readonly value="还没选" spellcheck="false">
  <button class="ghost" id="copy">复制</button>
</footer>
<script>
  const PICKED = ${JSON.stringify(
    OPTIONS.map((o) => ({ id: o.id, name: o.name })),
  )};
  const grid = document.getElementById('grid')
  const cards = [...grid.querySelectorAll('.opt')]
  const out = document.getElementById('out')
  const who = document.getElementById('who')
  let picked = null

  function refresh() {
    const italic = document.getElementById('italic').checked
    document.documentElement.classList.toggle('no-italic', !italic)
    if (picked === null) { who.textContent = '—'; out.value = '还没选'; return }
    const o = PICKED[picked]
    who.textContent = o.id + ' · ' + o.name
    out.value = '思考配色：选 ' + o.id + '「' + o.name + '」，斜体' + (italic ? '保留' : '去掉')
  }
  function pick(i) {
    picked = i
    cards.forEach((c, n) => c.classList.toggle('is-picked', n === i))
    refresh()
  }

  cards.forEach((c, i) => {
    c.addEventListener('click', () => pick(i))
    c.querySelector('.pick').addEventListener('click', (e) => { e.stopPropagation(); pick(i) })
  })
  document.addEventListener('keydown', (e) => {
    const n = parseInt(e.key, 10)
    if (n >= 1 && n <= cards.length) pick(n - 1)
  })
  document.getElementById('italic').addEventListener('change', refresh)
  document.querySelectorAll('#seg button').forEach((b) => {
    b.addEventListener('click', () => {
      document.documentElement.dataset.theme = b.dataset.theme
      document.querySelectorAll('#seg button').forEach((x) => x.classList.toggle('on', x === b))
    })
  })
  document.getElementById('copy').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(out.value) }
    catch { out.select(); document.execCommand('copy') }   // file:// 下 clipboard 可能没有
    const b = document.getElementById('copy')
    b.textContent = '已复制'
    setTimeout(() => (b.textContent = '复制'), 1200)
  })
  refresh()
</script>
</body>
</html>`

mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, page)

console.log('思考配色选型台 →', outFile)
console.log(SUMMARY)

// ---------------------------------------------------------------- 打开 / 截图

const CHROMIUM_CANDIDATES = [
  process.env.CCoder_CHROMIUM,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
].filter(Boolean)

const url = 'file:///' + outFile.replace(/\\/g, '/')
if (process.argv.includes('--shot')) {
  const chromium = CHROMIUM_CANDIDATES.find(existsSync)
  if (!chromium) {
    console.error('找不到 Chromium，截图跳过')
    process.exitCode = 2
  } else {
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1500,1250',
       '--virtual-time-budget=1500', `--screenshot=${join(outDir, 'thinking-palette.png')}`, url],
      { stdio: 'ignore' },
    )
    console.log('截图 →', join(outDir, 'thinking-palette.png'))
  }
}
if (process.argv.includes('--open')) {
  execFileSync('cmd', ['/c', 'start', '', url], { stdio: 'ignore' })
  console.log('已在默认浏览器打开')
}
