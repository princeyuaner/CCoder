#!/usr/bin/env node
/**
 * 「MCP 还没连完」挪到输入框右下角 · 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-14 用户："我想把这个（MCP 连接中那条带子）放到输入框的右下角，
 * 当 mcp 未连接完成时如果发送了聊天信息，那么弹框让用户选择要不要继续发送，
 * 先出效果图放浏览器给我选择"。
 *
 * 两个决定分开选，别的都现成：
 *   一、**常驻记号**长什么样、蹲在右下角哪儿 —— 甲 静字 / 乙 徽章 / 丙 单点 /
 *       丁 卡内右下小字行；
 *   二、**发送前的弹框**弹成什么样 —— A 贴卡上缘的锚定浮层 / B 居中模态 /
 *       C 卡上整条浮层（旧带子的幽灵版）。
 *
 * 三条保真规则（照抄 tools/scheduled-send-view.mjs 的做法）：
 *
 *   1. 几何不是手填的：窗口 560（工具窗口默认宽）、卡片圆角 16（CARD_CORNER_ARC）、
 *      卡内边距 4/6/5/6、发送键直径 29（RoundSendButton.DIAMETER）、附件键 22 格
 *      （AttachButton.BOX_SIDE）、底区内边距 6/8/8/8（ClaudePanel.kt）、
 *      底带 = mix(面板底, 标签前景, 0.09)（FOOTER_TINT）；
 *   2. 颜色不是手填的：琥珀/红/绿取 `warningColor / dangerColor / okColor` 的真实值
 *      （浅色 #D84315 · 深色 #FF8A65 那一对，已用 JBColor 在真机上核过哪个论元对哪套主题）；
 *   3. 深色 / 浅色两套都渲染 —— 琥珀小字在浅底上够不够看得见，换一套才看得出来。
 *
 * 口径（写进页头）：记号的可见门槛、失败可点、落定绿 6 秒、发送前的拦截判据
 * （恢复的 / 长会话）全部沿用现状；这次动的只是"它长在哪儿、确认怎么弹"。
 *
 * 用法：
 *   node tools/mcp-corner-view.mjs --open          # 生成并用默认浏览器打开
 *   node tools/mcp-corner-view.mjs --shot          # 生成并截图（深色）
 *   node tools/mcp-corner-view.mjs --shot=light    # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'mcp-corner-view.html')

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
    // toneColor：okColor / warningColor / dangerColor 的深色支
    ok: '#5fad65', warn: '#ff8a65', danger: '#db5c5c',
    labelFg: '#dfe1e5',        // 用来算底带的"标签前景"
  },
  light: {
    pageBg: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageLine: '#e0e2e7',
    pageCard: '#ffffff', code: '#f2f3f5',
    panel: '#f7f8fa',
    transcript: '#ffffff',
    text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', accentText: '#3574f0', focus: '#3574f0',
    ok: '#3d8b43', warn: '#d84315', danger: '#c0392b',
    labelFg: '#000000',
  },
}

/** 工具栏底带 = mix(面板底, 标签前景, 0.09)，与 Composer.kt 的 FOOTER_TINT 同源。 */
const bandOf = (t) => mix(t.panel, t.labelFg, 0.09)

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

const WIN_W = 560        // 工具窗口宽（splitter / 定时发送两张选型台同一常数）
const NARROW_W = 420    // 最窄体检宽度（"面板约 420px"是本仓文案里一直用的那档）
const CARD_ARC = 16     // CARD_CORNER_ARC
const SEND_D = 29       // RoundSendButton.DIAMETER
const ATTACH_BOX = 22   // AttachButton.BOX_SIDE
const ROW = 30          // PickerList.ROW_HEIGHT（弹层尺子）
const CARD_PAD = 5      // 弹层卡内边距
const CARD_GAP = 8      // 弹层卡距

/** 场景（各图统一，取最坏情况：长模型名是这台机器上的真名）。 */
const SCENE = {
  tail: '…3 个文件都改完了，测试全绿。',
  typed: '帮我把今天的发布记录整理一下',
  model: 'deepseek-v4.1-flash[1m]',
  mode: '标准',
  effort: '思考·高',
  placeholder: '@ 文件 · # 符号 · / 命令 · Enter 发送 · Shift+Enter 换行',
  connecting: 'codegraph、code-review-graph',
}

/** 记号的三种状态文案（short = 工具栏里的；full = 整句（丁 与悬停用））。 */
const MK = {
  pending: { short: 'MCP 连接中', full: 'MCP 连接中 · 现在发送可能不命中缓存' },
  failed: { short: 'MCP 连接失败', tail: ' · 点开设置', full: 'MCP 连接失败 · 点开设置' },
  settled: { short: 'MCP 已连接', full: 'MCP 已连接 · 现在发送照常命中缓存' },
}

// ---------------------------------------------------------------- 小图标

const svgPaperclip = () => `
  <svg class="gly" width="17" height="17" viewBox="0 0 12 12" fill="none" aria-hidden="true"
       style="transform:rotate(-45deg)">
    <path d="M8.4 2.8 L8.4 7.6 A2.4 2.4 0 0 1 3.6 7.6 L3.6 3.9 A1.15 1.15 0 0 1 5.9 3.9 L5.9 7.4 A0.5 0.5 0 0 1 4.9 7.4 L4.9 4.2"
          stroke="currentColor" stroke-width="1.15" stroke-linecap="round"/>
  </svg>`

const svgClock = (size = 12, sw = 1.35) => `
  <svg class="gly" width="${size}" height="${size}" viewBox="0 0 12 12" fill="none" aria-hidden="true">
    <circle cx="6" cy="6" r="4.7" stroke="currentColor" stroke-width="${sw}"/>
    <path d="M6 6 L6 3.4 M6 6 L8.1 7.2" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round"/>
  </svg>`

const svgArrowUp = () => `
  <svg width="13" height="13" viewBox="0 0 13 13" fill="none" aria-hidden="true">
    <path d="M6.5 10.4 L6.5 2.6 M6.5 2.6 L3.7 5.4 M6.5 2.6 L9.3 5.4"
          stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>`

// ---------------------------------------------------------------- 记号（四种形态）

const mkTone = (state) => `s-${state}`

/** 甲/乙/丙 的短句。`tail`（失败态的「点开设置」）单独染链接色。 */
const mkShort = (state) => {
  const m = MK[state]
  const tail = m.tail ? `<span class="lnk">${m.tail}</span>` : ''
  return `${m.short}${tail}`
}

/**
 * 记号本体。kind：a1 静字 / a2 徽章 / a3 单点 / a4 卡内小字行。
 * 返回空串 = 该状态在该方案下不画（目前没有这种组合，留个口子）。
 */
const markerHtml = (kind, state) => {
  if (!kind) return ''
  const s = mkTone(state)
  if (kind === 'a1') return `<span class="mk mk--txt ${s}">${mkShort(state)}</span>`
  if (kind === 'a2') return `<span class="mk mk--pill ${s}"><i class="mk__dot"></i>${mkShort(state)}</span>`
  if (kind === 'a3') return `<span class="mk mk--dot ${s}"><i class="mk__dot"></i></span>`
  if (kind === 'a4') {
    // 丁 的整句：字安静、色由左边那颗点扛。失败时「点开设置」染链接色。
    const inner = state === 'pending'
      ? 'MCP 连接中 · 现在发送可能不命中缓存'
      : state === 'failed'
        ? 'MCP 连接失败<span class="lnk"> · 点开设置</span>'
        : 'MCP 已连接 · 现在发送照常命中缓存'
    return `<div class="mkrow ${s}"><i class="mk__dot"></i><span>${inner}</span></div>`
  }
  return ''
}

// ---------------------------------------------------------------- 输入卡与底区

/** 工具栏那一行。marker=记号 html（null 不画）；leftClip=窄窗体检用（把左排切一半）。 */
const toolbarRow = ({ marker = null, leftClip = false } = {}) =>
  `<div class="foot">
     <span class="ic" title="附件">${svgPaperclip()}</span>
     <span class="labs ${leftClip ? 'is-clipped' : ''}">
       <span class="lab">${SCENE.model} <span class="caret">▾</span></span>
       <span class="lab">${SCENE.mode} <span class="caret">▾</span></span>
       <span class="lab">${SCENE.effort} <span class="caret">▾</span></span>
     </span>
     <span class="right">
       ${marker || ''}
       <span class="ic ic--clock" title="定时发送">${svgClock()}</span>
       <span class="send" title="发送">${svgArrowUp()}</span>
     </span>
   </div>`

/** 输入卡。input 给了就画那行字，否则画占位串；mkRow = 丁 的卡内小字行。 */
const composerCard = ({ input = null, marker = null, mkRow = null, leftClip = false } = {}) =>
  `<div class="card">
     <div class="card__input">${input ? `<span class="txt">${input}</span>` : `<span class="ph">${SCENE.placeholder}</span>`}</div>
     ${mkRow || ''}
     ${toolbarRow({ marker, leftClip })}
   </div>`

/** 一套完整模拟：转写区切片 + 底区（输入卡）。 */
const sim = ({ kind = null, state = 'pending', input = null, width = WIN_W } = {}) => {
  // 丁 的记号是"卡内一行"（mkRow），其余三种住在工具栏右端（marker）
  const isRow = kind === 'a4'
  return `<div class="sim" style="width:${width}px">
     <div class="tr"><div class="tr__line">${SCENE.tail}</div></div>
     <div class="bottom">
       ${composerCard({
         input,
         marker: isRow ? null : markerHtml(kind, state),
         mkRow: isRow ? markerHtml(kind, state) : null,
       })}
     </div>
   </div>`
}

/** 现状那条带子：pending 原样 / confirm 展开态。cap 是那行小注（两个状态同一条带子）。 */
const oldBar = (confirm = false) =>
  `<div class="oldcap">${confirm ? '按发送那一下 —— 就地确认（本次要改掉的就是它）' : '常态 —— 一条全宽的带子'}</div>
   <div class="oldbar ${confirm ? 'is-confirm' : ''}">
     <span class="oldbar__txt">${confirm ? 'MCP 还没连完 —— 这一发会全价重算' : 'MCP 连接中 · 现在发送可能不命中缓存'}</span>
     ${confirm ? '<span class="oldbar__row"><span class="btn">等它</span><span class="btn">照发</span><span class="chk"><i class="chk__box"></i>本会话不再问</span></span>' : ''}
   </div>`

/** 悬停提示（给 丙 画的：一颗点也得能问出全句）。 */
const hoverTip = () => `
  <div class="tipwrap">
    <div class="tip">
      <div>${MK.pending.full}</div>
      <div class="tip__sub">正在连：${SCENE.connecting}</div>
    </div>
    <div class="tip__gap"></div>
    <div class="sim" style="width:${WIN_W}px">
      <div class="bottom">
        ${composerCard({ marker: markerHtml('a3', 'pending') })}
      </div>
    </div>
  </div>`

// ---------------------------------------------------------------- 弹框（三个方案）

/** 弹框正文（A/B/C 共用的一组行，容器各不相同）。 */
const ppBody = () => `
  <div class="pp__t">MCP 还没连完 —— 这一发会全价重算</div>
  <div class="pp__n">正在连：${SCENE.connecting}</div>
  <div class="pp__row">
    <span class="btn btn--focus">等它</span>
    <span class="btn">照发</span>
    <span class="chk"><i class="chk__box"></i>本会话不再问</span>
  </div>`

/** A：锚定浮层。贴输入卡上缘弹出、右缘对齐卡片；不盖住输入框。 */
const popupA = () => `
  <div class="floatfig">
    <div class="pp pp--a">${ppBody()}</div>
    <div class="floatgap"></div>
    ${sim({ kind: 'a4', state: 'pending', input: SCENE.typed })}
  </div>`

/** B：居中模态。整窗压暗、正中一张框。 */
const popupB = () => `
  <div class="scrimwrap" style="width:${WIN_W}px">
    ${sim({ kind: 'a4', state: 'pending', input: SCENE.typed })}
    <div class="scrim"></div>
    <div class="modal">
      <div class="modal__t">MCP 还没连完</div>
      <div class="modal__b">这一发会全价重算。<br>正在连：${SCENE.connecting}</div>
      <div class="modal__f">
        <span class="chk"><i class="chk__box"></i>本会话不再问</span>
        <span class="spring"></span>
        <span class="btn btn--focus">等它</span>
        <span class="btn">照发</span>
      </div>
    </div>
  </div>`

/** C：卡上整条浮层。旧带子的位置、整条浮出来，一句整话 + 动作同行（名字进悬停，同旧带子）。 */
const popupC = () => `
  <div class="sim" style="width:${WIN_W}px">
    <div class="tr"><div class="tr__line">${SCENE.tail}</div></div>
    <div class="bottom">
      <div class="popbar">
        <span class="popbar__txt">MCP 还没连完 —— 这一发会全价重算</span>
        <span class="spring"></span>
        <span class="btn btn--focus">等它</span>
        <span class="btn">照发</span>
        <span class="chk"><i class="chk__box"></i>本会话不再问</span>
      </div>
      ${composerCard({ input: SCENE.typed, mkRow: markerHtml('a4', 'pending') })}
    </div>
  </div>`

// ---------------------------------------------------------------- 三态矩阵的小切片

/** 右端裁片：…（左排的尾巴）+ 记号 + 时钟 + 发送。丁 多一行小字。 */
const crop = (kind, state) => {
  const mk = markerHtml(kind, state)
  const row = `
    <div class="crop__row">
      <span class="crop__ell">…</span>
      <span class="lab">${SCENE.effort} <span class="caret">▾</span></span>
      <span class="crop__right">${kind === 'a4' ? '' : mk}<span class="ic ic--clock">${svgClock()}</span><span class="send">${svgArrowUp()}</span></span>
    </div>`
  return `<div class="crop ${kind === 'a4' ? 'crop--tall' : ''}">
      ${kind === 'a4' && state !== 'hidden' ? mk : ''}
      ${row}
    </div>`
}

// ---------------------------------------------------------------- 页面骨架

const bay = (letter, name, note, html, muted = false) =>
  `<div class="bay">
     <div class="bay__cap"><b class="${muted ? 'is-old' : ''}">${letter}</b>${name}<span class="bay__note">${note}</span></div>
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
<title>MCP 提示挪进输入框右下角 · 选型台</title>
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
.spring { flex: 1; }

.masthead { display:flex; align-items:flex-end; justify-content:space-between; gap:32px;
            flex-wrap:wrap; padding-bottom: 22px; border-bottom: 1px solid var(--page-line); }
h1 { font-family: var(--serif); font-size: 34px; font-weight: 600; margin: 0 0 10px; line-height:1.15; }
.lede { color: var(--page-dim); max-width: 84ch; margin: 0; font-size: 14.5px; }
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
.brief p { margin: 0 0 8px; color: var(--page-dim); font-size: 13.5px; max-width: 108ch; }
.brief p:last-child { margin-bottom: 0; }
.brief code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
.brief b { color: var(--page-fg); }

h2.sect { font-family: var(--serif); font-size: 25px; font-weight: 600; margin: 54px 0 4px; }
.sect-note { color: var(--page-dim); font-size: 13.5px; margin: 0 0 22px; max-width: 104ch; }
.sect-note b { color: var(--page-fg); }

/* ---- 模拟块：真机几何 ---- */
.bay { width: ${WIN_W}px; margin: 0 auto 34px; }
.bay__cap { font-size: 14px; margin: 0 0 10px; color: var(--page-fg); }
.bay__cap b { font-family: var(--mono); font-size: 13px; letter-spacing:.06em; color:#fff;
              background: var(--accent); border-radius: 6px; padding: 2px 8px; margin-right: 10px; }
.bay__cap b.is-old { background: var(--page-dim); }
.bay__note { color: var(--page-dim); font-size: 12.5px; margin-left: 10px; }

.sim { border:1px solid var(--page-line); border-radius: 10px; overflow: hidden; margin: 0 auto; }
.tr { background: var(--transcript); padding: 10px 12px 8px; min-height: 46px; display:flex;
      flex-direction:column; justify-content:flex-end; gap: 6px; }
.tr__line { font-size: 12.5px; color: var(--dim); }
.bottom { background: var(--panel); padding: 6px 8px 8px; }

/* 输入卡（圆角 16、描边 1px、底带 = mix 0.09） */
.card { border:1px solid var(--border); border-radius: ${CARD_ARC}px; overflow:hidden; }
.card__input { padding: 6px 8px 4px; min-height: 34px; font-size: 13px; }
.card__input .txt { color: var(--text); }
.card__input .ph { color: var(--dim); }
.foot { background: var(--band); padding: 2px 6px; display:flex; align-items:center; min-height: 33px; }
.ic { color: var(--dim); display:flex; align-items:center; width: ${ATTACH_BOX}px; justify-content:center; }
.labs { display:flex; align-items:center; margin-left: 8px; gap: 10px; }
.lab { font-size: 13px; color: var(--dim); white-space:nowrap; }
.labs.is-clipped { max-width: 200px; overflow: hidden; }
.caret { font-size: 10px; }
.right { margin-left:auto; display:flex; align-items:center; gap: 6px; }
.send { width: ${SEND_D}px; height: ${SEND_D}px; border-radius: 50%; background: var(--focus);
        display:flex; align-items:center; justify-content:center; flex: 0 0 auto; }
.ic--clock { width: auto; padding: 0 1px; }

/* 现状那条带子（2026-10-13 的样子，作对照） */
.oldcap { font-size: 11px; color: var(--dim); margin: 2px 0 3px; }
.oldbar { border:1px solid var(--warn); border-radius: 7px; padding: 3px 8px 5px; margin-bottom: 5px;
          font-size: 12px; display:flex; align-items:baseline; gap: 8px; flex-wrap: wrap; }
.oldbar.is-confirm { padding-bottom: 5px; }
.oldbar__txt { color: var(--text); }
.oldbar__row { display:flex; align-items:center; gap: 6px; }

/* ---- 记号的四种形态 ---- */
.s-pending { --tone: var(--warn); }
.s-failed  { --tone: var(--danger); }
.s-settled { --tone: var(--ok); }
.mk__dot { width:6px; height:6px; border-radius:50%; background: var(--tone); display:inline-block; flex: 0 0 auto; }
.mk { display:inline-flex; align-items:center; gap: 5px; white-space: nowrap; }
.mk--txt { font-size: 12px; color: var(--tone); }
.mk--pill { font-size: 11.5px; color: var(--tone); border:1px solid color-mix(in srgb, var(--tone) 38%, transparent);
            background: color-mix(in srgb, var(--tone) 13%, var(--panel)); border-radius: 999px; padding: 0 8px 1px; }
.mk--dot { width: 14px; height: 14px; justify-content: center; }
.mkrow { display:flex; justify-content:flex-end; align-items:baseline; gap: 5px;
         padding: 0 8px 4px; font-size: 12px; color: var(--dim); }
.mkrow .mk__dot { position: relative; top: -1px; }
.mkrow .lnk, .mk--txt .lnk { color: var(--focus); }

/* ---- 悬停提示（丙 的搭档图）---- */
.tipwrap { display:flex; flex-direction:column; width: ${WIN_W}px; margin: 0 auto; }
.tip { align-self: flex-end; margin-right: 46px; background: color-mix(in srgb, var(--panel) 92%, var(--text));
       border:1px solid var(--border); border-radius: 6px; padding: 5px 9px; font-size: 12px; color: var(--text);
       box-shadow: 0 3px 10px rgba(0,0,0,.28); }
.tip__sub { color: var(--dim); font-size: 11.5px; }
.tip__gap { height: 5px; }

/* ---- 弹框 ---- */
.floatfig { display:flex; flex-direction:column; width: ${WIN_W}px; margin: 0 auto; }
.floatgap { height: 5px; }
.pp { align-self: flex-end; background: var(--panel); border:1px solid var(--border); border-radius: 12px;
      padding: ${CARD_PAD + 3}px ${CARD_PAD + 5}px; box-shadow: 0 6px 18px rgba(0,0,0,.30); }
.pp--a { width: 330px; margin-right: 8px; }
.pp__t { font-size: 13px; color: var(--text); }
.pp__n { font-size: 11.5px; color: var(--dim); margin-top: 2px; }
.pp__row { display:flex; align-items:center; gap: 8px; margin-top: 9px; }

.btn { font-size: 12.5px; color: var(--text); border:1px solid var(--border); border-radius: 6px;
       padding: 2px 12px 3px; background: color-mix(in srgb, var(--text) 7%, var(--panel)); white-space: nowrap; }
.btn--focus { border-color: var(--focus); box-shadow: 0 0 0 1px var(--focus) inset; }
.chk { font-size: 12px; color: var(--dim); display:inline-flex; align-items:center; gap: 5px; margin-left: 4px; }
.chk__box { width: 12px; height: 12px; border:1px solid var(--border); border-radius: 3px; background: var(--panel);
            display:inline-block; position: relative; top: 1px; }

/* B：居中模态 */
.scrimwrap { position: relative; margin: 0 auto; }
.scrim { position:absolute; inset:0; background: rgba(0,0,0, .38); border-radius: 10px; }
.modal { position:absolute; left:50%; top:50%; transform: translate(-50%,-50%); width: 352px;
         background: var(--panel); border:1px solid var(--border); border-radius: 12px; padding: 12px 14px 11px;
         box-shadow: 0 10px 30px rgba(0,0,0,.45); }
.modal__t { font-size: 13.5px; font-weight: 600; color: var(--text); }
.modal__b { font-size: 12px; color: var(--dim); margin-top: 5px; line-height: 1.55; }
.modal__f { display:flex; align-items:center; gap: 8px; margin-top: 12px; }
.modal__f .chk { margin-left: 0; }

/* C：卡上整条浮层 */
.popbar { display:flex; align-items:center; gap: 8px; border:1px solid var(--warn); border-radius: 7px;
          background: var(--panel); padding: 4px 8px; margin-bottom: 5px; font-size: 12px; flex-wrap: wrap; }
.popbar__txt { color: var(--text); }
.popbar .dimmed { color: var(--dim); }

/* ---- 三态矩阵 ---- */
.mx { display:grid; grid-template-columns: 120px 1fr 1fr 1fr; gap: 12px 14px; align-items:center;
      width: min(1100px, 100%); margin: 0 auto; }
.mx__h { font-family: var(--mono); font-size: 10.5px; letter-spacing:.1em; text-transform:uppercase; color: var(--page-dim); text-align:center; }
.mx__row { font-size: 13px; color: var(--page-fg); }
.mx__row b { font-family: var(--mono); font-size: 12px; color:#fff; background: var(--accent);
             border-radius: 6px; padding: 1px 7px; margin-right: 8px; }
.crop { background: var(--panel); border:1px solid var(--page-line); border-radius: 10px; padding: 7px 10px;
        min-height: 46px; display:flex; flex-direction:column; gap: 3px; }
.crop--tall { min-height: 62px; }
.crop__row { display:flex; align-items:center; gap: 8px; }
.crop__ell { color: var(--dim); font-size: 12px; }
.crop__right { margin-left:auto; display:flex; align-items:center; gap: 6px; }
.crop .ic, .crop .send { width: ${ATTACH_BOX}px; }
.crop .send { width: ${SEND_D}px; height: ${SEND_D}px; }
.crop .mkrow { padding-right: 0; }

/* ---- 窄窗体检 ---- */
.narrow { display:flex; gap: 26px; justify-content:center; flex-wrap:wrap; }
.narrow figure { margin: 0; }
.narrow figcaption { font-size: 12.5px; color: var(--page-dim); margin-top: 8px; max-width: 420px; }
.narrow figcaption b { color: var(--page-fg); }

/* ---- 两栏排版 / 逐个看 ---- */
.cols { display:grid; grid-template-columns: 1fr 1fr; gap: 30px; align-items:start; }
@media (max-width: 1200px) { .cols { grid-template-columns: 1fr; } }
.figcap { font-size: 12.5px; color: var(--page-dim); margin: 10px 0 0; max-width: 72ch; }
.figcap b { color: var(--page-fg); }

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
.verdict p { color: var(--page-dim); margin: 0 0 10px; max-width: 96ch; }
.verdict b { color: var(--page-fg); }
.verdict code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
</style>
</head>
<body>
<div class="wrap">
  <div class="masthead">
    <div>
      <p class="eyebrow">2026-10-14 · 选型台 · 不参与构建</p>
      <h1>MCP 缓存提示：挪进输入框右下角，发送前改成弹框</h1>
      <p class="lede">那条「MCP 连接中 · 现在发送可能不命中缓存」的带子从输入框上方撤掉，换成<b>右下角一个常驻小记号</b>；
        按发送而 MCP 还没连完时，<b>弹一次框</b>让你选「照发 / 等它」。两个决定分开选 ——
        <b>记号长什么样</b>（甲/乙/丙/丁）、<b>弹框弹成什么样</b>（A/B/C）。</p>
    </div>
    <div class="toggle">
      <button id="dk" aria-pressed="true">深色</button><button id="lt" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先把事实摆清楚</h3>
    <p><b>这条提示在管什么</b>：重启 / 恢复长会话后，MCP 是后台连的，工具表稳下来之前的几发整段前缀不命中 ——
      实测那两发约 <b>17 万 token 全价重算</b>（2026-10-13，网关账单与插件账本双对账）。它的语义是
      「<b>工具表此刻稳定</b>」，掉线重连会再破，所以是状态、不是一次性的开关。</p>
    <p><b>带子撤掉后，它原来的五件事不丢</b>：① 常驻说"现在发送会亏"（琥珀）；② 失败 → 可点开 MCP 设置页（红）；
      ③ 落定 → 绿 6 秒自己走；④ 按发送那一发拦一次；⑤ 拦不拦的门槛不动（只对恢复的 / 上下文 ≥3 万 token 的会话开口）。</p>
    <p><b>旧规矩在这里翻掉了</b>：上一版刻意<b>不弹框</b>（"确认不是装饰、但也不弹框"）。这次按你的要求改弹框 ——
      所以问题只剩"弹得轻还是重"，A/B/C 三案就是三档轻重。</p>
    <p><b>几何不是手填的</b>：窗口 ${WIN_W}、卡片圆角 ${CARD_ARC}（<code>CARD_CORNER_ARC</code>）、发送键直径 ${SEND_D}、
      附件键 ${ATTACH_BOX} 格、底带 <code>mix(面板底, 标签前景, 0.09)</code>（<code>FOOTER_TINT</code>）。
      <b>颜色不是手填的</b>：琥珀/红/绿取 <code>warningColor / dangerColor / okColor</code> 的真实值
      （浅色 #D84315 · 深色 #FF8A65 那一对）。场景故意取最坏：模型名用真名 <code>deepseek-v4.1-flash[1m]</code>。</p>
  </div>

  <h2 class="sect">一、记号放哪：输入框右下角的四个方案</h2>
  <p class="sect-note">五张 1:1 切片（${WIN_W}px），同一场景：恢复的长会话、MCP 连接中、输入框空着。
    第一张是现在的样子，作对照；后四张是候选。眼熟的东西都不动 —— 附件、三个标签、时钟、发送键。</p>

  ${bay('旧', '现状：卡上方那条带子', '五件事它一件不落，但也一直占着输入框上方那格 —— 这就是要挪走的原因。',
    `<div class="sim" style="width:${WIN_W}px">
       <div class="tr"><div class="tr__line">${SCENE.tail}</div></div>
       <div class="bottom">${oldBar(false)}${oldBar(true)}${composerCard()}</div>
     </div>`, true)}
  ${bay('甲', '静字·工具栏右端', '时钟键左边一句琥珀小字；全句进悬停。失败时整颗可点。', sim({ kind: 'a1' }))}
  ${bay('乙', '徽章·工具栏右端', '同一个位置，外面加一层淡琥珀胶囊 —— 一行里最显眼的做法。', sim({ kind: 'a2' }))}
  ${bay('丙', '单点·工具栏右端', '只剩一颗 6px 的点，全句进悬停（下图）。最安静，也最不容易被读懂。', sim({ kind: 'a3' }))}
  ${bay('丁', '小字行·卡内右下', '卡内右下、工具栏上方一行右对齐的小字，整句都在；颜色由一颗小点扛，字保持安静。', sim({ kind: 'a4' }))}

  <h2 class="sect">二、丙 的搭档：一颗点也得能问出全句</h2>
  <p class="sect-note">悬停 500ms 出平台提示（同 IDE 里别处的提示）：全句 + 正在连的名字。甲/乙 的悬停是同一张。</p>
  <div class="cols" style="align-items:center">
    <div>${hoverTip()}</div>
    <p class="figcap">左右两组其实是一件事：上面是提示、下面是它长在哪儿。<b>丙 的代价</b>：失败态那颗点也要点开设置 ——
      6px 的目标太小，真要选丙，失败时得放大成带「!」的圆点才点得中。<b>甲/乙/丁 没有这个问题</b>：文字本身就是点击目标。</p>
  </div>

  <h2 class="sect">三、三个状态：连接中 / 失败 / 落定</h2>
  <p class="sect-note">颜色语言沿用现状（琥珀待连 · 红失败 · 绿落定 6 秒自己走）。甲/乙/丙 <b>字色染状态色</b>；
    丁 <b>字色保持安静、由左侧小点扛色</b> —— 一整句琥珀色在输入框里太像报错。</p>
  <div class="mx">
    <div class="mx__h"></div><div class="mx__h">连接中（琥珀）</div><div class="mx__h">失败（红 · 可点）</div><div class="mx__h">落定（绿 · 6 秒走）</div>
    <div class="mx__row"><b>甲</b>静字</div>${crop('a1', 'pending')}${crop('a1', 'failed')}${crop('a1', 'settled')}
    <div class="mx__row"><b>乙</b>徽章</div>${crop('a2', 'pending')}${crop('a2', 'failed')}${crop('a2', 'settled')}
    <div class="mx__row"><b>丙</b>单点</div>${crop('a3', 'pending')}${crop('a3', 'failed')}${crop('a3', 'settled')}
    <div class="mx__row"><b>丁</b>小字行</div>${crop('a4', 'pending')}${crop('a4', 'failed')}${crop('a4', 'settled')}
  </div>

  <h2 class="sect">四、窄窗体检（最窄档 · 长模型名）</h2>
  <p class="sect-note">本仓文案里一直把"面板约 420px"当最窄档；模型名用的是你这台机器上的真名。
    工具栏一行里左排（附件 + 三个标签）本身就快占满 —— 甲/乙/丙 再往里塞字，挤的就是它。三张图同一把尺子。</p>
  <div class="narrow">
    <figure>
      ${`<div class="sim" style="width:${NARROW_W}px">
         <div class="bottom">${composerCard({ marker: markerHtml('a1', 'pending') })}</div>
       </div>`}
      <figcaption><b>甲/乙/丙 @420</b>：恰好挤下 —— 记号与「思考·高」只剩一口气（图上已贴住）。</figcaption>
    </figure>
    <figure>
      ${`<div class="sim" style="width:390px">
         <div class="bottom">${composerCard({ marker: markerHtml('a1', 'pending'), leftClip: true })}</div>
       </div>`}
      <figcaption><b>再窄 30px（390）</b>：左排先被切（图上「思考·高」被截）。要选甲/乙/丙，就得再定一条"让位规则" ——
        比如窄窗时把字退成点。多一条规则，多一处会忘。</figcaption>
    </figure>
    <figure>
      ${`<div class="sim" style="width:${NARROW_W}px">
         <div class="bottom">${composerCard({ marker: null, mkRow: markerHtml('a4', 'pending') })}</div>
       </div>`}
      <figcaption><b>丁 @420</b>：不参与抢宽 —— 整句约 210px，右对齐放得下，工具栏那一行一个像素不动。</figcaption>
    </figure>
  </div>

  <h2 class="sect">五、发送前的弹框：三个方案</h2>
  <p class="sect-note">场景：消息已经敲好在框里，回车那一下 MCP 还没连完 —— 弹框拦住这一发。三案的动作与文案一样
    （结论一句 + 等它 · 照发 · 本会话不再问）；正在连的名字：A/B 写得下就写，C 进悬停（同旧带子）。
    分岔在<b>弹成什么样、盖住什么</b>。图里右下角的记号按丁画（记号选哪个不影响这题）。</p>

  <div class="grid">
    ${opt('A', '锚定浮层：贴卡片上缘，右对齐', '浮层贴着输入卡上缘弹出、右缘与卡片对齐 —— 不盖住你正要发的那句话。',
      popupA(),
      `<dt>好在哪</dt>
       <dd><ul>
         <li><b>不遮输入框</b>：那句"要花冤枉钱的话"还看得见；</li>
         <li>机制现成：模型 / 权限 / 思考三个弹层同一套（<code>showTogglePopup</code>），只补一条"右缘对齐卡片"；</li>
         <li>点外面就收（= 等它），鼠标离触发点最近。</li>
       </ul></dd>
       <dt>代价</dt>
       <dd>会盖住转写区最底下几行 —— 弹层都这样，可接受。</dd>
       <dt>实现量</dt>
       <dd>小。</dd>`)}
    ${opt('B', '居中模态：把整窗压暗', '最不容错过的做法；代价是分量也最重，像一个系统级决定。',
      popupB(),
      `<dt>好在哪</dt>
       <dd>绝不会看漏；不用讨论"该锚在哪"。</dd>
       <dt>代价</dt>
       <dd><ul>
         <li>盖掉整个工具窗口 —— 而这事只关于"这一发"；</li>
         <li>要先点"确定"才能看后面的对话，回合里的流全停在那儿等它；</li>
         <li>新的对话框类 + 模态焦点，实现三案最重。</li>
       </ul></dd>
       <dt>实现量</dt>
       <dd>中。</dd>`)}
    ${opt('C', '卡上整条浮层：旧带子的幽灵版', '弹层占满卡片宽、贴卡上缘一条 —— 一句话 + 三个动作，什么都不盖。',
      popupC(),
      `<dt>好在哪</dt>
       <dd><ul>
         <li>整句话 + 动作一行放齐，名字进悬停（同旧带子的做法）；</li>
         <li><b>什么都不盖</b>：卡、转写区都完整；</li>
         <li>和旧带子同一位置 —— 你昨天刚验收过它。</li>
       </ul></dd>
       <dt>代价</dt>
       <dd>它看起来就是那条带子回来了（只是来得晚）；"一条带子"的视觉分量和"就地问一句"不成比例。</dd>
       <dt>实现量</dt>
       <dd>小。</dd>`)}
  </div>

  <h2 class="sect">六、三案共用的口径（不参与选型）</h2>
  <p class="sect-note">下面这几条三个方案一个样，先看一眼确认没有异义。</p>
  <table class="cmp">
    <tr><th>规矩</th><th>口径</th></tr>
    <tr><td>焦点默认</td><td>落「等它」（蓝框那颗）—— 你刚按的是回车，第二下回车不该把钱花出去</td></tr>
    <tr><td>Esc / 点框外</td><td>= 等它，都不发送</td></tr>
    <tr><td>本会话不再问</td><td>勾了直接发（本会话）—— 换会话复位，同现状</td></tr>
    <tr><td>照发的重入许可</td><td>只对那一发有效；下一按该问还问</td></tr>
    <tr><td>输入框内容</td><td>拦下来时原样不动（清空发生在"真的发出去"之后）</td></tr>
    <tr><td>弹框开着时</td><td>右下角记号还在（它就是这个问题本身）；MCP 落定则弹框自动收回</td></tr>
  </table>

  <h2 class="sect">七、取舍与微决策</h2>
  <table class="cmp">
    <tr><th>记号</th><th>位置</th><th>信息量</th><th>窄窗 ${NARROW_W}</th><th>失败可点</th><th>实现量</th></tr>
    <tr><td>甲 静字</td><td>工具栏右端</td><td class="mid">只剩状态词（全句在悬停）</td><td class="mid">恰好挤下 · 更窄就切</td><td class="yes">文字可点</td><td class="yes">小</td></tr>
    <tr><td>乙 徽章</td><td>工具栏右端</td><td class="mid">同甲</td><td class="mid">同甲</td><td class="yes">文字可点</td><td class="yes">小</td></tr>
    <tr><td>丙 单点</td><td>工具栏右端</td><td class="no">悬停才有</td><td class="yes">同甲（点最省）</td><td class="no">目标太小 · 要放大</td><td class="yes">最小</td></tr>
    <tr><td>丁 小字行</td><td>卡内右下</td><td class="yes">整句都在</td><td class="yes">不参与</td><td class="yes">整行可点</td><td class="yes">小</td></tr>
  </table>

  <table class="cmp">
    <tr><th>弹框</th><th>盖住什么</th><th>会不会看漏</th><th>鼠标距离</th><th>实现量</th><th>感觉</th></tr>
    <tr><td>A 锚定浮层</td><td class="yes">只掠到转写区底几行</td><td class="mid">中</td><td class="yes">贴着触发点</td><td class="yes">小 · 机制现成</td><td>轻 · 就地问一句</td></tr>
    <tr><td>B 居中模态</td><td class="no">整个工具窗口</td><td class="yes">不可能</td><td class="no">要横穿半屏</td><td class="no">中 · 新对话框类</td><td>重 · 系统级决定</td></tr>
    <tr><td>C 卡上整条</td><td class="yes">什么都不盖</td><td class="yes">高</td><td class="yes">就在卡上缘</td><td class="yes">小</td><td>旧带子回来了</td></tr>
  </table>

  <div class="cols" style="margin-top:0">
    <div>
      <table class="cmp">
        <tr><th>微决策</th><th>默认（图上画的）</th></tr>
        <tr><td>记号 pending 文案</td><td>「MCP 连接中」（全句进悬停）；丁 例外：整句</td></tr>
        <tr><td>记号 failed 文案</td><td>「MCP 连接失败 · 点开设置」，整颗可点开 MCP 页</td></tr>
        <tr><td>落定绿</td><td>6 秒自己走（现状不动的部分）</td></tr>
        <tr><td>让位规则（甲/乙/丙）</td><td>窄于 420px 时字退成点；丁 不需要</td></tr>
        <tr><td>悬停提示</td><td>全句 + 「正在连：名字」</td></tr>
      </table>
    </div>
    <div>
      <table class="cmp">
        <tr><th>微决策</th><th>默认（图上画的）</th></tr>
        <tr><td>弹框按钮顺序</td><td>等它在前（左）· 照发在后；蓝框=默认焦点</td></tr>
        <tr><td>弹框里写名字</td><td>写「正在连：…」—— 弹框有宽度，名字一直放得下</td></tr>
        <tr><td>弹框里写 token</td><td>默认不写；备选写「约 10.5 万 token 按全价」</td></tr>
        <tr><td>弹框标题</td><td>就用现成那句「MCP 还没连完 —— 这一发会全价重算」</td></tr>
        <tr><td>可见门槛</td><td>不变：只对恢复的 / ≥3 万 token 的会话开口</td></tr>
      </table>
    </div>
  </div>

  <div class="verdict">
    <h2>我的建议</h2>
    <p><b>记号选 丁</b>。它是唯一把整句"现在发送可能不命中缓存"留下来的方案 —— 五个字「MCP 连接中」说不清"这事关我钱包"；
      而右下角这行小字和发送前弹的框是同一句话，前后对得上。它还不和工具栏抢宽度：420 + 长模型名那一关只剩它过得去。
      如果你不喜欢输入框里多一行字，退 <b>甲</b>（最安静的那档），别选丙 —— 一颗点第一次看见不知道点它。</p>
    <p><b>弹框选 A</b>。它最贴"就地问一句"的旧气质：浮层贴着卡片上缘、右对齐，不盖住你正要发的那句话；
      机制是现成的（模型/权限/思考三个弹层同一套），实现最小。要"绝不可能错过"就 <b>B</b>；
      <b>C</b> 信息最全但分量也回来了 —— 它是"挪走带子"这件事的自我否定，除非你看了图反而想念那条带子。</p>
    <p>看完回：<b>记号一个字母</b>（甲/乙/丙/丁）+ <b>弹框一个字母</b>（A/B/C）；微决策有要改的一起说（比如"弹框里要写 token 数"）。</p>
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
    let name = 'mcp-corner-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'mcp-corner-view-light.html')
      writeFileSync(target, page('light'))
      name = 'mcp-corner-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1500,9200',
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
