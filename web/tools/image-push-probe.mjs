#!/usr/bin/env node
/**
 * 贴图推送探针 —— 往**打好的那一份 bundle** 里，走真入口
 * （`window.ccoder.pushBatch`，与 Kotlin 推送时用的同一条路）推一条带图的
 * 用户消息，量转写区里到底画不画得出缩略图。
 *
 * ## 为什么非要有它（2026-09-15）
 *
 * 现象：Kotlin 把图推出了、消息也发出去了（模型确实收到了图），转写区里却只有
 * 文字。根因在 `web/src/codec.ts` 的 `parseItem` —— 它是**逐字段重建** item 的，
 * 漏了 `images`，于是静默丢掉。
 *
 * 当时三处测试全绿，因为它们各自绕开了这条路：
 *   - `UserBubble.test.tsx` 直接把图当 props 喂进去（没经过 codec）
 *   - `layout-probe.mjs` 自己拼 HTML 量布局（根本没经过 codec）
 *   - 共享夹具 `shared/transcript-ops.json` 里**一条带图的用例都没有**
 * 也就是说：**没有任何东西守着"图从推送到上屏"这条路**，它坏了没人知道。
 *
 * 这个探针走的就是那条路：
 *   dist/index.html（JCEF loadHTML 的那一份）
 *     → window.ccoder.pushBatch(json) → parseOps → applyOps → Transcript → UserBubble
 * 然后量缩略图有没有、多大、文字在不在。
 *
 * 用法：
 *   npm run build && npm run probe:image
 *   CCoder_CHROMIUM="C:\path\to\msedge.exe" npm run probe:image
 *
 * 退出码：0 = 通过；1 = 有断言失败；2 = 找不到浏览器（**不算通过**）。
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const distPath = join(webDir, 'dist', 'index.html')
const outDir = resolve(webDir, '..', 'build', 'probe')

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

/** 1×1 的 gif：这里量的是"有没有、多大"，不是画质。 */
const PIXEL = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'

/** 面板宽度。设计稿与 layout-probe 用的都是 420，这里量布局也钉在同一个数上。 */
const PANEL_W = 420

/**
 * 把测量脚本接到**打好的那一份** HTML 后面。
 *
 * 不在探针里重新组装页面：`dist/index.html` 才是 JCEF 里真正被 loadHTML 的那一份，
 * 重新组装就等于又绕开了要验的东西（这正是上一个探针的教训）。
 */
function buildPage() {
  const html = readFileSync(distPath, 'utf8')
  // 脚本等在桥出现之后再推，与 Kotlin 的时序一致（Kotlin 也是收到 ready 才推）
  const script = `
<script>
(function () {
  // 面板的真实宽度。不能只靠 --window-size：Edge 会把窗口钳到 ~500 的最小宽度，
  // 420 的窗口根本量不到 420 的布局（2026-09-15 实测 viewport=492）
  document.documentElement.style.width = '${PANEL_W}px'

  const batch = JSON.stringify([
    { op: 'append', item: { kind: 'user', id: 'u1', ts: 1726050000000,
      text: '这两张你看下', images: ['${PIXEL}', '${PIXEL}'] } },
  ])
  let tries = 0
  const wait = setInterval(function () {
    if (!window.ccoder || typeof window.ccoder.pushBatch !== 'function') {
      if (++tries > 40) { clearInterval(wait); report({ pushed: false }) }
      return
    }
    clearInterval(wait)
    try {
      window.ccoder.pushBatch(batch)
      setTimeout(measure, 300)
    } catch (e) {
      report({ pushed: false, error: String(e) })
    }
  }, 25)

  function measure() {
    const imgs = Array.prototype.slice.call(document.querySelectorAll('.bubble__image img'))
    const box = document.querySelector('.bubble__images')
    const bubble = document.querySelector('.bubble--user')
    const row = document.querySelector('.row--user')
    const transcript = document.querySelector('.transcript')
    const text = document.querySelector('.bubble--user .bubble__text')
    const rect = (el) => (el ? el.getBoundingClientRect().right : -1)
    report({
      pushed: true,
      thumbs: imgs.length,
      thumbW: imgs.length ? Math.round(imgs[0].getBoundingClientRect().width) : -1,
      thumbH: imgs.length ? Math.round(imgs[0].getBoundingClientRect().height) : -1,
      imagesBox: box ? 1 : 0,
      text: text ? text.textContent : null,
      srcPrefix: imgs.length ? String(imgs[0].getAttribute('src')).slice(0, 24) : null,
      // 图排不排得下：气泡自己溢不溢出、气泡有没有越出转写区/窗口（右边被裁就是它）
      bubbleOverflow: bubble ? bubble.scrollWidth - bubble.clientWidth : -99,
      bubbleRight: Math.round(rect(bubble)),
      transcriptRight: Math.round(rect(transcript)),
      // 浏览器窗口宽度（Edge 会钳到 ~500）。布局宽度不看它 —— 看转写区右沿
      windowW: window.innerWidth,
      rowOverflow: row ? row.scrollWidth - row.clientWidth : -99,
      docOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    })
  }

  function report(o) {
    const el = document.createElement('div')
    el.id = 'ccoder-probe'
    el.textContent = 'MEASURE ' + JSON.stringify(o)
    document.body.appendChild(el)
  }
})()
</script>
`
  return html + script
}

const chromium = findChromium()
if (!chromium) {
  console.error('找不到 Chromium（Edge / Chrome）。这个探针**必须**用真实浏览器渲染，')
  console.error('没有它就没有任何东西守着"图从推送到上屏"这条路。可指定路径：')
  console.error('  CCoder_CHROMIUM="C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"')
  process.exit(2)
}

if (!existsSync(distPath)) {
  console.error(`没有打好的前端：${distPath}\n先 npm run build（或 gradle buildWebUi）。`)
  process.exit(2)
}

// dist 比源码旧就提醒一句：验的是打好的那一份，源码改了没重打就没意义
const newestSrc = ['src/codec.ts', 'src/components/UserBubble.tsx', 'src/App.tsx']
  .map((f) => statSync(join(webDir, f)).mtimeMs)
  .reduce((a, b) => Math.max(a, b), 0)
if (statSync(distPath).mtimeMs < newestSrc) {
  console.warn('⚠ dist/index.html 比源码旧 —— 这份探针验的是打好的产物，先 npm run build')
}

const dir = mkdtempSync(join(tmpdir(), 'ccoder-image-'))
const page = join(dir, 'page.html')
writeFileSync(page, buildPage())

const url = 'file:///' + page.replace(/\\/g, '/')
const args = [
  '--headless=new',
  '--disable-gpu',
  '--no-sandbox',
  '--virtual-time-budget=4000',
  '--window-size=420,760',
  '--dump-dom',
  url,
]

const dom = execFileSync(chromium, args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
const m = dom.match(/MEASURE (\{.*?\})/)
if (!m) {
  console.error('页面没有回报测量值：前端没起来，或者注入的脚本没跑。')
  process.exit(1)
}
const measured = JSON.parse(m[1].replace(/&quot;/g, '"'))

mkdirSync(outDir, { recursive: true })
const shot = join(outDir, 'image-push.png')
execFileSync(
  chromium,
  ['--headless=new', '--disable-gpu', '--no-sandbox', '--virtual-time-budget=4000',
   // 比面板宽一截：布局钉在 420，窗口再宽也只看 420 那部分，右边留白免得
   // 把气泡右侧截掉（Edge 会钳窗口最小宽度，420 的窗口截出来是裁过的画面）
   '--window-size=560,600', `--screenshot=${shot}`, url],
  { stdio: 'ignore' },
)

const checks = [
  [measured.pushed === true, `推送没发生（桥没到？${measured.error ?? ''}）`],
  [measured.thumbs === 2, `推了 2 张图，转写区里只有 ${measured.thumbs} 张 —— images 在 codec 里被丢了？`],
  [measured.imagesBox === 1, '连 .bubble__images 这个容器都没有'],
  [measured.thumbW >= 120, `缩略图宽 ${measured.thumbW}px（定宽 150，太窄就是布局塌了）`],
  [measured.text === '这两张你看下', `文字那条路也坏了：${JSON.stringify(measured.text)}`],
  // 两张 150 的缩略图 + 间距在一个 420 宽的面板里排不排得下：排不下就该换行，
  // 不许越出转写区（越出去右边缘会被裁掉，看截图才发现的那种问题）
  // 转写区必须正好落在钉住的面板宽度里 —— 否则下面几条量的就不是 420 的面板
  [Math.abs(measured.transcriptRight - PANEL_W) <= 1,
    `转写区右沿 ${measured.transcriptRight}，没落在 ${PANEL_W} 的面板里（量出来的数就不算数）`],
  [measured.bubbleOverflow <= 1, `气泡被内容撑破了 ${measured.bubbleOverflow}px（该换行却没换）`],
  [measured.bubbleRight <= measured.transcriptRight + 1,
    `气泡越出了转写区：右边 ${measured.bubbleRight} > 转写区 ${measured.transcriptRight}`],
  [measured.docOverflow === 0, `页面横向能滚 ${measured.docOverflow}px —— 内容比窗口宽`],
]

const failed = checks.filter(([ok]) => !ok)
console.log(
  `贴图推送：thumbs=${measured.thumbs} 宽=${measured.thumbW}×${measured.thumbH}` +
  ` src=${measured.srcPrefix} 文字=${JSON.stringify(measured.text)}`,
)
console.log(
  `布局：面板右沿=${measured.transcriptRight}（窗口=${measured.windowW}） 气泡右=${measured.bubbleRight}` +
  ` 气泡溢出=${measured.bubbleOverflow} 页面横滚=${measured.docOverflow}`,
)
console.log(`截图：${shot}`)
if (failed.length > 0) {
  console.error('✗ 贴图推送探针失败：')
  failed.forEach(([, why]) => console.error('  - ' + why))
  process.exit(1)
}
console.log('✓ 图从 pushBatch 一路走到了转写区的缩略图上')
