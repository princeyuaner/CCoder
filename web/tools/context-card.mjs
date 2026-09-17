#!/usr/bin/env node
/**
 * 上下文卡的进度呈现 · 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-09-17 用户："这个进度条不好看，我想做成覆盖整上下文卡片的效果，出几个效果放
 * 浏览器给我选择" + "要有动画效果"。于是把"卡片自己就是进度"这件事的几个方向
 * 并排摆出来，由用户在浏览器里挑一个，再动 Kotlin。
 *
 * 三条保真规则（照抄 tools/thinking-palette.mjs 的做法）：
 *
 *   1. 色值**不是手填的十六进制**，而是 `StatusCardView` 里那些 `JBColor` 回退值
 *      （accent `#3574f0`/`#548af7`、warn、danger、track、面板底色），并用
 *      **复刻 ThemeInjector.mix()** 现算混合色 —— 页面上写着的那个 #rrggbb，
 *      就是选了该方案后 Kotlin 侧会算出来的值；
 *   2. 几何照真卡抄：一行 404px、四张等宽（间距 5）、圆角 16、内边距 5/8/6、
 *      指示器槽 5px（比例条本体 2px）；
 *   3. 深色 / 浅色两套都渲染，并给出文字压在填充上的**对比度**（< 4.5:1 标红）——
 *      "进度铺满卡片"最大的风险就是字被底色吃掉，摆出来比在注释里写一句管用。
 *
 * 用法：
 *   node tools/context-card.mjs --open        # 生成并用默认浏览器打开
 *   node tools/context-card.mjs --shot        # 生成并截图（深色）
 *   node tools/context-card.mjs --shot=light  # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'context-card.html')

// ---------------------------------------------------------------- 颜色运算

const rgb = (hex) => {
  const h = hex.replace('#', '')
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}
const hex = ([r, g, b]) =>
  '#' + [r, g, b].map((c) => Math.round(c).toString(16).padStart(2, '0')).join('')

/** 复刻 ThemeInjector.mix(base, fg, ratio)：把 fg 按比例混进 base。 */
const mix = (base, fg, ratio) => {
  const b = rgb(base)
  const f = rgb(fg)
  return hex(b.map((c, i) => c + (f[i] - c) * ratio))
}

/** WCAG 相对亮度 / 对比度。 */
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

// 两套主题：面板底 / 卡片底 / 文字 取自 New UI 的常色；
// accent·warn·danger·track 就是 StatusCardView 里那几个 JBColor 的回退值
const THEMES = {
  dark: {
    bg: '#1e1f22', card: '#2b2d30', text: '#dfe1e5', dim: '#9da0a8', border: '#393b40',
    accent: '#548af7', warn: '#ff8a65', danger: '#db5c5c', ok: '#5fad65', track: '#33363b',
  },
  light: {
    bg: '#f7f8fa', card: '#ffffff', text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', warn: '#d84315', danger: '#c0392b', ok: '#3d8b43', track: '#e0e2e7',
  },
}

const P = 0.34 // 样板用量：34%（用户截图里那个数）

// ---------------------------------------------------------------- 方案表
//
// 每个方案给：填充怎么算（按主题）、文字对比度怎么量。动画一律写在 CSS 里。

const OPTIONS = [
  {
    id: 'A',
    name: '整卡横向水位',
    tag: '最接近你说的那个',
    why: '卡片底色自己就是进度：从左往右铺到 34%。铺到哪、读到哪，一眼就知道还剩多少空。',
    tint: 0.24,
    textOn: 'fill',
    labelOnFill: true,   // 染色之后标签得跟着换成正文色（见页脚那条说明）
    anim: '首次 0→34% 涨上来（700ms），之后每 3.2s 一道很淡的斜光扫过',
  },
  {
    id: 'A2',
    name: '整卡横向水位（浓）',
    tag: '效果最明显',
    why: '同上，但填充浓到"半张卡是蓝的"。远看就知道用量，代价是卡片本身变重、四张里它最抢眼。',
    tint: 0.45,
    textOn: 'fill',
    labelOnFill: true,
    anim: '同 A（涨 + 斜光）',
  },
  {
    id: 'B',
    name: '整卡纵向水位',
    tag: '水箱',
    why: '从卡片底部涨上来，像电池/水杯。四张并排时"谁快满了"变成比高度，比横向更容易一眼扫出来。',
    tint: 0.16,
    textOn: 'fill',
    labelOnFill: true,
    anim: '涨水位 + 水面一层缓慢起伏的波纹（2.4s 循环）',
  },
  {
    id: 'C',
    name: '右下角扇形扫过',
    tag: '仪表',
    why: '从右下角扫出扇形，越满扫得越开。几何上最像仪表盘，但读数要瞄角度；好处是上半张卡完全没被碰。',
    tint: 0.30,
    textOn: 'card',
    anim: '从 0 扫到 34% + 静止后极慢地呼吸（3.6s）',
  },
  {
    id: 'D',
    name: '边框即进度条',
    tag: '最克制',
    why: '卡片里面一个像素不动，进度沿**边框**跑一圈。文字永远压在卡底上（对比度零风险），最不打扰阅读。',
    tint: 0.9,
    textOn: 'card',
    anim: '沿周长跑到 34% + 一道短光沿着边走（流光）',
  },
  {
    id: 'E',
    name: '按阈值整卡染色',
    tag: '不说话的那种',
    why: '不画进度，只让整张卡随用量变色（蓝 → 琥珀 → 红）。信息密度最低，但"要不要压缩"一眼就有答案。',
    tint: 0.18,
    textOn: 'fill',
    labelOnFill: true,
    anim: '常驻极缓呼吸；进 danger 档变成 1.6s 一次的脉冲（那是"该动手了"）',
  },
  {
    id: 'F',
    name: '现状（2px 条）',
    tag: '对照',
    why: '现在装着的就是这个。摆进来当基准 —— 你可以直接说"就它"，那我一个字都不用改。',
    tint: 0,
    textOn: 'card',
    anim: '无（现状本来就没有动画）',
  },
]

/** 深色 / 浅色各一套：把该方案在某个主题下要用的色算出来。 */
function colorsOf(opt, t) {
  const ctx = THEMES[t]
  const fill = mix(ctx.card, ctx.accent, opt.tint)
  const warnFill = mix(ctx.card, ctx.warn, opt.tint)
  const dangerFill = mix(ctx.card, ctx.danger, opt.tint)
  // 卡片被染色之后，灰标签在浅色主题下必然掉到 3.5–3.9:1 —— 所以染色方案
  // 一律把标签换成正文色（labelOnFill），这里量的就是**真会显示的那个颜色**
  const label = opt.labelOnFill ? ctx.text : ctx.dim
  const bg = opt.textOn === 'fill' ? fill : ctx.card
  return {
    ...ctx, fill, warnFill, dangerFill, label,
    onFill: contrast(ctx.text, bg),
    labelOn: contrast(label, bg),
  }
}

// ---------------------------------------------------------------- 卡片骨架

const ICON = `<svg class="ico" viewBox="0 0 12 12" aria-hidden="true">
  <circle cx="6" cy="6" r="4.6" fill="none" stroke="currentColor" stroke-width="1.2"/>
  <path d="M6 6 L6 1.4 A4.6 4.6 0 0 1 10.6 6 Z" fill="currentColor" opacity=".85"/>
</svg>`

const ACTION = `<svg class="act" viewBox="0 0 12 12" aria-hidden="true">
  <path d="M6 1.6 V8.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" fill="none"/>
  <path d="M3.4 5.8 L6 8.4 L8.6 5.8" stroke="currentColor" stroke-width="1.3"
        stroke-linecap="round" stroke-linejoin="round" fill="none"/>
  <path d="M2.6 10.2 H9.4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" fill="none"/>
</svg>`

/** 一张卡。`opt` 为 null 时画的是另外三张（只做背景板，不参与方案）。 */
function card(opt, { label = '上下文', value = '34%', tone = 'idle', compacting = false } = {}) {
  const cls = ['card']
  if (compacting) cls.push('is-compacting')
  if (tone === 'warn') cls.push('is-warn')
  if (tone === 'danger') cls.push('is-danger')
  const attr = opt ? ` data-opt="${opt.id}"` : ''
  const meter = opt === null || opt.id === 'F' ? '<i class="meter"><i></i></i>' : ''
  // 描边进度：mask 造的一圈渐变边（跟着卡片的圆角走），不引 SVG
  const ring = opt && opt.id === 'D' ? '<i class="ringbox"></i><i class="spark"></i>' : ''
  return `<div class="${cls.join(' ')}"${attr}>
    <i class="layer"></i>${ring}
    <div class="body">
      <div class="row1">${ICON}<span class="lbl">${label}</span>${ACTION}</div>
      <div class="val">${value}</div>
      <div class="slot">${meter}</div>
    </div>
  </div>`
}

/** 一排四张：方案卡 + 三张陪衬（真机上它们就长这样）。 */
function row(opt) {
  return `<div class="row">
    ${card(opt)}
    ${card(null, { label: '连接', value: '已连接' })}
    ${card(null, { label: '任务列表', value: '1/3' })}
    ${card(null, { label: '子代理', value: '2' })}
  </div>`
}

// ---------------------------------------------------------------- 页面

function optionCss(opt) {
  const out = []
  for (const t of ['dark', 'light']) {
    const c = colorsOf(opt, t)
    out.push(
      `html[data-theme="${t}"] .card[data-opt="${opt.id}"]{` +
        `--fill:${c.fill};--warn-fill:${c.warnFill};--danger-fill:${c.dangerFill};` +
        `--accent:${c.accent};--warn:${c.warn};--danger:${c.danger};` +
        `--card:${c.card};--border:${c.border};--text:${c.text};--dim:${c.label};--track:${c.track}}`,
    )
  }
  return out.join('\n')
}

function chip(fg, bg, label) {
  const c = contrast(fg, bg)
  const bad = c < 4.5
  return `<span class="num${bad ? ' num--bad' : ''}"><i style="background:${fg}"></i>${label}
    <code>${fg}</code> ${c.toFixed(1)}:1${bad ? ' ⚠' : ''}</span>`
}

function optionCard(opt, n) {
  const d = colorsOf(opt, 'dark')
  const l = colorsOf(opt, 'light')
  const bgOf = (c) => (opt.textOn === 'fill' ? c.fill : c.card)
  const nums =
    chip(d.text, bgOf(d), '深·数字') +
    chip(d.label, bgOf(d), opt.labelOnFill ? '深·标签（已换色）' : '深·标签（灰）') +
    chip(l.text, bgOf(l), '浅·数字') +
    chip(l.label, bgOf(l), opt.labelOnFill ? '浅·标签（已换色）' : '浅·标签（灰）')
  return `<article class="opt" data-opt="${opt.id}" tabindex="0">
    <header class="opt__head">
      <span class="kbd">${n}</span>
      <h2>${opt.name}</h2><span class="tag">${opt.tag}</span>
    </header>
    <p class="why">${opt.why}</p>
    <p class="anim">动画：${opt.anim}</p>
    <div class="samples">
      ${opt.id === 'E'
        ? `<div class="sample sample--big sample--tone">
             ${card(opt)}
             ${card(opt, { value: '76%', tone: 'warn' })}
             ${card(opt, { value: '93%', tone: 'danger' })}
           </div>`
        : `<div class="sample sample--big">${card(opt)}</div>`}
      <div class="sample sample--row">
        <p class="cap">一排四张（真实尺寸，404px 宽）</p>
        ${row(opt)}
      </div>
    </div>
    <div class="nums">${nums}</div>
    <button type="button" class="pick">选这个</button>
  </article>`
}

const page = `<!doctype html>
<html lang="zh" data-theme="dark">
<head>
<meta charset="utf-8">
<title>上下文卡 · 进度呈现选型</title>
<style>
  @property --p { syntax: '<number>'; inherits: false; initial-value: 0 }
  @property --q { syntax: '<number>'; inherits: false; initial-value: 0 }
  @property --r { syntax: '<number>'; inherits: false; initial-value: 0 }

  * { box-sizing: border-box }
  html { --chrome: #101215 }
  html[data-theme="light"] { --chrome: #e9ebee }
  body {
    margin: 0; padding: 28px 28px 120px; background: var(--chrome);
    color: #dcdcdc; font: 13px/1.6 "Segoe UI", "Microsoft YaHei", sans-serif;
  }
  html[data-theme="light"] body { color: #202124 }
  h1 { font-size: 19px; margin: 0 0 6px }
  .lead { max-width: 940px; margin: 0 0 4px; opacity: .85 }
  .lead code { background: rgba(127,127,127,.18); padding: 0 3px; border-radius: 3px }
  .bar { position: sticky; top: 0; z-index: 9; display: flex; gap: 10px; align-items: center;
         padding: 10px 0 12px; background: var(--chrome) }
  .bar button { font: inherit; padding: 4px 12px; border-radius: 6px;
    border: 1px solid rgba(127,127,127,.45); background: transparent; color: inherit; cursor: pointer }
  .bar button.on { border-color: #548af7; color: #548af7 }
  .bar label { display: flex; gap: 6px; align-items: center; cursor: pointer }

  /* ---- 状态卡：几何照真卡抄 ---- */
  .card {
    position: relative; width: 100%; height: 56px; overflow: hidden;
    border: 1px solid var(--border); border-radius: 16px; background: var(--card);
    color: var(--text); font-size: 12px; text-align: center;
  }
  .card .body { position: relative; z-index: 2; padding: 5px 8px 6px; height: 100%;
                display: flex; flex-direction: column }
  .row1 { display: flex; align-items: center; justify-content: center; gap: 4px;
          color: var(--dim); height: 16px; position: relative }
  .ico { width: 12px; height: 12px; flex: none }
  .act { width: 12px; height: 12px; position: absolute; right: 0; top: 1px; color: var(--accent) }
  .val { flex: 1; display: flex; align-items: center; justify-content: center;
         font-size: 15px; font-weight: 600 }
  .slot { height: 5px; display: flex; align-items: flex-end }
  .layer { position: absolute; inset: 0; z-index: 1; pointer-events: none }
  .row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 5px; width: 404px }

  /* ---- A 整卡横向水位 ---- */
  [data-opt="A"] .layer {
    background: linear-gradient(to right, var(--fill) 0 var(--p), transparent var(--p));
    animation: fill-x .7s cubic-bezier(.22,.61,.36,1) both, sheen 3.2s linear infinite 1s;
  }
  @keyframes fill-x { from { --p: 0 } to { --p: 34% } }
  @keyframes sheen { 0% { filter: none } 55% { filter: brightness(1.18) } 100% { filter: none } }

  /* ---- B 整卡纵向水位 ---- */
  [data-opt="B"] .layer {
    background: linear-gradient(to top, var(--fill), color-mix(in oklab, var(--fill) 78%, var(--card)));
    height: var(--p); top: auto; bottom: 0;
    animation: fill-y .9s cubic-bezier(.22,.61,.36,1) both;
  }
  [data-opt="B"] .layer::before {
    content: ''; position: absolute; left: 0; right: 0; top: -3px; height: 6px;
    background: radial-gradient(circle at 6px 3px, var(--fill) 3px, transparent 3.4px) 0 0/12px 6px repeat-x;
    animation: wave 2.4s linear infinite;
  }
  @keyframes fill-y { from { height: 0 } to { height: 34% } }
  @keyframes wave { to { background-position-x: 12px } }

  /* ---- C 右下角扇形 ---- */
  [data-opt="C"] .layer {
    background: conic-gradient(from -90deg at 100% 100%,
      var(--fill) 0 calc(var(--p) * 1turn), transparent calc(var(--p) * 1turn));
    animation: fill-c 1s cubic-bezier(.22,.61,.36,1) both, breath 3.6s ease-in-out infinite 1s;
  }
  @keyframes fill-c { from { --p: 0 } to { --p: .34 } }
  @keyframes breath { 50% { opacity: .82 } }

  /* ---- D 边框即进度 ---- */
  [data-opt="D"] .ringbox, [data-opt="D"] .spark {
    position: absolute; inset: 0; z-index: 1; border-radius: 16px; padding: 1px;
    -webkit-mask-image: linear-gradient(#000 0 0), linear-gradient(#000 0 0);
    -webkit-mask-clip: content-box, border-box;
    -webkit-mask-composite: xor;
    mask-image: linear-gradient(#000 0 0), linear-gradient(#000 0 0);
    mask-clip: content-box, border-box;
    mask-composite: exclude;
    pointer-events: none;
  }
  [data-opt="D"] .ringbox {
    background: conic-gradient(from -90deg,
      var(--fill) 0 calc(var(--q) * 1turn), var(--track) calc(var(--q) * 1turn) 1turn);
    animation: fill-d 1s cubic-bezier(.22,.61,.36,1) both;
  }
  [data-opt="D"] .spark {
    background: conic-gradient(from calc(var(--r) * 1turn),
      transparent 0 .80turn, var(--accent) .90turn, transparent .97turn);
    opacity: .85; animation: spin 3.4s linear infinite 1s;
  }
  @keyframes fill-d { from { --q: 0 } to { --q: .34 } }
  @keyframes spin { from { --r: 0 } to { --r: 1 } }

  /* ---- E 按阈值整卡染色 ---- */
  [data-opt="E"] .layer { animation: breath 2.8s ease-in-out infinite }
  [data-opt="E"] .layer { background: var(--fill) }
  [data-opt="E"].is-warn .layer { background: var(--warn-fill) }
  [data-opt="E"].is-danger .layer { background: var(--danger-fill); animation: pulse 1.6s ease-in-out infinite }
  @keyframes pulse { 50% { opacity: .62 } }

  /* ---- F 现状：2px 条 ---- */
  .meter { display: block; width: 100%; height: 2px; border-radius: 2px; background: var(--track);
           position: relative; overflow: hidden }
  .meter > i { position: absolute; inset: 0 auto 0 0; width: 34%; border-radius: 2px;
               background: var(--accent) }

  /* ---- 压缩中：不确定态（勾上之后所有样板一起切） ---- */
  .is-compacting .val { color: var(--warn) }
  .is-compacting .layer,
  .is-compacting .meter > i { background: var(--warn-fill, var(--warn)) }
  .is-compacting .layer { animation: marquee 1.8s linear infinite !important; opacity: .9 }
  .is-compacting[data-opt="D"] .ringbox { background: none !important }
  .is-compacting[data-opt="D"] .spark {
    background: conic-gradient(from calc(var(--r) * 1turn),
      transparent 0 .72turn, var(--warn) .86turn, transparent .96turn);
    animation: spin 1.3s linear infinite !important; opacity: .95
  }
  @keyframes marquee {
    from { background-position: -140px 0 } to { background-position: 140px 0 }
  }
  .is-compacting .layer {
    background-image: linear-gradient(100deg, transparent 35%,
      color-mix(in oklab, var(--warn) 42%, transparent) 50%, transparent 65%);
    background-size: 140px 100%; background-repeat: no-repeat;
  }

  /* ---- 版式 ---- */
  .opt { margin: 0 0 22px; padding: 16px 18px 14px; border-radius: 12px;
         background: rgba(127,127,127,.08); border: 1px solid rgba(127,127,127,.22);
         max-width: 980px }
  .opt__head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px }
  .opt__head h2 { font-size: 15px; margin: 0 }
  .kbd { display: inline-flex; width: 20px; height: 20px; align-items: center; justify-content: center;
         border-radius: 5px; background: rgba(127,127,127,.25); font-size: 12px }
  .tag { font-size: 11px; padding: 1px 7px; border-radius: 20px;
         background: rgba(84,138,247,.18); color: #548af7 }
  .why { margin: 2px 0 2px; max-width: 760px }
  .anim { margin: 0 0 12px; opacity: .72; font-size: 12px }
  .samples { display: flex; gap: 26px; align-items: flex-start; flex-wrap: wrap }
  .sample--big { zoom: 1.9 }
  .sample--tone { display: flex; gap: 8px }
  .sample--row { flex: 1 }
  .cap { margin: 0 0 6px; font-size: 11px; opacity: .6 }
  .nums { display: flex; gap: 14px; flex-wrap: wrap; margin: 12px 0 4px; font-size: 11px; opacity: .9 }
  .num { display: inline-flex; align-items: center; gap: 5px }
  .num i { width: 9px; height: 9px; border-radius: 2px; display: inline-block }
  .num--bad { color: #ff6b6b }
  .num code { opacity: .7 }
  .pick { font: inherit; margin-top: 8px; padding: 5px 14px; border-radius: 7px; cursor: pointer;
    border: 1px solid rgba(127,127,127,.45); background: transparent; color: inherit }
  .opt.picked .pick { border-color: #548af7; color: #548af7 }
  #out { width: 100%; max-width: 980px; height: 58px; margin-top: 8px; font: 12px/1.5 ui-monospace, monospace;
         background: rgba(127,127,127,.12); color: inherit; border: 1px solid rgba(127,127,127,.3);
         border-radius: 8px; padding: 8px }
  #copy { font: inherit; padding: 5px 14px; border-radius: 7px; cursor: pointer;
          border: 1px solid rgba(127,127,127,.45); background: transparent; color: inherit }
  .foot { max-width: 980px; margin-top: 26px; font-size: 12px; opacity: .75 }
${OPTIONS.map(optionCss).join('\n')}
</style>
</head>
<body>
<h1>上下文卡：进度不再是"一条 2px 的条"</h1>
<p class="lead">用户原话：<b>"这个进度条不好看，我想做成覆盖整上下文卡片的效果"</b> ——
下面每个方案都是"卡片自己就是进度"的一种做法，都带动画。色值取的是
<code>StatusCardView</code> 里那几个 <code>JBColor</code> 回退值 + 复刻
<code>ThemeInjector.mix()</code> 现算的混合色（不是手填的十六进制）；几何照真卡：
一行 404px、四张等宽、圆角 16、内边距 5/8/6、指示器槽 5px。</p>
<div class="bar">
  <button data-theme="dark" class="on">深色</button>
  <button data-theme="light">浅色</button>
  <label><input type="checkbox" id="compact"> 看"压缩中"的样子（不确定态）</label>
  <span style="opacity:.6">按 1–7 或点"选这个"</span>
</div>
${OPTIONS.map((o, i) => optionCard(o, i + 1)).join('\n')}
<div class="foot">
  <p><b>选好之后我这边要做的</b>：Swing 里加一条 <code>javax.swing.Timer</code> 驱动卡片的
  重绘（进度条换成整卡填充/描边），再补一张渲染探针图与几条量几何的用例；
  "压缩中"那一档换成不确定动画。<b>动画要克制</b>：IDE 里常驻跳动的元素很耗注意力，
  所以只有"正在涨"与"该压缩了"这两件事会动。</p>
  <p>对比度说明：按 4.5:1 标尺，低于它的标红。**染色方案有个真实代价**：卡片一被染色，
  现在那个灰标签（<code>InactiveTextColor</code>）在浅色主题下必然掉到 3.5–3.9:1 ——
  所以页面上 A / A2 / B / E 的标签已经是**换成正文色之后**的样子，选它们等于连带改了标签颜色。
  C 与 D 不碰卡内区域，标签保持灰的。</p>
  <button id="copy">复制选择</button>
  <textarea id="out" readonly></textarea>
</div>
<script>
  const cards = [...document.querySelectorAll('.opt')]
  const out = document.getElementById('out')
  let picked = null
  function refresh() {
    out.value = picked
      ? picked.getAttribute('data-opt') + ' · ' + picked.querySelector('h2').textContent +
        '（' + picked.querySelector('.tag').textContent + '）'
      : '还没选。'
  }
  function pick(i) {
    picked = cards[i]
    cards.forEach((c) => c.classList.toggle('picked', c === picked))
    refresh()
  }
  cards.forEach((c, i) => c.querySelector('.pick').addEventListener('click', (e) => {
    e.stopPropagation(); pick(i)
  }))
  document.addEventListener('keydown', (e) => {
    const n = parseInt(e.key, 10)
    if (n >= 1 && n <= cards.length) pick(n - 1)
  })
  document.querySelectorAll('.bar button').forEach((b) => b.addEventListener('click', () => {
    document.documentElement.dataset.theme = b.dataset.theme
    document.querySelectorAll('.bar button').forEach((x) => x.classList.toggle('on', x === b))
  }))
  document.getElementById('compact').addEventListener('change', (e) => {
    document.querySelectorAll('.card[data-opt]').forEach((c) => c.classList.toggle('is-compacting', e.target.checked))
    document.querySelectorAll('.card[data-opt] .val').forEach((v) => {
      v.dataset.text = v.dataset.text || v.textContent
      v.textContent = e.target.checked ? '压缩中…' : v.dataset.text
    })
  })
  document.getElementById('copy').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(out.value) }
    catch { out.select(); document.execCommand('copy') }
    const b = document.getElementById('copy'); b.textContent = '已复制'
    setTimeout(() => (b.textContent = '复制选择'), 1200)
  })
  if (location.hash === '#light') {
    document.documentElement.dataset.theme = 'light'
    document.querySelectorAll('.bar button').forEach((x) => x.classList.toggle('on', x.dataset.theme === 'light'))
  }
  refresh()
</script>
</body>
</html>`

mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, page)

console.log('上下文卡选型台 →', outFile)
for (const o of OPTIONS) {
  const d = colorsOf(o, 'dark')
  const l = colorsOf(o, 'light')
  console.log(
    `  ${o.id.padEnd(2, ' ')} ${o.name.padEnd(8, '　')} ` +
      `深 填充 ${d.fill} 数字 ${d.onFill.toFixed(1)}:1 标签 ${d.labelOn.toFixed(1)}:1` +
      `   浅 填充 ${l.fill} 数字 ${l.onFill.toFixed(1)}:1 标签 ${l.labelOn.toFixed(1)}:1`,
  )
}

// ---------------------------------------------------------------- 打开 / 截图

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
    // 浅色那张另存一份**把 data-theme 写死在标记里**的 html：
    // 靠 `#light` 让脚本改，实测 headless 截图那条路上没生效（截出来还是深色）
    let target = outFile
    let name = 'context-card-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'context-card-light.html')
      writeFileSync(target, page.replace('<html lang="zh" data-theme="dark">', '<html lang="zh" data-theme="light">'))
      name = 'context-card-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1180,2600',
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
