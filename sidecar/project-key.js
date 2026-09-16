/**
 * 会话目录名（project key）对齐 —— **映射盘 / 符号链接项目的历史会话是空的那条**。
 *
 * ## 症状（2026-09-16 实机）
 *
 * 用户开着两个 IDE：本地盘那个项目历史会话正常，映射盘（`Z:` → `\\192.168.3.221\lihoo`）
 * 那个**恒为空**，而磁盘上 `~/.claude/projects/Z--m71-server/` 里躺着 107 条。
 *
 * ## 根因：两边算目录名用的**不是同一个字符串**
 *
 * SDK 取会话目录前会把路径解析一遍，用的是 `fs.realpathSync.native`（libuv，
 * Windows 上走 `GetFinalPathNameByHandle`）—— 映射盘会被**展开成 UNC**；而会话是
 * CLI 写的，它按 `process.cwd()` 的**字面路径**写：
 *
 * ```
 * Z:\m71\server
 *    CLI（写）        Z:\m71\server                       → Z--m71-server              ← 107 条躺这儿
 *    SDK（读）        \\192.168.3.221\lihoo\m71\server    → --192-168-3-221-lihoo-…   ← 它找这儿
 * ```
 *
 * 注意 JS 那版 `fs.realpathSync` 保留盘符、native 那版才展开 —— 两个 realpath
 * 不一样，是这条坑的全部来源（探针见 `tools/probe-session-key.mjs`）。
 *
 * ## 修法：不自己解析会话，而是**把 SDK 的目录名指定成 CLI 那个**
 *
 * SDK 读环境变量 `CLAUDE_CODE_PROJECT_DIR_NAME`（要配合 `CLAUDE_CONFIG_DIR`）作为
 * 项目目录名，优先级高于它自己算的那个（sdk.mjs 里的 `Ps()` → `iU()`）。实测：
 *
 * ```
 * CLAUDE_CONFIG_DIR=C:\Users\CY\.claude CLAUDE_CODE_PROJECT_DIR_NAME=Z--m71-server
 *   listSessions({dir:'Z:\m71\server'})  → 30 条
 *   getSessionMessages(<id>, {dir})      → 516 条消息
 * ```
 *
 * 为什么走这条而不是"自己读 jsonl"（另一个插件 `idea-claude-code-gui` 的做法，
 * 它的 `PathUtils.sanitizePath()` 就是这一条 `replace(/[^a-zA-Z0-9]/g,'-')`
 * 加在**字面**路径上）：因为改环境变量**一次性覆盖所有接口** —— 列表、加载历史、
 * 改名、打标签、删除、子代理全走同一条 key 推导。自己读 jsonl 则要把这些
 * 一个一个重写，还要复刻 SDK 的元数据（customTitle / tag）与过滤规则。
 *
 * ## 只在**确实不一致**时才动环境变量
 *
 * 本地盘上两条路算出来的名字本来就一样（`C--Users-CY-Desktop-CCoder`），那时
 * 一个字节都不改 —— 少一个变量就少一种出错方式。
 *
 * ## 依赖的是**没写进文档的内部开关**
 *
 * `CLAUDE_CODE_PROJECT_DIR_NAME` 不在 sdk.d.ts 里（探针 + `grep -a` 内嵌代码
 * 才找到的）。所以：只在需要时设、设完写一行 stderr 留痕、并且**别删**
 * `tools/probe-session-key.mjs` —— 将来 SDK 换了这套机制，那个探针一跑就露馅。
 */
import { realpathSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

/** SDK 的目录名规则（sdk.mjs 里 `Au`，与 CLI、与别的插件都是同一条）。 */
export function literalProjectKey(dir) {
  return String(dir ?? '').replace(/[^a-zA-Z0-9]/g, '-');
}

/** native realpath —— 解析失败（目录不存在、没权限）给 null，不抛。 */
export function nativeRealPath(dir) {
  try {
    return realpathSync.native(dir);
  } catch {
    return null;
  }
}

/**
 * 这个目录两边算出来的名字**是否不一致**。
 *
 * @param {string} dir
 * @param {(dir: string) => string|null} resolve 取"SDK 会用的那个路径"，可注入（测试用）
 * @returns {{literal: string, sdkKey: string, sdkPath: string}|null}
 *   一致、或解析不出来时给 null（那时不需要任何干预）
 */
export function projectKeyMismatch(dir, resolve = nativeRealPath) {
  const literal = literalProjectKey(dir);
  if (!literal) return null;
  const sdkPath = resolve(dir);
  if (!sdkPath) return null;
  const sdkKey = literalProjectKey(sdkPath);
  return sdkKey === literal ? null : { literal, sdkKey, sdkPath };
}

/** 已经设过的那一个名字。SDK 那边是**记忆化**的：第一次读走之后就定了。 */
let applied = null;

/**
 * 把 SDK 的项目目录名对齐到 CLI 那个。**幂等**，重复调用不会反复改环境。
 *
 * @param {string} dir 项目目录（字面路径）
 * @param {object} [opts]
 * @param {object} [opts.env]    要改的环境对象，默认 process.env。
 *   必须是**进程自己的**环境：SDK 从 process.env 读，子进程也继承它
 * @param {Function} [opts.resolve] 见 [projectKeyMismatch]
 * @returns {{applied: boolean, reason?: string, literal?: string, sdkKey?: string}}
 */
export function applyProjectKey(dir, { env = process.env, resolve = nativeRealPath } = {}) {
  const mismatch = projectKeyMismatch(dir, resolve);
  if (!mismatch) return { applied: false, reason: 'keys-agree' };

  // 用户（或宿主）自己指定过就别抢：那是他们的配置，我们只是补一个缺失的默认值
  const preset = env.CLAUDE_CODE_PROJECT_DIR_NAME;
  if (preset && preset !== mismatch.literal) {
    console.error(
      `[ccoder] 会话目录名已是 "${preset}"，不覆盖（本会话会被 SDK 当作 ${mismatch.sdkKey} 去找）`,
    );
    return { applied: false, reason: 'preset' };
  }

  if (applied !== null) {
    // 记忆化：第一份生效之后改也没用。换个项目还带着上一个名字，是**说清楚**而不是硬改
    if (applied !== mismatch.literal) {
      console.error(
        `[ccoder] 本进程的会话目录名已定为 "${applied}"，改为 "${mismatch.literal}" 不会生效（SDK 只读第一次）`,
      );
    }
    return { applied: false, reason: 'already-applied', literal: mismatch.literal };
  }

  env.CLAUDE_CONFIG_DIR = env.CLAUDE_CONFIG_DIR ?? join(homedir(), '.claude');
  env.CLAUDE_CODE_PROJECT_DIR_NAME = mismatch.literal;
  applied = mismatch.literal;
  console.error(
    `[ccoder] 会话目录名对齐：SDK 会解析成 ${mismatch.sdkPath}（→ ${mismatch.sdkKey}），` +
      `会话实际写在 ${mismatch.literal}；已按后者指定`,
  );
  return { applied: true, literal: mismatch.literal, sdkKey: mismatch.sdkKey };
}

/** 测试用：清掉"已设过"的记忆。 */
export function resetAppliedForTest() {
  applied = null;
}
