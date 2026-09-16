/**
 * 探针：**SDK 去哪个目录名下面找这个项目的会话？**
 *
 * 起因（2026-09-16）：用户开了两个 IDE，其中一个（项目在映射盘 `Z:` 上）的历史会话
 * 列表**恒为空**，而磁盘上 `~/.claude/projects/Z--m71-server/` 里躺着 107 条会话。
 * 同一个仓库里的另一个项目（本地 `C:` 盘）列表正常。
 *
 * ## 结论：两边算目录名的输入不是同一个字符串
 *
 * SDK 取会话目录前会**解析真实路径**，用的是 `fs.realpathSync.native`（libuv，
 * Windows 上走 `GetFinalPathNameByHandle`）。**映射盘的 native realpath 会展开成 UNC**，
 * 而 JS 那版 `realpathSync` 不会：
 *
 * ```
 * Z:\m71\server
 *    realpathSync        : Z:\m71\server                          → Z--m71-server   ← CLI 写的
 *    realpathSync.native : \\192.168.3.221\lihoo\m71\server       → --192-168-3-221-lihoo-m71-server ← SDK 找的
 * ```
 *
 * 目录名规则照抄 SDK：`path.replace(/[^a-zA-Z0-9]/g, '-')`（sdk.mjs 里的 `Au`）。
 * 而**写**会话的是 CLI：终端里 `cd Z:\m71\server` 之后 `process.cwd()` 就是字面路径，
 * 于是写在 `Z--m71-server` 下面。**两个名字永远碰不上面。**
 *
 * ## 这个结论是怎么钉死的（不是读代码猜的）
 *
 * 把 HOME/USERPROFILE 指到一个**假的家目录**，在几个候选目录名下各放一份会话文件
 * （文件名用不同的 uuid），再调 `listSessions({dir})`：
 *
 * ```
 * 候选目录名                        放进去的 sessionId      回执
 * Z--m71-server                    11111111…               没出现
 * --192-168-3-221-lihoo-m71-server 33333333…               出现了 ← 就是它
 * ```
 *
 * 传 UNC 路径进去回执也是 `33333333` —— 两条路的解析结果一样，佐证了上面的机制。
 *
 * ## 影响范围
 *
 * 不只是列表：SDK 里加载历史 / 改名 / 删除走的是**同一条 key 推导**
 * （`no()` → `Ps()`），所以映射盘项目下这一整块都是瞎的。同理适用于一切
 * "字面路径 ≠ native realpath" 的项目：junction / symlink / subst 盘。
 *
 * ## 绕法
 *
 * **全都用 UNC**：IDE 里按 `\\192.168.3.221\lihoo\m71\server` 打开，终端里也 cd 到
 * UNC 再跑 claude —— 两边算出来就都是 UNC 那个名字。老的会话要看见得把
 * `Z--m71-server` 改名成 `--192-168-3-221-lihoo-m71-server`（动的是会话库，先问人）。
 *
 * 花钱与副作用：**零模型调用**，只读 `~/.claude/projects` 的目录清单。不改任何东西。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-session-key.mjs [目录...]      # 缺省看当前目录
 */
import { listSessions } from '@anthropic-ai/claude-agent-sdk';
import { existsSync, readdirSync, realpathSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

/** SDK 的目录名规则（sdk.mjs 里的 `Au`，一个字都没改）。 */
const keyOf = (p) => p.replace(/[^a-zA-Z0-9]/g, '-');

const UUID_JSONL = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.jsonl$/i;

const projectsRoot = join(homedir(), '.claude', 'projects');

function realOf(fn, p) {
  try {
    return fn(p);
  } catch {
    return null;
  }
}

/** 这个目录名下面有多少条会话。不存在给 null。 */
function sessionsUnder(key) {
  const dir = join(projectsRoot, key);
  if (!existsSync(dir)) return null;
  try {
    return readdirSync(dir).filter((n) => UUID_JSONL.test(n)).length;
  } catch {
    return null;
  }
}

const dirs = process.argv.slice(2);
if (dirs.length === 0) dirs.push(process.cwd());

console.log(`会话库：${projectsRoot}\n`);

for (const dir of dirs) {
  const js = realOf(realpathSync, dir);
  const native = realOf(realpathSync.native, dir);
  console.log(`目录：${dir}`);
  console.log(`  realpathSync        ：${js ?? '（解析失败）'}`);
  console.log(`  realpathSync.native ：${native ?? '（解析失败）'}`);

  // 三个候选：字面、JS 解析、native 解析。去重后逐个报"那儿有多少条会话"
  const seen = new Set();
  for (const [label, p] of [['字面', dir], ['JS realpath', js], ['native realpath', native]]) {
    if (p == null) continue;
    const key = keyOf(p);
    if (seen.has(key)) continue;
    seen.add(key);
    const n = sessionsUnder(key);
    console.log(`  ${label.padEnd(16)}→ ${key}   那里有 ${n === null ? '**没有这个目录**' : n + ' 条'}`);
  }

  const found = await listSessions({ dir, limit: 5 });
  console.log(`  listSessions(${dir}) → **${found.length} 条**`);
  console.log(`  结论：${found.length > 0 ? '两条路的目录名一致（本地盘、或本来就用 UNC）' : '列表为空 —— 看上表，SDK 找的那个目录名和 CLI 写的不是同一个'}`);
  console.log();
}
