#!/usr/bin/env node
/**
 * 性能探针 —— 用**真实 Chromium** 量流式输出的每帧代价。
 *
 * ## 为什么必须有它
 *
 * `web/src/streamingCost.test.tsx` 跑在 **jsdom** 里，而 jsdom 没有布局引擎：
 * 它能量出「React 建树 + 协调」的耗时，却对布局、绘制、滚动一律视而不见。
 * 而转写区每帧都有一句
 *
 *     el.scrollTop = el.scrollHeight
 *
 * 读 `scrollHeight` 会**强制浏览器同步布局**（forced reflow），紧接着写
 * `scrollTop` 又触发一次。这一句在 jsdom 里是**完全免费**的（它不做布局，
 * 读 scrollHeight 返回常量），在真实浏览器里却随内容长度增长 —— 这正是
 * 「长会话越来越卡」最经典的产地，也是 JS 侧优化做完之后剩下的那个瓶颈。
 *
 * `layout-probe.mjs` 也起真实 Chromium，但它量的是**几何**（高度/宽度），
 * 用来抓「DOM 全对、屏幕上不对」；它不量耗时。
 *
 * ## 与 layout-probe 的关键差别：不能快进时间
 *
 * layout-probe 用 `--virtual-time-budget=2000` 把计时器快进，于是 `--dump-dom`
 * 能在同一趟里拿到结果。这个探针**绝对不能**那么做 —— 虚拟时间下
 * `performance.now()` 与 rAF 都是假的，量出来的「帧间隔」毫无意义。
 *
 * 所以回报改成异步的：起一个本地 HTTP server，把探针页从这里 serve 出去，
 * 页面测完用 `fetch` 把结果 POST 回来。全程真实时间。
 *
 * ## 量什么
 *
 * - **帧间隔**（rAF）：p50 / p90 / 最大。这是端到端的主指标 ——
 *   不管时间花在 JS 还是布局上，掉了帧它都会说出来
 * - **空转基线**：什么都不推时同一环境下的帧间隔，是上面那些数字的对照物。
 *   没有基线，「p90=25ms」说明不了任何事
 * - **每帧布局 + 滚动**：单独量 `scrollTop = scrollHeight` 这一步 ——
 *   它就是强制同步布局，与帧间隔对照就能看出瓶颈在 JS 还是在布局
 * - **长任务**（>50ms）：主线程被独占的次数，一次都不该有
 *
 * 用法：
 *   npm run probe:perf        （会先 build —— 量的是产物，不是源码）
 *
 * 退出码：0 = 全部通过；1 = 有场景超预算；2 = 找不到浏览器（**不算通过**）。
 */
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const distPath = join(webDir, 'dist', 'index.html')

/** 常见安装位置。可以用环境变量覆盖（与 layout-probe 同一张表）。 */
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

/** 整个探针的墙钟上限。跑不完就是探针坏了，不能挂着不走。 */
const TIMEOUT_MS = 180_000

/**
 * 窗口尺寸与 layout-probe 一致：插件面板的实际宽度就在 400 上下，
 * 换更宽的视口会把「卡片被挤压」这类真实布局问题量没。
 */
const WINDOW = '420,760'

/**
 * 预算。
 *
 * 这些数字**第一版先放着**，跑出真实数据之后再收紧 —— 探针首先得能说出
 * 数字，阈值是第二步。现在它们的唯一作用是拦住「整块退化」。
 */
const BUDGET = {
  /** 帧间隔 p90 超过它就算掉帧。33ms 约合 30fps，是观感的底线。 */
  frameP90Ms: 33,
  /** 一次都不该有的东西。 */
  longTasks: 0,
}

// ---------------------------------------------------------------------------
// 场景
// ---------------------------------------------------------------------------

/**
 * 场景表。
 *
 * `idle` 那条是**对照物**，不是被测对象：没有它，别的场景量出的帧间隔
 * 说明不了任何事 —— headless Chromium 自己的 rAF 节奏未必是 16.7ms。
 */
const SCENES = [
  { key: 'idle', name: '空转基线（什么都不推）', kind: 'idle' },
  { key: 'empty', name: '空会话流式（只有正文在流）', kind: 'session', items: 0 },
  { key: 'session', name: '长会话流式（160 项，卡片全程展开）', kind: 'session', items: 40 },
  { key: 'prose', name: '长正文流式（1.5 万字继续流）', kind: 'prose', chars: 14_520 },
  { key: 'thinking', name: '长思考流式（1.3 万字思考块继续流）', kind: 'thinking', chars: 13_143 },
  { key: 'replay', name: '回放规模流式（800 项）', kind: 'session', items: 200 },
]

/** 每次推的增量。用中文，一个 UTF-16 单元 —— 与真实流式的一个 token 相当。 */
const CHUNK = '一'

const FRAMES = 60
const FRAME_GAP_MS = 16

/**
 * 探针页里跑的驱动脚本。
 *
 * 注入在 `</body>` 之前，而 dist 里的应用是个 `type="module"` 的内联 bundle
 * —— module 是 deferred 的，所以这段先执行。顺序正好：先把桥备好，
 * 应用的 useEffect 再往上加 `pushBatch`。
 *
 * 内部的 JS 刻意不用模板字符串，与外面这层反引号隔开。
 */
const DRIVER = `
(function () {
  'use strict';
  var CONFIG = __CONFIG__;

  // 应用里是 window.ccoder = window.ccoder || {} —— 先备好这个对象，
  // 它就只往上加 pushBatch，不会覆盖。send 必须给：否则应用会一直轮询
  // 等「Kotlin 侧就绪」（那是它在生产里等 CEF 桥的行为）。
  window.ccoder = { send: function () {} };

  var T = 1726050000000;

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function push(ops) { window.ccoder.pushBatch(JSON.stringify(ops)); }

  // ---- 种子内容：一次真实会话的样子 ----
  // 与 streamingCost.test.tsx 的 scene() 同一套形状 —— 两处必须能对上，
  // 否则 jsdom 的数字与这里的数字没法互相对照

  function wrapFile(i) {
    var lines = [];
    for (var k = 0; k < 60; k++) lines.push('第 ' + k + ' 行内容，写点像样的东西占位');
    return lines.join('\\n');
  }

  function wrapOutput(i) {
    var lines = [];
    for (var k = 0; k < 40; k++) lines.push('ok ' + k + ' - 输出行');
    return lines.join('\\n');
  }

  var PROSE = '一段已经流出来的正文，带**粗体**、\`行内代码\`、[链接](https://example.com/a)、\\n' +
    '- 列表项一\\n- 列表项二\\n\\n以及代码：\\n\\n\`\`\`kotlin\\nfun main() { println("hi") }\\n\`\`\`\\n\\n';

  /** 思考是连续的中文散文，没有 Markdown 结构 —— 排版行为与正文完全不同。 */
  var THOUGHT = '让我想想这个问题的关键在哪里。首先需要区分两种可能：一种是数据到达本身就是一段一段的，' +
    '另一种是数据到达很细但渲染跟不上。从探针的数据看，到达这一侧是干净的，那么问题就只能出在屏幕这一侧。';

  function seedFor(scene) {
    if (scene.kind === 'idle') return [];
    if (scene.kind === 'prose') return [];

    var ops = [];
    for (var i = 0; i < scene.items; i++) {
      ops.push({ op: 'append', item: {
        kind: 'toolUse', id: 'u' + i, ts: T, toolUseId: 'toolu_' + i,
        name: 'Write',
        input: JSON.stringify({ file_path: '/proj/src/File' + i + '.kt', content: wrapFile(i) })
      }});
      ops.push({ op: 'append', item: {
        kind: 'toolResult', id: 'r' + i, ts: T, toolUseId: 'toolu_' + i,
        text: wrapOutput(i), isError: false
      }});
      ops.push({ op: 'append', item: {
        kind: 'assistant', id: 'a' + i, ts: T,
        text: '## 第 ' + i + ' 步\\n\\n已完成编辑：\\n\\n- 改了 ' + i + ' 处\\n- 测试通过\\n'
      }});
      ops.push({ op: 'append', item: {
        kind: 'thinking', id: 'k' + i, ts: T, text: '思考内容 '.repeat(30)
      }});
    }
    return ops;
  }

  /** 长正文场景：整段灌进 live —— 那才是「正在流」的正文。 */
  function seedLive(scene) {
    if (scene.kind === 'thinking') {
      // 思考走的是 LiveThinkingBlock —— 纯文本 div，**没有 Markdown 的块级
      // 缓存**，而且 styles.css 里只有 .entry / .tool 设了 contain，
      // .thinking-text 什么都没设。它是 .transcript 的直接子项，每帧长高都会
      // 卷进整页重排。这个场景就是来量那一份代价的
      var times = Math.ceil(scene.chars / THOUGHT.length);
      var t = '';
      for (var i = 0; i < times; i++) t += THOUGHT;
      push([{ op: 'appendDelta', target: 'thinking', text: t }]);
      return;
    }
    if (scene.kind !== 'prose') return;
    var para = PROSE;
    var times2 = Math.ceil(scene.chars / para.length);
    var text = '';
    for (var i = 0; i < times2; i++) text += para;
    push([{ op: 'appendDelta', target: 'assistant', text: text }]);
  }

  // ---- 统计 ----
  function stat(nums) {
    if (!nums.length) return null;
    var s = nums.slice().sort(function (a, b) { return a - b; });
    var at = function (q) { return s[Math.min(s.length - 1, Math.floor(q * s.length))]; };
    var mean = s.reduce(function (a, b) { return a + b; }, 0) / s.length;
    return {
      n: s.length,
      mean: +mean.toFixed(2),
      p50: +at(0.5).toFixed(2),
      p90: +at(0.9).toFixed(2),
      max: +s[s.length - 1].toFixed(2),
    };
  }

  async function measure(scene) {
    push([{ op: 'reset' }]);
    var seed = seedFor(scene);
    // 种子按生产的单批上限切开发 —— 一次推 800 项会造出一个**探针自己的**
    // 长任务，那不是产品的行为（生产走 TranscriptPump 的 maxBatch=200）。
    // 探针报的每一条都必须是产品真有的，否则它就是狼来了
    for (var s = 0; s < seed.length; s += CONFIG.maxBatch) {
      push(seed.slice(s, s + CONFIG.maxBatch));
      await sleep(CONFIG.gap);
    }
    seedLive(scene);

    // 让种子内容渲染完、布局稳定下来再开始记帧
    await sleep(150);

    var scroller = document.querySelector('.transcript');
    if (!scroller) return { key: scene.key, error: '没找到 .transcript（应用没挂载？）' };

    // 长任务：主线程被独占超过 50ms 的次数
    var longTasks = [];
    var po = null;
    try {
      po = new PerformanceObserver(function (list) {
        var es = list.getEntries();
        for (var i = 0; i < es.length; i++) longTasks.push(+es[i].duration.toFixed(1));
      });
      po.observe({ entryTypes: ['longtask'] });
    } catch (e) { /* 老浏览器没有 longtask，不影响主指标 */ }

    // 帧间隔
    var gaps = [];
    var last = performance.now();
    var stopped = false;
    function tick(t) {
      if (stopped) return;
      gaps.push(t - last);
      last = t;
      requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);

    await sleep(200);           // 先攒一段空转的帧间隔（等下会被丢掉，见下）
    gaps.length = 0;            // 只保留「开始推送之后」的帧
    last = performance.now();

    // ---- 逐帧推流 ----
    // 思考与正文是两个独立的 live 缓冲，推错 target 就量错了组件
    var target = scene.kind === 'thinking' ? 'thinking' : 'assistant';
    var scrollMs = [];
    for (var f = 0; f < CONFIG.frames; f++) {
      if (scene.kind !== 'idle') {
        push([{ op: 'appendDelta', target: target, text: CONFIG.chunk }]);
      }
      await sleep(CONFIG.gap);
      if (scene.kind !== 'idle') {
        // 生产代码里每帧都做这一句（Transcript.tsx 的粘底）。读 scrollHeight
        // 会强制浏览器同步布局，紧接着写 scrollTop 再触发一次 ——
        // 这里单独把它量出来，就是「布局 vs JS」的分离器
        var t0 = performance.now();
        scroller.scrollTop = scroller.scrollHeight;
        scrollMs.push(performance.now() - t0);
      }
    }

    stopped = true;
    if (po) po.disconnect();
    await sleep(60);

    var items = 0;
    try {
      var root = document.querySelector('.transcript');
      items = root ? root.childElementCount : 0;
    } catch (e) { /* 只用于显示 */ }

    return {
      key: scene.key,
      name: scene.name,
      frame: stat(gaps),
      scroll: stat(scrollMs),
      longTasks: longTasks,
      nodes: items,
    };
  }

  async function run() {
    var out = [];
    for (var i = 0; i < CONFIG.scenes.length; i++) {
      try {
        out.push(await measure(CONFIG.scenes[i]));
      } catch (e) {
        out.push({ key: CONFIG.scenes[i].key, error: String(e && e.message || e) });
      }
    }
    try {
      await fetch('/result', { method: 'POST', body: JSON.stringify({ scenes: out }) });
    } catch (e) { /* server 已经走了，没什么可做的 */ }
  }

  // 等应用挂上桥再开跑。顺序不确定：应用是 deferred module，
  // 而 onLoadEnd 那条路在生产里还要跨一次 CEF 进程边界
  var waited = 0;
  (function whenBridge() {
    if (window.ccoder && typeof window.ccoder.pushBatch === 'function') return run();
    waited += 10;
    if (waited > 8000) {
      fetch('/result', { method: 'POST', body: JSON.stringify({ fatal: '8 秒内应用没挂上桥' }) });
      return;
    }
    setTimeout(whenBridge, 10);
  })();
})();
`

// ---------------------------------------------------------------------------
// 页面 / server / 浏览器
// ---------------------------------------------------------------------------

function buildPage() {
  const html = readFileSync(distPath, 'utf8')
  const config = JSON.stringify({
    frames: FRAMES,
    gap: FRAME_GAP_MS,
    chunk: CHUNK,
    // 与 TranscriptPump.DEFAULT_MAX_BATCH 对齐：种子要按产品的真实节奏灌进去
    maxBatch: 200,
    scenes: SCENES,
  })
  const driver = DRIVER.replace('__CONFIG__', config)

  if (!html.includes('</body>')) {
    throw new Error('dist/index.html 里没有 </body> —— 注入点没了，产物结构变了吗？')
  }
  return html.replace('</body>', `<script>${driver}</script></body>`)
}

/**
 * 起 server → 开来浏览器 → 等页面把结果 POST 回来。
 *
 * 页面的回报是**唯一**的出口：性能测量必须跑在真实时间里，
 * 所以没有 `--dump-dom` 那种同步取结果的余地。
 */
function runProbe(chromium, page) {
  return new Promise((resolvePromise, rejectPromise) => {
    let settled = false
    const finish = (fn, arg) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      try { child?.kill() } catch { /* 已经退了 */ }
      server.close()
      fn(arg)
    }

    const server = createServer((req, res) => {
      if (req.method === 'POST' && req.url === '/result') {
        let body = ''
        req.on('data', (c) => { body += c })
        req.on('end', () => {
          res.writeHead(204)
          res.end()
          try {
            finish(resolvePromise, JSON.parse(body))
          } catch (e) {
            finish(rejectPromise, new Error(`结果不是 JSON：${String(body).slice(0, 200)}`))
          }
        })
        return
      }
      if (req.url === '/' || req.url.startsWith('/index')) {
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
        res.end(page)
        return
      }
      res.writeHead(404)
      res.end()
    })

    let child = null
    const timer = setTimeout(
      () => finish(rejectPromise, new Error(`${TIMEOUT_MS / 1000} 秒内页面没有回报结果`)),
      TIMEOUT_MS,
    )

    server.listen(0, '127.0.0.1', () => {
      const url = `http://127.0.0.1:${server.address().port}/`
      child = spawn(
        chromium,
        [
          '--headless=new',
          '--disable-gpu',
          '--no-sandbox',
          // 这三条缺一不可：headless 默认会把「不可见」页面的计时器降频，
          // 而本探针全靠 setTimeout 与 rAF —— 被节流之后量到的是节流后的
          // 节奏，不是真实节奏
          '--disable-background-timer-throttling',
          '--disable-renderer-backgrounding',
          '--disable-backgrounding-occluded-windows',
          `--window-size=${WINDOW}`,
          url,
        ],
        { stdio: 'ignore' },
      )
      child.on('error', (e) => finish(rejectPromise, new Error(`启动浏览器失败：${e.message}`)))
      child.on('exit', (code) => {
        // 页面若已回报，这次退出是 finish 里 kill 的
        if (!settled) {
          finish(rejectPromise, new Error(`浏览器提前退出（code=${code}），且没有回报结果`))
        }
      })
    })

    server.on('error', (e) => finish(rejectPromise, e))
  })
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------

const chromium = findChromium()
if (!chromium) {
  console.error('找不到 Chromium（Edge / Chrome）。这个探针**必须**用真实浏览器：')
  console.error('jsdom 不做布局，量不出「每帧强制同步布局」这类代价。')
  console.error('请设环境变量指定路径：')
  console.error('  CCoder_CHROMIUM="C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"')
  process.exit(2)
}

if (!existsSync(distPath)) {
  console.error(`没有产物：${distPath}`)
  console.error('先跑 npm run build —— 这个探针量的是**产物**，不是源码。')
  process.exit(2)
}

console.log('性能探针 · 浏览器:', chromium)
console.log('产物:', distPath, `(${statSync(distPath).size} 字节)`)
console.log('窗口:', WINDOW, '· 每场景', FRAMES, `帧，间隔 ${FRAME_GAP_MS}ms（约 60fps）`)
console.log()

let payload
try {
  payload = await runProbe(chromium, buildPage())
} catch (e) {
  console.error('探针失败：', e.message)
  process.exit(1)
}

if (payload.fatal) {
  console.error('探针失败：', payload.fatal)
  process.exit(1)
}

const fmt = (s) => (s ? `p50=${s.p50} p90=${s.p90} 最大=${s.max} 均=${s.mean}` : '（无样本）')
const baseline = payload.scenes.find((s) => s.key === 'idle')?.frame

let failed = 0
for (const s of payload.scenes) {
  if (s.error) {
    failed++
    console.log(`[失败] ${s.name ?? s.key}`)
    console.log(`         ✗ ${s.error}`)
    continue
  }

  const problems = []
  if (s.frame && s.frame.p90 > BUDGET.frameP90Ms) {
    problems.push(`帧间隔 p90 ${s.frame.p90}ms > ${BUDGET.frameP90Ms}ms（掉帧）`)
  }
  if (s.longTasks.length > BUDGET.longTasks) {
    problems.push(`长任务 ${s.longTasks.length} 个（最长 ${Math.max(...s.longTasks)}ms）`)
  }

  if (problems.length) failed++
  console.log(`[${problems.length ? '失败' : '通过'}] ${s.name}`)
  console.log(`         帧间隔 ${fmt(s.frame)}   长任务 ${s.longTasks.length} 个`)
  console.log(`         每帧布局+滚动 ${fmt(s.scroll)}   顶层子元素 ${s.nodes}`)
  for (const p of problems) console.log('         ✗ ' + p)
}

console.log()
if (baseline) {
  console.log(`空转基线 p50=${baseline.p50} p90=${baseline.p90}ms —— 各场景的帧间隔要与它比，`)
  console.log('而不是与 16.7ms 比：headless 下的合成节奏未必是 60fps。')
  console.log()
}
console.log('读法：帧间隔 ≈ 基线 且 布局+滚动很小 → 这一档没问题；')
console.log('      帧间隔被拉长、布局+滚动却很小 → 瓶颈在 JS；')
console.log('      布局+滚动自己就很大 → 瓶颈在「每帧强制同步布局」，那要动滚动策略。')

process.exit(failed ? 1 : 0)
