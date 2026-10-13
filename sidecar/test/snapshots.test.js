import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, existsSync, readdirSync, utimesSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createTurnSnapshots, normalizeTurnPath } from '../snapshots.js';

/** 每个用例一个临时根目录 —— 快照 store 只在测试自己的目录里写。 */
function makeRoot() {
  return mkdtempSync(join(tmpdir(), 'ccoder-snap-'));
}

function storeAt(root, over = {}) {
  return createTurnSnapshots({ root, projectKey: 'proj', sid: 'sid-1', cwd: root, ...over });
}

// ---- 归一化（跨端配对的地基，双胞胎在 Kotlin 的 OpenFileTarget） ----

test('normalizeTurnPath：四种写法折成同一个键（Windows）', () => {
  const opts = { win32: true };
  const cwd = 'C:/proj';
  const k = (raw) => normalizeTurnPath(cwd, raw, opts).key;
  assert.equal(k('a/b.txt'), 'c:/proj/a/b.txt');
  assert.equal(k('a\\b.txt'), 'c:/proj/a/b.txt');
  assert.equal(k('C:\\proj\\a\\b.txt'), 'c:/proj/a/b.txt');
  assert.equal(k('/c/proj/a/b.txt'), 'c:/proj/a/b.txt');
  assert.equal(k('sub/../a/./b.txt'), 'c:/proj/a/b.txt');
  assert.equal(k('  C:/PROJ/A/B.TXT  '), 'c:/proj/a/b.txt', 'Windows 键要大小写折叠');
});

test('normalizeTurnPath：UNC 的前导 // 保住；相对路径没有 cwd 时给 null', () => {
  assert.equal(normalizeTurnPath('C:/proj', '\\\\srv\\share\\x.kt', { win32: true }).key,
    '//srv/share/x.kt');
  assert.equal(normalizeTurnPath(null, 'a.txt', { win32: true }), null);
  assert.equal(normalizeTurnPath('C:/proj', '   ', { win32: true }), null);
});

test('normalizeTurnPath：非 Windows 不折大小写，绝对路径按 / 认', () => {
  const r = normalizeTurnPath('/home/u/proj', 'Sub/A.TXT', { win32: false });
  assert.equal(r.key, '/home/u/proj/Sub/A.TXT');
});

// ---- capture / snapshot ----

test('capture：存在的文件存的是**改动前**的字节，改完再取仍是旧的', () => {
  const root = makeRoot();
  const f = join(root, 'x.txt');
  writeFileSync(f, 'AAA\n');
  const s = storeAt(root);
  s.beginTurn();
  const r = s.capture({ path: f, toolUseId: 'toolu_1' });
  assert.deepEqual(r, { existed: true });
  writeFileSync(f, 'BBB\n');                 // 模拟工具把文件改了
  const snap = s.snapshot({ path: f });
  assert.equal(snap.kind, 'file');
  assert.equal(snap.content, 'AAA\n', '取回的必须是改动前的样子');
});

test('capture：新建文件记 existed=false；回滚 = 删掉它', () => {
  const root = makeRoot();
  const f = join(root, 'new.txt');
  const s = storeAt(root);
  s.beginTurn();
  assert.deepEqual(s.capture({ path: f, toolUseId: 'toolu_2' }), { existed: false });
  writeFileSync(f, 'created\n');             // 工具随后把它写出来了
  assert.deepEqual(s.snapshot({ path: f }), { kind: 'absent' });
  const [res] = s.revert({ paths: [f] });
  assert.equal(res.ok, true);
  assert.equal(res.action, 'deleted');
  assert.equal(existsSync(f), false);
});

test('同轮同文件只留第一份 —— 换一种写法也算同一个', () => {
  const root = makeRoot();
  const sub = join(root, 'sub');
  mkdirSync(sub);
  const f = join(sub, 'x.txt');
  writeFileSync(f, 'AAA\n');
  const s = storeAt(root);
  s.beginTurn();
  assert.deepEqual(s.capture({ path: f, toolUseId: 't1' }), { existed: true });
  writeFileSync(f, 'BBB\n');
  const again = s.capture({ path: f.replace(/\//g, '\\'), toolUseId: 't2' });
  assert.equal(again.deduped, true, '反斜杠写法要认出是同一个文件');
  assert.equal(s.entries().length, 1, '第二轮 capture 不能再记一条');
  assert.equal(s.entries()[0].toolUseId, 't1', '配对给的是第一次那个 toolUseId');
  assert.equal(s.snapshot({ path: f }).content, 'AAA\n');
});

test('entries：有没有存下都如实报（null = 无存档）', () => {
  const root = makeRoot();
  const big = join(root, 'big.bin');
  writeFileSync(big, 'ABCDEF');
  const s = storeAt(root, { maxFileBytes: 4 });
  s.beginTurn();
  const r = s.capture({ path: big, toolUseId: 't9' });
  assert.equal(r.existed, null);
  assert.equal(r.reason, 'too-big');
  // path 给的是**归一形**（store 的身份）；跨端配对走 toolUseId，path 只作展示
  assert.deepEqual(s.entries(), [{ toolUseId: 't9', path: big.replace(/\\/g, '/'), existed: null }]);
  assert.deepEqual(s.snapshot({ path: big }), { kind: 'missing' });
  const [res] = s.revert({ paths: [big] });
  assert.equal(res.ok, false);
  assert.equal(res.action, 'failed');
  assert.equal(res.error, 'no-snapshot');
});

// ---- 轮次 ----

test('轮次递增；revert 打的是**当前轮**的快照', () => {
  const root = makeRoot();
  const f = join(root, 'a.txt');
  writeFileSync(f, 'v1\n');
  const s = storeAt(root);
  const t1 = s.beginTurn();
  s.capture({ path: f, toolUseId: 't1' });
  writeFileSync(f, 'v2\n');
  const t2 = s.beginTurn();
  s.capture({ path: f, toolUseId: 't2' });
  writeFileSync(f, 'v3\n');
  assert.equal(t2, t1 + 1);
  // 当前轮（第 2 轮）的快照是 v2；回滚得到 v2 而不是 v1
  const [res] = s.revert({ paths: [f] });
  assert.equal(res.action, 'restored');
  assert.equal(readFileSync(f, 'utf8'), 'v2\n');
});

test('markTurnEnd 之后文件又被改过 → dirty=true（回执里说白）', () => {
  const root = makeRoot();
  const f = join(root, 'a.txt');
  writeFileSync(f, 'v1\n');
  const s = storeAt(root);
  s.beginTurn();
  s.capture({ path: f, toolUseId: 't1' });
  writeFileSync(f, 'v2\n');                  // 工具改完
  s.markTurnEnd();                           // 轮末记账
  // 本轮结束后，用户又动了它
  writeFileSync(f, 'v2-user\n');
  const future = new Date(Date.now() + 10_000);
  utimesSync(f, future, future);
  const [res] = s.revert({ paths: [f] });
  assert.equal(res.ok, true);
  assert.equal(res.dirty, true);
  assert.equal(readFileSync(f, 'utf8'), 'v1\n');

  // 没有后续改动的那条路：dirty=false
  writeFileSync(f, 'again\n');
  const s2 = storeAt(root, { sid: 'sid-2' });
  s2.beginTurn();
  s2.capture({ path: f, toolUseId: 't1' });
  writeFileSync(f, 'again2\n');
  s2.markTurnEnd();
  const [res2] = s2.revert({ paths: [f] });
  assert.equal(res2.dirty, false);
});

test('keepTurns：超出的旧轮连 blob 一起淘汰，再回滚就是「无存档」', () => {
  const root = makeRoot();
  const s = storeAt(root, { keepTurns: 2 });
  const files = ['f1.txt', 'f2.txt', 'f3.txt'];
  for (const [i, name] of files.entries()) {
    const f = join(root, name);
    writeFileSync(f, `v${i}\n`);
    s.beginTurn();
    s.capture({ path: f, toolUseId: `t${i}` });
  }
  const dir = join(root, 'proj', 'sid-1');
  const blobs = readdirSync(dir).filter((n) => n.endsWith('.blob'));
  assert.equal(blobs.length, 2, '第 1 轮的 blob 要删掉');
  const [res] = s.revert({ paths: [join(root, 'f1.txt')] });
  assert.equal(res.action, 'failed');
  assert.equal(res.error, 'no-snapshot');
});

test('落盘与恢复：新实例接着旧索引，轮次号不撞', () => {
  const root = makeRoot();
  const f = join(root, 'a.txt');
  writeFileSync(f, 'v1\n');
  const a = storeAt(root);
  const t1 = a.beginTurn();
  a.capture({ path: f, toolUseId: 't1' });
  writeFileSync(f, 'v2\n');
  a.markTurnEnd();

  const b = storeAt(root);           // 模拟插件重启后 resume
  assert.equal(b.currentTurn(), t1, '没发消息前，当前轮 = 磁盘上最新那轮');
  assert.equal(b.entries().length, 1);
  assert.equal(b.entries()[0].existed, true);
  // 还没发新消息：对比/回滚打的就是磁盘上最新那轮（恢复后照常可用）
  const [res] = b.revert({ paths: [f] });
  assert.equal(res.action, 'restored');
  assert.equal(readFileSync(f, 'utf8'), 'v1\n');
  const t2 = b.beginTurn();
  assert.equal(t2, t1 + 1, '新一轮的编号要续着来，不撞旧轮');
});

test('sid 迟到：补上之前只计数不落盘，补上之后照常记', () => {
  const root = makeRoot();
  const f = join(root, 'a.txt');
  writeFileSync(f, 'v1\n');
  const s = storeAt(root, { sid: null });
  s.beginTurn();
  const early = s.capture({ path: f, toolUseId: 't0' });
  assert.equal(early.existed, null);
  assert.equal(early.reason, 'no-sid');
  s.setSid('sid-late');
  const ok = s.capture({ path: f, toolUseId: 't1' });
  assert.equal(ok.existed, true, 'id 补上之后同一轮内照常抓');
});

test('坏索引不静默：改名 .bad 后从头再来', () => {
  const root = makeRoot();
  const dir = join(root, 'proj', 'sid-1');
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'index.json'), '{ 这不是 JSON');
  const s = storeAt(root);
  assert.deepEqual(s.entries(), []);
  assert.equal(existsSync(join(dir, 'index.json.bad')), true, '坏文件要留证据，不能悄悄覆盖');
});
