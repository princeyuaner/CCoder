/**
 * 使用量账本 —— 日/周/月统计的数据层（侧车侧）。
 *
 * ## 口径（都是探针实测钉死的，见 tools/probe-usage-delta.mjs 与
 *    tools/probe-usage-jsonl.mjs；设计稿 docs/superpowers/specs/2026-10-10-usage-stats-design.md）
 *
 * **live（实时记账）**：每个 result 的 `modelUsage` 是全管线（含子代理、压缩）、
 * **跨回合累计**的快照 —— 落账要**差分**：
 *   - 全新会话第一回合：快照≈本回合量（基线=0，差分=全额）；
 *   - 同一会话后续回合：差分 = 当前 − 上一条快照；
 *   - resume：第一条快照**从 transcript 里存的旧总额续走**（差分照常成立，
 *     前提是基线还在 —— 也就是这个目录里的 baselines 文件）；
 *   - `/clear`：累计**重置**。差分出现**任何一项下降** ⇒ 当重置处理，取当前快照作全额；
 *   - 没有 `modelUsage` 的 result（`/clear` 自己那条，local_command=clear）跳过；
 *   - 全零快照（崩溃/启动失败那条）跳过、**且不动基线** —— 动了的话恢复后的
 *     下一条快照会被当成全额，前面记过的账就双算了。
 *
 * **fill（历史回填）**：见 usage-backfill.js。落账是 (天 × 项目 × 模型) 聚合行。
 *
 * **成本**：读取时按单价表估算（live/fill 同一公式），**不是账单**。探针实测
 * CLI 自己对未知模型也是按 5/25 记费、缓存读 0.1×输入价 —— 这里的兜底跟着它走，
 * 让两边的数量级对得上。
 *
 * ## 存储（root 目录，默认 ~/.ccoder/usage）
 *
 * - `usage-ledger.jsonl`   逐行账本（append-only）
 * - `usage-baselines.json` 每会话最后一条快照（差分的基线）
 * - `usage-backfill.json`  回填登记（每个文件读到哪个 uuid / mtime）
 *
 * 全部文件都可整目录删除 —— 会从头重建；代价只是"恢复会话后的第一回合"这类
 * 边角信息（README.txt 里也写着这句）。
 */
import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { backfill } from './usage-backfill.js';

/** 本机本地日的 'YYYY-MM-DD'。天按本地时区切 —— 界面上的"今日"就是它。 */
export function localDay(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * 项目路径的规范形：分隔符统一成 `/`、去掉尾斜杠。
 *
 * **不归一"本项目"就会一边有一边没有**（端到端实测抓到过）：live 行的 cwd 是
 * Kotlin 传来的 basePath（正斜杠），而会话文件里 CLI 存的是反斜杠形式 ——
 * 同一个项目在账本里会变成两个键。
 */
export function normalizeProj(p) {
  return String(p ?? '').replace(/\\/g, '/').replace(/\/+$/, '');
}

/**
 * 单价表（$/M tokens）。缓存读 = 0.1×输入价、缓存写 = 1.25×输入价（业界惯例，
 * 也与 CLI 的算法一致）。认不出的模型走 [FALLBACK]。
 */
const RATES = [
  { re: /fable/i, in: 10, out: 50 },
  { re: /opus/i, in: 5, out: 25 },
  { re: /sonnet/i, in: 2, out: 10 },
  { re: /haiku/i, in: 0.1, out: 0.5 },
];
const FALLBACK = { in: 5, out: 25 };

/** 一组 token 的估算成本（$）。[t] 形如 `{ i, o, r, c }`。 */
export function estimateCostUsd(model, t) {
  const rate = RATES.find((x) => x.re.test(model)) ?? FALLBACK;
  return (t.i / 1e6) * rate.in + (t.o / 1e6) * rate.out
    + (t.r / 1e6) * rate.in * 0.1 + (t.c / 1e6) * rate.in * 1.25;
}

const README_TEXT = `CCoder 使用量统计的数据目录（整个目录都可以删 —— 会从头重建）。
- usage-ledger.jsonl   账本，一行一笔。live = 每个回合的差分；fill = 历史会话文件回填（按 天×项目×模型 聚合）。
- usage-baselines.json 每个会话最后一条 modelUsage 快照 = 差分的基线（删了最多丢"恢复会话后的第一回合"）。
- usage-backfill.json  回填登记：每个会话文件读到哪个 uuid、什么 mtime（删了会重扫，不丢数据）。
口径（探针实测）：
- 历史会话文件里同一条响应平均重写 2.5 遍，按 message.id 去重后才对；
- 成本是按 API 单价折算的估算，不是账单；天按本机本地时区切。
`;

const empty = () => ({ i: 0, o: 0, r: 0, c: 0 });
const sum = (t) => t.i + t.o + t.r + t.c;
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
const round4 = (v) => Math.round(v * 1e4) / 1e4;

/** 原子写：半个文件比没有文件更糟（坏 JSON 会让"读不着"和"没有"长一样）。 */
function writeAtomic(path, text) {
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, text, 'utf8');
  renameSync(tmp, path);
}

function normalizeModelUsage(modelUsage) {
  const out = new Map();
  if (!modelUsage || typeof modelUsage !== 'object') return out;
  for (const [name, u] of Object.entries(modelUsage)) {
    if (!u || typeof u !== 'object') continue;
    out.set(name, {
      i: num(u.inputTokens),
      o: num(u.outputTokens),
      r: num(u.cacheReadInputTokens),
      c: num(u.cacheCreationInputTokens),
    });
  }
  return out;
}

/**
 * 差分 + 重置判定。
 *
 * - 当前有的模型：逐项相减；**任一项下降 ⇒ 整体当重置**（返回当前快照）；
 * - 当前才出现的模型（会话中途换模型）：全额算它；
 * - 基线里有、当前没有的模型：**什么都不做** —— 那是换模型/会话形态变了，
 *   不是"用量重置"，它已经记过的账不受影响。
 */
function diffWithReset(cur, base) {
  const out = new Map();
  for (const [model, t] of cur) {
    const b = base.get(model);
    if (!b) {
      out.set(model, t);
      continue;
    }
    if (t.i < b.i || t.o < b.o || t.r < b.r || t.c < b.c) return cur; // 重置：全额取当前
    out.set(model, { i: t.i - b.i, o: t.o - b.o, r: t.r - b.r, c: t.c - b.c });
  }
  return out;
}

export function createUsageStore({
  root = join(homedir(), '.ccoder', 'usage'),
  projectsRoot = join(homedir(), '.claude', 'projects'),
  now = () => Date.now(),
} = {}) {
  const ledgerPath = join(root, 'usage-ledger.jsonl');
  const baselinesPath = join(root, 'usage-baselines.json');
  const registryPath = join(root, 'usage-backfill.json');

  let dirReady = false;
  function ensureDir() {
    if (dirReady) return;
    mkdirSync(root, { recursive: true });
    const readme = join(root, 'README.txt');
    if (!existsSync(readme)) writeFileSync(readme, README_TEXT, 'utf8');
    dirReady = true;
  }

  // ---- 基线（会话 id → Map(model → tokens)）----

  let baselinesLoaded = false;
  const baselines = new Map();

  function loadBaselines() {
    if (baselinesLoaded) return;
    baselinesLoaded = true;
    try {
      const j = JSON.parse(readFileSync(baselinesPath, 'utf8'));
      for (const [sid, rec] of Object.entries(j.sessions ?? {})) {
        const m = new Map();
        for (const [model, t] of Object.entries(rec.m ?? {})) m.set(model, { ...empty(), ...t });
        baselines.set(sid, { m, t: num(rec.t) });
      }
    } catch {
      // 没文件 / 坏了：当空。基线丢了最多丢"恢复会话后的第一回合"，不冒双算的风险
    }
  }

  function persistBaselines() {
    ensureDir();
    // 有界：每个会话一条、很轻，但别让它无限长。旧于 180 天的剪掉（剪掉的那些
    // 会话再被恢复时，第一回合按"没有基线"处理）
    const cutoff = now() - 180 * 86400000;
    const entries = [...baselines.entries()].filter(([, rec]) => (rec.t ?? 0) >= cutoff);
    if (entries.length > 5000) {
      entries.sort((a, b) => (b[1].t ?? 0) - (a[1].t ?? 0));
      entries.length = 5000;
    }
    const sessions = {};
    for (const [sid, rec] of entries) {
      sessions[sid] = { t: rec.t ?? now(), m: Object.fromEntries(rec.m) };
    }
    writeAtomic(baselinesPath, JSON.stringify({ v: 1, sessions }));
  }

  // ---- 逐日状态（report / tick 的读侧）----
  //
  // days: Map<'YYYY-MM-DD', Map<proj, Map<model, tokens>>>
  // built=false 时惰性全量重读账本；回填完成会把它置回 false。

  let state = { built: false, days: new Map() };

  function ensureBuilt() {
    if (state.built) return;
    const days = new Map();
    if (existsSync(ledgerPath)) {
      for (const line of readFileSync(ledgerPath, 'utf8').split('\n')) {
        if (!line) continue;
        let j;
        try { j = JSON.parse(line); } catch { continue; }
        const day = typeof j.d === 'string' && j.d ? j.d : localDay(num(j.t));
        let byProj = days.get(day);
        if (!byProj) { byProj = new Map(); days.set(day, byProj); }
        const proj = j.proj ?? '';
        let byModel = byProj.get(proj);
        if (!byModel) { byModel = new Map(); byProj.set(proj, byModel); }
        for (const [model, t] of Object.entries(j.m ?? {})) {
          const cur = byModel.get(model) ?? empty();
          cur.i += num(t.i); cur.o += num(t.o); cur.r += num(t.r); cur.c += num(t.c);
          byModel.set(model, cur);
        }
      }
    }
    state = { built: true, days };
  }

  function appendLedger(rec) {
    ensureDir();
    appendFileSync(ledgerPath, `${JSON.stringify(rec)}\n`);
  }

  /** 记一笔增量进内存状态（state 没建好就先不记 —— 下次 ensureBuilt 会从文件里读到）。 */
  function patchState(day, proj, deltas) {
    if (!state.built) return;
    let byProj = state.days.get(day);
    if (!byProj) { byProj = new Map(); state.days.set(day, byProj); }
    let byModel = byProj.get(proj);
    if (!byModel) { byModel = new Map(); byProj.set(proj, byModel); }
    for (const [model, t] of deltas) {
      const cur = byModel.get(model) ?? empty();
      cur.i += t.i; cur.o += t.o; cur.r += t.r; cur.c += t.c;
      byModel.set(model, cur);
    }
  }

  // ---- 回填 ----

  let backfillPromise = null;

  /**
   * 跑一遍（增量）回填。单例：并发调用拿的是同一个 Promise。
   * 完成后把读侧状态置脏（下次 report/tick 重读账本）。
   */
  function ensureBackfill() {
    if (!backfillPromise) {
      ensureDir();
      backfillPromise = backfill({
        projectsRoot,
        registryPath,
        dayOf: localDay,
        // 落账前把项目键归一 —— 与 live 那条同一个出处（见 normalizeProj）
        append: (rec) => appendFileSync(ledgerPath, `${JSON.stringify({ ...rec, proj: normalizeProj(rec.proj) })}\n`),
      })
        .then((stats) => {
          backfillPromise = null;
          state.built = false;
          return stats;
        })
        .catch((err) => {
          backfillPromise = null; // 失败不粘住：下次调用再试
          throw err;
        });
    }
    return backfillPromise;
  }

  // ---- 记账（live）----

  /**
   * 处理一条 result 的 `modelUsage`。返回要推给界面的 tick，或 null（没得可记）。
   *
   * @param ctx { sid, cwd, resume } 会话上下文。[resume] 只在**恢复后的第一回合**
   *   为 true —— 没有基线（比如换了机器/清了目录）时，恢复会话的第一条快照里
   *   含着恢复前的旧账，双算的风险比丢一回合大，所以那种情况当基线用、不记账。
   */
  function recordResult(ctx, modelUsage) {
    const cur = normalizeModelUsage(modelUsage);
    if (!ctx?.sid || cur.size === 0) return null;
    let any = false;
    for (const t of cur.values()) if (sum(t) > 0) any = true;
    if (!any) return null; // 全零快照（崩溃那条）：不记、**不动基线**

    loadBaselines();
    const base = baselines.get(ctx.sid)?.m;
    let delta = null;
    if (!base) {
      if (!ctx.resume) delta = cur; // 全新会话：基线=0
      // resume 且没基线：delta 留 null（当基线用，见上）
    } else {
      delta = diffWithReset(cur, base);
    }

    baselines.set(ctx.sid, { m: cur, t: now() });
    persistBaselines();

    if (!delta) return null;
    let nonzero = false;
    for (const t of delta.values()) if (sum(t) > 0) nonzero = true;
    if (!nonzero) return null; // 同一快照发了两遍（重复 result）之类的零增量

    const m = {};
    for (const [model, t] of delta) m[model] = { i: t.i, o: t.o, r: t.r, c: t.c };
    const rec = { t: now(), sid: ctx.sid, proj: normalizeProj(ctx.cwd), src: 'live', m };
    appendLedger(rec);
    patchState(localDay(rec.t), rec.proj, delta);
    return tickFor(ctx.cwd ?? '');
  }

  // ---- 读（report / tick）----

  async function report({ scope = 'project', project = null } = {}) {
    try {
      await ensureBackfill();
    } catch (err) {
      // 回填失败不该让"已有的账"看不见 —— 拿现有账本继续
      console.error(`[ccoder] 使用量回填失败（用现有账本继续）：${err?.message ?? err}`);
    }
    ensureBuilt();
    const cutoff = localDay(now() - 400 * 86400000);
    const want = normalizeProj(project);
    const rows = [];
    for (const day of [...state.days.keys()].sort()) {
      if (day < cutoff) continue;
      const byProj = state.days.get(day);
      const merged = new Map();
      for (const [proj, byModel] of byProj) {
        if (scope === 'project' && normalizeProj(proj) !== want) continue;
        for (const [model, t] of byModel) {
          const cur = merged.get(model) ?? empty();
          cur.i += t.i; cur.o += t.o; cur.r += t.r; cur.c += t.c;
          merged.set(model, cur);
        }
      }
      const m = {};
      for (const [model, t] of merged) {
        if (sum(t) === 0) continue;
        m[model] = { i: t.i, o: t.o, r: t.r, c: t.c, cost: round4(estimateCostUsd(model, t)) };
      }
      if (Object.keys(m).length > 0) rows.push({ d: day, m });
    }
    return { scope: scope === 'project' ? 'project' : 'all', rows };
  }

  /** 某项目某天的合计（tokens + 估算成本）。[want] 必须是 [normalizeProj] 过的。 */
  function projectDayTotals(day, want) {
    let tokens = 0;
    let cost = 0;
    for (const [proj, byModel] of state.days.get(day) ?? []) {
      if (normalizeProj(proj) !== want) continue;
      for (const [model, t] of byModel) {
        tokens += sum(t);
        cost += estimateCostUsd(model, t);
      }
    }
    return { tokens, cost };
  }

  /**
   * 常驻条的推送数据：今天 + 近 14 天（这条项目）。
   *
   * state 还没建好（第一次 report 之前）给 null —— 在事件流里顺手把整本账
   * 读一遍会卡住聊天的主路；反正 Ready 那一步的 report 会先把它建起来。
   */
  function tickFor(project) {
    if (!state.built) return null;
    const want = normalizeProj(project);
    const today = localDay(now());
    const spark = [];
    for (let k = 13; k >= 0; k--) spark.push(projectDayTotals(localDay(now() - k * 86400000), want).tokens);
    const t = projectDayTotals(today, want);
    // project 回**规范形**（与账本里存的同一个形）。回原始形的话，Windows 上
    // cwd（Path.absolutePathString 的反斜杠）与面板手里的 basePath（正斜杠）
    // 对不上 —— 面板会把这**每一条 tick** 都丢掉（2026-10-10 用户实机按出）
    return { project: want, today: { t: t.tokens, cost: round4(t.cost) }, spark };
  }

  return { recordResult, report, ensureBackfill, tickFor };
}
