#!/usr/bin/env node
/**
 * 子代理那层 · 选型台 —— 一次性工具，不参与构建。
 *
 * 2026-09-18 用户："改后主界面是什么样子，先放到浏览器给我看效果再决定做不做"。
 * 两个 SDK 开关会让主界面换个样子：
 *
 *   1. `Options.forwardSubagentText` —— 默认只转发子代理的 tool_use/tool_result
 *      （"enough for a heartbeat counter"），开了之后**子代理自己的正文与思考**
 *      也带上 `parent_tool_use_id` 转发出来；
 *   2. `Options.agentProgressSummaries` —— 每 ~30s fork 一次子代理的对话，生成一句
 *      现在进行时的描述，挂在 `task_progress.summary` 上。
 *
 * **第 2 条其实只差一个开关**：`RunStatusTracker` 早就在读 `task_progress.summary`
 * 写进 `RunningTask.detail`（RunStatusTracker.kt:227），`RunDetail.taskRow` 也早在
 * 显示它（RunDetail.kt:359）—— 只是 CLI 从来没被要求生成过，所以那个字段一直是空的。
 * 页面里 B 那一节就是把"这句话长什么样"摆出来。
 *
 * 三条保真规则（照抄 tools/context-card.mjs 的做法）：
 *
 *   1. 色值不是手填的：`--bg/--surface/--text/--border` 取自 New UI 的真实常量，
 *      `--thinking-fg`、diff 那几支按 `ThemeInjector.mix()` 的**同一条公式**现算
 *      （锚色 `#cdc5b2` / `#706a5c`，比例 0.85），工具徽标的颜色直接抄
 *      `web/src/styles.css` 里那几个 tone；
 *   2. 几何照真机：工具窗口 420px、转写区内边距 12、工具卡圆角 7 / 头行 5px 7px、
 *      状态卡一行 404（四张 97 + 间距 5）、浮层宽 260；
 *   3. 深色 / 浅色两套都渲染 —— 嵌套那层的左边线、进行时那句话的灰度，
 *      换一套主题就可能看不见。
 *
 * 用法：
 *   node tools/subagent-view.mjs --open          # 生成并用默认浏览器打开
 *   node tools/subagent-view.mjs --shot          # 生成并截图（深色）
 *   node tools/subagent-view.mjs --shot=light    # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'subagent-view.html')

// ---------------------------------------------------------------- 颜色运算

const rgb = (hex) => {
  const h = hex.replace('#', '')
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}
const hex = ([r, g, b]) =>
  '#' + [r, g, b].map((c) => Math.round(c).toString(16).padStart(2, '0')).join('')

/** 复刻 ThemeInjector.mix(base, fg, ratio)。 */
const mix = (base, fg, ratio) => {
  const b = rgb(base)
  const f = rgb(fg)
  return hex(b.map((c, i) => c + (f[i] - c) * ratio))
}

/**
 * 两套主题。`bg` = 面板底（ThemeInjector 的 `--bg` 取的就是 getPanelBackground），
 * `surface` = 输入框底（气泡底），`text`/`dim` = 正文与次要色。
 */
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

/** 一份主题 → 一串 CSS 变量。dif/thinking 都按 ThemeInjector 的公式现算。 */
function tokens(t) {
  return `
    --page-bg:${t.pageBg}; --page-fg:${t.pageFg}; --page-dim:${t.pageDim};
    --page-line:${t.pageLine}; --page-card:${t.pageCard};
    --bg:${t.bg}; --surface:${t.surface}; --text:${t.text}; --dim:${t.dim};
    --border:${t.border}; --code:${t.code};
    --accent:${t.accent}; --accent-text:${t.accentText};
    --ok:${t.ok}; --warn:${t.warn}; --danger:${t.danger};
    --tone-run:${t.run}; --tone-net:${t.net}; --tone-task:${t.task}; --tone-write:${t.write};
    --thinking-fg:${mix(t.text, t.thinkAnchor, 0.85)};
    --diff-add-bg:${mix(t.bg, '#4caf50', 0.16)};
    --diff-del-bg:${mix(t.bg, '#e05454', 0.16)};
    --diff-add-fg:${mix(t.text, '#4caf50', 0.6)};
    --diff-del-fg:${mix(t.text, '#e05454', 0.6)};`
}

// ---------------------------------------------------------------- 片段

/** 工具卡（折叠态）。[tone] 决定徽标颜色，取的名字与 styles.css 里那几个一致。 */
const toolCard = ({ tone, letter, title, file, meta, cls = '' }) => `
      <div class="tool ${cls}">
        <div class="tool__head">
          <span class="tool__chev">▸</span>
          <span class="tool__badge tool__badge--${tone}">${letter}</span>
          <span class="tool__title">${title}</span>
          ${file ? `<span class="tool__file">${file}</span>` : ''}
          ${meta ? `<span class="tool__meta">${meta}</span>` : ''}
        </div>
      </div>`

/** 思考块。 */
const thinking = (text) => `
      <div class="think">
        <div class="think__head"><span class="think__chev">▸</span>思考</div>
        <div class="think__body">${text}</div>
      </div>`

/** 助手正文块（子代理的正文也用同一套，只是外面套一层嵌套框）。 */
const assistant = (html) => `<div class="bub bub--a">${html}</div>`

/** 主转写里那张 Task（子代理）卡。 */
const taskCard = (inner, { open = false, head = '' } = {}) => `
      <div class="tool tool--task ${open ? 'is-open' : ''}">
        <div class="tool__head">
          <span class="tool__chev">${open ? '▾' : '▸'}</span>
          <span class="tool__badge tool__badge--task">T</span>
          <span class="tool__title">Explore 子代理</span>
          <span class="tool__file">找一下 token 刷新的调用点</span>
          ${head ? `<span class="tool__meta">${head}</span>` : '<span class="tool__meta tool__meta--live">跑着…</span>'}
        </div>
        ${inner ? `<div class="tool__body">${inner}</div>` : ''}
      </div>`

/** 子代理内部：三种画法共用的一段内容。 */
const SUBAGENT_BODY = {
  think: '先全局搜 <code>refreshToken(</code>，再确认哪几处走的是真刷新路径、哪几处只是读缓存…',
  tools: [
    { tone: 'run', letter: 'G', title: 'Grep', file: 'refreshToken\\(', meta: '12 处' },
    { tone: 'read', letter: 'R', title: 'Read', file: 'auth/TokenStore.kt', meta: '' },
    { tone: 'run', letter: 'B', title: 'Bash', file: './gradlew test --tests *Token*', meta: '✓ 4.2s' },
  ],
  text: '三处真调用点：<code>TokenStore.refresh()</code>、<code>SessionGate.beforeRequest()</code>、<code>LoginFlow.retry()</code>。',
  report: '子代理最终报告：调用点在 TokenStore.kt:88、SessionGate.kt:41、LoginFlow.kt:132；只有前两处会真的发刷新请求。',
}

// ---------------------------------------------------------------- 面板

/** 一个 420px 的工具窗口壳。 */
const panel = (title, note, body, pick) => `
  <div class="opt">
    <div class="opt__head">
      <span class="opt__pick">${pick}</span>
      <div>
        <div class="opt__name">${title}</div>
        <div class="opt__note">${note}</div>
      </div>
    </div>
    <div class="tw">
      <div class="tw-bar"><span class="ic"></span>CCoder · 子代理那层</div>
      <div class="tr">${body}</div>
      <div class="bottom">
        <div class="comp">
          <div class="ph">继续指挥 Claude…</div>
          <div class="bar"><span class="m">Opus 4.8</span><span class="m">自动接受编辑</span><span class="r"></span></div>
        </div>
      </div>
    </div>
  </div>`

const USER_BUBBLE = `<div class="bub bub--u">找一下 token 刷新的调用点</div>`

// ---- A 节：转写区 ----

const transcriptA0 = `
        ${USER_BUBBLE}
        ${assistant('我派一个 Explore 去搜，自己先看 auth 模块。')}
        ${taskCard('')}
        ${toolCard({ tone: 'run', letter: 'G', title: 'Grep', file: 'refreshToken\\(', meta: '12 处' })}
        ${toolCard({ tone: 'read', letter: 'R', title: 'Read', file: 'auth/TokenStore.kt' })}
        ${toolCard({ tone: 'run', letter: 'B', title: 'Bash', file: './gradlew test --tests *Token*', meta: '✓ 4.2s' })}`

const transcriptA1 = `
        ${USER_BUBBLE}
        ${assistant('我派一个 Explore 去搜，自己先看 auth 模块。')}
        ${taskCard(
          `
          <div class="nest">
            <div class="nest__head">子代理的对话 <span class="nest__dim">Explore · 3 次工具调用</span></div>
            ${thinking(SUBAGENT_BODY.think)}
            ${SUBAGENT_BODY.tools.map((t) => toolCard(t)).join('')}
            ${assistant(SUBAGENT_BODY.text)}
          </div>`,
          { open: true },
        )}`

const transcriptA2 = `
        ${USER_BUBBLE}
        ${assistant('我派一个 Explore 去搜，自己先看 auth 模块。')}
        ${taskCard('')}
        ${toolCard({ tone: 'run', letter: 'G', title: 'Grep', file: 'refreshToken\\(', meta: '12 处', cls: 'tool--from-agent' })}
        ${toolCard({ tone: 'read', letter: 'R', title: 'Read', file: 'auth/TokenStore.kt', cls: 'tool--from-agent' })}
        ${toolCard({ tone: 'run', letter: 'B', title: 'Bash', file: './gradlew test --tests *Token*', meta: '✓ 4.2s', cls: 'tool--from-agent' })}`

const transcriptA3 = `
        ${USER_BUBBLE}
        ${assistant('我派一个 Explore 去搜，自己先看 auth 模块。')}
        ${taskCard(
          `<div class="report">${SUBAGENT_BODY.report}</div>`,
          { head: '3 次工具调用 · 12.3k tok' },
        )}`

// ---- B 节：运行中 / 子代理卡 ----

/** 一行状态卡（真机 404 宽、四张 97 + 间距 5）。 */
const statusRow = (agentValue) => `
  <div class="row">
    <div class="sc"><div class="sc__top"><span class="sc__ico"></span><span class="sc__lbl">连接</span></div><div class="sc__val">已连接</div></div>
    <div class="sc"><div class="sc__top"><span class="sc__ico"></span><span class="sc__lbl">任务列表</span></div><div class="sc__val">2/5</div><div class="sc__pips"><i class="on"></i><i class="on"></i><i></i><i></i><i></i></div></div>
    <div class="sc"><div class="sc__top"><span class="sc__ico"></span><span class="sc__lbl">子代理</span></div><div class="sc__val">${agentValue}</div><div class="sc__pips"><i class="run"></i></div></div>
    <div class="sc sc--meter"><div class="sc__top"><span class="sc__ico"></span><span class="sc__lbl">上下文</span></div><div class="sc__val">34%</div></div>
  </div>`

/** 浮层里的一行：左 = 类型 + 名字，右 = 统计。 */
const popupRow = (left, meta, sub = '') => `
      <div class="prow">
        <div class="prow__main"><span class="prow__w1">${left}</span></div>
        <div class="prow__meta">${meta}</div>
        ${sub ? `<div class="prow__sub">${sub}</div>` : ''}
      </div>`

const popupB0 = `
      <div class="popup">
        <div class="popup__head">子代理</div>
        <div class="psec">运行中 <span class="psec__n">1</span></div>
        ${popupRow('Explore&nbsp;&nbsp;找一下 token 刷新的调用点', '12.3k tok · 1m 20s')}
        <div class="psec">全部子代理 <span class="psec__n">3</span></div>
        ${popupRow('Explore&nbsp;&nbsp;找一下 token 刷新的调用点', '12.3k tok')}
        ${popupRow('general-purpose&nbsp;&nbsp;把测试跑通', '48.1k tok')}
        ${popupRow('Explore&nbsp;&nbsp;看看 MCP 是怎么配的', '9.4k tok')}
        <div class="phint">点一条看它的转写</div>
      </div>`

const popupB1 = `
      <div class="popup">
        <div class="popup__head">子代理</div>
        <div class="psec">运行中 <span class="psec__n">1</span></div>
        ${popupRow('Explore&nbsp;&nbsp;正在核对 TokenStore 的刷新路径', '12.3k tok · 1m 20s')}
        <div class="psec">全部子代理 <span class="psec__n">3</span></div>
        ${popupRow('Explore&nbsp;&nbsp;找一下 token 刷新的调用点', '12.3k tok')}
        ${popupRow('general-purpose&nbsp;&nbsp;把测试跑通', '48.1k tok')}
        ${popupRow('Explore&nbsp;&nbsp;看看 MCP 是怎么配的', '9.4k tok')}
        <div class="phint">点一条看它的转写</div>
      </div>`

const popupB2 = `
      <div class="popup">
        <div class="popup__head">子代理</div>
        <div class="psec">运行中 <span class="psec__n">1</span></div>
        ${popupRow('Explore&nbsp;&nbsp;找一下 token 刷新的调用点', '12.3k tok · 1m 20s', '正在核对 TokenStore 的刷新路径')}
        <div class="psec">全部子代理 <span class="psec__n">3</span></div>
        ${popupRow('Explore&nbsp;&nbsp;找一下 token 刷新的调用点', '12.3k tok')}
        ${popupRow('general-purpose&nbsp;&nbsp;把测试跑通', '48.1k tok')}
        ${popupRow('Explore&nbsp;&nbsp;看看 MCP 是怎么配的', '9.4k tok')}
        <div class="phint">点一条看它的转写</div>
      </div>`

const popupB3 = `
      <div class="popup">
        <div class="popup__head">子代理</div>
        <div class="psec">运行中 <span class="psec__n">1</span></div>
        ${popupRow('正在核对 TokenStore 的刷新路径', '1m 20s')}
        <div class="psec">全部子代理 <span class="psec__n">3</span></div>
        ${popupRow('正在核对 TokenStore 的刷新路径', '12.3k tok')}
        ${popupRow('把测试跑通', '48.1k tok')}
        ${popupRow('看看 MCP 是怎么配的', '9.4k tok')}
        <div class="phint">点一条看它的转写</div>
      </div>`

// ---------------------------------------------------------------- 页面

function page(theme) {
  const t = THEMES[theme]
  return `<!DOCTYPE html>
<html lang="zh" data-theme="${theme}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>子代理那层 · 改前 / 改后</title>
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

.grid { display:grid; grid-template-columns: repeat(auto-fit, minmax(460px, 1fr)); gap: 26px; align-items:start; }
.opt { background: var(--page-card); border:1px solid var(--page-line); border-radius: 14px; padding: 18px 18px 20px; }
.opt__head { display:flex; gap: 12px; margin-bottom: 14px; }
.opt__pick { font-family: var(--mono); font-size: 12px; letter-spacing:.08em; color:#fff; background: var(--accent);
             width: 30px; height: 30px; border-radius: 8px; display:flex; align-items:center; justify-content:center; flex:0 0 auto; }
.opt__name { font-size: 15px; font-weight: 600; }
.opt__note { color: var(--page-dim); font-size: 12.5px; margin-top: 3px; max-width: 60ch; }

/* ---- 420px 工具窗口（真机宽度）---- */
.tw { position:relative; width: 420px; max-width:100%; background: var(--bg);
      border:1px solid var(--border); border-radius: 8px; overflow:hidden; display:flex; flex-direction:column; }
.tw-bar { display:flex; align-items:center; gap:7px; padding: 6px 10px; background: var(--surface);
          border-bottom:1px solid var(--border); font-size: 11.5px; color: var(--dim); }
.tw-bar .ic { width:11px; height:11px; border-radius:2px; background: var(--border); }
.tr { padding: 12px; background: var(--bg); display:flex; flex-direction:column; gap: 9px; min-height: 190px; }

.bub { border-radius: 9px; padding: 7px 10px; font-size: 12px; line-height:1.5; }
.bub--u { align-self:flex-end; background: var(--accent); color:#fff; max-width: 85%; }
.bub--a { align-self:flex-start; background: var(--surface); border:1px solid var(--border); color: var(--text); max-width: 92%; }

.tool { border:1px solid var(--border); border-radius: 7px; background: var(--surface); overflow:hidden; }
.tool__head { display:flex; align-items:center; gap:7px; padding: 5px 7px; min-width:0; }
.tool__chev { font-family: var(--mono); font-size: 9px; color: var(--dim); width:8px; flex:0 0 auto; }
.tool__badge { display:inline-flex; align-items:center; justify-content:center; width:16px; height:16px;
               border-radius:4px; font-family: var(--mono); font-size:9px; flex:0 0 auto;
               border:1px solid currentColor; background: color-mix(in srgb, currentColor 14%, transparent); }
.tool__badge--run   { color: var(--tone-run); }
.tool__badge--read  { color: var(--accent-text); }
.tool__badge--write { color: var(--tone-write); }
.tool__badge--net   { color: var(--tone-net); }
.tool__badge--task  { color: var(--tone-task); }
.tool__title { font-family: var(--mono); font-size: 11px; color: var(--text); flex:0 0 auto; }
.tool__file { font-family: var(--mono); font-size: 11px; color: var(--accent-text);
              overflow:hidden; text-overflow:ellipsis; white-space:nowrap; min-width:0; }
.tool__meta { margin-left:auto; flex:0 0 auto; font-family: var(--mono); font-size: 10px; color: var(--dim); }
.tool__meta--live { color: var(--accent-text); }
.tool--task > .tool__head { background: color-mix(in srgb, var(--tone-task) 8%, transparent); }
.tool__body { border-top:1px solid var(--border); padding: 7px; }
.report { font-size: 11.5px; color: var(--text); line-height:1.55; }

/* 嵌套那层：左边一条竖线 + 一点点底 */
.nest { border-left: 2px solid var(--tone-task); padding-left: 9px; display:flex; flex-direction:column; gap: 7px; }
.nest__head { font-size: 10.5px; color: var(--dim); font-family: var(--mono); }
.nest__dim { color: var(--dim); opacity:.85; }
/* 「扁平但归属」：不缩进，只在卡片头上挂一个小记号 */
.tool--from-agent { border-left: 2px solid var(--tone-task); }
.tool--from-agent .tool__head::after { content:'↳ Explore'; font-family: var(--mono); font-size:9.5px;
                                       color: var(--tone-task); margin-left: 6px; flex:0 0 auto; }

.think { border:1px solid var(--border); border-radius: 7px; background: var(--surface); padding: 5px 8px 7px; }
.think__head { font-size: 10.5px; color: var(--dim); }
.think__chev { font-family: var(--mono); font-size: 9px; margin-right: 4px; }
.think__body { color: var(--thinking-fg); font-size: 11.5px; line-height: 1.5; margin-top: 2px; }

/* ---- 状态卡（97×56，间距 5）---- */
.row { display:flex; gap:5px; width: 404px; max-width:100%; }
.sc { width: 97px; height: 56px; border:1px solid var(--border); border-radius: 8px; background: var(--bg);
      padding: 5px 8px 6px; display:flex; flex-direction:column; justify-content:center; gap:1px; position:relative; }
.sc__top { display:flex; align-items:center; gap:4px; }
.sc__ico { width:10px; height:10px; border-radius:50%; border:1px solid var(--ok); flex:0 0 auto; }
.sc__lbl { font-size: 10.5px; color: var(--dim); }
.sc__val { font-size: 13px; color: var(--text); line-height:1.3; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.sc__pips { display:flex; gap:3px; margin-top:1px; }
.sc__pips i { width:4px; height:4px; border-radius:50%; background: var(--border); }
.sc__pips i.on { background: var(--ok); }
.sc__pips i.run { background: var(--accent); }
.sc--meter::after { content:''; position:absolute; left:1px; right:1px; bottom:1px; height:16px;
                    border-radius: 0 0 7px 7px; background: color-mix(in srgb, var(--accent) 22%, transparent); }

/* ---- 浮层 ---- */
.popup { width: 260px; border:1px solid var(--border); border-radius: 8px; background: var(--bg);
         box-shadow: 0 6px 18px rgba(0,0,0,.28); padding: 8px 9px 7px; }
.popup__head { font-size: 12px; color: var(--text); font-weight: 600; margin-bottom: 6px; }
.psec { font-size: 10.5px; color: var(--dim); font-family: var(--mono); margin: 8px 0 3px; }
.psec__n { margin-left: 4px; }
.prow { display:flex; align-items:baseline; gap:8px; padding: 3px 0; flex-wrap: wrap; }
.prow__main { min-width:0; flex: 1 1 auto; }
.prow__w1 { font-size: 12px; color: var(--text); }
.prow__meta { margin-left:auto; font-family: var(--mono); font-size: 9.5px; color: var(--dim); flex:0 0 auto; }
.prow__sub { flex: 1 0 100%; font-size: 11px; color: var(--dim); font-style: italic; }
.phint { font-size: 10.5px; color: var(--dim); margin-top: 7px; }

/* ---- 代价表 ---- */
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
      <p class="eyebrow">2026-09-18 · 选型台 · 不参与构建</p>
      <h1>子代理那层：改前 / 改后</h1>
      <p class="lede">两个 SDK 开关（<code>forwardSubagentText</code> 与 <code>agentProgressSummaries</code>）会让主界面长什么样。
        下面每个方案都是 <b>420px 的真实工具窗口</b>、真机配色与几何；左边深色、右上角可切浅色。</p>
    </div>
    <div class="toggle">
      <button id="dk" aria-pressed="true">深色</button><button id="lt" aria-pressed="false">浅色</button>
    </div>
  </div>

  <div class="brief">
    <h3>先把事实摆清楚</h3>
    <p><b>今天</b>：子代理跑起来时，SDK 只把它的 <code>tool_use</code>/<code>tool_result</code> 转发出来
      （文档原话是 "enough for a heartbeat counter"），而插件<b>完全没有用</b> <code>parent_tool_use_id</code>
      —— 侧车只在一处写了 <code>null</code>（session.js:185），web 侧一个引用都没有。所以子代理跑的工具卡
      <b>平铺在主转写里</b>，跟主线程自己的工具长得一模一样（方案 <b>A0</b>）。</p>
    <p><b>开了 <code>forwardSubagentText</code></b>：子代理的正文与思考也带 <code>parent_tool_use_id</code> 转发出来，
      于是"哪几句话是子代理说的"第一次可分辨 —— A1/A2/A3 是三种用法。</p>
    <p><b>第二条其实只差一个开关</b>：<code>RunStatusTracker</code> 早就在读 <code>task_progress.summary</code>
      写进 <code>RunningTask.detail</code>，浮层里的行也早在显示它 —— 只是从来没要求 CLI 生成过，那个字段一直是空的。
      代价按 SDK 文档：每 ~30s fork 一次子代理的对话来写这句话，<b>复用子代理自己的模型与提示缓存</b>，文档原话
      "cost is typically minimal"。</p>
  </div>

  <h2 class="sect">一、转写区：子代理那层怎么画</h2>
  <p class="sect-note">同一段对话的四种画法。注意 A1/A2 里子代理的那三张工具卡<b>不在主流水里重复出现</b> —— 这正是
    "认得出归属"的核心；A0 里它们跟主线程的卡挤在一起，读的人分不清谁在干活。</p>

  <div class="grid">
    ${panel('今天：全都平铺', '没有任何归属信息。下面三张工具卡其实全是子代理跑的，但跟主线程自己跑的一模一样，看不出来。',
      transcriptA0, 'A0')}
    ${panel('嵌套：子代理单独一块', 'Task 卡展开后是子代理自己的对话（缩进 + 左侧竖线 + 一层浅底），折叠起来就退回一行。',
      transcriptA1, 'A1')}
    ${panel('扁平：只挂一个归属记号', '不缩进、不套框，只在卡片左边一条紫线加 <code>↳ Explore</code>。省地方，但归属感弱一档。',
      transcriptA2, 'A2')}
    ${panel('只报结果，不报过程', '卡头写"3 次工具调用 · 12.3k tok"，展开是子代理的最终报告；过程不出现。'
      + '今天其实已经有了（结果就在卡里），这条是把工具卡收走。',
      transcriptA3, 'A3')}
  </div>

  <h2 class="sect">二、「运行中」那一行写什么</h2>
  <p class="sect-note">子代理卡本身<b>只有 97px 宽</b>，一句话塞不下（这也是当初"等待响应 12s"要换行放的原因）。
    所以这一节讨论的是<b>浮层里的行</b>，不是卡面。</p>

  <div class="grid">
    <div class="opt">
      <div class="opt__head"><span class="opt__pick">B0</span><div>
        <div class="opt__name">今天：只有任务名</div>
        <div class="opt__note">行里是"类型 + 最初的任务描述"，跑着的时候看不出它在干嘛。右边的 tok/耗时是真的。</div>
      </div></div>
      ${statusRow('1')}
      <div style="height:12px"></div>
      ${popupB0}
    </div>
    <div class="opt">
      <div class="opt__head"><span class="opt__pick">B1</span><div>
        <div class="opt__name">一句话顶掉任务名</div>
        <div class="opt__note">零改动的那条路：<code>RunDetail.kt:359</code> 的 <code>detail ?: label</code>
          本来就是"有 detail 用 detail"。代价是任务名没了 —— 想知道"我让它干嘛"得点进去。</div>
      </div></div>
      ${statusRow('1')}
      <div style="height:12px"></div>
      ${popupB1}
    </div>
    <div class="opt">
      <div class="opt__head"><span class="opt__pick">B2</span><div>
        <div class="opt__name">两行：任务名 + 进行时</div>
        <div class="opt__note">信息不丢，代价是每行高一档（浮层里最多 6 条，就是 6 行的高度）。</div>
      </div></div>
      ${statusRow('1')}
      <div style="height:12px"></div>
      ${popupB2}
    </div>
    <div class="opt">
      <div class="opt__head"><span class="opt__pick">B3</span><div>
        <div class="opt__name">换成"只看进行时"（我建议别选）</div>
        <div class="opt__note">把任务名整列换掉。问题在"全部子代理"那一段：跑完的子代理<b>没有</b>进行时可言，
          于是列表里躺着一排看起来像任务名的句子，而它们其实是收尾时的状态描述。这版还顺手把类型丢了。</div>
      </div></div>
      ${statusRow('1')}
      <div style="height:12px"></div>
      ${popupB3}
    </div>
  </div>

  <h2 class="sect">三、要动什么 / 值不值</h2>
  <table class="cmp">
    <tr><th>方案</th><th>改哪儿</th><th>风险</th></tr>
    <tr>
      <td>A1 嵌套</td>
      <td>四处，都是机械改动：① 侧车开 <code>forwardSubagentText</code>；② <code>MessageRenderer.kt:29</code> 的
          <code>RenderItem.ToolUse</code> 加一个字段；③ <code>TranscriptOp.ToolUse</code> 加同一个字段；
          ④ web 侧解析它，并按它分组渲染（一个新组件）</td>
      <td>转写区是 <b>JCEF 里的 React</b>，改动落在前端；回放（历史会话）走的是另一条路（读磁盘 jsonl），
          两边都要认这个字段，否则"看着看着刷新一下就变回 A0 了"</td>
    </tr>
    <tr>
      <td>A2 扁平</td>
      <td>同 A1，只是渲染更简单（不套框、不折叠）</td>
      <td>最省的一种；但一眼看过去还是"一堆卡片"，归属靠一条紫线撑着</td>
    </tr>
    <tr>
      <td>A3 只报结果</td>
      <td>侧车<b>把带 parent 的 tool_use 丢掉</b>（今天已经在转发了，得主动滤），卡头加统计</td>
      <td>丢信息：子代理干了什么就再也查不到（除了解析磁盘转写）。<b>代价比看起来大</b> —— 建议不做</td>
    </tr>
    <tr>
      <td>B1 / B2</td>
      <td>侧车一行 <code>agentProgressSummaries: true</code>；B2 另加 <code>RunDetail.taskRow</code> 的一处布局</td>
      <td>几乎没有。<span class="yes">这是这一堆里唯一一个"开个开关就能看到"的</span></td>
    </tr>
  </table>

  <div class="verdict">
    <h2>我的建议</h2>
    <p><b>先把 B 做掉</b>（真的是一行），看它值不值 —— 如果那句进行时确实有用，再谈 A。
      反过来先做 A，等于在一个还没验证的信息源上花几天的前端工期。</p>
    <p>A 里如果要做，我倾向 <b>A1</b>：只有它解决"这段是子代理说的"这件事本身；
      A2 省的地方有限，A3 会丢信息。</p>
    <p>看完回一个组合就行，比如 <code>B2</code> 或 <code>A1+B2</code>。不做也直接说。</p>
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
    let name = 'subagent-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'subagent-view-light.html')
      writeFileSync(target, page('light'))
      name = 'subagent-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--window-size=1500,3600',
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
