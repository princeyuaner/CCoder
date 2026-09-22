/**
 * 探针：**思考中**点停止（`interrupt()`）到底会发生什么。
 *
 * 起因（2026-09-21 用户报）：「思考中时点击停止按钮，思考中不会停止」。
 * 两种可能，修法完全不同，靠读代码分不出来：
 *   (a) 中断没被 CLI 认账 —— 思考增量继续来，"停止"是句空话；
 *   (b) 中断立刻生效，但**界面上那块「思考中」不会收尾** —— 它靠一条"整块思考"
 *       的消息才停（`codec.ts` 里 `live.thinking` 只被 `append(thinking)`/`reset`
 *       清掉），而被打断的一轮根本不产那条消息。
 *
 * 所以这里量三件事，带毫秒时间线：
 *   1. 点下 interrupt 之后**还有没有** thinking/assistant 增量；
 *   2. `result` 什么时候到、什么 subtype、`is_error` 是什么（SDK 说"一轮恰好一条
 *      result，把它当回合结束的信号"—— 真到不到得量）；
 *   3. 从 interrupt 到 result 隔了多久（界面在这段时间里应该显示什么）。
 *
 * 选项照抄 session.js（cwd / permissionMode / env 清洗 / includePartialMessages），
 * 测的是生产路径。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-interrupt-thinking.mjs ["自定义提问"] [interrupt前的毫秒数]
 *
 * ## 2026-09-21 实测：这台机器上暂时跑不出"真现场"
 *
 * - 走用户自己的配置（默认）：上游 **503**，CLI `api_retry` 十次、退避最长 37 秒，
 *   最后 `exited with code 143` —— 一轮都跑不起来，更按不下中断。
 * - 走本地假流（`MOCK_BASE_URL`，见 `mock-anthropic.mjs`）：管道通了，但 CLI 收到
 *   第一片思考就掐了连接。假流本身还没喂对（见那边头部记的几条）。
 *
 * 所以"中断到底多久生效"这条**仍未在真机上量到**。目前能确证的只有 CLI 自己的
 * 代码：interrupt 是一等公民的回合终止原因（`claude.exe` 里那张 stop-reason 表：
 * `case"interrupt":return"interrupt"`、`case"turn-abort":return"interrupt"`），
 * 外加 `interrupt_receipt_v1` / `interrupt_cancel_queued_v1` 两个能力位 ——
 * 也就是"被打断的一轮会结束、会吐 result"这件事是站得住的。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';

/** interrupt 前先让它思考多久（默认 2.5 秒）。 */
const HOLD_MS = Number(process.argv[3] ?? 2500);

const t0 = Date.now();
const at = () => `${String(Date.now() - t0).padStart(6)}ms`;
const log = (...a) => console.log(`[${at()}]`, ...a);

const events = [];
const waiters = [];

function waitFor(pred, label, ms) {
  const hit = events.find(pred);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    const w = { pred, resolve };
    waiters.push(w);
    setTimeout(() => {
      const i = waiters.indexOf(w);
      if (i >= 0) waiters.splice(i, 1);
      reject(new Error(`超时（${ms}ms）：${label}`));
    }, ms).unref?.();
  });
}

// ---- 时间线：每条与"思考"有关的消息都记一笔 ----
const lines = [];
let thinkingChars = 0;
let assistantChars = 0;
let deltaAfterInterrupt = 0;
let interruptedAt = null;

function note(text) {
  // 边跑边打：卡住时也要看得见卡在哪一步（第一版只在结尾统一打，结果超时后
  // 一个字都没有，白等两分钟）
  const line = `[${at()}] ${text}`;
  lines.push(line);
  console.log(line);
}

function deltaKind(msg) {
  if (msg.type !== 'stream_event') return null;
  const e = msg.event;
  if (e?.type !== 'content_block_delta') return null;
  const d = e.delta;
  if (d?.type === 'thinking_delta') return 'thinking';
  if (d?.type === 'text_delta') return 'text';
  return null;
}

function onEvent(msg) {
  events.push(msg);
  for (const w of waiters.slice()) {
    if (w.pred(msg)) {
      waiters.splice(waiters.indexOf(w), 1);
      w.resolve(msg);
    }
  }

  const kind = deltaKind(msg);
  if (kind === 'thinking' || kind === 'text') {
    const n = (msg.event.delta.thinking ?? msg.event.delta.text ?? '').length;
    if (kind === 'thinking') thinkingChars += n;
    else assistantChars += n;
    if (interruptedAt !== null) {
      deltaAfterInterrupt += n;
      note(`⚠ 中断之后又来了 ${kind} 增量 ${n} 字（累计 ${deltaAfterInterrupt}）`);
    }
    return;
  }

  switch (msg.type) {
    case 'assistant': {
      const kinds = (msg.message?.content ?? []).map((c) => c.type).join(',');
      note(`assistant 消息（块：${kinds}）`);
      break;
    }
    case 'result':
      note(
        `result subtype=${msg.subtype} is_error=${msg.is_error} ` +
          `耗时=${msg.duration_ms ?? '?'}ms 文本长度=${(msg.result ?? '').length}`,
      );
      break;
    case 'system':
      // init 只在开头有意义，其余系统消息不逐条啰嗦
      if (msg.subtype === 'init') note(`init（模型 ${msg.model ?? '?'}）`);
      break;
    default:
      break;
  }
}

function inputStream() {
  const queue = [];
  let notifyInput = null;
  return {
    [Symbol.asyncIterator]() {
      return this;
    },
    async next() {
      if (queue.length > 0) return { value: queue.shift(), done: false };
      const item = await new Promise((r) => {
        notifyInput = r;
      });
      notifyInput = null;
      if (item === null) return { value: undefined, done: true };
      return { value: item, done: false };
    },
  };
}

function send(text) {
  queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
  notifyInput?.('go');
}

const queue = [];
let notifyInput = null;

/**
 * 子进程环境。
 *
 * 默认就是宿主环境（走用户自己的配置）—— 但那条路要求上游可用，而**上游 503 时
 * 连一轮都跑不起来**（2026-09-21 实测就是这样）。所以多一条：`MOCK_BASE_URL` 给上
 * 时把端点指向本地假流（`mock-anthropic.mjs`）。
 *
 * 指向本地**必须同时带上 `CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1`**：那个开关是
 * CLI 官方说的"端点与凭证由宿主管"，它会因此去剥 `~/.claude/settings.json` 里
 * 那套 `env`（同 spec §6.1 的毒饵实验）。不带的话 settings 的端点赢、进程环境
 * 里给什么都不作数 —— 实测：不带时假端点一个请求都收不到，CLI 在那儿
 * `api_retry` 打真端点。
 *
 * 它还得经 overrides 传：这个变量在 `env.js` 的黑名单里（继承来的照剥），
 * 只有显式给的才放行 —— 那正是插件生产路径的写法。
 */
function childEnv() {
  const mock = process.env.MOCK_BASE_URL;
  if (!mock) return buildChildEnv(process.env);
  console.log(`（用本地假端点跑：${mock}）`);
  return buildChildEnv(process.env, {
    CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST: '1',
    ANTHROPIC_BASE_URL: mock,
    ANTHROPIC_AUTH_TOKEN: 'test-mock-key',
    ANTHROPIC_MODEL: 'mock-model',
  });
}

const prompt =
  process.argv[2] ??
  '不要用任何工具，也不要先回答。请只在思考块里非常仔细地推演这个题，推演至少三十步：' +
    '一个 8x8 棋盘去掉对角两个格子，能否用 31 张 1x2 骨牌完全覆盖？把每一种可能的论证都试一遍。';

const q = query({
  prompt: inputStream(),
  options: {
    cwd: process.cwd(),
    permissionMode: 'default',
    env: childEnv(),
    includePartialMessages: true,
  },
});

(async () => {
  (async () => {
    for await (const msg of q) onEvent(msg);
  })().catch(() => {});

  send(prompt);

  // 等第一个思考增量：这是"它真的在想"的唯一信号。
  // **等不到就如实说、不硬撑**：有些模型/配置根本不产 thinking 块，而"这一轮没有
  // 思考"与"中断不生效"是两回事，混起来会把结论带偏（同 probe-delta 最后那条提醒）
  const gotThinking = await waitFor((m) => deltaKind(m) === 'thinking', '第一个 thinking 增量', 60000)
    .then(() => true)
    .catch(() => false);
  if (!gotThinking) {
    console.log('\n===== 结论 =====');
    console.log(
      '这一轮**没有 thinking 增量**（60 秒内一片都没来）—— 换个会思考的模型/提问再跑，' +
        `本轮已经收到：正文增量 ${assistantChars} 字，事件 ${events.length} 条`,
    );
    console.log(`（init 里的模型：${events.find((m) => m.type === 'system' && m.subtype === 'init')?.model ?? '?'}）`);
    notifyInput?.(null);
    process.exit(2);
  }
  note(`第一个 thinking 增量到了（累计 ${thinkingChars} 字）`);

  await new Promise((r) => setTimeout(r, HOLD_MS));
  note(`→ 调 interrupt()（此刻思考 ${thinkingChars} 字，正文 ${assistantChars} 字）`);
  interruptedAt = Date.now();
  const receipt = await q.interrupt().catch((e) => ({ error: e.message }));
  note(`interrupt() 回执：${JSON.stringify(receipt ?? null)}`);

  // 等回合结束
  const result = await waitFor((m) => m.type === 'result', 'result', 120000).catch((e) => {
    note(`✗ ${e.message}`);
    return null;
  });
  const resultAt = result ? Date.now() : null;

  console.log('\n===== 时间线 =====');
  for (const l of lines) console.log(l);

  console.log('\n===== 结论 =====');
  if (!result) {
    console.log('interrupt 之后 **没有 result** —— 界面等不到回合结束信号（busy 会一直挂着）');
  } else {
    console.log(`interrupt → result 隔了 ${resultAt - interruptedAt}ms`);
  }
  console.log(
    `中断之后到达的增量：${deltaAfterInterrupt} 字` +
      (deltaAfterInterrupt === 0 ? '（CLI 立刻停了）' : '（**没停**）'),
  );
  console.log(
    `这一轮总共：思考 ${thinkingChars} 字、正文 ${assistantChars} 字；` +
      `中断时思考进行到 ${thinkingChars} 字`,
  );
  console.log(
    '\n对照界面：整块思考（assistant 消息里带 thinking 块）到没到？到了的话 "思考中" 会自己收尾，' +
      '没到而上面又有思考文本，那「思考中」就会一直转 —— 那就是 (b) 那条路。',
  );

  notifyInput?.(null);
  process.exit(0);
})().catch((err) => {
  console.error('探针失败：', err.message);
  process.exit(1);
});
