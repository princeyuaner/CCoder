#!/usr/bin/env node
/**
 * 链接可读性的选型台 —— 一次性工具，不参与构建。
 *
 * 2026-09-17 用户截图：转写区 `Sources:` 那串链接是深蓝压深灰，"看不清"。
 * 病根是 `--accent` 取的是 `UIUtil.getTreeSelectionBackground`（选中块的**底色**），
 * 网页层却拿它当文字色 —— 深色主题下对气泡底只有 2.5:1。
 *
 * 三条保真规则（照抄 tools/thinking-palette.mjs 的做法）：
 *
 *   1. 链接**真实**的 src/styles.css —— 页面里就是 `.bubble__text` + `.md-link`
 *      那套类名，不是照着截图描的近似品；
 *   2. "修复后"那一列的色值不是手填的十六进制，而是**复刻 ThemeInjector.accentTextFor
 *      的挑色规则**算出来的（候选顺序 = 优先级，按 WCAG 对比度逐个量）——
 *      页面上写着的那个 #rrggbb，就是装上之后 Kotlin 会注入的值；
 *   3. 深色 / 浅色两套都渲染，修复前 / 修复后并排。浅色主题下 `--accent` 当文字色
 *      更糟（1.7:1）—— 摆出来的证据比在注释里写一句管用。
 *
 * 用法：
 *   node tools/link-contrast-probe.mjs --open      # 生成并用默认浏览器打开
 *   node tools/link-contrast-probe.mjs --shot      # 生成并截图（给自己看的）
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const cssPath = join(webDir, 'src', 'styles.css')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'link-contrast.html')

// ---------------------------------------------------------------- 颜色运算

const rgb = (hex) => {
  const h = hex.replace('#', '')
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}
const hex = ([r, g, b]) => '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')

/** WCAG 相对亮度 —— 与 Contrast.kt:relativeLuminance 同一套公式。 */
const luminance = ([r, g, b]) => {
  const ch = (v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
}

const contrast = (a, b) => {
  const [hi, lo] = [luminance(rgb(a)), luminance(rgb(b))].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** 兜底的两个蓝，与 Contrast.kt 的 CODE_BLUE_BRIGHT / CODE_BLUE_DEEP 同值。 */
const BRIGHT = '#7fb0ff'
const DEEP = '#1f6feb'
const AA = 4.5

/**
 * 复刻 ThemeInjector.accentTextFor：主题色排第一，够读就用它；
 * 不够往兜底的亮蓝 / 深蓝退。**不做明暗判断** —— 谁够读是量出来的。
 */
function accentTextFor(accent, surface) {
  for (const c of [accent, BRIGHT, DEEP]) {
    if (contrast(c, surface) >= AA) return c
  }
  return [accent, BRIGHT, DEEP].sort((a, b) => contrast(b, surface) - contrast(a, surface))[0]
}

// ---------------------------------------------------------------- 两套主题

const THEMES = [
  {
    name: '深色（Darcula）',
    vars: {
      '--bg': '#1e1f22', '--text': '#dcdcdc', '--text-dim': '#8c8c8c',
      '--border': '#393b40', '--surface': '#2b2d30', '--code-bg': '#191a1c',
      '--accent': '#2f65ca',
    },
  },
  {
    name: '浅色（IntelliJ Light）',
    vars: {
      '--bg': '#f7f8fa', '--text': '#000000', '--text-dim': '#808080',
      '--border': '#d1d1d1', '--surface': '#ffffff', '--code-bg': '#f2f2f2',
      '--accent': '#a8c7fa',
    },
  },
]

/** 转写区里那条消息 —— 内容照用户截图那一段（正文 + Sources 链接列表）。 */
const BODY = `
<div class="bubble bubble--assistant">
  <div class="bubble__text">
    <p>会话库与列表动作都在 <code class="inline-code">~/.claude/projects</code> 下，
    改名 / 标签 / 子代理这些动作在 Codex 侧没有对应物。</p>
    <p><strong>Sources:</strong></p>
    <ul>
      <li><a class="md-link" href="#">Codex TypeScript SDK README (openai/codex)</a></li>
      <li><a class="md-link" href="#">Codex SDK Overview (mintlify)</a></li>
      <li><a class="md-link" href="#">Codex SDK usage / thread options</a></li>
      <li><a class="md-link" href="#">第三方 provider 的 mode → approvalPolicy 映射示例</a></li>
      <li><a class="md-link" href="#">openai/codex commit 07c195f（approvalPolicy/approvalsReviewer）</a></li>
    </ul>
  </div>
</div>`

function panel(theme, state) {
  const surface = theme.vars['--surface']
  const accent = theme.vars['--accent']
  const picked = accentTextFor(accent, surface)
  // 修复前 = 旧 CSS 实际取的值（.md-link 用的就是 --accent）；修复后 = 按对比度挑的
  const value = state === 'before' ? accent : picked
  const style = Object.entries({ ...theme.vars, '--accent-text': value })
    .map(([k, v]) => `${k}: ${v};`)
    .join(' ')
  return { picked, html: `
<section class="panel" style="${style}">
  <header class="panel__cap">${theme.name} · ${state === 'before' ? '修复前（--accent）' : '修复后（--accent-text）'}
    <span class="panel__ratio">${contrast(value, surface).toFixed(2)}:1</span></header>
  ${BODY}
</section>` }
}

// ---------------------------------------------------------------- 页面

function buildPage() {
  const blocks = []
  const rows = []
  for (const theme of THEMES) {
    const before = panel(theme, 'before')
    const after = panel(theme, 'after')
    blocks.push(before.html, after.html)
    rows.push({
      theme: theme.name,
      surface: theme.vars['--surface'],
      accent: theme.vars['--accent'],
      before: contrast(theme.vars['--accent'], theme.vars['--surface']),
      after: contrast(after.picked, theme.vars['--surface']),
      picked: after.picked,
    })
  }
  return { html: `<!doctype html>
<html><head><meta charset="utf-8">
<link rel="stylesheet" href="file:///${cssPath.replace(/\\/g, '/')}">
<style>
  html, body { height: auto; overflow: visible; }
  body { padding: 14px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; max-width: 900px; }
  /* color 必须在**这一层**重新解析一次：真实注入走 :root，body 一上来就拿到了
     正确的 --text；探针把变量挂在下层的 .panel 上，body 解析时看不到它们，
     会回落到 var(--text) 的兜底 #dcdcdc —— 浅色面板的正文就成了灰的（第一版
     截图里就是这样）。这跟 2026-09-13 那类"DOM 全对、屏幕上不对"是同一类坑。
     （这段注释写在这里不能用反引号 —— 外面是模板字符串，反引号会把它截断。） */
  .panel { border: 1px solid var(--border); border-radius: 8px; padding: 10px;
           background: var(--bg); color: var(--text, #dcdcdc); }
  .panel__cap { font-size: 11px; color: var(--text-dim); margin-bottom: 8px; }
  .panel__ratio { float: right; font-family: var(--font-mono, monospace); }
</style>
</head><body><div class="grid">${blocks.join('')}</div></body></html>`, rows }
}

// ---------------------------------------------------------------- 跑

const { html, rows } = buildPage()
mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, html)

const pad = (s, n) => String(s).padEnd(n)
console.log(`${pad('主题', 22)}${pad('气泡底', 11)}${pad('修复前', 10)}${pad('修复后', 10)}挑中的色`)
for (const r of rows) {
  console.log(
    `${pad(r.theme, 20)}  ${pad(r.surface, 10)}${pad(r.before.toFixed(2) + ':1', 11)}` +
    `${pad(r.after.toFixed(2) + ':1', 11)}${r.picked}` +
    `${r.before < AA ? '   ← 修复前低于 AA' : ''}`,
  )
}
console.log(`\nWCAG 正文门槛 ${AA}:1`)

if (process.argv.includes('--open')) {
  execFileSync('cmd', ['/c', 'start', '', outFile], { stdio: 'ignore' })
} else {
  console.log(`\n页面：${outFile}（--open 打开，--shot 截图）`)
}

if (process.argv.includes('--shot')) {
  const CHROMIUM = [
    process.env.CCoder_CHROMIUM,
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
  ].filter(Boolean).find((p) => existsSync(p))
  if (!CHROMIUM) {
    console.error('找不到 Chromium（Edge / Chrome），设 CCoder_CHROMIUM 指定路径')
    process.exit(2)
  }
  const shot = join(outDir, 'link-contrast.png')
  execFileSync(
    CHROMIUM,
    ['--headless=new', '--disable-gpu', '--no-sandbox', '--virtual-time-budget=2000',
     // 高要装得下**两行**（深色 / 浅色各一行）—— 只截到第一行的话，
     // 浅色主题那个更糟的 1.72:1 就永远不在图里
     '--window-size=940,1300', `--screenshot=${shot}`, 'file:///' + outFile.replace(/\\/g, '/')],
    { stdio: 'ignore' },
  )
  console.log(`截图：${shot}`)
}
