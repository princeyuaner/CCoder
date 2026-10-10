import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createUsageStore, estimateCostUsd, localDay } from '../usage.js';

// ------------------------------------------------------------------ 夹具
//
// 一律用临时目录当 root / projectsRoot —— 这些用例**绝不能**碰真实的
// ~/.ccoder/usage 与 ~/.claude/projects。

function tmpRoots() {
  const base = mkdtempSync(join(tmpdir(), 'ccoder-usage-'));
  return {
    root: join(base, 'usage'),
    projectsRoot: join(base, 'projects'),
    cleanup: () => rmSync(base, { recursive: true, force: true }),
  };
}

/** 本地时区无关的"某天正午"——localDay 按本地时区切，测试不能钉 UTC 字符串。 */
const DAY = 86400000;
const NOW = new Date(2026, 9, 10, 12, 0, 0).getTime();

/** 造一份 modelUsage：[in, out, cacheRead, cacheCreation]。 */
const mu = (spec) =>
  Object.fromEntries(
    Object.entries(spec).map(([m, [i, o, r, c]]) => [
      m,
      { inputTokens: i, outputTokens: o, cacheReadInputTokens: r, cacheCreationInputTokens: c },
    ]),
  );

const ledgerOf = (root) => {
  try {
    return readFileSync(join(root, 'usage-ledger.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
  } catch {
    return [];
  }
};

/** 先看一眼（把读侧状态建起来），tick 才有数据 —— 生产里这条是 Ready 时的 report。 */
async function prime(store, project = 'C:/proj') {
  await store.report({ scope: 'project', project });
}

// ------------------------------------------------------------------ 差分

test('全新会话第一回合：快照全额落账，tick 报当天合计', async () => {
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store);
    const tick = store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [100, 10, 200, 5] }));
    assert.ok(tick, '第一条必须有 tick');
    assert.equal(tick.project, 'C:/proj');
    assert.equal(tick.today.t, 315);
    assert.equal(tick.spark.length, 14);
    assert.equal(tick.spark[13], 315, 'spark 最后一格是今天');
    const lines = ledgerOf(t.root);
    assert.equal(lines.length, 1);
    assert.equal(lines[0].src, 'live');
    assert.deepEqual(lines[0].m.m1, { i: 100, o: 10, r: 200, c: 5 });
  } finally {
    t.cleanup();
  }
});

test('同一会话第二回合：只记差分', async () => {
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store);
    store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [100, 10, 200, 5] }));
    const tick = store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [140, 12, 400, 5] }));
    assert.deepEqual(ledgerOf(t.root)[1].m.m1, { i: 40, o: 2, r: 200, c: 0 });
    assert.equal(tick.today.t, 315 + 242);
  } finally {
    t.cleanup();
  }
});

test('/clear 重置：任一项下降 ⇒ 全额取当前快照', async () => {
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store);
    store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [20000, 300, 90000, 0] }));
    store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [30010, 2, 0, 0] }));
    const lines = ledgerOf(t.root);
    assert.equal(lines.length, 2);
    assert.deepEqual(lines[1].m.m1, { i: 30010, o: 2, r: 0, c: 0 }, '重置后应为全额，而不是负数或零');
  } finally {
    t.cleanup();
  }
});

test('会话中途换模型：新模型全额、旧模型缺了也不当重置', async () => {
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store);
    store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ a: [100, 0, 0, 0] }));
    store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ a: [110, 0, 0, 0], b: [50, 0, 0, 0] }));
    let lines = ledgerOf(t.root);
    assert.deepEqual(lines[1].m.a, { i: 10, o: 0, r: 0, c: 0 });
    assert.deepEqual(lines[1].m.b, { i: 50, o: 0, r: 0, c: 0 }, '新模型应全额');
    // 第三回合只剩 b：a 消失不是重置，只记 b 的增量
    store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ b: [60, 0, 0, 0] }));
    lines = ledgerOf(t.root);
    assert.deepEqual(lines[2].m, { b: { i: 10, o: 0, r: 0, c: 0 } });
  } finally {
    t.cleanup();
  }
});

test('没有 modelUsage 的 result（/clear 自己那条）：不记、不动基线', async () => {
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store);
    assert.equal(store.recordResult({ sid: 's1', cwd: 'C:/proj' }, undefined), null);
    assert.equal(ledgerOf(t.root).length, 0);
  } finally {
    t.cleanup();
  }
});

test('全零快照（崩溃那条）：跳过且不污染基线 —— 恢复后不能双算', async () => {
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store);
    store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [100, 0, 0, 0] }));
    // 崩溃后的零值快照
    assert.equal(store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [0, 0, 0, 0] })), null);
    store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [150, 0, 0, 0] }));
    const lines = ledgerOf(t.root);
    assert.equal(lines.length, 2);
    assert.deepEqual(lines[1].m.m1, { i: 50, o: 0, r: 0, c: 0 }, '增量应对着 100 的基线，而不是全额 150');
  } finally {
    t.cleanup();
  }
});

test('resume：没有基线时当基线（丢一回合、不双算）；有基线时正常差分', async () => {
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store);
    // 恢复会话、但目录刚清过：第一条快照里含着旧账，不能当全额记
    assert.equal(store.recordResult({ sid: 'r1', cwd: 'C:/proj', resume: true }, mu({ m1: [9000, 100, 0, 0] })), null);
    assert.equal(ledgerOf(t.root).length, 0);
    // 之后的回合就正常差分了
    store.recordResult({ sid: 'r1', cwd: 'C:/proj', resume: false }, mu({ m1: [9100, 110, 0, 0] }));
    assert.deepEqual(ledgerOf(t.root)[0].m.m1, { i: 100, o: 10, r: 0, c: 0 });
  } finally {
    t.cleanup();
  }
});

test('基线持久化：换一个 store 实例（进程重启）也能接着差分', async () => {
  const t = tmpRoots();
  try {
    const store1 = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store1);
    store1.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [100, 0, 0, 0] }));
    // 模拟进程重启：新实例读同一份 baselines
    const store2 = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    store2.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [130, 0, 0, 0] }));
    assert.deepEqual(ledgerOf(t.root)[1].m.m1, { i: 30, o: 0, r: 0, c: 0 });
  } finally {
    t.cleanup();
  }
});

// ------------------------------------------------------------------ 汇总

test('report：按天分组、按项目过滤、成本按单价表算且排序', async () => {
  const t = tmpRoots();
  try {
    let nowMs = NOW;
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => nowMs });
    await prime(store);
    store.recordResult({ sid: 'a', cwd: 'C:/A', resume: false }, mu({ 'claude-sonnet-5-5': [1000000, 0, 0, 0] }));
    nowMs += DAY;
    store.recordResult({ sid: 'a', cwd: 'C:/A', resume: false }, mu({ 'deepseek-x': [1000000, 0, 0, 0] }));
    store.recordResult({ sid: 'b', cwd: 'C:/B', resume: false }, mu({ m1: [999, 0, 0, 0] }));

    const rep = await store.report({ scope: 'project', project: 'C:/A' });
    assert.equal(rep.scope, 'project');
    assert.equal(rep.rows.length, 2);
    assert.ok(rep.rows[0].d < rep.rows[1].d, '行按日期升序');
    // 第一天：sonnet 全额
    const sonnetCost = Math.round(estimateCostUsd('claude-sonnet-5-5', { i: 1000000, o: 0, r: 0, c: 0 }) * 1e4) / 1e4;
    assert.equal(rep.rows[0].m['claude-sonnet-5-5'].cost, sonnetCost);
    // C:/B 的那笔不出现在本项目里
    const all = await store.report({ scope: 'all' });
    const flat = all.rows.flatMap((r) => Object.keys(r.m));
    assert.ok(flat.includes('m1'), '全部项目口径要能看到别的项目');
  } finally {
    t.cleanup();
  }
});

test('tick 的 spark 按天给出 14 格（别的项目不算进来）', async () => {
  const t = tmpRoots();
  try {
    let nowMs = NOW - 2 * DAY;
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => nowMs });
    await prime(store);
    store.recordResult({ sid: 'a', cwd: 'C:/A', resume: false }, mu({ m1: [5, 0, 0, 0] }));
    nowMs += DAY;
    store.recordResult({ sid: 'b', cwd: 'C:/B', resume: false }, mu({ m1: [7, 0, 0, 0] }));
    nowMs += DAY;
    // 新会话（新 sid）：全额 9，而不是拿旧基线去做差分
    store.recordResult({ sid: 'a2', cwd: 'C:/A', resume: false }, mu({ m1: [9, 0, 0, 0] }));

    const tick = store.tickFor('C:/A');
    assert.equal(tick.spark.length, 14);
    assert.equal(tick.spark[11], 5, '三格 … 倒数第三格 = 前天那笔');
    assert.equal(tick.spark[12], 0, '昨天只有 C:/B 的账，不算进本项目');
    assert.equal(tick.spark[13], 9, '最后一格 = 今天那笔');
    assert.equal(tick.today.t, 9);
  } finally {
    t.cleanup();
  }
});

test('读侧状态没建好之前，tick 给 null（不卡聊天主路）', async () => {
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    // 故意不先 report：recordResult 里那次 tickFor 应该给 null
    assert.equal(store.recordResult({ sid: 's1', cwd: 'C:/proj', resume: false }, mu({ m1: [1, 0, 0, 0] })), null);
  } finally {
    t.cleanup();
  }
});

// ------------------------------------------------------------------ 单价

test('estimateCostUsd：已知家族用自家价，认不出的走兜底（5/25、缓存读 0.1×）', () => {
  assert.equal(estimateCostUsd('deepseek-v4-flash[1m]', { i: 1e6, o: 1e6, r: 1e6, c: 0 }), 5 + 25 + 0.5);
  assert.equal(estimateCostUsd('claude-sonnet-5-5', { i: 1e6, o: 1e6, r: 1e6, c: 0 }), 2 + 10 + 0.2);
  assert.equal(estimateCostUsd('claude-opus-5-5', { i: 0, o: 0, r: 0, c: 1e6 }), 5 * 1.25);
  assert.equal(estimateCostUsd('claude-haiku-4-5', { i: 1e6, o: 0, r: 0, c: 0 }), 0.1);
});

test('localDay：按本地时区切', () => {
  assert.equal(localDay(NOW), '2026-10-10');
});

test('项目口径把路径分隔符归一：会话文件里的反斜杠与 Kotlin 传来的正斜杠是同一个项目', async () => {
  // 端到端实测抓到过：fill 行的 proj 来自会话文件的 cwd（Windows 反斜杠），
  // 而查询来自 Kotlin 的 basePath（正斜杠）—— 不归一，"本项目"会一边有一边没有
  const t = tmpRoots();
  try {
    const store = createUsageStore({ root: t.root, projectsRoot: t.projectsRoot, now: () => NOW });
    await prime(store, 'C:/proj');
    store.recordResult({ sid: 's1', cwd: 'C:\\proj', resume: false }, mu({ m1: [5, 0, 0, 0] }));
    const rep = await store.report({ scope: 'project', project: 'C:/proj' });
    assert.equal(rep.rows.length, 1);
    assert.equal(rep.rows[0].m.m1.i, 5);
    assert.equal(store.tickFor('C:/proj').today.t, 5);
    assert.equal(store.tickFor('C:\\proj').today.t, 5, '反斜杠传进来也认得');
    // 另一个项目不会被误收
    const other = await store.report({ scope: 'project', project: 'C:/other' });
    assert.equal(other.rows.length, 0);
  } finally {
    t.cleanup();
  }
});
