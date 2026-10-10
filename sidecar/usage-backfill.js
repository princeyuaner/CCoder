/**
 * 历史回填：把 `~/.claude/projects` 下的会话文件煮熟成账本里的 fill 行。
 *
 * ## 探针实测（tools/probe-usage-jsonl.mjs；本机 480 个文件 / 730 MB / 全扫 5.5s）
 *
 * - assistant 条目 100% 带 `message.usage` 与 `timestamp`/`cwd`/`uuid`；
 * - **同一条响应平均重写 2.5 遍**：同一 `message.id`、同样的数值、不同的 uuid，
 *   相隔几百毫秒 —— 不去重会多算 155%，按 `message.id` 去重后才对得上账；
 * - 子代理的转写在 `<项目目录>/<会话id>/subagents/agent-*.jsonl`，同样带 usage，
 *   约占总量 6.8%（`isSidechain: true`）；
 * - `requestId` 字段在本机一个都没有（键里留个位，别指望）。
 *
 * ## 增量
 *
 * 登记文件（usage-backfill.json）按**相对路径**记 `{ u: 读到的最后一个 uuid,
 * m: 文件 mtime, mids: 最近的 message.id（有界） }`：
 *
 * - mtime 没变 → 整个文件跳过（多数文件都属于这种）；
 * - 变了 → 从 `u` 那一行往后读（resume 只在文件尾部追加，跳过的都是熟内容）；
 * - 锚行找不到（文件被重写过）→ 整读一遍，用 `mids` 兜底去重。有界的代价：
 *   被重写的大文件里，超过上限的旧 mid 没在兜底集里 —— 那部分会重记一遍，
 *   属于"重写 + 大文件 + 清理目录"三重巧合，接受。
 *
 * ## 去重是**扫描侧**做的（同一遍里按 mid 只认第一条），落账直接是聚合行：
 * 一行 = (天 × 项目 × 模型)，增量再跑同一天时是**追加新行**而不是改写 ——
 * 读侧（usage.js）是按行求和，天然支持这种"分批落账"。
 */
import { createReadStream, existsSync, readdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { join, sep } from 'node:path';

/** 每个文件保多少条最近的 message.id 当"锚丢了"时的兜底去重集。 */
const RECENT_MIDS = 2000;

export function loadRegistry(path) {
  try {
    const j = JSON.parse(readFileSync(path, 'utf8'));
    if (j && j.v === 1 && j.files && typeof j.files === 'object') return j;
  } catch {
    // 没文件 / 坏了：当空 —— 最坏也就是重扫一遍，不丢数据
  }
  return { v: 1, files: {}, lastRun: 0 };
}

export function saveRegistry(path, reg) {
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, JSON.stringify(reg), 'utf8');
  renameSync(tmp, path);
}

/**
 * 列出所有会话文件（递归三级，照着实测的目录形状）：
 * `<项目>/<会话id>.jsonl` 与 `<项目>/<会话id>/subagents/agent-*.jsonl`。
 */
export function listTranscriptFiles(root) {
  const out = [];
  if (!existsSync(root)) return out;
  for (const proj of readdirSync(root)) {
    const projDir = join(root, proj);
    let isDir = false;
    try { isDir = statSync(projDir).isDirectory(); } catch { /* 跳过 */ }
    if (!isDir) continue;
    const walk = (dir, depth) => {
      for (const name of readdirSync(dir)) {
        const p = join(dir, name);
        let st;
        try { st = statSync(p); } catch { continue; }
        if (st.isDirectory()) {
          if (depth < 3) walk(p, depth + 1);
          continue;
        }
        if (!name.endsWith('.jsonl')) continue;
        const rel = p.slice(root.length + 1);
        const parts = rel.split(sep);
        // 顶层 = `<会话id>.jsonl`；子目录里的 = 会话目录名就是它的父会话 id
        const sid = parts.length === 2 ? parts[1].replace(/\.jsonl$/, '') : (parts[1] ?? '');
        out.push({ rel, path: p, projKey: proj, sid });
      }
    };
    walk(projDir, 0);
  }
  return out;
}

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);

/**
 * 流式扫一个文件。返回 `{ found, lastUuid, count, mids }`。
 *
 * [resumeUuid]：从这一行**之后**开始收（锚行本身是上次读过的）。
 * [seedMids]：预置的去重集（锚丢了整读时用）。
 * [onEntry]：每条有效 assistant 条目回调一次（已去重、已过滤）。
 */
function scanFile({ path, resumeUuid, seedMids, onEntry }) {
  return new Promise((resolve, reject) => {
    const seen = new Set(seedMids ?? []);
    const mids = [];
    let seek = Boolean(resumeUuid);
    let found = !seek;
    let lastUuid = null;
    let count = 0;

    const rl = createInterface({
      input: createReadStream(path, { encoding: 'utf8' }),
      crlfDelay: Infinity,
    });
    rl.on('line', (line) => {
      if (seek) {
        // 快速筛 + 精确认：只有当行里出现锚串时才值得 parse
        if (resumeUuid && line.includes(resumeUuid)) {
          try {
            if (JSON.parse(line).uuid === resumeUuid) {
              seek = false;
              found = true;
            }
          } catch { /* 半行，跳过 */ }
        }
        return;
      }
      if (!line.trim()) return;
      let j;
      try { j = JSON.parse(line); } catch { return; }
      if (j.uuid) lastUuid = j.uuid;
      if (j.type !== 'assistant') return;
      const mid = j.message?.id;
      const u = j.message?.usage;
      if (!mid || !u) return;
      if (seen.has(mid)) return; // 同一条响应重写 N 遍：只算第一遍
      seen.add(mid);
      mids.push(mid);
      if (mids.length > RECENT_MIDS) mids.shift();
      count += 1;
      onEntry(j);
    });
    rl.on('error', reject);
    rl.on('close', () => resolve({ found, lastUuid, count, mids }));
  });
}

/**
 * 跑一遍增量回填。
 *
 * @param projectsRoot ~/.claude/projects
 * @param registryPath 登记文件位置
 * @param append 落一行账：`{ d, proj, src: 'fill', m: { model: {i,o,r,c} } }`
 * @param dayOf epoch ms → 本地日 'YYYY-MM-DD'（由 usage.js 注入，天只有一个出处）
 * @returns {{ files, scanned, skipped, appended, emptyFiles }}
 */
export async function backfill({ projectsRoot, registryPath, append, dayOf, now = () => Date.now() }) {
  const reg = loadRegistry(registryPath);
  const files = listTranscriptFiles(projectsRoot);
  let scanned = 0;
  let skipped = 0;
  let appended = 0;

  for (const f of files) {
    let mtime;
    try { mtime = statSync(f.path).mtimeMs; } catch { continue; }
    const rec = reg.files[f.rel];
    if (rec && rec.m === mtime) { skipped += 1; continue; }
    scanned += 1;

    // 一遍文件一个聚合表：key = 天 × 项目 → { d, proj, m: { model: tokens } }
    const byKey = new Map();
    const fallbackProj = f.projKey; // cwd 缺失时的兜底（实测从不缺，留个退路）

    const collect = (j) => {
      const t = Date.parse(j.timestamp);
      if (!Number.isFinite(t)) return;
      const u = j.message.usage;
      const tok = {
        i: num(u.input_tokens),
        o: num(u.output_tokens),
        r: num(u.cache_read_input_tokens),
        c: num(u.cache_creation_input_tokens),
      };
      if (tok.i + tok.o + tok.r + tok.c === 0) return;
      const day = dayOf(t);
      const proj = typeof j.cwd === 'string' && j.cwd ? j.cwd : fallbackProj;
      const key = `${day}\u0000${proj}`;
      let rec2 = byKey.get(key);
      if (!rec2) { rec2 = { d: day, proj, m: {} }; byKey.set(key, rec2); }
      const model = j.message?.model ?? '（未知）';
      const acc = rec2.m[model] ?? { i: 0, o: 0, r: 0, c: 0 };
      acc.i += tok.i; acc.o += tok.o; acc.r += tok.r; acc.c += tok.c;
      rec2.m[model] = acc;
    };

    let res = await scanFile({ path: f.path, resumeUuid: rec?.u ?? null, seedMids: rec?.mids, onEntry: collect });
    if (rec?.u && !res.found) {
      // 锚行不在了（文件被重写）：整读一遍，用登记里的 mid 兜底去重
      byKey.clear();
      res = await scanFile({ path: f.path, resumeUuid: null, seedMids: rec?.mids, onEntry: collect });
    }

    for (const rec2 of byKey.values()) {
      append({ d: rec2.d, proj: rec2.proj, src: 'fill', m: rec2.m });
      appended += 1;
    }
    reg.files[f.rel] = { u: res.lastUuid ?? rec?.u ?? null, m: mtime, mids: res.mids };
  }

  // 已消失的文件从登记里清掉（不常发生，但别让它无限长）
  const present = new Set(files.map((x) => x.rel));
  for (const key of Object.keys(reg.files)) {
    if (!present.has(key)) delete reg.files[key];
  }
  reg.lastRun = now();
  saveRegistry(registryPath, reg);

  return { files: files.length, scanned, skipped, appended };
}
