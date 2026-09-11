import { existsSync, statSync } from 'node:fs';
import { join } from 'node:path';

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

// PATH 分隔符也必须由注入的 platform 决定，而非宿主平台——
// 否则测试无法构造跨平台场景（Windows 上是 ';'，POSIX 上是 ':'）。
function separatorFor(platform) {
  return platform === 'win32' ? ';' : ':';
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
export function resolveClaudePath({
  explicit,
  env = process.env,
  platform = process.platform,
} = {}) {
  if (explicit && explicit.trim()) {
    if (isFile(explicit)) return explicit;
    // 显式指定却不存在 —— 报错而非静默回退。
    // 静默回退会让用户以为设置生效了，实际用的是 PATH 里另一个 claude。
    throw new ClaudeNotFoundError(`设置中指定的 claude 路径不存在：${explicit}`);
  }

  const names = namesFor(platform);
  const dirs = (env.PATH || '').split(separatorFor(platform)).filter(Boolean);

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
