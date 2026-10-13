#!/usr/bin/env node
/**
 * 定时发送 · 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-13 用户："我想增加定时发送功能，我可以选择当天几点发送输入框的东西，
 * 先给我出方案到浏览器选择"。这一页就是"几个方案"。
 *
 * 三个方案的分岔点只有一个：**排上之后，那条消息放在哪、长什么样** ——
 *   A 定时条：消息从输入框交出去，挂到输入卡上方一条带子上（排队条的姊妹）；
 *   B 输入框预定：文字留在输入框里，卡内多一条「将在几点自动发送」的带子；
 *   C 待发卡：消息进转写区，是一条虚着画的"待发气泡"，到点变真气泡。
 * 入口（一颗小时钟）、选时弹层、到点行为三个方案完全一样，画在"共同件"一节。
 *
 * 三条保真规则（照抄 tools/model-popup-height-view.mjs 的做法）：
 *
 *   1. 几何不是手填的：窗口 560、底区内边距 6/8/8/8（ClaudePanel.kt 的
 *      `empty(6,8,8,8)`）、卡片圆角 16（CARD_CORNER_ARC）、卡内边距 4/6/5/6、
 *      发送键直径 29（RoundSendButton.DIAMETER）、附件键 22 格（AttachButton.BOX_SIDE）、
 *      排队条内边距 4/8（QueueStrip 的 border）、弹层常数 30/26/5/8/6（PickerList.kt，
 *      与模型弹层同一把尺子）；
 *   2. 颜色不是手填的：转写区底、气泡、工具栏底带（mix(面板底, 标签前景, 0.09)，
 *      FOOTER_TINT）都取自 ThemeInjector / styles.css 的真实值；
 *   3. 深色 / 浅色两套都渲染 —— 幽灵气泡的虚线、"待发"标签这种淡记号，
 *      换一套主题才看得出够不够。
 *
 * 一条口径（写进页头）：到点那一下 = 替你按发送键，走的就是现成的发送路径
 * （回合进行中 → 进排队条；未就绪 → 排队等就绪）。定时只活在这次会话里，
 * 与排队条同寿命（关工具窗口/重启即丢）。
 *
 * 用法：
 *   node tools/scheduled-send-view.mjs --open          # 生成并用默认浏览器打开
 *   node tools/scheduled-send-view.mjs --shot          # 生成并截图（深色）
 *   node tools/scheduled-send-view.mjs --shot=light    # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'scheduled-send-view.html')

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
    panel: '#2b2d30',          // 底区（Swing 一侧）的面板底
    transcript: '#1e1f22',     // 转写区（JCEF）的底
    text: '#dfe1e5', dim: '#9da0a8', border: '#393b40',
    accent: '#2f65ca', accentText: '#7fb0ff', focus: '#548af7',
    ok: '#5fad65', warn: '#d9a343', danger: '#db5c5c',
  },
  light: {
    pageBg: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageLine: '#e0e2e7',
    pageCard: '#ffffff', code: '#f2f3f5',
    panel: '#f7f8fa',
    transcript: '#ffffff',
    text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', accentText: '#3574f0', focus: '#3574f0',
    ok: '#3d8b43', warn: '#b07d1a', danger: '#c0392b',
  },
}

/** 工具栏底带 = mix(面板底, 标签前景, 0.09)，与 Composer.kt 的 FOOTER_TINT 同源。 */
const bandOf = (t) => mix(t.panel, t.dark ? '#dfe1e5' : '#000000', 0.09)

function tokens(t) {
  return `
    --page-bg:${t.pageBg}; --page-fg:${t.pageFg}; --page-dim:${t.pageDim};
    --page-line:${t.pageLine}; --page-card:${t.pageCard};
    --panel:${t.panel}; --transcript:${t.transcript};
    --text:${t.text}; --dim:${t.dim}; --border:${t.border}; --code:${t.code};
    --accent:${t.accent}; --accent-text:${t.accentText}; --focus:${t.focus};
    --band:${bandOf(t)};
    --ok:${t.ok}; --warn:${t.warn}; --danger:${t.danger};`
}

// ---------------------------------------------------------------- 真实常数

const WIN_W = 560        // 工具窗口宽（splitter 选型台同一常数）
const CARD_ARC = 16      // CARD_CORNER_ARC
const SEND_D = 29        // RoundSendButton.DIAMETER
const ATTACH_BOX = 22    // AttachButton.BOX_SIDE
const ROW = 30           // PickerList.ROW_HEIGHT
const HEADER = 26        // 弹层卡头
const CARD_PAD = 5       // 弹层卡内边距
const CARD_GAP = 8       // 弹层卡距
const ROOT_PAD = 6       // 弹层根内边距

/** 场景（三张图统一，取最坏情况）。 */
const SCENE = {
  now: '现在 14:22',
  schedTime: '今天 18:00',
  schedText: '帮我整理今天的发布记录',
  queueText: '跑完把测试结果贴给我',
  tail: '…3 个文件都改完了，测试全绿。',
  model: 'deepseek-v4.1-flash[1m]',
  mode: '标准',
  effort: '思考·高',
  placeholder: '@ 文件 · # 符号 · / 命令 · Enter 发送 · Shift+Enter 换行',
}

// ---------------------------------------------------------------- 小图标

const svgClock = (size = 12, sw = 1.35) => `
  <svg class="gly" width="${size}" height="${size}" viewBox="0 0 12 12" fill="none" aria-hidden="true">
    <circle cx="6" cy="6" r="4.7" stroke="currentColor" stroke-width="${sw}"/>
    <path d="M6 6 L6 3.4 M6 6 L8.1 7.2" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round"/>
  </svg>`

const svgPaperclip = () => `
  <svg class="gly" width="17" height="17" viewBox="0 0 12 12" fill="none" aria-hidden="true"
       style="transform:rotate(-45deg)">
    <path d="M8.4 2.8 L8.4 7.6 A2.4 2.4 0 0 1 3.6 7.6 L3.6 3.9 A1.15 1.15 0 0 1 5.9 3.9 L5.9 7.4 A0.5 0.5 0 0 1 4.9 7.4 L4.9 4.2"
          stroke="currentColor" stroke-width="1.15" stroke-linecap="round"/>
  </svg>`

const svgArrowUp = () => `
  <svg width="13" height="13" viewBox="0 0 13 13" fill="none" aria-hidden="true">
    <path d="M6.5 10.4 L6.5 2.6 M6.5 2.6 L3.7 5.4 M6.5 2.6 L9.3 5.4"
          stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>`

const svgStop = () => `<span style="display:inline-block;width:9px;height:9px;border-radius:2px;background:#fff"></span>`

// ---------------------------------------------------------------- 输入卡

/** 工具栏那一行。clock: 'plain' | 'marked' | 'none'；send: 'arrow'（空闲）| 'stop'（回合进行中）。 */
const toolbarRow = (clock = 'plain', send = 'arrow') =>
  `<div class="foot">
     <span class="ic" title="附件">${svgPaperclip()}</span>
     <span class="labs">
       <span class="lab">${SCENE.model} <span class="caret">▾</span></span>
       <span class="lab">${SCENE.mode} <span class="caret">▾</span></span>
       <span class="lab">${SCENE.effort} <span class="caret">▾</span></span>
     </span>
     <span class="right">
       ${clock === 'none' ? '' : `<span class="ic ic--clock ${clock === 'marked' ? 'is-marked' : ''}" title="定时发送">${svgClock()}</span>`}
       <span class="send" title="${send === 'stop' ? '停止（并清掉 1 条排队）' : '发送'}">${send === 'stop' ? svgStop() : svgArrowUp()}</span>
     </span>
   </div>`

/** 输入卡。input 给了就画那行字，否则画占位串；band 是乙案的卡内定时带。 */
const composerCard = ({ input = null, band = null, clock = 'plain', send = 'arrow' } = {}) =>
  `<div class="card">
     ${band ? `<div class="card__band">${svgClock(12)} 今天 18:00 自动发送 <span class="link">· 撤回</span></div>` : ''}
     <div class="card__input">${input ? `<span class="txt">${input}</span>` : `<span class="ph">${SCENE.placeholder}</span>`}</div>
     ${toolbarRow(clock, send)}
   </div>`

// ---------------------------------------------------------------- 条带与气泡

/** 定时条（甲案）：排队条的姊妹 —— 同一把内边距、同一档小字、同一个 ✕。 */
const stripSched = (collapsed = false) =>
  `<div class="strip strip--sched">
     <span class="strip__main">${svgClock(12)} 定时${collapsed ? ' 2 条 <span class="dim">▸</span>' : ` · ${SCENE.schedTime} · ${SCENE.schedText}`}</span>
     ${collapsed ? '' : '<span class="strip__x">✕</span>'}
   </div>`

/** 排队条（现成控件，原样画）。 */
const stripQueue = () =>
  `<div class="strip">
     <span class="strip__main">排队 1 · ${SCENE.queueText}</span>
     <span class="strip__x">✕</span>
   </div>`

/** 转写区切片：最后一行助手文字；（丙案）底下挂一枚待发气泡。 */
const transcriptSlice = ({ ghost = false } = {}) =>
  `<div class="tr">
     <div class="tr__line">${SCENE.tail}</div>
     ${ghost ? `<div class="ghost">
        <div class="ghost__top">${svgClock(11)} 今天 18:00 待发 <span class="ghost__x">✕</span></div>
        <div class="ghost__txt">${SCENE.schedText}</div>
      </div>` : ''}
   </div>`

/** 一套完整模拟：转写区切片 + 底区（条带 + 输入卡）。 */
const mock = (kind, { clock = 'plain' } = {}) => {
  const strips =
    kind === 'A' ? stripSched(false) + stripQueue()
    : kind === 'C' ? stripQueue()
    : stripQueue()
  const card =
    kind === 'B'
      ? composerCard({ input: SCENE.schedText, band: true, clock, send: 'stop' })
      : composerCard({ clock, send: 'stop' })
  return `<div class="sim">
     ${transcriptSlice({ ghost: kind === 'C' })}
     <div class="bottom">${strips}${card}</div>
   </div>`
}

// ---------------------------------------------------------------- 弹层（选时）

const popRow = (label, value) =>
  `<div class="m-row"><span class="m-lab">${label}</span><span class="m-val">${value}</span></div>`

/** 选时弹层。state: 'ok' | 'past'。 */
const timePopup = (state = 'ok') => `
  <div class="m-pop" style="width:300px">
    <div class="m-card">
      <div class="m-head"><span class="m-name">快捷</span><span class="m-hint">${SCENE.now}</span></div>
      ${popRow('30 分钟后', '14:52')}
      ${popRow('1 小时后', '15:22')}
      ${popRow('2 小时后', '16:22')}
    </div>
    <div class="m-gap"></div>
    <div class="m-card">
      <div class="m-head"><span class="m-name">指定今天时刻</span></div>
      <div class="m-row m-row--field">
        <span class="m-field ${state === 'past' ? 'is-bad' : 'is-good'}">${state === 'past' ? '14:05' : '18:00'}</span>
        <span class="m-fhint ${state === 'past' ? 'is-bad' : ''}">${state === 'past' ? '今天 14:05 已经过了，换一个' : '回车确认'}</span>
      </div>
    </div>
  </div>`

/** 弹层锚在工具栏上（向上弹）：一串悬浮图。 */
const floatOn = (popupHtml, cardHtml, gap = 4) =>
  `<div class="floatfig">${popupHtml}<div style="height:${gap}px"></div>${cardHtml}</div>`

// ---------------------------------------------------------------- 页面

const bay = (letter, name, note, html) =>
  `<div class="bay">
     <div class="bay__cap"><b>${letter}</b>${name}<span class="bay__note">${note}</span></div>
     ${html}
   </div>`

const opt = (letter, name, note, html, dl) =>
  `<div class="opt">
     <div class="opt__head"><span class="opt__pick">${letter}</span><div>
       <div class="opt__name">${name}</div>
       <div class="opt__note">${note}</div>
     </div></div>
     <div class="opt__demo">${html}</div>
     <dl class="spec">${dl}</dl>
   </div>`

function page(theme) {
  const t = THEMES[theme]
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>定时发送 · 三个方案</title>
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
.gly { vertical-align: -2px; }

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
.brief p { margin: 0 0 8px; color: var(--page-dim); font-size: 13.5px; max-width: 104ch; }
.brief p:last-child { margin-bottom: 0; }
.brief code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
.brief b { color: var(--page-fg); }

h2.sect { font-family: var(--serif); font-size: 25px; font-weight: 600; margin: 54px 0 4px; }
.sect-note { color: var(--page-dim); font-size: 13.5px; margin: 0 0 22px; max-width: 100ch; }
.sect-note b { color: var(--page-fg); }

/* ---- 模拟块：真机几何 ---- */
.bay { width: ${WIN_W}px; margin: 0 auto 34px; }
.bay__cap { font-size: 14px; margin: 0 0 10px; color: var(--page-fg); }
.bay__cap b { font-family: var(--mono); font-size: 13px; letter-spacing:.06em; color:#fff;
              background: var(--accent); border-radius: 6px; padding: 2px 8px; margin-right: 10px; }
.bay__note { color: var(--page-dim); font-size: 12.5px; margin-left: 10px; }

.sim { width: ${WIN_W}px; border:1px solid var(--page-line); border-radius: 10px; overflow: hidden; }
.tr { background: var(--transcript); padding: 10px 12px 8px; min-height: 64px; display:flex;
      flex-direction:column; justify-content:flex-end; gap: 6px; }
.tr__line { font-size: 12.5px; color: var(--dim); }
.bottom { background: var(--panel); padding: 6px 8px 8px; }

/* 条带（定时条 = 排队条的姊妹：4/8 内边距、12px 小字、右侧 ✕） */
.strip { padding: 4px 0; font-size: 12px; color: var(--dim); display:flex; align-items:center; }
.strip__main { display:flex; align-items:center; gap: 5px; }
.strip__x { margin-left:auto; font-size: 11px; }
.strip--sched .strip__main { color: var(--dim); }

/* 输入卡（圆角 16、描边 1px、内边距 4/6/5/6、底带 = mix 0.09） */
.card { border:1px solid var(--border); border-radius: ${CARD_ARC}px; overflow:hidden; }
.card__band { padding: 3px 8px 0; font-size: 12px; color: var(--dim); display:flex; align-items:center; gap: 5px; }
.card__band .link { color: var(--accent-text); }
.card__input { padding: 6px 8px 4px; min-height: 34px; font-size: 13px; }
.card__input .txt { color: var(--text); }
.card__input .ph { color: var(--dim); }
.foot { background: var(--band); padding: 2px 6px; display:flex; align-items:center; min-height: 33px; }
.ic { color: var(--dim); display:flex; align-items:center; width: ${ATTACH_BOX}px; justify-content:center; }
.ic--clock { margin-right: 6px; }
.ic.is-marked { outline: 2px solid var(--danger); outline-offset: 1px; border-radius: 5px; }
.labs { display:flex; align-items:center; margin-left: 8px; gap: 10px; }
.lab { font-size: 13px; color: var(--dim); white-space:nowrap; }
.caret { font-size: 10px; }
.right { margin-left:auto; display:flex; align-items:center; }
.send { width: ${SEND_D}px; height: ${SEND_D}px; border-radius: 50%; background: var(--focus);
        display:flex; align-items:center; justify-content:center; }

/* 待发气泡（丙案）：虚着画的用户气泡 */
.ghost { align-self: flex-end; max-width: 85%; border: 1px dashed color-mix(in srgb, var(--focus) 55%, var(--transcript));
         border-radius: 12px; padding: 5px 10px 7px; background: color-mix(in srgb, var(--focus) 7%, var(--transcript)); }
.ghost__top { font-size: 11px; color: var(--dim); display:flex; align-items:center; gap: 4px; }
.ghost__x { margin-left: 6px; }
.ghost__txt { font-size: 13px; color: var(--text); margin-top: 1px; }

/* ---- 弹层（PickerList 同一把尺子）---- */
.floatfig { display:flex; flex-direction:column; width: ${WIN_W - 16}px; }
.floatfig > .m-pop { align-self: flex-end; margin-right: 44px; }
.floatfig > .card { width: 100%; }
.m-pop { background: var(--panel); border:1px solid var(--border); border-radius: 12px; padding: ${ROOT_PAD}px; }
.m-gap { height: ${CARD_GAP}px; }
.m-card { border:1px solid var(--border); border-radius: 12px; padding: ${CARD_PAD}px; background: var(--panel); }
.m-head { height: ${HEADER}px; display:flex; align-items:center; gap:8px; padding: 0 4px; }
.m-name { font-weight: 600; font-size: 12.5px; color: var(--text); }
.m-hint { margin-left:auto; font-size: 11px; color: var(--dim); }
.m-row { height: ${ROW}px; display:flex; align-items:center; border-radius: 8px; padding: 0 6px; gap: 8px; }
.m-lab { font-size: 12.5px; color: var(--text); }
.m-val { margin-left:auto; font-family: var(--mono); font-size: 12px; color: var(--dim); }
.m-row--field { gap: 10px; }
.m-field { font-family: var(--mono); font-size: 12.5px; color: var(--text);
           border:1px solid var(--border); border-radius: 6px; padding: 3px 8px; min-width: 64px; text-align:center; }
.m-field.is-good { border-color: var(--focus); }
.m-field.is-bad { border-color: var(--warn); }
.m-fhint { font-size: 11.5px; color: var(--dim); }
.m-fhint.is-bad { color: var(--warn); }

/* ---- 两栏排版 ---- */
.cols { display:grid; grid-template-columns: 1fr 1fr; gap: 30px; align-items:start; }
@media (max-width: 1200px) { .cols { grid-template-columns: 1fr; } }
.figcap { font-size: 12.5px; color: var(--page-dim); margin: 10px 0 0; max-width: 66ch; }
.figcap b { color: var(--page-fg); }

/* ---- 逐个看 ---- */
.grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(620px, 1fr)); gap: 26px; align-items:start; }
.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px; padding: 20px 20px 22px; }
.opt__head { display:flex; gap: 12px; margin-bottom: 14px; }
.opt__pick { font-family: var(--mono); font-size: 12px; letter-spacing:.08em; color:#fff; background: var(--accent);
             min-width: 30px; height: 30px; padding: 0 7px; border-radius: 8px; display:flex; align-items:center;
             justify-content:center; flex: 0 0 auto; }
.opt__name { font-size: 15px; font-weight: 600; }
.opt__note { color: var(--page-dim); font-size: 12.5px; margin-top: 3px; max-width: 74ch; }
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
.yes { color: var(--ok); } .no { color: var(--danger); } .mid { color: var(--warn); }

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
      <p class="eyebrow">2026-10-13 · 选型台 · 不参与构建</p>
      <h1>定时发送：把输入框里的东西定到「今天几点」发</h1>
      <p class="lede">敲好一句话 → 选今天的一个时刻 → 到点自动发出去。三个方案在<b>「排上之后，那条消息放在哪、长什么样」</b>
        上分岔 —— 这是你以后一直要看的那一处；入口、选时弹层、到点行为三案一样，画在第二节。</p>
    </div>
    <div class="toggle">
      <button id="dk" aria-pressed="true">深色</button><button id="lt" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先把事实摆清楚</h3>
    <p><b>三案共用的口径</b>：入口是新加的一颗小时钟（挨着发送键，见第二节）；选时弹层向上弹；
      只能选<b>今天还没到的时刻</b>；到点那一下 = 替你按发送键 —— 走的就是现成的发送路径
      （回合进行中 → 进排队条）；定时只活在<b>这次会话里</b>，与排队条同寿命（关工具窗口/重启即丢）。</p>
    <p><b>场景三张图统一，故意取最坏情况</b>：回合进行中（排队 1 条，发送键此刻是"停止"态）+ 已排 1 条
      ${SCENE.schedTime} 的定时。两条带子同时挂出来长什么样，正是"甲"要过的关；"二"里的入口图取的空闲态。</p>
    <p><b>几何不是手填的</b>：窗口 ${WIN_W}、底区内边距 6/8/8/8（<code>ClaudePanel.kt</code>）、
      卡片圆角 ${CARD_ARC}、发送键直径 ${SEND_D}、附件键 ${ATTACH_BOX} 格、排队条内边距 4/8（<code>QueueStrip.kt</code>）、
      弹层常数 ${ROW}/${HEADER}/${CARD_PAD}/${CARD_GAP}/${ROOT_PAD}（<code>PickerList.kt</code>，与模型弹层同一把尺子）。</p>
    <p><b>颜色不是手填的</b>：转写区底、气泡、工具栏底带（<code>mix(面板底, 标签前景, 0.09)</code>，即
      <code>FOOTER_TINT</code>）都取自 <code>ThemeInjector</code> / <code>styles.css</code> 的真实值；深浅两套都渲染。</p>
  </div>

  <h2 class="sect">一、并排：同一把尺子</h2>
  <p class="sect-note">三张都是 1:1 的真机切片（${WIN_W}px 宽）。同一场景、同一条消息，只有"它待在哪"不同。</p>

  ${bay('甲', '定时条', '消息从框里交出去，挂到输入卡上方一条带子上 —— 排队条的姊妹。',
    mock('A'))}
  ${bay('乙', '输入框预定', '文字留在框里，卡内多一条「今天 18:00 自动发送」的带子；到点自动按发送。',
    mock('B'))}
  ${bay('丙', '待发卡', '消息进转写区，是一条虚着画的"待发气泡"；到点变成真气泡发出去。',
    mock('C'))}

  <h2 class="sect">二、三案共用的两件</h2>
  <p class="sect-note">下面这两件不参与选型 —— 三个方案都长这样，先看一眼确认没有异义。</p>

  <div class="cols">
    <div>
      ${floatOn('', composerCard({ clock: 'marked' }))}
      <p class="figcap"><b>入口</b>：发送键左边多一颗小时钟（图上描了红框）。它是"这条消息晚点发"的动作，
        挨着"现在发"站；有字才可点。位置也可以改成挨着附件键、或干脆不画、右键发送键唤出同一个弹层 ——
        见第四节的微决策。</p>
    </div>
    <div>
      ${floatOn(timePopup('ok'), '')}
      <p class="figcap"><b>选时弹层</b>：向上弹（输入框在窗口底部）。上半"快捷" —— 相对现在算，右侧直接写出
        落到的钟点；下半"指定今天时刻" —— 敲 <code>HH:MM</code> 回车确认。已过的时刻当场标黄（右图）。</p>
    </div>
  </div>
  <div class="cols" style="margin-top:8px">
    <div>${floatOn(timePopup('past'), '')}</div>
    <div class="figcap" style="margin-top:24px"><b>已过时刻的样子</b>：字段描边转黄、右侧给出原因；
      回车不生效。规则：只收"今天、且大于现在"的时刻 —— 你说的是"当天几点"，跨天的需求这版不做
      （快捷里的相对增量若越过零点，右侧会写"明天 01:20"）。</div>
  </div>

  <h2 class="sect">三、逐个看</h2>
  <p class="sect-note">每张图都是上面"并排"里那套的复现；下面列了「好在哪 / 代价 / 实现量 / 适合谁」。</p>

  <div class="grid">
    ${opt('甲', '定时条：消息交出去等', '排上之后输入框立刻空出来写下一句；那条消息挂在输入卡上方一条安静的小带子上，✕ 撤回。',
      mock('A'),
      `<dt>好在哪</dt>
       <dd><ul>
         <li>输入框<b>不被占用</b> —— 定时几个小时开外也不挡你发别的；</li>
         <li>机制全部现成：排版、小字、收折、"2 条起收折"、✕ 撤回，都是排队条（<code>QueueStrip</code>）已经跑熟的那一套；</li>
         <li>槽位固定（排在最上面那条），任何状态下你都知道去哪找它。</li>
       </ul></dd>
       <dt>代价</dt>
       <dd>最坏情况同屏两条带子（图上就是：定时 + 排队）；交出去就<b>不能改了</b> —— 要改就是撤回重排
         （与排队条一样，没有编辑）。</dd>
       <dt>实现量</dt>
       <dd>小～中：排队条的姊妹件 + 一颗时钟键 + 一个弹层 + 一个到点触发的定时器。</dd>
       <dt>适合</dt>
       <dd>大多数时候；定时开得远（>1 小时）的人。</dd>`)}
    ${opt('乙', '输入框预定：框里等', '文字留在输入框，卡内多一条「今天 18:00 自动发送 · 撤回」；到点自动按发送键。',
      mock('B'),
      `<dt>好在哪</dt>
       <dd><ul>
         <li>最贴字面："输入框里的东西"自始至终就在输入框里；</li>
         <li>到点之前<b>还能改</b> —— 改的就是将要发的；</li>
         <li>不占卡外任何空间，上与下的带子一个不多。</li>
       </ul></dd>
       <dt>代价</dt>
       <dd>这一格输入框<b>被占住了</b>：想发别的消息，先得决断它（提前发 / 撤回）；
         空掉输入框 = 定时随之取消，这条规则要专门讲给人听。天生只排得下 1 条。</dd>
       <dt>实现量</dt>
       <dd>中：卡内带子（与附件带同槽，两条带子的排布要理一遍）+ 发送键联动（手动发送 = 提前发）+ 自动发送状态。</dd>
       <dt>适合</dt>
       <dd>定时都很近（半小时内）、且想改到最后一刻的人。</dd>`)}
    ${opt('丙', '待发卡：转写区的幽灵气泡', '消息从框里走，出现在对话流里 —— 一条虚着画的"待发气泡"。',
      mock('C'),
      `<dt>好在哪</dt>
       <dd>在对话流里可见，和消息本身长得一样（虚线 + 淡色表示"还没发出"）；你能一眼看到"它排在对话后面"。</dd>
       <dt>代价</dt>
       <dd><ul>
         <li>转写区在 <b>JCEF 一侧</b>：要给它投一路"待发消息"、回收它的撤回点击、到点还要换成真气泡 —— 跨栈成本三案最高；</li>
         <li>它随对话滚走 —— 想让它一直在眼前就得钉底，钉住之后视觉上<b>又趋同甲</b>（一条悬浮带子）；</li>
         <li>"还没发的东西"进了对话记录，心智上有点混（它还算不算"说过的话"）。</li>
       </ul></dd>
       <dt>实现量</dt>
       <dd>大。</dd>
       <dt>适合</dt>
       <dd>非常在意"在对话里看得见"、且接受更贵实现的人。</dd>`)}
  </div>

  <h2 class="sect">四、取舍与微决策</h2>
  <table class="cmp">
    <tr><th>方案</th><th>输入框</th><th>等待时看得见</th><th>到点前能改</th><th>与排队条同屏</th><th>实现量</th></tr>
    <tr><td>甲 定时条</td><td class="yes">立刻解放</td><td>卡上方一条带子</td><td class="no">不能（撤回重排）</td><td class="mid">两条并排，都安静</td><td class="yes">小～中</td></tr>
    <tr><td>乙 输入框预定</td><td class="no">被占用</td><td>框边一条带子</td><td class="yes">能，改到发前</td><td class="yes">只多卡内一条</td><td class="mid">中</td></tr>
    <tr><td>丙 待发卡</td><td class="yes">立刻解放</td><td>对话流里</td><td class="no">不能</td><td class="mid">钉底后趋同甲</td><td class="no">大</td></tr>
  </table>

  <div class="cols" style="margin-top:26px">
    <div>
      <table class="cmp">
        <tr><th>微决策</th><th>默认（图上画的）</th></tr>
        <tr><td>槽位顺序</td><td>定时条在最上，排队条贴近输入卡（排队更"烫"）；MCP 提示带保持最贴卡</td></tr>
        <tr><td>多条定时</td><td>甲可多条（2 条起收折，同排队条规则）；乙天然 1 条</td></tr>
        <tr><td>跨零点</td><td>相对增量越过零点 → 右侧写"明天 01:20"，照样能用</td></tr>
        <tr><td>持久化</td><td>只活这次会话（与排队条同寿命）；"活过重启"要落盘，另算</td></tr>
        <tr><td>入口位置</td><td>挨发送键（本页画法）；备选：挨附件键 / 右键发送键唤同一弹层（下图）</td></tr>
      </table>
    </div>
    <div>
      ${floatOn(timePopup('ok'), composerCard({ clock: 'none' }))}
      <p class="figcap"><b>入口备选：右键发送键</b> —— 不新增控件，弹层锚在发送键上方；
        代价是"藏起来了"，不右键就永远发现不了。</p>
    </div>
  </div>

  <div class="verdict">
    <h2>我的建议</h2>
    <p><b>甲（定时条）</b>。它是排队条的双胞胎：同一把内边距、同一档小字、同一个 ✕、同一套收折规则 ——
      你早就认识这条带子了，学习成本几乎为零；机制现成也意味着<b>实现最省、最不容易出新毛病</b>。
      输入框立刻解放这一条，在你这种"一天里聊很多轮"的用法下比看起来重要。</p>
    <p>乙只在一种情况下更值：你定时几乎都是<b>很近的时刻</b>（半小时内），又想在最后一刻改词 ——
      那时"框被占住"的代价还没痛起来。丙不建议做：贵，而且钉住可见之后就是甲。</p>
    <p>看完回一个字母：<code>甲</code> / <code>乙</code> / <code>丙</code>；
      微决策有要改的一起说（比如"入口换右键"）。</p>
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
    let name = 'scheduled-send-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'scheduled-send-view-light.html')
      writeFileSync(target, page('light'))
      name = 'scheduled-send-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1500,6200',
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
