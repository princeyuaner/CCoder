#!/usr/bin/env node
/**
 * 布局探针 —— 用**真实 Chromium** 量关键元素的高度。
 *
 * 为什么必须有它：jsdom 不做布局。`web/src/*.test.tsx` 能证明 DOM 里有 86 个
 * `.tool` 元素，却看不出它们在屏幕上是 29px 还是 2px。2026-09-13 的
 * "工具调用一个都不显示"就是这么来的 ——
 *
 *   `.transcript` 是高度受限的 flex 列，工具卡片是它的直接子项（没有 .entry
 *   包裹），而 `overflow: hidden` 把 flex 项的自动最小尺寸从内容高改成了 0
 *   （CSS Flexbox §4.5）。内容溢出时全部压缩量堆到卡片身上：86 张卡片被压成
 *   只剩两条边框的 2px 细缝，而没设 overflow 的文字气泡纹丝不动。
 *
 * 单测全绿（DOM 正确）、日志全干净（推送成功）、装上就是看不见 —— 这类问题
 * 只有真的渲染一遍才抓得到。与 Kotlin 侧的 `*RenderProbe` 是同一个思路，
 * 这里是它在浏览器里的对应物。
 *
 * 2026-09-14 追加一条：还量卡片头是不是正好占满卡片宽（`headOverflow`）。
 * 卡片头从 `<button>` 变成 `div[role=button]` 之后，div 的 content-box 撞上
 * `width: 100%` 会让它比卡片宽出两个内边距，右侧的状态位直接被裁掉 ——
 * 也是同一类"DOM 全对、屏幕上不对"的问题。
 *
 * 用法：
 *   npm run probe:layout
 *   CCoder_CHROMIUM="C:\path\to\msedge.exe" npm run probe:layout
 *
 * 退出码：0 = 全部通过；1 = 有断言失败；2 = 找不到浏览器（**不算通过**）。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const cssPath = join(webDir, 'src', 'styles.css')
const outDir = resolve(webDir, '..', 'build', 'probe')

/** 常见安装位置。可以用环境变量覆盖。 */
const CHROMIUM_CANDIDATES = [
  process.env.CCoder_CHROMIUM,
  process.env.CHROMIUM_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].filter(Boolean)

function findChromium() {
  for (const p of CHROMIUM_CANDIDATES) {
    if (existsSync(p)) return p
  }
  return null
}

/**
 * 场景表。
 *
 * `overflow` 那条是 2026-09-13 事故的**真实规模**（38 条文字 + 86 张卡片），
 * 必须留着 —— 内容不溢出时 flex 压缩根本不触发，只用小场景是量不出问题的。
 */
const SCENARIOS = [
  { name: 'overflow', note: '内容严重溢出（38 文字 + 86 卡片 + 2 组）', entries: 38, tools: 86, thinking: 6, notes: 3, groups: 2 },
  { name: 'fits', note: '内容不溢出（2 文字 + 2 卡片 + 2 组）', entries: 2, tools: 2, thinking: 1, notes: 1, groups: 2 },
]

/** 卡片高度下限。卡片头是 5px 内边距 + 一行 12px 文字 + 2px 边框 ≈ 29px。 */
const MIN_CARD_H = 20
const MIN_HEAD_H = 16

function buildPage(scenario) {
  return `<!doctype html>
<html><head><meta charset="utf-8">
<link rel="stylesheet" href="file:///${cssPath.replace(/\\/g, '/')}">
<style>html, body { height: 100%; }</style>
</head><body>
<div class="transcript-wrap">
  <div class="transcript" id="t"></div>
</div>
<div id="measure" style="position:fixed;left:-9999px">PENDING</div>
<script>
  const t = document.getElementById('t')
  const S = ${JSON.stringify(scenario)}
  // DOM 结构照抄 Transcript.tsx：
  // 文字项有 .entry 包裹，thinking / toolUse / systemNote 都**没有**
  for (let i = 0; i < S.entries; i++) {
    const d = document.createElement('div')
    d.className = 'entry'
    d.innerHTML = '<div class="bubble">文字气泡 ' + i + '</div>'
    t.appendChild(d)
  }
  for (let i = 0; i < S.thinking; i++) {
    const d = document.createElement('div')
    d.className = 'thinking-text'
    d.textContent = '思考 ' + i
    t.appendChild(d)
  }
  for (let i = 0; i < S.notes; i++) {
    const d = document.createElement('div')
    d.className = 'system-note'
    d.textContent = '系统提示 ' + i
    t.appendChild(d)
  }
  // 状态位三种形态轮着来 —— 它们的宽度必须一致，切换时不能让标题左右抖
  // （设计稿 tool-progress.html 细节①）
  const STATUS = [
    '<span class="tool__status"><span class="spin"></span>' +
      '<span class="tool__time">12s</span></span>',
    '<span class="tool__status"><svg class="tool__check" viewBox="0 0 16 16">' +
      '<path d="M3.5 8.5l3 3 6.5-7.5"/></svg></span>',
    '<span class="tool__status"><svg class="tool__abort" viewBox="0 0 16 16">' +
      '<circle cx="8" cy="8" r="6.2"/><line x1="4.4" y1="4.4" x2="11.6" y2="11.6"/></svg></span>',
  ]
  for (let i = 0; i < S.tools; i++) {
    const d = document.createElement('div')
    d.className = 'tool'
    // DOM 结构照抄 ToolCallBlock.tsx：卡片头是 div[role=button]（不是 <button>，
    // 因为卡面上还有一个真的按钮），文件类工具的名字就是那个
    // <button class="tool__file">，它**不带** aria-expanded。
    // 两种卡面交替出现：长路径与长文件名都必须在 420px 里能省略
    const file = i % 2 === 0
    d.innerHTML = '<div class="tool__head" role="button" tabindex="0" aria-expanded="false">' +
      '<span class="tool__chevron">▸</span>' +
      '<span class="tool__badge">B</span>' +
      '<span class="tool__name">Bash</span>' +
      (file
        ? '<button type="button" class="tool__title tool__file">' +
          'SessionSwitchStateTestWithAVeryLongName.kt</button>'
        : '<span class="tool__title">ls -la /some/very/long/path/that/should/ellipsize/' +
          i + '</span>') +
      STATUS[i % STATUS.length] +
      '</div>'
    t.appendChild(d)
  }
  t.scrollTop = t.scrollHeight   // 真实页面也会自动滚到底

  // 工具组卡（方案甲）：组头 + 涉及文件 + 展开后的缩进体。
  // 它和 .tool 一样是 .transcript 的**直接子项** —— 当年被压成 2px 的就是这类元素，
  // 所以每一组都必须在这里出现，光有卡片量不出这个坑
  for (let i = 0; i < S.groups; i++) {
    const d = document.createElement('div')
    d.className = 'run'
    d.innerHTML = '<button type="button" class="run__head" aria-expanded="true">' +
      '<span class="tool__chevron is-open">▸</span>' +
      '<span class="run__title"><span class="run__count">6</span> 次工具调用 · 读 2 · 改 2 · 跑 2</span>' +
      '<span class="run__status">' +
      '<svg class="tool__check" viewBox="0 0 16 16"><path d="M3.5 8.5l3 3 6.5-7.5"/></svg></span>' +
      '</button>' +
      '<div class="run__files"><span class="run__files-lbl">涉及</span>' +
      '<button type="button" class="run__file">tools.ts' +
      '<span class="run__file-delta"><span class="tool__add">+18</span>' +
      '<span class="tool__del">−3</span></span></button>' +
      '<button type="button" class="run__file">ToolCallBlock.test.tsx</button>' +
      '<span class="run__files-rest">+1</span></div>' +
      '<div class="run__body"><div class="run__ind">' +
      '<div class="tool"><div class="tool__head" role="button" tabindex="0" aria-expanded="false">' +
      '<span class="tool__chevron">▸</span><span class="tool__badge">E</span>' +
      '<span class="tool__name">Edit</span>' +
      '<button type="button" class="tool__title tool__file">tools.ts</button>' +
      '<span class="tool__status"><svg class="tool__check" viewBox="0 0 16 16">' +
      '<path d="M3.5 8.5l3 3 6.5-7.5"/></svg></span></div></div>' +
      '<div class="tool"><div class="tool__head" role="button" tabindex="0" aria-expanded="false">' +
      '<span class="tool__chevron">▸</span><span class="tool__badge">B</span>' +
      '<span class="tool__name">Bash</span>' +
      '<span class="tool__title">跑 tools 单测</span>' +
      '<span class="tool__status"><svg class="tool__check" viewBox="0 0 16 16">' +
      '<path d="M3.5 8.5l3 3 6.5-7.5"/></svg></span></div></div>' +
      '</div></div>'
    t.appendChild(d)
  }

  const h = (sel) => {
    const el = document.querySelector(sel)
    return el ? el.getBoundingClientRect().height : -1
  }
  const card = t.lastElementChild
  // 卡片头必须正好占满卡片的内容宽。宽出来就说明它比卡片还宽
  // （div 的 content-box 撞上 width:100% 正是这个症状），右侧内边距会被
  // 卡片的 overflow:hidden 裁掉，状态位跟着看不见。
  //
  // :scope > 不是可选的：组卡里面还嵌着 .tool（缩进一级），不加限定
  // 会量到组内那张卡的头，量出来是个负几十的数（2026-09-14 实际踩到）。
  const overflowOf = (parent, sel) => {
    const hd = parent?.querySelector(':scope > ' + sel)
    if (!hd) return -1
    return Math.round((hd.getBoundingClientRect().width - parent.clientWidth) * 10) / 10
  }
  const headOverflow = overflowOf(t.querySelector('.tool'), '.tool__head')
  const runHeadOverflow = overflowOf(t.querySelector('.run'), '.run__head')
  // 两种标题的**计算样式**必须一致：可点的那半是个 <button>，而按钮不继承
  // 字体与颜色 —— 少写一条就会在同一个卡面上出现"另一种字体、更暗一档"
  // 的文件名，缩略图里根本看不出来
  const styleOf = (sel) => {
    const el = t.querySelector(sel)
    if (!el) return null
    const cs = getComputedStyle(el)
    return cs.fontFamily + ' | ' + cs.fontSize + ' | ' + cs.color
  }
  document.getElementById('measure').textContent = 'MEASURE ' + JSON.stringify({
    tool: h('.tool'),
    head: h('.tool__head'),
    entry: h('.entry'),
    thinking: h('.thinking-text'),
    note: h('.system-note'),
    status: h('.tool__status'),
    run: h('.run'),
    clipped: card.scrollHeight > card.clientHeight + 1,
    headOverflow: headOverflow,
    runHeadOverflow: runHeadOverflow,
    titleStyle: styleOf('.tool__title:not(.tool__file)'),
    fileStyle: styleOf('.tool__file'),
    // 能不能点不该靠悬停才发现 —— 静止态就得有下划线
    fileDecoration: (() => {
      const el = t.querySelector('.tool__file')
      return el ? getComputedStyle(el).textDecorationLine : null
    })(),
    scrollH: t.scrollHeight,
    clientH: t.clientHeight,
  })
</script>
</body></html>`
}

function runScenario(chromium, scenario) {
  const dir = mkdtempSync(join(tmpdir(), 'ccoder-layout-'))
  const page = join(dir, 'page.html')
  writeFileSync(page, buildPage(scenario))

  const url = 'file:///' + page.replace(/\\/g, '/')
  const args = [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--virtual-time-budget=2000',
    '--window-size=420,760',
    '--dump-dom',
    url,
  ]

  const dom = execFileSync(chromium, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
  const m = dom.match(/MEASURE (\{.*?\})/)
  if (!m) throw new Error(`${scenario.name}: 页面没有回报测量值（脚本没跑起来？）`)
  const measured = JSON.parse(m[1].replace(/&quot;/g, '"'))

  mkdirSync(outDir, { recursive: true })
  execFileSync(
    chromium,
    ['--headless=new', '--disable-gpu', '--no-sandbox', '--virtual-time-budget=2000',
     '--window-size=420,500', `--screenshot=${join(outDir, `layout-${scenario.name}.png`)}`, url],
    { stdio: 'ignore' },
  )

  return measured
}

const chromium = findChromium()
if (!chromium) {
  console.error('找不到 Chromium（Edge / Chrome）。这个探针**必须**用真实浏览器量布局，')
  console.error('没有它就没有任何东西在守着这类问题。请设环境变量指定路径：')
  console.error('  CCoder_CHROMIUM="C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"')
  process.exit(2)
}

console.log('布局探针 · 浏览器:', chromium)
console.log('样式表:', cssPath)
console.log()

let failed = 0
for (const scenario of SCENARIOS) {
  const m = runScenario(chromium, scenario)
  const problems = []
  if (m.tool < MIN_CARD_H) problems.push(`.tool 高度 ${m.tool} < ${MIN_CARD_H}`)
  if (m.head < MIN_HEAD_H) problems.push(`.tool__head 高度 ${m.head} < ${MIN_HEAD_H}`)
  if (m.run < MIN_CARD_H) {
    problems.push(`.run 高度 ${m.run} < ${MIN_CARD_H}（组卡被压扁 —— flex-shrink: 0 还在吗？）`)
  }
  if (m.entry < MIN_CARD_H) problems.push(`.entry 高度 ${m.entry} < ${MIN_CARD_H}`)
  if (m.clipped) problems.push('卡片内容被裁剪（scrollHeight > clientHeight）')
  if (m.headOverflow > 1) {
    problems.push(`.tool__head 比卡片宽 ${m.headOverflow}px（div 化以后漏了 box-sizing: border-box?）`)
  }
  if (m.runHeadOverflow > 1) {
    problems.push(`.run__head 比组卡宽 ${m.runHeadOverflow}px（漏了 box-sizing: border-box?）`)
  }
  if (m.fileDecoration !== 'underline') {
    problems.push(
      `可点文件名没有下划线（text-decoration-line=${m.fileDecoration}）——` +
      ' 没有它没人知道文件名能点',
    )
  }
  if (m.fileStyle && m.fileStyle !== m.titleStyle) {
    problems.push(
      `可点文件名与命令原文的计算样式不一致：${m.fileStyle} ≠ ${m.titleStyle}` +
      '（<button> 不继承字体与颜色，逐条写出来了吗？）',
    )
  }

  const status = problems.length ? '失败' : '通过'
  if (problems.length) failed++
  console.log(`[${status}] ${scenario.name} — ${scenario.note}`)
  console.log(
    `         tool=${m.tool} head=${m.head} entry=${m.entry} thinking=${m.thinking}` +
    ` note=${m.note} status=${m.status} run=${m.run} clipped=${m.clipped}` +
    ` headOverflow=${m.headOverflow}` +
    ` scrollH=${m.scrollH} clientH=${m.clientH}`,
  )
  for (const p of problems) console.log('         ✗ ' + p)
}

console.log()
console.log(`截图已写到 ${outDir}`)
process.exit(failed ? 1 : 0)
