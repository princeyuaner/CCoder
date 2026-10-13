#!/usr/bin/env node
/**
 * 「本轮改动」卡的渲染检查台 —— 出图用，不参与构建。
 *
 * 为什么要有它：这张卡的五个状态（收起 / 展开 / 选择 / 确认 / 已回滚+失败）
 * 在单测里全是"DOM 里有几个元素"，而**观感**（420px 窄栏下路径省不省得干净、
 * 标记挤不挤、确认态的后果行读不读得懂、明暗两套主题下对比度够不够）
 * 单测一个字都说不出来 —— 这个仓库在"单测全绿、屏幕上不对"上付过代价
 * （见 layout-probe 的头部注释）。
 *
 * 保真规则（照抄 tools/subagent-nesting-probe.mjs 的做法）：
 *   1. 样式用的是**产品那份 styles.css**（直接读进来内联），一个色号都不手写；
 *   2. 主题变量取自 New UI 的真实常量（真机由 ThemeInjector 注入），
 *      明暗两套都渲染 —— 卡上那几颗绿/红小字换一套主题就可能看不见；
 *   3. DOM 结构照 TurnChangesCard.tsx 手抄（这是**漂移风险**：组件改了这里
 *      不会自己跟着变。改动那张卡时顺手跑一眼这个探针）。
 *
 * 用法：
 *   node tools/turn-changes-view.mjs --open        # 生成并用默认浏览器打开
 *   node tools/turn-changes-view.mjs --shot        # 生成并截图（深色）
 *   node tools/turn-changes-view.mjs --shot=light  # 截图浅色
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const outDir = resolve(webDir, '..', 'build', 'probe')
const outFile = join(outDir, 'turn-changes-view.html')
const css = readFileSync(join(webDir, 'src', 'styles.css'), 'utf8')

const THEMES = {
  dark: {
    page: '#161719', pageFg: '#e8eaed', pageDim: '#8b8f97', pageCard: '#1c1e21', pageLine: '#26282c',
    bg: '#2b2d30', surface: '#1e1f22', text: '#dfe1e5', dim: '#9da0a8', border: '#393b40',
    accent: '#2f65ca', accentText: '#7fb0ff', code: '#1e1f22', add: '#5fad65', del: '#e05454',
  },
  light: {
    page: '#f4f5f7', pageFg: '#1e1f22', pageDim: '#6c707e', pageCard: '#ffffff', pageLine: '#e0e2e7',
    bg: '#f7f8fa', surface: '#ffffff', text: '#000000', dim: '#7a7a7a', border: '#d1d1d9',
    accent: '#3574f0', accentText: '#3574f0', code: '#f2f3f5', add: '#3d8b43', del: '#c0392b',
  },
}

const vars = (t) => `
  --bg:${t.bg}; --surface:${t.surface}; --text:${t.text}; --text-dim:${t.dim};
  --border:${t.border}; --accent:${t.accent}; --accent-text:${t.accentText};
  --code-bg:${t.code}; --diff-add-fg:${t.add}; --diff-del-fg:${t.del};
  --font-ui:"Segoe UI",sans-serif; --font-mono:"Cascadia Mono",Consolas,monospace;`

// ---------------------------------------------------------------- DOM 片段

const ICON = `<span class="turn-changes__icon" aria-hidden="true">
  <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round">
    <path d="M3 3.5 h8"/><path d="M3 7 h5.5"/><path d="M3 10.5 h8"/>
    <path d="M11.4 6.2 v2.6 M10.1 7.5 h2.6" stroke-opacity=".9"/>
  </svg></span>`

/** 一行。`dir`/`name` 按 splitPath 的规则拆好（这里直接给两段）。 */
function row({ dir, name, delta, tag = '', err = '', checked = false, cb = 'on', openVisible = false, done = false }) {
  return `<div class="turn-row${done ? ' turn-row--done' : ''}">
    <input type="checkbox" class="turn-row__cb" ${checked ? 'checked' : ''} ${cb === 'off' ? 'disabled' : ''}>
    <button type="button" class="turn-row__path turn-row__path--link">
      <span class="turn-row__dir">${dir}</span><span class="turn-row__name">${name}</span>
    </button>
    ${tag}
    ${err !== '' ? `<span class="turn-row__err">${err}</span>` : ''}
    <span class="turn-row__delta">${delta}</span>
    <button type="button" class="turn-row__open"${openVisible ? ' style="opacity:1"' : ''}>↗</button>
  </div>`
}

const ADD = (n) => `<span class="turn-add">+${n}</span>`
const DEL = (n) => `<span class="turn-del">−${n}</span>`
const TAG = (cls, text) => `<span class="turn-tag${cls ? ' ' + cls : ''}">${text}</span>`

function head({ open }) {
  return `<div class="turn-changes__head">
    ${ICON}
    <span class="turn-changes__title">本轮改动</span>
    <span class="turn-changes__count">3 个文件</span>
    <span class="turn-changes__delta">${ADD(117)}${DEL(3)}</span>
    <span class="turn-changes__spacer"></span>
    ${open ? `<button type="button" class="turn-btn">在 IDE 查看</button>
      <button type="button" class="turn-btn">全部回滚</button>` : ''}
    <button type="button" class="turn-btn turn-btn--ghost">${open ? '收起' : '展开'}</button>
  </div>`
}

const bar = (n) => `<div class="turn-bar">
  <span>已选 ${n} 个</span><span class="turn-changes__spacer"></span>
  <button type="button" class="turn-btn turn-btn--ghost">清除</button>
  <button type="button" class="turn-btn turn-btn--danger">回滚所选</button>
</div>`

function states() {
  const common = [
    row({ dir: 'src/main/java/db/', name: 'JdbcBookingStore.java', delta: ADD(3) }),
    row({ dir: 'src/main/java/booking/', name: 'BookingService.java', delta: ADD(3) + DEL(3) }),
  ]
  return `
  <div class="col">
    <p class="cap">① 收起（默认，卡片乙）</p>
    <div class="turn-changes">${head({ open: false })}</div>

    <p class="cap">② 展开：普通行 + 底部的选择操作条</p>
    <div class="turn-changes">${head({ open: true })}
      <div class="turn-changes__body">
        ${common[0]}
        ${common[1]}
        ${bar(2)}
      </div>
    </div>
  </div>

  <div class="col">
    <p class="cap">②（续）三种标记 + 长路径省略（目录先被吃掉，文件名永远看得见）</p>
    <div class="turn-changes">
      <div class="turn-changes__body">
        ${row({ dir: 'src/main/java/booking/reservation/domain/aggregate/', name: 'BookingService.java', delta: ADD(3) + DEL(3) })}
        ${row({ dir: 'src/test/java/booking/', name: 'BookingServiceTest.java', delta: ADD(111), tag: TAG('turn-tag--new', '新') })}
        ${row({ dir: 'resources/sql/migrations/', name: 'V42__booking.sql', delta: '', tag: TAG('', '无存档'), cb: 'off' })}
      </div>
      <div class="turn-bar">
        <span>已选 2 个</span><span class="turn-changes__spacer"></span>
        <button type="button" class="turn-btn turn-btn--ghost">清除</button>
        <button type="button" class="turn-btn turn-btn--danger">回滚所选</button>
      </div>
    </div>

    <p class="cap">④ 确认态（卡内确认甲；焦点在取消上）</p>
    <div class="turn-changes">
      <div class="turn-changes__head">
        <span class="turn-changes__title">回滚 2 个文件？</span>
        <span class="turn-changes__spacer"></span>
        <span class="turn-changes__count">此操作不可撤销</span>
      </div>
      <div class="turn-changes__body">
        <div class="turn-row"><span class="turn-mark turn-mark--rec">恢复</span>
          <span class="turn-row__path"><span class="turn-row__dir">src/main/java/booking/</span><span class="turn-row__name">BookingService.java</span></span>
          <span class="turn-row__why">回到本轮改动前</span></div>
        <div class="turn-row"><span class="turn-mark turn-mark--del">删除</span>
          <span class="turn-row__path"><span class="turn-row__dir">src/test/java/booking/</span><span class="turn-row__name">BookingServiceTest.java</span></span>
          <span class="turn-row__why">本轮新建</span></div>
        <p class="turn-changes__note">回滚只动文件：对话不变。本轮之后对它们的手动改动也会被一并覆盖。</p>
        <div class="turn-bar"><span class="turn-changes__spacer"></span>
          <button type="button" class="turn-btn turn-btn--focus">取消</button>
          <button type="button" class="turn-btn turn-btn--danger">回滚</button></div>
      </div>
    </div>
  </div>

  <div class="col">
    <p class="cap">⑤ 回滚之后：成功（含 dirty）/ 失败 / 已回滚的行</p>
    <div class="turn-changes">
      <div class="turn-changes__body">
        ${row({ dir: 'src/main/java/booking/', name: 'BookingService.java', delta: '', tag: TAG('turn-tag--ok', '已回滚') + TAG('', '覆盖了之后的新改动'), cb: 'off', done: true })}
        ${row({ dir: 'src/main/java/db/', name: 'JdbcBookingStore.java', delta: ADD(3), err: '没有存档' })}
        ${row({ dir: 'src/main/java/util/', name: 'Time.kt', delta: ADD(1), openVisible: true })}
      </div>
    </div>

    <p class="cap">⑥ 忙时：回滚入口禁用（悬停有一句解释）</p>
    <div class="turn-changes">
      ${head({ open: true }).replace(/<button type="button" class="turn-btn">全部回滚<\/button>/,
        '<button type="button" class="turn-btn" disabled title="回滚要在会话空闲时做">全部回滚</button>')}
    </div>

    <p class="cap">⑦ 文件多时：清单先露 5 行，再多在清单里滚（动作条钉在卡底，不跟着滚）。
      截图里可能看不到滚动条 —— headless 的 overlay 滑块不画；真机 JCEF 会显示（全局 ::-webkit-scrollbar 那套）</p>
    <div class="turn-changes">
      <div class="turn-changes__body">
        <div class="turn-changes__scroll">
          ${[
            ['src/main/kotlin/com/ccoder/ui/', 'ComposerModel.kt', ADD(252) + DEL(54)],
            ['src/main/kotlin/com/ccoder/ui/', 'PickerList.kt', ADD(15) + DEL(9)],
            ['src/main/kotlin/com/ccoder/ui/', 'ClaudePanel.kt', ADD(59) + DEL(23)],
            ['src/test/kotlin/com/ccoder/ui/', 'ComposerModelRenderProbe.kt', ADD(39) + DEL(23)],
            ['src/test/kotlin/com/ccoder/ui/', 'ComposerModelTest.kt', ADD(242) + DEL(161)],
            ['src/main/kotlin/com/ccoder/ui/', 'TranscriptSplit.kt', ADD(30) + DEL(5)],
            ['web/src/components/', 'TurnChangesCard.tsx', ADD(12) + DEL(4)],
            ['web/src/', 'styles.css', ADD(14) + DEL(2)],
          ].map(([d, n, delta]) => row({ dir: d, name: n, delta })).join('')}
        </div>
        ${bar(2)}
      </div>
    </div>
  </div>`
}

// ---------------------------------------------------------------- 页面

function page(theme) {
  const t = THEMES[theme]
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<style>${css}</style>
<style>
  html, body { height: auto; }
  :root { ${vars(t)} }
  body { background: ${t.page}; color: ${t.pageFg}; font-family: ${'var(--font-ui, "Segoe UI", sans-serif)'};
         padding: 26px 30px 60px; margin: 0; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: ${t.pageDim}; font-size: 13px; margin: 0 0 22px; }
  .grid { display: grid; grid-template-columns: 420px 420px 420px; gap: 26px; align-items: start; }
  .col { min-width: 0; }
  .cap { font-size: 11px; color: ${t.pageDim}; font-family: var(--font-mono, monospace); margin: 16px 2px 6px; }
  .cap:first-child { margin-top: 0; }
</style></head>
<body>
  <h1>「本轮改动」卡 · 渲染检查台（${theme}）</h1>
  <p class="sub">420px 窄栏（真机工具窗口宽度）· DOM 照 TurnChangesCard.tsx 手抄 · 只看了外观，行为在单测里</p>
  <div class="grid">${states()}</div>
</body></html>`
}

// ---------------------------------------------------------------- CLI

const CHROMIUM_CANDIDATES = [
  process.env.CCoder_CHROMIUM,
  process.env.CHROMIUM_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
].filter(Boolean)

mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, page('dark'))

const shotArg = process.argv.find((a) => a.startsWith('--shot'))
if (shotArg) {
  const theme = shotArg.includes('light') ? 'light' : 'dark'
  const chromium = CHROMIUM_CANDIDATES.find(existsSync)
  if (!chromium) {
    console.error('找不到 Chromium，截图跳过')
    process.exitCode = 2
  } else {
    let target = outFile
    let name = 'turn-changes-view-dark.png'
    if (theme === 'light') {
      target = join(outDir, 'turn-changes-view-light.html')
      writeFileSync(target, page('light'))
      name = 'turn-changes-view-light.png'
    }
    execFileSync(
      chromium,
      ['--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
        '--window-size=1360,1500', '--virtual-time-budget=2000',
        `--screenshot=${join(outDir, name)}`, 'file:///' + target.replace(/\\/g, '/')],
      { stdio: 'ignore' },
    )
    console.log('截图 →', join(outDir, name))
  }
}
if (process.argv.includes('--open')) {
  execFileSync('cmd', ['/c', 'start', '', 'file:///' + outFile.replace(/\\/g, '/')], { stdio: 'ignore' })
  console.log('已在默认浏览器打开')
}
if (!shotArg && !process.argv.includes('--open')) {
  console.log('生成 →', outFile, '（--open 打开，--shot / --shot=light 截图）')
}
