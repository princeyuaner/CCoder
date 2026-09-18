#!/usr/bin/env node
/**
 * 表格观感的选型台 —— 一次性工具，不参与构建。
 *
 * 2026-09-18 用户截图：转写区里那张 4 列表格"怎么这么难看"。
 * 原始 markdown 是游戏工程那次会话里的一条真实回复（见下 MD），
 * 每个单元格里都塞着行内代码，第 4 列还是整段中文散文。
 *
 * 三条保真规则（照抄 tools/link-contrast-probe.mjs / thinking-palette.mjs）：
 *
 *   1. 样式表是**真实**的 src/styles.css —— 页面里就是
 *      `.bubble__text` > `.md-table` > table 那套类名，不是照着截图描的近似品；
 *   2. 正文是**真实**的 markdown，走**真实的 marked**（web/node_modules 里的
 *      那一份，也就是 Markdown.tsx 用的那个版本）转成 DOM，
 *      包括 `inline-code` 这个类名 —— 它是 Markdown.tsx 手工挂上去的，
 *      marked 自己不产它，postProcess 里补；
 *   3. 外层 DOM 照抄 AssistantBubble：`.entry > .row.row--assistant >
 *      .bubble.bubble--assistant > .bubble__text`。少一层，量出来的宽度就不是
 *      真实可用宽 —— 而宽度正是这张表难看的**主因**。
 *
 * ⚠ `.bubble__text` 带着 `white-space: pre-wrap`（styles.css:190），
 *   它会**继承进每个单元格**。这条在截图里看不出来、在 CSS 里极易漏，
 *   是本探针要钉住的第一号事实。
 *
 * 用法：
 *   node tools/table-style-probe.mjs            # 生成 HTML + 截图
 *   node tools/table-style-probe.mjs --open     # 生成并用默认浏览器打开
 *   node tools/table-style-probe.mjs --variant=fix
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const cssPath = join(webDir, 'src', 'styles.css')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'table-style.html')

// ------------------------------------------------------------------ 真实正文

/** 真实 markdown：游戏工程会话 8031d74a 里那条回复（A 档那张表）。 */
const MD = `
## A 档：现在基本测不到的路径

| # | 指令 | 落点 | 为什么需要 |
|---|---|---|---|
| 17 | \`种植指定作物 <landSID> <cropSID>\` | team \`cropcom.PlantCrop(player, land, cropSID)\`（走占用兜底+广播，免扣 \`moneyCost\`、不看 \`unlockLevel\`） | 图鉴条目是按 \`FarmCropData.itemSid\` 对齐的，作物 3/4 号还要解锁等级+货币；手点种满一遍很慢。配合 15/16 就能把「任意作物 × 阶段 × 变异」矩阵跑完 |
| 18 | \`清空指定田地 <landSID>\` | team \`scene.RemoveCrop\` + \`crop.Destroy()\` + 广播 | 现在只有 4（取消全部解锁）/6（整场重置），单田清理没有 |
| 19 | \`设置接受祝福次数 <landSID> <count>\` | team 写 \`crop.blessTotalCount\` | 这个字段同时是①「农作物祝福上限」拒绝分支（\`blessTotalLimit\`）②变异概率加成（\`baseMutationRate + count × blessMutationBonus\`）的输入，设成上限值可稳定复现拒绝分支 |
| 20 | \`清空田地社交记录 <landSID>\` | team 清 \`blessDayInfo\` / \`stolenTimes\` / \`stolenAmount\` / \`stolenPlayers\` | 祝福偷取必须开第二个账号，而「同一株每人只能拿一次」「每日祝福 10 次」——不清档的话回归脚本跑第二遍必挂，跟 6 号指令同一个动机 |
| 21 | \`塞入待播报变异 <landSID> <mutationID[,..]>\` | team \`MergePendingMutation(scene.pendingMutations, ...)\`（复用"同大类只留最高倍率"规则）+ \`_PushFarmDesc\` | \`pendingMutations\` 只在**主人不在农场**时写（\`AddPendingMutation\` 里 \`IsPlayerInScene\` 直接 return），人工只能"离开→等变异→再进"，这条离线播报基本漏测 |
| 22 | \`设置图鉴收集数量 <collectionSID> <count>\` | player 走 \`GetOrCreateCollectionItem\` 写 \`count\` | count 只在收获时按果实数量累加，而 \`FarmCropCollectionData.amount\` 往往要收几十上百个；有了它才能测得动「图鉴达成→领积分→领档位奖励」（就是上轮那套奖励） |
| 23 | \`清空单条图鉴 <collectionSID>\` | player 单条归零（1 是全清） | 重复测"首次收集"分支 |
`

// ------------------------------------------------------------------ 转 DOM

const { marked } = await import(
  'file:///' + join(webDir, 'node_modules', 'marked', 'lib', 'marked.esm.js').replace(/\\/g, '/')
)

/**
 * marked 的输出与 Markdown.tsx 的产物对齐。
 *
 * 组件不是"渲染 HTML 字符串"，而是把 token 拼成 React 元素 —— 两者的 DOM
 * 结构一致，**只有两处类名**是组件手工挂的、marked 不产：
 *   codespan → `class="inline-code"`（Markdown.tsx:234）
 *   link     → `class="md-link"`    （Markdown.tsx:254）
 * 这里用字符串替换补上，而不是重写 renderer —— 免去 marked 版本间
 * renderer 签名（老版位置参数 / 新版 token 对象）的坑。
 */
function toDom(md) {
  return marked
    .parse(md)
    .replace(/<code>/g, '<code class="inline-code">')
    .replace(/<a href=/g, '<a class="md-link" href=')
}

// ------------------------------------------------------------------ 两套主题

const THEMES = [
  {
    name: '深色（Darcula）',
    vars: {
      '--bg': '#1e1f22', '--text': '#dcdcdc', '--text-dim': '#8c8c8c',
      '--border': '#393b40', '--surface': '#2b2d30', '--code-bg': '#191a1c',
      '--accent': '#2f65ca', '--accent-text': '#7fb0ff',
    },
  },
  {
    name: '浅色（IntelliJ Light）',
    vars: {
      '--bg': '#f7f8fa', '--text': '#000000', '--text-dim': '#808080',
      '--border': '#d1d1d1', '--surface': '#ffffff', '--code-bg': '#f2f2f2',
      '--accent': '#a8c7fa', '--accent-text': '#1f6feb',
    },
  },
]

/**
 * 候选修法。`current` 就是今天的 styles.css（一个字都不加）。
 * 其余每条都是**一句话能说清的独立假设** —— 选型台的意义是能一眼看出
 * 是哪一条在起作用，而不是一坨改了十几处的补丁叠上去。
 */
const VARIANTS = {
  current: { note: '今天的样式（什么都不加）', css: '' },

  // 三个**独立**的假设，各自单跑一次，才看得出是哪一条在起作用。
  // 一次性全叠上去的话，某一条若其实是无效的，会被另外两条的效果盖住。
  ws: {
    note: '① 单元格 white-space: normal（掐掉 pre-wrap 继承）',
    css: `.bubble__text th, .bubble__text td { white-space: normal; }`,
  },

  valign: {
    note: '② 单元格 vertical-align: top',
    css: `.bubble__text th, .bubble__text td { vertical-align: top; }`,
  },

  wrap: {
    note: '③ 单元格 overflow-wrap: break-word（放回列宽下限）',
    css: `.bubble__text th, .bubble__text td { overflow-wrap: break-word; }`,
  },

  // 上面三条如果都成立，合起来就是这一版
  fix: {
    note: '①②③ 合并 + 内边距/行高',
    css: `
      .bubble__text th, .bubble__text td {
        white-space: normal;          /* ① pre-wrap 是给正文的，不是给表格的 */
        overflow-wrap: break-word;    /* ③ anywhere 让列宽下限掉到 1 个字 */
        vertical-align: top;          /* ② 数字别再飘在行中间 */
        padding: 5px 10px;
        line-height: 1.5;             /* 中文没有 1.5 会挤成一条 */
      }
      .bubble__text th { background: var(--code-bg, #191a1c); }
    `,
  },

  // 表头单独试：近黑底在深色主题下是一道黑杠
  head: {
    note: '表头改透明 + 下边框',
    css: `.bubble__text th { background: transparent; border-bottom: 2px solid var(--border, #393b40); }`,
  },

  // fix 之上只换表头 —— 与 fix 并排看，判断到底哪个好
  fix2: {
    note: '①②③ + 表头改透明 + 下边框',
    css: `
      .bubble__text th, .bubble__text td {
        white-space: normal;
        overflow-wrap: break-word;
        vertical-align: top;
        padding: 5px 10px;
        line-height: 1.5;
      }
      .bubble__text th {
        background: transparent;
        border-bottom: 2px solid var(--border, #393b40);
      }
    `,
  },
}

// ------------------------------------------------------------------ 页面

/** 一个面板 = 一套主题。候选的覆盖样式是**全局**的（探针一次只跑一个候选），
 *  直接整页生效 —— 不做作用域改写，免得引入探针自己的 bug。 */
function panel(theme, variant) {
  const style = Object.entries(theme.vars).map(([k, v]) => `${k}: ${v};`).join(' ')
  return `
<section class="panel" style="${style}">
  <header class="panel__cap">${theme.name} · ${variant.note}</header>
  <div class="entry">
    <div class="row row--assistant">
      <div class="bubble bubble--assistant">
        <div class="bubble__text">${toDom(MD)}</div>
      </div>
    </div>
  </div>
</section>`
}

function buildPage(variantKey) {
  const variant = VARIANTS[variantKey]
  if (!variant) throw new Error(`未知候选：${variantKey}（可选：${Object.keys(VARIANTS).join(', ')}）`)
  const blocks = THEMES.map((theme) => panel(theme, variant))
  return `<!doctype html>
<html><head><meta charset="utf-8">
<link rel="stylesheet" href="file:///${cssPath.replace(/\\/g, '/')}">
<style>
  html, body { height: auto; overflow: visible; }
  body { padding: 14px; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
  /* 单列铺开：并排两列会让每栏只剩 420px，而 420px 下这张表本来就必然横向滚动，
     量不出"格子挤不挤"。要横着比就 --open 之后自己拉窗口 */
  .stack { display: grid; grid-template-columns: 1fr; gap: 14px; max-width: 860px; }
  .panel { border: 1px solid var(--border); border-radius: 8px; padding: 10px;
           background: var(--bg); color: var(--text, #dcdcdc); }
  .panel__cap { font-size: 11px; color: var(--text-dim); margin-bottom: 8px;
                font-family: ui-monospace, monospace; }
</style>
${variant.css ? `<style>\n${variant.css}\n</style>` : ''}
</head><body><div class="stack">${blocks.join('')}</div></body></html>`
}

// ------------------------------------------------------------------ 跑

const variantKey = (process.argv.find((a) => a.startsWith('--variant=')) ?? '--variant=current')
  .split('=')[1]

mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, buildPage(variantKey))
console.log(`候选：${variantKey} — ${VARIANTS[variantKey].note}`)
console.log(`页面：${outFile}`)

if (process.argv.includes('--open')) {
  execFileSync('cmd', ['/c', 'start', '', outFile], { stdio: 'ignore' })
}

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

const shot = join(outDir, `table-style-${variantKey}.png`)
execFileSync(
  CHROMIUM,
  ['--headless=new', '--disable-gpu', '--no-sandbox', '--virtual-time-budget=2000',
   '--window-size=900,2400', `--screenshot=${shot}`, 'file:///' + outFile.replace(/\\/g, '/')],
  { stdio: 'ignore' },
)
console.log(`截图：${shot}`)
