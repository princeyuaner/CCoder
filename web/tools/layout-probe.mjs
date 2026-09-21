#!/usr/bin/env node
/**
 * 布局探针 —— 用**真实 Chromium** 量关键元素的高度。
 *
 * 为什么必须有它：jsdom 不做布局。`web/src/*.test.tsx` 能证明 DOM 里有 86 个
 * `.tool` 元素，却看不出它们在屏幕上是 29px 还是 2px。2026-09-13 的
 * "工具调用一个都不显示"就是这么来的 ——
 *
 *   `.transcript` 是高度受限的 flex 列，工具卡片是它的直接子项（没有 .entry
 *   包裹），而 `overflow: hidden` 把 flex 项的自动最小尺寸从内容高改成了 0
 *   （CSS Flexbox §4.5）。内容溢出时全部压缩量堆到卡片身上：86 张卡片被压成
 *   只剩两条边框的 2px 细缝，而没设 overflow 的文字气泡纹丝不动。
 *
 * 单测全绿（DOM 正确）、日志全干净（推送成功）、装上就是看不见 —— 这类问题
 * 只有真的渲染一遍才抓得到。与 Kotlin 侧的 `*RenderProbe` 是同一个思路，
 * 这里是它在浏览器里的对应物。
 *
 * 2026-09-14 追加一条：还量卡片头是不是正好占满卡片宽（`headOverflow`）。
 * 卡片头从 `<button>` 变成 `div[role=button]` 之后，div 的 content-box 撞上
 * `width: 100%` 会让它比卡片宽出两个内边距，右侧的状态位直接被裁掉 ——
 * 也是同一类"DOM 全对、屏幕上不对"的问题。
 *
 * 2026-09-14 再追加一条：正文气泡是不是铺满了整行（`bubbleW` vs `rowW`）。
 * 92% 的 max-width 光看 CSS 不觉得窄，但它只作用在气泡上，而工具卡片是
 * `.transcript` 的直接子项、天然满宽 —— 并排出现就成了"正文没对齐"。
 * 这一条同时守着反面：用户气泡**不该**铺满（宽度是"谁在说话"的分栏手段）。
 *
 * 用法：
 *   npm run probe:layout
 *   CCoder_CHROMIUM="C:\path\to\msedge.exe" npm run probe:layout
 *
 * 退出码：0 = 全部通过；1 = 有断言失败；2 = 找不到浏览器（**不算通过**）。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const cssPath = join(webDir, 'src', 'styles.css')
const outDir = resolve(webDir, '..', 'build', 'probe')

/** 常见安装位置。可以用环境变量覆盖。 */
const CHROMIUM_CANDIDATES = [
  process.env.CCoder_CHROMIUM,
  process.env.CHROMIUM_PATH,
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].filter(Boolean)

function findChromium() {
  for (const p of CHROMIUM_CANDIDATES) {
    if (existsSync(p)) return p
  }
  return null
}

/**
 * 场景表。
 *
 * `overflow` 那条是 2026-09-13 事故的**真实规模**（38 条文字 + 86 张卡片），
 * 必须留着 —— 内容不溢出时 flex 压缩根本不触发，只用小场景是量不出问题的。
 */
const SCENARIOS = [
  { name: 'overflow', note: '内容严重溢出（38 文字 + 86 卡片）', entries: 38, tools: 86, thinking: 6, notes: 3 },
  { name: 'fits', note: '内容不溢出（2 文字 + 2 卡片）', entries: 2, tools: 2, thinking: 1, notes: 1 },
]

/** 卡片高度下限。卡片头是 5px 内边距 + 一行 12px 文字 + 2px 边框 ≈ 29px。 */
const MIN_CARD_H = 20
const MIN_HEAD_H = 16

function buildPage(scenario) {
  return `<!doctype html>
<html><head><meta charset="utf-8">
<link rel="stylesheet" href="file:///${cssPath.replace(/\\/g, '/')}">
<style>html, body { height: 100%; }</style>
</head><body>
<div class="transcript-wrap">
  <div class="transcript" id="t"></div>
</div>
<div id="measure" style="position:fixed;left:-9999px">PENDING</div>
<script>
  const t = document.getElementById('t')
  const S = ${JSON.stringify(scenario)}
  // DOM 结构照抄 Transcript.tsx：文字项有 .entry 包裹（真气泡还多一层 .row，
  // 见 AssistantBubble.tsx），thinking / toolUse / systemNote 都**没有**
  //
  // 第一条正文必须是**长文本**：短气泡本来就贴着内容（实测 69px），量它
  // 证明不了"铺满"这件事 —— 宽度上限只有文本长到能把行撑满时才看得出来
  const LONG = '这一段是长正文，用来把行撑满，好量气泡到底占多宽。'.repeat(24)
  for (let i = 0; i < S.entries; i++) {
    const d = document.createElement('div')
    d.className = 'entry'
    d.innerHTML = '<div class="row row--assistant">' +
      '<div class="bubble bubble--assistant"><div class="bubble__text">' +
      (i === 0 ? LONG : '文字气泡 ' + i) +
      '</div></div></div>'
    t.appendChild(d)
  }
  // 一条**长**用户消息：它该仍然是窄气泡（85%）—— 助手侧铺满不等于全铺满，
  // 宽度是"谁在说话"的分栏手段
  const u = document.createElement('div')
  u.className = 'entry'
  // 再加一条**带图**的用户消息（贴图那条路）：缩略图是定宽 150 的 flex 行，
  // 窄栏里两张就该换行而不是把气泡撑破 —— 这条由下面的 imagesOverflow 守着。
  // 图用 1×1 的 data URL：这里量的是布局，不是画质
  const PIXEL = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
  u.innerHTML = '<div class="row row--user">' +
    '<div class="bubble bubble--user"><div class="bubble__text">' + LONG + '</div></div></div>' +
    '<div class="row row--user">' +
    '<div class="bubble bubble--user" id="u-img">' +
    '<div class="bubble__images">' +
    '<button type="button" class="bubble__image"><img src="' + PIXEL + '"></button>' +
    '<button type="button" class="bubble__image"><img src="' + PIXEL + '"></button>' +
    '</div><div class="bubble__text">这两张你看下</div></div></div>'
  t.appendChild(u)
  for (let i = 0; i < S.thinking; i++) {
    const d = document.createElement('div')
    d.className = 'thinking-text'
    d.textContent = '思考 ' + i
    t.appendChild(d)
  }
  for (let i = 0; i < S.notes; i++) {
    const d = document.createElement('div')
    d.className = 'system-note'
    d.textContent = '系统提示 ' + i
    t.appendChild(d)
  }
  // 状态位四种形态轮着来 —— 它们的宽度必须一致，切换时不能让标题左右抖
  // （设计稿 tool-progress.html 细节①）。红 ✗ 是 2026-09-15 加的失败态
  const STATUS = [
    '<span class="tool__status"><span class="spin"></span>' +
      '<span class="tool__time">12s</span></span>',
    '<span class="tool__status"><svg class="tool__check" viewBox="0 0 16 16">' +
      '<path d="M3.5 8.5l3 3 6.5-7.5"/></svg></span>',
    '<span class="tool__status"><svg class="tool__x" viewBox="0 0 16 16">' +
      '<line x1="4.6" y1="4.6" x2="11.4" y2="11.4"/>' +
      '<line x1="11.4" y1="4.6" x2="4.6" y2="11.4"/></svg></span>',
    '<span class="tool__status"><svg class="tool__abort" viewBox="0 0 16 16">' +
      '<circle cx="8" cy="8" r="6.2"/><line x1="4.4" y1="4.4" x2="11.6" y2="11.6"/></svg></span>',
  ]
  for (let i = 0; i < S.tools; i++) {
    const d = document.createElement('div')
    d.className = 'tool'
    // DOM 结构照抄 ToolCallBlock.tsx：卡片头是 div[role=button]（不是 <button>，
    // 因为卡面上还有一个真的按钮），文件类工具的名字就是那个
    // <button class="tool__file">，它**不带** aria-expanded。
    // 两种卡面交替出现：长路径与长文件名都必须在 420px 里能省略
    const file = i % 2 === 0
    d.innerHTML = '<div class="tool__head" role="button" tabindex="0" aria-expanded="true">' +
      '<span class="tool__chevron is-open">▸</span>' +
      // 徽标从"首字母"换成了图形 + 色调（2026-09-15），这里照抄 ToolCallBlock
      // 的新结构 —— 它对几何没有影响（仍是固定的 15px 方块），但探针既然
      // 号称"DOM 结构照抄"，就不能留着一个已经不存在的结构
      '<span class="tool__badge tool__badge--run">' +
      '<svg class="tool__glyph" viewBox="0 0 12 12">' +
      '<path d="M2.6 3.2 L5.4 5.6 L2.6 8"/><path d="M6.4 8.4 h3.2"/></svg></span>' +
      '<span class="tool__name">Bash</span>' +
      (file
        ? '<button type="button" class="tool__title tool__file">' +
          'SessionSwitchStateTestWithAVeryLongName.kt</button>'
        : '<span class="tool__title">ls -la /some/very/long/path/that/should/ellipsize/' +
          i + '</span>') +
      STATUS[i % STATUS.length] +
      '</div>' +
      // 卡片默认收着，但**展开态**才是高度最大的那种 —— 溢出场景要的就是最坏
      // 情况，所以这里画的是展开体（只画卡头的话量不出压缩问题）
      '<div class="tool__body">' +
      '<div class="tool__cmd"><span class="tool__prompt">$ </span>node --test test/x.test.js</div>' +
      // 这里是**外层模板字符串**里，\\n 才是生成页面里的换行转义
      '<pre class="tool__out">ok 1 - 第一条\\nok 2 - 第二条\\nok 3 - 第三条</pre>' +
      '</div>'
    t.appendChild(d)
  }
  // 一张 4 列表格。DOM 结构照抄 Markdown.tsx 的 table 分支：
  // .md-table > table > thead/tbody，行内代码是 <code class="inline-code">。
  // 内容和 2026-09-18 那张"A 档"表同型：首列是序号，其余列塞着长行内代码 + 中文散文
  //
  // 这张表是来钉**三条继承**的（当天用户截图："表格怎么这么难看"）：
  //   overflow-wrap / white-space 都是从 .bubble / .bubble__text 继承进单元格的，
  //   而「anywhere」会把列宽下限算成 1 个字 —— 自动布局随即把首列压到放不下 "17"，
  //   数字断成上下两行。这类"单测全绿、装上看就是坏的"正是本探针存在的理由。
  const tbl = document.createElement('div')
  tbl.className = 'entry'
  const CELL = (tag, html) => '<' + tag + '>' + html + '</' + tag + '>'
  tbl.innerHTML = '<div class="row row--assistant"><div class="bubble bubble--assistant">' +
    '<div class="bubble__text"><div class="md-table"><table>' +
    '<thead><tr>' +
    CELL('th', '#') + CELL('th', '指令') + CELL('th', '落点') + CELL('th', '为什么需要') +
    '</tr></thead><tbody>' +
    '<tr>' +
    CELL('td', '17') +
    CELL('td', '<code class="inline-code">种植指定作物 &lt;landSID&gt; &lt;cropSID&gt;</code>') +
    CELL('td', 'team <code class="inline-code">cropcom.PlantCrop(player, land, cropSID)</code>（走占用兜底+广播）') +
    CELL('td', '图鉴条目是按 <code class="inline-code">FarmCropData.itemSid</code> 对齐的，作物 3/4 号还要解锁等级+货币') +
    '</tr>' +
    '<tr>' +
    CELL('td', '18') +
    CELL('td', '<code class="inline-code">清空指定田地 &lt;landSID&gt;</code>') +
    CELL('td', 'team <code class="inline-code">scene.RemoveCrop</code> + 广播') +
    CELL('td', '现在只有 4（取消全部解锁）/6（整场重置），单田清理没有') +
    '</tr>' +
    '</tbody></table></div></div></div></div>'
  t.appendChild(tbl)

  t.scrollTop = t.scrollHeight   // 真实页面也会自动滚到底

  const h = (sel) => {
    const el = document.querySelector(sel)
    return el ? el.getBoundingClientRect().height : -1
  }
  const card = t.lastElementChild
  // 卡片头必须正好占满卡片的内容宽。宽出来就说明它比卡片还宽
  // （div 的 content-box 撞上 width:100% 正是这个症状），右侧内边距会被
  // 卡片的 overflow:hidden 裁掉，状态位跟着看不见。
  //
  // :scope > 量的是卡片的**直接**子元素 —— 卡片头正好占满卡片的内容宽
  const overflowOf = (parent, sel) => {
    const hd = parent?.querySelector(':scope > ' + sel)
    if (!hd) return -1
    return Math.round((hd.getBoundingClientRect().width - parent.clientWidth) * 10) / 10
  }
  const headOverflow = overflowOf(t.querySelector('.tool'), '.tool__head')
  // 正文气泡 vs 它那一行的行宽。长文本下两者必须相等 —— 差多少就是右边空了多少
  const widthOf = (sel) => {
    const el = t.querySelector(sel)
    return el ? Math.round(el.getBoundingClientRect().width * 10) / 10 : -1
  }
  const rowW = widthOf('.row')
  const bubbleW = widthOf('.bubble')
  const userW = widthOf('.bubble--user')
  // 带图那条气泡：缩略图定宽 150，两张排不下就该换行，**不许**把气泡撑破
  const imgBubble = document.getElementById('u-img')
  const imagesOverflow = imgBubble ? imgBubble.scrollWidth - imgBubble.clientWidth : -1
  const thumbW = (() => {
    const el = t.querySelector('.bubble__image img')
    return el ? Math.round(el.getBoundingClientRect().width) : -1
  })()
  // 两种标题的**计算样式**必须一致：可点的那半是个 <button>，而按钮不继承
  // 字体与颜色 —— 少写一条就会在同一个卡面上出现"另一种字体、更暗一档"
  // 的文件名，缩略图里根本看不出来
  const styleOf = (sel) => {
    const el = t.querySelector(sel)
    if (!el) return null
    const cs = getComputedStyle(el)
    return cs.fontFamily + ' | ' + cs.fontSize + ' | ' + cs.color
  }
  // 量之前先把动画走完：.tool__body 的 fade-in 从 translateY(4px) 起步，
  // 建完 DOM 就量等于在第 0 帧量它，那 4px 会被记成"卡片内容被裁剪"的假阳性
  // （2026-09-14 实测 client=128 / scroll=132）。无限动画（转圈、光标闪烁）
  // finish() 会抛 —— 它们只转不占高，跳过即可
  for (const a of document.getAnimations()) {
    try {
      a.finish()
    } catch {
      /* 无限动画不能 finish，与布局无关 */
    }
  }
  // 表格：首列序号必须**一行装得下**。Range.getClientRects() 数的是行盒，
  // 断了行就是 2 个 —— 比量高度准（行高会随字体变），也不受内边距干扰
  const numCell = t.querySelector('.md-table tbody td')
  const numRects = (() => {
    if (!numCell) return -1
    const r = document.createRange()
    r.selectNodeContents(numCell)
    return r.getClientRects().length
  })()
  const cellStyle = (() => {
    if (!numCell) return null
    const cs = getComputedStyle(numCell)
    // 三条都必须是**显式写死在单元格上**的：继承来的任意一条都会让这张表回到
    // 2026-09-18 那版难看的样子（见 styles.css 里单元格那段注释）
    return cs.whiteSpace + ' | ' + cs.overflowWrap + ' | ' + cs.verticalAlign
  })()
  // 反面：正文的 pre-wrap 不能跟着一起被"顺手改掉" —— 它负责还原松散列表项
  // 里那个空行（Markdown.tsx 的 space token），去掉之后列表会粘成一坨
  const bubbleWhiteSpace = (() => {
    const el = t.querySelector('.bubble__text')
    return el ? getComputedStyle(el).whiteSpace : null
  })()
  document.getElementById('measure').textContent = 'MEASURE ' + JSON.stringify({
    tool: h('.tool'),
    head: h('.tool__head'),
    entry: h('.entry'),
    thinking: h('.thinking-text'),
    note: h('.system-note'),
    status: h('.tool__status'),
    clipped: card.scrollHeight > card.clientHeight + 1,
    headOverflow: headOverflow,
    bubbleW: bubbleW,
    rowW: rowW,
    userW: userW,
    imagesOverflow: imagesOverflow,
    thumbW: thumbW,
    titleStyle: styleOf('.tool__title:not(.tool__file)'),
    fileStyle: styleOf('.tool__file'),
    // 能不能点不该靠悬停才发现 —— 静止态就得有下划线
    fileDecoration: (() => {
      const el = t.querySelector('.tool__file')
      return el ? getComputedStyle(el).textDecorationLine : null
    })(),
    numRects: numRects,
    cellStyle: cellStyle,
    // 表格字号必须**跟着正文**：Chromium 的 UA 样式表给 table/td 钉了
    // font-size: medium（16px），不显式拉回来就是"表格比正文大一号"，
    // 而且设置里的字号档（--fs-scale）永远到不了表格里（2026-09-21 实测）
    cellFontSize: numCell ? getComputedStyle(numCell).fontSize : null,
    bubbleFontSize: (() => {
      const el = t.querySelector('.bubble__text')
      return el ? getComputedStyle(el).fontSize : null
    })(),
    bubbleWhiteSpace: bubbleWhiteSpace,
    scrollH: t.scrollHeight,
    clientH: t.clientHeight,
  })
</script>
</body></html>`
}

function runScenario(chromium, scenario) {
  const dir = mkdtempSync(join(tmpdir(), 'ccoder-layout-'))
  const page = join(dir, 'page.html')
  writeFileSync(page, buildPage(scenario))

  const url = 'file:///' + page.replace(/\\/g, '/')
  const args = [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--virtual-time-budget=2000',
    '--window-size=420,760',
    '--dump-dom',
    url,
  ]

  const dom = execFileSync(chromium, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
  const m = dom.match(/MEASURE (\{.*?\})/)
  if (!m) throw new Error(`${scenario.name}: 页面没有回报测量值（脚本没跑起来？）`)
  const measured = JSON.parse(m[1].replace(/&quot;/g, '"'))

  mkdirSync(outDir, { recursive: true })
  execFileSync(
    chromium,
    ['--headless=new', '--disable-gpu', '--no-sandbox', '--virtual-time-budget=2000',
     '--window-size=420,500', `--screenshot=${join(outDir, `layout-${scenario.name}.png`)}`, url],
    { stdio: 'ignore' },
  )

  return measured
}

const chromium = findChromium()
if (!chromium) {
  console.error('找不到 Chromium（Edge / Chrome）。这个探针**必须**用真实浏览器量布局，')
  console.error('没有它就没有任何东西在守着这类问题。请设环境变量指定路径：')
  console.error('  CCoder_CHROMIUM="C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"')
  process.exit(2)
}

console.log('布局探针 · 浏览器:', chromium)
console.log('样式表:', cssPath)
console.log()

let failed = 0
for (const scenario of SCENARIOS) {
  const m = runScenario(chromium, scenario)
  const problems = []
  if (m.tool < MIN_CARD_H) problems.push(`.tool 高度 ${m.tool} < ${MIN_CARD_H}`)
  if (m.head < MIN_HEAD_H) problems.push(`.tool__head 高度 ${m.head} < ${MIN_HEAD_H}`)
  if (m.entry < MIN_CARD_H) problems.push(`.entry 高度 ${m.entry} < ${MIN_CARD_H}`)
  if (m.clipped) problems.push('卡片内容被裁剪（scrollHeight > clientHeight）')
  if (m.headOverflow > 1) {
    problems.push(`.tool__head 比卡片宽 ${m.headOverflow}px（div 化以后漏了 box-sizing: border-box?）`)
  }
  // 长正文必须铺满整行：差多少，右边就空多少（92% 那阵子是 8% 行宽）
  if (Math.abs(m.bubbleW - m.rowW) > 1) {
    problems.push(
      `正文气泡宽 ${m.bubbleW} ≠ 行宽 ${m.rowW}（右边空了 ` +
      `${Math.round((m.rowW - m.bubbleW) * 10) / 10}px）—— 又给它设回 max-width 了吗？` +
      '助手侧的文字要与工具卡片一样铺满整行',
    )
  }
  // 反面：用户气泡不该跟着铺满，铺满了"谁在说话"就没有分栏手段了
  if (m.userW > m.rowW - 1) {
    problems.push(
      `用户气泡宽 ${m.userW} 已经顶满行宽 ${m.rowW} —— ` +
      '助手侧铺满不等于全铺满，用户气泡的 85% 要留着',
    )
  }
  // 带图的气泡：定宽缩略图排不下要换行，不许把气泡横向撑破
  if (m.imagesOverflow > 1) {
    problems.push(
      `带图的气泡比内容宽 ${m.imagesOverflow}px —— 缩略图那一排没有换行（flex-wrap 掉了？）`,
    )
  }
  if (m.thumbW !== -1 && m.thumbW !== 150) {
    problems.push(`缩略图宽 ${m.thumbW} ≠ 150 —— 尺寸被别处的规则改掉了`)
  }
  if (m.fileDecoration !== 'underline') {
    problems.push(
      `可点文件名没有下划线（text-decoration-line=${m.fileDecoration}）——` +
      ' 没有它没人知道文件名能点',
    )
  }
  if (m.fileStyle && m.fileStyle !== m.titleStyle) {
    problems.push(
      `可点文件名与命令原文的计算样式不一致：${m.fileStyle} ≠ ${m.titleStyle}` +
      '（<button> 不继承字体与颜色，逐条写出来了吗？）',
    )
  }

  // 表格（2026-09-18）。三条断言对应当天那张"难看"的表里同时发作的三处继承 ——
  // 它们全都来自 .bubble / .bubble__text，光看单元格的 CSS 是看不出来的
  const CELL_STYLE_OK = 'normal | break-word | top'
  if (m.cellStyle !== CELL_STYLE_OK) {
    problems.push(
      `单元格计算样式 ${m.cellStyle} ≠ ${CELL_STYLE_OK} —— ` +
      '这三条必须显式写在 th/td 上：white-space 与 overflow-wrap 都会继承，' +
      'anywhere 会把列宽下限算成 1 个字（首列序号会断行），' +
      'vertical-align 不写则继承 table 的 middle（数字飘在行中间）',
    )
  }
  if (m.numRects > 1) {
    problems.push(
      `首列序号占了 ${m.numRects} 行 —— 列被压瘪了。` +
      'overflow-wrap: anywhere 又回到单元格上了吗？',
    )
  }
  // 表格字号要跟正文一致（第四处"继承坑"，2026-09-21 补）：UA 给 table/td 钉了
  // medium，光看 styles.css 是看不出来的 —— 而正文一缩放，差得就更明显
  if (m.cellFontSize !== null && m.cellFontSize !== m.bubbleFontSize) {
    problems.push(
      `表格单元格字号 ${m.cellFontSize} ≠ 正文字号 ${m.bubbleFontSize} —— ` +
      'Chromium 的 UA 给 table/td 钉了 font-size: medium，' +
      '要在 .bubble__text table 上把字号拉回来（见 styles.css 那段注释）',
    )
  }
  if (m.bubbleWhiteSpace !== 'pre-wrap') {
    problems.push(
      `正文的 white-space 变成了 ${m.bubbleWhiteSpace} —— 它得是 pre-wrap，` +
      '松散列表项里的空行靠它还原（Markdown.tsx 的 space token）。' +
      '修表格要改在 th/td 上，不要动 .bubble__text',
    )
  }

  const status = problems.length ? '失败' : '通过'
  if (problems.length) failed++
  console.log(`[${status}] ${scenario.name} — ${scenario.note}`)
  console.log(
    `         tool=${m.tool} head=${m.head} entry=${m.entry} thinking=${m.thinking}` +
    ` note=${m.note} status=${m.status} clipped=${m.clipped}` +
    ` headOverflow=${m.headOverflow}` +
    ` bubbleW=${m.bubbleW}/${m.rowW} userW=${m.userW}` +
    ` thumbW=${m.thumbW} imagesOverflow=${m.imagesOverflow}` +
    ` numRects=${m.numRects} cell=[${m.cellStyle}] cellFont=${m.cellFontSize}/${m.bubbleFontSize}` +
    ` scrollH=${m.scrollH} clientH=${m.clientH}`,
  )
  for (const p of problems) console.log('         ✗ ' + p)
}

console.log()
console.log(`截图已写到 ${outDir}`)
process.exit(failed ? 1 : 0)
