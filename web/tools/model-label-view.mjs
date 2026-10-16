#!/usr/bin/env node
/**
 * 模型位「也显示自定义名称」· 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-14 用户："这里模型显示我想要同时显示自定义的名称，比如当前是
 * opencode，我也希望显示出来"。现状（`ComposerModel.modelLabelText`）只写模型 ID
 * —— 而两条配置完全可以是同一个网关、同一族模型名（`ModelPick` 的注释原话），
 * 光看模型名认不出"现在用的是哪条"。
 *
 * **用户当天选了乙 + 让法②**（可选件全走默认），落在
 * `src/main/kotlin/com/ccoder/ui/ComposerModel.kt`（ModelLabel 换自绘）与
 * `ComposerModelRenderProbe` 的 composer-model-label-named(-narrow)(-dark).png。
 *
 * 这页就是"几个方案"：甲 点分·配置在前 / 乙 胶囊·配置在前 / 丙 点分·模型在前 /
 * 丁 括注·模型在前；外加两节前置决定 ——「窄窗怎么让」与「特殊态」（没有自定义
 * 名字、没有模型、超长名）。悬停整句变亮、点击开弹层、24 字截断这些行为**四案
 * 都不动**；变的只是名字段摆在哪、长什么样。
 *
 * 三条保真规则（照抄 mcp-corner-view 那套的做法）：
 *   1. 几何不是手填的：舞台 756 → 卡 740（与用户实机量到的一致）、卡弧 16
 *      （CARD_CORNER_ARC）、底带 = mix(面板底, 标签前景, 0.09)（FOOTER_TINT）、
 *      附件格 22（AttachButton.BOX_SIDE）、发送键 29（RoundSendButton.DIAMETER）；
 *   2. 颜色不是手填的：权限那格走 warningColor（绕过态）；两档灰从 --dim 派生；
 *   3. 深/浅两套都渲染。
 *
 * 用法：
 *   node tools/model-label-view.mjs --open
 *   node tools/model-label-view.mjs --shot        / --shot=light
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'model-label-view.html')

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
    panel: '#2b2d30', transcript: '#1e1f22',
    text: '#dfe1e5', dim: '#9da0a8', border: '#393b40',
    accent: '#2f65ca', accentText: '#7fb0ff', focus: '#548af7',
    ok: '#5fad65', warn: '#ff8a65', danger: '#db5c5c',
    labelFg: '#dfe1e5',
  },
  light: {
    pageBg: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageLine: '#e0e2e7',
    pageCard: '#ffffff', code: '#f2f3f5',
    panel: '#f7f8fa', transcript: '#ffffff',
    text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', accentText: '#3574f0', focus: '#3574f0',
    ok: '#3d8b43', warn: '#d84315', danger: '#c0392b',
    labelFg: '#000000',
  },
}

/** 底带 = mix(面板底, 标签前景, 0.09)，与 Composer.kt 的 FOOTER_TINT 同源。 */
const bandOf = (t) => mix(t.panel, t.labelFg, 0.09)

function tokens(t) {
  return `
    --page-bg:${t.pageBg}; --page-fg:${t.pageFg}; --page-dim:${t.pageDim};
    --page-line:${t.pageLine}; --page-card:${t.pageCard};
    --panel:${t.panel}; --transcript:${t.transcript};
    --text:${t.text}; --dim:${t.dim}; --border:${t.border}; --code:${t.code};
    --accent:${t.accent}; --accent-text:${t.accentText}; --focus:${t.focus};
    --band:${bandOf(t)};
    /* 名字段退的那一档：比标签的 --dim 再退半格 */
    --dim2:${mix(t.dim, t.panel, 0.62)};
    --warn:${t.warn};`
}

// ---------------------------------------------------------------- 真实常数

const STAGE = 756      // 舞台（面板宽）→ 卡 740 —— 与用户实机量到的条宽同源
const NARROW = 436     // 窄窗舞台 → 卡 420（MAX_LABEL_CHARS 就是在 420 下量出来的）
const CARD_ARC = 16    // CARD_CORNER_ARC
const SEND_D = 29      // RoundSendButton.DIAMETER
const ATTACH_BOX = 22  // AttachButton.BOX_SIDE

/** 场景：用户这台机器的真值（opencode 配置 + 那条模型；权限正处在绕过态）。 */
const SCENE = {
  profile: 'opencode',
  model: 'deepseek-v4.1-flash[1m]',
  perm: '绕过权限',
  effort: '思考·默认',
  placeholder: '@ 文件 · # 符号 · / 命令 · Enter 发送 · Shift+Enter 换行',
}

// ---------------------------------------------------------------- 小图标（照 mcp-corner-view）

const svgPaperclip = () => `
  <svg width="17" height="17" viewBox="0 0 12 12" fill="none" aria-hidden="true" style="transform:rotate(-45deg)">
    <path d="M8.4 2.8 L8.4 7.6 A2.4 2.4 0 0 1 3.6 7.6 L3.6 3.9 A1.15 1.15 0 0 1 5.9 3.9 L5.9 7.4 A0.5 0.5 0 0 1 4.9 7.4 L4.9 4.2"
          stroke="currentColor" stroke-width="1.15" stroke-linecap="round"/>
  </svg>`

const svgClock = (size = 12, sw = 1.35) => `
  <svg width="${size}" height="${size}" viewBox="0 0 12 12" fill="none" aria-hidden="true">
    <circle cx="6" cy="6" r="4.7" stroke="currentColor" stroke-width="${sw}"/>
    <path d="M6 6 L6 3.4 M6 6 L8.1 7.2" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round"/>
  </svg>`

const svgArrowUp = () => `
  <svg width="13" height="13" viewBox="0 0 13 13" fill="none" aria-hidden="true">
    <path d="M6.5 10.4 L6.5 2.6 M6.5 2.6 L3.7 5.4 M6.5 2.6 L9.3 5.4"
          stroke="#fff" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>`

// ---------------------------------------------------------------- 模型位的五种写法

const caret = '<span class="caret">▾</span>'

/** 现状：只有模型 ID。 */
const mNow = () => `${SCENE.model} ${caret}`

/** 甲：配置名在前、退一档，一个中点分家。 */
const mA = () => `<span class="m2">${SCENE.profile}</span><span class="msep">·</span>${SCENE.model} ${caret}`

/** 乙：配置名装进小胶囊。 */
const mB = () => `<span class="mtag">${SCENE.profile}</span>${SCENE.model} ${caret}`

/** 丙：模型仍占原位，配置名缀在后、退一档。 */
const mC = () => `${SCENE.model}<span class="msep">·</span><span class="m2">${SCENE.profile}</span> ${caret}`

/** 丁：同丙，但用括号做成"批注"。 */
const mD = () => `${SCENE.model}<span class="m2">（${SCENE.profile}）</span> ${caret}`

// ---------------------------------------------------------------- 骨架

/** 输入卡（占位行 + 底带那一行）。modelHtml = 模型位画哪一版。 */
const card = (modelHtml) => `
  <div class="card">
    <div class="card__input"><span class="ph">${SCENE.placeholder}</span></div>
    <div class="foot">
      <span class="ic" title="附件">${svgPaperclip()}</span>
      <span class="labs">
        <span class="lab lab--model">${modelHtml}</span>
        <span class="lab lab--perm">${SCENE.perm} <span class="caret">▾</span></span>
        <span class="lab">${SCENE.effort} <span class="caret">▾</span></span>
      </span>
      <span class="right">
        <span class="ic ic--clock" title="定时发送">${svgClock()}</span>
        <span class="send" title="发送">${svgArrowUp()}</span>
      </span>
    </div>
  </div>`

const stage = (modelHtml, width = STAGE) =>
  `<div class="stage" style="width:${width}px">${card(modelHtml)}</div>`

/** 一个图：题头 + 舞台。 */
const fig = (caption, modelHtml, width = STAGE) => `
  <div class="fig">
    ${caption ? `<div class="figcap">${caption}</div>` : ''}
    ${stage(modelHtml, width)}
  </div>`

// ---------------------------------------------------------------- 页面

function page(theme) {
  const T = THEMES[theme]
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>模型位 · 显示自定义名称 · 选型台</title>
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
.wrap { max-width: 1020px; margin: 0 auto; padding: 52px 32px 120px; }

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
.brief p { margin: 0 0 8px; color: var(--page-dim); font-size: 13.5px; max-width: 100ch; }
.brief p:last-child { margin-bottom: 0; }
.brief b { color: var(--page-fg); }
.brief code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--page-fg); }

h2.sect { font-family: var(--serif); font-size: 25px; font-weight: 600; margin: 54px 0 4px; }
.sect-note { color: var(--page-dim); font-size: 13.5px; margin: 0 0 22px; max-width: 100ch; }
.sect-note b { color: var(--page-fg); }

.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px;
       padding: 18px 18px 20px; margin-top: 26px; }
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

.fig { margin: 12px 0 0; }
.figcap { font-family: var(--mono); font-size: 10.5px; letter-spacing:.08em; color: var(--page-dim);
          text-transform: uppercase; margin: 0 0 6px 2px; }

/* ---- 窗片：舞台 = 面板，卡 = 输入卡 ---- */
.stage { background: var(--panel); padding: 6px 8px 8px; border-radius: 8px; max-width: 100%; }
.card { border:1px solid var(--border); border-radius: ${CARD_ARC}px; overflow:hidden; background: var(--panel); }
.card__input { padding: 8px 10px 7px; }
.card__input .ph { font-size: 12.5px; color: var(--dim); }

.foot { background: var(--band); padding: 2px 6px; display:flex; align-items:center; min-height: 33px; }
.ic { color: var(--dim); display:flex; align-items:center; width: ${ATTACH_BOX}px; justify-content:center; }
.labs { display:flex; align-items:center; margin-left: 8px; gap: 10px; min-width: 0; }
.lab { font-size: 13px; color: var(--dim); white-space:nowrap; }
.lab--perm { color: var(--warn); }
.caret { font-size: 10px; }
.right { margin-left:auto; display:flex; align-items:center; gap: 6px; }
.ic--clock { width: auto; padding: 0 1px; }
.send { width: ${SEND_D}px; height: ${SEND_D}px; border-radius: 50%; background: var(--focus);
        display:flex; align-items:center; justify-content:center; flex: 0 0 auto; }

/* ---- 名字段的五种形态 ---- */
.m2 { color: var(--dim2); }
.msep { color: var(--dim2); margin: 0 5px; }
.mtag { color: var(--dim); background: color-mix(in srgb, var(--text) 8%, transparent);
        border-radius: 4px; padding: 1px 6px 2px; margin-right: 6px; }
.is-cut { color: var(--dim); }

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

.stack { display:flex; flex-direction:column; gap: 8px; }
</style>
</head>
<body>
<div class="wrap">
  <div class="masthead">
    <div>
      <p class="eyebrow">2026-10-14 · 选型台 · 不参与构建</p>
      <h1>模型位：也显示自定义名称，四个方案</h1>
      <p class="lede">现状只写模型 ID —— 而<b>两条配置完全可以是同一个网关、同一族模型名</b>，
        光看模型名认不出"现在用的是哪条"。四个方案只差<b>名字段摆在哪、长什么样</b>；
        悬停整句变亮、点击开弹层、24 字截断这些行为全部不动。舞台 756 → 卡 740（你这台
        机器的实宽）；深浅两套都渲染，右上角切换。</p>
    </div>
    <div class="toggle">
      <button data-theme="dark" aria-pressed="true">深色</button><button data-theme="light" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>三条规矩（四案共有，先看一眼再往下挑）</h3>
    <p>1. <b>名字段只在配置有自定义名字时出现</b>（<code>profile.name</code> 非空）—— 没名字时
       <code>displayName()</code> 会回退成模型 ID，跟模型段自我重复，所以四案都自动退回现状。</p>
    <p>2. <b>名字段不是独立按钮</b> —— 整句仍是同一个点击目标（点哪都开弹层），别被胶囊的框骗了。</p>
    <p>3. <b>窄窗怎么让</b>是另一道选择题（第四节）：先丢配置段，或缩模型保配置 —— 定了它，四案照跟。</p>
  </div>

  <h2 class="sect">零 · 现状</h2>
  <p class="sect-note">只写模型 ID；配置名只在"没指定模型"时才顶上来（那时它才是唯一能说明用哪条的东西）。</p>
  ${fig('现状 · 卡宽 740', mNow())}

  <h2 class="sect">一 · 四个方案</h2>
  <p class="sect-note">名字段的内容都一样（配置的自定义名字），四案只差位置与形态。</p>

  <div class="opt">
    <div class="opt__head"><span class="opt__pick">甲</span><div>
      <div class="opt__name">点分 · 配置在前</div>
    </div></div>
    <div class="opt__note">配置名在最前、<b>退一档</b>（比模型更淡），一个中点分家 ——
      与刚定的使用量条同一套"词暗数亮"的层级语言。先读"哪条"、再读"哪个模型"。</div>
    <ul class="opt__gain">
      <li>同一模型名跨配置，一眼可分；不添任何盒子，最安静</li>
      <li>代价：名字在最左边，扫过时先撞见它；两段色要自绘（现状 JLabel 是单色）</li>
    </ul>
    ${fig('甲 · 卡宽 740', mA())}
  </div>

  <div class="opt">
    <div class="opt__head"><span class="opt__pick">乙</span><div>
      <div class="opt__name">胶囊 · 配置在前</div>
    </div></div>
    <div class="opt__note">配置名装进一枚小胶囊（前景 8% 的底），模型照旧跟在后面 ——
      名字像一枚"舱牌"，与模型是两个视觉物件，谁也不吃谁的读法。</div>
    <ul class="opt__gain">
      <li>名字段最"实"的一版；深浅两色都由 mix 派生，不用新颜色</li>
      <li>代价：胶囊容易被误会成独立按钮（其实整句同一个点击目标）；窄窗先整个丢掉</li>
    </ul>
    ${fig('乙 · 卡宽 740', mB())}
  </div>

  <div class="opt">
    <div class="opt__head"><span class="opt__pick">丙</span><div>
      <div class="opt__name">点分 · 模型在前</div>
    </div></div>
    <div class="opt__note">模型仍占原位（肌肉记忆一个字不动），配置名缀在后、退一档。</div>
    <ul class="opt__gain">
      <li>左端完全不变 —— 已经习惯"第一个字就是模型"的眼睛不用重新学</li>
      <li>代价：名字在尾巴上，快速扫过容易漏；长了先截模型（见第四节）</li>
    </ul>
    ${fig('丙 · 卡宽 740', mC())}
  </div>

  <div class="opt">
    <div class="opt__head"><span class="opt__pick">丁</span><div>
      <div class="opt__name">括注 · 模型在前</div>
    </div></div>
    <div class="opt__note">同丙的位置，但用括号把它做成"批注"的样子（中文全角、英文半角）——
      读法上明确"这是附注，不是并列的另一个名字"。</div>
    <ul class="opt__gain">
      <li>附注感最强；括号本身把两段分得比中点更开</li>
      <li>代价：全角括号占宽（约一个半字符），窄窗更早开始截</li>
    </ul>
    ${fig('丁 · 卡宽 740', mD())}
  </div>

  <h2 class="sect">二 · 窄窗怎么让（卡宽 420）</h2>
  <p class="sect-note">三个标签并排，420 是现有 <code>MAX_LABEL_CHARS = 24</code> 量出来的底线宽度。
    两套让法选一个，四案照跟。</p>
  <div class="stack">
    ${fig('让法 ① 先丢配置段（模型 24 字上限照旧）—— 零回归，窄窗等于现状', mNow(), NARROW)}
    ${fig('让法 ② 缩模型保配置（名字短时还能两个都看全）', `<span class="m2">${SCENE.profile}</span><span class="msep">·</span><span class="is-cut">deepseek-v4.1…</span> ${caret}`, NARROW)}
  </div>
  <p class="sect-note" style="margin-top:10px">落笔宽度按像素量（<code>ellipsizeToWidth</code> 已有），
    上面的"截到哪"是示意 —— 实现时按真字体把两段合着裁到预算内。</p>

  <h2 class="sect">三 · 特殊态（以甲的形态演示，其余三案同理）</h2>
  <div class="stack">
    ${fig('① 配置没有自定义名字 → 名字段整个不出现', `${SCENE.model} ${caret}`)}
    ${fig('② 配置有名字但没指定模型 → 只写名字（与现状一致，名字此时是唯一说明）', `<span class="m2">${SCENE.profile}</span> ${caret}`)}
    ${fig('③ 超长模型名 → 24 字上限照旧（先截模型，名字段完整）', `<span class="m2">${SCENE.profile}</span><span class="msep">·</span>claude-sonnet-5-5-max-th… ${caret}`)}
    ${fig('④ 超长名字 → 名字段也截（示意 ≈12 字），别把模型挤出窗口', `<span class="m2">opencode 备用线…</span><span class="msep">·</span>${SCENE.model} ${caret}`)}
  </div>

  <h2 class="sect">四 · 对照表</h2>
  <table class="cmp">
    <tr><th>方案</th><th>名字段位置 / 形态</th><th>窄窗让法</th><th>实现（Swing）</th><th>代价 / 风险</th></tr>
    <tr><td>甲 点分·前</td><td>前缀，退一档灰，中点分家</td><td rowspan="4">①先丢配置（默认建议）或<br>②缩模型保配置 —— 四案共用</td>
        <td rowspan="3">ModelLabel 从 JLabel 换自绘：两段色 + 中点，量宽走 FontMetrics、截断走 ellipsizeToWidth（都是现成的）</td>
        <td>最安静；两段色需自绘</td></tr>
    <tr><td>乙 胶囊·前</td><td>前缀，装进 8% 底的小胶囊</td>
        <td>名字段最显眼；胶囊可能被当成按钮</td></tr>
    <tr><td>丙 点分·后</td><td>后缀，退一档灰，中点分家</td>
        <td>左端零变化；尾巴上的名字易漏看</td></tr>
    <tr><td>丁 括注·后</td><td>后缀，括号批注</td>
        <td>同上 + 多一枚 fillRoundRect(弧 8) 的胶囊底（乙）</td>
        <td>批注感强；全角括号占宽</td></tr>
  </table>

  <div class="verdict">
    <h2>怎么回</h2>
    <p>回一个组合就行，例：<code>甲 + 让法①</code>、<code>乙 + 让法②</code>、<code>丙</code>（默认让法①）。
       都不对也直接说方向。定了我就落到 <code>ComposerModel.kt</code>，并补一张
       <code>ComposerModelRenderProbe</code> 的图。</p>
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
    let name = 'model-label-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'model-label-view-light.html')
      writeFileSync(target, page('light'))
      name = 'model-label-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1120,4600',
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
