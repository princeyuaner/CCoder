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
    if (v === undefined) continue;
    if (blacklist.has(k)) continue;
    out[k] = v;
  }

  for (const [k, v] of Object.entries(overrides ?? {})) {
    if (v === undefined) continue;
    if (blacklist.has(k)) {
      // 黑名单是正确性保证而非偏好：放行会重新引入认证失败。
      // 这里只记 stderr——sidecar 的 stdout 必须保持纯 NDJSON。
      console.error(`[ccoder] 忽略环境变量覆盖 "${k}"：该变量在宿主隔离黑名单中`);
      continue;
    }
    out[k] = v;
  }

  return out;
}
