import { test } from 'node:test';
import assert from 'node:assert/strict';
import { appendFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { backfill, listTranscriptFiles, loadRegistry } from '../usage-backfill.js';
import { localDay } from '../usage.js';

// ------------------------------------------------------------------ 夹具
//
// 会话文件的形状照 tools/probe-usage-jsonl.mjs 的实测：
// `<项目>/<会话id>.jsonl` 与 `<项目>/<会话id>/subagents/agent-*.jsonl`，
// assistant 条目带 timestamp/cwd/uuid/message.{id,model,usage}。
// **同一条响应会重写多遍**（同 mid、不同 uuid）—— 每个夹具都放一对。

function tmp() {
  const base = mkdtempSync(join(tmpdir(), 'ccoder-usage-bf-'));
  return {
    projectsRoot: join(base, 'projects'),
    registry: join(base, 'registry.json'),
    cleanup: () => rmSync(base, { recursive: true, force: true }),
  };
}

const assistant = (uuid, mid, ts, model, [i, o, r, c], extra = {}) => ({
  type: 'assistant',
  uuid,
  timestamp: ts,
  cwd: 'C:/proj',
  message: {
    id: mid,
    model,
    usage: { input_tokens: i, output_tokens: o, cache_read_input_tokens: r, cache_creation_input_tokens: c },
  },
  ...extra,
});

function writeLines(path, entries) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, entries.map((e) => JSON.stringify(e)).join('\n') + '\n');
}

function appendRaw(path, text) {
  appendFileSync(path, text);
}

/** 把 append 出来的行收起来，方便断言。 */
function sink() {
  const lines = [];
  return { lines, append: (l) => lines.push(l) };
}

const amountOf = (lines, day, proj, model) => {
  let total = 0;
  for (const l of lines) {
    if (l.d !== day || l.proj !== proj) continue;
    const t = l.m?.[model];
    if (t) total += t.i + t.o + t.r + t.c;
  }
  return total;
};

const DAY = '2026-10-09T02:00:00Z';
const dayKey = localDay(Date.parse(DAY));

// ------------------------------------------------------------------ 用例

test('回填：按 message.id 去重、按 天×项目×模型 聚合、子目录里的也收', async () => {
  const t = tmp();
  try {
    const main = join(t.projectsRoot, 'C--proj', 'sid-1.jsonl');
    writeLines(main, [
      assistant('u1', 'm-a', DAY, 'mX', [100, 10, 0, 0]),
      assistant('u2', 'm-a', DAY, 'mX', [100, 10, 0, 0]), // 同一条响应重写的第二遍
      { type: 'user', uuid: 'u3', timestamp: DAY, cwd: 'C:/proj', message: { content: 'hi' } },
      assistant('u4', 'm-b', DAY, 'mX', [50, 5, 0, 0]),
    ]);
    appendRaw(main, '不是 JSON 的半行\n');
    const sub = join(t.projectsRoot, 'C--proj', 'sid-1', 'subagents', 'agent-1.jsonl');
    writeLines(sub, [assistant('u5', 'm-c', DAY, 'mY', [7, 0, 0, 0], { isSidechain: true })]);

    const s = sink();
    const stats = await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s.append, dayOf: localDay });
    assert.equal(stats.files, 2, '主文件 + 子代理文件');
    assert.equal(amountOf(s.lines, dayKey, 'C:/proj', 'mX'), 165, 'm-a 只算一遍：100+10+50+5');
    assert.equal(amountOf(s.lines, dayKey, 'C:/proj', 'mY'), 7);
    assert.equal(s.lines.length, 2, '两个文件各落一行（同一天同项目可以有多行，读侧求和）');
  } finally {
    t.cleanup();
  }
});

test('空用量、坏时间戳的条目不进账', async () => {
  const t = tmp();
  try {
    const main = join(t.projectsRoot, 'C--proj', 'sid-1.jsonl');
    writeLines(main, [
      assistant('u1', 'm-a', DAY, 'mX', [0, 0, 0, 0]),
      assistant('u2', 'm-b', '不是时间', 'mX', [5, 0, 0, 0]),
      assistant('u3', 'm-c', DAY, 'mX', [9, 0, 0, 0]),
    ]);
    const s = sink();
    await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s.append, dayOf: localDay });
    assert.equal(s.lines.length, 1);
    assert.equal(amountOf(s.lines, dayKey, 'C:/proj', 'mX'), 9);
  } finally {
    t.cleanup();
  }
});

test('cwd 缺失时回落到项目目录名（探针说实测从不缺，留个退路）', async () => {
  const t = tmp();
  try {
    const main = join(t.projectsRoot, 'C--proj', 'sid-1.jsonl');
    const noCwd = assistant('u1', 'm-a', DAY, 'mX', [5, 0, 0, 0]);
    delete noCwd.cwd;
    writeLines(main, [noCwd]);
    const s = sink();
    await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s.append, dayOf: localDay });
    assert.equal(s.lines[0].proj, 'C--proj');
  } finally {
    t.cleanup();
  }
});

test('增量：没动过的文件整跳；追加后只读尾巴', async () => {
  const t = tmp();
  try {
    const main = join(t.projectsRoot, 'C--proj', 'sid-1.jsonl');
    writeLines(main, [assistant('u1', 'm-a', DAY, 'mX', [100, 0, 0, 0])]);

    const s1 = sink();
    await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s1.append, dayOf: localDay });
    assert.equal(s1.lines.length, 1);

    // 第二遍：什么都没动
    const s2 = sink();
    const stats2 = await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s2.append, dayOf: localDay });
    assert.equal(stats2.scanned, 0);
    assert.equal(stats2.skipped, 1);
    assert.equal(s2.lines.length, 0);

    // 追加一条：只应该落新那条
    appendRaw(main, JSON.stringify(assistant('u9', 'm-z', DAY, 'mX', [42, 0, 0, 0])) + '\n');
    const s3 = sink();
    const stats3 = await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s3.append, dayOf: localDay });
    assert.equal(stats3.scanned, 1);
    assert.equal(amountOf(s3.lines, dayKey, 'C:/proj', 'mX'), 42, '只算尾巴上那条');
  } finally {
    t.cleanup();
  }
});

test('锚行丢了（文件被重写）：整读一遍，但登记里的 mid 兜底去重', async () => {
  const t = tmp();
  try {
    const main = join(t.projectsRoot, 'C--proj', 'sid-1.jsonl');
    writeLines(main, [
      assistant('u1', 'm-a', DAY, 'mX', [100, 0, 0, 0]),
      assistant('u2', 'm-b', DAY, 'mX', [50, 0, 0, 0]),
    ]);
    const s1 = sink();
    await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s1.append, dayOf: localDay });

    // 重写：老两条换了 uuid（锚行 u2 不在了），另加一条新的
    writeLines(main, [
      assistant('v1', 'm-a', DAY, 'mX', [100, 0, 0, 0]),
      assistant('v2', 'm-b', DAY, 'mX', [50, 0, 0, 0]),
      assistant('v3', 'm-new', DAY, 'mX', [7, 0, 0, 0]),
    ]);
    const s2 = sink();
    await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s2.append, dayOf: localDay });
    assert.equal(amountOf(s2.lines, dayKey, 'C:/proj', 'mX'), 7, '老 mid 不许重记，只记新的那条');
  } finally {
    t.cleanup();
  }
});

test('listTranscriptFiles：顶层是会话、子目录里算它的父会话', () => {
  const t = tmp();
  try {
    writeLines(join(t.projectsRoot, 'P1', 'sid-1.jsonl'), []);
    writeLines(join(t.projectsRoot, 'P1', 'sid-1', 'subagents', 'agent-1.jsonl'), []);
    const files = listTranscriptFiles(t.projectsRoot);
    const byRel = Object.fromEntries(files.map((f) => [f.rel.replace(/\\/g, '/'), f.sid]));
    assert.equal(byRel['P1/sid-1.jsonl'], 'sid-1');
    assert.equal(byRel['P1/sid-1/subagents/agent-1.jsonl'], 'sid-1');
  } finally {
    t.cleanup();
  }
});

test('登记文件坏掉时当空，重扫一遍而不是丢数据', async () => {
  const t = tmp();
  try {
    writeLines(join(t.projectsRoot, 'C--proj', 'sid-1.jsonl'), [assistant('u1', 'm-a', DAY, 'mX', [3, 0, 0, 0])]);
    writeFileSync(t.registry, '{ 这不是 JSON');
    const s = sink();
    const stats = await backfill({ projectsRoot: t.projectsRoot, registryPath: t.registry, append: s.append, dayOf: localDay });
    assert.equal(stats.scanned, 1);
    const reg = loadRegistry(t.registry);
    assert.equal(Object.keys(reg.files).length, 1, '坏登记会被重建成好的');
  } finally {
    t.cleanup();
  }
});
