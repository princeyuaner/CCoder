#!/usr/bin/env node
/**
 * 分隔条拖手 · 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-10-13 用户："输入窗口可以上下拖动大小的位置，应该显示一个按钮告诉我
 * 这里可以拖动，出几个方案放浏览器给我选择"。这一页就是"几个方案"。
 *
 * 三条保真规则（照抄 tools/subagent-view.mjs 的做法）：
 *
 *   1. 色值不是手填的：`--bg/--surface/--text/--border` 取自 New UI 的真实常量，
 *      连使用量条的圆角都按刚落定的 8px 画（与状态卡同一个模子）；
 *   2. 几何照真机：工具窗口 560、分隔条 6px（`DIVIDER_WIDTH`）、使用量条 28px、
 *      状态卡一行五张等宽（GridLayout 的等效写法）；
 *   3. 深色 / 浅色两套都渲染 —— 握把是"很淡的灰"，换一套主题就可能看不见。
 *
 * 两条已核实的事实（写进页头，免得看的人再问一遍）：
 *
 *   - 平台的 `setShowDividerControls` / `setShowDividerIcon` 在本插件编译的那档
 *     平台（PyCharm 2026.1，对应 intellij-community 的 261 分支）里已是**空实现**：
 *     `Splitter.DividerImpl.setOrientation` 的尾部只有 removeAll/revalidate/repaint，
 *     不挂任何子件、也没有自绘（master 同样）—— 所以"这儿能拖"这层提示只能自绘。
 *   - 拖手是"长得像按钮、点了没动作"的记号：动作是按住上下拖。光标（上下箭头）、
 *     命中区（6px）、拖动结果持久化都是现成的，四个方案只差"何时出现 / 长什么样"。
 *
 * 用法：
 *   node tools/splitter-handle-view.mjs --open          # 生成并用默认浏览器打开
 *   node tools/splitter-handle-view.mjs --shot          # 生成并截图（深色）
 *   node tools/splitter-handle-view.mjs --shot=light    # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'splitter-handle-view.html')

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
    ok: '#5fad65', warn: '#d9a343', danger: '#db5c5c', run: '#d9a343', net: '#3fa7c4',
    task: '#a06bd4', write: '#5fad65',
    thinkAnchor: '#cdc5b2',
  },
  light: {
    pageBg: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageLine: '#e0e2e7',
    pageCard: '#ffffff', code: '#f2f3f5',
    bg: '#f7f8fa', surface: '#ffffff', text: '#000000', dim: '#7a7a7a', border: '#d1d1d1',
    accent: '#3574f0', accentText: '#3574f0',
    ok: '#3d8b43', warn: '#b07d1a', danger: '#c0392b', run: '#b07d1a', net: '#2b7c93',
    task: '#7a4fb0', write: '#3d8b43',
    thinkAnchor: '#706a5c',
  },
}

/** 一份主题 → 一串 CSS 变量。 */
function tokens(t) {
  return `
    --page-bg:${t.pageBg}; --page-fg:${t.pageFg}; --page-dim:${t.pageDim};
    --page-line:${t.pageLine}; --page-card:${t.pageCard};
    --bg:${t.bg}; --surface:${t.surface}; --text:${t.text}; --dim:${t.dim};
    --border:${t.border}; --code:${t.code};
    --accent:${t.accent}; --accent-text:${t.accentText};
    --ok:${t.ok}; --warn:${t.warn}; --danger:${t.danger};
    --tone-run:${t.run}; --tone-net:${t.net}; --tone-task:${t.task}; --tone-write:${t.write};
    --thinking-fg:${mix(t.text, t.thinkAnchor, 0.85)};`
}

// ---------------------------------------------------------------- 四个方案

/**
 * 每项 = 一个画法。`rest`/`hover` 是握把的 CSS 类（null = 不出现），
 * `tint` = 悬停时整条 6px 是否染色。几何（宽高）注释在各 CSS 里。
 */
const OPTIONS = [
  {
    id: 'today', pick: '现状', name: '今天：什么都没有',
    note: '分隔条 6px 里零提示 —— 只有鼠标压上去时光标变成上下箭头（平台自带）。' +
      '"不知道这儿能拖"就是这一格。',
    rest: null, hover: null, tint: false,
    change: '静止 → 悬停：没有任何东西出现。',
  },
  {
    id: 'A', pick: 'A', name: '常驻 · 细握把',
    note: '中间一条 32×3 的圆头短棒常驻（边框色），悬停变强调蓝。常驻形态里最安静的一种 ——' +
      '没碰之前就知道这儿能拖。',
    rest: 'gA', hover: 'gA', tint: false,
    change: '悬停只换颜色，尺寸不变（不跳动）。',
  },
  {
    id: 'B', pick: 'B', name: '悬停 · 胶囊',
    note: '静止时和今天一模一样；鼠标进入分隔条 → 浮出一条 44×5 的强调色胶囊。保住"静止零干扰"，' +
      '代价是第一次得靠撞见。',
    rest: 'gB', hover: 'gB', tint: false,
    change: '无 → 出现 44×5 的胶囊。',
  },
  {
    id: 'C', pick: 'C', name: '悬停 · 整条染色 + 细握把',
    note: '悬停时整条 6px 亮起（强调色 16% 的底）加中间一条细握把。用"整条发亮"说清' +
      '"整条都能拖"—— 不会误以为只有中间那一小块能按。',
    rest: null, hover: 'gAc', tint: true,
    change: '无 → 整条亮起 + 细握把浮现。',
  },
  {
    id: 'D', pick: 'D', name: '常驻 · 三颗点',
    note: '三颗 3px 圆点常驻，悬停变蓝。老式分隔条的"抓痕"语汇 —— 更像把手，也比细棒吵。',
    rest: 'gD', hover: 'gD', tint: false,
    change: '悬停换色。',
  },
]

// ---------------------------------------------------------------- 片段

const gripHtml = (cls) => {
  if (!cls) return ''
  if (cls === 'gD') return '<span class="grip gD"><i></i><i></i><i></i></span>'
  return `<span class="grip ${cls}"></span>`
}

/** 一行状态卡：五张等宽（等效 GridLayout），内容照真机取短值。 */
const statusRow = () => {
  const cards = [
    ['连接', '已连接', 'ok', ''],
    ['同步', '运行中', 'run', ''],
    ['上下文', '6%', '', ''],
    ['清单', '1/3', '', '<div class="sc__pips"><i class="on"></i><i></i><i></i></div>'],
    ['任务', '2', '', '<div class="sc__pips"><i class="run"></i><i class="run"></i></div>'],
  ]
  return `<div class="row">${cards
    .map(
      ([label, value, dot, extra]) => `
    <div class="sc">
      <div class="sc__top"><span class="sc__ico${dot ? ' sc__ico--' + dot : ''}"></span><span class="sc__lbl">${label}</span></div>
      <div class="sc__val">${value}</div>
      ${extra}
    </div>`,
    )
    .join('')}</div>`
}

/** 使用量常驻条（28px，圆角 8 = 刚与状态卡统一的那套）。 */
const usageStrip = () => `
  <div class="usestrip">
    <span class="usestrip__t">使用量</span>
    <span class="usestrip__v">今日 <b>289.2M</b> · ≈$200.6</span>
    <span class="spark">${[4, 6, 5, 8, 5, 7, 10, 9, 7, 6, 5, 4, 3, 6]
      .map((h, i, a) => `<i class="${i === a.length - 1 ? 'on' : ''}" style="height:${h}px"></i>`)
      .join('')}</span>
    <span class="usestrip__chev">›</span>
  </div>`

/** 输入卡的顶上一截：占位行 + 工具条一行。 */
const composer = () => `
  <div class="comp">
    <div class="comp__ph">继续指挥 Claude…</div>
    <div class="comp__bar">
      <span class="cm">Sonnet 4.5</span><span class="cm">标准</span><span class="cm">思考·高</span>
      <span class="comp__snd">↑</span>
    </div>
  </div>`

/** 一块窗片：转写区底部 → 分隔条 → 输入区顶部。state = rest | hover。 */
const slice = (opt, state) => {
  const hover = state === 'hover'
  return `
  <div class="tw">
    <div class="tr">
      <div class="bub bub--a">先跑一遍测试，绿了再动。</div>
      <div class="tool">
        <div class="tool__head">
          <span class="tool__chev">▸</span>
          <span class="tool__badge tool__badge--run">B</span>
          <span class="tool__title">Bash</span>
          <span class="tool__file">./gradlew test</span>
          <span class="tool__meta">✓ 2m39s</span>
        </div>
      </div>
    </div>
    <div class="split ${hover ? 'is-hover' : ''} ${hover && opt.tint ? 'split--tint' : ''}">
      ${gripHtml(hover ? opt.hover : opt.rest)}
    </div>
    <div class="bottom">
      ${statusRow()}
      ${usageStrip()}
      ${composer()}
    </div>
  </div>`
}

/** 3.5× 放大镜：85px 宽的分隔条中段放大到 ~300px，看清 3px 的握把。 */
const zoom = (opt, state) => {
  const hover = state === 'hover'
  return `
  <div class="zoom">
    <div class="zin"><div class="zin__in">
      <div class="split ${hover ? 'is-hover' : ''} ${hover && opt.tint ? 'split--tint' : ''}">
        ${gripHtml(hover ? opt.hover : opt.rest)}
      </div>
    </div></div>
    <div class="zoom__cap">${hover ? '悬停' : '静止'} · 3.5×</div>
  </div>`
}

/** 一个方案卡：题头 + 静止全图 + 悬停全图 + 两个放大镜。 */
const card = (opt) => `
  <div class="opt">
    <div class="opt__head"><span class="opt__pick${opt.id === 'today' ? ' opt__pick--dim' : ''}">${opt.pick}</span><div>
      <div class="opt__name">${opt.name}</div>
      <div class="opt__note">${opt.note}</div>
    </div></div>
    <div class="state"><div class="state__cap">静止</div>${slice(opt, 'rest')}</div>
    <div class="state"><div class="state__cap">悬停（鼠标压在分隔条上）</div>${slice(opt, 'hover')}</div>
    <div class="zooms">${zoom(opt, 'rest')}${zoom(opt, 'hover')}</div>
    <div class="change">${opt.change}</div>
  </div>`

// ---------------------------------------------------------------- 页面

function page(theme) {
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>分隔条拖手 · 四个方案</title>
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
.lede { color: var(--page-dim); max-width: 74ch; margin: 0; font-size: 14.5px; }
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
.brief p { margin: 0 0 8px; color: var(--page-dim); font-size: 13.5px; max-width: 92ch; }
.brief p:last-child { margin-bottom: 0; }
.brief code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
.brief b { color: var(--page-fg); }

h2.sect { font-family: var(--serif); font-size: 25px; font-weight: 600; margin: 54px 0 4px; }
.sect-note { color: var(--page-dim); font-size: 13.5px; margin: 0 0 22px; max-width: 92ch; }
.sect-note b { color: var(--page-fg); }

.grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(620px, 1fr)); gap: 26px; align-items:start; }
.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px; padding: 18px 18px 20px; }
.opt__head { display:flex; gap: 12px; margin-bottom: 14px; }
.opt__pick { font-family: var(--mono); font-size: 12px; letter-spacing:.08em; color:#fff; background: var(--accent);
             min-width: 30px; height: 30px; padding: 0 7px; border-radius: 8px; display:flex; align-items:center;
             justify-content:center; flex:0 0 auto; }
.opt__pick--dim { background: var(--page-dim); }
.opt__name { font-size: 15px; font-weight: 600; }
.opt__note { color: var(--page-dim); font-size: 12.5px; margin-top: 3px; max-width: 74ch; }

.state { margin-bottom: 10px; }
.state__cap { font-family: var(--mono); font-size: 10.5px; letter-spacing:.1em; text-transform:uppercase;
              color: var(--page-dim); margin-bottom: 5px; }
.zooms { display:flex; gap:12px; flex-wrap:wrap; margin-top: 12px; }
.zoom__cap { font-family: var(--mono); font-size: 10px; color: var(--page-dim); margin-top: 4px; }
.zin { width: 310px; height: 32px; border:1px solid var(--page-line); border-radius: 8px; overflow: hidden;
       background: var(--bg); display:flex; align-items:center; padding-left: 4px; }
.zin__in { width: 85px; height: 6px; transform: scale(3.5); transform-origin: left center; }
.change { color: var(--page-dim); font-size: 12.5px; margin-top: 12px; }
.change b { color: var(--page-fg); }

/* ---- 560px 工具窗口（窗片）---- */
.tw { position:relative; width: 560px; max-width:100%; background: var(--bg);
      border:1px solid var(--border); border-radius: 8px; overflow:hidden; display:flex; flex-direction:column; }
.tr { padding: 10px 10px 8px; background: var(--bg); display:flex; flex-direction:column; gap: 7px;
      justify-content:flex-end; min-height: 90px; }
.bub { border-radius: 9px; padding: 6px 10px; font-size: 12px; line-height:1.5; }
.bub--a { align-self:flex-start; background: var(--surface); border:1px solid var(--border); color: var(--text); max-width: 92%; }
.tool { border:1px solid var(--border); border-radius: 7px; background: var(--surface); overflow:hidden; }
.tool__head { display:flex; align-items:center; gap:7px; padding: 5px 8px; min-width:0; }
.tool__chev { font-family: var(--mono); font-size: 9px; color: var(--dim); width:8px; flex:0 0 auto; }
.tool__badge { display:inline-flex; align-items:center; justify-content:center; width:16px; height:16px;
               border-radius:4px; font-family: var(--mono); font-size:9px; flex:0 0 auto;
               border:1px solid currentColor; background: color-mix(in srgb, currentColor 14%, transparent); }
.tool__badge--run { color: var(--tone-run); }
.tool__title { font-family: var(--mono); font-size: 11px; color: var(--text); flex:0 0 auto; }
.tool__file { font-family: var(--mono); font-size: 11px; color: var(--accent-text);
              overflow:hidden; text-overflow:ellipsis; white-space:nowrap; min-width:0; }
.tool__meta { margin-left:auto; flex:0 0 auto; font-family: var(--mono); font-size: 10px; color: var(--dim); }

/* ---- 分隔条本体（6px）---- */
.split { height: 6px; position:relative; display:flex; align-items:center; justify-content:center;
         cursor: ns-resize; }
.split--tint.is-hover { background: color-mix(in srgb, var(--accent-text) 16%, var(--bg)); }

/* ---- 握把四种画法（几何就是实现里会用的那几个数）---- */
.grip { display:block; pointer-events:none; }
.gA { width:32px; height:3px; border-radius:1.5px; background: var(--border); }
.is-hover .gA { background: var(--accent-text); }
.gAc { width:32px; height:3px; border-radius:1.5px; background: var(--accent-text); }
.gB { width:44px; height:5px; border-radius:2.5px; background: var(--accent-text); opacity:0; }
.is-hover .gB { opacity:1; }
.gD { display:flex; gap:3px; }
.gD i { width:3px; height:3px; border-radius:50%; background: var(--border); }
.is-hover .gD i { background: var(--accent-text); }

/* ---- 输入区顶部 ---- */
.bottom { background: var(--bg); display:flex; flex-direction:column; gap:6px; padding: 6px 8px 8px; }
.row { display:flex; gap:5px; }
.sc { flex:1 1 0; min-width:0; height: 52px; border:1px solid var(--border); border-radius: 8px;
      background: var(--bg); padding: 4px 7px; display:flex; flex-direction:column; justify-content:center; gap:1px; }
.sc__top { display:flex; align-items:center; gap:4px; }
.sc__ico { width:10px; height:10px; border-radius:50%; border:1px solid var(--dim); flex:0 0 auto; }
.sc__ico--ok { border-color: var(--ok); }
.sc__ico--run { border-color: var(--accent-text); }
.sc__lbl { font-size: 10.5px; color: var(--dim); white-space:nowrap; }
.sc__val { font-size: 12.5px; color: var(--text); line-height:1.3; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.sc__pips { display:flex; gap:3px; margin-top:1px; }
.sc__pips i { width:4px; height:4px; border-radius:50%; background: var(--border); }
.sc__pips i.on { background: var(--ok); }
.sc__pips i.run { background: var(--accent); }

.usestrip { display:flex; align-items:center; gap:8px; border:1px solid var(--border); border-radius: 8px;
            background: var(--bg); padding: 4px 9px; height: 28px; }
.usestrip__t { font-size:10.5px; color: var(--dim); flex:0 0 auto; }
.usestrip__v { font-size:11px; color: var(--text); flex:0 0 auto; }
.usestrip__v b { font-family: var(--mono); font-size: 11.5px; }
.spark { display:flex; align-items:flex-end; gap:2px; height:15px; margin-left:auto; }
.spark i { width:3px; border-radius:1px; background: color-mix(in srgb, var(--accent-text) 55%, transparent); }
.spark i.on { background: var(--accent-text); }
.usestrip__chev { color: var(--dim); font-size:12px; flex:0 0 auto; }

.comp { border:1px solid var(--border); border-radius: 8px; background: color-mix(in srgb, var(--text) 4%, var(--bg));
        padding: 8px 10px 6px; }
.comp__ph { color: var(--dim); font-size: 12px; }
.comp__bar { display:flex; align-items:center; gap:10px; margin-top: 8px; }
.cm { font-size: 10.5px; color: var(--dim); }
.comp__snd { margin-left:auto; width:21px; height:21px; border-radius:50%; background: var(--accent); color:#fff;
             display:flex; align-items:center; justify-content:center; font-size:12px; }

/* ---- 取舍表 ---- */
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
.verdict p { color: var(--page-dim); margin: 0 0 10px; max-width: 84ch; }
.verdict b { color: var(--page-fg); }
.verdict code { font-family: var(--mono); font-size: 12.5px; background: var(--code); padding: 1px 5px; border-radius: 4px; color: var(--text); }
</style>
</head>
<body>
<div class="wrap">
  <div class="masthead">
    <div>
      <p class="eyebrow">2026-10-13 · 选型台 · 不参与构建</p>
      <h1>分隔条上的拖手：四个画法</h1>
      <p class="lede">转写区与输入区之间那条 6px 的可拖动分隔，今天<b>一点提示都没有</b>。
        下面每个方案都是 <b>560px 的真实工具窗口窗片</b>、真机配色与几何，静止 / 悬停两个状态各画一遍，
        再加一个 3.5× 放大镜（6px 高的东西 1:1 看不清）。右上角可切浅色。</p>
    </div>
    <div class="toggle">
      <button id="dk" aria-pressed="true">深色</button><button id="lt" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先把事实摆清楚</h3>
    <p><b>为什么只能自绘</b>：这块分隔用的平台 <code>JBSplitter</code> 有两个开关
      （<code>setShowDividerControls</code> / <code>setShowDividerIcon</code>），但在本插件编译的那档平台
      （PyCharm 2026.1）里它们已经是<b>空实现</b> —— <code>Splitter.DividerImpl</code> 的
      <code>setOrientation</code> 尾部只有 removeAll/revalidate/repaint，不挂子件也不自绘（261 分支与
      master 都读过）。所以"这儿能拖"这层记号只能自己画。</p>
    <p><b>拖手不是按钮</b>：它长得像按钮，但<b>点了没动作</b> —— 按住上下拖才是动作（今天也一样，双击会复位到一半是平台行为）。
      光标（上下箭头）、命中区（6px）、拖动结果持久化都不动；四个方案只差"何时出现 / 长什么样"。</p>
    <p><b>图里那条带子</b>：分隔条本身在真机上不带任何画线（就是面板底），图里画成和两侧同底的 6px 横带只为标出位置。
      对照卡"今天"会把现在的样子原样画一遍。</p>
  </div>

  <h2 class="sect">一、四个画法（外加"现状"对照）</h2>
  <p class="sect-note">每张卡里三个东西：<b>静止态全图</b>、<b>悬停态全图</b>（鼠标压在分隔条上）、
    两个 <b>3.5× 放大镜</b>。四个方案的命中区都是整条 6px（今天就是这个数，要加宽就改
    <code>DIVIDER_WIDTH</code>，四案通用）。</p>

  <div class="grid">
    ${OPTIONS.map(card).join('')}
  </div>

  <h2 class="sect">二、取舍</h2>
  <table class="cmp">
    <tr><th>方案</th><th>静止时的存在感</th><th>悬停时</th><th>发现性</th><th>备注</th></tr>
    <tr><td>现状</td><td>零</td><td>零（只有光标变）</td><td class="no">低</td><td>要拖才知道能拖 —— 你想解决的就是这条</td></tr>
    <tr><td>A 常驻·细握把</td><td>低（一条细棒）</td><td>变强调蓝</td><td class="yes">高</td><td>常驻里最安静；不改变尺寸、无跳动</td></tr>
    <tr><td>B 悬停·胶囊</td><td>零</td><td>浮现 44×5 胶囊</td><td class="no">低</td><td>保住"静止零干扰"；第一次仍得撞见</td></tr>
    <tr><td>C 悬停·整条染色</td><td>零</td><td>整条亮 + 细握把</td><td>中</td><td>"整条都能拖"说得最清楚，但也是悬停才知道</td></tr>
    <tr><td>D 常驻·三颗点</td><td>中（三点）</td><td>变强调蓝</td><td class="yes">高</td><td>更像"把手"，也比细棒吵一档</td></tr>
  </table>

  <div class="verdict">
    <h2>我的建议</h2>
    <p>目标是"<b>不用碰到就知道这儿能拖</b>"，所以只有常驻（A / D）真的解决发现性；悬停（B / C）
      等于把今天的问题推迟到鼠标碰上那一刻。</p>
    <p>常驻里我倾向 <b>A（细握把）</b>：它是四种里最安静的一种，静止时就是一条 32×3 的淡灰棒，
      和这个界面里其它自绘记号（状态卡的线、使用量条的描边）是同一档的存在感；悬停换色、不改变尺寸，
      不会在指针靠近时"跳一下"。</p>
    <p>看完回一个字母就行：<code>A</code> / <code>B</code> / <code>C</code> / <code>D</code>，
      或者说"维持现状"。</p>
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
    // 浅色那张另存一份把 data-theme 写死的 html —— headless 里靠脚本切换实测不生效
    let target = outFile
    let name = 'splitter-handle-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'splitter-handle-view-light.html')
      writeFileSync(target, page('light'))
      name = 'splitter-handle-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1500,4200',
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
