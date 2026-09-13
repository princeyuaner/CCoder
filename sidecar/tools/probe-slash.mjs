/**
 * 探针：slash 命令怎么触发、@ 引用会不会被 CLI 展开。
 *
 * **不是产品代码**，但与 `web/tools/layout-probe.mjs` 同一个惯例留在仓库里 ——
 * 设计稿 §9 那条"名字配对规则"的确认项要用它重跑。
 *
 * 选项照抄 session.js，环境走 env.js 的清洗，保证测的是生产路径而不是一个
 * 更宽松的旁路。探针自己踩过的三个坑记在设计稿 §10.6。
 *
 * 用法（`MSYS_NO_PATHCONV=1` 是必需的，否则 Git Bash 会把 `/cost` 转成
 * `C:/Program Files/Git/cost`）：
 *
 *   cd <repo root>
 *   MSYS_NO_PATHCONV=1 node sidecar/tools/probe-slash.mjs "/cost" "/context"
 *
 * 不带参数则跑默认那三问。
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

function onEvent(msg) {
  events.push(msg);
  const tag = `${msg.type}/${msg.subtype ?? ''}`;
  let extra = '';
  if (msg.type === 'assistant') {
    const types = (msg.message?.content ?? []).map((b) => b.type).join(',');
    extra = `[${types}]`;
  } else if (msg.type === 'system' && msg.subtype === 'local_command_output') {
    extra = JSON.stringify(String(msg.content ?? '').slice(0, 120));
  }
  // stream_event 是增量分片，几百条，只计数不打印
  if (msg.type !== 'stream_event') {
    console.log(`  EVENT ${tag} ${extra}`);
    // 这几个的载荷形状是设计要用的，整份打出来
    if (['compact_boundary', 'init', 'status', 'commands_changed'].includes(msg.subtype)) {
      const { ...rest } = msg;
      delete rest.uuid;
      console.log(`    payload: ${JSON.stringify(rest).slice(0, 1200)}`);
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
  queue.push({
    type: 'user',
    message: { role: 'user', content: text },
    parent_tool_use_id: null,
  });
  notifyInput?.('go');
}

function assistantTexts(from) {
  return events
    .slice(from)
    .filter((m) => m.type === 'assistant')
    .flatMap((m) => (m.message?.content ?? []).filter((b) => b.type === 'text').map((b) => b.text))
    .join('');
}

function toolUses(from) {
  return events
    .slice(from)
    .filter((m) => m.type === 'assistant')
    .flatMap((m) => (m.message?.content ?? []).filter((b) => b.type === 'tool_use').map((b) => b.name));
}

async function turn(text, label) {
  const mark = events.length;
  console.log(`\n=== ${label} ===`);
  console.log(`  发送：${JSON.stringify(text)}`);
  send(text);
  const res = await waitFor((m) => m.type === 'result', `${label} 的 result`, 120000, mark);
  const localOut = events
    .slice(mark)
    .filter((m) => m.type === 'system' && m.subtype === 'local_command_output');
  console.log(`  → result.subtype=${res.subtype}  local_command=${JSON.stringify(res.local_command ?? null)}  num_turns=${res.num_turns}`);
  console.log(`  → local_command_output 事件：${localOut.length} 个`);
  console.log(`  → 本轮 tool_use：${JSON.stringify(toolUses(mark))}`);
  console.log(`  → assistant 文本：${JSON.stringify(assistantTexts(mark).slice(0, 400))}`);
  return res;
}

// ---- 主流程 ----
const cwd = process.cwd();
console.log(`工作目录：${cwd}`);
console.log(`环境清洗后 ANTHROPIC_BASE_URL 存在：${'ANTHROPIC_BASE_URL' in buildChildEnv(process.env)}`);
console.log(`环境清洗后 CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST 存在：${'CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST' in buildChildEnv(process.env)}`);

const q = query({
  prompt: inputStream(),
  options: {
    cwd,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,
    // 探针只读，不碰写操作
    // allow 分支必须把 input 原样回传：SDK 的 .d.ts 把 updatedInput 标成可选，
    // 运行时校验却要求它，漏了会让工具直接报 ZodError（本轮探针实测踩到）
    canUseTool: (toolName, input) =>
      Promise.resolve(
        ['Read', 'Glob', 'Grep'].includes(toolName)
          ? { behavior: 'allow', updatedInput: input }
          : { behavior: 'deny', message: '探针：只放行只读工具' },
      ),
  },
});

// 消费事件流 —— 少了这一圈，query 对象不会推进，一个事件都收不到
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
}, 240000);

try {
  console.log('\n--- 阶段 0：先发一条消息，看 init 是不是被首条输入触发的 ---');
  const initBefore = events.some((m) => m.type === 'system' && m.subtype === 'init');
  console.log(`  发消息前收到 init：${initBefore}`);
  await turn('回复 exactly: OK', '预热消息');
  const init = events.find((m) => m.type === 'system' && m.subtype === 'init');
  if (init) {
    console.log(`  发消息后收到 init：true`);
    console.log(`  model=${init.model}  session_id=${init.session_id}`);
    console.log(`  init 上的 slash_commands 字段：${JSON.stringify(init.slash_commands ?? null)}`);
    console.log(`  tools 数量：${(init.tools ?? []).length}`);
  } else {
    console.log(`  发消息后仍没有 init —— 它不是被输入触发的`);
  }

  console.log('\n--- 问题 1：supportedCommands() 能不能拿到命令列表 ---');
  try {
    const cmds = await Promise.race([
      q.supportedCommands(),
      new Promise((_, rj) => setTimeout(() => rj(new Error('supportedCommands 超时')), 20000)),
    ]);
    console.log(`  拿到 ${cmds.length} 条：`);
    for (const c of cmds) {
      console.log(`    /${c.name}  ${c.argumentHint ?? ''}  —  ${c.description ?? ''}${c.aliases?.length ? `  [别名: ${c.aliases.join(', ')}]` : ''}`);
    }
    console.log(`  含 cost：${cmds.some((c) => c.name === 'cost' || c.aliases?.includes('cost'))}`);
    console.log(`  含 compact：${cmds.some((c) => c.name === 'compact')}`);
  } catch (err) {
    console.log(`  !! 失败：${err.message}`);
  }

  const extra = process.argv.slice(2);
  if (extra.length > 0) {
    for (const text of extra) {
      await turn(text, `发送 ${text}`);
    }
  } else {
    console.log('\n--- 问题 2：把 "/cost" 当普通文本发过去，会被当命令执行吗 ---');
    await turn('/cost', '发送 /cost');

    console.log('\n--- 问题 3：文本里的 @ 路径会不会被 CLI 展开 ---');
    await turn('@sidecar/package.json 这个文件里 version 字段的值是什么？只回答值本身。', '发送 @ 引用');
  }

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
  setTimeout(() => process.exit(1), 500);
}
