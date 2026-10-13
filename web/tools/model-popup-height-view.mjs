#!/usr/bin/env node
/**
 * 模型弹层「太高了」· 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-13 用户（截图）："这个页面太高了，出几个优化方案放浏览器给我选择"。
 * 现状 4 条配置 10 个模型 ≈ 518px 高，一直顶到屏幕上沿。
 *
 * 三条保真规则（照抄 tools/subagent-view.mjs 的做法）：
 *
 *   1. 几何不是手填的：行高 30、卡头 26、卡内边距 5、卡间距 8、根内边距 6、
 *      动作行 30 —— 全部取自 PickerList.kt 的常量；每张图的高度**是算出来的**
 *      （页面上标出的数字 = 按这套常数逐项加出来的和），不是估的；
 *   2. 文案照用户截图：我的ds / 公司tokenhub / 公司dmx / opencode 四条配置、
 *      10 个模型、当前 = opencode · deepseek-v4.1-flash[1m]；
 *   3. 深色 / 浅色两套都渲染 —— 选中行的蓝底在浅色下是不是够、卡边看不看得见，
 *      换一套主题才现形。
 *
 * 一条核实过的事实（写进页头）：弹层内容按**内容首选尺寸**长，**没有上限、
 * 没有滚动**（`popupHeightOf` 直接取内容高）；上下都放不下时 `popupAnchorY`
 * 只能贴屏幕顶 —— 配置一多就是"顶天立地"。
 *
 * 用法：
 *   node tools/model-popup-height-view.mjs --open          # 生成并用默认浏览器打开
 *   node tools/model-popup-height-view.mjs --shot          # 生成并截图（深色）
 *   node tools/model-popup-height-view.mjs --shot=light    # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'model-popup-height-view.html')

// ---------------------------------------------------------------- 颜色运算

const rgb = (hex) => {
  const h = hex.replace('#', '')
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}
const hex = (arr) => '#' + arr.map((c) => Math.round(Math.min(255, Math.max(0, c))).toString(16).padStart(2, '0')).join('')
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
    ok: '#5fad65', warn: '#d9a343', danger: '#db5c5c',
  },
  light: {
    pageBg: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageLine: '#e0e2e7',
    pageCard: '#ffffff', code: '#f2f3f5',
    bg: '#f7f8fa', surface: '#ffffff', text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', accentText: '#3574f0',
    ok: '#3d8b43', warn: '#b07d1a', danger: '#c0392b',
  },
}

function tokens(t) {
  return `
    --page-bg:${t.pageBg}; --page-fg:${t.pageFg}; --page-dim:${t.pageDim};
    --page-line:${t.pageLine}; --page-card:${t.pageCard};
    --bg:${t.bg}; --surface:${t.surface}; --text:${t.text}; --dim:${t.dim};
    --border:${t.border}; --code:${t.code};
    --accent:${t.accent}; --accent-text:${t.accentText};
    --ok:${t.ok}; --warn:${t.warn}; --danger:${t.danger};`
}

// ---------------------------------------------------------------- 真实常数（取 PickerList.kt）

const ROW = 30        // ROW_HEIGHT
const HEADER = 26     // 卡头一行（名字 + host + 胶囊）
const CARD_PAD = 5    // CARD_PAD
const CARD_GAP = 8    // CARD_GAP
const ROOT_PAD = 6    // ROOT_PAD
const ACTION_H = 30   // 动作行与可选项同高
const ACTION_GAP = 8  // ACTION_GAP
const CAP = 360       // D 案的封顶高度（可调）

/** 四条配置 —— 文案照用户截图。`cur` = 这个标签现在用的那条。 */
const PROFILES = [
  { name: '我的ds', host: 'api.deepseek.com', models: ['deepseek-flash[1m]'] },
  { name: '公司tokenhub', host: 'tokenhub.lihoogame…', models: ['deepseek-v4-flash[1m]', 'deepseek-v4-pro[1m]', 'mimo-v2.6-pro[1m]', 'mimo-v2.6-flash[1m]'] },
  { name: '公司dmx', host: 'www.dmxapi.cn', models: ['deepseek-flash-guan[1m]', 'mimo-v2.6-pro[1m]', 'claude-opus-5-5-cc[1m]', 'claude-sonnet-5-5-cc[1m]'] },
  { name: 'opencode', host: 'opencode.ai', models: ['deepseek-v4.1-flash[1m]'], cur: true },
]

const cardH = (p, collapsed = false) => CARD_PAD * 2 + HEADER + (collapsed ? 0 : p.models.length * ROW)
const stackH = (cards) => ROOT_PAD * 2 + cards.reduce((a, h) => a + h, 0) + CARD_GAP * (cards.length - 1) + ACTION_GAP + ACTION_H

// 各方案的高度（默认展开/选中 = 当前那条 opencode）。页面上标出的就是这些数。
const H_TODAY = stackH(PROFILES.map((p) => cardH(p)))
const H_A = stackH(PROFILES.map((p) => cardH(p, !p.cur)))
const H_B_TAB = 26 + 6
const H_B = ROOT_PAD * 2 + H_B_TAB + cardH(PROFILES[3]) + ACTION_GAP + ACTION_H
const H_C = ROOT_PAD * 2 + Math.max(PROFILES.length * ROW, cardH(PROFILES[3])) + ACTION_GAP + ACTION_H
const H_D = Math.min(H_TODAY, CAP)
// 最坏情况（当前那条恰好是模型最多的 dmx）
const H_A_WORST = stackH(PROFILES.map((p, i) => cardH(p, i !== 2)))
const H_B_WORST = ROOT_PAD * 2 + H_B_TAB + cardH(PROFILES[2]) + ACTION_GAP + ACTION_H
const H_C_WORST = ROOT_PAD * 2 + Math.max(PROFILES.length * ROW, cardH(PROFILES[2])) + ACTION_GAP + ACTION_H

// ---------------------------------------------------------------- 弹层片段

const headerRow = (p, arrow = '') => `
  <div class="m-head">${arrow ? `<span class="m-arrow">${arrow}</span>` : ''}
    <span class="m-name">${p.name}</span><span class="m-host">${p.host}</span>
    <span class="m-chip">会重开会话</span>
  </div>`

const modelRow = (p, m) => `
  <div class="m-row${p.cur ? ' is-cur' : ''}"><span class="m-tick">${p.cur ? '✓' : ''}</span><span class="m-model">${m}</span></div>`

const card = (p, collapsed = false) => `
  <div class="m-card${collapsed ? ' is-collapsed' : ''}">${headerRow(p, collapsed ? '▸' : '')}${collapsed ? '' : p.models.map((m) => modelRow(p, m)).join('')}</div>`

const actionRow = () => `<div class="m-row m-row--action"><span class="m-tick"></span><span class="m-model dim">管理模型…</span></div>`

/** 一个弹层像素框。kind: today | a | b | c | d */
const popup = (kind, width = 352, cap = null) => {
  let body = ''
  if (kind === 'today') {
    body = PROFILES.map((p) => card(p)).join(`<div class="m-gap"></div>`) + actionRow()
  } else if (kind === 'a') {
    body = PROFILES.map((p) => card(p, !p.cur)).join(`<div class="m-gap"></div>`) + actionRow()
  } else if (kind === 'b') {
    const tabs = PROFILES.map((p) => `<span class="m-tab${p.cur ? ' is-cur' : ''}">${p.name}</span>`).join('')
    body = `<div class="m-tabs">${tabs}</div><div class="m-gap m-gap--s"></div>${card(PROFILES[3])}${actionRow()}`
  } else if (kind === 'c') {
    const left = PROFILES.map((p) => `<div class="m-crow${p.cur ? ' is-cur' : ''}">${p.name}</div>`).join('')
    body = `<div class="m-cols"><div class="m-col-list">${left}</div><div class="m-col-models">${headerRow(PROFILES[3])}${PROFILES[3].models.map((m) => modelRow(PROFILES[3], m)).join('')}</div></div>${actionRow()}`
  } else if (kind === 'd') {
    body = `<div class="m-scroll" style="height:${cap - ROOT_PAD * 2 - ACTION_GAP - ACTION_H}px">${PROFILES.map((p) => card(p)).join(`<div class="m-gap"></div>`)}</div>${actionRow()}`
  }
  const style = kind === 'd' ? ` style="position:relative"` : ''
  const bar = kind === 'd' ? `<div class="m-bar" style="height:${Math.round((cap - ROOT_PAD * 2 - ACTION_GAP - ACTION_H) / H_TODAY * 100)}%"></div>` : ''
  return `<div class="m-pop" style="width:${width}px"${style}>${body}${bar}</div>`
}

const mockWithCap = (kind, width, label, extraClass = '') => `
  <div class="sil ${extraClass}">
    ${popup(kind, width)}
    <div class="sil__cap">${label}</div>
  </div>`

// ---------------------------------------------------------------- 页面

function page(theme) {
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>模型弹层「太高了」· 四个方案</title>
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
.lede { color: var(--page-dim); max-width: 76ch; margin: 0; font-size: 14.5px; }
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

/* ---- 并排：同一把尺子 ---- */
.bench { display:flex; align-items:flex-start; justify-content:center; gap:14px; flex-wrap:nowrap;
         padding: 26px 12px 22px; border:1px solid var(--page-line); border-radius: 14px;
         background: var(--page-card); overflow-x:auto; }
.sil { display:flex; flex-direction:column; gap:10px; flex:0 0 auto; }
.sil__cap { text-align:center; font-family: var(--mono); font-size: 11px; color: var(--page-dim); }
.sil__cap b { color: var(--page-fg); font-size: 14px; display:block; }
.sil--today .m-pop { outline: 2px solid var(--danger); outline-offset: 2px; }

/* ---- 弹层本体（几何 = PickerList.kt 的常数）---- */
.m-pop { position:relative; background: var(--bg); border:1px solid var(--border);
         border-radius: 12px; padding: ${ROOT_PAD}px; }
.m-gap { height: ${CARD_GAP}px; }
.m-gap--s { height: 6px; }
.m-card { border:1px solid var(--border); border-radius: 12px; padding: ${CARD_PAD}px; background: var(--bg); }
.m-head { height: ${HEADER}px; display:flex; align-items:center; gap:8px; padding: 0 4px; }
.m-arrow { font-family: var(--mono); font-size: 9px; color: var(--dim); }
.m-name { font-weight: 600; font-size: 12.5px; color: var(--text); white-space:nowrap; }
.m-host { font-size: 11px; color: var(--dim); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.m-chip { margin-left:auto; flex:0 0 auto; font-size: 10.5px; color: var(--warn);
          border:1px solid color-mix(in srgb, var(--warn) 42%, transparent); border-radius: 999px;
          padding: 0 7px; line-height: 16px;
          background: color-mix(in srgb, var(--warn) 12%, var(--bg)); }
.m-row { height: ${ROW}px; display:flex; align-items:center; border-radius: 8px; }
.m-row.is-cur { background: var(--accent); }
.m-row.is-cur .m-model { color: #fff; }
.m-tick { width:16px; flex:0 0 auto; text-align:center; font-size: 11px; color: var(--accent-text); }
.m-row.is-cur .m-tick { color:#fff; }
.m-model { font-size: 12px; font-family: var(--mono); color: var(--text); overflow:hidden;
           text-overflow:ellipsis; white-space:nowrap; }
.m-model.dim { color: var(--dim); font-family: var(--ui); font-size: 12px; }
.m-row--action { }

/* ---- B：页签 ---- */
.m-tabs { height:26px; display:flex; gap:4px; }
.m-tab { flex:1 1 0; min-width:0; display:flex; align-items:center; justify-content:center;
         font-size: 11px; color: var(--dim); border-bottom: 2px solid transparent;
         overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.m-tab.is-cur { color: var(--accent-text); border-bottom-color: var(--accent-text); }

/* ---- C：两栏 ---- */
.m-cols { display:flex; gap:6px; }
.m-col-list { width:96px; flex:0 0 auto; background: var(--surface); border:1px solid var(--border);
              border-radius: 8px; padding: 2px; }
.m-crow { height: 26px; display:flex; align-items:center; padding: 0 8px;
          border-radius: 6px; font-size: 11.5px; color: var(--text);
          overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.m-crow.is-cur { background: var(--accent); color:#fff; }
.m-col-models { flex:1 1 auto; min-width:0; border:1px solid var(--border); border-radius: 8px; padding: 3px; }

/* ---- D：滚动 ---- */
.m-scroll { overflow:hidden; position:relative; }
.m-bar { position:absolute; right:2px; top:${ROOT_PAD}px; width:6px; border-radius:3px;
         background: color-mix(in srgb, var(--dim) 55%, transparent); }

/* ---- 逐个看 ---- */
.grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(500px, 1fr)); gap: 26px; align-items:start; }
.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px; padding: 20px 20px 22px; }
.opt__head { display:flex; gap: 12px; margin-bottom: 14px; }
.opt__pick { font-family: var(--mono); font-size: 12px; letter-spacing:.08em; color:#fff; background: var(--accent);
             min-width: 30px; height: 30px; padding: 0 7px; border-radius: 8px; display:flex; align-items:center;
             justify-content:center; flex:0 0 auto; }
.opt__pick--dim { background: var(--page-dim); }
.opt__name { font-size: 15px; font-weight: 600; }
.opt__note { color: var(--page-dim); font-size: 12.5px; margin-top: 3px; max-width: 70ch; }
.opt__demo { display:flex; justify-content:center; padding: 14px 0 6px; }
dl.spec { margin: 12px 0 0; font-size: 12.5px; }
dl.spec dt { font-family: var(--mono); font-size: 10.5px; letter-spacing:.1em; text-transform:uppercase;
             color: var(--page-dim); margin-top: 10px; }
dl.spec dd { margin: 3px 0 0; color: var(--page-dim); }
dl.spec dd b { color: var(--page-fg); }
dl.spec ul { margin: 0; padding-left: 18px; }

table.cmp { width:100%; border-collapse: collapse; margin-top: 20px; font-size: 12.5px; }
table.cmp th, table.cmp td { text-align:left; padding: 9px 10px; border-bottom:1px solid var(--page-line); vertical-align: top; }
table.cmp th { color: var(--page-dim); font-family: var(--mono); font-size: 10.5px; letter-spacing:.1em;
               text-transform:uppercase; font-weight:500; }
table.cmp td { color: var(--page-dim); }
table.cmp td:first-child { color: var(--text); white-space:nowrap; }
table.cmp code { font-family: var(--mono); font-size: 11.5px; background: var(--code); padding: 1px 4px; border-radius: 3px; color: var(--text); }
.yes { color: var(--ok); } .no { color: var(--danger); }

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
      <p class="eyebrow">2026-10-13 · 选型台 · 不参与构建</p>
      <h1>模型弹层「太高了」：四个方案</h1>
      <p class="lede">你那套配置（4 条 × 10 个模型）现在要弹 <b>${H_TODAY}px</b> 高，一直顶到屏幕上沿。
        下面四个方案用<b>同一把尺子</b>并排画出来（高度 1:1、按真机常数算），选一个。</p>
    </div>
    <div class="toggle">
      <button id="dk" aria-pressed="true">深色</button><button id="lt" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先把事实摆清楚</h3>
    <p><b>为什么会长到顶天</b>：弹层内容按<b>内容首选尺寸</b>长，<b>没有上限、也没有滚动</b>
      （<code>popupHeightOf</code> 直接取内容高）；上下都塞不下时 <code>popupAnchorY</code> 只能贴屏幕顶
      —— 配置再多一块，它就再高一截，直到用不了。</p>
    <p><b>几何是算出来的，不是画着玩的</b>：行高 30、卡头 26、卡内边距 5、卡间距 8、根内边距 6、
      动作行 30 —— 全部取自 <code>PickerList.kt</code> 的常量；图上标的每个高度都是按这套常数逐项加出来的。</p>
    <p><b>内容照你的截图</b>：我的ds / 公司tokenhub / 公司dmx / opencode 四条、10 个模型，
      当前 = opencode · deepseek-v4.1-flash[1m]（蓝底那行）。宽度为了并排压到 270px，
      真机上弹层是按内容撑的（≈350px）。</p>
  </div>

  <h2 class="sect">一、并排：同一把尺子</h2>
  <p class="sect-note">全部 1:1、同一基线。高度标注 = 默认状态（展开/停留在<b>当前那条 opencode</b>，它只有 1 个模型）；
    括号里是该方案的最坏情况（当前那条恰好是模型最多的 dmx 时）。</p>

  <div class="bench">
    ${mockWithCap('today', 270, '<b>' + H_TODAY + 'px</b>现状', 'sil--today')}
    ${mockWithCap('a', 270, '<b>' + H_A + 'px</b>A 手风琴<br>最坏 ' + H_A_WORST + 'px')}
    ${mockWithCap('b', 270, '<b>' + H_B + 'px</b>B 页签<br>最坏 ' + H_B_WORST + 'px')}
    ${mockWithCap('c', 270, '<b>' + H_C + 'px</b>C 两栏<br>最坏 ' + H_C_WORST + 'px')}
    ${mockWithCap('d', 270, '<b>' + H_D + 'px</b>D 封顶+滚动<br>（封顶值）')}
  </div>

  <h2 class="sect">二、逐个看（真宽 352px）</h2>
  <p class="sect-note">每张下面列了「好在哪 / 代价 / 适合谁 / 实现量」。所有方案都保持：点行即切换、
    〔会重开会话〕照旧挂在卡头、弹层不抢输入框焦点。</p>

  <div class="grid">
    <div class="opt">
      <div class="opt__head"><span class="opt__pick opt__pick--dim">现状</span><div>
        <div class="opt__name">全都平铺</div>
        <div class="opt__note">4 条配置的 10 个模型全展开，${H_TODAY}px。</div>
      </div></div>
      <div class="opt__demo">${popup('today')}</div>
      <dl class="spec">
        <dt>好在哪</dt>
        <dd>所有模型一眼看到，一步直达。</dd>
        <dt>代价</dt>
        <dd>配置一多就顶天；顶天之后连"贴屏幕顶"都会把卡头切掉。</dd>
      </dl>
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">A</span><div>
        <div class="opt__name">手风琴：只展开当前那条</div>
        <div class="opt__note">当前配置照旧展开；其余三条收成一行卡头（名字 + 端点 + ▸）。点卡头换展开 ——
          一次只开一个。</div>
      </div></div>
      <div class="opt__demo">${popup('a')}</div>
      <dl class="spec">
        <dt>好在哪</dt>
        <dd><ul>
          <li><b>${H_TODAY}px → ${H_A}px</b>（最坏 ${H_A_WORST}px）；常用动作（在当前组里换模型）点击次数<b>不变</b>；</li>
          <li>四个网关的<b>名字与端点全在</b> —— 模型名很像的几条之间（deepseek-v4-flash / deepseek-flash-guan…），
            端点是唯一的区分，收起行把它保住了；</li>
          <li>点卡头只是换展开，不改变选择 —— 不容易误触。</li>
        </ul></dd>
        <dt>代价</dt>
        <dd>去别的组点模型要两步（展开 + 点）—— 与页签方案的点击数一样；点击卡头要重建弹层内容并调一次尺寸；
          可选：收起行尾部带一条"这组现在用啥"的小字，更贵一点。</dd>
        <dt>实现量</dt>
        <dd>中：复用现有卡与行，加一个"哪张卡展开"的状态与重建逻辑。</dd>
        <dt>适合</dt>
        <dd>组多、模型名像、经常跨组对比的人 —— 就是你这套配置的样子。</dd>
      </dl>
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">B</span><div>
        <div class="opt__name">页签：顶部一排组名</div>
        <div class="opt__note">一排组名页签，下面只画当前页签那组（卡头 + 模型行）。点页签换组。</div>
      </div></div>
      <div class="opt__demo">${popup('b')}</div>
      <dl class="spec">
        <dt>好在哪</dt>
        <dd>最矮：<b>${H_B}px</b>（最坏 ${H_B_WORST}px）；换组一次点击；组名一眼全在。</dd>
        <dt>代价</dt>
        <dd>别组的<b>端点与模型都看不见</b> —— 跨组对比要点着看；页签名长了要截断（352px 里四个中文名会挤）；
          页签是弹层族里没有过的新控件样式，要新画一套。</dd>
        <dt>实现量</dt>
        <dd>中偏多：新页签样式 + 重建逻辑。</dd>
        <dt>适合</dt>
        <dd>组名短、绝大多数时间只在当前组里换模型。</dd>
      </dl>
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">C</span><div>
        <div class="opt__name">两栏：左配置、右模型</div>
        <div class="opt__note">左列四条配置名（当前高亮），右列当前那条的模型行。</div>
      </div></div>
      <div class="opt__demo">${popup('c')}</div>
      <dl class="spec">
        <dt>好在哪</dt>
        <dd>约 <b>${H_C}px</b>（最坏 ${H_C_WORST}px）；静态内容、不用重建；左列本身就是"我在哪台机器上"。</dd>
        <dt>代价</dt>
        <dd>弹层要<b>变宽</b>（两列 ≈ 370px+，工具窗口 420 里几乎顶格）；左列名字截断；
          弹层族里第一次出现横向布局。</dd>
        <dt>实现量</dt>
        <dd>中：静态布局简单，但要重新量宽度、重排卡头。</dd>
        <dt>适合</dt>
        <dd>组名短、模型多、喜欢列表式操作的人。</dd>
      </dl>
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">D</span><div>
        <div class="opt__name">封顶 + 滚动（兜底）</div>
        <div class="opt__note">内容照旧，超过 ${CAP}px 就滚动；打开时把当前项滚进视野。</div>
      </div></div>
      <div class="opt__demo">${popup('d', 352, CAP)}</div>
      <dl class="spec">
        <dt>好在哪</dt>
        <dd>改动最小；弹层<b>永远不顶天</b>，顺带解掉 <code>popupAnchorY</code>"贴屏幕顶、卡头被切"那条；
          与 A/B/C <b>可以叠加</b>。</dd>
        <dt>代价</dt>
        <dd>治标：${H_TODAY}px 的内容还是 ${H_TODAY}px，只是装进 ${CAP}px 的框 —— 找东西要滚。</dd>
        <dt>实现量</dt>
        <dd>小：内容包一层滚动框 + 设一次尺寸。</dd>
        <dt>适合</dt>
        <dd><b>不管选哪个都建议带上</b> —— 它是兜底，不是主方案。</dd>
      </dl>
    </div>
  </div>

  <h2 class="sect">三、取舍</h2>
  <table class="cmp">
    <tr><th>方案</th><th>默认高</th><th>最坏高</th><th>跨组一览</th><th>去别组点模型</th><th>备注</th></tr>
    <tr><td>现状</td><td>${H_TODAY}px</td><td>${H_TODAY}px</td><td class="yes">全在</td><td>一步</td><td>顶天</td></tr>
    <tr><td>A 手风琴</td><td>${H_A}px</td><td>${H_A_WORST}px</td><td class="yes">名字+端点全在</td><td>两步</td><td>保住"端点即区分"</td></tr>
    <tr><td>B 页签</td><td>${H_B}px</td><td>${H_B_WORST}px</td><td class="no">只看得到组名</td><td>两步</td><td>最矮，但丢了对比</td></tr>
    <tr><td>C 两栏</td><td>${H_C}px</td><td>${H_C_WORST}px</td><td>组名全在</td><td>一步</td><td>变宽，逼近面板宽</td></tr>
    <tr><td>D 封顶滚动</td><td>${CAP}px</td><td>${CAP}px</td><td class="yes">全在（要滚）</td><td>一步</td><td>兜底，建议标配</td></tr>
  </table>

  <div class="verdict">
    <h2>我的建议</h2>
    <p><b>A（手风琴）+ D（封顶）</b>。A 把常用路径（在当前组里换模型）一步不少地留下，同时把四个网关的
      名字与端点全摆在眼前 —— 你这几条配置的名字（deepseek-v4-flash / deepseek-flash-guan / deepseek-flash）
      恰恰是"端点即身份"最吃紧的一类，B 的页签会把端点信息藏起来，C 则要付出弹层变宽的代价。</p>
    <p>D 顺手做掉：它与选哪个方案无关，封的是"配置再多也顶到天"这个底。</p>
    <p>看完回一个字母：<code>A</code> / <code>B</code> / <code>C</code> / <code>D</code>，
      组合也行（比如 <code>A+D</code>），或"再加个"。</p>
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
    let target = outFile
    let name = 'model-popup-height-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'model-popup-height-view-light.html')
      writeFileSync(target, page('light'))
      name = 'model-popup-height-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1500,4300',
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
