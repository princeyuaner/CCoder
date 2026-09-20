import { existsSync, statSync, readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { defaultT } from './strings.js';

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

/**
 * claude 的候选文件名。
 *
 * **导出是为了让 Kotlin 侧那份镜像能被钉住**：设置页的「运行依赖」检测在
 * `src/main/kotlin/com/ccoder/sidecar/RuntimeDeps.kt` 里有一份逐字副本，
 * `ClaudePathTablesSyncTest` 会跑 node 把这两张表打印出来逐字比对 ——
 * 只改一边，那条用例就红。
 */
export const CANDIDATE_NAMES = {
  win32: ['claude.exe', 'claude.cmd', 'claude'],
  posix: ['claude'],
};

/**
 * 已知安装目录（三级解析的第三级），模板形式：`%VAR%` / `~` / `*`（nvm 的版本位）。
 *
 * 为什么必须有这一级：**装完 node/claude 之后，IDE 进程的 PATH 不会刷新**
 * （那是启动时继承的）。只认 PATH 的话，设置页检测会绿、而开会话仍然 CLAUDE_NOT_FOUND。
 *
 * 与 Kotlin 侧同名表逐字镜像（同 `CANDIDATE_NAMES`），被同步用例钉住。
 * 两张依赖共用这一张表：每项只是一个 isFile，省掉"谁住哪"的记账。
 */
export const KNOWN_DIRS = {
  win32: [
    '%ProgramFiles%\\nodejs',
    '%ProgramFiles(x86)%\\nodejs',
    '%APPDATA%\\npm',
    '%USERPROFILE%\\.local\\bin',
    '%LOCALAPPDATA%\\Microsoft\\WindowsApps',
    '%LOCALAPPDATA%\\Programs\\nodejs',
    '%USERPROFILE%\\scoop\\shims',
    '%ProgramData%\\chocolatey\\bin',
    '%LOCALAPPDATA%\\Volta\\bin',
  ],
  darwin: [
    '/opt/homebrew/bin',
    '/usr/local/bin',
    '~/.local/bin',
    '~/.volta/bin',
    '~/.bun/bin',
    '~/.nvm/versions/node/*/bin',
  ],
  linux: [
    '/usr/local/bin',
    '/usr/bin',
    '~/.local/bin',
    '/snap/bin',
    '~/.nvm/versions/node/*/bin',
  ],
};

function namesFor(platform) {
  // .exe 优先：原生安装的 claude 是真正的可执行文件，能直接被 spawn
  return platform === 'win32' ? CANDIDATE_NAMES.win32 : CANDIDATE_NAMES.posix;
}

// PATH 分隔符也必须由注入的 platform 决定，而非宿主平台——
// 否则测试无法构造跨平台场景（Windows 上是 ';'，POSIX 上是 ':'）。
function separatorFor(platform) {
  return platform === 'win32' ? ';' : ':';
}

const SHIM_EXT = /\.(cmd|bat|ps1)$/i;

/**
 * 把 Windows 批处理垫片解析到它指向的真实可执行文件。
 *
 * 为什么必须做：npm 全局包在 Windows 上装的是 `claude.cmd` 垫片，
 * 而 Node 无法直接 spawn `.cmd` —— CVE-2024-27980 之后这类文件必须经
 * shell 执行，而 SDK 内部用 spawn 调起 CLI，不给我们传 shell 的机会。
 * 实测症状是 `Error: spawn EINVAL`。
 *
 * npm 生成的垫片格式稳定，内容形如：
 *   "%dp0%\node_modules\@anthropic-ai\claude-code\bin\claude.exe"   %*
 * 把 %dp0% 替换为垫片所在目录即可得到真实路径。
 *
 * @returns 真实可执行文件路径；无法解析时返回 null
 */
function resolveShimTarget(shimPath, platform) {
  if (platform !== 'win32') return null;
  if (!SHIM_EXT.test(shimPath)) return null;

  let content;
  try {
    content = readFileSync(shimPath, 'utf8');
  } catch {
    return null;
  }

  const match = content.match(/["']([^"']*\.exe)["']/i);
  if (!match) return null;

  const dir = dirname(shimPath);
  const resolved = match[1]
    .replace(/%~?dp0%~?/gi, dir)
    .replace(/\$basedir/g, dir)
    .replace(/\\{2,}/g, '\\');

  return isFile(resolved) ? resolved : null;
}

/**
 * 校验候选路径可直接执行。/ 无法解析的垫片一律拒绝 —— 返回它只会让问题
 * 推迟到 SDK 内部炸成 EINVAL，在那里错误信息毫无指向性。
 *
 * @param {string} where 这条路径**是哪来的**（已翻好的半句，如"PATH 中的"）。
 *   由调用点各自 `t('error.claudeShimWhere…')` 取好再传进来 —— 在这里按变量取键
 *   的话，源码扫描就看不见那三个键了（引用的键都在、词表里的键都有人引用，
 *   靠的就是字面量调用）。中文把它当前缀用、英文得在它后面加空格，
 *   所以整句的模板在词表里（`error.claudeShimUnresolved`），不在这里拼
 */
function accept(candidate, platform, where, t) {
  if (SHIM_EXT.test(candidate)) {
    const target = resolveShimTarget(candidate, platform);
    if (target) return target;
    throw new ClaudeNotFoundError(
      t('error.claudeShimUnresolved', { 0: where, 1: candidate })
    );
  }
  return candidate;
}

/** nvm 那一项的版本位：`v9` 与 `v20` 按字典序会挑错，必须按数字比。 */
function highestVersionDir(names) {
  const parsed = [];
  for (const name of names) {
    const m = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(name.trim());
    if (m) parsed.push({ name, key: [Number(m[1]), Number(m[2]), Number(m[3])] });
  }
  if (parsed.length === 0) return null;
  parsed.sort((a, b) => b.key[0] - a.key[0] || b.key[1] - a.key[1] || b.key[2] - a.key[2]);
  return parsed[0].name;
}

/**
 * 展开一条目录模板。`%VAR%` / `~` / `*` 三个位置。
 *
 * 变量缺失时**整条丢弃**，不能只把它替换成空串：`%ProgramFiles%\nodejs` 会变成
 * `\nodejs`，那是指向当前盘根目录的路径 —— 会去测一个根本不存在的地方。
 */
function expandDir(template, env) {
  let missing = false;
  let text = template.replace(/%([^%]+)%/g, (_, name) => {
    const value = env[name];
    if (value === undefined || value === '') {
      missing = true;
      return '';
    }
    return value;
  });
  if (missing) return [];

  if (text.startsWith('~')) {
    const home = env.HOME || env.USERPROFILE || '';
    if (!home) return [];
    text = home + text.slice(1);
  }

  if (!text.includes('*')) return [text];

  const star = text.indexOf('*');
  const parent = text.slice(0, star);
  const tail = text.slice(star + 1);
  let entries = [];
  try {
    entries = readdirSync(parent);
  } catch {
    return [];
  }
  const picked = highestVersionDir(entries);
  return picked ? [parent + picked + tail] : [];
}

/**
 * 解析 claude 可执行文件路径。
 *
 * 插件不打包 212M 的平台二进制（spec §8.2），改为运行时解析用户已安装的那份。
 *
 * **三级**（spec §8.2 原定三级，第三级 2026-09-17 才补上）：
 * 设置里的显式路径 → PATH → 已知安装目录（[KNOWN_DIRS]）。
 *
 * @param {object} opts
 * @param {string} [opts.explicit] 设置中显式指定的路径，优先级最高
 * @param {object} opts.env        用于读取 PATH 的环境对象
 * @param {string} [opts.platform] 默认 process.platform，测试时可注入
 * @param {Function} [opts.t]      取词器（见 strings.js）。默认现读环境变量
 * @returns {string} 可直接被 spawn 的可执行文件绝对路径
 * @throws {ClaudeNotFoundError}
 */
export function resolveClaudePath({
  explicit,
  env = process.env,
  platform = process.platform,
  t = defaultT(),
} = {}) {
  if (explicit && explicit.trim()) {
    if (isFile(explicit)) {
      return accept(explicit, platform, t('error.claudeShimWhereExplicit'), t);
    }
    // 显式指定却不存在 —— 报错而非静默回退。
    // 静默回退会让用户以为设置生效了，实际用的是 PATH 里另一个 claude。
    throw new ClaudeNotFoundError(t('error.claudePathMissing', { 0: explicit }));
  }

  const names = namesFor(platform);
  const dirs = (env.PATH || '').split(separatorFor(platform)).filter(Boolean);

  for (const dir of dirs) {
    for (const name of names) {
      const full = join(dir, name);
      if (isFile(full)) return accept(full, platform, t('error.claudeShimWherePath'), t);
    }
  }

  // 第三级：已知安装目录。装完 node/claude 之后 IDE 的 PATH 不会刷新，
  // 用户刚装好就点「启动会话」时，能找到它的只有这一级。
  const known = (KNOWN_DIRS[platform] || KNOWN_DIRS.linux).flatMap((tpl) => expandDir(tpl, env));
  for (const dir of known) {
    for (const name of names) {
      const full = join(dir, name);
      if (isFile(full)) return accept(full, platform, t('error.claudeShimWhereKnownDir'), t);
    }
  }

  throw new ClaudeNotFoundError(t('error.claudeNotFound'));
}
