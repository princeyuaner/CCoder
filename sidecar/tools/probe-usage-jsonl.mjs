#!/usr/bin/env node
/**
 * 探针：**历史会话文件里的 token 用量长什么样** —— 给"回填"钉口径。
 *
 * 为什么要问：使用量统计的日/周/月，历史那一段要从
 * `~/.claude/projects/**&#47;*.jsonl` 回填。而回填的正确性全押在几个"只能实测"
 * 的事情上（ccusage 那类工具的坑也都在这几处）：
 *
 *   1. assistant 条目的 `message.usage` 里有哪些字段、模型名怎么记；
 *   2. **同一条 API 响应会不会落多行** —— 会的话按什么键去重
 *      （本机实测：会，而且很多；键 = message.id）；
 *   3. 子代理的用量在不在文件里（isSidechain / `subagents/` 子目录）；
 *   4. 时间戳与时区怎么转"本地日"，`cwd` 在不在条目里（按项目过滤）；
 *   5. 顺带把"近 30 天按天合计"跑出来，当量级与现实性的 sanity check；
 *      总字节数与耗时也打出来 —— 回填的扫描成本要心里有数。
 *
 * 只读，不写任何东西，不花钱。跑法：
 *   node sidecar/tools/probe-usage-jsonl.mjs
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const ROOT = join(homedir(), '.claude', 'projects');
const T0 = Date.now();

// ------------------------------------------------------------------ 收集
//
// 递归到三级：`<proj>/<sid>.jsonl` 是主文件；子代理的转写藏在
// `<proj>/<sid>/subagents/*.jsonl` 那一类子目录里（有没有是另一回事，得先扫到）。

function collectFiles() {
  const mains = [];
  const subs = [];
  for (const name of readdirSync(ROOT)) {
    const projDir = join(ROOT, name);
    if (!statSync(projDir).isDirectory()) continue;
    const recurse = (dir, depth) => {
      for (const f of readdirSync(dir)) {
        const p = join(dir, f);
        let st;
        try { st = statSync(p); } catch { continue; }
        if (st.isDirectory()) { if (depth < 3) recurse(p, depth + 1); continue; }
        if (!f.endsWith('.jsonl')) continue;
        if (dir === projDir) mains.push({ proj: name, file: p });
        else subs.push({ proj: name, file: p });
      }
    };
    recurse(projDir, 0);
  }
  return { mains, subs };
}

function readEntries(file) {
  const raw = readFileSync(file, 'utf8');
  const out = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    try {
      out.push(JSON.parse(line));
    } catch {
      // 半行（写一半的最后一笔）是正常现象，跳过即可 —— 回填也该这样
    }
  }
  return out;
}

const { mains, subs } = collectFiles();
let bytes = 0;
for (const f of [...mains, ...subs]) bytes += statSync(f.file).size;
console.log(`根目录 : ${ROOT}`);
console.log(`会话文件: 主 ${mains.length} 个，子目录（hasSidechain 那一类）里 ${subs.length} 个，共 ${(bytes / 1e6).toFixed(0)} MB`);
if (subs.length > 0) {
  for (const s of subs.slice(0, 5)) console.log(`  子目录样例: ${s.file.slice(ROOT.length + 1)}`);
}
console.log('---');

// ------------------------------------------------------------------ 形状

const recent = mains
  .map((m) => ({ ...m, mtime: statSync(m.file).mtimeMs }))
  .sort((a, b) => b.mtime - a.mtime)
  .slice(0, 3);

const shape = { count: 0, withUsage: 0, fields: {}, models: new Set(), hasTs: 0, hasCwd: 0, hasUuid: 0, hasReqId: 0, hasSidechain: 0, hasMsgId: 0 };

for (const { file } of recent) {
  for (const j of readEntries(file)) {
    if (j.type !== 'assistant') continue;
    shape.count += 1;
    const u = j.message?.usage;
    if (u) {
      shape.withUsage += 1;
      for (const k of Object.keys(u)) shape.fields[k] = (shape.fields[k] ?? 0) + 1;
    }
    if (j.message?.model) shape.models.add(j.message.model);
    if (j.timestamp) shape.hasTs += 1;
    if (j.cwd) shape.hasCwd += 1;
    if (j.uuid) shape.hasUuid += 1;
    if (j.requestId) shape.hasReqId += 1;
    if (j.isSidechain === true) shape.hasSidechain += 1;
    if (j.message?.id) shape.hasMsgId += 1;
  }
}

console.log(`【形状】最近 3 个会话文件里的 assistant 条目（共 ${shape.count} 条）：`);
console.log(`  带 message.usage : ${shape.withUsage}`);
console.log(`  usage 字段出现次数 : ${JSON.stringify(shape.fields)}`);
console.log(`  模型名集合 : ${[...shape.models].join(', ')}`);
console.log(`  timestamp ${shape.hasTs} · cwd ${shape.hasCwd} · uuid ${shape.hasUuid}` +
  ` · requestId ${shape.hasReqId} · message.id ${shape.hasMsgId} · isSidechain ${shape.hasSidechain}`);

outer: for (const { file } of recent) {
  for (const j of readEntries(file)) {
    if (j.type === 'assistant' && j.message?.usage) {
      console.log('  样例一条：');
      console.log(`    timestamp=${j.timestamp} cwd=${j.cwd}`);
      console.log(`    message.id=${j.message.id} requestId=${j.requestId ?? '（无）'} uuid=${j.uuid}`);
      console.log(`    model=${j.message.model}`);
      console.log(`    usage=${JSON.stringify(j.message.usage)}`);
      break outer;
    }
  }
}

// ------------------------------------------------------------------ 去重面
//
// 三件事：重复有多重、重复组里 usage 数值是否**一致**（不一致的话"留第一条"
// 就不对，得换键或换取值策略）、跨天重复有没有。

const dup = { entries: 0, groups: 0, dupGroups: 0, maxDup: 0, differ: 0, differExample: null, crossDay: 0 };
const dupById = new Map();
const localDay = (iso) => {
  const d = new Date(iso);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

for (const { file } of recent) {
  for (const j of readEntries(file)) {
    if (j.type !== 'assistant' || !j.message?.usage) continue;
    dup.entries += 1;
    const key = `${j.message.id ?? j.uuid}|${j.requestId ?? ''}`;
    const arr = dupById.get(key) ?? [];
    arr.push({ ts: j.timestamp, uuid: j.uuid, usage: j.message.usage });
    dupById.set(key, arr);
  }
}

for (const [key, arr] of dupById) {
  dup.groups += 1;
  if (arr.length <= 1) continue;
  dup.dupGroups += 1;
  dup.maxDup = Math.max(dup.maxDup, arr.length);
  const sig = (u) => `${u.input_tokens}|${u.output_tokens}|${u.cache_read_input_tokens}|${u.cache_creation_input_tokens}`;
  const first = sig(arr[0].usage);
  if (arr.some((e) => sig(e.usage) !== first)) {
    dup.differ += 1;
    if (!dup.differExample) dup.differExample = { key, arr };
  }
  const days = new Set(arr.map((e) => localDay(e.ts)));
  if (days.size > 1) dup.crossDay += 1;
}

console.log('\n【去重】最近 3 个会话文件：');
console.log(`  assistant 带 usage 的条目 ${dup.entries} 条 → 按 message.id 归组 ${dup.groups} 组`);
console.log(`  重复组 ${dup.dupGroups} 组（占 ${(100 * dup.dupGroups / Math.max(dup.groups, 1)).toFixed(0)}%），最大重复 ${dup.maxDup} 次`);
console.log(`  组内 usage 数值不一致的组：${dup.differ} 个${dup.differ > 0 ? '（**要查**，见下）' : '（同一条响应重写的值一致，"留第一条"成立）'}`);
if (dup.differExample) {
  for (const e of dup.differExample.arr) {
    console.log(`    uuid=${e.uuid} ts=${e.ts} in=${e.usage.input_tokens} out=${e.usage.output_tokens}`);
  }
}
console.log(`  重复跨越本地日边界的组：${dup.crossDay} 个（跨天重复会让"哪天算多少"有歧义）`);

// ------------------------------------------------------------------ 子代理面

let sidechainInMains = 0;
let sidechainWithUsage = 0;
for (const { file } of recent) {
  for (const j of readEntries(file)) {
    if (j.isSidechain === true) {
      sidechainInMains += 1;
      if (j.message?.usage) sidechainWithUsage += 1;
    }
  }
}
console.log('\n【子代理】');
console.log(`  最近 3 个主文件里 isSidechain=true 的条目 ${sidechainInMains}（其中带 usage ${sidechainWithUsage}）`);
console.log(`  子目录里的文件总数 ${subs.length} 个`);
let subUsage = 0;
for (const s of subs.slice(0, 60)) {
  subUsage += readEntries(s.file).filter((j) => j.type === 'assistant' && j.message?.usage).length;
}
console.log(`  抽样 ${Math.min(subs.length, 60)} 个子目录文件里带 usage 的 assistant：${subUsage} 条`);

// ------------------------------------------------------------------ 逐日合计（近 30 天）
//
// 两种口径对打：不去重 vs 按 message.id 去重。差额就是"去重不做会多算多少"。
// 拆分母（输出/缓存读写/模型明细）也按**去重后**算。

const WINDOW_DAYS = 30;
const since = new Date();
since.setDate(since.getDate() - WINDOW_DAYS);

const byDayRaw = new Map();
const byDayDedup = new Map();
const seen = new Set();
const totals = { raw: 0, dedup: 0, out: 0, cr: 0, cc: 0, inp: 0, sidechainDedup: 0 };
const modelTotals = new Map();

for (const f of [...mains, ...subs]) {
  for (const j of readEntries(f.file)) {
    if (j.type !== 'assistant' || !j.message?.usage || !j.timestamp) continue;
    const t = Date.parse(j.timestamp);
    if (Number.isNaN(t) || t < since.getTime()) continue;
    const u = j.message.usage;
    const inp = Number(u.input_tokens) || 0;
    const out = Number(u.output_tokens) || 0;
    const cr = Number(u.cache_read_input_tokens) || 0;
    const cc = Number(u.cache_creation_input_tokens) || 0;
    const sum = inp + out + cr + cc;
    const day = localDay(j.timestamp);
    byDayRaw.set(day, (byDayRaw.get(day) ?? 0) + sum);
    totals.raw += sum;

    const key = `${j.message.id ?? j.uuid}|${j.requestId ?? ''}`;
    if (seen.has(key)) continue;
    seen.add(key);
    byDayDedup.set(day, (byDayDedup.get(day) ?? 0) + sum);
    totals.dedup += sum;
    totals.inp += inp;
    totals.out += out;
    totals.cr += cr;
    totals.cc += cc;
    const model = j.message.model ?? '（未知）';
    modelTotals.set(model, (modelTotals.get(model) ?? 0) + sum);
    if (j.isSidechain === true) totals.sidechainDedup += sum;
  }
}

console.log(`\n【近 ${WINDOW_DAYS} 天】全部项目、所有会话文件：`);
for (const d of [...byDayDedup.keys()].sort()) {
  console.log(`  ${d}  去重 ${String(byDayDedup.get(d)).padStart(13)}   不去重 ${String(byDayRaw.get(d) ?? 0).padStart(13)}`);
}
console.log(`  合计：去重 ${totals.dedup}（${(totals.dedup / 1e6).toFixed(1)}M），不去重 ${totals.raw}（${(totals.raw / 1e6).toFixed(1)}M）` +
  `，不去重会多算 ${(100 * (totals.raw / Math.max(totals.dedup, 1) - 1)).toFixed(0)}%`);
console.log(`  拆分母（去重口径）：输入 ${(totals.inp / 1e6).toFixed(1)}M · 输出 ${(totals.out / 1e6).toFixed(2)}M` +
  ` · 缓存读 ${(totals.cr / 1e6).toFixed(1)}M · 缓存写 ${(totals.cc / 1e6).toFixed(2)}M`);
console.log(`  子代理（isSidechain=true）: 去重后 ${(totals.sidechainDedup / 1e6).toFixed(2)}M，占 ${(100 * totals.sidechainDedup / Math.max(totals.dedup, 1)).toFixed(1)}%`);
console.log('  近 30 天用过的模型（去重口径，前 8）：');
[...modelTotals.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8)
  .forEach(([m, v]) => console.log(`    ${m.padEnd(34)} ${(v / 1e6).toFixed(1)}M`));

console.log(`\n扫描耗时 ${Date.now() - T0} ms（${((bytes / 1e6) / ((Date.now() - T0) / 1000)).toFixed(0)} MB/s 量级）`);
