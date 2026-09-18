#!/usr/bin/env node
/**
 * 渲染探针：子代理那一块（A1）在**真实 Chromium** 里长什么样、量出来多少。
 *
 * 为什么不能只靠单测：`Transcript.test.tsx` 能证明 DOM 里有 `[data-testid=
 * subagent-block]` 且里面装着那两条，却看不出缩进是不是真的看得出层级、竖线会不会
 * 太抢、420px 的面板里嵌套的卡片会不会被挤到溢出。jsdom 不做布局（这条账
 * `layout-probe.mjs` 的文件头已经算过一遍）。
 *
 * 与 `layout-probe.mjs` 同一套做法：**手写标记 + 真 CSS**（`src/styles.css` 原样引进来），
 * 所以这里量的就是产品里那套样式。标记按 `SubagentBlock` / `ToolCallBlock` 渲染出来的
 * 结构抄 —— 结构由单测钉，几何由这里量。
 *
 * 四样东西一张图：
 *   ① 展开态：Task 卡里嵌着子代理的思考、三张工具卡、一句正文
 *   ② 收起态：卡面那个 `N 次工具调用` 是不是看得出来里面有事
 *   ③ 五层嵌套：再深一层时缩进还剩多少（420px 里缩两次就没地方了）
 *   ④ **插在正文中间**（展开 / 收起对照）—— 2026-09-18 撤销自动展开的依据。
 *      前两节把卡片孤零零摆着，看不出"它挤在正文里"是什么样；那一节画的就是那个。
 *
 * 顺带量四个数（打印出来；图看着不对时先看它们）：
 *   indent   嵌套块的左竖线相对卡片左边框的位移
 *   cardW    嵌套里那张工具卡的宽度  ← 必须**小于**外层卡片，否则缩进没生效
 *   overflow 嵌套块有没有横向溢出（scrollWidth > clientWidth）
 *   headY    展开/收起两种状态下卡片头的高度（态②不该比态①高）
 *
 * 用法：
 *   node tools/subagent-nesting-probe.mjs --shot          # 生成并截图（深色）
 *   node tools/subagent-nesting-probe.mjs --shot=light
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'subagent-nesting.html')
const css = readFileSync(join(webDir, 'src', 'styles.css'), 'utf8')

// 主题变量：真机由 ThemeInjector 注入（见 Kotlin 侧同名对象），这里给 New UI 的常色
const THEMES = {
  dark: {
    page: '#161719', pageFg: '#e8eaed', pageDim: '#8b8f97', pageCard: '#1c1e21',
    bg: '#2b2d30', surface: '#1e1f22', text: '#dfe1e5', dim: '#9da0a8', border: '#393b40',
    accent: '#2f65ca', accentText: '#7fb0ff', code: '#1e1f22', ref: '#2b2d30',
    think: '#cfc8b8', add: '#5fad65', del: '#e05454',
  },
  light: {
    page: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageCard: '#ffffff',
    bg: '#f7f8fa', surface: '#ffffff', text: '#000000', dim: '#7a7a7a', border: '#d1d1d9',
    accent: '#3574f0', accentText: '#3574f0', code: '#f2f3f5', ref: '#efe6ff',
    think: '#706a5c', add: '#3d8b43', del: '#c0392b',
  },
}

const vars = (t) => `
  --bg:${t.bg}; --surface:${t.surface}; --text:${t.text}; --text-dim:${t.dim};
  --border:${t.border}; --accent:${t.accent}; --accent-text:${t.accentText};
  --code-bg:${t.code}; --ref-bg:${t.ref}; --error-bg:#3a2a2a;
  --diff-add-bg:#1e2f1f; --diff-del-bg:#33222a; --diff-add-fg:${t.add}; --diff-del-fg:${t.del};
  --thinking-fg:${t.think}; --font-ui:"Segoe UI",sans-serif; --font-mono:"Cascadia Mono",Consolas,monospace;`

/** 一张工具卡（折叠态，与产品里那张同一个结构）。 */
const tool = (tone, letter, name, title, meta = '') => `
  <div class="tool">
    <div class="tool__head" role="button" tabindex="0" aria-expanded="false">
      <span class="tool__chevron">▸</span>
      <span class="tool__badge tool__badge--${tone}">${letter}</span>
      <span class="tool__name">${name}</span>
      <span class="tool__title">${title}</span>
      <span class="tool__status">${meta}</span>
    </div>
  </div>`

/** 子代理那一块的内容。抽出来是因为 ④ 要再用一次（那里不能用带 id 的那两份） */
const subagentBody = () => `
      <div class="subagent" data-testid="subagent-block">
        <div class="subagent__head">子代理的对话<span class="subagent__count">3 次工具调用</span></div>
        <div class="thinking-block">
          <div class="thinking-text">先全局搜 refreshToken(，再确认哪几处走的是真刷新路径…</div>
        </div>
        ${tool('run', 'G', 'Grep', 'refreshToken\\(', '12 处')}
        ${tool('read', 'R', 'Read', 'TokenStore.kt')}
        ${tool('run', 'B', 'Bash', './gradlew test --tests *Token*', '✓ 4.2s')}
        <div class="row row--assistant">
          <div class="bubble bubble--assistant"><div class="bubble__text">三处真调用点：TokenStore / SessionGate / LoginFlow。</div></div>
        </div>
      </div>`

/** Task 卡 + 里面的子代理那一块（展开态）。 */
const taskOpen = () => `
  <div class="tool" id="task">
    <div class="tool__head" role="button" tabindex="0" aria-expanded="true">
      <span class="tool__chevron is-open">▸</span>
      <span class="tool__badge tool__badge--task">T</span>
      <span class="tool__name">Task</span>
      <span class="tool__title">找一下 token 刷新的调用点</span>
      <span class="tool__status"><span class="spin"></span></span>
    </div>
    <div class="tool__body">${subagentBody()}</div>
  </div>`

/** 同一张卡，收起态 —— 卡面只剩一行。 */
const taskClosed = () => `
  <div class="tool" id="task-closed">
    <div class="tool__head" role="button" tabindex="0" aria-expanded="false">
      <span class="tool__chevron">▸</span>
      <span class="tool__badge tool__badge--task">T</span>
      <span class="tool__name">Task</span>
      <span class="tool__title">找一下 token 刷新的调用点</span>
      <span class="tool__status"><span class="spin"></span></span>
    </div>
  </div>`

/**
 * ④ 用：同一张卡插在**主线程正文中间**。展开 / 收起各画一份。
 *
 * 这一节是 2026-09-18 那次撤销的依据。A1 当天给这张卡破过一次例：第一次冒出
 * 子项就自动展开。装上一看不行 —— 子代理那块（上面那几百字）展开后整块插在
 * 主线程叙述**中间**，把正在读的段落顶开。用户的原话："它在文本中间输出，很难看"。
 *
 * 两份并排：收起时它只是流水里的一行，展开时它把正文切成了两截。看不出差别的话，
 * 就是这张图白画了。**不带 id** —— 上面 ① ② 那两份已经用了 `task` / `task-closed`，
 * 重名会让量数字那段脚本取错元素。
 */
const inProse = (open) => `
  <div class="panel">
    <div class="row row--assistant"><div class="bubble bubble--assistant"><div class="bubble__text">先看一遍现有的 16 条覆盖到哪儿。我同时派一个 Explore 去找调用点，等它回来再决定要不要动 SessionGate。</div></div></div>
    <div class="tool">
      <div class="tool__head" role="button" tabindex="0" aria-expanded="${open}">
        <span class="tool__chevron${open ? ' is-open' : ''}">▸</span>
        <span class="tool__badge tool__badge--task">T</span>
        <span class="tool__name">Task</span>
        <span class="tool__title">找一下 token 刷新的调用点</span>
        <span class="tool__status"><span class="spin"></span><span class="tool__time">12s</span></span>
      </div>
      ${open ? `<div class="tool__body">${subagentBody()}</div>` : ''}
    </div>
    <div class="row row--assistant"><div class="bubble bubble--assistant"><div class="bubble__text">结论：不用改 SessionGate。上面三处里只有一处走真刷新路径，另两处是缓存命中。</div></div></div>
  </div>`

/** 再嵌一层（子代理里又派了一个）。 */
const nestedTwice = () => `
  <div class="tool" id="task2">
    <div class="tool__head" role="button" tabindex="0" aria-expanded="true">
      <span class="tool__chevron is-open">▸</span>
      <span class="tool__badge tool__badge--task">T</span>
      <span class="tool__name">Task</span>
      <span class="tool__title">把测试跑通</span>
    </div>
    <div class="tool__body">
      <div class="subagent">
        <div class="subagent__head">子代理的对话<span class="subagent__count">2 次工具调用</span></div>
        ${tool('run', 'B', 'Bash', './gradlew test')}
        <div class="tool">
          <div class="tool__head" role="button" tabindex="0" aria-expanded="true">
            <span class="tool__chevron is-open">▸</span>
            <span class="tool__badge tool__badge--task">T</span>
            <span class="tool__name">Task</span>
            <span class="tool__title">查一下这条断言为什么不稳</span>
          </div>
          <div class="tool__body">
            <div class="subagent">
              <div class="subagent__head">子代理的对话<span class="subagent__count">1 次工具调用</span></div>
              ${tool('read', 'R', 'Read', 'FlakyTest.kt')}
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>`

function page(theme) {
  const t = THEMES[theme]
  return `<!doctype html>
<html lang="zh" data-theme="${theme}">
<head><meta charset="utf-8"><title>子代理嵌套 · 渲染探针</title>
<style>${css}
/* 产品那份在前、探针自己的在后 —— 反过来的话 styles.css 里那条
   body{ margin:0; padding:0; overflow:hidden } 会把这里的页边距与底色全顶掉
   （第一次截图就是这么拍出一张"贴边、右边被裁"的图的）。
   注意这一段整块套在外层模板串里：**反引号会把外层截断**，第二次踩了。 */
:root {${vars(t)}}
* { box-sizing: border-box; }
body { margin: 0 !important; padding: 22px !important; height: auto !important;
       overflow: visible !important; background: ${t.page} !important; color: ${t.pageFg};
       font-family: "Segoe UI", sans-serif; font-size: 13px; }
h2 { font-size: 13px; color: ${t.pageDim}; font-weight: 600; margin: 18px 0 8px;
     font-family: "Cascadia Mono", Consolas, monospace; }
.panel { width: 420px; background: var(--bg); border: 1px solid var(--border);
         border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 9px; }
</style></head>
<body>
  <h2>① 展开态：Task 卡里嵌着子代理的对话</h2>
  <div class="panel" id="p1">
    <div class="row row--user"><div class="bubble bubble--user"><div class="bubble__text">找一下 token 刷新的调用点</div></div></div>
    <div class="row row--assistant"><div class="bubble bubble--assistant"><div class="bubble__text">我派一个 Explore 去搜。</div></div></div>
    ${taskOpen()}
  </div>

  <h2>② 收起态：卡面只剩一行，但数字在</h2>
  <div class="panel" id="p2">${taskClosed()}</div>

  <h2>③ 再嵌一层：缩进还剩多少地方</h2>
  <div class="panel" id="p3">${nestedTwice()}</div>

  <h2>④ 插在主线程正文中间 —— 上：展开（A1 曾自动变成这样）；下：收起（2026-09-18 改回）</h2>
  <p style="width:420px;margin:0 0 8px;color:${t.pageDim}">子代理那张卡现在**不**自动展开了，但收起不等于藏起来：卡头写着 Task 与那句描述，
     点一下就在。下面两份是同一段对话、同一张卡，只差一个开合。</p>
  ${inProse(true)}
  ${inProse(false)}

<script>
  const r = (sel, fn) => { const el = document.querySelector(sel); return el ? fn(el) : null }
  const sub = document.querySelector('.subagent')
  const inner = sub ? sub.querySelector('.tool') : null
  const task = document.getElementById('task')
  const closed = document.getElementById('task-closed')
  const deep = document.querySelector('#task2 .subagent .subagent .tool')
  const out = {
    indent: sub && task
      ? Math.round(sub.getBoundingClientRect().left - task.getBoundingClientRect().left) : null,
    cardW: inner ? Math.round(inner.getBoundingClientRect().width) : null,
    taskW: task ? Math.round(task.getBoundingClientRect().width) : null,
    overflow: sub ? sub.scrollWidth - sub.clientWidth : null,
    deepCardW: deep ? Math.round(deep.getBoundingClientRect().width) : null,
    headY: r('#task .tool__head', (e) => Math.round(e.getBoundingClientRect().height)),
    closedHeadY: r('#task-closed .tool__head', (e) => Math.round(e.getBoundingClientRect().height)),
  }
  // 数字**写进页面**而不是打控制台：headless 截图那条路上 stdout 拿不回来，
  // 而看图的人正好也该看见这几个数（它们就是"缩进够不够"的判据）。
  // **别在这里用模板串**：这段脚本本身套在外层的模板串里，反引号会把外层截断
  let lines = '[嵌套探针]\\n'
  Object.keys(out).forEach(function (k) {
    lines += '  ' + k + ' '.repeat(Math.max(0, 11 - k.length)) + out[k] + '\\n'
  })
  const pre = document.createElement('pre')
  pre.id = 'metrics'
  pre.style.cssText = 'margin-top:16px;font:11px/1.6 Consolas,monospace;color:#9da0a8;white-space:pre'
  pre.textContent = lines
  document.body.appendChild(pre)
  document.title = 'ok'
</script>
</body></html>`
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
if (shotArg) {
  const theme = shotArg.includes('light') ? 'light' : 'dark'
  const chromium = CHROMIUM_CANDIDATES.find(existsSync)
  if (!chromium) {
    console.error('找不到 Chromium，截图跳过')
    process.exitCode = 2
  } else {
    let target = outFile
    let name = 'subagent-nesting-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'subagent-nesting-light.html')
      writeFileSync(target, page('light'))
      name = 'subagent-nesting-light.png'
    }
    // 量出来的数从控制台捞回来（headless 下 stdin 拿不到，走 --dump-dom 太笨）：
    // 把 console 的那行塞进 title，截图前用 --virtual-time-budget 保证脚本跑完
    execFileSync(chromium, [
      '--headless=new', '--disable-gpu', '--no-sandbox',
      // 高要装得下 ④ 那两份（展开那份很高）—— 只到 1400 的话收起那份永远不在图里，
      // 而 ④ 的价值正在"两份并排看"（2026-09-18 实测：1400 时下面那份被裁掉）
      '--window-size=520,2600', '--virtual-time-budget=1500',
      `--screenshot=${join(outDir, name)}`,
      '--enable-logging=stderr', '--v=0',
      'file:///' + target.replace(/\\/g, '/'),
    ], { stdio: ['ignore', 'ignore', 'pipe'] })
    console.log('截图 →', join(outDir, name))
  }
}
