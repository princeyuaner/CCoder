#!/usr/bin/env node
/**
 * 滚动跟随探针 —— 在**真实 Chromium** 里复现「流式期间上滚被拽回底部」。
 *
 * ## 为什么必须有它
 *
 * 设计文档 §4.5 的契约是"暂停 = 用户上滚后新内容不再移动视口"。2026-09-21
 * 用户报："思考输出的时候，转写区往上滚动的时候，会自动弹回来。"
 *
 * 要害是**事件派发的先后**，而 jsdom 里根本没有这件事：
 *
 *   一拍推送的任务里：React 提交 → 跟随写入 scrollTop = scrollHeight（瞬时到底）
 *   同一帧稍后：浏览器才派发用户上滚那条 scroll 事件 → 处理器读到的位置已是底部
 *
 * 于是"暂停"登记不上 —— 用户看到的就是滚上去一点、又被弹回来。
 *
 * **这不是"主线程忙不过来"**：真实浏览器里量过，帧间隔 p50/p90 = 16.7ms、
 * 长任务 0 个、`scrollTop = scrollHeight` 每帧 0.1ms
 * （`docs/superpowers/specs/2026-09-14-streaming-perf-design.md` §6 —— 那份文档
 * 同时纠正了"48.7ms/帧"是 jsdom 数字）。所以本探针不去复现"卡"，
 * 只钉"谁先谁后"这一条**顺序**事实：写入总在同一帧的 scroll steps 之前落地。
 *
 * `Transcript.test.tsx` 测不了这个：jsdom 不做布局，程序化赋值 scrollTop
 * **不派发 scroll 事件**（那边一律用 fireEvent.scroll 显式模拟），更没有
 * "谁先谁后"。与 layout-probe / perf-probe 同一套路：本地 server + --headless=new
 * + POST 回报结果，全程真实时间。
 *
 * ## 不引入 CDP，以及它测不到的那一半
 *
 * `dispatchEvent(new WheelEvent(...))` 是 **untrusted** 事件：它只能验应用的状态机，
 * **不代表真实滚轮**，也复现不出"合成器已经把视口滚上去、主线程还没 commit"的那一半。
 * 那一半在本探针里由**直接改 scrollTop** 代替（视觉上等价：位置真的动了），
 * 真正无法自动化的部分（JCEF 的输入链路，OSR 与否还要实测）留在 §8.4 的手工清单里。
 *
 * ## 量什么
 *
 * 四个场景，每个都按生产节拍（16ms 一拍）推思考增量：
 *
 * | 场景 | 用户动作 | 判据 |
 * |---|---|---|
 * | 对照物 | 不上滚 | 全程贴底，且应用**确实在写**（跟随没被修坏） |
 * | 上滚 | 两格滚轮（第一格落在"推送任务进行中"） | 终态距底 ≥200px，且此后应用**一次都不写** |
 * | 滚回底部 | 先上滚，再滚回底部 | 终态距底 ≤32px（跟随恢复），写入恢复 |
 * | 平滑动画 | 先上滚，点「回到底部」，立刻再上滚 | 终态距底 ≥200px（动画被接管） |
 *
 * 判据一律用**距底距离**（scrollHeight - clientHeight - scrollTop），
 * 不用绝对 scrollTop —— 内容一直在长，绝对值没有意义。
 * "应用是否还在写"由包在 `scrollTop` setter 上的计数器给出：它是"暂停已登记"
 * 最硬的证据（比只看位置更直接 —— 位置可能因为内容长高而碰巧变大）。
 *
 * 顺带报出帧间隔与长任务，把"这台机器当时忙不忙"留成数字。
 *
 * 用法：
 *   npm run probe:scroll      （会先 build —— 量的是产物，不是源码）
 *
 * 退出码：0 = 全部通过；1 = 有断言失败；2 = 找不到浏览器/没有产物（**不算通过**）。
 */
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(here, '..')
const distPath = join(webDir, 'dist', 'index.html')

/** 常见安装位置。可以用环境变量覆盖（与 layout-probe / perf-probe 同一张表）。 */
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
 * 窗口尺寸与前两个探针一致：插件面板的真实宽度就在 400 上下。
 * 宽度换掉会让"转写区多高"这件事跟着变，而跟随判据全在高度方向上。
 */
const WINDOW = '420,760'

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

  // ---- 种子：一次真实会话的样子（与 perf-probe 同一套形状）----

  function wrapFile(i) {
    var lines = [];
    for (var k = 0; k < 25; k++) lines.push('第 ' + k + ' 行内容，写点像样的东西占位');
    return lines.join('\\n');
  }

  function wrapOutput(i) {
    var lines = [];
    for (var k = 0; k < 20; k++) lines.push('ok ' + k + ' - 输出行');
    return lines.join('\\n');
  }

  /** 思考是连续的中文散文，没有 Markdown 结构 —— 排版行为与正文完全不同。 */
  var THOUGHT = '让我想想这个问题的关键在哪里。首先需要区分两种可能：一种是数据到达本身就是一段一段的，' +
    '另一种是数据到达很细但渲染跟不上。从探针的数据看，到达这一侧是干净的，那么问题就只能出在屏幕这一侧。';

  /**
   * 种子。id 里带场景序号：每次重挂都换一批 id，于是"最后一条用户消息换了 id"
   * 那条规则（发送 / 回放 → 回到底部）在场景之间照样成立 —— 上一个场景结束时
   * 停在暂停态，下一个场景必须从"跟随"重新开始，否则对照物是假的。
   */
  function seedFor(n) {
    var ops = [];
    ops.push({ op: 'append', item: { kind: 'user', id: 'u' + n, ts: T, text: '先按这个方案改一版看看' } });
    for (var i = 0; i < CONFIG.seedItems; i++) {
      ops.push({ op: 'append', item: {
        kind: 'toolUse', id: 'tu' + n + '_' + i, ts: T, toolUseId: 'toolu_' + n + '_' + i,
        name: 'Write',
        input: JSON.stringify({ file_path: '/proj/src/File' + i + '.kt', content: wrapFile(i) })
      }});
      ops.push({ op: 'append', item: {
        kind: 'toolResult', id: 'tr' + n + '_' + i, ts: T, toolUseId: 'toolu_' + n + '_' + i,
        text: wrapOutput(i), isError: false
      }});
      ops.push({ op: 'append', item: {
        kind: 'assistant', id: 'a' + n + '_' + i, ts: T, text: '## 第 ' + i + ' 步\\n\\n已完成编辑。'
      }});
    }
    return ops;
  }

  /**
   * 把"进行中的思考"一开始就撑到真实体量（1.3 万字）。
   *
   * **这一步是复现的前提**：思考块每帧整块重排，代价随文本长度涨；从空串一个字
   * 一个字地流，头几十拍根本占不满主线程，那条时序也就撞不出来
   * （perf-probe 的 thinking 场景同理）。
   */
  function seedLive() {
    var times = Math.ceil(CONFIG.thoughtChars / THOUGHT.length);
    var t = '';
    for (var i = 0; i < times; i++) t += THOUGHT;
    push([{ op: 'appendDelta', target: 'thinking', text: t }]);
  }

  /** 距底距离 —— 内容一直在长，只有它有意义。 */
  function dist(el) { return el.scrollHeight - el.clientHeight - el.scrollTop; }

  /** 探针自己那两笔（模拟用户滚动、模拟应用那一笔写入）不算"应用在写"。 */
  var selfWriting = false;

  /**
   * 给 scrollTop 的 setter 装计数器：应用每写一次 +1。
   *
   * 这是"暂停登记之后应用必须停止写入"最直接的证据 —— 比只看距底距离硬，
   * 因为距离会因为内容长高而变大（那与跟随开关无关）。
   */
  function instrumentWrites(scroller) {
    var desc = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop');
    var count = 0;
    Object.defineProperty(scroller, 'scrollTop', {
      configurable: true,
      get: function () { return desc.get.call(scroller); },
      set: function (v) {
        if (!selfWriting) count += 1;
        desc.set.call(scroller, v);
      },
    });
    return { count: function () { return count; } };
  }

  /** 转写区现在是不是处在"跟随"态 —— 「回到底部」按钮只在暂停时渲染（见 Transcript.tsx），
   *  它是应用自己给出的、最精确的可观察量（jsdom 那边也用 data-new 当证据）。 */
  function following() {
    return !document.querySelector('[data-testid="jump-to-bottom"]');
  }

  /**
   * 用户上滚一格。三件事，对应真实浏览器里的一整个来回：
   *
   *  - 位置：合成器把视口滚上去（这里直接改 scrollTop；真实浏览器里这还会
   *    派发一条 scroll 事件）；
   *  - **应用这一拍的写入**：跟随态下，应用每拍都会写一次 scrollTop = scrollHeight。
   *    真实浏览器里谁先落地是**调度决定的**（首轮实测同一份代码能跑出红也能跑出绿），
   *    而用户报的那个现象只能是"写入先落地"。探针固定取这一种 —— 判据要的是
   *    "最坏顺序下契约仍然成立"，赌运气的用例不算用例。
   *
   *    要不要写这一笔，判据是**应用现在是否在跟随**（按钮在不在）：跟随中它这一拍
   *    必然会写，暂停了才不会写。不这么判，第二格会被探针自己吃掉 —— 那就不是
   *    在测应用了。
   *  - 意图：滚轮事件本身。它只是个方向信号，所以不需要可信事件。
   *
   * 返回值是"一拍之后再派发意图"的函数 —— 那也是真实顺序：滚轮事件要等当前任务
   * 结束才被派发，而当前任务里已经有一次写入落地了。
   */
  function wheelUp(scroller) {
    selfWriting = true;
    scroller.scrollTop = scroller.scrollTop - CONFIG.scrollBy;
    if (following()) scroller.scrollTop = scroller.scrollHeight;
    selfWriting = false;
    return function (el) {
      el.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, bubbles: true }));
    };
  }

  async function mount(n) {
    push([{ op: 'reset' }]);
    var seed = seedFor(n);
    // 按生产的单批上限切开推 —— 一次推几百项会造出一个**探针自己的**长任务
    for (var s = 0; s < seed.length; s += CONFIG.maxBatch) {
      push(seed.slice(s, s + CONFIG.maxBatch));
      await sleep(CONFIG.seedGap);
    }
    seedLive();
    await sleep(CONFIG.settleMs);   // 让种子渲染完、布局稳定
    return document.querySelector('.transcript');
  }

  /**
   * 推 CONFIG.frames 拍思考增量，每拍记两样：距底距离、应用这一拍写了几次 scrollTop。
   *
   * at 是帧号 → 用户动作。动作在**那一拍的推送之前**同步执行、并返回一个
   * "睡完这一拍再执行"的函数（见 wheelUp 的说明），于是同一帧里既有
   * "推送任务的写入"又有"用户已滚上去的位置"。
   */
  async function stream(scroller, at, counter) {
    var dists = [];
    var appWrites = [];
    var last = 0;
    for (var f = 0; f < CONFIG.frames; f++) {
      var after = at[f] ? at[f](scroller) : null;
      push([{ op: 'appendDelta', target: 'thinking', text: CONFIG.chunk }]);
      await sleep(CONFIG.gap);
      if (typeof after === 'function') after(scroller);
      dists.push(dist(scroller));
      var now = counter.count();
      appWrites.push(now - last);
      last = now;
    }
    return { dists: dists, appWrites: appWrites };
  }

  /** 从第 from 拍起，应用一共写了几次。 */
  function writesFrom(appWrites, from) {
    var n = 0;
    for (var i = from; i < appWrites.length; i++) n += appWrites[i];
    return n;
  }

  // ---- 统计与观测 ----

  function gapsAndLongTasks() {
    var state = { gaps: [], longTasks: [], stopped: false, last: performance.now() };
    try {
      var po = new PerformanceObserver(function (list) {
        var es = list.getEntries();
        for (var i = 0; i < es.length; i++) state.longTasks.push(+es[i].duration.toFixed(1));
      });
      po.observe({ entryTypes: ['longtask'] });
      state.po = po;
    } catch (e) { /* 老浏览器没有 longtask，不影响主判据 */ }
    (function tick(t) {
      if (state.stopped) return;
      state.gaps.push(t - state.last);
      state.last = t;
      requestAnimationFrame(tick);
    })(performance.now());
    return state;
  }

  function stat(nums) {
    if (!nums.length) return null;
    var s = nums.slice().sort(function (a, b) { return a - b; });
    var at = function (q) { return s[Math.min(s.length - 1, Math.floor(q * s.length))]; };
    return { n: s.length, p50: +at(0.5).toFixed(1), p90: +at(0.9).toFixed(1), max: +s[s.length - 1].toFixed(1) };
  }

  // ---- 场景 ----

  /** 上滚两格：第一格落在"推送任务进行中"，第二格落在那之后。 */
  function twoNotches() {
    var at = {};
    at[CONFIG.notchFrame] = wheelUp;
    at[CONFIG.notchFrame + 2] = wheelUp;
    return at;
  }

  var SCENES = [
    {
      key: 'follow',
      name: '对照物：不上滚，跟随全程贴底',
      run: async function (scroller, counter) {
        var r = await stream(scroller, {}, counter);
        return { maxDist: Math.max.apply(null, r.dists), writes: writesFrom(r.appWrites, 0) };
      },
      judge: function (r) {
        return {
          // 两个方向都要：位置贴底**且**应用确实在写 —— 否则"什么都不做"也能贴底
          ok: r.maxDist <= CONFIG.thresholdPx && r.writes > 0,
          detail: '最大距底 ' + r.maxDist + 'px · 应用写入 ' + r.writes + ' 次（要求 >0）',
        };
      },
    },
    {
      key: 'pause',
      name: '流式期间上滚：视口必须留在上面，且应用停止写入',
      run: async function (scroller, counter) {
        var r = await stream(scroller, twoNotches(), counter);
        var d = r.dists;
        return {
          afterFirst: d[CONFIG.notchFrame],
          afterSecond: d[CONFIG.notchFrame + 2],
          finalDist: d[d.length - 1],
          // 第一格那一拍之后（意图事件已被派发），应用一次都不该再写
          writesAfterFirst: writesFrom(r.appWrites, CONFIG.notchFrame + 1),
        };
      },
      judge: function (r) {
        return {
          ok: r.finalDist >= CONFIG.stayUpPx && r.writesAfterFirst === 0,
          detail: '第一格后距底 ' + r.afterFirst + 'px · 第二格后 ' + r.afterSecond +
            'px · 终态 ' + r.finalDist + 'px（要求 ≥' + CONFIG.stayUpPx +
            '） · 此后应用写入 ' + r.writesAfterFirst + ' 次（要求 0）',
        };
      },
    },
    {
      key: 'resume',
      name: '暂停后滚回底部：跟随必须恢复',
      run: async function (scroller, counter) {
        var at = twoNotches();
        at[CONFIG.resumeFrame] = function (el) {
          selfWriting = true;
          el.scrollTop = el.scrollHeight;        // 用户滚回底部
          selfWriting = false;
          return function (e2) {
            e2.dispatchEvent(new WheelEvent('wheel', { deltaY: 100, bubbles: true }));
          };
        };
        var r = await stream(scroller, at, counter);
        return {
          beforeResume: r.dists[CONFIG.resumeFrame],
          tail: r.dists.slice(CONFIG.resumeFrame + 2),
          writesAfter: writesFrom(r.appWrites, CONFIG.resumeFrame + 1),
        };
      },
      judge: function (r) {
        var maxTail = Math.max.apply(null, r.tail);
        return {
          ok: maxTail <= CONFIG.thresholdPx && r.writesAfter > 0,
          detail: '恢复前距底 ' + r.beforeResume + 'px · 恢复后最大距底 ' + maxTail +
            'px · 恢复后写入 ' + r.writesAfter + ' 次（要求 >0）',
        };
      },
    },
    {
      key: 'smooth',
      name: '点「回到底部」后立刻上滚：动画被接管',
      run: async function (scroller, counter) {
        var at = twoNotches();
        at[CONFIG.smoothFrame] = function (el) {
          var btn = document.querySelector('[data-testid="jump-to-bottom"]');
          if (btn) btn.click();                  // 真触发一次平滑动画
          return wheelUp(el);                    // 紧接着用户又上滚（同一个任务里）
        };
        // 与 pause 场景同形：第一格可能被"同一帧里那一拍推送"吃掉（点完按钮跟随是开的），
        // 第二格必须留住 —— 契约要的是"最坏顺序下仍然停得下来"，不是"一格都不许丢"
        at[CONFIG.smoothFrame + 2] = wheelUp;
        var r = await stream(scroller, at, counter);
        return {
          finalDist: r.dists[r.dists.length - 1],
          writesAfter: writesFrom(r.appWrites, CONFIG.smoothFrame + 1),
        };
      },
      judge: function (r) {
        return {
          ok: r.finalDist >= CONFIG.stayUpPx && r.writesAfter === 0,
          detail: '终态距底 ' + r.finalDist + 'px（要求 ≥' + CONFIG.stayUpPx +
            '） · 此后应用写入 ' + r.writesAfter + ' 次（要求 0）',
        };
      },
    },
  ];

  async function run() {
    var out = [];
    for (var i = 0; i < SCENES.length; i++) {
      var scene = SCENES[i];
      var rec = { key: scene.key, name: scene.name };
      try {
        var scroller = await mount(i);
        if (!scroller) throw new Error('没找到 .transcript（应用没挂载？）');
        // 转写区必须真的能滚 —— 否则"距底 0px"既可能是跟随正确，也可能是**根本没得滚**，
        // 两条判据全都成了空转。这是探针自己的前提，先钉死。
        var contentH = scroller.scrollHeight;
        var viewH = scroller.clientHeight;
        if (contentH - viewH < CONFIG.stayUpPx) {
          throw new Error('种子没把转写区撑满（内容 ' + contentH + 'px / 视口 ' + viewH +
            'px）：本场景的判据不成立');
        }
        rec.heights = '内容 ' + contentH + 'px / 视口 ' + viewH + 'px';
        var obs = gapsAndLongTasks();
        var r = await scene.run(scroller, instrumentWrites(scroller));
        obs.stopped = true;
        if (obs.po) obs.po.disconnect();
        var verdict = scene.judge(r);
        rec.ok = verdict.ok;
        rec.detail = verdict.detail;
        rec.frame = stat(obs.gaps);
        rec.longTasks = obs.longTasks;
      } catch (e) {
        rec.ok = false;
        rec.detail = String((e && e.message) || e);
      }
      out.push(rec);
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

/** 探针自己的配置：节拍与生产对齐（TranscriptPump 是 16ms 一拍）。 */
const CONFIG = {
  seedItems: 5,
  seedGap: 40,
  settleMs: 200,
  frames: 40,
  gap: 16,
  chunk: '一',
  maxBatch: 200,
  /** 与 TranscriptPump.DEFAULT_MAX_BATCH 对齐：种子要按产品的真实节奏灌进去 */
  thoughtChars: 13_143,
  scrollBy: 300,
  /** 用户动作落在第几拍：先让流跑起来（主线程被占满），再上滚 */
  notchFrame: 12,
  resumeFrame: 24,
  smoothFrame: 24,
  /** 跟随是否贴底的判据（与 Transcript.tsx 的 STICK_THRESHOLD_PX 同值） */
  thresholdPx: 32,
  /** "留在上面"的判据：远大于阈值，免得贴着阈值打转 */
  stayUpPx: 200,
}

function buildPage() {
  const html = readFileSync(distPath, 'utf8')
  const driver = DRIVER.replace('__CONFIG__', JSON.stringify(CONFIG))
  if (!html.includes('</body>')) {
    throw new Error('dist/index.html 里没有 </body> —— 注入点没了，产物结构变了吗？')
  }
  return html.replace('</body>', `<script>${driver}</script></body>`)
}

/**
 * 起 server → 开来浏览器 → 等页面把结果 POST 回来。
 *
 * 页面的回报是**唯一**的出口：判据取决于真实时间下的时序，
 * 没有 `--dump-dom` 那种同步取结果的余地（同 perf-probe）。
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
  console.error('jsdom 不做布局、不派发 scroll 事件，测不出"谁先谁后"这条时序。')
  console.error('请设环境变量指定路径：')
  console.error('  CCoder_CHROMIUM="C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"')
  process.exit(2)
}

if (!existsSync(distPath)) {
  console.error(`没有产物：${distPath}`)
  console.error('先跑 npm run build —— 这个探针量的是**产物**，不是源码。')
  process.exit(2)
}

console.log('滚动跟随探针 · 浏览器:', chromium)
console.log('产物:', distPath, `(${statSync(distPath).size} 字节)`)
console.log('窗口:', WINDOW, `· 每场景 ${CONFIG.frames} 拍，间隔 ${CONFIG.gap}ms（约 60fps）`)
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

const fmtStat = (s) => (s ? `p50=${s.p50} p90=${s.p90} 最大=${s.max}` : '（无样本）')

let failed = 0
for (const s of payload.scenes) {
  const mark = s.ok ? '[通过]' : '[失败]'
  if (!s.ok) failed++
  console.log(`${mark} ${s.name}`)
  console.log(`        ${s.detail}`)
  if (s.heights) console.log(`        ${s.heights}`)
  const lt = s.longTasks?.length ?? 0
  const worst = lt ? `（最长 ${Math.max(...s.longTasks)}ms）` : ''
  console.log(`        帧间隔 ${fmtStat(s.frame)} · 长任务 ${lt} 次${worst}`)
}

console.log()
if (failed) {
  console.log(`✗ ${failed} 个场景没通过 —— 滚动跟随的行为契约（设计文档 §4.5）破了。`)
  process.exit(1)
}
console.log('✓ 四个场景全通过：跟随贴底、上滚不被拽回、滚回底部能恢复、平滑动画能被接管。')
process.exit(0)
