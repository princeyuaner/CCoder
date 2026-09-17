/**
 * 探针：`/compact` 在这条链路上的现场。
 *
 * 状态卡上的「压缩上下文」按钮（spec `2026-09-17-card-actions-design.md`）押在
 * 四件**读文档读不出来**的事上 —— 两份实测记录甚至互相矛盾：
 *
 *   Q1 压缩期间 assistant 的文本到底是什么？
 *      composer 设计稿 §5.1 说"`/compact` 的 assistant 文本是空串"，
 *      §10.5 的事件链里却有一条 `[text]` 标着"摘要本身"。
 *      这不是小事：插件对命令回合只抑制**空串与 `(no content)`**（MessageRenderer §5.1），
 *      若摘要是非空文本，点一下压缩就会在转写区冒出一大段摘要 —— 是接受还是抑制，得先知道现场。
 *   Q2 "压缩中"该认哪个 status 值？（`requesting`？`compacting`？）—— 卡上的状态与按钮灰态靠它。
 *   Q3 `compact_boundary` 的原始形状：字段是不是嵌在 `compact_metadata` 里、三个字段齐不齐
 *      （回执文案要按缺字段降级）。
 *   Q4 boundary 与 init 的先后 —— 决定"压缩中"什么时候结束。
 *
 * **不是产品代码**，与 `probe-slash.mjs` 同一个惯例留在仓库里：结论回填 spec §8/§9 后，
 * 实现期或复查期都可重跑。
 *
 * 选项照抄 session.js、环境走 env.js 的清洗（测生产路径，不是宽松旁路）；
 * 探针自己踩过的坑记在 composer 设计稿 §10.6 —— 这里照单全避：
 * `canUseTool` 的 allow 分支原样回传 input、必须消费事件流、init 要等首条消息。
 *
 * 用法（`MSYS_NO_PATHCONV=1` 是必需的，否则 Git Bash 会把 `/compact` 转成路径）：
 *
 *   cd <repo root>
 *   MSYS_NO_PATHCONV=1 node sidecar/tools/probe-compact.mjs
 *
 * 退出码：0 = 四个问题都拿到了答案；2 = 全局超时；3 = 流错误。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';

const events = [];
const waiters = [];

function waitFor(pred, label, ms, from = 0) {
  const hit = events.slice(from).find(pred);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    const w = { pred, resolve };
    waiters.push(w);
    setTimeout(() => {
      const i = waiters.indexOf(w);
      if (i >= 0) {
        waiters.splice(i, 1);
        reject(new Error(`超时（${ms}ms）：${label}`));
      }
    }, ms).unref?.();
  });
}

/** 事件一行一条打出来；status / compact_boundary / init 的载荷整份打。 */
function onEvent(msg) {
  // 先入账再打 —— 漏了这一行的话 waitFor 照样能用（waiter 直接 resolve），
  // 但"结论"段按区间切片会全空：第一版探针就是这么哑掉的
  events.push(msg);
  const tag = `${msg.type}/${msg.subtype ?? ''}`;
  let extra = '';

  if (msg.type === 'assistant') {
    // Q1 的正主：每块的类型 + 文本长度与开头 —— "空串"和"没有这一块"是两回事
    const blocks = (msg.message?.content ?? []).map((b) =>
      b.type === 'text' ? `text(${b.text.length}):${JSON.stringify(b.text.slice(0, 60))}` : b.type,
    );
    extra = `[${blocks.join(', ')}]`;
  } else if (msg.type === 'system' && msg.subtype === 'status') {
    // Q2 的正主：status 的原值，不加工
    extra = `status=${JSON.stringify(msg.status ?? null)} compact_result=${JSON.stringify(msg.compact_result ?? null)}`;
  }

  if (msg.type !== 'stream_event') {
    console.log(`  EVENT ${tag} ${extra}`);
    if (['compact_boundary', 'init'].includes(msg.subtype)) {
      const { uuid, ...rest } = msg;
      // Q3：整份打，不截断 —— 字段形状就是结论本身
      console.log(`    payload: ${JSON.stringify(rest)}`);
    }
  }

  for (const w of waiters.slice()) {
    if (w.pred(msg)) {
      waiters.splice(waiters.indexOf(w), 1);
      w.resolve(msg);
    }
  }
}

// ---- 输入流：与 session.js 同款 ----
let stopped = false;
let notifyInput = null;
const queue = [];

async function* inputStream() {
  while (!stopped) {
    if (queue.length > 0) {
      yield queue.shift();
      continue;
    }
    const item = await new Promise((r) => { notifyInput = r; });
    notifyInput = null;
    if (item === null) return;
    yield item;
  }
}

function send(text) {
  queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
  notifyInput?.('go');
}

async function turn(text, label, ms = 120000) {
  const mark = events.length;
  console.log(`\n=== ${label} ===`);
  console.log(`  发送：${JSON.stringify(text)}`);
  send(text);
  const res = await waitFor((m) => m.type === 'result', `${label} 的 result`, ms, mark);
  console.log(`  → result.subtype=${res.subtype}`);
  return { res, mark };
}

// ---- 主流程 ----
const cwd = process.cwd();
console.log(`工作目录：${cwd}`);

const q = query({
  prompt: inputStream(),
  options: {
    cwd,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    // 与 probe-slash 同款：只放行只读工具，allow 分支必须原样回传 input
    canUseTool: (toolName, input) =>
      Promise.resolve(
        ['Read', 'Glob', 'Grep'].includes(toolName)
          ? { behavior: 'allow', updatedInput: input }
          : { behavior: 'deny', message: '探针：只放行只读工具' },
      ),
  },
});

// 消费事件流 —— 少了这一圈，一个事件都收不到（§10.6）
(async () => {
  try {
    for await (const msg of q) onEvent(msg);
  } catch (err) {
    console.log(`\n!! 流错误：${err?.message ?? err}`);
  }
})();

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  process.exit(2);
}, 300000);

try {
  console.log('\n--- 阶段 1：垫两句，让会话有东西可压 ---');
  await turn('回复 exactly: OK', '预热');
  await turn('回复 exactly: DONE', '第二句');

  console.log('\n--- 阶段 2：发 /compact，看现场 ---');
  const mark = events.length;
  send('/compact');
  // 压缩那一回合以 boundary 或 result 收尾 —— 两个都等，谁先到算谁
  const end = await Promise.race([
    waitFor((m) => m.subtype === 'compact_boundary', 'compact_boundary', 180000, mark),
    waitFor((m) => m.type === 'result', '/compact 的 result', 180000, mark),
  ]).catch((err) => {
    console.log(`  !! ${err.message}`);
    return null;
  });
  console.log(`  先到的是：${end ? `${end.type}/${end.subtype}` : '（都没有）'}`);
  // 再给 boundary 一点时间（若 result 先到，boundary 可能紧随其后）
  await waitFor((m) => m.subtype === 'compact_boundary', 'compact_boundary(补等)', 20000, mark).catch(() => {});
  await new Promise((r) => setTimeout(r, 1000)); // 收最后几片

  console.log('\n===== 结论 =====');

  const compactEvents = events.slice(mark);
  console.log('\nQ1 · 压缩期间 assistant 文本（空串 / (no content) / 摘要？）');
  const assistantBlocks = compactEvents
    .filter((m) => m.type === 'assistant')
    .flatMap((m) => (m.message?.content ?? []).filter((b) => b.type === 'text').map((b) => b.text));
  if (assistantBlocks.length === 0) {
    console.log('  没有 assistant 文本块 —— 转写区不会被摘要污染');
  } else {
    for (const t of assistantBlocks) {
      console.log(
        `  长度 ${t.length}：${JSON.stringify(t.slice(0, 100))}` +
          (t.length === 0 ? '   ← 空串（命令回合会抑制）' : t.trim() === '(no content)' ? '   ← 占位符（会抑制）' : '   ← **非空，会渲染成气泡**'),
      );
    }
  }

  console.log('\nQ2 · status 的取值（压缩中的判据）');
  const statuses = compactEvents
    .filter((m) => m.type === 'system' && m.subtype === 'status')
    .map((m) => `status=${JSON.stringify(m.status ?? null)} compact_result=${JSON.stringify(m.compact_result ?? null)}`);
  console.log(statuses.length ? `  ${statuses.join('\n  ')}` : '  （这一回合没有 status 事件）');
  console.log('  提示：`requesting` 每一回合都会发（预热那两句也有），**不是**压缩专属 —— 判据只认 `compacting`');

  console.log('\nQ3 · compact_boundary 的字段形状');
  const boundary = compactEvents.find((m) => m.subtype === 'compact_boundary');
  if (boundary) {
    console.log(`  顶层键：${Object.keys(boundary).join(', ')}`);
    console.log(`  payload: ${JSON.stringify(boundary.compact_metadata ?? boundary)}`);
  } else {
    console.log('  **没有收到 compact_boundary** —— 压缩可能没执行（会话太短？），看上面的 result');
  }

  console.log('\nQ4 · boundary 与 init 的先后');
  console.log(
    `  这一回合的事件序列：\n    ${compactEvents.map((m) => `${m.type}/${m.subtype ?? ''}`).join(' → ')}`,
  );

  console.log('\n--- 收尾 ---');
  stopped = true;
  notifyInput?.(null);
  clearTimeout(watchdog);
  setTimeout(() => process.exit(0), 1500);
} catch (err) {
  console.log(`\n!! 探针失败：${err.message}`);
  clearTimeout(watchdog);
  stopped = true;
  notifyInput?.(null);
  setTimeout(() => process.exit(3), 1500);
}
