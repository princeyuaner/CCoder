/**
 * 本轮改动卡的快照 store —— 机制甲（spec：`docs/superpowers/specs/2026-10-13-turn-changes-spec.md` §3）。
 *
 * ## 它是什么
 *
 * 「本轮改动」卡要能**看对比、能回滚**，而这需要一份「改动前的内容」—— 转写区里
 * 没有它。侧车在每次编辑落地**之前**（PreToolUse hook，先于工具执行，探针事实
 * 见 spec §1）把文件原样存一份；回滚 = 把那一份写回，存档时文件不存在 → 删掉。
 * 同一轮同一文件**只留第一份** —— 那一份就是「本轮开始前」的样子（探针事实 2）。
 *
 * ## 存储（root 目录，默认 ~/.ccoder/turn-snapshots）
 *
 * ```
 * <projectKey>/<sid>/index.json            每轮的元数据（谁、什么时候、存没存下）
 * <projectKey>/<sid>/t<n>-<hash16>.blob    快照本体（原样字节）
 * ```
 *
 * 整个目录都可以删 —— 丢的只是「回滚与对比」，卡片清单本身从转写区就能重建。
 * 每会话只保留最近 [keepTurns] 轮（卡片只活在最新一轮，旧轮的快照没有用户）；
 * 单文件超过 [maxFileBytes] 不存（行上标「无存档」，照实说）。
 *
 * ## 键：归一化只在这一层做一次
 *
 * 捕获时用 hook 的 `tool_input.file_path`，查/回滚时用网页送来的工具卡路径 ——
 * 两者**不保证逐字节相同**（CLI 可能换了写法）。所以本 store 内部一律按
 * [normalizeTurnPath] 归一后的键记账；而「哪个 toolUseId 算有存档」的配对
 * 走 toolUseId，不信路径字符串（网页侧做的是同一件事）。
 *
 * 归一化的双胞胎在 Kotlin：`OpenFileTarget.resolveAbsolutePath`（盘符两种写法、
 * UNC、相对路径折 `.`/`..` 的规则一致）。两边规则**照抄同一套**，改一边要看另一边。
 *
 * ## 原子写
 *
 * index 与 blob 都走 tmp + rename（同 usage.js）：半个文件比没有文件更糟。
 * 回滚写回原文件时同样 tmp + rename，并保住权限位（POSIX 上 rename 不会带走 mode）。
 */
import {
  existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync, chmodSync,
} from 'node:fs';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

/** 侧车要抓的编辑类工具 —— 与网页的 FILE_TOOLS 同族（NotebookRead/Read 不写盘，不抓）。 */
export const EDIT_TOOLS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);

const README_TEXT = `CCoder 「本轮改动」快照目录（整个目录都可以删 —— 丢的只是回滚与对比）。
- <项目>/<会话>/index.json        每轮的元数据：文件、是否新建、轮末 mtime
- <项目>/<会话>/t<轮>-<哈希>.blob  快照本体（编辑落地前的原样字节）
口径：
- 同一轮同一文件只留第一份 = 本轮开始前；
- 单文件超过 2MB 不存（界面上标「无存档」）；
- 每会话只保留最近 20 轮。
`;

/**
 * 把「Claude 报出来的路径」归一成记账用的绝对路径。
 *
 * 认得：`C:/p/a.kt`、`C:\\p\\a.kt`、`/c/Users/a.kt`（Git Bash 风格）、
 * `src/x.ts`（相对 cwd）、`//srv/share/a.kt`（UNC）。认不出给 null。
 *
 * @returns {{path: string, key: string}|null} path = 原样大小写的绝对路径（做文件操作用）；
 *   key = 记账键（Windows 上小写折叠 —— 盘符与大小写不敏感是同一份文件）。
 */
export function normalizeTurnPath(cwd, raw, { win32 = process.platform === 'win32' } = {}) {
  let p = String(raw ?? '').trim().replace(/\\/g, '/');
  if (!p) return null;

  // Git Bash 风格：/c/Users/... → C:/Users/...
  if (/^\/[a-zA-Z]\//.test(p)) p = p[1].toUpperCase() + ':' + p.slice(2);

  const isAbsolute = /^[a-zA-Z]:\//.test(p) || p.startsWith('//') || (!win32 && p.startsWith('/'));
  if (!isAbsolute) {
    const base = String(cwd ?? '').trim().replace(/\\/g, '/').replace(/\/+$/, '');
    if (!base) return null;
    p = `${base}/${p}`;
  }

  const collapsed = collapse(p);
  if (!collapsed) return null;
  return { path: collapsed, key: win32 ? collapsed.toLowerCase() : collapsed };
}

/**
 * 折掉 `.` 与 `..` 段（`/a/./b/../c` → `/a/c`）。
 *
 * **照抄 Kotlin 的 `OpenFileTarget.collapse`**（连 UNC 前导 `//` 必须保住这条
 * 怪癖一起）—— 两个实现给出同一个答案，跨端配对才不会一边有一边没有。
 */
function collapse(path) {
  const absolute = path.startsWith('/');
  const unc = path.startsWith('//');
  const segments = [];
  for (const segment of path.split('/')) {
    if (segment === '' || segment === '.') continue;
    if (segment === '..') {
      if (segments.length > 0 && segments[segments.length - 1] !== '..') segments.pop();
      else if (!absolute && !unc) segments.push('..');
      continue;
    }
    segments.push(segment);
  }
  const body = segments.join('/');
  if (unc) return `//${body}`;
  if (absolute) return `/${body}`;
  return body;
}

/** 原子写：半个文件比没有文件更糟。 */
function writeAtomic(path, data) {
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, data);
  renameSync(tmp, path);
}

/**
 * 建一个会话的快照 store。
 *
 * @param {object} opts
 * @param {string} opts.cwd        会话的项目目录（相对路径归一化的基准）
 * @param {string} opts.projectKey 项目目录名（`literalProjectKey(cwd)`，与会话存储同一套）
 * @param {string|null} opts.sid   会话 id。新会话在 init 事件到达前是 null ——
 *   那时 [setSid] 补上；补上之前只计数不落盘（那种窗口里工具根本还没得跑）
 * @param {number} [opts.maxFileBytes] 单文件上限，超了标「无存档」
 * @param {number} [opts.keepTurns]    每会话保留最近几轮
 */
export function createTurnSnapshots({
  root = join(homedir(), '.ccoder', 'turn-snapshots'),
  projectKey,
  sid = null,
  cwd = null,
  maxFileBytes = 2 * 1024 * 1024,
  keepTurns = 20,
  now = () => Date.now(),
  win32 = process.platform === 'win32',
} = {}) {
  /** @type {string|null} */ let currentSid = sid;
  /** 索引：`{ turns: [{ n, at, files: [...] }] }`。懒加载。 */
  let index = null;
  /** 当前轮的轮次号。**恢复来的会话从 max+1 续**（不撞旧轮）。 */
  let turn = 0;

  function dirOf() {
    return currentSid ? join(root, projectKey ?? 'unknown', currentSid) : null;
  }

  function indexPath() {
    const d = dirOf();
    return d ? join(d, 'index.json') : null;
  }

  function ensureLoaded() {
    if (index !== null) return;
    index = { turns: [] };
    const p = indexPath();
    if (!p || !existsSync(p)) return;
    try {
      const parsed = JSON.parse(readFileSync(p, 'utf8'));
      if (parsed && Array.isArray(parsed.turns)) index = parsed;
    } catch {
      // 坏 JSON 不能静默当"没有"（那会把磁盘上一堆 blob 变成孤儿）——
      // 改名留证据，从头再来
      try { renameSync(p, `${p}.bad`); } catch { /* 留不下就算了 */ }
    }
  }

  function persist() {
    const p = indexPath();
    if (!p) return;
    try {
      mkdirSync(dirname(p), { recursive: true });
      writeAtomic(p, `${JSON.stringify(index)}\n`);
      const readme = join(root, 'README.txt');
      mkdirSync(root, { recursive: true });
      if (!existsSync(readme)) writeFileSync(readme, README_TEXT, 'utf8');
    } catch (err) {
      console.error(`[ccoder] 快照索引写盘失败（不影响会话）：${err?.message ?? err}`);
    }
  }

  function turnOf(n) {
    ensureLoaded();
    return index.turns.find((t) => t.n === n) ?? null;
  }

  function maxTurn() {
    ensureLoaded();
    return index.turns.reduce((m, t) => Math.max(m, t.n), 0);
  }

  /** 淘汰旧轮：删条目 + 删 blob。 */
  function prune() {
    ensureLoaded();
    const kept = index.turns.filter((t) => t.n > maxTurn() - keepTurns);
    const dropped = index.turns.filter((t) => !kept.includes(t));
    if (dropped.length === 0) return;
    index.turns = kept;
    const d = dirOf();
    for (const t of dropped) {
      for (const f of t.files) {
        if (!f.blob || !d) continue;
        try { rmSync(join(d, f.blob), { force: true }); } catch { /* 删不掉就留着 */ }
      }
    }
    persist();
  }

  /** 新会话补 id（init 事件到达时调）。id 变了才重建索引。 */
  function setSid(next) {
    if (!next || next === currentSid) return;
    currentSid = next;
    index = null;
    // 只增不减：id 迟到时内存里可能已经开过轮（那时只计数）——
    // 倒回磁盘 max 会让同一轮内的 capture 找不到轮
    turn = Math.max(maxTurn(), turn);
    prune();
  }

  return {
    get sid() { return currentSid; },

    /** 新会话在 init 事件里拿到 id 时补上（会话建立前工具没得跑，不丢东西）。 */
    setSid(next) {
      setSid(next);
    },

    /**
     * 恢复会话时把轮次号续到磁盘上那个 max（不撞旧轮）；
     * 新会话就是 0。构造后立即可读，但**不落盘**。
     */
    init() {
      turn = maxTurn();
      prune();
    },

    /** 一条用户消息出发 = 新一轮。返回本轮轮次号。 */
    beginTurn() {
      ensureLoaded();
      // **只增不减**：setSid 重载索引之后，内存里的轮次号不能倒回去
      //（否则新轮的轮次号会撞上刚才那条内存轮的记录）
      turn = Math.max(maxTurn(), turn) + 1;
      index.turns.push({ n: turn, at: now(), files: [] });
      prune();
      persist();
      return turn;
    },

    /** 当前轮（回滚/取快照都打它）。恢复来的会话在没发消息前 = 磁盘上最新那轮。 */
    currentTurn() {
      if (turn === 0) turn = maxTurn();
      return turn;
    },

    /**
     * 抓一份快照（hook 里调，先于工具执行）。
     *
     * @returns {{existed: boolean|null, reason?: string, deduped?: boolean}}
     *   existed=null 表示**没存下**（会话 id 未定 / 路径认不出 / 太大 / 读失败）——
     *   调用方据此给网页发一条「无存档」标记；`deduped` 表示本轮已有这份，不发标记。
     */
    capture({ path, toolUseId = null }) {
      // 惰性建轮：setSid 迟到的情形（建会话时没有 id，init 事件才带上）会把
      // 内存里那条空轮丢掉 —— 这里把它补回来，编号取内存里那个只增不减的
      let t = turnOf(this.currentTurn());
      if (!t && currentSid && this.currentTurn() >= 1) {
        t = { n: this.currentTurn(), at: now(), files: [] };
        index.turns.push(t);
      }
      if (!t) return { existed: null, reason: 'no-turn' };
      const abs = normalizeTurnPath(cwd, path, { win32 });
      if (!abs) return { existed: null, reason: 'bad-path' };
      if (t.files.some((f) => f.key === abs.key)) return { existed: null, deduped: true };
      if (!currentSid) return { existed: null, reason: 'no-sid' };

      let exists = false;
      let size = 0;
      try {
        exists = existsSync(abs.path);
        if (exists) size = statSync(abs.path).size;
      } catch {
        return { existed: null, reason: 'stat-failed' };
      }

      // 文件不存在也是一种快照 —— 回滚就是删掉它（探针事实 3）
      if (!exists) {
        t.files.push({ key: abs.key, path: abs.path, toolUseId, existed: false });
        persist();
        return { existed: false };
      }

      if (size > maxFileBytes) {
        t.files.push({ key: abs.key, path: abs.path, toolUseId, existed: true, unsupported: true });
        persist();
        return { existed: null, reason: 'too-big' };
      }

      let bytes;
      try {
        bytes = readFileSync(abs.path);
      } catch {
        return { existed: null, reason: 'read-failed' };
      }
      const blob = `t${t.n}-${createHash('sha1').update(abs.key).digest('hex').slice(0, 16)}.blob`;
      try {
        mkdirSync(dirOf(), { recursive: true });
        writeAtomic(join(dirOf(), blob), bytes);
      } catch (err) {
        console.error(`[ccoder] 快照写盘失败（不影响会话）：${err?.message ?? err}`);
        return { existed: null, reason: 'write-failed' };
      }
      t.files.push({ key: abs.key, path: abs.path, toolUseId, existed: true, blob, size });
      persist();
      return { existed: true };
    },

    /**
     * 轮末记账（result 事件时调）：把每个存档文件的 mtime 记下 ——
     * 回滚时拿它检测「本轮结束后又被改过」（dirty）。
     */
    markTurnEnd() {
      const t = turnOf(this.currentTurn());
      if (!t) return;
      for (const f of t.files) {
        try { f.mtimeAtEnd = statSync(f.path).mtimeMs; } catch { f.mtimeAtEnd = null; }
      }
      persist();
    },

    /** 本轮的全部标记（给网页标「新 / 无存档」）。ready / 恢复时推一次。 */
    entries() {
      const t = turnOf(this.currentTurn());
      if (!t) return [];
      return t.files.map((f) => ({
        toolUseId: f.toolUseId,
        path: f.path,
        existed: f.unsupported ? null : f.existed,
      }));
    },

    /** 取「改动前」内容。`missing` = 没存档（老轮次 / 开关关 / 超限）。 */
    snapshot({ path }) {
      const t = turnOf(this.currentTurn());
      const abs = normalizeTurnPath(cwd, path, { win32 });
      if (!t || !abs) return { kind: 'missing' };
      const f = t.files.find((x) => x.key === abs.key);
      if (!f) return { kind: 'missing' };
      if (f.existed === false) return { kind: 'absent' };
      if (!f.blob) return { kind: 'missing' };
      try {
        return { kind: 'file', content: readFileSync(join(dirOf(), f.blob), 'utf8') };
      } catch {
        return { kind: 'missing' };
      }
    },

    /**
     * 逐文件回滚。**每条路径各自成败**，绝不整批放弃 —— 回执里逐条带原因。
     *
     * @returns {Array<{path, ok, action: 'restored'|'deleted'|'failed', error?, dirty?}>}
     *   action 三分类与 spec §4 一致；dirty = 轮末记过 mtime 且现在对不上
     *   （本轮结束后又被改过 —— 包括用户自己改的）。
     *
     *   `error` 两种形状：我们自己的已知情况给**稳定的码**（`'no-snapshot'`，
     *   由网页翻成当前语言 —— 它是界面字句）；系统错误（EACCES…）**原样透传**，
     *   转它等于替系统撒谎（同 strings.js 顶上那条规矩）。
     */
    revert({ paths = [] } = {}) {
      const t = turnOf(this.currentTurn());
      const out = [];
      for (const raw of paths) {
        const abs = normalizeTurnPath(cwd, raw, { win32 });
        const f = t && abs ? t.files.find((x) => x.key === abs.key) : null;
        // unsupported（太大没存下）与查无此档是同一件事：回不了
        if (!f || f.unsupported) {
          out.push({ path: raw, ok: false, action: 'failed', error: 'no-snapshot' });
          continue;
        }
        let dirty;
        if (f.mtimeAtEnd != null) {
          try { dirty = statSync(f.path).mtimeMs !== f.mtimeAtEnd; } catch { dirty = undefined; }
        }
        try {
          if (f.existed === false) {
            rmSync(f.path, { force: true });   // 本轮新建的 → 回滚 = 删掉
            out.push({ path: raw, ok: true, action: 'deleted', dirty });
            continue;
          }
          const content = readFileSync(join(dirOf(), f.blob));
          const tmp = `${f.path}.ccoder-tmp`;
          writeFileSync(tmp, content);
          // POSIX 上 rename 不会把原文件的权限位带过来 —— 显式搬一次
          try { chmodSync(tmp, statSync(f.path).mode); } catch { /* Windows 上没有这一套 */ }
          renameSync(tmp, f.path);
          out.push({ path: raw, ok: true, action: 'restored', dirty });
        } catch (err) {
          out.push({ path: raw, ok: false, action: 'failed', error: String(err?.message ?? err) });
        }
      }
      return out;
    },
  };
}
