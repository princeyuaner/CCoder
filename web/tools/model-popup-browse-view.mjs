#!/usr/bin/env node
/**
 * 模型弹层「两栏的后续」· 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-14 用户（截图）："这个列表做的不好用，要点击才会显示子列表，同时
 * 左侧列表高度还会随着子列表变化，优化它"。现状是 10-13 选的 C 案（两栏）。
 *
 * 两条量过的事实（写进页头）：
 *   1. 左栏卡本身固定 108px（4 行 × 26 + 卡内衬 4），**不**随子列表变；
 *      跳的是**弹层整体** —— 浏览 opencode（1 个模型）158px、点一下公司内网
 *      （4 个模型）206px。原因是换浏览走 "cancel + 重开"（`browseModelProfile`，
 *      弹层尺寸按内容撑）；重建本身还带一次闪烁。
 *   2. 左栏行的浏览只有 `mouseClicked`（`profileChoiceRow`）—— 没有悬停路径。
 *
 * 三条保真规则（照抄 tools/model-popup-height-view.mjs 的做法）：
 *   1. 几何不是手填的：行 30（PICKER_ROW_HEIGHT）、左栏行 26（PROFILE_ROW_HEIGHT）、
 *      卡头 26、卡内衬 5（CARD_PAD）、卡缝 8（CARD_GAP）、根 6（ROOT_PAD）、
 *      左栏宽 108（PROFILE_COLUMN_WIDTH）、栏缝 6（COLUMN_GAP）、动作行 30；
 *      页面标出的每个高度都是按这套常数逐项加出来的；
 *   2. 文案照用户截图：我的ds(1) / 公司内网(4) / 公司dmx(4) / opencode(1，在用)
 *      共 10 个模型，当前 = opencode · deepseek-v4.1-flash[1m]；
 *   3. 深色 / 浅色两套都渲染。
 *
 * A 的 demo 是**可交互的**：悬停左栏 ≈130ms 切右栏（真行为就是按这个做的）、
 * 点左栏立刻切 —— 让人直接摸手感，别只看图。B/C 的滚动区也能滚。
 *
 * 用法：
 *   node tools/model-popup-browse-view.mjs --open          # 生成并用默认浏览器打开
 *   node tools/model-popup-browse-view.mjs --shot          # 生成并截图（深色）
 *   node tools/model-popup-browse-view.mjs --shot=light    # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'model-popup-browse-view.html')

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

// ---------------------------------------------------------------- 真实常数（PickerList.kt / ComposerModel.kt）

const ROW = 30          // PICKER_ROW_HEIGHT：模型行、动作行
const LEFT_ROW = 26     // PROFILE_ROW_HEIGHT：左栏配置行
const HEADER = 26       // 卡头一行（名字 + host + 胶囊）
const CARD_PAD = 5      // CARD_PAD
const CARD_GAP = 8      // CARD_GAP（卡与卡、动作行前）
const ROOT_PAD = 6      // ROOT_PAD（弹层内容四周）
const ACTION_H = 30     // 动作行
const COL_W = 108       // PROFILE_COLUMN_WIDTH
const COL_GAP = 6       // COLUMN_GAP
const CAP = 360         // B/C 的封顶高度

/** 四条配置 —— 文案照用户截图。cur = 在用那条；curModel = 它当前那个模型。 */
const PROFILES = [
  { id: 'ds', name: '我的ds', host: 'api.deepseek.com', models: ['deepseek-flash[1m]'] },
  { id: 'hub', name: '公司内网', host: 'tokenhub.lihoogame…', models: ['deepseek-v4-flash[1m]', 'deepseek-v4-pro[1m]', 'mimo-v2.6-pro[1m]', 'mimo-v2.6-flash[1m]'] },
  { id: 'dmx', name: '公司dmx', host: 'www.dmxapi.cn', models: ['deepseek-flash-guan[1m]', 'mimo-v2.6-pro[1m]', 'claude-opus-5-5-cc[1m]', 'claude-sonnet-5-5-cc[1m]'] },
  { id: 'oc', name: 'opencode', host: 'opencode.ai', models: ['deepseek-v4.1-flash[1m]'], cur: true, curModel: 'deepseek-v4.1-flash[1m]' },
]

const cardH = (p) => CARD_PAD * 2 + HEADER + p.models.length * ROW
const leftH = (n) => 4 + n * LEFT_ROW // 卡内衬 2×2
const H_TODAY_OC = ROOT_PAD * 2 + Math.max(leftH(4), cardH(PROFILES[3])) + CARD_GAP + ACTION_H   // 浏览 opencode
const H_TODAY_HUB = ROOT_PAD * 2 + Math.max(leftH(4), cardH(PROFILES[1])) + CARD_GAP + ACTION_H  // 浏览 公司内网
const H_A = ROOT_PAD * 2 + Math.max(leftH(4), Math.max(...PROFILES.map(cardH))) + CARD_GAP + ACTION_H // 定高 = 最高的那条卡
const H_B = Math.min(ROOT_PAD * 2 + PROFILES.reduce((a, p) => a + cardH(p), 0) + CARD_GAP * 3 + CARD_GAP + ACTION_H, CAP)
const H_C = Math.min(
  ROOT_PAD * 2 + PROFILES.reduce((a, p) => a + LEFT_ROW + p.models.length * ROW, 0) + CARD_GAP * 3 + CARD_GAP + ACTION_H,
  CAP,
)
const SCROLL_AREA = CAP - ROOT_PAD * 2 - CARD_GAP - ACTION_H // 310

// ---------------------------------------------------------------- 弹层片段

const chip = () => '<span class="m-chip">会重开会话</span>'

const headerRow = (p, withChip) => `
  <div class="m-head"><span class="m-name">${p.name}</span><span class="m-host">${p.host}</span>${withChip ? chip() : ''}</div>`

const modelRow = (p, m) => {
  const sel = p.cur && p.curModel === m
  return `<div class="m-row${sel ? ' is-sel' : ''}">${sel ? '<span class="m-tick">✓</span>' : '<span class="m-tick"></span>'}<span class="m-model">${m}</span></div>`
}

/** 一张卡（卡头 + 模型行）。[withChip]：切换它会重开会话时挂胶囊（opencode 那条不挂）。 */
const card = (p, withChip) => `
  <div class="m-card" data-pid="${p.id}">${headerRow(p, withChip)}${p.models.map((m) => modelRow(p, m)).join('')}</div>`

const leftRow = (p, browsedId) => `
  <div class="m-crow${p.id === browsedId ? ' is-browsed' : ''}" data-pid="${p.id}">
    <span class="m-crowname">${p.name}</span>${p.cur ? '<span class="m-dot"></span>' : ''}</div>`

const leftCol = (browsedId) => `<div class="m-col-list">${PROFILES.map((p) => leftRow(p, browsedId)).join('')}</div>`

const actionRow = () => '<div class="m-row m-row--action"><span class="m-tick"></span><span class="m-model dim">管理模型…</span></div>'

const GAP = `<div class="m-gap" style="height:${CARD_GAP}px"></div>`

/** 现状：两栏、按内容撑（浏览哪条就画哪条的卡）。 */
const popupToday = (browsedId, width = 400) => {
  const b = PROFILES.find((p) => p.id === browsedId)
  return `<div class="m-pop" style="width:${width}px">
    <div class="m-cols">${leftCol(browsedId)}<div class="m-right">${card(b, !b.cur)}</div></div>
    ${GAP}${actionRow()}</div>`
}

/**
 * A 悬停即览 + 定高：两栏结构不变；右栏里四条卡全部预先建好（只显示浏览那条），
 * 悬停/点击只换显示 —— 弹层尺寸在打开时钉死（= 最高那条卡），不重建、不跳。
 */
const popupA = (width = 400, browsedId = 'hub') => `
  <div class="m-pop a-zone" style="width:${width}px">
    <div class="a-cols" style="height:${Math.max(leftH(4), Math.max(...PROFILES.map(cardH)))}px">
      ${leftCol(browsedId)}
      <div class="m-right a-right">${PROFILES.map((p) => `<div class="a-card" data-pid="${p.id}" style="display:${p.id === browsedId ? '' : 'none'}">${card(p, !p.cur)}</div>`).join('')}</div>
    </div>
    ${GAP}${actionRow()}</div>`

/** B 全部平铺 + 封顶滚动。 */
const popupB = (width = 400) => `
  <div class="m-pop" style="width:${width}px">
    <div class="m-scroll b-scroll" style="height:${SCROLL_AREA}px">
      ${PROFILES.map((p, i) => card(p, !p.cur) + (i < PROFILES.length - 1 ? GAP : '')).join('')}
    </div>
    ${GAP}${actionRow()}</div>`

/** C 单栏树 + 封顶滚动：组名当节标题，模型缩进；无卡片壳。 */
const popupC = (width = 400) => `
  <div class="m-pop" style="width:${width}px">
    <div class="m-scroll c-scroll" style="height:${SCROLL_AREA}px">
      ${PROFILES.map((p) => `
        <div class="c-group">
          <div class="c-head"><span class="m-name">${p.name}</span><span class="m-host">${p.host}</span>${!p.cur ? chip() : ''}</div>
          ${p.models.map((m) => `<div class="c-mrow${p.cur && p.curModel === m ? ' is-sel' : ''}"><span class="m-tick">${p.cur && p.curModel === m ? '✓' : ''}</span><span class="m-model">${m}</span></div>`).join('')}
        </div>`).join('')}
    </div>
    ${GAP}${actionRow()}</div>`

// ---------------------------------------------------------------- 页面

const sil = (html, label, extraClass = '') => `
  <div class="sil ${extraClass}">${html}<div class="sil__cap">${label}</div></div>`

function page(theme) {
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>模型弹层·两栏之后：三个方案</title>
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
.sect-note code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }

/* ---- 并排：同一把尺子 ---- */
.bench { display:flex; align-items:flex-start; justify-content:center; gap:14px; flex-wrap:nowrap;
         padding: 26px 12px 22px; border:1px solid var(--page-line); border-radius: 14px;
         background: var(--page-card); overflow-x:auto; }
.sil { display:flex; flex-direction:column; gap:10px; flex:0 0 auto; }
.sil__cap { text-align:center; font-family: var(--mono); font-size: 11px; color: var(--page-dim); }
.sil__cap b { color: var(--page-fg); font-size: 14px; display:block; }
.sil--today .m-pop { outline: 2px solid var(--danger); outline-offset: 2px; }
.sil--rec .m-pop { outline: 2px solid var(--ok); outline-offset: 2px; }

/* ---- 弹层本体（几何 = PickerList.kt / ComposerModel.kt 的常数）---- */
.m-pop { position:relative; background: var(--bg); border:1px solid var(--border);
         border-radius: 12px; padding: ${ROOT_PAD}px; }
.m-gap { }
.m-card { border:1px solid var(--border); border-radius: 12px; padding: ${CARD_PAD}px; background: var(--bg); }
.m-head { height: ${HEADER}px; display:flex; align-items:center; gap:8px; padding: 0 4px; }
.m-name { font-weight: 600; font-size: 12.5px; color: var(--text); white-space:nowrap; }
.m-host { font-size: 11px; color: var(--dim); overflow:hidden; text-overflow:ellipsis;
          white-space:nowrap; flex:1 1 auto; min-width:0; }
.m-chip { margin-left:auto; flex:0 0 auto; font-size: 10.5px; color: var(--warn);
          border:1px solid color-mix(in srgb, var(--warn) 42%, transparent); border-radius: 999px;
          padding: 0 7px; line-height: 16px;
          background: color-mix(in srgb, var(--warn) 12%, var(--bg)); }
.m-row { height: ${ROW}px; display:flex; align-items:center; border-radius: 8px; }
.m-row.is-sel { background: var(--accent); }
.m-row.is-sel .m-model { color:#fff; }
.m-row:hover:not(.is-sel) { background: color-mix(in srgb, var(--accent) 18%, transparent); }
.m-tick { width:16px; flex:0 0 auto; text-align:center; font-size: 11px; color: var(--accent-text); }
.m-row.is-sel .m-tick { color:#fff; }
.m-model { font-size: 12px; font-family: var(--mono); color: var(--text); overflow:hidden;
           text-overflow:ellipsis; white-space:nowrap; }
.m-model.dim { color: var(--dim); font-family: var(--ui); font-size: 12px; }

/* ---- 两栏 ---- */
.m-cols, .a-cols { display:flex; gap:${COL_GAP}px; align-items:flex-start; }
.m-col-list { width:${COL_W}px; flex:0 0 auto; border:1px solid var(--border);
              border-radius: 12px; padding: 2px; }
.bench .m-col-list { width: 88px; }
.m-crow { height: ${LEFT_ROW}px; display:flex; align-items:center; padding: 0 6px;
          border-radius: 8px; font-size: 11.5px; color: var(--text);
          cursor:pointer; }
.m-crowname { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; flex:1 1 auto; min-width:0; }
.m-crow.is-browsed { background: var(--accent); color:#fff;
                     box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent-text) 60%, transparent); }
.m-crow:hover:not(.is-browsed) { background: color-mix(in srgb, var(--accent) 18%, transparent); }
.m-dot { width:12px; flex:0 0 auto; position:relative; }
.m-dot::after { content:''; position:absolute; right:0; top:50%; transform:translateY(-50%);
                width:5px; height:5px; border-radius:50%; background: var(--accent-text); }
.m-crow.is-browsed .m-dot::after { background:#fff; }
.m-right { flex:1 1 auto; min-width:0; }
.a-right { flex:1 1 auto; min-width:0; }

/* ---- 滚动（B / C） ---- */
.m-scroll { overflow-y:auto; position:relative; scrollbar-width: thin;
            scrollbar-color: color-mix(in srgb, var(--dim) 55%, transparent) transparent; }
.m-scroll::-webkit-scrollbar { width: 8px; }
.m-scroll::-webkit-scrollbar-thumb { background: color-mix(in srgb, var(--dim) 55%, transparent); border-radius: 4px; }

/* ---- C：树 ---- */
/* 组间隔 = 8（7 外边距 + 1 发丝线），与卡缝 CARD_GAP 等值 —— 标称高度才是真的 */
.c-group + .c-group { margin-top: 7px; border-top:1px solid var(--page-line); }
.c-head { height: ${HEADER}px; display:flex; align-items:center; gap:8px; padding: 0 4px; }
.c-mrow { height: ${ROW}px; display:flex; align-items:center; border-radius: 8px; padding-left: 14px; }
.c-mrow.is-sel { background: var(--accent); }
.c-mrow.is-sel .m-model { color:#fff; }
.c-mrow:hover:not(.is-sel) { background: color-mix(in srgb, var(--accent) 18%, transparent); }

/* ---- 逐个看 ---- */
.grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(500px, 1fr)); gap: 26px; align-items:start; }
.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px; padding: 20px 20px 22px; }
.opt--rec { border-left: 3px solid var(--ok); }
.opt__head { display:flex; gap: 12px; margin-bottom: 14px; }
.opt__pick { font-family: var(--mono); font-size: 12px; letter-spacing:.08em; color:#fff; background: var(--accent);
             min-width: 30px; height: 30px; padding: 0 7px; border-radius: 8px; display:flex; align-items:center;
             justify-content:center; flex:0 0 auto; }
.opt__pick--dim { background: var(--page-dim); }
.opt__pick--ok { background: var(--ok); }
.opt__name { font-size: 15px; font-weight: 600; }
.opt__note { color: var(--page-dim); font-size: 12.5px; margin-top: 3px; max-width: 78ch; }
.opt__note b { color: var(--page-fg); }
.opt__demo { display:flex; justify-content:center; padding: 14px 0 6px; }
.opt__hint { text-align:center; font-family: var(--mono); font-size: 11px; color: var(--page-dim); margin-top: 6px; }
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
.verdict p { color: var(--page-dim); margin: 0 0 10px; max-width: 92ch; }
.verdict b { color: var(--page-fg); }
.verdict code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
</style>
</head>
<body>
<div class="wrap">
  <div class="masthead">
    <div>
      <p class="eyebrow">2026-10-14 · 选型台 · 不参与构建</p>
      <h1>模型弹层：两栏的后续，三个方案</h1>
      <p class="lede">10-13 换上两栏（左配置、右模型）之后你报了两个人机问题：模型要
        <b>点</b>左栏才出来；换浏览时<b>弹层高度会跳</b>。下面先把现状量清楚，再看三个改法 ——
        A 的 demo 可以<b>直接摸</b>（悬停左栏、点左栏都接了真行为）。</p>
    </div>
    <div class="toggle">
      <button id="dk" aria-pressed="true">深色</button><button id="lt" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先把事实摆清楚</h3>
    <p><b>「左侧列表高度跟着子列表变」量下来的真相</b>：左栏卡本身是<b>固定 108px</b>（4 行 × 26 + 内衬 4），
      不随右栏变；跳的是<b>弹层整体</b> —— 浏览 opencode（1 个模型）时 <b>${H_TODAY_OC}px</b>，点一下公司内网（4 个模型）变
      <b>${H_TODAY_HUB}px</b>，一进一出跳 <b>${H_TODAY_HUB - H_TODAY_OC}px</b>，看起来就是"列表跟着子列表长高变矮"。
      跳的原因在 <code>browseModelProfile</code>：换浏览走的是"收起再重开"，而弹层尺寸按内容撑 ——
      重建本身还附送一次闪烁。</p>
    <p><b>「要点击才会显示子列表」在代码里就一句话</b>：左栏行的浏览只有 <code>mouseClicked</code>
      （<code>profileChoiceRow</code>），没有悬停路径 —— 想看别条配置的模型，必须先点一下。</p>
    <p><b>为什么换浏览非要重建</b>：平台弹层没有"改内容再改尺寸"的现成路数（代码注释原话）。
      这条正好是 A 的钥匙：<b>尺寸在打开时钉死之后，内容就能就地换</b> —— 不重建、不跳。</p>
    <p><b>几何是算出来的</b>：行 30 / 左栏行 26 / 卡头 26 / 卡内衬 5 / 卡缝 8 / 根 6 / 左栏宽 108 / 栏缝 6 / 动作行 30，
      全部取自 <code>PickerList.kt</code> 与 <code>ComposerModel.kt</code>；图上标的每个高度都按这套常数逐项加出。
      内容照你截图：我的ds(1) / 公司内网(4) / 公司dmx(4) / opencode(1，<b>在用</b>)，共 10 个模型。
      并排图压到 270px 宽，单看区是你数据的真宽 ≈400px。</p>
  </div>

  <h2 class="sect">一、并排：同一把尺子</h2>
  <p class="sect-note">全部 1:1（高度不缩）。前两张是<b>现状的两种浏览态</b> —— 红框那一对之间就是那一下 48px 的跳动；
    后三张是新方案，都在"浏览公司内网"的同一状态下画。</p>

  <div class="bench">
    ${sil(popupToday('oc', 270), '<b>' + H_TODAY_OC + 'px</b>现状 · 浏览 opencode', 'sil--today')}
    ${sil(popupToday('hub', 270), '<b>' + H_TODAY_HUB + 'px</b>现状 · 浏览 公司内网<br>↕ 点左栏跳 ' + (H_TODAY_HUB - H_TODAY_OC) + 'px', 'sil--today')}
    ${sil(popupA(270, 'hub'), '<b>' + H_A + 'px</b>A 悬停即览 · 定高<br>（任何浏览态都一样）', '')}
    ${sil(popupB(270), '<b>' + H_B + 'px</b>B 全部平铺 · 封顶<br>（内容 518，滚 158）', '')}
    ${sil(popupC(270), '<b>' + H_C + 'px</b>C 单栏树 · 封顶<br>（内容 478，滚 118）', '')}
  </div>

  <h2 class="sect">二、逐个看（真宽 ≈400px）</h2>
  <p class="sect-note">A 的 demo 是活的：把鼠标压到左栏条目上停约 130ms、或直接点，右栏都跟着换；
    B / C 的滚动区能滚（打开时自动滚到「在用」那条）。三个方案都保持：〔会重开会话〕挂在卡头（同一条
    <code>effectOf</code> 判定）、点模型才真正切换、弹层不抢输入框焦点。</p>

  <div class="grid">
    <div class="opt opt--rec">
      <div class="opt__head"><span class="opt__pick opt__pick--ok">A</span><div>
        <div class="opt__name">悬停即览（两栏保留 · 弹层定高）</div>
        <div class="opt__note">鼠标停在左栏条目上，右栏就地换成那一条的模型（约 130ms，防指针路过闪跳）；
          点击照旧也切浏览（老手势不废）。弹层在<b>打开时定高一次</b>（= 最高那条卡 + 动作行 = ${H_A}px），
          换浏览只换内容、不重建、不跳。</div>
      </div></div>
      <div class="opt__demo">${popupA(400, 'hub')}</div>
      <div class="opt__hint">把鼠标压到左栏条目上试试；切到 opencode（1 个模型）能看到下方空出的区域</div>
      <dl class="spec">
        <dt>好在哪</dt>
        <dd><ul>
          <li><b>两个毛病直接消失</b>：不点也能看（悬停）、永远不跳（定高）；</li>
          <li>信息零损失，结构还是 10-13 你选的那套；你的数据<b>全程不要滚动</b>（${H_A}px &lt; 封顶 ${CAP}px）；</li>
          <li>比 B 矮 ${CAP - H_A}px：默认打开不再"一屏全是模型"。</li>
        </ul></dd>
        <dt>代价</dt>
        <dd>浏览 1 个模型的配置时，右栏下方空出一块（定高换稳定 —— 在上面 demo 里点 opencode 看）；
          悬停要有 ~130ms 延迟兜底，否则指针路过左栏会闪跳。</dd>
        <dt>实现量</dt>
        <dd>中。四张卡打开时全部建好、只显示浏览那条；换浏览 = 替换右栏子件 + 重画
          （<b>不走 cancel/rebuild</b>，高度钉死后这条路才成立）；hover 计时器挂左栏行；
          「换浏览重开弹层」那条路和 <code>modelPopupBrowsedId</code> 的跨重建状态一并简化。测试补三条：
          悬停切换 / 定高 / 点击兼容。</dd>
        <dt>适合</dt>
        <dd>默认选项。想"什么都不丢、什么都不跳"。</dd>
      </dl>
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">B</span><div>
        <div class="opt__name">全部平铺 + 封顶滚动</div>
        <div class="opt__note">回到卡片平铺：每条一张卡、全部展开、不分左右。超过 ${CAP}px 封顶滚动，
          打开时自动滚到「在用」那条。没有浏览态、没有子列表 —— 想看谁的模型都直接在看。</div>
      </div></div>
      <div class="opt__demo">${popupB(400)}</div>
      <div class="opt__hint">滚轮可用；打开时已自动滚到 opencode（在用那条）</div>
      <dl class="spec">
        <dt>好在哪</dt>
        <dd>零交互看到全部（滚轮除外）；结构最简单 —— 左栏与浏览态整段代码直接删掉；
          卡片语言不变（还是同一套卡与行）。</dd>
        <dt>代价</dt>
        <dd>你的数据内容 518px、封顶 ${CAP} → 要滚 158px（约 5 行）；弹层比 A 高 ${CAP - H_A}px；
          滚动找东西比"停一下就换"慢。</dd>
        <dt>实现量</dt>
        <dd>小~中（其实是删代码）：平铺 + JBScrollPane 封顶 + 打开滚到在用；浏览相关测试删/改。</dd>
        <dt>适合</dt>
        <dd>手不沾悬停、宁可滚的人；配置少模型少时最舒服。</dd>
      </dl>
    </div>

    <div class="opt">
      <div class="opt__head"><span class="opt__pick">C</span><div>
        <div class="opt__name">单栏树 + 封顶滚动</div>
        <div class="opt__note">组名当节标题、模型缩进排在下面，全部展开；无卡片壳、更密；
          封顶滚动同 B。组头以后可加"点击折叠"。</div>
      </div></div>
      <div class="opt__demo">${popupC(400)}</div>
      <div class="opt__hint">滚轮可用；同样已滚到在用那条</div>
      <dl class="spec">
        <dt>好在哪</dt>
        <dd>同样零交互看到全部；${CAP}px 里装的比 B 多（无卡壳、无卡内衬）；树的口味。</dd>
        <dt>代价</dt>
        <dd>与弹层族的"卡"语言不同了 —— 模型 / 权限模式 / 思考深度三个弹层共用同一套卡与行，
          这是块新皮肤；同样是滚动方案，先滚再点。</dd>
        <dt>实现量</dt>
        <dd>中（B 基础上加一套树行样式；折叠可选）。</dd>
        <dt>适合</dt>
        <dd>嫌卡片占地方的人。</dd>
      </dl>
    </div>
  </div>

  <h2 class="sect">三、取舍</h2>
  <table class="cmp">
    <tr><th>方案</th><th>看别条模型要几笔</th><th>高度（你的数据）</th><th>要滚动吗</th><th>信息</th><th>实现量</th><th>备注</th></tr>
    <tr><td>现状</td><td class="no">1 击 + 重建闪烁</td><td class="no">${H_TODAY_OC} ↔ ${H_TODAY_HUB} 跳</td><td>不滚</td><td class="yes">全</td><td>—</td><td>两个毛病</td></tr>
    <tr><td>A 悬停即览</td><td class="yes">0（悬停），1 击兼容</td><td class="yes">${H_A} 恒定</td><td>不滚</td><td class="yes">全</td><td>中</td><td>小配置浏览时有空区</td></tr>
    <tr><td>B 平铺滚动</td><td class="yes">0</td><td>${CAP} 恒定</td><td>158px</td><td class="yes">全</td><td class="yes">小~中</td><td>结构最简</td></tr>
    <tr><td>C 单栏树</td><td class="yes">0</td><td>${CAP} 恒定</td><td>118px</td><td class="yes">全</td><td>中</td><td>新皮肤</td></tr>
  </table>

  <div class="verdict">
    <h2>我的建议</h2>
    <p><b>A</b>。两个毛病它都消掉，别的东西一样没动 —— 两栏是你 10-13 刚选的，A 只把
      "要看别条"从"点一步"降到"停一下"，把"跳一截"钉成恒定；你的数据下它 ${H_A}px、不滚，
      比 B 矮 ${CAP - H_A}px。代价也摆在明处：小配置的右栏下方会空一块（demo 里点 opencode 看得到）。</p>
    <p><b>B 是好的备胎</b>：如果你对悬停无感、或主要是触控板（没有"鼠标停一下"这种动作），
      B 的零交互反而更直 —— 就是要滚。C 除非你就想换掉卡语言，否则先不动。</p>
    <p>附带一条，无论选谁：把「<b>封顶 + 滚动</b>」当兜底焊上（A 是超过封顶时右栏滚；B/C 已含）——
      配置一多不会再顶天，这条与 10-13 的 D 案一致。</p>
    <p>看完回一个字母：<code>A</code> / <code>B</code> / <code>C</code>，或带一句修改要求
      （比如「A 但悬停不打延迟」）。</p>
  </div>
</div>

<script>
  // ---- A：悬停即览（~130ms 防抖）+ 点击立刻切 ----
  document.querySelectorAll('.a-zone').forEach((pop) => {
    const rows = pop.querySelectorAll('.m-crow[data-pid]')
    const cards = pop.querySelectorAll('.a-card[data-pid]')
    const show = (pid) => {
      cards.forEach((c) => { c.style.display = c.dataset.pid === pid ? '' : 'none' })
      rows.forEach((r) => r.classList.toggle('is-browsed', r.dataset.pid === pid))
    }
    rows.forEach((r) => {
      r.addEventListener('mouseenter', () => {
        clearTimeout(r._t)
        r._t = setTimeout(() => show(r.dataset.pid), 130)
      })
      r.addEventListener('mouseleave', () => clearTimeout(r._t))
      r.addEventListener('click', () => { clearTimeout(r._t); show(r.dataset.pid) })
    })
  })

  // ---- B / C：打开时滚到「在用」那条 ----
  document.querySelectorAll('.b-scroll, .c-scroll').forEach((sc) => {
    const cur = sc.querySelector('.is-sel')
    if (cur) sc.scrollTop = Math.max(0, cur.offsetTop + cur.offsetHeight + 6 - sc.clientHeight)
  })

  // ---- 主题切换 ----
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
    let name = 'model-popup-browse-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'model-popup-browse-view-light.html')
      writeFileSync(target, page('light'))
      name = 'model-popup-browse-view-light.png'
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
