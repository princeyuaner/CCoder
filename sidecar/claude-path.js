import { existsSync, statSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';

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
  // .exe 优先：原生安装的 claude 是真正的可执行文件，能直接被 spawn
  return platform === 'win32' ? ['claude.exe', 'claude.cmd', 'claude'] : ['claude'];
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
 */
function accept(candidate, platform, context) {
  if (SHIM_EXT.test(candidate)) {
    const target = resolveShimTarget(candidate, platform);
    if (target) return target;
    throw new ClaudeNotFoundError(
      `${context}指向批处理垫片，但无法解析出其中的真实可执行文件：\n  ${candidate}\n` +
      'Node 在 Windows 上无法直接运行 .cmd 文件。请在插件设置中直接指定 claude.exe 的完整路径。'
    );
  }
  return candidate;
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
 * @returns {string} 可直接被 spawn 的可执行文件绝对路径
 * @throws {ClaudeNotFoundError}
 */
export function resolveClaudePath({
  explicit,
  env = process.env,
  platform = process.platform,
} = {}) {
  if (explicit && explicit.trim()) {
    if (isFile(explicit)) return accept(explicit, platform, '设置中指定的路径');
    // 显式指定却不存在 —— 报错而非静默回退。
    // 静默回退会让用户以为设置生效了，实际用的是 PATH 里另一个 claude。
    throw new ClaudeNotFoundError(`设置中指定的 claude 路径不存在：${explicit}`);
  }

  const names = namesFor(platform);
  const dirs = (env.PATH || '').split(separatorFor(platform)).filter(Boolean);

  for (const dir of dirs) {
    for (const name of names) {
      const full = join(dir, name);
      if (isFile(full)) return accept(full, platform, 'PATH 中的');
    }
  }

  throw new ClaudeNotFoundError(
    '未找到 claude 可执行文件。请在插件设置中指定其完整路径。'
  );
}
