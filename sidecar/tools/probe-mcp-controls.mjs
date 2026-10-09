/**
 * 探针：MCP 的两条动作请求**到底管得着什么**。
 *
 * 起因是 SDK 的一句话只说了三分之一：
 *
 *   - `setMcpServers` 的注释写得很死 —— "This only affects servers added
 *     dynamically via this method or the SDK. Servers configured via settings
 *     files are not affected."（sdk.d.ts:3176-3198）
 *   - `toggleMcpServer` / `reconnectMcpServer` 只有一句 "Throws on failure"，
 *     **一个字没说作用的集合是谁**（sdk.d.ts:3142-3156）。
 *
 * 而我们一个 server 都没动态加过：`Options.mcpServers` 全仓零命中，server 全来自
 * 项目根的 `.mcp.json`，由 CLI 自己读。于是"重连"和"停用"是不是也像
 * `setMcpServers` 一样打在空气上，只能量。
 *
 * 量法照 `probe-mcp-hooks.mjs`：**不真起 MCP server**。配一个指向不存在命令的
 * server —— 它会以 `status=failed` 出现在列表里，而"它在列表里"正是我们要的
 * 前提（配置文件确实被读到了）。整套不跑模型，只发控制请求。
 *
 * 要回答的问题：
 *   Q1 这三条方法在这台 CLI 上**存在**吗（`typeof === 'function'`）
 *   Q2 `toggle(name,false)` 对 `.mcp.json` 里的 server 生效吗（status → disabled？）
 *   Q3 再 `toggle(name,true)` 能开回来吗
 *   Q4 `reconnect(name)` 会抛吗；抛的那句话说明它**够得着**这个 server 吗
 *   Q5 `setMcpServers({})` 会不会把配置文件里的 server 弄没（照文档应该**不会**）
 *
 * **SDK 的 cwd 是临时目录** —— 探针要真写配置文件，绝不能在仓库里写。跑完**不删**。
 *
 * 用法（cwd 必须是仓库根）：
 *
 *   node sidecar/tools/probe-mcp-controls.mjs
 *
 * 退出码：0 = Q2 或 Q4 至少有一条够得着配置文件里的 server（两颗按钮里有能用的）；
 *        1 = 三条全打在空气上（那就只该留「看状态」，别画按钮）；2 = 超时。
 *
 * 2026-10-09 用 SDK 0.3.293 原样复跑：结论逐字不变；唯一可见的差别是基线的首发状态
 * 可能是 `pending` 而不是 `failed` —— 新 SDK 的 MCP 在后台连接，失败原因晚一拍才到。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-mcpctl-'));
const MCP_JSON = join(workDir, '.mcp.json');

/** 指向一个不存在的命令：连不上是**预期**的，我们要的是"它被读到了"。 */
const SERVER = 'ccoder-probe-bogus';
const BOGUS_COMMAND = 'no-such-command-ccoder-probe-xyz';

/** 每条动作之后等它落地。控制请求本身很快，慢的是 CLI 那边重连/掉线。 */
const SETTLE_MS = 1500;

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} 超时（${ms}ms）`)), ms)),
  ]);
}

function makeInputStream() {
  let stopped = false;
  let notifyInput = null;
  const queue = [];
  async function* stream() {
    while (!stopped) {
      if (queue.length > 0) { yield queue.shift(); continue; }
      const item = await new Promise((r) => { notifyInput = r; });
      notifyInput = null;
      if (item === null) return;
      yield item;
    }
  }
  return {
    stream: stream(),
    send(text) { queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null }); notifyInput?.('go'); },
    stop() { stopped = true; notifyInput?.(null); },
  };
}

function openSession() {
  const input = makeInputStream();
  const q = query({
    prompt: input.stream,
    options: {
      cwd: workDir,
      permissionMode: 'default',
      env: buildChildEnv(process.env),
      // 探针只发控制请求、不跑回合 —— 这一条是防手滑：真跑了也不会写到外面
      canUseTool: () => Promise.resolve({ behavior: 'deny', message: '探针：不发工具' }),
    },
  });
  (async () => { try { for await (const _ of q) { /* 事件不关心 */ } } catch { /* 收尾时断开属预期 */ } })();
  return { q, input };
}

/** 读一次状态，返回那个 server 的条目（没有就是没读到）。 */
async function statusOf(q, label) {
  try {
    const list = await withTimeout(q.mcpServerStatus(), 20000, 'mcpServerStatus');
    const hit = (list ?? []).find((s) => s.name === SERVER);
    console.log(`  ${ts()} [${label}] 共 ${list?.length ?? 0} 个；`
      + (hit ? `${SERVER}: status=${hit.status} error=${JSON.stringify(hit.error ?? null)}` : `${SERVER} **不在列表里**`));
    return hit ?? null;
  } catch (err) {
    console.log(`  ${ts()} [${label}] mcpServerStatus 抛错：${err?.message ?? err}`);
    return null;
  }
}

/** 发一条动作请求，把"抛没抛、抛的什么"如实打出来 —— 那句话本身就是答案。 */
async function act(q, label, fn) {
  try {
    await withTimeout(fn(), 20000, label);
    console.log(`  ${ts()} [${label}] 没抛错（CLI 收下了）`);
    return { threw: false, message: null };
  } catch (err) {
    const message = String(err?.message ?? err);
    console.log(`  ${ts()} [${label}] 抛错：${message}`);
    return { threw: true, message };
  }
}

const findings = [];
const note = (q, text) => { findings.push(`  ${q}: ${text}`); console.log(`  ${ts()} → ${q}: ${text}`); };

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  console.log(`临时目录：${workDir}`);
  process.exit(2);
}, 300000);

let verdict = 1;

try {
  console.log(`工作目录：${workDir}`);
  writeFileSync(MCP_JSON, JSON.stringify({
    mcpServers: { [SERVER]: { command: BOGUS_COMMAND, args: [] } },
  }, null, 2) + '\n');
  console.log(`写入 ${MCP_JSON}`);
  console.log(`内容：${readFileSync(MCP_JSON, 'utf8').trim().replace(/\n/g, ' ')}`);

  const { q, input } = openSession();

  // ---- Q1：方法在不在 ----
  console.log('\n=== Q1：这三条方法在这台 CLI 上存在吗 ===');
  const has = {
    mcpServerStatus: typeof q.mcpServerStatus === 'function',
    reconnectMcpServer: typeof q.reconnectMcpServer === 'function',
    toggleMcpServer: typeof q.toggleMcpServer === 'function',
    setMcpServers: typeof q.setMcpServers === 'function',
  };
  for (const [k, v] of Object.entries(has)) console.log(`  ${ts()} ${k}: ${v ? '有' : '**没有**'}`);
  note('Q1', Object.entries(has).map(([k, v]) => `${k}=${v ? '有' : '没有'}`).join(' '));
  if (!has.mcpServerStatus) {
    note('Q1-', '连状态都读不到，这台 CLI 太老，后面三条无从谈起');
    input.stop();
    throw new Error('CLI 太老');
  }

  // ---- 基线：配置文件里的 server 在列表里吗 ----
  console.log('\n=== 基线：.mcp.json 里的 server 被读到了吗 ===');
  await sleep(SETTLE_MS);
  const base = await statusOf(q, '基线');
  if (!base) {
    note('Q0', '配置文件里的 server 根本不在列表里 —— 前提不成立，后面几条不成立');
    input.stop();
    throw new Error('前提不成立');
  }
  note('Q0', `前提成立：${SERVER} 在列表里，status=${base.status}`);

  // ---- Q2：停用 ----
  console.log('\n=== Q2：toggle(name, false) 打得到配置文件里的 server 吗 ===');
  const off = has.toggleMcpServer
    ? await act(q, 'toggle(false)', () => q.toggleMcpServer(SERVER, false))
    : { threw: true, message: '方法不存在' };
  await sleep(SETTLE_MS);
  const afterOff = await statusOf(q, '停用后');
  const offWorked = !off.threw && afterOff?.status === 'disabled';
  note('Q2', offWorked
    ? '**生效**：status → disabled（这一颗按钮够得着 .mcp.json 的 server）'
    : `没生效：${off.threw ? `抛错(${off.message})` : `status 还是 ${afterOff?.status ?? '不在列表'}`}`);

  // ---- Q3：开回来 ----
  console.log('\n=== Q3：toggle(name, true) 能开回来吗 ===');
  if (has.toggleMcpServer) {
    const on = await act(q, 'toggle(true)', () => q.toggleMcpServer(SERVER, true));
    await sleep(SETTLE_MS);
    const afterOn = await statusOf(q, '启用后');
    note('Q3', !on.threw && afterOn && afterOn.status !== 'disabled'
      ? `**开回来了**：status=${afterOn.status}`
      : `没开回来：${on.threw ? `抛错(${on.message})` : `status=${afterOn?.status ?? '不在列表'}`}`);
  } else {
    note('Q3', '方法不存在，跳过');
  }

  // ---- Q4：重连 ----
  console.log('\n=== Q4：reconnect(name) 够得着吗 ===');
  const rec = has.reconnectMcpServer
    ? await act(q, 'reconnect', () => q.reconnectMcpServer(SERVER))
    : { threw: true, message: '方法不存在' };
  await sleep(SETTLE_MS);
  await statusOf(q, '重连后');
  // 这个 server 的 command 是假的，所以**抛错才是正常的** —— 问题是抛的哪一句：
  // 说"连不上/起不来"= 够得着；说"没有这个 server"= 打空气
  const reachable = !rec.threw || !/unknown|not found|no such server|未知/i.test(rec.message ?? '');
  note('Q4', rec.threw
    ? `抛了：${rec.message}（${reachable ? '像是够得着、只是连不上（预期）' : '像是够不着这个 server'}）`
    : '没抛错 —— CLI 收下了这次重连（够得着）');

  // ---- Q5：setMcpServers 打空气吗 ----
  console.log('\n=== Q5：setMcpServers({}) 会不会把配置文件里的 server 弄没 ===');
  if (has.setMcpServers) {
    const set = await act(q, 'setMcpServers({})', () => q.setMcpServers({}));
    await sleep(SETTLE_MS);
    const afterSet = await statusOf(q, '清空动态之后');
    note('Q5', afterSet
      ? `**没被弄没**（status=${afterSet.status}）—— 照文档：它只管动态加的，配置文件的不受影响`
      : `**被弄没了** —— 与文档那句话说反了，得重读一遍`);
    if (set.threw) note('Q5-', `另外 setMcpServers({}) 自己抛了：${set.message}`);
  } else {
    note('Q5', '方法不存在，跳过');
  }

  verdict = offWorked || reachable ? 0 : 1;

  input.stop();
  try { await q.interrupt?.(); } catch { /* 预期 */ }
} catch (err) {
  console.log(`\n提前收场：${err?.message ?? err}`);
} finally {
  clearTimeout(watchdog);
  console.log('\n================ 结论 ================');
  findings.forEach((f) => console.log(f));
  console.log(`\n退出码 ${verdict}：${verdict === 0 ? '有够得着的（按钮值得做）' : '三条都打在空气上（别画按钮）'}`);
  console.log(`临时目录（**没有删**，要复看就进去）：${workDir}`);
  process.exit(verdict);
}
