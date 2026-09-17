/**
 * 探针：文件回滚（Query.rewindFiles）在这条链路上到底能不能用。
 *
 * 回滚整套设计押在两件事上，而这两件事**都不能从 .d.ts 读出来**：
 *
 *   1. SDK 的 `SDKUserMessage`（往输入流里推的那个类型）在 0.3.268 时**没有
 *      uuid 字段**，只有回放事件 `SDKUserMessageReplay` 才有 ——
 *      **0.3.274 起 SDKUserMessage 自己也带上了 `uuid?: UUID`**（sdk.d.ts:5910；
 *      回放事件仍是 :5923）。但**类型收下不等于 CLI 采纳**：probe-queue.mjs 只
 *      证明了那个字段被**接受**（用的是 'client-A' 这种假串，而 CLI 对 uuid
 *      是有校验的），所以"插件自己铸一个 uuid 塞进去、之后拿它回滚"这条路
 *      照旧要实测。
 *   2. rewind 只改文件、不动对话。用户心里的"回滚"多半来自 CLI 的 /rewind
 *      （连对话一起回），界面文案必须与真实语义对齐，否则就是我们让他误解。
 *
 * 要回答的问题（清单与"为什么必须先知道"见设计稿）：
 *   Q1 不开 enableFileCheckpointing 时 rewind 说什么（对照臂）
 *   Q2 我们推上去的那条消息，事件流上以什么形式回来、带什么 uuid
 *   Q3 rewind 认哪个 id：插件铸的，还是回放事件上的   ← **决定走路线甲还是乙**
 *   Q4 真回滚（非 dryRun）之后，文件真的回到原状吗
 *   Q5 dryRun 会不会撒谎（链接那条 skippedLinks 在预览里永远不设）
 *   Q6 多轮里"回到这条之前"的准确语义
 *   Q7 回合进行中调会怎样；resume 恢复的会话能不能回滚老消息
 *
 * 走生产路径：选项照抄 session.js，环境过 env.js 的清洗。
 * **SDK 的 cwd 是临时目录** —— 探针要真改文件，不能在仓库里改；
 * 跑完**不删**，路径打在输出里：失败时它就是证据。
 *
 * 用法（cwd 必须是仓库根）：
 *
 *   node sidecar/tools/probe-rewind.mjs          # Q1-Q4、Q6（核心）
 *   node sidecar/tools/probe-rewind.mjs --links  # 追加 Q5（链接安全）
 *   node sidecar/tools/probe-rewind.mjs --busy   # 追加 Q7 前半（忙时回滚）
 *   node sidecar/tools/probe-rewind.mjs --resume # 追加 Q7 后半（恢复会话）
 *
 * 退出码：0 = 路线甲成立**且**文件真的还原；1 = 路线甲不成立（改走乙）；
 *         2 = 全局超时；3 = 模型没照做，这一跑不算数，重跑。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { randomUUID } from 'node:crypto';
import {
  mkdtempSync, readFileSync, existsSync, linkSync, symlinkSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const RUN_LINKS = process.argv.includes('--links');
const RUN_BUSY = process.argv.includes('--busy');
const RUN_RESUME = process.argv.includes('--resume');
const RUN_TWICE = process.argv.includes('--twice');

const workDir = mkdtempSync(join(tmpdir(), 'ccoder-rewind-'));
const TARGET = join(workDir, 'target.txt');   // 被改来改去的那个
const LATE = join(workDir, 'late.txt');       // 回滚点之后才建的
const LINKED = join(workDir, 'linked.txt');   // 链接安全那一问用

/** 一条能在 8 秒内跑完、不碰网络的慢命令 —— 期间才有"回合进行中"可言。 */
const SLOW = 'node -e "setTimeout(()=>console.log(\'SLOW-DONE\'),8000)"';

const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const read = (p) => (existsSync(p) ? readFileSync(p, 'utf8').trim() : '（不存在）');

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} 超时（${ms}ms）`)), ms)),
  ]);
}

/**
 * 输入流：与 session.js 同款，只多了 uuid 这条路（手法照 probe-queue.mjs）。
 * uuid 必须是**合法 UUID** —— CLI 对它有校验，'client-A' 那种假串证明不了采纳。
 */
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
    send(text, uuid = randomUUID()) {
      const item = {
        type: 'user',
        message: { role: 'user', content: text },
        parent_tool_use_id: null,
      };
      if (uuid) item.uuid = uuid;
      queue.push(item);
      notifyInput?.('go');
      return uuid;
    },
    stop() { stopped = true; notifyInput?.(null); },
  };
}

/** 起一个会话。[checkpointing]=false 那一趟是对照臂。 */
function openSession({ checkpointing = true, resume = null, cwd = workDir } = {}) {
  const input = makeInputStream();
  const events = [];
  const options = {
    cwd,
    permissionMode: 'default',
    env: buildChildEnv(process.env),
    includePartialMessages: true,   // 与 session.js 一致：联结帧就搭在 stream_event 上
    canUseTool: (name, inp) => {
      const p = String(inp?.file_path ?? '');
      const cmd = String(inp?.command ?? '');
      const ok = ((name === 'Write' || name === 'Edit') && p.startsWith(cwd))
        || (name === 'Bash' && cmd.includes('SLOW-DONE'));
      return Promise.resolve(ok
        ? { behavior: 'allow', updatedInput: inp }
        : { behavior: 'deny', message: '探针：只放行临时目录里的写' });
    },
  };
  if (checkpointing) options.enableFileCheckpointing = true;
  if (resume) options.resume = resume;

  const q = query({ prompt: input.stream, options });
  // 事件流得有人消费，否则 query 对象不推进
  (async () => {
    try { for await (const m of q) events.push(m); } catch { /* 收尾时断开属预期 */ }
  })();
  return { q, input, events, send: (t, u) => input.send(t, u) };
}

async function waitResult(events, from, ms = 120000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const r = events.slice(from).find((m) => m.type === 'result');
    if (r) return r;
    await sleep(200);
  }
  throw new Error('等不到 result');
}

/** 让模型写一个文件。写完回来核对，没照做就让整跑作废（退出码 3）。 */
async function writeTurn(ctx, path, body, label) {
  const from = ctx.events.length;
  const uuid = ctx.send(
    `请用 Write 工具把文件 ${path} 的内容写成恰好 ${body}（不要做任何别的事），完成后只回复 OK`,
  );
  await waitResult(ctx.events, from);
  const got = read(path);
  console.log(`  ${ts()} ${label} uuid=${uuid} → 文件实际=${JSON.stringify(got)}`);
  return { uuid, ok: got === body, path };
}

/** Q2 的全部证据：我们推的那条，在事件流上以什么形式、带什么 uuid 回来。 */
function dumpReplays(events, from) {
  const isToolResult = (m) => Array.isArray(m.message?.content)
    && m.message.content.some((b) => b?.type === 'tool_result');
  for (const m of events.slice(from)) {
    if (m.type !== 'user' || isToolResult(m)) continue;
    const c = m.message?.content;
    const shown = String(typeof c === 'string' ? c : JSON.stringify(c)).slice(0, 50);
    console.log(`  ${ts()} REPLAY uuid=${m.uuid ?? '-'} synthetic=${m.isSynthetic ?? '-'} `
      + `content=${JSON.stringify(shown)}`);
  }
  // 联结帧：user_message_uuid(s) 是**客户端 uuid**的回显，与消息自身的 uuid 不是一回事
  for (const m of events.slice(from)) {
    if (!m.user_message_uuid && !m.user_message_uuids) continue;
    console.log(`  ${ts()} JOIN ${m.type}/${m.subtype ?? ''} `
      + `uuid=${m.user_message_uuid ?? '-'} all=${JSON.stringify(m.user_message_uuids ?? null)}`);
  }
}

/** 回放事件里最后一条"我们自己发的"（非 tool_result 的 user 事件）的 uuid。 */
function lastReplayUuid(events, from) {
  const isToolResult = (m) => Array.isArray(m.message?.content)
    && m.message.content.some((b) => b?.type === 'tool_result');
  const replays = events.slice(from).filter((m) => m.type === 'user' && !isToolResult(m));
  return replays.at(-1)?.uuid ?? null;
}

async function dryRun(q, uuid, label) {
  try {
    const r = await withTimeout(q.rewindFiles(uuid, { dryRun: true }), 30000, `dryRun ${label}`);
    console.log(`  dryRun[${label}] canRewind=${r.canRewind} `
      + `error=${JSON.stringify(r.error ?? null)} `
      + `files=${JSON.stringify(r.filesChanged ?? null)} `
      + `skippedLinks=${r.skippedLinks ?? '(未设)'}`);
    return r;
  } catch (err) {
    const msg = String(err?.message ?? err);
    console.log(`  dryRun[${label}] 抛错：${msg}`);
    return { canRewind: false, error: msg };
  }
}

const answers = {};
const findings = [];
const note = (q, text) => { answers[q] = text; findings.push(`  ${q}: ${text}`); };

const watchdog = setTimeout(() => {
  console.log('\n!! 全局超时，强制退出');
  console.log(`临时目录：${workDir}`);
  process.exit(2);
}, 900000);

let verdict = 1;

try {
  console.log(`工作目录：${workDir}`);

  // ============ Q1 对照臂：不开开关 ============
  console.log('\n=== Q1：不开 enableFileCheckpointing 时会怎样（对照臂）===');
  {
    const ctx = openSession({ checkpointing: false });
    const from = ctx.events.length;
    const w = await writeTurn(ctx, TARGET, 'AAA', '对照臂写入');
    const r = await dryRun(ctx.q, w.uuid, '对照臂/客户端 uuid');
    note('Q1', r.canRewind
      ? '不开开关也能回滚（意外！那就没必要无条件打开它）'
      : `不开就回不了 —— 开关是必需的。error=${JSON.stringify(r.error ?? null)}`);
    // 顺便记下这个会话的磁盘代价（备份目录）
    ctx.input.stop();
    try { await ctx.q.interrupt?.(); } catch { /* 没有回合在跑时属预期 */ }
  }

  // ============ Q2/Q3/Q4/Q6：带开关的主线 ============
  console.log('\n=== Q2：带开关，第一条消息推上去后事件流长什么样 ===');
  const ctx = openSession({ checkpointing: true });
  const mark1 = ctx.events.length;
  const m1 = await writeTurn(ctx, TARGET, 'AAA', 'M1 写入');
  dumpReplays(ctx.events, mark1);
  const init = ctx.events.find((m) => m.type === 'system' && m.subtype === 'init');
  console.log(`  ${ts()} init capabilities=${JSON.stringify(init?.capabilities ?? null)}`);
  const cliUuid1 = lastReplayUuid(ctx.events, mark1);
  console.log(`  ${ts()} 客户端铸的 uuid=${m1.uuid}  回放事件上的 uuid=${cliUuid1 ?? '（没有）'}`);
  note('Q2', cliUuid1
    ? `回放事件带 uuid；客户端 uuid 是否被采纳要看 Q3`
    : '回放事件**没有** uuid —— 路线乙也不成立，得另找联结点');

  console.log('\n=== Q3：rewind 认哪个 id（决定路线甲/乙）===');
  const byClient = await dryRun(ctx.q, m1.uuid, '客户端铸的 uuid');
  const byCli = cliUuid1 ? await dryRun(ctx.q, cliUuid1, '回放事件上的 uuid') : { canRewind: false };
  const routeA = byClient.canRewind === true;
  note('Q3', routeA
    ? '路线甲成立 —— 插件自己铸 uuid 就够'
    : `路线甲不成立（${JSON.stringify(byClient.error ?? null)}）`
      + `；回放 uuid ${byCli.canRewind ? '可用 → 走路线乙' : '也不可用 → 回滚功能不可做'}`);

  console.log('\n=== Q4：真回滚（非 dryRun）之后文件真的回去了吗 ===');
  {
    // 回滚点之后再来一条：M2 把 TARGET 改成 CCC，再建 LATE
    await writeTurn(ctx, TARGET, 'CCC', 'M2 写入');
    const m3 = await writeTurn(ctx, LATE, 'BBB', 'M3 新建 late.txt');
    console.log(`  ${ts()} 回滚前：target=${JSON.stringify(read(TARGET))} late=${JSON.stringify(read(LATE))}`);
    const id = routeA ? m1.uuid : cliUuid1;
    const r = await withTimeout(ctx.q.rewindFiles(id), 60000, '真实回滚');
    console.log(`  ${ts()} 真实回执：canRewind=${r.canRewind} files=${JSON.stringify(r.filesChanged ?? null)} `
      + `+${r.insertions ?? 0} -${r.deletions ?? 0} skippedLinks=${r.skippedLinks ?? '(未设)'} `
      + `error=${JSON.stringify(r.error ?? null)}`);
    const afterTarget = read(TARGET);
    const afterLate = read(LATE);
    console.log(`  ${ts()} 回滚后：target=${JSON.stringify(afterTarget)} late=${JSON.stringify(afterLate)}`);
    const restored = afterTarget !== 'CCC';
    note('Q4', restored
      ? `文件真的回到原状（target=${JSON.stringify(afterTarget)}）；`
        + `回滚点之后新建的文件${afterLate === '（不存在）' ? '被删掉了' : `仍在（${JSON.stringify(afterLate)}）`}`
      : '**文件没变** —— 回执说成了但盘上没动，这条路不能按"它会还原"写文案');
    note('Q6', '见 Q4 的两行：把"回到这条之前"的准确含义与那两行逐字对上（M1 之后的内容应当保留）');
    void m3;
  }

  // ============ Q6：多轮语义 ============
  console.log('\n=== Q6：多轮里"回到某条之前"的准确语义 ===');
  {
    const before = `target=${JSON.stringify(read(TARGET))} late=${JSON.stringify(read(LATE))}`;
    const m4 = await writeTurn(ctx, TARGET, 'DDD', 'M4 写入');
    const r = await withTimeout(ctx.q.rewindFiles(routeA ? m4.uuid : cliUuid1), 60000, '回滚到 M4');
    console.log(`  ${ts()} 回到 M4 之前：canRewind=${r.canRewind} `
      + `target=${JSON.stringify(read(TARGET))} late=${JSON.stringify(read(LATE))}`);
    console.log(`  ${ts()} （M4 之前是 ${before}）`);
    note('Q6', `回到"某条之前" = 该条消息发出时的文件状态；`
      + `回执 files=${JSON.stringify(r.filesChanged ?? null)}`);
  }

  // ============ Q8（可选）：连续回滚，第二次还准吗 ============
  //
  // 主线跑出来的现象：回滚过一次之后，再对一条**新消息**回滚，落点不是那条
  // 消息之前的状态（M1=AAA → M2=CCC → 回滚 M1 得 AAA ✓ → M4=DDD → 回滚 M4
  // 得 **CCC**，而 CCC 是 M2 写的值）。按任何解读都不该落到 CCC。
  // 这一阶段用一条**干净的新会话**把序列跑一遍，判定它是不是"回滚一次之后
  // checkpoint 链错位"：
  //   T1=AAA → T2=BBB → 回滚 T1（期望 AAA）→ T3=CCC → 回滚 T3
  //   期望 AAA（T3 之前的状态）；若得 BBB 或 CCC 就是错位。
  if (RUN_TWICE) {
    console.log('\n=== Q8：连续回滚会不会错位（**独立目录 + 独立会话**）===');
    // 必须给一个全新的目录：快照看起来是按工作目录/文件路径组织的，
    // 与主线共用目录时，新会话的回滚会被上一段的快照污染（第一版就栽在这里，
    // 它把主线的 CCC 还了回来）。同一个项目目录开多个会话是真实场景，
    // 但那是另一个问题（见 Q8b），这里先量"单会话内连续回滚准不准"。
    const dir2 = mkdtempSync(join(tmpdir(), 'ccoder-rewind-twice-'));
    const T2F = join(dir2, 'target.txt');
    console.log(`  ${ts()} 独立目录：${dir2}`);
    const c = openSession({ checkpointing: true, cwd: dir2 });
    const t1 = await writeTurn(c, T2F, 'AAA', 'T1 写入');
    const t2 = await writeTurn(c, T2F, 'BBB', 'T2 写入');
    const r1 = await withTimeout(c.q.rewindFiles(t1.uuid), 60000, '回滚到 T1');
    const after1 = read(T2F);
    console.log(`  ${ts()} 回滚到 T1：canRewind=${r1.canRewind} `
      + `target=${JSON.stringify(after1)}（期望 AAA）`);
    const t3 = await writeTurn(c, T2F, 'CCC', 'T3 写入');
    const r2 = await withTimeout(c.q.rewindFiles(t3.uuid), 60000, '回滚到 T3');
    const after3 = read(T2F);
    console.log(`  ${ts()} 回滚到 T3：canRewind=${r2.canRewind} `
      + `target=${JSON.stringify(after3)}（期望 AAA；若是 BBB/CCC 即错位）`);
    const r3 = await withTimeout(c.q.rewindFiles(t2.uuid), 60000, '再回滚到 T2');
    console.log(`  ${ts()} 再回滚到 T2：canRewind=${r3.canRewind} `
      + `target=${JSON.stringify(read(T2F))}（诊断用：看它落到哪一格）`);
    // 正确的期望是"**发这条消息那一刻**的文件状态"：
    //   T1 发出时文件还不存在 → 回滚 T1 = 删掉它
    //   （回滚 T1 之后）T3 发出时文件同样不存在 → 回滚 T3 = 仍不存在
    //   T2 发出时文件是 T1 写的 AAA → 回滚 T2 = AAA
    const ok = after1 === '（不存在）' && after3 === '（不存在）' && read(T2F) === 'AAA';
    note('Q8', ok
      ? '连续回滚正确：语义 = **恢复到该条消息发出那一刻**的文件状态'
      : `连续回滚与"发出去那一刻"的语义对不上：`
        + `回滚 T1 得 ${JSON.stringify(after1)}、回滚 T3 得 ${JSON.stringify(after3)}`);
    c.input.stop();
    try { await c.q.interrupt?.(); } catch { /* 预期 */ }

    // ---- Q8b：同一目录、两个会话（主线那次异常的嫌疑来源）----
    console.log('\n=== Q8b：同一目录先后两个会话，后者回滚 ===');
    const dir3 = mkdtempSync(join(tmpdir(), 'ccoder-rewind-two-'));
    const F3 = join(dir3, 'target.txt');
    console.log(`  ${ts()} 目录：${dir3}`);
    {
      const s1 = openSession({ checkpointing: true, cwd: dir3 });
      await writeTurn(s1, F3, 'AAA', 'S1 写入');
      s1.input.stop();
      try { await s1.q.interrupt?.(); } catch { /* 预期 */ }
      await sleep(1000);

      const s2 = openSession({ checkpointing: true, cwd: dir3 });
      console.log(`  ${ts()} S1 结束后盘上：${JSON.stringify(read(F3))}`);
      const b = await writeTurn(s2, F3, 'BBB', 'S2 写入');
      const r = await withTimeout(s2.q.rewindFiles(b.uuid), 60000, 'S2 回滚');
      const after = read(F3);
      console.log(`  ${ts()} S2 回滚到自己那条之后：canRewind=${r.canRewind} `
        + `target=${JSON.stringify(after)}（期望 AAA —— S2 那条发出前的状态）`);
      note('Q8b', after === 'AAA'
        ? '跨会话也准 —— 主线那次异常不是"多会话"引起的'
        : `**跨会话时落点不对**：得到 ${JSON.stringify(after)}，而不是 S2 发出前的 AAA`
          + ` —— 界面要在回滚前 dryRun 并如实报 canRewind，且不能承诺"一定回到那一刻"`);
      s2.input.stop();
      try { await s2.q.interrupt?.(); } catch { /* 预期 */ }
    }
  }

  // ============ Q5（可选）：链接安全 —— dryRun 会不会撒谎 ============
  if (RUN_LINKS) {
    console.log('\n=== Q5：链接文件与 dryRun 撒谎 ===');
    let made = 'symlink';
    try { symlinkSync(TARGET, LINKED); }
    catch {
      made = 'hardlink';
      try { linkSync(TARGET, LINKED); } catch { made = null; }
    }
    console.log(`  ${ts()} 链接类型：${made ?? '造不出来（本机限制）—— 这一问**未验证**'}`);
    if (made) {
      const m5 = await writeTurn(ctx, LINKED, 'EEE', 'M5 经链接写入');
      const before = await dryRun(ctx.q, m5.uuid, '链接在场的 dryRun');
      const r = await withTimeout(ctx.q.rewindFiles(m5.uuid), 60000, '链接在场的真回滚');
      console.log(`  ${ts()} 真跑：skippedLinks=${r.skippedLinks ?? '(未设)'} `
        + `target=${JSON.stringify(read(TARGET))} linked=${JSON.stringify(read(LINKED))}`);
      const lies = before.canRewind && (r.skippedLinks ?? 0) > 0;
      note('Q5', lies
        ? '**dryRun 确实撒谎**：预览的计数不含链接安全的拒绝 —— 确认框不能承诺"没有链接被跳过"'
        : '本轮没复现出撒谎（或本机造不出链接）—— 文案仍按保守措辞写');
    } else {
      note('Q5', '本机造不出链接 → **未验证**，文案按保守措辞写');
    }
  } else {
    console.log('\n（跳过 Q5 —— 加 --links 才跑）');
  }

  // ============ Q7 前半（可选）：忙时回滚 ============
  if (RUN_BUSY) {
    console.log('\n=== Q7a：回合进行中调回滚 ===');
    const from = ctx.events.length;
    ctx.send(`请用 Bash 跑这条命令：${SLOW}，跑完告诉我 SLOW-DONE`);
    await sleep(1500);
    const t = Date.now();
    try {
      const r = await withTimeout(ctx.q.rewindFiles(routeA ? m1.uuid : cliUuid1), 40000, '忙时回滚');
      const ms = Date.now() - t;
      console.log(`  ${ts()} 忙时：canRewind=${r.canRewind} 用时=${ms}ms error=${JSON.stringify(r.error ?? null)}`);
      note('Q7a', `忙时能调，用时 ${ms}ms —— `
        + `${ms > 10000 ? '**超过 10s 的请求超时**，入口必须在忙时禁用' : '10s 超时够用'}`);
    } catch (err) {
      console.log(`  ${ts()} 忙时：抛错/超时（${Date.now() - t}ms）${err?.message ?? err}`);
      note('Q7a', `忙时抛错（${Date.now() - t}ms）→ 入口必须在忙时禁用`);
    }
    await waitResult(ctx.events, from);
  } else {
    console.log('\n（跳过 Q7a —— 加 --busy 才跑）');
  }

  // ============ Q7 后半（可选）：恢复会话 ============
  if (RUN_RESUME) {
    console.log('\n=== Q7b：resume 恢复的会话能不能回滚老消息 ===');
    const sid = ctx.events.find((m) => m.type === 'system' && m.subtype === 'init')?.session_id;
    if (!sid) {
      note('Q7b', '拿不到 session_id，未验证');
    } else {
      const ctx2 = openSession({ checkpointing: true, resume: sid });
      await sleep(3000);
      const r = await dryRun(ctx2.q, routeA ? m1.uuid : cliUuid1, '恢复会话里回滚老消息');
      note('Q7b', r.canRewind
        ? '恢复会话后老消息**仍可回滚** —— ↺ 可以挂在恢复进来的历史消息上'
        : `恢复会话后老消息回不了（${JSON.stringify(r.error ?? null)}）→ ↺ 只挂本次会话发出的消息`);
      ctx2.input.stop();
      try { await ctx2.q.interrupt?.(); } catch { /* 预期 */ }
    }
  } else {
    console.log('\n（跳过 Q7b —— 加 --resume 才跑）');
  }

  console.log('\n--- 结论 ---');
  for (const f of findings) console.log(f);
  verdict = (routeA && answers.Q4?.includes('真的回到原状')) ? 0 : 1;
} catch (err) {
  console.log(`\n!! 探针失败：${err?.message ?? err}`);
  verdict = 3;
}

clearTimeout(watchdog);
console.log(`\n临时目录（不删，失败时它就是证据）：${workDir}`);
console.log(verdict === 0
  ? '  路线甲成立且文件真的还原 —— 按原设计走 ✓'
  : '  路线甲不成立/未跑完 —— 回看上面哪一问红的，按设计稿的分支改道 ✗');
setTimeout(() => process.exit(verdict), 1500);
