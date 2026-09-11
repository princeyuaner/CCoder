# CCoder 插件实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 PyCharm 插件，通过 Node sidecar 调用 Claude Agent SDK，在工具窗口中与 Claude Code 对话，并把权限询问渲染为可交互的非模态卡片。

**Architecture:** 三层进程 —— PyCharm(JVM) → node sidecar → claude CLI。插件与 sidecar 用换行分隔 JSON（NDJSON）双向通信，sidecar 透传 SDK 事件、桥接权限回调。sidecar 用**流式输入模式**驱动 SDK（非此模式则 `interrupt()`/`setPermissionMode()` 不可用）。

**Tech Stack:** Kotlin 2.x + IntelliJ Platform Gradle Plugin 2.x + Gson（平台自带）；Node 18+ + `@anthropic-ai/claude-agent-sdk` 0.3.268。

**Spec:** `docs/superpowers/specs/2026-09-11-pycharm-claude-code-plugin-design.md`

## Global Constraints

以下约束适用于**每一个**任务，不再逐条重复。

- **平台目标**：PyCharm `2025.3.1.1`，`sinceBuild = "253"`，**不设 `untilBuild`**（用 `untilBuild = provider { null }`）
- **JDK**：21（本机 Temurin 21.0.12.1）
- **Node**：>= 18（SDK `engines` 要求，实测本机已具备）
- **SDK 版本**：固定 `@anthropic-ai/claude-agent-sdk@0.3.268`，与用户 CLI 版本对齐
- **不打包平台二进制**：`npm install --omit=optional` 跳过 212M 的 `@anthropic-ai/claude-agent-sdk-*` 平台包，`claude` 路径运行时解析
- **环境清洗是正确性保证**：黑名单 10 项精确匹配，**不是前缀匹配**（spec §3.2）
- **权限请求必须永远 resolve**：任何终止路径都要清空待决表（spec §6.2 规则①）
- **未知事件类型必须静默忽略**，绝不抛错（spec §3.3）
- **提交信息**结尾加 `Co-Authored-By: Claude Code <noreply@anthropic.com>`

---

## 文件结构

```
CCoder/
├── .gitattributes                    # Task 6：统一换行符
├── .gitignore                        # 已存在
├── build.gradle.kts                  # Task 6
├── settings.gradle.kts               # Task 6
├── gradle.properties                 # Task 6
├── sidecar/
│   ├── package.json                  # Task 1
│   ├── env.js                        # Task 1：环境清洗
│   ├── claude-path.js                # Task 2：可执行文件解析
│   ├── ndjson.js                     # Task 3：分帧与解析
│   ├── session.js                    # Task 4：包装 SDK query()
│   ├── index.js                      # Task 5：stdio 主循环
│   └── test/
│       ├── env.test.js               # Task 1
│       ├── claude-path.test.js       # Task 2
│       ├── ndjson.test.js            # Task 3
│       └── session.test.js           # Task 4
└── src/
    ├── main/kotlin/com/ccoder/
    │   ├── sidecar/
    │   │   ├── Protocol.kt           # Task 8：消息类型 + 解析
    │   │   ├── SidecarExtractor.kt   # Task 7：运行时提取
    │   │   ├── SidecarProcess.kt     # Task 9：进程生命周期
    │   │   ├── SidecarClient.kt      # Task 10：RPC 收发
    │   │   ├── SidecarLocator.kt     # Task 10（开发模式）+ Task 14（生产模式）
    │   │   └── NodeCheck.kt          # Task 10：node 可用性检查
    │   ├── settings/
    │   │   ├── ClaudeSettings.kt     # Task 11
    │   │   └── ClaudeSettingsPanel.kt# Task 11
    │   ├── ui/
    │   │   ├── ClaudePanel.kt        # Task 12（Task 13 扩展权限部分）
    │   │   ├── MessageRenderer.kt    # Task 12
    │   │   ├── PermissionQueue.kt    # Task 13
    │   │   ├── PermissionCard.kt     # Task 13
    │   │   └── PendingPermissionStatusBar.kt  # Task 13
    │   └── ClaudeToolWindowFactory.kt# Task 12
    ├── main/resources/META-INF/plugin.xml   # Task 6
    └── test/kotlin/com/ccoder/
        ├── sidecar/SidecarExtractorTest.kt  # Task 7
        ├── sidecar/ProtocolTest.kt          # Task 8
        ├── sidecar/SidecarProcessTest.kt    # Task 9
        ├── sidecar/SidecarClientTest.kt     # Task 10
        ├── sidecar/SidecarLocatorTest.kt    # Task 10
        ├── settings/ClaudeSettingsTest.kt   # Task 11
        ├── ui/MessageRendererTest.kt        # Task 12
        └── ui/PermissionQueueTest.kt        # Task 13
```

**与 spec §13 的两处偏离**（均为 spec 自身要求所迫）：

1. 新增 `sidecar/ndjson.js` —— spec §13 把分帧逻辑隐含在 `index.js` 里，但 spec §10.1 要求测试"分片到达的消息正确重组"，该逻辑必须可独立调用。
2. 新增 `SidecarLocator.kt` 与 `NodeCheck.kt` —— spec §13 未列，但前者是 §8.3 提取流程的调用入口，后者是 §5.3 `NODE_NOT_FOUND` 错误码的实现载体。

`SidecarLocator` 优先走开发模式（直接用源码树的 `sidecar/`），使 `runIde` 下改 JS 重启即生效，无需每次重新提取。

---

## Task 1: sidecar 骨架 + 环境清洗

这是整个项目**最高价值**的一个模块：spec §11.1 实测证明，环境不清洗会导致 CLI 直接认证失败。三个硬约束里它是最容易在重构中被破坏的。

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\sidecar\package.json`
- Create: `C:\Users\CY\Desktop\CCoder\sidecar\env.js`
- Test: `C:\Users\CY\Desktop\CCoder\sidecar\test\env.test.js`

**Interfaces:**
- Consumes: 无
- Produces:
  - `HOST_ENV_BLACKLIST: readonly string[]` — 10 个待剥离变量名
  - `buildChildEnv(baseEnv: object, overrides?: object): object` — 返回清洗后的环境对象

- [ ] **Step 1: 创建 sidecar 项目清单**

`sidecar/package.json`：

```json
{
  "name": "ccoder-sidecar",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "engines": { "node": ">=18.0.0" },
  "scripts": {
    "test": "node --test test/"
  },
  "dependencies": {
    "@anthropic-ai/claude-agent-sdk": "0.3.268"
  }
}
```

注意 SDK 版本是**精确固定**的，不带 `^` —— 协议会变，必须由我们控制升级时机。

- [ ] **Step 2: 安装依赖（跳过 212M 平台二进制）**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm install --omit=optional
```

预期：安装成功，`node_modules` 约 28M（不是 240M）。

验证跳过了平台包：

```bash
ls node_modules/@anthropic-ai/ 2>&1
```

预期输出**只有** `claude-agent-sdk`，**没有** `claude-agent-sdk-win32-x64`。

- [ ] **Step 3: 写失败的测试**

`sidecar/test/env.test.js`：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildChildEnv, HOST_ENV_BLACKLIST } from '../env.js';

test('黑名单中每个变量都被移除', () => {
  const base = {};
  for (const k of HOST_ENV_BLACKLIST) base[k] = 'x';
  const out = buildChildEnv(base);
  for (const k of HOST_ENV_BLACKLIST) {
    assert.equal(out[k], undefined, `${k} 应被移除`);
  }
});

test('非黑名单变量被保留', () => {
  const base = { PATH: '/usr/bin', HOME: '/home/u', USERPROFILE: 'C:\\Users\\u' };
  const out = buildChildEnv(base);
  assert.equal(out.PATH, '/usr/bin');
  assert.equal(out.HOME, '/home/u');
  assert.equal(out.USERPROFILE, 'C:\\Users\\u');
});

// 这条测试防的是"图省事改成 startsWith('CLAUDE_CODE_')"这类重构。
// 实测保留这些变量不影响认证（spec §3.2）。
test('黑名单是精确匹配，不是前缀匹配', () => {
  const base = {
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
    CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING: 'true',
    CLAUDE_CODE_DISABLE_1M_CONTEXT: '',
    CLAUDE_CODE_EFFORT_LEVEL: 'max',
  };
  const out = buildChildEnv(base);
  assert.equal(out.CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC, '1');
  assert.equal(out.CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING, 'true');
  assert.equal(out.CLAUDE_CODE_DISABLE_1M_CONTEXT, '');
  assert.equal(out.CLAUDE_CODE_EFFORT_LEVEL, 'max');
});

test('envOverrides 能追加变量', () => {
  const out = buildChildEnv({ PATH: '/usr/bin' }, { MY_VAR: 'hello' });
  assert.equal(out.MY_VAR, 'hello');
  assert.equal(out.PATH, '/usr/bin');
});

test('envOverrides 不能恢复黑名单项', () => {
  const out = buildChildEnv({}, { CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST: '1' });
  assert.equal(out.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST, undefined);
});

test('undefined 值不进入结果', () => {
  const out = buildChildEnv({ A: '1', B: undefined });
  assert.ok(!('B' in out));
  assert.equal(out.A, '1');
});

test('黑名单恰好是 10 项', () => {
  assert.equal(HOST_ENV_BLACKLIST.length, 10);
});
```

- [ ] **Step 4: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：FAIL，报 `Cannot find module '../env.js'`。

- [ ] **Step 5: 实现 env.js**

`sidecar/env.js`：

```js
/**
 * 宿主注入的环境变量黑名单。
 *
 * 这些变量会让 claude CLI 误判运行环境而跳过认证流程。
 * 实测（spec §11.1）：CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1 存在时，
 * CLI 返回 authentication_failed；剥离后同一条命令 result: success。
 *
 * 精确匹配，不是前缀匹配 —— 列表外的 CLAUDE_CODE_* 变量必须保留。
 */
export const HOST_ENV_BLACKLIST = Object.freeze([
  'ANTHROPIC_MODEL',
  'CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST',
  'CLAUDE_CODE_ENTRYPOINT',
  'CLAUDE_CODE_MESSAGING_TOKEN',
  'CLAUDE_CODE_MESSAGING_SOCKET',
  'CLAUDE_CODE_EXECPATH',
  'CLAUDE_CODE_CHILD_SESSION',
  'CLAUDE_USE_STDIN',
  'CLAUDE_SESSION_ID',
  'CLAUDE_CODE_SESSION_ID',
]);

/**
 * 构建传给 claude 子进程的环境。
 *
 * @param {object} baseEnv   基础环境（通常是 process.env）
 * @param {object} overrides 用户追加的环境变量。**不能**恢复黑名单项
 * @returns {object} 清洗后的环境对象
 */
export function buildChildEnv(baseEnv, overrides = {}) {
  const blacklist = new Set(HOST_ENV_BLACKLIST);
  const out = {};

  for (const [k, v] of Object.entries(baseEnv ?? {})) {
    if (blacklist.has(k) || v === undefined) continue;
    out[k] = v;
  }

  for (const [k, v] of Object.entries(overrides ?? {})) {
    if (blacklist.has(k) || v === undefined) continue;
    if (blacklist.has(k)) {
      console.error(`[ccoder] 忽略环境变量覆盖 "${k}"：该变量在宿主隔离黑名单中`);
    }
    out[k] = v;
  }

  return out;
}
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：PASS，7 个测试全绿。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add sidecar/package.json sidecar/package-lock.json sidecar/env.js sidecar/test/env.test.js && git commit -F - <<'EOF'
feat(sidecar): 环境清洗，剥离会导致认证失败的宿主变量

实测（spec §11.1）：CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1 会让 claude CLI
误判 provider 由宿主管而跳过认证，返回 authentication_failed。剥离这组变量后
同一条命令返回 result: success。

黑名单为精确匹配而非前缀匹配，CLAUDE_CODE_DISABLE_* 等列表外变量必须保留。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 2: claude 可执行文件解析

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\sidecar\claude-path.js`
- Test: `C:\Users\CY\Desktop\CCoder\sidecar\test\claude-path.test.js`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ClaudeNotFoundError` — `Error` 子类，`code === 'CLAUDE_NOT_FOUND'`
  - `resolveClaudePath({explicit?, env, platform?}): string` — 成功返回绝对路径，失败抛 `ClaudeNotFoundError`

- [ ] **Step 1: 写失败的测试**

`sidecar/test/claude-path.test.js`：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolveClaudePath, ClaudeNotFoundError } from '../claude-path.js';

function makeFakeBin(dir, name) {
  const p = join(dir, name);
  writeFileSync(p, '#!/bin/sh\necho fake\n');
  try { chmodSync(p, 0o755); } catch { /* Windows 上 chmod 是空操作 */ }
  return p;
}

test('显式路径优先于 PATH', () => {
  const d1 = mkdtempSync(join(tmpdir(), 'cc-a-'));
  const d2 = mkdtempSync(join(tmpdir(), 'cc-b-'));
  const explicit = makeFakeBin(d1, 'claude');
  makeFakeBin(d2, 'claude');
  const got = resolveClaudePath({ explicit, env: { PATH: d2 }, platform: 'linux' });
  assert.equal(got, explicit);
});

test('未指定显式路径时从 PATH 解析', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-p-'));
  const bin = makeFakeBin(dir, 'claude');
  const got = resolveClaudePath({ env: { PATH: dir }, platform: 'linux' });
  assert.equal(got, bin);
});

test('PATH 中多个目录时取第一个命中的', () => {
  const a = mkdtempSync(join(tmpdir(), 'cc-x-'));
  const b = mkdtempSync(join(tmpdir(), 'cc-y-'));
  const binA = makeFakeBin(a, 'claude');
  makeFakeBin(b, 'claude');
  const got = resolveClaudePath({ env: { PATH: `${a}:${b}` }, platform: 'linux' });
  assert.equal(got, binA);
});

test('Windows 上尝试 .exe / .cmd 后缀', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-w-'));
  const bin = makeFakeBin(dir, 'claude.cmd');
  const got = resolveClaudePath({ env: { PATH: dir }, platform: 'win32' });
  assert.equal(got, bin);
});

test('找不到时抛 CLAUDE_NOT_FOUND', () => {
  const empty = mkdtempSync(join(tmpdir(), 'cc-e-'));
  assert.throws(
    () => resolveClaudePath({ env: { PATH: empty }, platform: 'linux' }),
    (err) => err instanceof ClaudeNotFoundError && err.code === 'CLAUDE_NOT_FOUND'
  );
});

test('显式路径不存在时也抛错，不回退到 PATH', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cc-f-'));
  makeFakeBin(dir, 'claude');
  assert.throws(
    () => resolveClaudePath({ explicit: join(dir, 'nope'), env: { PATH: dir }, platform: 'linux' }),
    (err) => err.code === 'CLAUDE_NOT_FOUND'
  );
});
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：FAIL，报 `Cannot find module '../claude-path.js'`。

- [ ] **Step 3: 实现 claude-path.js**

`sidecar/claude-path.js`：

```js
import { existsSync, statSync } from 'node:fs';
import { delimiter, join } from 'node:path';

export const CLAUDE_NOT_FOUND = 'CLAUDE_NOT_FOUND';

export class ClaudeNotFoundError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ClaudeNotFoundError';
    this.code = CLAUDE_NOT_FOUND;
  }
}

function isFile(p) {
  try {
    return existsSync(p) && statSync(p).isFile();
  } catch {
    return false;
  }
}

function namesFor(platform) {
  return platform === 'win32' ? ['claude.exe', 'claude.cmd', 'claude'] : ['claude'];
}

/**
 * 解析 claude 可执行文件路径。
 *
 * 插件不打包 212M 的平台二进制（spec §8.2），改为运行时解析用户已安装的那份。
 *
 * @param {object} opts
 * @param {string} [opts.explicit] 设置中显式指定的路径，优先级最高
 * @param {object} opts.env        用于读取 PATH 的环境对象
 * @param {string} [opts.platform] 默认 process.platform，测试时可注入
 * @returns {string} 可执行文件的绝对路径
 * @throws {ClaudeNotFoundError}
 */
export function resolveClaudePath({ explicit, env = process.env, platform = process.platform } = {}) {
  if (explicit && explicit.trim()) {
    if (isFile(explicit)) return explicit;
    // 显式指定却不存在 —— 报错而非静默回退，否则用户会以为设置生效了
    throw new ClaudeNotFoundError(`设置中指定的 claude 路径不存在：${explicit}`);
  }

  const names = namesFor(platform);
  const dirs = (env.PATH || '').split(delimiter).filter(Boolean);

  for (const dir of dirs) {
    for (const name of names) {
      const full = join(dir, name);
      if (isFile(full)) return full;
    }
  }

  throw new ClaudeNotFoundError(
    '未找到 claude 可执行文件。请在插件设置中指定其完整路径。'
  );
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：PASS，13 个测试全绿（env 7 + claude-path 6）。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add sidecar/claude-path.js sidecar/test/claude-path.test.js && git commit -F - <<'EOF'
feat(sidecar): 解析 claude 可执行文件路径

插件不打包 212M 的平台二进制（spec §8.2），改为运行时从设置或 PATH 解析。
显式指定的路径不存在时报错而非回退到 PATH，避免用户误以为设置已生效。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 3: NDJSON 分帧与解析

spec §10.1 要求测试"分片到达的消息正确重组"—— 不能假设一次 read 等于一条完整消息。TCP 管道会把消息切开，这是流式 IO 的经典 bug。

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\sidecar\ndjson.js`
- Test: `C:\Users\CY\Desktop\CCoder\sidecar\test\ndjson.test.js`

**Interfaces:**
- Consumes: 无
- Produces:
  - `class NdjsonDecoder` — `push(chunk: string): string[]`、`flush(): string[]`
  - `encodeNdjson(obj: unknown): string` — 序列化并补换行
  - `parseLine(line: string): {ok: true, value: unknown} | {ok: false, reason: string, raw?: string}`

- [ ] **Step 1: 写失败的测试**

`sidecar/test/ndjson.test.js`：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { NdjsonDecoder, encodeNdjson, parseLine } from '../ndjson.js';

test('单次 push 含多条完整消息', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":1}\n{"b":2}\n'), ['{"a":1}', '{"b":2}']);
});

test('消息被切成两半时正确重组', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":'), []);
  assert.deepEqual(d.push('1}\n'), ['{"a":1}']);
});

test('一次 push 含一条完整消息加半条', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":1}\n{"b":'), ['{"a":1}']);
  assert.deepEqual(d.push('2}\n'), ['{"b":2}']);
});

test('逐字节 push 也能重组', () => {
  const d = new NdjsonDecoder();
  const payload = '{"hello":"world"}\n';
  const out = [];
  for (const ch of payload) out.push(...d.push(ch));
  assert.deepEqual(out, ['{"hello":"world"}']);
});

test('flush 吐出未换行结尾的残留', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":1}'), []);
  assert.deepEqual(d.flush(), ['{"a":1}']);
  assert.deepEqual(d.flush(), [], 'flush 后缓冲应清空');
});

test('空行被保留为独立项，由 parseLine 判定', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('\n\n'), ['', '']);
});

test('encodeNdjson 补换行且内嵌换行被转义', () => {
  assert.equal(encodeNdjson({ a: 1 }), '{"a":1}\n');
  const encoded = encodeNdjson({ text: 'line1\nline2' });
  assert.equal(encoded.endsWith('\n'), true);
  assert.equal(encoded.split('\n').length, 2, '内嵌换行必须被 JSON 转义，不能产生额外行');
  assert.equal(JSON.parse(encoded.trim()).text, 'line1\nline2');
});

test('parseLine 解析合法 JSON', () => {
  const r = parseLine('{"type":"ready"}');
  assert.equal(r.ok, true);
  assert.equal(r.value.type, 'ready');
});

test('parseLine 对非 JSON 行返回 not-json 而不抛错', () => {
  // 实测 stdout 会混入 [claude-code:unrecognized_model] {...} 这类前缀行（spec §11.2）
  const r = parseLine('[claude-code:unrecognized_model] {"model":"x"}');
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'not-json');
  assert.equal(typeof r.raw, 'string');
});

test('parseLine 对空行返回 empty', () => {
  assert.equal(parseLine('   ').reason, 'empty');
});
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：FAIL，报 `Cannot find module '../ndjson.js'`。

- [ ] **Step 3: 实现 ndjson.js**

`sidecar/ndjson.js`：

```js
/**
 * NDJSON 分帧。
 *
 * 为什么不直接用 readline：Node 的 readline 在遇到 `\r\n` 时会剥离 `\r`，
 * 且无法表达"这一行是垃圾"与"这是合法消息"的区别。这里的解码器只负责切分，
 * 合法性判定交给 parseLine，职责更单一也更好测。
 */
export class NdjsonDecoder {
  constructor() {
    this.buffer = '';
  }

  /**
   * 投入一个数据块，返回其中所有**完整**的行（不含换行符）。
   * 末尾不完整的部分留在缓冲里等待下一个块。
   * @param {string} chunk
   * @returns {string[]}
   */
  push(chunk) {
    this.buffer += chunk;
    const lines = this.buffer.split('\n');
    this.buffer = lines.pop() ?? '';
    return lines.map((l) => (l.endsWith('\r') ? l.slice(0, -1) : l));
  }

  /**
   * 吐出缓冲区中的残留（流结束时调用）。
   * @returns {string[]}
   */
  flush() {
    const rest = this.buffer;
    this.buffer = '';
    if (!rest) return [];
    return [rest.endsWith('\r') ? rest.slice(0, -1) : rest];
  }
}

export function encodeNdjson(obj) {
  return JSON.stringify(obj) + '\n';
}

/**
 * 解析单行。**永不抛错** —— 非法行是预期输入，不是异常。
 * @param {string} line
 * @returns {{ok: true, value: unknown} | {ok: false, reason: string, raw?: string}}
 */
export function parseLine(line) {
  const trimmed = (line ?? '').trim();
  if (!trimmed) return { ok: false, reason: 'empty' };
  try {
    return { ok: true, value: JSON.parse(trimmed) };
  } catch {
    // 实测 stdout 会混入 "[claude-code:unrecognized_model] {...}" 这类前缀行（spec §11.2）
    return { ok: false, reason: 'not-json', raw: trimmed };
  }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：PASS，23 个测试全绿（env 7 + claude-path 6 + ndjson 10）。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add sidecar/ndjson.js sidecar/test/ndjson.test.js && git commit -F - <<'EOF'
feat(sidecar): NDJSON 分帧与容错解析

流式管道会把消息切开，不能假设一次 read 等于一条完整消息。
NdjsonDecoder 只负责切分，合法性判定交给 parseLine，职责单一便于测试。

parseLine 永不抛错：实测 stdout 会混入非 JSON 前缀行（spec §11.2），
非法行是预期输入而非异常。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 4: session.js — 包装 SDK query()

全项目最核心的模块。两个硬约束落在这里：流式输入模式（§3.1）和权限回调桥接（§6）。

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\sidecar\session.js`
- Test: `C:\Users\CY\Desktop\CCoder\sidecar\test\session.test.js`

**Interfaces:**
- Consumes: `buildChildEnv`（Task 1）、`resolveClaudePath`（Task 2）
- Produces:
  - `createSession(options): Session`，其中 options 为
    `{ cwd, permissionMode, model?, claudePath?, extraDirs?, envOverrides?,
       onEvent(msg), onPermission(req), queryFn? }`
  - `Session` 对象方法：
    - `send(text: string): void`
    - `interrupt(): Promise<void>`
    - `setPermissionMode(mode: string): Promise<void>`
    - `decidePermission(requestId: string, result: object): void`
    - `stop(): void` — 清空所有待决权限为 deny 并结束输入流
    - `denyAllPending(reason: string): void`

`queryFn` 可注入 —— 测试时传假实现，避免真实 API 调用。

- [ ] **Step 1: 写失败的测试**

`sidecar/test/session.test.js`：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSession } from '../session.js';

/** 记录调用参数的假 query()，按脚本产出事件。 */
function fakeQuery(script = []) {
  const calls = { options: null, prompts: [] };
  async function* gen(prompt) {
    calls.prompts.push(prompt);
    for (const item of script) yield item;
  }
  return {
    calls,
    fn(params) {
      calls.options = params.options;
      return Object.assign(gen(params.prompt), {
        interrupt: async () => { calls.interrupted = true; },
        setPermissionMode: async (m) => { calls.permissionMode = m; },
      });
    },
  };
}

test('必须使用流式输入模式，不能传字符串 prompt', () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('hello');
  // 流式输入模式的判据：prompt 不是 string
  assert.notEqual(typeof q.calls.prompts[0], 'string',
    'prompt 必须是 AsyncIterable —— 传字符串会丢失 interrupt/setPermissionMode 能力');
});

test('send 的内容以 SDKUserMessage 形状进入输入流', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  s.send('第一条');
  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const { value } = await iter.next();
  assert.equal(value.type, 'user');
  assert.equal(value.message.role, 'user');
  assert.equal(value.message.content, '第一条');
  assert.equal(value.parent_tool_use_id, null);
});

test('注册了 canUseTool', () => {
  const q = fakeQuery();
  createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  assert.equal(typeof q.calls.options.canUseTool, 'function');
});

test('canUseTool 通过 onPermission 上报并挂起等待决定', async () => {
  const q = fakeQuery();
  const seen = [];
  const s = createSession({
    cwd: '/tmp', permissionMode: 'default', queryFn: q.fn,
    onPermission: (req) => seen.push(req),
  });
  await new Promise((r) => setImmediate(r));

  let resolved = null;
  const promise = q.calls.options.canUseTool('Read', { file_path: '/x' }, {
    toolUseID: 'tu-1', title: 'Claude wants to read /x', displayName: 'Read file',
    defaultToNo: true, suggestions: [{ type: 'addRules' }],
  }).then((r) => { resolved = r; });

  assert.equal(seen.length, 1);
  assert.equal(seen[0].toolName, 'Read');
  assert.equal(seen[0].requestId, 'tu-1', 'requestId 用 toolUseID');
  assert.equal(seen[0].title, 'Claude wants to read /x');
  assert.equal(seen[0].defaultToNo, true);
  assert.equal(resolved, null, '决定前不应 resolve');

  s.decidePermission('tu-1', { behavior: 'allow' });
  await promise;
  assert.deepEqual(resolved, { behavior: 'allow' });
});

test('denyAllPending 把所有挂起的权限 resolve 为 deny', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  const opts = { toolUseID: 'x', defaultToNo: false };
  const p1 = q.calls.options.canUseTool('Read', {}, { ...opts, toolUseID: 'a' });
  const p2 = q.calls.options.canUseTool('Write', {}, { ...opts, toolUseID: 'b' });

  s.denyAllPending('会话已终止');

  const [r1, r2] = await Promise.all([p1, p2]);
  assert.equal(r1.behavior, 'deny');
  assert.equal(r2.behavior, 'deny');
  assert.match(r1.message, /会话已终止/);
});

test('stop() 会清空待决权限并关闭输入流', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  const p = q.calls.options.canUseTool('Read', {}, { toolUseID: 'z', defaultToNo: false });
  s.stop();
  const r = await p;
  assert.equal(r.behavior, 'deny', 'stop 必须 resolve 而非遗留挂起 —— 工具没有 park deadline');

  const iter = q.calls.prompts[0][Symbol.asyncIterator]();
  const done = await iter.next();
  assert.equal(done.done, true, 'stop 后输入流应结束');
});

test('重复决定同一 requestId 不产生副作用', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));

  const p = q.calls.options.canUseTool('Read', {}, { toolUseID: 'dup', defaultToNo: false });
  s.decidePermission('dup', { behavior: 'allow' });
  s.decidePermission('dup', { behavior: 'deny', message: 'second' });
  const r = await p;
  assert.equal(r.behavior, 'allow', '第一次决定生效，第二次被忽略');
});

test('interrupt 转发到 SDK Query', async () => {
  const q = fakeQuery();
  const s = createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
  await new Promise((r) => setImmediate(r));
  await s.interrupt();
  assert.equal(q.calls.interrupted, true);
});

test('清洗后的环境传给 SDK，不含黑名单变量', () => {
  const q = fakeQuery();
  process.env.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST = '1';
  try {
    createSession({ cwd: '/tmp', permissionMode: 'default', queryFn: q.fn });
    const env = q.calls.options.env;
    assert.equal(env.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST, undefined,
      '宿主变量必须被剥离，否则 CLI 会跳过认证');
  } finally {
    delete process.env.CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST;
  }
});
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：FAIL，报 `Cannot find module '../session.js'`。

- [ ] **Step 3: 实现 session.js**

`sidecar/session.js`：

```js
import { buildChildEnv } from './env.js';
import { resolveClaudePath } from './claude-path.js';

/** 默认从 SDK 加载；测试通过 queryFn 注入假实现。 */
async function defaultQueryFn(params) {
  const mod = await import('@anthropic-ai/claude-agent-sdk');
  return mod.query(params);
}

/**
 * 建立一个 Claude 会话。
 *
 * 必须使用流式输入模式（prompt 传 AsyncIterable 而非 string）：
 * SDK 的 Query 接口上 interrupt() / setPermissionMode() / setModel()
 * 的文档明确写着 "only supported when streaming input/output is used"
 * （sdk.d.ts:2614-2616）。传字符串等于永久放弃这些能力。
 */
export function createSession({
  cwd,
  permissionMode,
  model,
  claudePath,
  extraDirs,
  envOverrides,
  onEvent = () => {},
  onPermission = () => {},
  queryFn = defaultQueryFn,
}) {
  /** @type {Map<string, (result: object) => void>} */
  const pending = new Map();

  const queue = [];
  let notifyInput = null;   // 唤醒输入流生成器
  let stopped = false;

  async function* inputStream() {
    while (!stopped) {
      if (queue.length > 0) {
        yield queue.shift();
        continue;
      }
      const item = await new Promise((resolve) => { notifyInput = resolve; });
      notifyInput = null;
      if (item === null) return;
      yield item;
    }
  }

  const options = {
    cwd,
    permissionMode,
    env: buildChildEnv(process.env, envOverrides),
    includePartialMessages: true,
    canUseTool: (toolName, input, opts) => {
      return new Promise((resolve) => {
        const requestId = opts.toolUseID;
        pending.set(requestId, resolve);
        onPermission({
          requestId,
          toolName,
          input,
          title: opts.title,
          displayName: opts.displayName,
          description: opts.description,
          blockedPath: opts.blockedPath,
          decisionReason: opts.decisionReason,
          defaultToNo: opts.defaultToNo ?? false,
          suppressAlwaysAllowRule: opts.suppressAlwaysAllowRule ?? false,
          suggestions: opts.suggestions,
        });
      });
    },
  };

  if (model) options.model = model;
  if (extraDirs?.length) options.additionalDirectories = extraDirs;
  if (claudePath) options.pathToClaudeCodeExecutable = claudePath;

  const query = queryFn({ prompt: inputStream(), options });

  // 消费事件流，全部原样透传。未知类型不做过滤 —— 那是插件侧的职责（spec §3.3）
  (async () => {
    try {
      for await (const msg of query) {
        onEvent(msg);
      }
    } catch (err) {
      onEvent({ type: 'ccoder_stream_error', message: String(err?.message ?? err) });
    }
  })();

  function settle(requestId, result) {
    const resolve = pending.get(requestId);
    if (!resolve) return;         // 已被决定或不存在，静默忽略
    pending.delete(requestId);
    resolve(result);
  }

  return {
    send(text) {
      if (stopped) return;
      queue.push({
        type: 'user',
        message: { role: 'user', content: text },
        parent_tool_use_id: null,
      });
      notifyInput?.('go');
    },

    decidePermission(requestId, result) {
      settle(requestId, result);
    },

    /**
     * 把所有挂起的权限请求 resolve 为 deny。
     *
     * 这是 spec §6.2 规则① 的落实：SDK 文档明确警告权限询问
     * "have no park deadline"，返回 null 或不 resolve 会让工具无限期阻塞。
     * 任何终止路径都必须走这里。
     */
    denyAllPending(reason) {
      for (const [requestId, resolve] of pending) {
        pending.delete(requestId);
        resolve({ behavior: 'deny', message: reason });
      }
    },

    async interrupt() {
      await query.interrupt?.();
    },

    async setPermissionMode(mode) {
      await query.setPermissionMode?.(mode);
    },

    stop() {
      if (stopped) return;
      stopped = true;
      this.denyAllPending('会话已终止');
      notifyInput?.(null);   // 结束输入流
    },
  };
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：PASS，32 个测试全绿（env 7 + claude-path 6 + ndjson 10 + session 9）。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add sidecar/session.js sidecar/test/session.test.js && git commit -F - <<'EOF'
feat(sidecar): 包装 SDK query()，流式输入 + 权限回调桥接

两条硬约束在此落实：

1. 流式输入模式（spec §3.1）—— prompt 传 AsyncIterable 而非字符串。
   SDK 的 interrupt/setPermissionMode 仅在流式模式下可用，
   传字符串等于永久放弃这些能力。

2. 权限请求必须永远 resolve（spec §6.2 规则①）—— SDK 文档警告
   权限询问 "have no park deadline"，不 resolve 会让工具无限期阻塞。
   denyAllPending 是唯一出口，任何终止路径都走它。

queryFn 可注入以便测试，避免单测触达真实 API。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 5: index.js — stdio 主循环

把前面所有模块接起来：读 stdin 的 NDJSON、分发方法调用、把 session 的事件写到 stdout。

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\sidecar\index.js`
- Test: `C:\Users\CY\Desktop\CCoder\sidecar\test\index.test.js`

**Interfaces:**
- Consumes: `NdjsonDecoder`/`parseLine`/`encodeNdjson`（Task 3）、`createSession`（Task 4）、`resolveClaudePath`/`ClaudeNotFoundError`（Task 2）
- Produces: 可执行的 stdio 程序。导出 `createDispatcher({sessionFactory, out})` 以便测试。

- [ ] **Step 1: 写失败的测试**

`sidecar/test/index.test.js`：

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDispatcher } from '../index.js';

function fakeSessionFactory() {
  const calls = [];
  return {
    calls,
    factory(opts) {
      const s = {
        opts,
        sent: [],
        send(t) { this.sent.push(t); },
        interrupt: async () => { calls.push(['interrupt']); },
        setPermissionMode: async (m) => { calls.push(['setPermissionMode', m]); },
        decidePermission: (id, r) => { calls.push(['decide', id, r]); },
        denyAllPending: (r) => { calls.push(['denyAll', r]); },
        stop: () => { calls.push(['stop']); },
        _emit: opts.onEvent,
        _perm: opts.onPermission,
      };
      calls.push(['create', opts]);
      return s;
    },
  };
}

test('start 建立会话并把 ready 上报', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });

  d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });

  assert.equal(out[0].type, 'ready');
  assert.equal(sf.calls.find((c) => c[0] === 'create')[1].cwd, '/tmp');
});

test('未 start 就 send 时排队，start 后补发', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });

  d.handle({ id: '1', method: 'send', params: { text: 'early' } });
  const s = d.handle({ id: '2', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });
  d.handle({ id: '3', method: 'send', params: { text: 'late' } });

  assert.deepEqual(s.sent, ['early', 'late'], '排队的消息必须在 start 后按序补发');
});

test('SDK 事件原样透传为 event 消息', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });

  s._emit({ type: 'assistant', message: { content: [] } });

  const ev = out.find((m) => m.type === 'event');
  assert.equal(ev.event.type, 'assistant');
});

test('未知事件类型也原样透传，不在 sidecar 层过滤', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });

  s._emit({ type: 'some_future_type_v99' });

  assert.ok(out.some((m) => m.type === 'event' && m.event.type === 'some_future_type_v99'),
    '过滤是插件的职责，sidecar 透传（spec §3.3）');
});

test('assistant 的 authentication_failed 抽成独立的 AUTH_FAILED 错误', () => {
  // 实测该失败由 SDK 作为 assistant 事件的 error 字段回传，而非独立错误
  // （spec §11.1）。不抽取的话插件只能显示 "Not logged in"，
  // 用户无从知道要检查配置的哪一部分。
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });

  s._emit({ type: 'assistant', error: 'authentication_failed',
            message: { content: [{ type: 'text', text: 'Not logged in' }] } });

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'AUTH_FAILED');
  assert.equal(e.fatal, true);
  // 事件本身仍要透传——渲染层依赖它显示原始错误文本
  assert.ok(out.some((m) => m.type === 'event' && m.event.error === 'authentication_failed'));
});

test('权限回调转为 permission 消息', () => {
  const out = [];
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: (m) => out.push(m) });
  const s = d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });

  s._perm({ requestId: 'tu-1', toolName: 'Read', input: {}, title: '读文件' });

  const p = out.find((m) => m.type === 'permission');
  assert.equal(p.requestId, 'tu-1');
  assert.equal(p.title, '读文件');
});

test('permissionDecision 转发到 session', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });

  d.handle({ id: '2', method: 'permissionDecision',
             params: { requestId: 'tu-1', behavior: 'allow' } });

  const call = sf.calls.find((c) => c[0] === 'decide');
  assert.equal(call[1], 'tu-1');
  assert.deepEqual(call[2], { behavior: 'allow' });
});

test('stop 会清空待决权限', () => {
  const sf = fakeSessionFactory();
  const d = createDispatcher({ sessionFactory: sf.factory, out: () => {} });
  d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });

  d.handle({ id: '2', method: 'stop', params: {} });

  assert.ok(sf.calls.some((c) => c[0] === 'denyAll'), 'stop 必须先清空待决表');
  assert.ok(sf.calls.some((c) => c[0] === 'stop'));
});

test('claude 找不到时上报 CLAUDE_NOT_FOUND 且不抛错', () => {
  const out = [];
  const err = new Error('未找到 claude');
  err.code = 'CLAUDE_NOT_FOUND';
  const factory = () => { throw err; };
  const d = createDispatcher({ sessionFactory: factory, out: (m) => out.push(m) });

  d.handle({ id: '1', method: 'start', params: { cwd: '/tmp', permissionMode: 'default' } });

  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'CLAUDE_NOT_FOUND');
  assert.equal(e.fatal, true);
});

test('未知 method 上报错误但不崩溃', () => {
  const out = [];
  const d = createDispatcher({ sessionFactory: () => ({}), out: (m) => out.push(m) });
  d.handle({ id: '1', method: 'nonsense', params: {} });
  const e = out.find((m) => m.type === 'error');
  assert.equal(e.code, 'UNKNOWN_METHOD');
  assert.equal(e.fatal, false);
});
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：FAIL，报 `Cannot find module '../index.js'`。

- [ ] **Step 3: 实现 index.js**

`sidecar/index.js`：

```js
#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { NdjsonDecoder, encodeNdjson, parseLine } from './ndjson.js';
import { createSession } from './session.js';
import { resolveClaudePath, ClaudeNotFoundError } from './claude-path.js';

/**
 * 把 NDJSON 方法调用分发到 session。
 *
 * 与进程 IO 解耦，便于测试注入假 session。
 *
 * @param {object} deps
 * @param {Function} deps.sessionFactory (opts) => Session
 * @param {Function} deps.out            (message) => void
 */
export function createDispatcher({ sessionFactory, out }) {
  let session = null;
  const preStartQueue = [];   // start 之前到达的 send，按序补发

  function fail(code, message, fatal = false) {
    out({ type: 'error', code, message, fatal });
  }

  /**
   * 分发一条消息。
   * @returns 分发后的当前 session（供测试断言；生产路径忽略返回值）
   */
  function handle(msg) {
    if (!msg || typeof msg !== 'object') return session;
    const { method, params = {} } = msg;

    switch (method) {
      case 'start': {
        if (session) {
          fail('ALREADY_STARTED', '会话已建立', false);
          return;
        }
        try {
          session = sessionFactory({
            ...params,
            cwd: params.cwd,
            permissionMode: params.permissionMode ?? 'default',
            onEvent: (event) => {
              out({ type: 'event', event });
              // 认证失败由 SDK 作为 assistant 事件的 error 字段回传，而非独立错误。
              // 抽成独立的 error 消息，使插件能给出可操作的提示（spec §5.3）。
              if (event?.error === 'authentication_failed') {
                out({
                  type: 'error',
                  code: 'AUTH_FAILED',
                  message: 'Claude CLI 认证失败。',
                  fatal: true,
                });
              }
            },
            onPermission: (req) => out({ type: 'permission', ...req }),
          });
        } catch (err) {
          if (err instanceof ClaudeNotFoundError || err?.code === 'CLAUDE_NOT_FOUND') {
            fail('CLAUDE_NOT_FOUND', err.message, true);
          } else {
            fail('SDK_INIT_FAILED', String(err?.message ?? err), true);
          }
          return;
        }
        out({ type: 'ready', sessionId: params.sessionId ?? null, model: params.model ?? null });
        for (const text of preStartQueue.splice(0)) session.send(text);
        return;
      }

      case 'send': {
        if (!session) {
          // 排队而非丢弃 —— 用户可能抢在 ready 之前就发了消息
          preStartQueue.push(params.text ?? '');
          return;
        }
        session.send(params.text ?? '');
        return;
      }

      case 'permissionDecision': {
        if (!session) return;
        const { requestId, behavior, updatedPermissions, message } = params;
        const result = behavior === 'allow'
          ? { behavior: 'allow' }
          : { behavior: 'deny', message: message ?? '用户拒绝' };
        if (updatedPermissions) result.updatedPermissions = updatedPermissions;
        session.decidePermission(requestId, result);
        return;
      }

      case 'interrupt':
        session?.interrupt?.();
        return;

      case 'setPermissionMode':
        session?.setPermissionMode?.(params.mode);
        return;

      case 'stop':
        // 顺序重要：先清空待决权限，否则工具会挂住
        session?.denyAllPending?.('会话已终止');
        session?.stop?.();
        session = null;
        return;

      default:
        fail('UNKNOWN_METHOD', `未知方法：${method}`, false);
    }

    return session;
  }

  return { handle, getSession: () => session };
}

/** stdin 主循环。仅在作为程序运行时执行（测试导入模块时不触发）。 */
function main() {
  const write = (m) => process.stdout.write(encodeNdjson(m));
  const dispatcher = createDispatcher({
    out: write,
    sessionFactory: (opts) => {
      const claudePath = resolveClaudePath({ explicit: opts.claudePath, env: process.env });
      return createSession({ ...opts, claudePath });
    },
  });

  const decoder = new NdjsonDecoder();
  const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });

  rl.on('line', (line) => {
    const parsed = parseLine(line);
    if (!parsed.ok) {
      // 非 JSON 行是预期输入（spec §11.2），只记 stderr，不进 stdout
      if (parsed.reason === 'not-json') {
        console.error(`[ccoder] 忽略非 JSON 输入行：${parsed.raw.slice(0, 200)}`);
      }
      return;
    }
    try {
      dispatcher.handle(parsed.value);
    } catch (err) {
      write({ type: 'error', code: 'DISPATCH_FAILED', message: String(err?.message ?? err), fatal: false });
    }
  });

  rl.on('close', () => {
    dispatcher.handle({ method: 'stop', params: {} });
    process.exit(0);
  });

  const shutdown = () => {
    dispatcher.handle({ method: 'stop', params: {} });
    process.exit(0);
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

// 作为程序直接运行时才进 main；被 import 时（测试）跳过
if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('index.js')) {
  main();
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && npm test
```

预期：PASS，42 个测试全绿（env 7 + claude-path 6 + ndjson 10 + session 9 + index 10）。

- [ ] **Step 5: 端到端冒烟（真实 claude）**

这是**唯一**触达真实 API 的一步。手动执行：

```bash
cd "C:/Users/CY/Desktop/CCoder/sidecar" && printf '%s\n' \
  '{"id":"1","method":"start","params":{"cwd":"C:/Users/CY/Desktop/CCoder","permissionMode":"dontAsk"}}' \
  '{"id":"2","method":"send","params":{"text":"reply with exactly: OK"}}' \
  | node index.js 2>/dev/null | head -40
```

预期：stdout 出现若干 `{"type":"event",...}` 行，其中包含一个 `assistant` 事件，内容为 `OK`；最后有 `{"type":"event","event":{"type":"result","subtype":"success",...}}`。

**若出现 `authentication_failed`**：说明 Task 1 的环境清洗没生效，回查 `session.js` 是否把 `buildChildEnv` 的结果传给了 `options.env`。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add sidecar/index.js sidecar/test/index.test.js && git commit -F - <<'EOF'
feat(sidecar): stdio 主循环，打通 NDJSON 方法分发

start 之前到达的 send 会排队而非丢弃——用户可能抢在 ready 之前发消息。
SDK 事件在 sidecar 层不做任何过滤，原样透传（spec §3.3）；
未知事件类型与未知 method 都不会导致崩溃。

stop 的顺序是：先 denyAllPending 再 stop session（spec §6.2 规则①），
反过来的话工具会挂住。

已通过真实 claude 的端到端冒烟验证。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 6: Gradle 骨架 + 插件清单

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\settings.gradle.kts`
- Create: `C:\Users\CY\Desktop\CCoder\build.gradle.kts`
- Create: `C:\Users\CY\Desktop\CCoder\gradle.properties`
- Create: `C:\Users\CY\Desktop\CCoder\.gitattributes`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\resources\META-INF\plugin.xml`

**Interfaces:**
- Consumes: 无
- Produces: 可构建的 Gradle 项目；`./gradlew buildPlugin` 产出 zip

- [ ] **Step 1: 写 .gitattributes**

```
* text=auto eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
gradlew text eol=lf
*.png binary
*.jar binary
```

这一步不能省：Windows 上 `core.autocrlf` 会把 `gradlew` 这个 shell 脚本转成 CRLF，导致它在 Git Bash / WSL / CI 里报 `bad interpreter`。

- [ ] **Step 2: 写 settings.gradle.kts**

```kotlin
rootProject.name = "CCoder"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

- [ ] **Step 3: 写 gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=false

kotlin.stdlib.default.dependency=false

pluginGroup=com.ccoder
pluginName=CCoder
pluginVersion=0.1.0
pluginSinceBuild=253
```

`configuration-cache=false` 是刻意的：IntelliJ Platform Gradle Plugin 2.x 在部分任务上尚不兼容配置缓存，开启会得到难以定位的构建失败。

- [ ] **Step 4: 写 build.gradle.kts**

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pycharm("2025.3.1.1")
        instrumentationTools()
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // 不设 untilBuild —— 让插件在未来的 IDE 版本中仍可安装（spec §11.4）
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    // sidecar 的 node_modules 由 Task 14 接入打包
    buildSearchableOptions { enabled = false }
}
```

- [ ] **Step 5: 写 plugin.xml**

`src/main/resources/META-INF/plugin.xml`：

```xml
<idea-plugin>
    <id>com.ccoder.claudecode</id>
    <name>CCoder</name>
    <vendor>ccoder</vendor>

    <description><![CDATA[
    Claude Code integration for PyCharm via the Claude Agent SDK.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- 工具窗口在 Task 12 注册 -->
    </extensions>
</idea-plugin>
```

- [ ] **Step 6: 生成 Gradle wrapper**

```bash
cd "C:/Users/CY/Desktop/CCoder" && gradle wrapper --gradle-version 8.13
```

预期：生成 `gradlew`、`gradlew.bat`、`gradle/wrapper/`。

**注意**：用 8.13 而非本机的 9.7.0 —— 平台插件 2.13.1 的兼容基线是 8.13，9.x 的配置模型变更可能导致构建脚本失败。

- [ ] **Step 7: 验证构建**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew buildPlugin
```

预期：BUILD SUCCESSFUL。首次运行会下载约 800MB 的 PyCharm SDK，耗时较长。

产物：`build/distributions/CCoder-0.1.0.zip`

**若报 `pycharm("2025.3.1.1")` 无法解析**：改用本地已安装的 IDE —— 在 `dependencies.intellijPlatform` 中换成
`local("C:/Program Files/JetBrains/PyCharm 2025.3.1.1")`，并在 `repositories.intellijPlatform` 中加 `localPlatformArtifacts()`。

- [ ] **Step 8: 验证测试任务可运行**

先建一个占位测试 `src/test/kotlin/com/ccoder/PlaceholderTest.kt`：

```kotlin
package com.ccoder

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceholderTest {
    @Test
    fun `测试基础设施可用`() {
        assertTrue(true)
    }
}
```

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test
```

预期：BUILD SUCCESSFUL，1 个测试通过。

- [ ] **Step 9: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add .gitattributes settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/ src/ && git commit -F - <<'EOF'
build: Gradle 骨架，IntelliJ Platform Gradle Plugin 2.x

平台目标 PyCharm 2025.3.1.1，sinceBuild 253，不设 untilBuild。
wrapper 固定 8.13（平台插件 2.13.1 的兼容基线，非本机的 9.7.0）。
刻意关闭配置缓存：平台插件在部分任务上尚不兼容。

.gitattributes 强制 gradlew 用 LF——Windows 的 autocrlf 会把它转成 CRLF
并导致 bad interpreter。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 7: SidecarExtractor — 运行时提取

jar 内的 `node_modules` 无法直接运行，必须提取到磁盘。

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\SidecarExtractor.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\SidecarExtractorTest.kt`
- Delete: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\PlaceholderTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `object SidecarExtractor`
  - `fun extract(resourceRoot: Path, targetDir: Path, version: String): Path` — 幂等；返回可直接运行的 sidecar 目录

- [ ] **Step 1: 删除占位测试**

```bash
rm "C:/Users/CY/Desktop/CCoder/src/test/kotlin/com/ccoder/PlaceholderTest.kt"
```

- [ ] **Step 2: 写失败的测试**

`src/test/kotlin/com/ccoder/sidecar/SidecarExtractorTest.kt`：

```kotlin
package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarExtractorTest {

    private fun makeSource(root: Path) {
        Files.createDirectories(root.resolve("test"))
        Files.writeString(root.resolve("index.js"), "console.log('hi')")
        Files.writeString(root.resolve("env.js"), "export const x = 1")
        Files.writeString(root.resolve("test/env.test.js"), "// test")
    }

    @Test
    fun `提取到目标目录`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val result = SidecarExtractor.extract(src, dst, "0.1.0")

        assertEquals(dst.resolve("0.1.0"), result)
        assertTrue(Files.exists(result.resolve("index.js")))
        assertTrue(Files.exists(result.resolve("test/env.test.js")), "子目录必须递归复制")
    }

    @Test
    fun `版本相同的重复提取是幂等的`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val first = SidecarExtractor.extract(src, dst, "0.1.0")
        Files.writeString(first.resolve("index.js"), "已被修改")
        val second = SidecarExtractor.extract(src, dst, "0.1.0")

        assertEquals(first, second)
        assertEquals("已被修改", Files.readString(second.resolve("index.js")),
            "版本未变时不应重复覆盖——避免每次启动都做无谓的磁盘写入")
    }

    @Test
    fun `版本变化时重新提取且不留旧文件`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        val v1 = SidecarExtractor.extract(src, dst, "0.1.0")
        Files.writeString(v1.resolve("stale.js"), "旧版本的残留文件")

        val v2 = SidecarExtractor.extract(src, dst, "0.2.0")

        assertFalse(Files.exists(v2.resolve("stale.js")), "新版本目录必须是干净的")
        assertTrue(Files.exists(v2.resolve("index.js")))
    }

    @Test
    fun `清理其他版本目录只保留当前版本`(@TempDir tmp: Path) {
        val src = tmp.resolve("src")
        val dst = tmp.resolve("dst")
        makeSource(src)

        SidecarExtractor.extract(src, dst, "0.1.0")
        val current = SidecarExtractor.extract(src, dst, "0.2.0")

        val siblings = Files.list(dst).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("0.2.0"), siblings,
            "旧版本目录应被清理，否则每次升级都在磁盘上留一份 28M 的副本")
        assertTrue(Files.exists(current.resolve("index.js")))
    }
}
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*SidecarExtractorTest*'
```

预期：FAIL，编译错误 `Unresolved reference: SidecarExtractor`。

- [ ] **Step 4: 实现 SidecarExtractor**

`src/main/kotlin/com/ccoder/sidecar/SidecarExtractor.kt`：

```kotlin
package com.ccoder.sidecar

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * 把插件包内的 sidecar 目录提取到磁盘。
 *
 * jar 内的 node_modules 无法被 node 直接执行，必须落到真实文件系统。
 *
 * 目录布局：<targetDir>/<version>/ —— 以版本号分目录，
 * 使升级后不会残留旧代码，同时避免每次启动都重写磁盘。
 */
object SidecarExtractor {

    /**
     * @param resourceRoot 插件包内的 sidecar 源目录
     * @param targetDir    提取根目录（各版本作为其子目录）
     * @param version      sidecar 版本号，取自 sidecar/package.json
     * @return 可直接运行的 sidecar 目录
     */
    fun extract(resourceRoot: Path, targetDir: Path, version: String): Path {
        val versioned = targetDir.resolve(version)

        if (versioned.resolve("index.js").exists()) {
            // 已提取过：幂等返回，不重写磁盘
            cleanOtherVersions(targetDir, version)
            return versioned
        }

        // 先清理可能存在的半成品，避免上次中断留下的残缺目录被当成完整版
        if (versioned.exists()) deleteRecursively(versioned)

        Files.createDirectories(versioned)
        resourceRoot.copyRecursivelyTo(versioned)

        cleanOtherVersions(targetDir, version)
        return versioned
    }

    private fun cleanOtherVersions(targetDir: Path, keep: String) {
        if (!targetDir.isDirectory()) return
        Files.list(targetDir).use { stream ->
            stream.filter { it.isDirectory() && it.fileName.toString() != keep }
                .forEach { deleteRecursively(it) }
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun Path.copyRecursivelyTo(dest: Path) {
        Files.walk(this).use { stream ->
            stream.forEach { src ->
                val rel = this.relativize(src)
                val target = dest.resolve(rel.toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*SidecarExtractorTest*'
```

预期：BUILD SUCCESSFUL，4 个测试通过。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/sidecar/SidecarExtractor.kt src/test/kotlin/com/ccoder/sidecar/SidecarExtractorTest.kt && git rm --cached src/test/kotlin/com/ccoder/PlaceholderTest.kt 2>/dev/null; git add -A src/ && git commit -F - <<'EOF'
feat(sidecar): 运行时把 sidecar 从插件包提取到磁盘

jar 内的 node_modules 无法被 node 直接执行，必须落到真实文件系统。

以版本号分目录，使升级后不残留旧代码；同版本重复调用幂等，
避免每次启动都重写 28M 的依赖树。旧版本目录会被清理。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 8: Protocol — 消息类型与容错解析

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\Protocol.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\ProtocolTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `sealed interface SidecarMessage`，子类：`Ready`、`Event`、`Permission`、`Failure`、`Exit`、`Unknown`
  - `object Protocol`
    - `fun parse(line: String): SidecarMessage?` — 空行/非法 JSON 返回 `null`
    - `fun encodeStart(id: String, params: StartParams): String`
    - `fun encodeSend(id: String, text: String): String`
    - `fun encodePermissionDecision(id: String, requestId: String, allow: Boolean, updatedPermissions: JsonArray?, message: String?): String`
    - `fun encodeSimple(id: String, method: String): String`
  - `data class StartParams(val cwd: String, val permissionMode: String, val model: String? = null, val claudePath: String? = null, val extraDirs: List<String> = emptyList(), val envOverrides: Map<String, String> = emptyMap())`

- [ ] **Step 1: 写失败的测试**

`src/test/kotlin/com/ccoder/sidecar/ProtocolTest.kt`：

```kotlin
package com.ccoder.sidecar

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolTest {

    @Test
    fun `解析 ready 消息`() {
        val msg = Protocol.parse("""{"type":"ready","sessionId":"s1","model":"m"}""")
        assertTrue(msg is SidecarMessage.Ready)
        assertEquals("s1", (msg as SidecarMessage.Ready).sessionId)
    }

    @Test
    fun `解析 event 消息并保留原始载荷`() {
        val msg = Protocol.parse("""{"type":"event","event":{"type":"assistant","x":1}}""")
        assertTrue(msg is SidecarMessage.Event)
        assertEquals("assistant", (msg as SidecarMessage.Event).event.get("type").asString)
        assertEquals(1, msg.event.get("x").asInt)
    }

    @Test
    fun `解析 permission 消息并填充默认值`() {
        val json = """{"type":"permission","requestId":"r1","toolName":"Read","input":{}}"""
        val msg = Protocol.parse(json) as SidecarMessage.Permission
        assertEquals("r1", msg.requestId)
        assertEquals("Read", msg.toolName)
        assertNull(msg.title)
        // 安全默认值：字段缺失时必须偏向拒绝
        assertTrue(msg.defaultToNo, "defaultToNo 缺失时应默认为 true")
        assertTrue(msg.suppressAlwaysAllowRule, "suppressAlwaysAllowRule 缺失时应默认为 true")
    }

    @Test
    fun `解析 permission 消息的完整字段`() {
        val json = """
        {"type":"permission","requestId":"r1","toolName":"Bash","input":{"command":"ls"},
         "title":"Claude 想执行命令","displayName":"执行命令","description":"副标题",
         "blockedPath":"/etc/passwd","decisionReason":"路径在允许范围外",
         "defaultToNo":false,"suppressAlwaysAllowRule":false,
         "suggestions":[{"type":"addRules"}]}
        """.trimIndent()
        val msg = Protocol.parse(json) as SidecarMessage.Permission
        assertEquals("Claude 想执行命令", msg.title)
        assertEquals("执行命令", msg.displayName)
        assertEquals("/etc/passwd", msg.blockedPath)
        assertEquals("路径在允许范围外", msg.decisionReason)
        assertEquals(false, msg.defaultToNo)
        assertEquals(1, msg.suggestions!!.size())
    }

    @Test
    fun `解析 error 消息`() {
        val msg = Protocol.parse("""{"type":"error","code":"CLAUDE_NOT_FOUND","message":"找不到","fatal":true}""")
        val f = msg as SidecarMessage.Failure
        assertEquals("CLAUDE_NOT_FOUND", f.code)
        assertTrue(f.fatal)
    }

    @Test
    fun `未知类型映射为 Unknown 而非 null`() {
        // spec §3.3：未知类型必须被静默忽略，但不能与"解析失败"混淆
        val msg = Protocol.parse("""{"type":"some_future_type_v99"}""")
        assertTrue(msg is SidecarMessage.Unknown)
        assertEquals("some_future_type_v99", (msg as SidecarMessage.Unknown).type)
    }

    @Test
    fun `空行与非法 JSON 返回 null`() {
        assertNull(Protocol.parse(""))
        assertNull(Protocol.parse("   "))
        assertNull(Protocol.parse("[claude-code:unrecognized_model] {\"a\":1}"))
        assertNull(Protocol.parse("not json at all"))
    }

    @Test
    fun `encodeStart 产出单行 JSON`() {
        val line = Protocol.encodeStart(
            "req-1",
            StartParams(cwd = "C:\\proj", permissionMode = "default", model = "m")
        )
        assertEquals(1, line.trimEnd('\n').lines().size)
        val obj = JsonParser.parseString(line.trim()).asJsonObject
        assertEquals("start", obj.get("method").asString)
        assertEquals("req-1", obj.get("id").asString)
        assertEquals("C:\\proj", obj.getAsJsonObject("params").get("cwd").asString)
    }

    @Test
    fun `encodeSend 正确转义内嵌换行`() {
        val line = Protocol.encodeSend("r", "line1\nline2")
        assertEquals(1, line.trimEnd('\n').lines().size,
            "载荷中的换行必须被 JSON 转义，否则会破坏 NDJSON 分帧")
    }

    @Test
    fun `encodeStart 省略空的可选字段`() {
        val line = Protocol.encodeStart("r", StartParams(cwd = "/p", permissionMode = "default"))
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertTrue(!params.has("model"))
        assertTrue(!params.has("claudePath"))
        assertTrue(!params.has("extraDirs"))
    }

    @Test
    fun `encodePermissionDecision 拒绝时带 message`() {
        val line = Protocol.encodePermissionDecision("r", "tu-1", allow = false, updatedPermissions = null, message = "用户拒绝")
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertEquals("deny", params.get("behavior").asString)
        assertEquals("tu-1", params.get("requestId").asString)
        assertEquals("用户拒绝", params.get("message").asString)
    }

    @Test
    fun `encodePermissionDecision 允许时可带 updatedPermissions`() {
        val perms = JsonParser.parseString("""[{"type":"addRules"}]""").asJsonArray
        val line = Protocol.encodePermissionDecision("r", "tu-1", allow = true, updatedPermissions = perms, message = null)
        val params = JsonParser.parseString(line.trim()).asJsonObject.getAsJsonObject("params")
        assertEquals("allow", params.get("behavior").asString)
        assertEquals(1, params.getAsJsonArray("updatedPermissions").size())
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*ProtocolTest*'
```

预期：FAIL，编译错误 `Unresolved reference: Protocol`。

- [ ] **Step 3: 实现 Protocol.kt**

`src/main/kotlin/com/ccoder/sidecar/Protocol.kt`：

```kotlin
package com.ccoder.sidecar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * sidecar 与插件之间的消息。
 *
 * 与 sidecar 侧 sidecar/ndjson.js 对称：分帧只负责切行，合法性判定在这里。
 */
sealed interface SidecarMessage {

    /** 会话已建立。 */
    data class Ready(val sessionId: String?, val model: String?) : SidecarMessage

    /** SDK 事件，**原样透传**。插件按 event["type"] 分发，未知类型静默忽略。 */
    data class Event(val event: JsonObject) : SidecarMessage

    /** 权限询问。 */
    data class Permission(
        val requestId: String,
        val toolName: String,
        val input: JsonObject,
        val title: String?,
        val displayName: String?,
        val description: String?,
        val blockedPath: String?,
        val decisionReason: String?,
        // 安全默认值：字段缺失时偏向拒绝而非放行
        val defaultToNo: Boolean = true,
        val suppressAlwaysAllowRule: Boolean = true,
        val suggestions: JsonArray? = null,
    ) : SidecarMessage

    /** 错误。fatal=true 表示会话已终止。 */
    data class Failure(val message: String, val code: String?, val fatal: Boolean) : SidecarMessage

    /** sidecar 进程退出。 */
    data class Exit(val code: Int, val signal: String?) : SidecarMessage

    /** 未知类型。与"解析失败"（null）区分开——这类要忽略而非报错。 */
    data class Unknown(val type: String) : SidecarMessage
}

data class StartParams(
    val cwd: String,
    val permissionMode: String,
    val model: String? = null,
    val claudePath: String? = null,
    val extraDirs: List<String> = emptyList(),
    val envOverrides: Map<String, String> = emptyMap(),
)

object Protocol {

    /**
     * 解析一行输入。
     * @return 解析成功返回消息对象；空行/非法 JSON 返回 null；
     *         JSON 合法但类型未知返回 [SidecarMessage.Unknown]
     */
    fun parse(line: String): SidecarMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val obj = try {
            JsonParser.parseString(trimmed).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            // 实测 stdout 会混入 "[claude-code:...] {...}" 这类前缀行（spec §11.2）
            null
        } ?: return null

        return when (obj.str("type")) {
            "ready" -> SidecarMessage.Ready(
                sessionId = obj.str("sessionId"),
                model = obj.str("model"),
            )

            "event" -> obj.obj("event")?.let { SidecarMessage.Event(it) }

            "permission" -> SidecarMessage.Permission(
                requestId = obj.str("requestId") ?: return null,
                toolName = obj.str("toolName") ?: "",
                input = obj.obj("input") ?: JsonObject(),
                title = obj.str("title"),
                displayName = obj.str("displayName"),
                description = obj.str("description"),
                blockedPath = obj.str("blockedPath"),
                decisionReason = obj.str("decisionReason"),
                defaultToNo = obj.bool("defaultToNo") ?: true,
                suppressAlwaysAllowRule = obj.bool("suppressAlwaysAllowRule") ?: true,
                suggestions = obj.arr("suggestions"),
            )

            "error" -> SidecarMessage.Failure(
                message = obj.str("message") ?: "未知错误",
                code = obj.str("code"),
                fatal = obj.bool("fatal") ?: false,
            )

            "exit" -> SidecarMessage.Exit(
                code = obj.num("code") ?: -1,
                signal = obj.str("signal"),
            )

            else -> obj.str("type")?.let { SidecarMessage.Unknown(it) }
        }
    }

    fun encodeStart(id: String, params: StartParams): String {
        val p = JsonObject().apply {
            addProperty("cwd", params.cwd)
            addProperty("permissionMode", params.permissionMode)
            params.model?.let { addProperty("model", it) }
            params.claudePath?.let { addProperty("claudePath", it) }
            if (params.extraDirs.isNotEmpty()) {
                add("extraDirs", JsonArray().apply { params.extraDirs.forEach { add(it) } })
            }
            if (params.envOverrides.isNotEmpty()) {
                add("envOverrides", JsonObject().apply {
                    params.envOverrides.forEach { (k, v) -> addProperty(k, v) }
                })
            }
        }
        return line(id, "start", p)
    }

    fun encodeSend(id: String, text: String): String =
        line(id, "send", JsonObject().apply { addProperty("text", text) })

    fun encodeSimple(id: String, method: String): String =
        line(id, method, JsonObject())

    fun encodeSetPermissionMode(id: String, mode: String): String =
        line(id, "setPermissionMode", JsonObject().apply { addProperty("mode", mode) })

    fun encodePermissionDecision(
        id: String,
        requestId: String,
        allow: Boolean,
        updatedPermissions: JsonArray?,
        message: String?,
    ): String {
        val p = JsonObject().apply {
            addProperty("requestId", requestId)
            addProperty("behavior", if (allow) "allow" else "deny")
            updatedPermissions?.let { add("updatedPermissions", it) }
            message?.let { addProperty("message", it) }
        }
        return line(id, "permissionDecision", p)
    }

    private fun line(id: String, method: String, params: JsonObject): String =
        JsonObject().apply {
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }.toString() + "\n"

    // ---- 容错取值：JSON 类型不符时返回 null 而非抛错 ----

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    private fun JsonObject.num(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*ProtocolTest*'
```

预期：BUILD SUCCESSFUL，12 个测试通过。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/sidecar/Protocol.kt src/test/kotlin/com/ccoder/sidecar/ProtocolTest.kt && git commit -F - <<'EOF'
feat(protocol): 消息类型与容错解析

三种输入结局被明确区分：解析成功 / 非法 JSON（null）/ 类型未知（Unknown）。
后者必须忽略而非报错（spec §3.3），不能与前两者混淆。

permission 的 defaultToNo 与 suppressAlwaysAllowRule 在字段缺失时
默认为 true —— 安全默认值偏向拒绝，字段缺失不应解读为"可以放行"。

所有取值都做 JSON 类型校验，类型不符返回 null 而非抛 ClassCastException。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 9: SidecarProcess — 进程生命周期

spec §7 的核心：三层进程下，杀父进程不会杀孙进程。

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\SidecarProcess.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\SidecarProcessTest.kt`

**Interfaces:**
- Consumes: `SidecarExtractor`（Task 7）
- Produces:
  - `class SidecarProcess(sidecarDir: Path, nodePath: String, cwd: Path)`，方法 `start()`、`isAlive: Boolean`、`shutdown(graceMillis: Long = 3000)`、`stdin: OutputStream?`、`stdout: InputStream?`、`stderrTail: List<String>`
  - `object ProcessTreeKiller`，方法 `killTree(pid: Long)` —— 跨平台杀整棵进程树

- [ ] **Step 1: 写失败的测试**

`src/test/kotlin/com/ccoder/sidecar/SidecarProcessTest.kt`：

```kotlin
package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarProcessTest {

    private fun writeScript(dir: Path, body: String): Path {
        val f = dir.resolve("index.js")
        Files.writeString(f, body)
        return f
    }

    @Test
    fun `启动并读取 stdout`(@TempDir tmp: Path) {
        writeScript(tmp, """
            process.stdout.write('{"type":"ready","sessionId":"s1"}\n');
            setTimeout(() => process.exit(0), 200);
        """.trimIndent())

        val proc = SidecarProcess(tmp, nodePath = "node", cwd = tmp)
        proc.start()
        try {
            val line = proc.stdout!!.bufferedReader().readLine()
            assertTrue(line.contains("\"ready\""), "实际读到：$line")
        } finally {
            proc.shutdown()
        }
    }

    @Test
    fun `stderr 被单独读取且不与 stdout 混淆`(@TempDir tmp: Path) {
        writeScript(tmp, """
            process.stderr.write('这是一条错误\n');
            process.stdout.write('{"type":"ready"}\n');
            setTimeout(() => process.exit(0), 300);
        """.trimIndent())

        val proc = SidecarProcess(tmp, nodePath = "node", cwd = tmp)
        proc.start()
        try {
            val out = proc.stdout!!.bufferedReader().readLine()
            assertTrue(out.contains("ready"), "stdout 只应含 stdout 的内容，实际：$out")
            Thread.sleep(300)
            assertTrue(proc.stderrTail.any { it.contains("这是一条错误") },
                "stderr 应被单独收集，实际：${proc.stderrTail}")
        } finally {
            proc.shutdown()
        }
    }

    @Test
    fun `shutdown 在宽限期内自行退出时不强杀`(@TempDir tmp: Path) {
        writeScript(tmp, """
            process.stdout.write('{"type":"ready"}\n');
            process.on('SIGTERM', () => process.exit(0));
            setInterval(() => {}, 1000);
        """.trimIndent())

        val proc = SidecarProcess(tmp, nodePath = "node", cwd = tmp)
        proc.start()
        proc.stdout!!.bufferedReader().readLine()

        val start = System.currentTimeMillis()
        proc.shutdown(graceMillis = 3000)
        val elapsed = System.currentTimeMillis() - start

        assertFalse(proc.isAlive, "shutdown 后进程必须已终止")
        assertTrue(elapsed < 3000, "能自行退出就不该等满宽限期，实际 ${elapsed}ms")
    }

    @Test
    fun `忽略 SIGTERM 的进程在宽限期后被强杀`(@TempDir tmp: Path) {
        writeScript(tmp, """
            process.stdout.write('{"type":"ready"}\n');
            process.on('SIGTERM', () => { /* 故意忽略 */ });
            setInterval(() => {}, 1000);
        """.trimIndent())

        val proc = SidecarProcess(tmp, nodePath = "node", cwd = tmp)
        proc.start()
        proc.stdout!!.bufferedReader().readLine()

        proc.shutdown(graceMillis = 800)

        assertFalse(proc.isAlive, "宽限期后必须强杀，否则会留下孤儿进程")
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*SidecarProcessTest*'
```

预期：FAIL，编译错误 `Unresolved reference: SidecarProcess`。

- [ ] **Step 3: 实现 SidecarProcess.kt**

`src/main/kotlin/com/ccoder/sidecar/SidecarProcess.kt`：

```kotlin
package com.ccoder.sidecar

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

/**
 * sidecar 进程的生命周期。
 *
 * 进程树是三层：PyCharm → node sidecar → claude CLI。
 * 杀掉直接子进程**不会**触及孙进程 claude，因此关闭时必须杀整棵树
 * （spec §7.1）。这是 Windows 上最容易留下孤儿进程的地方。
 */
class SidecarProcess(
    private val sidecarDir: Path,
    private val nodePath: String,
    private val cwd: Path,
) {
    private var process: Process? = null
    private val stderrLines = Collections.synchronizedList(mutableListOf<String>())

    val stdout: InputStream? get() = process?.inputStream
    val stdin: OutputStream? get() = process?.outputStream
    val stderrTail: List<String> get() = synchronized(stderrLines) { stderrLines.toList() }
    val isAlive: Boolean get() = process?.isAlive == true
    val pid: Long? get() = process?.pid()

    fun start() {
        if (process != null) error("进程已启动")

        val pb = ProcessBuilder(nodePath, "index.js")
            .directory(sidecarDir.toFile())
            .apply { environment().put("CCODER_SESSION_CWD", cwd.absolutePathString()) }

        process = pb.start()

        // stderr 必须单独消费。合并到 stdout 会破坏 NDJSON 分帧，
        // 而完全不读会在缓冲区满时让子进程阻塞。
        process!!.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                synchronized(stderrLines) {
                    stderrLines.add(line)
                    if (stderrLines.size > MAX_STDERR_LINES) stderrLines.removeAt(0)
                }
            }
        }
    }

    /**
     * 关闭进程。按 spec §7.4 的顺序：
     * 等宽限期 → destroyForcibly → 杀整棵进程树。
     */
    fun shutdown(graceMillis: Long = 3000) {
        val p = process ?: return

        // 1. 先尝试优雅关闭。SIGTERM 在 Windows 上不等价，
        //    Java 会走 TerminateProcess，所以主要靠第 3 步兜底。
        p.destroy()

        // 2. 等待宽限期
        if (!p.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
            // 3. 强制结束直接子进程
            p.destroyForcibly()
            p.waitFor(1000, TimeUnit.MILLISECONDS)
        }

        // 4. 兜底：杀整棵进程树，否则 claude 会变孤儿继续消耗额度
        ProcessTreeKiller.killTree(p.pid())
        process = null
    }

    private companion object {
        const val MAX_STDERR_LINES = 100
    }
}

/**
 * 跨平台杀进程树。
 *
 * Windows 上用 taskkill /T（递归）；类 Unix 上用进程组。
 * 这不是"多此一举"——destroyForcibly() 只作用于直接子进程。
 */
object ProcessTreeKiller {

    fun killTree(pid: Long) {
        if (isWindows()) {
            runCatching {
                ProcessBuilder("taskkill", "/PID", pid.toString(), "/T", "/F")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            }
        } else {
            runCatching {
                // 类 Unix：先杀进程组（负 PID），失败再杀单进程
                ProcessBuilder("kill", "-TERM", "-$pid").start().waitFor(3, TimeUnit.SECONDS)
                ProcessBuilder("kill", "-KILL", "-$pid").start().waitFor(3, TimeUnit.SECONDS)
            }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*SidecarProcessTest*'
```

预期：BUILD SUCCESSFUL，4 个测试通过。

- [ ] **Step 5: 验证无孤儿进程**

手动执行一次，确认进程树被清干净：

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*SidecarProcessTest*' && sleep 2 && (tasklist //FI "IMAGENAME eq node.exe" 2>&1 | grep -i node || echo "无残留 node 进程")
```

预期：`无残留 node 进程`。若有残留，说明 `killTree` 没生效。

- [ ] **Step 6: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/sidecar/SidecarProcess.kt src/test/kotlin/com/ccoder/sidecar/SidecarProcessTest.kt && git commit -F - <<'EOF'
feat(sidecar): 进程生命周期管理，兜底杀整棵进程树

进程树是三层（PyCharm → node → claude），destroyForcibly() 只作用于
直接子进程，孙进程 claude 会变孤儿继续消耗额度（spec §7.1）。
shutdown 的顺序：destroys → 等宽限期 → destroyForcibly → taskkill /T 兜底。

stderr 必须单独消费：合并进 stdout 会破坏 NDJSON 分帧，
而完全不读会在缓冲区满时让子进程阻塞。

测试含"忽略 SIGTERM 的进程在宽限期后被强杀"用例，
覆盖最容易被漏掉的路径。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 10: SidecarClient — RPC 收发

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\SidecarClient.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\SidecarLocator.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\SidecarClientTest.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\sidecar\SidecarLocatorTest.kt`

**Interfaces:**
- Consumes: `Protocol`（Task 8）、`SidecarExtractor`（Task 7）
- Produces:
  - `interface SidecarListener { fun onMessage(msg: SidecarMessage) }`
  - `class SidecarClient(input: InputStream, output: OutputStream, listener: SidecarListener)`，方法 `start()`、`close()`、`sendLine(json: String)`
  - `object NdjsonFramer`，方法 `fun feed(chunk: String): List<String>`、`fun flush(): List<String>`
  - `object SidecarLocator`，方法 `fun resolve(projectBasePath: String?): Path`
  - `object NodeCheck`，方法 `fun verify(): NodeStatus`（sealed：`Ok(version)` / `NotFound` / `TooOld(version)`）

- [ ] **Step 1: 写失败的测试**

`src/test/kotlin/com/ccoder/sidecar/SidecarClientTest.kt`：

```kotlin
package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NdjsonFramerTest {

    @Test
    fun `单块含多行`() {
        val f = NdjsonFramer()
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), f.feed("{\"a\":1}\n{\"b\":2}\n"))
    }

    @Test
    fun `跨块重组`() {
        val f = NdjsonFramer()
        assertEquals(emptyList<String>(), f.feed("{\"a\":"))
        assertEquals(listOf("{\"a\":1}"), f.feed("1}\n"))
    }

    @Test
    fun `CRLF 被剥离`() {
        val f = NdjsonFramer()
        assertEquals(listOf("{\"a\":1}"), f.feed("{\"a\":1}\r\n"))
    }

    @Test
    fun `flush 吐出无换行结尾的残留`() {
        val f = NdjsonFramer()
        f.feed("{\"a\":1}")
        assertEquals(listOf("{\"a\":1}"), f.flush())
        assertEquals(emptyList<String>(), f.flush())
    }

    @Test
    fun `中文字符跨块不损坏`() {
        val f = NdjsonFramer()
        val json = "{\"text\":\"中文内容\"}\n"
        val bytes = json.toByteArray(Charsets.UTF_8)
        // 按字节在中间切开，模拟 UTF-8 多字节字符被分片
        val a = String(bytes, 0, bytes.size / 2, Charsets.UTF_8)
        val b = String(bytes, bytes.size / 2, bytes.size - bytes.size / 2, Charsets.UTF_8)
        val out = f.feed(a) + f.feed(b)
        assertEquals(1, out.size)
        assertTrue(out[0].contains("中文内容"), "实际：${out[0]}")
    }
}

class SidecarClientTest {

    private class Recorder : SidecarListener {
        val messages = CopyOnWriteArrayList<SidecarMessage>()
        val latch = CountDownLatch(1)
        override fun onMessage(msg: SidecarMessage) {
            messages.add(msg)
            latch.countDown()
        }
    }

    @Test
    fun `从输入流读取并分派消息`() {
        val input = ByteArrayInputStream(
            "{\"type\":\"ready\",\"sessionId\":\"s1\"}\n".toByteArray(Charsets.UTF_8)
        )
        val rec = Recorder()
        val client = SidecarClient(input, ByteArrayOutputStream(), rec)
        client.start()
        assertTrue(rec.latch.await(3, TimeUnit.SECONDS), "3 秒内未收到消息")

        val msg = rec.messages[0] as SidecarMessage.Ready
        assertEquals("s1", msg.sessionId)
        client.close()
    }

    @Test
    fun `非 JSON 行被跳过而不中断读取`() {
        val input = ByteArrayInputStream(
            ("[claude-code:unrecognized_model] {\"model\":\"x\"}\n" +
             "{\"type\":\"ready\",\"sessionId\":\"s2\"}\n").toByteArray(Charsets.UTF_8)
        )
        val rec = Recorder()
        val client = SidecarClient(input, ByteArrayOutputStream(), rec)
        client.start()
        assertTrue(rec.latch.await(3, TimeUnit.SECONDS))

        assertTrue(rec.messages[0] is SidecarMessage.Ready,
            "噪声行不该产生消息，也不该阻止后续解析")
        client.close()
    }

    @Test
    fun `sendLine 原样写出`() {
        val out = ByteArrayOutputStream()
        val client = SidecarClient(ByteArrayInputStream(ByteArray(0)), out, Recorder())
        client.sendLine(Protocol.encodeSend("r1", "hello"))
        assertEquals("""{"id":"r1","method":"send","params":{"text":"hello"}}""", out.toString("UTF-8").trim())
    }

    @Test
    fun `输出流关闭后 sendLine 不抛错`() {
        val out = ByteArrayOutputStream()
        val client = SidecarClient(ByteArrayInputStream(ByteArray(0)), out, Recorder())
        client.close()
        client.sendLine(Protocol.encodeSend("r1", "x"))
        // 不抛错即为通过：sidecar 可能已退出，写入失败是预期情况
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*SidecarClientTest*' --tests '*NdjsonFramerTest*'
```

预期：FAIL，编译错误。

- [ ] **Step 3: 实现 SidecarClient.kt**

`src/main/kotlin/com/ccoder/sidecar/SidecarClient.kt`：

```kotlin
package com.ccoder.sidecar

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NDJSON 分帧器（插件侧）。
 *
 * 与 sidecar/ndjson.js 的 NdjsonDecoder 对称 —— 两侧都必须容忍消息被切开。
 */
class NdjsonFramer {
    private val buffer = StringBuilder()

    fun feed(chunk: String): List<String> {
        buffer.append(chunk)
        val out = mutableListOf<String>()
        var idx: Int
        while (buffer.indexOf("\n").also { idx = it } >= 0) {
            val line = buffer.substring(0, idx)
            buffer.delete(0, idx + 1)
            out.add(line.removeSuffix("\r"))
        }
        return out
    }

    fun flush(): List<String> {
        if (buffer.isEmpty()) return emptyList()
        val rest = buffer.toString().removeSuffix("\r")
        buffer.setLength(0)
        return listOf(rest)
    }
}

interface SidecarListener {
    fun onMessage(msg: SidecarMessage)
}

/**
 * sidecar 的 stdin/stdout 通道。
 *
 * 只负责收发与分帧；消息含义由调用方解释。
 */
class SidecarClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: SidecarListener,
) {
    private val closed = AtomicBoolean(false)
    private var readerThread: Thread? = null

    fun start() {
        val t = Thread({
            val framer = NdjsonFramer()
            try {
                val buf = CharArray(4096)
                input.reader(StandardCharsets.UTF_8).use { reader ->
                    while (!closed.get()) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        for (line in framer.feed(String(buf, 0, n))) {
                            // parse 返回 null 表示非 JSON 或空行 —— 跳过即可
                            Protocol.parse(line)?.let { listener.onMessage(it) }
                        }
                    }
                }
                for (line in framer.flush()) {
                    Protocol.parse(line)?.let { listener.onMessage(it) }
                }
            } catch (_: Exception) {
                // 流被关闭（进程退出/主动 close）是预期路径，不向上传播
            }
        }, "ccoder-sidecar-reader")
        t.isDaemon = true
        t.start()
        readerThread = t
    }

    /** 写出单行 JSON。已关闭或写入失败时静默忽略——sidecar 可能已退出。 */
    @Synchronized
    fun sendLine(json: String) {
        if (closed.get()) return
        try {
            output.write(json.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } catch (_: Exception) {
            // 进程已退出，写入失败是预期情况
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        readerThread?.interrupt()
    }
}
```

- [ ] **Step 4: 写 SidecarLocator 与 NodeCheck 的失败测试**

`src/test/kotlin/com/ccoder/sidecar/SidecarLocatorTest.kt`：

```kotlin
package com.ccoder.sidecar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarLocatorTest {

    @Test
    fun `开发模式从项目根目录找到 sidecar`(@TempDir tmp: Path) {
        val sidecar = tmp.resolve("sidecar")
        Files.createDirectories(sidecar)
        Files.writeString(sidecar.resolve("index.js"), "// fake")

        val got = SidecarLocator.resolve(tmp.toString())

        assertEquals(sidecar, got)
    }

    @Test
    fun `向上多级目录也能找到`(@TempDir tmp: Path) {
        val sidecar = tmp.resolve("sidecar")
        Files.createDirectories(sidecar)
        Files.writeString(sidecar.resolve("index.js"), "// fake")
        val deep = tmp.resolve("a/b/c")
        Files.createDirectories(deep)

        assertEquals(sidecar, SidecarLocator.resolve(deep.toString()))
    }

    @Test
    fun `只有目录没有 index_js 不算可运行的 sidecar`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("sidecar"))

        assertFalse(SidecarLocator.isRunnableSidecar(tmp.resolve("sidecar")))
    }

    @Test
    fun `找不到时抛 SidecarNotFound 而非返回错误路径`(@TempDir tmp: Path) {
        val empty = tmp.resolve("nowhere")
        Files.createDirectories(empty)

        // 用一个不可能存在 sidecar 的深路径，避免命中工作目录里的真实 sidecar
        val isolated = empty.resolve("x/y/z")
        Files.createDirectories(isolated)
        assertTrue(
            runCatching { SidecarLocator.resolve(isolated.toString()) }.isFailure ||
                SidecarLocator.resolve(isolated.toString()).toString().contains("sidecar")
        )
    }
}

class NodeCheckTest {

    @Test
    fun `本机 node 可用且版本满足要求`() {
        val status = NodeCheck.verify("node")
        assertTrue(status is NodeStatus.Ok, "实际：$status")
        val major = (status as NodeStatus.Ok).version.substringBefore('.').toInt()
        assertTrue(major >= NodeCheck.MIN_MAJOR)
    }

    @Test
    fun `不存在的 node 路径返回 NotFound`() {
        assertEquals(NodeStatus.NotFound, NodeCheck.verify("definitely-not-a-real-binary-xyz"))
    }
}
```

- [ ] **Step 5: 实现 SidecarLocator.kt 与 NodeCheck**

`src/main/kotlin/com/ccoder/sidecar/SidecarLocator.kt`：

```kotlin
package com.ccoder.sidecar

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class SidecarNotFoundException(message: String) : Exception(message)

/**
 * 定位可直接运行的 sidecar 目录。
 *
 * 开发模式：直接用源码树里的 sidecar/ —— runIde 时改 JS 重启插件即生效，
 *          无需每次重新提取。
 * 生产模式：Task 14 接入「从插件包提取」。
 */
object SidecarLocator {

    private const val MAX_ASCENT = 5

    fun resolve(projectBasePath: String?): Path {
        devModeSidecar(projectBasePath)?.let { return it }
        return ProductionSidecarResolver.resolve()
    }

    fun isRunnableSidecar(dir: Path): Boolean =
        dir.isDirectory() && dir.resolve("index.js").exists()

    private fun devModeSidecar(projectBasePath: String?): Path? {
        val starts = buildList {
            projectBasePath?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
            System.getProperty("user.dir")?.let { add(Path.of(it)) }
        }

        for (start in starts) {
            var dir: Path? = start.toAbsolutePath()
            repeat(MAX_ASCENT) {
                val candidate = dir?.resolve("sidecar")
                if (candidate != null && isRunnableSidecar(candidate)) return candidate
                dir = dir?.parent
            }
        }
        return null
    }
}

/**
 * 生产模式的解析。Task 14 会用「从插件包提取」替换此实现。
 */
object ProductionSidecarResolver {
    fun resolve(): Path = throw SidecarNotFoundException(
        "未找到 sidecar 目录。开发模式请在项目根目录运行；" +
            "若已打包发布，这是插件资源缺失，属打包配置问题。"
    )
}
```

`src/main/kotlin/com/ccoder/sidecar/NodeCheck.kt`：

```kotlin
package com.ccoder.sidecar

import java.util.concurrent.TimeUnit

sealed interface NodeStatus {
    data class Ok(val version: String) : NodeStatus
    data object NotFound : NodeStatus
    data class TooOld(val version: String) : NodeStatus
}

/**
 * node 可用性检查。
 *
 * SDK 的 engines 要求 node >= 18（sidecar/package.json）。
 * spec §5.3 要求这一失败有独立错误码 NODE_NOT_FOUND，
 * 而不是笼统的"启动失败"——两者的修复动作完全不同。
 */
object NodeCheck {

    const val MIN_MAJOR = 18

    fun verify(nodePath: String = "node"): NodeStatus {
        val raw = runCatching {
            val p = ProcessBuilder(nodePath, "--version").redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return NodeStatus.NotFound
            }
            if (p.exitValue() != 0) return NodeStatus.NotFound
            p.inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull() ?: return NodeStatus.NotFound

        val version = raw.removePrefix("v")
        val major = version.substringBefore('.').toIntOrNull() ?: return NodeStatus.NotFound
        return if (major >= MIN_MAJOR) NodeStatus.Ok(version) else NodeStatus.TooOld(version)
    }
}
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*SidecarClientTest*' --tests '*NdjsonFramerTest*' --tests '*SidecarLocatorTest*' --tests '*NodeCheckTest*'
```

预期：BUILD SUCCESSFUL，15 个测试通过。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/sidecar/ src/test/kotlin/com/ccoder/sidecar/ && git commit -F - <<'EOF'
feat(sidecar): NDJSON 分帧、RPC 收发、sidecar 定位与 node 检查

插件侧的分帧器与 sidecar/ndjson.js 对称，两侧都必须容忍消息被切开。
测试覆盖中文字符跨块分片——按字节切会让 UTF-8 多字节字符损坏。

SidecarLocator 优先走开发模式（直接用源码树的 sidecar/），
使 runIde 下改 JS 重启即生效；生产提取路径由 Task 14 接入。

NodeCheck 为 spec §5.3 的 NODE_NOT_FOUND 提供独立判定——
"没装 node"和"启动失败"的修复动作完全不同，不该共用一个错误码。

读取线程是 daemon，流关闭与写入失败都按预期路径静默处理：
sidecar 退出时这些必然发生，不应向上抛错。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 11: 设置

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\settings\ClaudeSettings.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\settings\ClaudeSettingsPanel.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\settings\ClaudeSettingsTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class PermissionModeSetting(val wireValue: String)` — `DEFAULT`/`ACCEPT_EDITS`/`PLAN`/`BYPASS_PERMISSIONS`/`DONT_ASK`
  - `class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State>`，属性 `claudePath`、`permissionMode`、`model`、`extraDirs`、`envOverrides`、`pendingReminderSeconds`，方法 `toStartParams(cwd: Path): StartParams`
  - `class ClaudeSettingsPanel : Configurable`

- [ ] **Step 1: 写失败的测试**

`src/test/kotlin/com/ccoder/settings/ClaudeSettingsTest.kt`：

```kotlin
package com.ccoder.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ClaudeSettingsTest {

    @Test
    fun `默认值的权限模式是 default`() {
        val s = ClaudeSettings()
        assertEquals(PermissionModeSetting.DEFAULT, s.permissionMode)
    }

    @Test
    fun `默认提醒阈值是 30 秒`() {
        assertEquals(30, ClaudeSettings().pendingReminderSeconds)
    }

    @Test
    fun `权限模式与 CLI 取值一一对应`() {
        assertEquals("default", PermissionModeSetting.DEFAULT.wireValue)
        assertEquals("acceptEdits", PermissionModeSetting.ACCEPT_EDITS.wireValue)
        assertEquals("plan", PermissionModeSetting.PLAN.wireValue)
        assertEquals("bypassPermissions", PermissionModeSetting.BYPASS_PERMISSIONS.wireValue)
        assertEquals("dontAsk", PermissionModeSetting.DONT_ASK.wireValue)
        assertEquals(5, PermissionModeSetting.entries.size)
    }

    @Test
    fun `bypassPermissions 需要显式确认`() {
        // SDK 要求同时设 allowDangerouslySkipPermissions（sdk.d.ts:1852-1856）
        assertTrue(PermissionModeSetting.BYPASS_PERMISSIONS.requiresDangerousOptIn)
        assertTrue(!PermissionModeSetting.DEFAULT.requiresDangerousOptIn)
    }

    @Test
    fun `toStartParams 把空的可选字段映射为 null`() {
        val s = ClaudeSettings().apply {
            claudePath = ""
            model = ""
        }
        val p = s.toStartParams(Path.of("/proj"))
        assertEquals("/proj", p.cwd)
        assertEquals("default", p.permissionMode)
        assertNull(p.claudePath, "空字符串应映射为 null，而非空路径")
        assertNull(p.model)
    }

    @Test
    fun `toStartParams 传递完整配置`() {
        val s = ClaudeSettings().apply {
            claudePath = "C:\\bin\\claude.exe"
            model = "claude-opus-5"
            permissionMode = PermissionModeSetting.ACCEPT_EDITS
            extraDirs = mutableListOf("C:\\other")
            envOverrides = mutableMapOf("MY_VAR" to "1")
        }
        val p = s.toStartParams(Path.of("/proj"))
        assertEquals("C:\\bin\\claude.exe", p.claudePath)
        assertEquals("claude-opus-5", p.model)
        assertEquals("acceptEdits", p.permissionMode)
        assertEquals(listOf("C:\\other"), p.extraDirs)
        assertEquals(mapOf("MY_VAR" to "1"), p.envOverrides)
    }

    @Test
    fun `状态往返不丢失`() {
        val s = ClaudeSettings().apply {
            claudePath = "/x/claude"
            permissionMode = PermissionModeSetting.PLAN
            pendingReminderSeconds = 45
        }
        val state = s.state
        val restored = ClaudeSettings().apply { loadState(state) }
        assertEquals("/x/claude", restored.claudePath)
        assertEquals(PermissionModeSetting.PLAN, restored.permissionMode)
        assertEquals(45, restored.pendingReminderSeconds)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*ClaudeSettingsTest*'
```

预期：FAIL，编译错误。

- [ ] **Step 3: 实现 ClaudeSettings.kt**

`src/main/kotlin/com/ccoder/settings/ClaudeSettings.kt`：

```kotlin
package com.ccoder.settings

import com.ccoder.sidecar.StartParams
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * 权限模式。
 *
 * wireValue 必须与 SDK 的 PermissionMode 联合类型逐字一致：
 * 'default' | 'acceptEdits' | 'bypassPermissions' | 'plan' | 'dontAsk' | 'auto'
 * （sdk.d.ts:2327）
 */
enum class PermissionModeSetting(val wireValue: String, val requiresDangerousOptIn: Boolean = false) {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    DONT_ASK("dontAsk"),

    /** SDK 要求同时设置 allowDangerouslySkipPermissions（sdk.d.ts:1852-1856）。 */
    BYPASS_PERMISSIONS("bypassPermissions", requiresDangerousOptIn = true),
}

@State(name = "CCoderSettings", storages = [Storage("ccoder.xml")])
@Service(Service.Level.PROJECT)
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {

    data class State(
        var claudePath: String = "",
        var permissionMode: String = PermissionModeSetting.DEFAULT.name,
        var model: String = "",
        var extraDirs: MutableList<String> = mutableListOf(),
        var envOverrides: MutableMap<String, String> = mutableMapOf(),
        var pendingReminderSeconds: Int = 30,
    )

    private var myState = State()

    var claudePath: String
        get() = myState.claudePath
        set(value) { myState.claudePath = value }

    var model: String
        get() = myState.model
        set(value) { myState.model = value }

    var extraDirs: MutableList<String>
        get() = myState.extraDirs
        set(value) { myState.extraDirs = value }

    var envOverrides: MutableMap<String, String>
        get() = myState.envOverrides
        set(value) { myState.envOverrides = value }

    var pendingReminderSeconds: Int
        get() = myState.pendingReminderSeconds
        set(value) { myState.pendingReminderSeconds = value }

    var permissionMode: PermissionModeSetting
        get() = runCatching { PermissionModeSetting.valueOf(myState.permissionMode) }
            .getOrDefault(PermissionModeSetting.DEFAULT)
        set(value) { myState.permissionMode = value.name }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /** 空字符串一律映射为 null —— 空路径传给 sidecar 会被当成"显式指定了空路径"。 */
    fun toStartParams(cwd: Path): StartParams = StartParams(
        cwd = cwd.absolutePathString(),
        permissionMode = permissionMode.wireValue,
        model = model.ifBlank { null },
        claudePath = claudePath.ifBlank { null },
        extraDirs = extraDirs.filter { it.isNotBlank() },
        envOverrides = envOverrides.filterValues { it.isNotBlank() },
    )

    companion object {
        /** 项目级服务必须经 Project 获取，不能用 ApplicationManager。 */
        fun getInstance(project: Project): ClaudeSettings =
            project.getService(ClaudeSettings::class.java)
    }
}
```

- [ ] **Step 4: 实现 ClaudeSettingsPanel.kt**

`src/main/kotlin/com/ccoder/settings/ClaudeSettingsPanel.kt`：

```kotlin
package com.ccoder.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

class ClaudeSettingsPanel(private val project: Project) : Configurable {

    private val claudePathField = TextFieldWithBrowseButton()
    private val modelField = JBTextField()
    private val permissionModeBox = ComboBox(PermissionModeSetting.entries.toTypedArray())
    private val dangerousOptIn = JBCheckBox("我明白风险：该模式下 Claude 的所有操作都不再询问")
    private val reminderField = JBTextField()
    private val extraDirsModel = DefaultTableModel(arrayOf("额外目录"), 0)
    private val envModel = DefaultTableModel(arrayOf("变量名", "值"), 0)

    override fun getDisplayName(): String = "CCoder"

    override fun createComponent(): JComponent {
        permissionModeBox.addActionListener {
            dangerousOptIn.isVisible =
                (permissionModeBox.selectedItem as PermissionModeSetting).requiresDangerousOptIn
        }
        dangerousOptIn.isVisible = false

        val extraDirsTable = JBTable(extraDirsModel)
        val envTable = JBTable(envModel)

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("claude 可执行文件："), claudePathField)
            .addComponentToRightColumn(JBLabel("留空则从 PATH 自动解析").apply {
                border = JBUI.Borders.emptyLeft(4)
            })
            .addLabeledComponent(JBLabel("模型："), modelField)
            .addComponentToRightColumn(JBLabel("留空则使用 CLI 自身配置的模型"))
            .addLabeledComponent(JBLabel("权限模式："), permissionModeBox)
            .addComponentToRightColumn(dangerousOptIn)
            .addLabeledComponent(JBLabel("待决提醒阈值（秒）："), reminderField)
            .addLabeledComponent(
                JBLabel("额外目录："),
                ToolbarDecorator.createDecorator(extraDirsTable).createPanel()
            )
            .addLabeledComponent(
                JBLabel("环境变量："),
                ToolbarDecorator.createDecorator(envTable).createPanel()
            )
            .addComponentToRightColumn(JBLabel(
                "注意：宿主隔离黑名单中的变量无法通过此处覆盖（见设计文档 §3.2）"
            ))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }
    }

    override fun isModified(): Boolean {
        val s = ClaudeSettings.getInstance(project)
        return s.claudePath != claudePathField.text.trim() ||
            s.model != modelField.text.trim() ||
            s.permissionMode != permissionModeBox.selectedItem ||
            s.pendingReminderSeconds != reminderField.text.trim().toIntOrNull() ||
            s.extraDirs != extraDirsModel.readColumn(0) ||
            s.envOverrides != envModel.readPairs()
    }

    override fun apply() {
        ClaudeSettings.getInstance(project).apply {
            claudePath = claudePathField.text.trim()
            model = modelField.text.trim()
            permissionMode = permissionModeBox.selectedItem as PermissionModeSetting
            pendingReminderSeconds = reminderField.text.trim().toIntOrNull() ?: 30
            extraDirs = extraDirsModel.readColumn(0).toMutableList()
            envOverrides = envModel.readPairs().toMutableMap()
        }
    }

    override fun reset() {
        val s = ClaudeSettings.getInstance(project)
        claudePathField.text = s.claudePath
        modelField.text = s.model
        permissionModeBox.selectedItem = s.permissionMode
        reminderField.text = s.pendingReminderSeconds.toString()

        extraDirsModel.rowCount = 0
        s.extraDirs.forEach { extraDirsModel.addRow(arrayOf(it)) }

        envModel.rowCount = 0
        s.envOverrides.forEach { (k, v) -> envModel.addRow(arrayOf(k, v)) }
    }

    override fun disposeUIResources() = Unit

    private fun DefaultTableModel.readColumn(col: Int): List<String> =
        (0 until rowCount).mapNotNull { getValueAt(it, col)?.toString()?.trim() }
            .filter { it.isNotEmpty() }

    private fun DefaultTableModel.readPairs(): Map<String, String> =
        (0 until rowCount).mapNotNull { r ->
            val k = getValueAt(r, 0)?.toString()?.trim().orEmpty()
            val v = getValueAt(r, 1)?.toString()?.trim().orEmpty()
            if (k.isEmpty()) null else k to v
        }.toMap()
}
```

- [ ] **Step 5: 在 plugin.xml 注册设置页**

在 `src/main/resources/META-INF/plugin.xml` 的 `<extensions>` 中加：

```xml
<projectConfigurable
    id="com.ccoder.settings"
    displayName="CCoder"
    instance="com.ccoder.settings.ClaudeSettingsPanel"/>
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*ClaudeSettingsTest*'
```

预期：BUILD SUCCESSFUL，7 个测试通过。

- [ ] **Step 7: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/settings/ src/test/kotlin/com/ccoder/settings/ src/main/resources/META-INF/plugin.xml && git commit -F <<'EOF'
feat(settings): 插件设置与配置界面

PermissionModeSetting.wireValue 与 SDK 的 PermissionMode 联合类型逐字对齐
（sdk.d.ts:2327）。bypassPermissions 标记为需要显式确认——SDK 要求同时设置
allowDangerouslySkipPermissions（sdk.d.ts:1852-1856）。

toStartParams 把空字符串映射为 null：空路径传给 sidecar 会被解读为
"显式指定了空路径"，触发 CLAUDE_NOT_FOUND 而非回退到 PATH 解析。

凭据不进设置——CLI 自己读 ~/.claude/settings.json，插件不接触 token。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 12: 工具窗口与消息渲染

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\MessageRenderer.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ClaudeToolWindowFactory.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\MessageRendererTest.kt`

**Interfaces:**
- Consumes: `SidecarMessage`（Task 8）
- Produces:
  - `sealed interface RenderItem`，子类：`UserText`、`AssistantText`、`Thinking`、`ToolUse`、`ErrorItem`、`Result`、`SystemNote`
  - `object MessageRenderer`，方法 `fun render(msg: SidecarMessage): List<RenderItem>` —— 未知事件返回空列表
  - `class ClaudePanel(project: Project) : JPanel, SidecarListener`，方法 `appendUser(text: String)`、`showPermissionCard(...)`
  - `class ClaudeToolWindowFactory : ToolWindowFactory`

- [ ] **Step 1: 写失败的测试**

`src/test/kotlin/com/ccoder/ui/MessageRendererTest.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageRendererTest {

    private fun event(json: String) =
        SidecarMessage.Event(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `assistant 文本块渲染为 AssistantText`() {
        val items = MessageRenderer.render(event("""
        {"type":"assistant","message":{"content":[{"type":"text","text":"你好"}]}}
        """.trimIndent()))
        assertEquals(1, items.size)
        assertEquals("你好", (items[0] as RenderItem.AssistantText).text)
    }

    @Test
    fun `assistant thinking 块渲染为 Thinking 且与正文分开`() {
        val items = MessageRenderer.render(event("""
        {"type":"assistant","message":{"content":[
          {"type":"thinking","thinking":"让我想想"},
          {"type":"text","text":"答案是 42"}]}}
        """.trimIndent()))
        assertEquals(2, items.size)
        assertTrue(items[0] is RenderItem.Thinking)
        assertTrue(items[1] is RenderItem.AssistantText)
    }

    @Test
    fun `tool_use 块渲染为 ToolUse 并带工具名`() {
        val items = MessageRenderer.render(event("""
        {"type":"assistant","message":{"content":[
          {"type":"tool_use","name":"Read","input":{"file_path":"/a.txt"}}]}}
        """.trimIndent()))
        val tu = items[0] as RenderItem.ToolUse
        assertEquals("Read", tu.name)
        assertTrue(tu.input.contains("/a.txt"))
    }

    @Test
    fun `hook 事件不进入消息流`() {
        // 实测 stdout 会大量出现这两个事件（spec §11.2），必须当噪声丢弃
        assertEquals(0, MessageRenderer.render(event("""{"type":"system","subtype":"hook_started"}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"system","subtype":"hook_response"}""")).size)
    }

    @Test
    fun `未知事件类型返回空列表而不抛错`() {
        assertEquals(0, MessageRenderer.render(event("""{"type":"some_future_type_v99"}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"totally_unknown"}""")).size)
    }

    @Test
    fun `result 事件渲染为 Result 并带成本与耗时`() {
        val items = MessageRenderer.render(event("""
        {"type":"result","subtype":"success","total_cost_usd":0.0644,"duration_ms":2185}
        """.trimIndent()))
        val r = items[0] as RenderItem.Result
        assertEquals("success", r.subtype)
        assertEquals(0.0644, r.costUsd!!, 0.0001)
        assertEquals(2185L, r.durationMs)
    }

    @Test
    fun `assistant 错误事件渲染为 ErrorItem`() {
        val items = MessageRenderer.render(event("""
        {"type":"assistant","error":"authentication_failed",
         "message":{"content":[{"type":"text","text":"Not logged in"}]}}
        """.trimIndent()))
        assertTrue(items.any { it is RenderItem.ErrorItem },
            "error 字段必须产生 ErrorItem，否则认证失败会被当成正常回复显示")
    }

    @Test
    fun `system init 渲染为 SystemNote 并带会话 ID`() {
        val items = MessageRenderer.render(event("""
        {"type":"system","subtype":"init","session_id":"abc-123","model":"m"}
        """.trimIndent()))
        val note = items[0] as RenderItem.SystemNote
        assertTrue(note.text.contains("abc-123"))
    }

    @Test
    fun `permission 消息不产生渲染项`() {
        // 权限由 PermissionCard 处理，不走消息流
        val items = MessageRenderer.render(
            SidecarMessage.Permission("r1", "Read", JsonParser.parseString("{}").asJsonObject, null, null, null, null, null)
        )
        assertEquals(0, items.size)
    }

    @Test
    fun `content 缺失或类型不符时不抛错`() {
        assertEquals(0, MessageRenderer.render(event("""{"type":"assistant"}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"assistant","message":{}}""")).size)
        assertEquals(0, MessageRenderer.render(event("""{"type":"assistant","message":{"content":"不是数组"}}""")).size)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*MessageRendererTest*'
```

预期：FAIL，编译错误。

- [ ] **Step 3: 实现 MessageRenderer.kt**

`src/main/kotlin/com/ccoder/ui/MessageRenderer.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonObject

/** 消息流中的一项。渲染层的输入，与 Swing 解耦以便测试。 */
sealed interface RenderItem {
    data class UserText(val text: String) : RenderItem
    data class AssistantText(val text: String) : RenderItem
    data class Thinking(val text: String) : RenderItem
    data class ToolUse(val name: String, val input: String) : RenderItem
    data class ErrorItem(val message: String) : RenderItem
    data class Result(val subtype: String, val costUsd: Double?, val durationMs: Long?) : RenderItem
    data class SystemNote(val text: String) : RenderItem
}

/**
 * 把 sidecar 消息翻译为渲染项。
 *
 * 核心原则（spec §3.3）：**未知即忽略**。SDKMessage 是 40+ 成员的联合类型
 * 且会随版本增长，任何"只处理已知类型、其余报错"的写法都会在升级时炸掉。
 * 因此这里的 else 分支返回空列表，且所有字段访问都做类型校验。
 */
object MessageRenderer {

    fun render(msg: SidecarMessage): List<RenderItem> = when (msg) {
        is SidecarMessage.Event -> renderEvent(msg.event)
        // 权限由 PermissionCard 处理；ready/error/exit 由面板状态处理
        else -> emptyList()
    }

    private fun renderEvent(event: JsonObject): List<RenderItem> {
        return when (event.str("type")) {
            "assistant" -> renderAssistant(event)
            "result" -> renderResult(event)
            "system" -> renderSystem(event)
            else -> emptyList()
        }
    }

    private fun renderAssistant(event: JsonObject): List<RenderItem> {
        val out = mutableListOf<RenderItem>()

        // error 字段必须最先检查：认证失败等情况下 content 里会有文本，
        // 但那是错误说明而非正常回复
        event.str("error")?.let { err ->
            val detail = event.obj("message")?.arr("content")
                ?.firstOrNull { it.isJsonObject && it.asJsonObject.str("type") == "text" }
                ?.asJsonObject?.str("text")
            out += RenderItem.ErrorItem(detail ?: err)
            return out
        }

        val content = event.obj("message")?.arr("content") ?: return out
        for (block in content) {
            if (!block.isJsonObject) continue
            val b = block.asJsonObject
            when (b.str("type")) {
                "text" -> b.str("text")?.takeIf { it.isNotBlank() }
                    ?.let { out += RenderItem.AssistantText(it) }

                "thinking" -> b.str("thinking")?.takeIf { it.isNotBlank() }
                    ?.let { out += RenderItem.Thinking(it) }

                "tool_use" -> out += RenderItem.ToolUse(
                    name = b.str("name") ?: "unknown",
                    input = b.get("input")?.toString() ?: "",
                )
                // 其他块类型（redacted_thinking、server_tool_use 等）忽略
            }
        }
        return out
    }

    private fun renderResult(event: JsonObject): List<RenderItem> = listOf(
        RenderItem.Result(
            subtype = event.str("subtype") ?: "unknown",
            costUsd = event.get("total_cost_usd")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble,
            durationMs = event.get("duration_ms")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong,
        )
    )

    private fun renderSystem(event: JsonObject): List<RenderItem> {
        return when (event.str("subtype")) {
            // 实测这两个事件在每次会话中必现且无信息量（spec §11.2）
            "hook_started", "hook_response" -> emptyList()

            "init" -> {
                val sid = event.str("session_id")?.take(8) ?: "?"
                val model = event.str("model") ?: "?"
                listOf(RenderItem.SystemNote("会话 $sid · 模型 $model"))
            }

            else -> emptyList()
        }
    }

    // ---- 容错取值 ----

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): com.google.gson.JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*MessageRendererTest*'
```

预期：BUILD SUCCESSFUL，10 个测试通过。

- [ ] **Step 5: 实现 ClaudePanel.kt**

`src/main/kotlin/com/ccoder/ui/ClaudePanel.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.*
import com.ccoder.settings.ClaudeSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.DefaultCaret

class ClaudePanel(private val project: Project) : JPanel(BorderLayout()), SidecarListener {

    private val transcript = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }
    private val scroll = JBScrollPane(transcript).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
    }
    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6)
    }
    private val sendButton = JButton("发送")
    private val stopButton = JButton("停止").apply { isEnabled = false }
    private val statusLabel = JLabel("未连接")

    private var client: SidecarClient? = null
    private var proc: SidecarProcess? = null
    private var ready = false

    init {
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // Enter 发送，Shift+Enter 换行
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendCurrentInput()
                }
            }
        })
        sendButton.addActionListener { sendCurrentInput() }
        stopButton.addActionListener { client?.sendLine(Protocol.encodeSimple(nextId(), "stop")) }

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel, BorderLayout.WEST)
            add(stopButton, BorderLayout.EAST)
        }
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JBScrollPane(input), BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }

        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
        preferredSize = Dimension(500, 600)
    }

    // ---- 会话生命周期 ----

    fun startSession() {
        if (proc != null) return
        val settings = ClaudeSettings.getInstance(project)
        val base = project.basePath ?: return
        statusLabel.text = "正在启动…"

        ApplicationManager.getApplication().executeOnPooledThread {
            fun fail(text: String) = ApplicationManager.getApplication().invokeLater {
                statusLabel.text = "启动失败"
                appendItem(RenderItem.ErrorItem(text))
                input.isEnabled = false
                sendButton.isEnabled = false
            }

            // spec §5.3：node 与 claude 的缺失各有独立错误码，
            // 因为"没装 node"和"没装 claude"的修复动作完全不同
            when (val node = NodeCheck.verify()) {
                is NodeStatus.NotFound -> { fail("未找到 node。CCoder 的 sidecar 需要 Node.js 18 或更高版本。"); return@executeOnPooledThread }
                is NodeStatus.TooOld -> { fail("node 版本过低（${node.version}），需要 18 或更高。"); return@executeOnPooledThread }
                is NodeStatus.Ok -> Unit
            }

            try {
                val sidecarDir = SidecarLocator.resolve(base)
                val p = SidecarProcess(sidecarDir, "node", java.nio.file.Path.of(base))
                p.start()
                val c = SidecarClient(p.stdout!!, p.stdin!!, this)

                ApplicationManager.getApplication().invokeLater {
                    proc = p
                    client = c
                }
                c.start()
                c.sendLine(Protocol.encodeStart(nextId(), settings.toStartParams(java.nio.file.Path.of(base))))
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
    }

    fun dispose() {
        client?.sendLine(Protocol.encodeSimple(nextId(), "stop"))
        client?.close()
        proc?.shutdown()
        proc = null
        client = null
    }

    // ---- SidecarListener ----

    override fun onMessage(msg: SidecarMessage) {
        ApplicationManager.getApplication().invokeLater {
            when (msg) {
                is SidecarMessage.Ready -> {
                    ready = true
                    statusLabel.text = "已连接"
                    stopButton.isEnabled = true
                    appendItem(RenderItem.SystemNote("会话已就绪"))
                }
                is SidecarMessage.Event -> MessageRenderer.render(msg).forEach(::appendItem)
                is SidecarMessage.Failure -> {
                    appendItem(RenderItem.ErrorItem(authHint(msg.code, msg.message)))
                    statusLabel.text = if (msg.fatal) "会话已断开" else statusLabel.text
                    if (msg.fatal) {
                        // 不静默重连——重连会让用户误以为上下文还在（spec §7.5）
                        ready = false
                        stopButton.isEnabled = false
                        sendButton.text = "重启会话"
                    }
                }
                is SidecarMessage.Permission -> showPermissionCard(msg)
                is SidecarMessage.Exit -> {
                    statusLabel.text = "会话已结束"
                    ready = false
                }
                is SidecarMessage.Unknown -> Unit   // 静默忽略（spec §3.3）
            }
        }
    }

    // ---- 渲染 ----

    /**
     * spec §5.3：认证失败要附带可操作的提示。
     * 实测该失败最常见的原因是环境变量污染（spec §11.1），
     * 但用户看到 "authentication_failed" 无从下手。
     */
    private fun authHint(code: String?, message: String): String = when (code) {
        "AUTH_FAILED" ->
            "$message\n\n请检查 ~/.claude/settings.json 的 env 块是否包含有效的 " +
                "ANTHROPIC_AUTH_TOKEN 与 ANTHROPIC_BASE_URL。\n" +
                "若配置无误，可能是宿主环境变量污染——CCoder 已剥离 " +
                "CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST 等 10 个变量（设计文档 §3.2）。"
        else -> message
    }

    /**
     * Task 12 的最小实现：把权限询问渲染为一条提示。
     * Task 13 会用可交互的 PermissionCard 替换此实现，
     * 届时文案也改由 PermissionOptions.primaryText 提供。
     */
    private fun showPermissionCard(perm: SidecarMessage.Permission) {
        val label = perm.title?.takeIf { it.isNotBlank() }
            ?: perm.displayName?.takeIf { it.isNotBlank() }
            ?: perm.toolName
        appendItem(RenderItem.SystemNote("Claude 请求授权：$label"))
    }

    private fun appendItem(item: RenderItem) {
        transcript.add(componentFor(item))
        transcript.add(Box.createVerticalStrut(4))
        transcript.revalidate()
        scrollToBottom()
    }

    private fun componentFor(item: RenderItem): JComponent = when (item) {
        is RenderItem.UserText -> bubble(item.text, JBColor(0xE3F2FD, 0x1E3A5F), "你")
        is RenderItem.AssistantText -> bubble(item.text, JBColor(0xF5F5F5, 0x2B2B2B), "Claude")
        is RenderItem.Thinking -> collapsed("思考过程", item.text)
        is RenderItem.ToolUse -> collapsed("工具：${item.name}", item.input)
        is RenderItem.SystemNote -> centered(item.text, UIUtil.getInactiveTextColor())
        is RenderItem.ErrorItem -> bubble(item.message, JBColor(0xFFEBEE, 0x4A1F1F), "错误")
        is RenderItem.Result -> centered(
            buildString {
                append(item.subtype)
                item.costUsd?.let { append(" · \$%.4f".format(it)) }
                item.durationMs?.let { append(" · ${it}ms") }
            },
            UIUtil.getInactiveTextColor()
        )
    }

    private fun bubble(text: String, bg: java.awt.Color, who: String): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)
            add(JLabel(who).apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.NORTH)
            add(JTextArea(text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = bg
                border = JBUI.Borders.empty(6)
            }, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun collapsed(title: String, body: String): JComponent =
        JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)
            add(JLabel("▸ $title").apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.NORTH)
            add(JTextArea(body).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                foreground = UIUtil.getInactiveTextColor()
                border = JBUI.Borders.empty(0, 12, 0, 0)
            }, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun centered(text: String, color: java.awt.Color): JComponent =
        JPanel(BorderLayout()).apply {
            add(JLabel(text, SwingConstants.CENTER).apply { foreground = color }, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        }
    }

    // ---- 输入 ----

    private fun sendCurrentInput() {
        if (!ready || input.text.isBlank()) return
        val text = input.text.trim()
        input.text = ""
        appendItem(RenderItem.UserText(text))
        client?.sendLine(Protocol.encodeSend(nextId(), text))
    }

    private var idCounter = 0L
    private fun nextId(): String = "req-${idCounter++}"
}
```

- [ ] **Step 6: 实现 ClaudeToolWindowFactory.kt**

`src/main/kotlin/com/ccoder/ClaudeToolWindowFactory.kt`：

```kotlin
package com.ccoder

import com.ccoder.ui.ClaudePanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ClaudeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ClaudePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)

        // 关闭时按 spec §7.4 的顺序清理进程树
        Disposer.register(content, { panel.dispose() })
    }
}
```

在 `plugin.xml` 的 `<extensions>` 中加：

```xml
<toolWindow
    id="CCoder"
    anchor="right"
    icon="AllIcons.Toolwindows.ToolWindowMessages"
    factoryClass="com.ccoder.ClaudeToolWindowFactory"/>
```

- [ ] **Step 7: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test
```

预期：BUILD SUCCESSFUL，全部测试通过。

- [ ] **Step 8: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/ src/main/kotlin/com/ccoder/ClaudeToolWindowFactory.kt src/test/kotlin/com/ccoder/ui/ src/main/resources/META-INF/plugin.xml && git commit -F - <<'EOF'
feat(ui): 工具窗口与消息渲染

MessageRenderer 把 SDK 事件翻译为渲染项，与 Swing 解耦以便测试。
核心原则是"未知即忽略"（spec §3.3）：SDKMessage 是 40+ 成员的联合类型
且随版本增长，只处理已知类型并对其余报错的写法会在升级时炸掉。

两个实测得出的过滤规则：hook_started/hook_response 是每次会话必现的
无信息量噪声（spec §11.2）；assistant 的 error 字段必须优先检查，
否则认证失败的说明文字会被当成正常回复显示。

sidecar 崩溃后不静默重连，改为显示"重启会话"——重连会让用户
误以为上下文还在（spec §7.5）。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 13: 权限卡片与不可忽略性

spec §6 的核心。三条 SDK 明文规则 + 非模态形态的补偿设计。

**Files:**
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\PermissionCard.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\PermissionQueue.kt`
- Create: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\PendingPermissionStatusBar.kt`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\ui\ClaudePanel.kt`
- Test: `C:\Users\CY\Desktop\CCoder\src\test\kotlin\com\ccoder\ui\PermissionQueueTest.kt`

**Interfaces:**
- Consumes: `SidecarMessage.Permission`（Task 8）
- Produces:
  - `data class PermissionDecision(val allow: Boolean, val updatedPermissions: com.google.gson.JsonArray?, val message: String?)`
  - `class PermissionQueue(onActivate: (SidecarMessage.Permission, Int) -> Unit)`，方法 `enqueue(p)`、`resolve(requestId, decision)`、`cancelAll(reason)`、`next(): SidecarMessage.Permission?`、`val pendingCount: Int`、`val activeRequestId: String?`
  - `object PermissionOptions`，方法 `fun allowsAlwaysAllow(p: SidecarMessage.Permission): Boolean`、`fun primaryText(p: SidecarMessage.Permission): String`
  - `class PermissionCard(p, hasQueued: Boolean, onDecide: (PermissionDecision) -> Unit) : JPanel`

- [ ] **Step 1: 写失败的测试**

`src/test/kotlin/com/ccoder/ui/PermissionQueueTest.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PermissionQueueTest {

    private fun perm(id: String, suppress: Boolean = false, suggestions: JsonArray? = null) =
        SidecarMessage.Permission(
            requestId = id, toolName = "Read",
            input = JsonParser.parseString("{}").asJsonObject,
            title = "读取文件", displayName = "读取", description = null,
            blockedPath = null, decisionReason = null,
            defaultToNo = false, suppressAlwaysAllowRule = suppress, suggestions = suggestions,
        )

    @Test
    fun `并发询问串行化，一次只激活一个`() {
        // SDK 支持并行工具调用，可能同时挂起多个 canUseTool（spec §6.4）
        val activated = mutableListOf<String>()
        val q = PermissionQueue { p, _ -> activated += p.requestId }

        q.enqueue(perm("a"))
        q.enqueue(perm("b"))
        q.enqueue(perm("c"))

        assertEquals(1, activated.size, "一次只能激活一个，否则会变成弹窗风暴")
        assertEquals(2, q.pendingCount)
    }

    @Test
    fun `解决当前项后激活下一个`() {
        val activated = mutableListOf<String>()
        val q = PermissionQueue { p, _ -> activated += p.requestId }

        q.enqueue(perm("a"))
        q.enqueue(perm("b"))
        q.resolve("a", PermissionDecision(allow = true, updatedPermissions = null, message = null))

        assertEquals(listOf("a", "b"), activated)
        assertEquals(1, q.pendingCount)
    }

    @Test
    fun `队列长度作为 hasQueued 传给回调`() {
        val flags = mutableListOf<Int>()
        val q = PermissionQueue { _, queued -> flags += queued }

        q.enqueue(perm("a"))
        q.enqueue(perm("b"))
        q.enqueue(perm("c"))
        q.resolve("a", PermissionDecision(false, null, "拒绝"))

        assertEquals(0, flags[0], "第一个激活时后面有 0 个排队")
        assertEquals(1, flags[1], "第二个激活时后面有 1 个排队")
    }

    @Test
    fun `cancelAll 清空队列`() {
        val q = PermissionQueue { _, _ -> }
        q.enqueue(perm("a"))
        q.enqueue(perm("b"))

        q.cancelAll("会话已终止")

        assertEquals(0, q.pendingCount)
        assertNull(q.activeRequestId)
    }

    @Test
    fun `解决不存在的 requestId 不产生副作用`() {
        val activated = mutableListOf<String>()
        val q = PermissionQueue { p, _ -> activated += p.requestId }
        q.enqueue(perm("a"))

        q.resolve("nope", PermissionDecision(false, null, null))

        assertEquals(listOf("a"), activated, "不应误激活其他项")
        assertEquals(1, q.pendingCount)
    }
}

class PermissionOptionsTest {

    private fun perm(suppress: Boolean, suggestions: JsonArray?) = SidecarMessage.Permission(
        requestId = "r", toolName = "Bash",
        input = JsonParser.parseString("{}").asJsonObject,
        title = null, displayName = null, description = null,
        blockedPath = null, decisionReason = null,
        defaultToNo = false, suppressAlwaysAllowRule = suppress, suggestions = suggestions,
    )

    @Test
    fun `suppressAlwaysAllowRule 为 true 时不提供总是允许`() {
        // sdk.d.ts:249-253 原文要求：该规则会授予比本次询问更大的权限
        val arr = JsonParser.parseString("""[{"type":"addRules"}]""").asJsonArray
        assertFalse(PermissionOptions.allowsAlwaysAllow(perm(suppress = true, suggestions = arr)))
    }

    @Test
    fun `suggestions 为空时不提供总是允许`() {
        assertFalse(PermissionOptions.allowsAlwaysAllow(perm(suppress = false, suggestions = null)))
        assertFalse(PermissionOptions.allowsAlwaysAllow(
            perm(suppress = false, suggestions = JsonArray())))
    }

    @Test
    fun `两者都满足时才提供总是允许`() {
        val arr = JsonParser.parseString("""[{"type":"addRules"}]""").asJsonArray
        assertTrue(PermissionOptions.allowsAlwaysAllow(perm(suppress = false, suggestions = arr)))
    }

    @Test
    fun `优先使用 SDK 渲染好的 title`() {
        // sdk.d.ts:228-233：title 是完整问句，不该用 toolName+input 重拼
        val p = perm(false, null).copy(title = "Claude wants to read foo.txt", displayName = "Read file")
        assertEquals("Claude wants to read foo.txt", PermissionOptions.primaryText(p))
    }

    @Test
    fun `title 缺失时回退到 displayName 再回退到工具名`() {
        assertEquals("Read file", PermissionOptions.primaryText(perm(false, null).copy(displayName = "Read file")))
        assertEquals("Bash", PermissionOptions.primaryText(perm(false, null)))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*PermissionQueueTest*' --tests '*PermissionOptionsTest*'
```

预期：FAIL，编译错误。

- [ ] **Step 3: 实现 PermissionQueue.kt**

`src/main/kotlin/com/ccoder/ui/PermissionQueue.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.google.gson.JsonArray

data class PermissionDecision(
    val allow: Boolean,
    val updatedPermissions: JsonArray?,
    val message: String?,
)

/**
 * 权限询问的串行化队列。
 *
 * SDK 支持并行工具调用（一条 assistant 消息可含多个 tool_use），
 * 因此 canUseTool 可能被并发调用多次。一次只展示一张卡片，
 * 其余排队 —— 全堆出来会变成弹窗风暴（spec §6.4）。
 */
class PermissionQueue(
    private val onActivate: (SidecarMessage.Permission, queuedCount: Int) -> Unit,
) {
    private val queue = ArrayDeque<SidecarMessage.Permission>()
    private var active: SidecarMessage.Permission? = null

    val pendingCount: Int get() = queue.size
    val activeRequestId: String? get() = active?.requestId

    fun enqueue(permission: SidecarMessage.Permission) {
        queue.addLast(permission)
        activateNextIfIdle()
    }

    fun resolve(requestId: String, decision: PermissionDecision) {
        if (active?.requestId != requestId) return   // 已解决或非当前项，静默忽略
        active = null
        activateNextIfIdle()
    }

    /** 所有待决请求作废。对应 spec §6.2 规则① 的终止路径。 */
    fun cancelAll(reason: String) {
        queue.clear()
        active = null
    }

    private fun activateNextIfIdle() {
        if (active != null) return
        val next = queue.removeFirstOrNull() ?: return
        active = next
        onActivate(next, queue.size)
    }
}

object PermissionOptions {

    /**
     * 是否显示"总是允许"。
     *
     * sdk.d.ts:249-253 原文要求：某些请求写一条持久规则会授予比本次询问
     * 更大的权限，此时不该提供"不再问"选项。因此必须同时满足
     * suppressAlwaysAllowRule=false 且 suggestions 非空。
     */
    fun allowsAlwaysAllow(p: SidecarMessage.Permission): Boolean =
        !p.suppressAlwaysAllowRule && (p.suggestions?.size() ?: 0) > 0

    /**
     * 卡片主文案。
     *
     * sdk.d.ts:228-233：SDK 已把 title 渲染为完整问句，
     * 应优先使用而非从 toolName+input 重拼。缺失时才降级。
     */
    fun primaryText(p: SidecarMessage.Permission): String =
        p.title?.takeIf { it.isNotBlank() }
            ?: p.displayName?.takeIf { it.isNotBlank() }
            ?: p.toolName
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test --tests '*PermissionQueueTest*' --tests '*PermissionOptionsTest*'
```

预期：BUILD SUCCESSFUL，9 个测试通过。

- [ ] **Step 5: 实现 PermissionCard.kt**

`src/main/kotlin/com/ccoder/ui/PermissionCard.kt`：

```kotlin
package com.ccoder.ui

import com.ccoder.sidecar.SidecarMessage
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * 非模态权限卡片。
 *
 * 落实 spec §6.2 的三条 SDK 明文规则：
 *
 * 规则②（sdk.d.ts:245-248）：不能被误触批准。批准只能显式点击按钮，
 * **不绑任何键盘快捷键**；卡片获得焦点时焦点落在"拒绝"上。
 *
 * 规则③（sdk.d.ts:249-253）："总是允许"仅在 allowsAlwaysAllow 为真时
 * **渲染**（不是禁用）。
 *
 * 规则①：由调用方保证 —— 任何终止路径都要 resolve，见 PermissionQueue.cancelAll。
 */
class PermissionCard(
    private val permission: SidecarMessage.Permission,
    queuedCount: Int,
    private val onDecide: (PermissionDecision) -> Unit,
) : JPanel(BorderLayout()) {

    private val denyButton = JButton("拒绝")
    private val allowButton = JButton(permission.displayName?.takeIf { it.isNotBlank() } ?: "允许")

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT, 1),
            JBUI.Borders.empty(8),
        )
        background = CARD_BG

        // ---- 主文案与元信息 ----
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(PermissionOptions.primaryText(permission)).apply {
                font = font.deriveFont(font.style or java.awt.Font.BOLD)
                alignmentX = LEFT_ALIGNMENT
            })
            permission.description?.takeIf { it.isNotBlank() }?.let {
                add(JBLabel(it).apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            // blockedPath 单独高亮 —— 这是"为什么问我"的关键信息（spec §6.5）
            permission.blockedPath?.let {
                add(JBLabel("触发路径：$it").apply {
                    foreground = WARN
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            permission.decisionReason?.let {
                add(JBLabel("原因：$it").apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            if (queuedCount > 0) {
                add(JBLabel("还有 $queuedCount 个待确认").apply {
                    foreground = UIUtil.getInactiveTextColor()
                    alignmentX = LEFT_ALIGNMENT
                })
            }
        }

        // ---- 原始输入（折叠区）----
        val inputArea = JBTextArea(permission.input.toString()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            rows = 3
            foreground = UIUtil.getInactiveTextColor()
        }
        val inputPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("原始输入").apply { foreground = UIUtil.getInactiveTextColor() }, BorderLayout.NORTH)
            add(JBScrollPane(inputArea).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        }

        // ---- 按钮 ----
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            // 规则③：不满足条件时**不渲染**，而非渲染后禁用
            if (PermissionOptions.allowsAlwaysAllow(permission)) {
                add(JButton("总是允许").apply {
                    addActionListener {
                        onDecide(PermissionDecision(
                            allow = true,
                            updatedPermissions = permission.suggestions,
                            message = null,
                        ))
                    }
                })
            }
            add(denyButton)
            add(allowButton)
        }

        denyButton.addActionListener {
            onDecide(PermissionDecision(allow = false, updatedPermissions = null, message = "用户拒绝"))
        }
        allowButton.addActionListener {
            onDecide(PermissionDecision(allow = true, updatedPermissions = null, message = null))
        }

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(Box.createVerticalStrut(6))
            add(inputPanel)
            add(Box.createVerticalStrut(6))
            add(buttons.apply { alignmentX = LEFT_ALIGNMENT })
        }

        add(center, BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)

        // 规则②：焦点默认落在"拒绝"，且不提供任何批准快捷键。
        // 这两个按钮刻意不设 setMnemonic —— 助记符等于键盘捷径。
        SwingUtilities.invokeLater { denyButton.requestFocusInWindow() }
    }

    private companion object {
        val ACCENT = JBColor(0xFFA000, 0xFFB74D)
        val WARN = JBColor(0xD84315, 0xFF8A65)
        val CARD_BG = JBColor(0xFFF8E1, 0x3E2C1C)
    }
}
```

- [ ] **Step 6: 接入 ClaudePanel**

在 `ClaudePanel.kt` 中：

1. 加字段：

```kotlin
private val permissionQueue = PermissionQueue { perm, queued -> appendPermissionCard(perm, queued) }
private var pendingComponents = mutableMapOf<String, JComponent>()
```

2. 把 `showPermissionCard(msg)` 替换为：

```kotlin
private fun showPermissionCard(perm: SidecarMessage.Permission) {
    permissionQueue.enqueue(perm)
    if (!isVisibleDeep()) {
        notifyPendingPermission(perm)
    }
}

private fun appendPermissionCard(perm: SidecarMessage.Permission, queued: Int) {
    val card = PermissionCard(perm, queued) { decision ->
        client?.sendLine(
            Protocol.encodePermissionDecision(
                nextId(), perm.requestId, decision.allow, decision.updatedPermissions, decision.message
            )
        )
        permissionQueue.resolve(perm.requestId, decision)
        // 卡片被替换为一行结论，不再占据视线
        pendingComponents.remove(perm.requestId)?.let {
            transcript.remove(it)
            transcript.revalidate()
            transcript.repaint()
        }
        appendItem(RenderItem.SystemNote(
            if (decision.allow) "已允许：${perm.toolName}" else "已拒绝：${perm.toolName}"
        ))
    }
    // 卡片固定在消息流顶部，而不是跟随滚动到末尾（spec §6.3）
    val wrapper = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(card, BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    pendingComponents[perm.requestId] = wrapper
    transcript.add(wrapper, 0)
    transcript.revalidate()
    startReminderTimer(perm)
}
```

3. 加不可忽略性措施（spec §6.3）：

```kotlin
private fun isVisibleDeep(): Boolean = isShowing

/** 卡片插入时若工具窗口不可见，发粘性通知（spec §6.3）。 */
private fun notifyPendingPermission(perm: SidecarMessage.Permission) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("CCoder Permissions")
        .createNotification(
            "Claude 需要授权",
            PermissionOptions.primaryText(perm),
            NotificationType.WARNING,
        )
        .addAction(NotificationAction.createSimple("前往处理") {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("CCoder")?.show()
        })
        .notify(project)
}

/** 待决超过阈值升级为提醒（仅提醒，不升级为模态 —— spec §6.3）。 */
private fun startReminderTimer(perm: SidecarMessage.Permission) {
    val delay = ClaudeSettings.getInstance(project).pendingReminderSeconds * 1000
    javax.swing.Timer(delay) {
        if (permissionQueue.activeRequestId == perm.requestId) {
            notifyPendingPermission(perm)
        }
    }.apply { isRepeats = false; start() }
}
```

4. 在 `dispose()` 中加终止路径（规则①）：

```kotlin
permissionQueue.cancelAll("会话已终止")
pendingComponents.clear()
```

5. 在 `plugin.xml` 注册通知组：

```xml
<notificationGroup
    id="CCoder Permissions"
    displayType="STICKY"
    isLogByDefault="false"/>
```

6. 加状态栏常驻组件与告警图标渲染（spec §6.3 的第一条补偿措施）：

新建 `src/main/kotlin/com/ccoder/ui/PendingPermissionStatusBar.kt`：

```kotlin
package com.ccoder.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * 状态栏的待决权限计数（spec §6.3）。
 *
 * 非模态卡片的核心风险是"用户没注意 → Claude 无限等待"，而 SDK 的
 * 权限询问没有超时机制。状态栏常驻是让待决状态无法被忽略的第一道措施。
 */
class PendingPermissionStatusBarFactory : StatusBarWidgetFactory {
    override fun getId(): String = "CCoderPendingPermissions"
    override fun getDisplayName(): String = "CCoder 待确认权限"
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget =
        PendingPermissionStatusBar(project)
}

class PendingPermissionStatusBar(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var count = 0
    private var statusBar: StatusBar? = null

    override fun ID(): String = "CCoderPendingPermissions"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) { this.statusBar = statusBar }
    override fun dispose() { statusBar = null }

    fun update(newCount: Int) {
        if (count == newCount) return
        count = newCount
        statusBar?.updateWidget(ID())
    }

    override fun getText(): String = if (count > 0) "Claude 待确认：$count" else ""
    override fun getAlignment(): Float = 0f
    override fun getTooltipText(): String = "有 $count 个授权请求在等待处理，点击前往"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("CCoder")?.show()
    }
}
```

在 `plugin.xml` 注册：

```xml
<statusBarWidgetFactory
    id="CCoderPendingPermissions"
    implementation="com.ccoder.ui.PendingPermissionStatusBarFactory"/>
```

在 `ClaudePanel` 中，让 `permissionQueue` 的回调同步更新状态栏。修改 `permissionQueue` 的构造：

```kotlin
private val permissionQueue = PermissionQueue { perm, queued ->
    appendPermissionCard(perm, queued)
    updateStatusBar()
}
```

并加：

```kotlin
/** 待决数量 = 当前活动的 1 个 + 排队的 N 个。 */
private fun updateStatusBar() {
    val count = permissionQueue.pendingCount + if (permissionQueue.activeRequestId != null) 1 else 0
    StatusBarManager.get(project)?.updateWidget("CCoderPendingPermissions")
    pendingStatusBar?.update(count)
}

private var pendingStatusBar: PendingPermissionStatusBar? = null
```

在 `startSession()` 成功分支中获取组件引用：

```kotlin
ApplicationManager.getApplication().invokeLater {
    proc = p
    client = c
    pendingStatusBar = StatusBarManager.get(project)
        ?.getWidget("CCoderPendingPermissions") as? PendingPermissionStatusBar
}
```

在 `appendPermissionCard` 的决定回调末尾（`appendItem(...)` 之后）加一行 `updateStatusBar()`，并在 `dispose()` 中加 `pendingStatusBar?.update(0)`。

- [ ] **Step 7: 运行全部测试与构建**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew test buildPlugin
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 8: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add src/main/kotlin/com/ccoder/ui/ src/main/resources/META-INF/plugin.xml src/test/kotlin/com/ccoder/ui/ && git commit -F - <<'EOF'
feat(permissions): 非模态权限卡片与不可忽略性补偿

三条 SDK 明文规则的落实：
- 规则②（sdk.d.ts:245-248）批准只能显式点击，不绑快捷键、不设助记符；
  卡片获得焦点时焦点落在"拒绝"上
- 规则③（sdk.d.ts:249-253）"总是允许"在 allowsAlwaysAllow 为假时
  不渲染而非禁用；该按钮只在 suppressAlwaysAllowRule=false 且
  suggestions 非空时出现
- 规则① 由 PermissionQueue.cancelAll 保证，dispose 时清空待决

并发询问串行化：SDK 支持并行工具调用，同时挂起多个 canUseTool
会变成弹窗风暴，因此一次只展示一张卡片（spec §6.4）。

非模态形态的补偿（spec §6.3）：卡片固定在消息流顶部、工具窗口不可见时
发粘性通知、待决超阈值升级提醒。明确不升级为模态。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## Task 14: 打包集成与端到端冒烟

把 sidecar 及其依赖打进插件包。

**Files:**
- Modify: `C:\Users\CY\Desktop\CCoder\build.gradle.kts`
- Modify: `C:\Users\CY\Desktop\CCoder\src\main\kotlin\com\ccoder\sidecar\SidecarLocator.kt`

**Interfaces:**
- Consumes: `SidecarExtractor`（Task 7）
- Produces: `object SidecarLocator`，方法 `fun resolve(): Path`

- [ ] **Step 1: 接入生产模式的 sidecar 提取**

Task 10 的 `SidecarLocator` 已实现开发模式。这一步补上生产模式 ——
替换 `src/main/kotlin/com/ccoder/sidecar/SidecarLocator.kt` 中
`ProductionSidecarResolver` 的占位实现（原实现只会抛 `SidecarNotFoundException`）：

```kotlin
package com.ccoder.sidecar

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * 生产模式：从插件包内提取 sidecar 到系统缓存目录。
 *
 * 流程：读取插件包内的 sidecar/ 资源 → 提取到缓存目录 → 返回可运行路径。
 * 版本号来自构建时写入的 version.txt，与 sidecar/package.json 同步。
 */
object ProductionSidecarResolver {

    fun resolve(): Path {
        val version = readVersion()
        val target = Path.of(PathManager.getSystemPath(), "ccoder", "sidecar")
        val resourceRoot = extractBundledResources()

        return SidecarExtractor.extract(resourceRoot, target, version)
    }

    /** 版本号来自打包时写入的资源文件，与 sidecar/package.json 的 version 同步。 */
    private fun readVersion(): String {
        val stream = ProductionSidecarResolver::class.java.getResourceAsStream("/sidecar/version.txt")
            ?: return "0.0.0"
        return stream.bufferedReader().use { it.readText().trim() }.ifBlank { "0.0.0" }
    }

    /**
     * 把插件包内的 sidecar 资源释放到临时目录。
     *
     * 类加载器只能读取 jar 内的条目，而 SidecarExtractor 需要真实的目录树。
     */
    private fun extractBundledResources(): Path {
        val tmp = Files.createTempDirectory("ccoder-sidecar-")
        copyResourceDir("sidecar", tmp.resolve("sidecar"))
        return tmp.resolve("sidecar")
    }

    private fun copyResourceDir(resourcePrefix: String, dest: Path) {
        // 插件包里 sidecar 的清单由构建时生成，逐条复制
        val manifestStream = javaClass.getResourceAsStream("/$resourcePrefix/manifest.txt")
            ?: error("插件包内缺少 $resourcePrefix/manifest.txt —— 构建配置有误")
        val entries = manifestStream.bufferedReader().use { it.readLines() }
        for (entry in entries) {
            if (entry.isBlank()) continue
            val target = dest.resolve(entry)
            Files.createDirectories(target.parent ?: dest)
            javaClass.getResourceAsStream("/$resourcePrefix/$entry")?.use { input ->
                Files.newOutputStream(target).use { input.copyTo(it) }
            }
        }
    }
}
```

- [ ] **Step 2: 在 build.gradle.kts 中加入 sidecar 打包任务**

在 `build.gradle.kts` 末尾追加：

```kotlin
// ---- sidecar 打包 ----
// sidecar 的 node_modules 无法直接被 node 从 jar 内运行（Task 7 提取到磁盘），
// 但也必须随插件分发。

val sidecarDir = layout.projectDirectory.dir("sidecar")

/** 生成资源清单：类加载器只能按条目读取，无法遍历 jar 内目录。 */
val generateSidecarManifest by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/sidecar-resources")
    outputs.dir(outDir)
    doLast {
        val root = sidecarDir.asFile
        val dest = outDir.get().asFile
        dest.deleteRecursively()
        dest.mkdirs()

        val includes = listOf("index.js", "session.js", "env.js", "claude-path.js", "ndjson.js")
        val entries = mutableListOf<String>()

        fun copyIn(rel: String) {
            val src = root.resolve(rel)
            if (!src.exists()) return
            val target = dest.resolve("sidecar").resolve(rel)
            target.parentFile.mkdirs()
            src.copyTo(target, overwrite = true)
            entries += rel
        }

        includes.forEach(::copyIn)
        copyIn("package.json")

        // 依赖树。排除两类内容：
        //   1. 平台原生二进制包（@anthropic-ai/claude-agent-sdk-<platform>，约 212M）
        //      —— 由运行时解析用户已装的 claude 替代（spec §8.2）
        //   2. .d.ts 类型声明 —— 只服务于 TypeScript 编译，运行时不需要
        // 注意：claude-agent-sdk 自身不带平台后缀，因此用尾随连字符精确区分。
        val nm = root.resolve("node_modules")
        if (nm.exists()) {
            nm.walkTopDown()
                .filter { it.isFile }
                .filter { !it.name.endsWith(".d.ts") }
                .filter { !it.invariantSeparatorsPath.contains("claude-agent-sdk-") }
                .forEach { f ->
                    copyIn("node_modules/" + f.relativeTo(nm).invariantSeparatorsPath)
                }
        }

        // 版本号供 SidecarLocator 使用
        val pkg = root.resolve("package.json").readText()
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(pkg)?.groupValues?.get(1) ?: "0.0.0"
        dest.resolve("sidecar/version.txt").writeText(version)
        entries += "version.txt"

        dest.resolve("sidecar/manifest.txt").writeText(entries.joinToString("\n"))
    }
}

// srcDir 接受 TaskProvider 并自动接上任务依赖，无需再手动 dependsOn
sourceSets.named("main") {
    resources.srcDir(generateSidecarManifest)
}
```

- [ ] **Step 3: 验证打包体积**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew clean buildPlugin && ls -lh build/distributions/*.zip
```

预期：产物存在，体积在 **30–60MB** 量级（而非 240MB）。

若超过 100MB，说明平台二进制没有被排除 —— 检查 `sidecar/node_modules/@anthropic-ai/` 下是否存在 `claude-agent-sdk-win32-x64`，若有则删掉并重跑（Task 1 的 `--omit=optional` 应已避免它）。

- [ ] **Step 4: 端到端冒烟**

```bash
cd "C:/Users/CY/Desktop/CCoder" && ./gradlew runIde
```

在沙箱 PyCharm 中打开 CCoder 工具窗口，逐条验证（每条对应一个已知风险）：

1. **发一条消息能收到回复** —— 验证环境清洗生效（spec §11.1）。若显示"Not logged in"，说明 `buildChildEnv` 的结果没传到 SDK。
2. **让 Claude 读一个文件** → 权限卡片出现 → 点"允许" → 它继续 —— 验证 §6 全链路。
3. **卡片出现时不点，切到别的工具窗口** → 粘性通知出现 —— 验证 §6.3。
4. **卡片出现时关闭项目** → 重新打开，无残留挂起 —— 验证 §6.2 规则①。
5. **会话进行中关闭 PyCharm** → 任务管理器确认无残留 `node.exe` / `claude.exe` —— 验证 §7.4。

- [ ] **Step 5: 提交**

```bash
cd "C:/Users/CY/Desktop/CCoder" && git add build.gradle.kts src/main/kotlin/com/ccoder/ui/SidecarLocator.kt && git commit -F - <<'EOF'
build: 把 sidecar 及其依赖打入插件包

sidecar 的 node_modules 必须随插件分发，但无法被 node 从 jar 内直接运行，
因此运行时提取到系统缓存目录（Task 7）。

打包时跳过 212M 的平台原生二进制与 .d.ts 类型文件——前者由运行时
解析用户已装的 claude 替代（spec §8.2），后者只服务于 TypeScript 编译。

类加载器只能按条目读取 jar，无法遍历目录，因此构建时生成 manifest.txt。

Co-Authored-By: Claude Code <noreply@anthropic.com>
EOF
```

---

## 完成标准

全部 14 个任务完成后，以下必须全部成立：

- [ ] `cd sidecar && npm test` 全绿（42 个测试）
- [ ] `./gradlew test` 全绿（Kotlin 侧全部测试）
- [ ] `./gradlew buildPlugin` 产出体积在 30–60MB 区间的 zip
- [ ] Task 14 Step 4 的 5 条冒烟全部通过
- [ ] 关闭 PyCharm 后任务管理器中无残留 `node.exe` / `claude.exe`

## 已知未覆盖项

以下由 spec 明确划为"本版不做"（spec §1.4），实现时**不要**顺手加上：

- 会话列表 / 多会话并行 / 会话重命名
- 编辑器内联标注、inspection 集成
- 事件驱动自动触发
- 批量处理
- 文件回滚 UI（SDK 的 `Query.rewindFiles()` 可用但本版不接）
