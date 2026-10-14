#!/usr/bin/env node
/**
 * 探针：**预热**（prewarm → claim）在这条链路上能省什么、在哪些口子上会被拒 ——
 * 给「预热」设计稿钉事实。
 *
 * sdk.d.ts（0.3.293，@alpha）写的是：
 * - `prewarm({ options })` 先把进程连 tools / 用户级 skills·plugins / MCP 握手 /
 *   initialize 全做完，停在"停车位"；`spare.claim({ prompt, options })` 用
 *   { cwd, model, permissionMode, … } 认领成一条会话。**claim 能设的字段里没有
 *   `resume`** —— 而本插件的主用线（重开后恢复会话）恰恰是 resume，这条要证伪。
 * - `startup({ options })` 先孵一个握过手的进程，`warm.query(prompt)` 直接写提示词
 *   （"saves only the spawn"）—— 它要 options 全 upfront，resume 能不能走它在丁/戊量。
 *
 * 但"文档说"与"真机上是不是"是两件事（本仓的老规矩）。四问：
 *
 *   甲 prewarm 起得起吗（CLI 要认识 --await-claim）？就绪花多久？claim 到本仓目录
 *      成不成？认领后第一问比冷 query 快多少？prewarm 时注册的 canUseTool / hooks
 *      在认领后的会话里还灵不灵（这是插件权限卡与快照钩子的依靠）？
 *   乙 对照臂：冷 query（新会话），量 query()→init 与首问→result。
 *   丙 证伪臂：claim 的 options 里塞一个没文档化的 `resume` —— 恢复、静默开新、
 *      还是被拒？三种结果三种记法。
 *   丁 startup（options 含 resume）→ warm.query：init 的 session_id 是否 == 目标？
 *      首问比冷 resume（戊）快多少？
 *   戊 对照臂：冷 query（resume）。
 *
 * 顺带两件（挂在甲/乙里）：
 *   - 「热切三件套」（setPermissionMode / applyFlagSettings / setModel）在认领的 Query
 *     上还灵吗 —— 面板换模式、换思考档、换模型全靠它们；
 *   - MCP 落定时刻（插件同款判据，McpSettleWatch）认领路 vs 冷起路有没有差 ——
 *     "等带子转绿"那件事值不值得提前。
 *
 * 会建几条探针会话（落在本工程的会话列表里）并各发 1–2 问，走当前网关，花几分钱。
 * 跑法：node sidecar/tools/probe-prewarm.mjs [模型名]
 * 退出码：0 = 走完了（**结论在输出里**）；1 = 起不来。
 */
import { query, prewarm, startup } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { resolveClaudePath } from '../claude-path.js';

const MODEL = process.argv[2] ?? 'deepseek-v4-flash[1m]';
const env = buildChildEnv(process.env);
const cwd = process.cwd();
const claudePath = resolveClaudePath({});

console.log(`模型  : ${MODEL}`);
console.log(`cwd   : ${cwd}`);
console.log(`claude: ${claudePath ?? '(内置)'}`);
console.log('---');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(get, timeoutMs = 180000) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const v = get();
    if (v) return v;
    await sleep(200);
  }
  throw new Error('等待超时');
}
const dt = (t0) => `${Date.now() - t0}ms`;

/** 一个可发多条、能收尾的输入流（照 session.js / probe-usage-delta 的形制）。 */
function makeInput() {
  const queue = [];
  let notify = null;
  async function* gen() {
    while (true) {
      if (queue.length > 0) { yield queue.shift(); continue; }
      const item = await new Promise((r) => { notify = r; });
      notify = null;
      if (item === null) return;
      yield item;
    }
  }
  return {
    stream: gen(),
    send(text) {
      queue.push({ type: 'user', message: { role: 'user', content: text }, parent_tool_use_id: null });
      notify?.('go');
    },
    close() { notify?.(null); },
  };
}

/** 宿主级选项 —— 与 session.js 建会话那份同形（预热时这些都是"固定件"）。 */
function hostOptions(rec) {
  return {
    env,
    includePartialMessages: true,
    forwardSubagentText: true,
    agentProgressSummaries: true,
    allowDangerouslySkipPermissions: true,
    ...(claudePath ? { pathToClaudeCodeExecutable: claudePath } : {}),
    canUseTool: (toolName, _input, _opts) => {
      rec.permissions.push(toolName);
      console.log(`    [canUseTool] ${toolName}`);
      return Promise.resolve({ behavior: 'allow' });
    },
    hooks: {
      PreToolUse: [{
        hooks: [async (hi) => {
          rec.hooks.push(hi?.tool_name);
          console.log(`    [hook] PreToolUse ${hi?.tool_name}`);
          return {};
        }],
      }],
    },
  };
}

const PROMPT = (tag) => `不要使用任何工具。只回两个字母：OK（${tag}）。`;
const TOOL_PROMPT = '用 Write 工具把字符串 ok 写入文件 C:/Users/CY/AppData/Local/Temp/ccoder-prewarm-probe.txt（已存在就覆盖）。';

/** 盯住一条流：抓 init 时刻、发出后的首字/收尾时刻、session_id。 */
function watch(q, t0) {
  const st = { sid: null, initMs: null, sendAt: null, firstAfterSendMs: null, firstContentMs: null,
    resultCount: 0, results: [], error: null };
  const done = (async () => {
    try {
      for await (const msg of q) {
        const now = Date.now();
        if (msg?.type === 'system' && msg?.subtype === 'init') {
          st.sid = st.sid ?? msg.session_id ?? null;
          st.initMs = st.initMs ?? (now - t0);
        }
        if (st.sendAt !== null) {
          st.firstAfterSendMs = st.firstAfterSendMs ?? (now - st.sendAt);
          if (msg?.type === 'stream_event' || msg?.type === 'assistant' || msg?.type === 'result') {
            st.firstContentMs = st.firstContentMs ?? (now - st.sendAt);
          }
        }
        if (msg?.type === 'result') {
          st.resultCount++;
          st.results.push({
            ms: st.sendAt !== null ? now - st.sendAt : null,
            text: String(msg.result ?? '').replace(/\s+/g, ' ').slice(0, 60),
          });
        }
      }
    } catch (err) {
      st.error = String(err?.message ?? err);
    }
  })();
  return { st, done };
}

/** 一行读数（统一格式，便于横向对照）。 */
function line(label, st, extra = '') {
  const r0 = st.results[0];
  return `${label}：init ${st.initMs ?? '?'}ms · 发出→首字 ${st.firstContentMs ?? '?'}ms` +
    ` · 发出→result ${r0?.ms ?? '?'}ms${extra}`;
}

/**
 * 等 MCP 落定 —— **与插件同款判据**（McpSettleWatch：非 pending 即落定；
 * SDK 0.3.293 起 MCP 后台连，起手空表不算落定）。t0 = 会话起手时刻（claim/query 调用）。
 */
async function settleMcp(q, label, t0) {
  const firstAt = {};
  let lastList = [];
  try {
    for (;;) {
      const list = (await q.mcpServerStatus?.()) ?? [];
      if (list.length > 0) lastList = list;
      for (const s of list) {
        if (s?.status !== 'pending' && s?.name && firstAt[s.name] == null) {
          firstAt[s.name] = Date.now() - t0;
        }
      }
      const pendingLeft = list.filter((s) => s?.status === 'pending').length;
      const elapsed = Date.now() - t0;
      if ((list.length > 0 && pendingLeft === 0) || elapsed > 20000) {
        const summary = lastList.map((s) => `${s?.name}:${s?.status}@${firstAt[s?.name] ?? '?'}ms`).join(' ');
        console.log(`  [${label}] MCP ${pendingLeft === 0 && list.length > 0 ? `落定 ${elapsed}ms` : `未落定（20s 封顶，剩 ${pendingLeft} 台 pending）`} —— ${summary || '（一直空表）'}`);
        return;
      }
      await sleep(500);
    }
  } catch (err) {
    console.log(`  [${label}] MCP 状态查询失败：${err?.message ?? err}`);
  }
}

let TARGET = null;

// ---- 甲：prewarm → claim（新会话） ----
{
  console.log('\n=== 甲：prewarm → claim（新会话，含工具回调考核）===');
  const rec = { permissions: [], hooks: [] };
  let spare = null;
  const t0 = Date.now();
  try {
    spare = await prewarm({ options: hostOptions(rec) });
    console.log(`  prewarm 就绪：${dt(t0)}（进程已孵好，停在停车位）`);
  } catch (err) {
    console.log(`  ✗ prewarm 起不来：${err?.message ?? err}`);
  }
  if (spare) {
    let claimReject = null;
    spare.claimed.then(
      (v) => console.log(`  claimed：sid=${v.sessionId} parkedMs=${v.parkedMs} mcpSettled=${v.sdkMcpSettled}`),
      (e) => { claimReject = e; },
    );

    const inp = makeInput();
    const tClaim = Date.now();
    let q = null;
    try {
      q = spare.claim({ prompt: inp.stream, options: { cwd, model: MODEL, permissionMode: 'default' } });
    } catch (err) {
      console.log(`  ✗ claim 同步抛错：${err?.message ?? err}`);
    }
    if (q) {
      const { st, done } = watch(q, tClaim);
      st.sendAt = Date.now();
      inp.send(PROMPT('甲1'));
      try {
        await waitFor(() => st.resultCount >= 1);
        console.log(`  ${line('认领→首答', st, ` · sid=${st.sid}`)}`);
        console.log(`  首答文本：${st.results[0]?.text ?? ''}`);
        TARGET = st.sid;
        await settleMcp(q, '甲·认领后', tClaim);
        // 工具回调考核：prewarm 时注册的 canUseTool / hooks 在认领后的会话里还灵吗
        const permBefore = rec.permissions.length;
        const hookBefore = rec.hooks.length;
        st.sendAt = Date.now();
        inp.send(TOOL_PROMPT);
        await waitFor(() => st.resultCount >= 2, 120000);
        const permHit = rec.permissions.length - permBefore;
        const hookHit = rec.hooks.length - hookBefore;
        console.log(`  工具回合（${st.results[1]?.ms ?? '?'}ms）：canUseTool 命中 ${permHit} 次${permHit ? `（${rec.permissions.slice(permBefore).join(', ')}）` : '（❌ 没触发）'}` +
          ` · hook 命中 ${hookHit} 次${hookHit ? `（${rec.hooks.slice(hookBefore).join(', ')}）` : '（❌ 没触发）'}`);
        console.log(`  工具回合文本：${st.results[1]?.text ?? ''}`);
        // 流式输入那几个"仅流式可用"的热切（面板换模式/思考/模型全靠它们）在
        // "认领的 Query"上还灵吗 —— 一调一还，原样复位
        let modeCheck;
        try { await q.setPermissionMode('acceptEdits'); await q.setPermissionMode('default'); modeCheck = '✅'; }
        catch (e) { modeCheck = `❌ ${e?.message ?? e}`; }
        let effortCheck;
        try { await q.applyFlagSettings({ effortLevel: 'high' }); await q.applyFlagSettings({ effortLevel: null }); effortCheck = '✅'; }
        catch (e) { effortCheck = `❌ ${e?.message ?? e}`; }
        let modelCheck;
        try { await q.setModel(MODEL); modelCheck = '✅'; }
        catch (e) { modelCheck = `❌ ${e?.message ?? e}`; }
        console.log(`  热切三件套：模式 ${modeCheck} · 思考档 ${effortCheck} · 模型 ${modelCheck}`);
      } catch (err) {
        console.log(`  （等待失败：${err?.message ?? err}）`);
      }
      inp.close();
      await done;
    }
    await sleep(500);
    if (claimReject) console.log(`  ✗ claimed 拒绝：${claimReject?.message ?? claimReject}`);
    try { spare.close(); } catch { /* 已退 */ }
  }
}

// ---- 乙：冷 query（对照·新会话） ----
{
  console.log('\n=== 乙：冷 query（对照·新会话）===');
  const rec = { permissions: [], hooks: [] };
  const inp = makeInput();
  const t0 = Date.now();
  let q = null;
  try {
    q = query({ prompt: inp.stream, options: { ...hostOptions(rec), cwd, model: MODEL, permissionMode: 'default' } });
  } catch (err) {
    console.log(`  ✗ query 抛错：${err?.message ?? err}`);
  }
  if (q) {
    const { st, done } = watch(q, t0);
    st.sendAt = Date.now();
    inp.send(PROMPT('乙'));
    try {
      await waitFor(() => st.resultCount >= 1);
      console.log(`  ${line('冷 query→首答', st, ` · sid=${st.sid}`)}`);
      TARGET = TARGET ?? st.sid;
      await settleMcp(q, '乙·冷起后', t0);
      // 工具回调对照：同一句工具提示词，冷 query 里 canUseTool 会不会响
      const permBefore = rec.permissions.length;
      const hookBefore = rec.hooks.length;
      st.sendAt = Date.now();
      inp.send(TOOL_PROMPT);
      await waitFor(() => st.resultCount >= 2, 120000);
      const permHit = rec.permissions.length - permBefore;
      const hookHit = rec.hooks.length - hookBefore;
      console.log(`  工具回合（${st.results[1]?.ms ?? '?'}ms）：canUseTool 命中 ${permHit} 次${permHit ? `（${rec.permissions.slice(permBefore).join(', ')}）` : '（❌ 没触发）'}` +
        ` · hook 命中 ${hookHit} 次${hookHit ? `（${rec.hooks.slice(hookBefore).join(', ')}）` : '（❌ 没触发）'}`);
      console.log(`  工具回合文本：${st.results[1]?.text ?? ''}`);
    } catch (err) {
      console.log(`  （等待失败：${err?.message ?? err}）`);
    }
    inp.close();
    await done;
    try { q.close?.(); } catch { /* 已退 */ }
  }
}

// ---- 丙：claim 带 resume（未文档化的口，来证伪） ----
if (!TARGET) {
  console.log('\n=== 丙：跳过（甲/乙都没拿到可恢复的会话 id）===');
} else {
  console.log(`\n=== 丙：claim 带 resume —— 证伪臂（照 d.ts，ClaimOptions 没有这个键；本臂量"真机怎么接"）===`);
  console.log(`  目标会话：${TARGET}`);
  const rec = { permissions: [], hooks: [] };
  let spare = null;
  try {
    spare = await prewarm({ options: hostOptions(rec) });
    console.log('  prewarm 就绪（第二只）');
  } catch (err) {
    console.log(`  ✗ prewarm 起不来：${err?.message ?? err}`);
  }
  if (spare) {
    let claimReject = null;
    spare.claimed.catch((e) => { claimReject = e; });
    const inp = makeInput();
    const tClaim = Date.now();
    let q = null;
    try {
      // 刻意多带一个没文档化的 resume 键：恢复、静默开新、被拒，三种都记
      q = spare.claim({ prompt: inp.stream, options: { cwd, model: MODEL, permissionMode: 'default', resume: TARGET } });
    } catch (err) {
      console.log(`  ✗ claim 同步抛错：${err?.message ?? err}`);
    }
    if (q) {
      const { st, done } = watch(q, tClaim);
      st.sendAt = Date.now();
      inp.send(PROMPT('丙'));
      try {
        await waitFor(() => st.resultCount >= 1, 120000);
        console.log(`  init：sid=${st.sid} → ${st.sid === TARGET ? '✅ 恢复到了目标会话' : '❌ 不是目标会话（resume 被忽略，开了新会话）'}`);
        console.log(`  ${line('认领→首答', st)}`);
      } catch (err) {
        console.log(`  （等待失败：${err?.message ?? err}）`);
      }
      inp.close();
      await done;
    }
    await sleep(500);
    if (claimReject) console.log(`  ✗ claimed 拒绝：${claimReject?.message ?? claimReject}`);
    try { spare.close(); } catch { /* 已退 */ }
  }
}

// ---- 丁：startup（含 resume）→ warm.query ----
if (!TARGET) {
  console.log('\n=== 丁/戊：跳过（没有可恢复的目标会话）===');
} else {
  console.log('\n=== 丁：startup（options 含 resume）→ warm.query ===');
  console.log(`  目标会话：${TARGET}`);
  const rec = { permissions: [], hooks: [] };
  const t0 = Date.now();
  let warm = null;
  try {
    warm = await startup({
      options: { ...hostOptions(rec), cwd, model: MODEL, permissionMode: 'default', resume: TARGET },
    });
    console.log(`  startup 就绪：${dt(t0)}（只省孵化，其余在首问前仍要跑）`);
  } catch (err) {
    console.log(`  ✗ startup 起不来：${err?.message ?? err}`);
  }
  if (warm) {
    const inp = makeInput();
    const t1 = Date.now();
    let q = null;
    try {
      q = warm.query(inp.stream);
    } catch (err) {
      console.log(`  ✗ warm.query 抛错：${err?.message ?? err}`);
    }
    if (q) {
      const { st, done } = watch(q, t1);
      st.sendAt = Date.now();
      inp.send(PROMPT('丁'));
      try {
        await waitFor(() => st.resultCount >= 1);
        console.log(`  ${line('warm→首答', st, ` · sid=${st.sid}${st.sid === TARGET ? '（✅ 恢复到了目标会话）' : '（❌ 与目标不一致）'}`)}`);
      } catch (err) {
        console.log(`  （等待失败：${err?.message ?? err}）`);
      }
      inp.close();
      await done;
      try { q.close?.(); } catch { /* 已退 */ }
    }
    try { warm.close(); } catch { /* 已退 */ }
  }

  // ---- 戊：冷 query（对照·resume） ----
  console.log('\n=== 戊：冷 query（对照·resume）===');
  const rec5 = { permissions: [], hooks: [] };
  const inp5 = makeInput();
  const t5 = Date.now();
  let q5 = null;
  try {
    q5 = query({
      prompt: inp5.stream,
      options: { ...hostOptions(rec5), cwd, model: MODEL, permissionMode: 'default', resume: TARGET },
    });
  } catch (err) {
    console.log(`  ✗ query 抛错：${err?.message ?? err}`);
  }
  if (q5) {
    const { st, done } = watch(q5, t5);
    st.sendAt = Date.now();
    inp5.send(PROMPT('戊'));
    try {
      await waitFor(() => st.resultCount >= 1);
      console.log(`  ${line('冷 resume→首答', st, ` · sid=${st.sid}${st.sid === TARGET ? '（恢复到了目标会话）' : '（❌ 与目标不一致）'}`)}`);
    } catch (err) {
      console.log(`  （等待失败：${err?.message ?? err}）`);
    }
    inp5.close();
    await done;
    try { q5.close?.(); } catch { /* 已退 */ }
  }
}

console.log('\n=== 结论（对照四件事）===');
console.log('  ① prewarm 起不起得来 + 就绪耗时：甲的第一行 —— 这是"提前付的学费"');
console.log('  ② claim 认领后还剩多少：甲 "claim→init"，与乙 "query()→init" 对照');
console.log('  ③ 第一问快多少：甲 vs 乙（新会话）；恢复路径：丁 vs 戊');
console.log('  ④ resume 走不走得通：丙（claim 带 resume 键）—— 恢复 / 静默开新 / 被拒；' +
  '丁（startup 带 resume）—— sid 是否 == 目标');
console.log('  ⑤ 热切三件套在认领的 Query 上全 ✅ 才说明 claim 这条路对插件是活的（甲的那行）');
console.log('  ⑥ MCP 落定：甲/乙两行对照 —— 认领能不能把"等带子转绿"提前');
