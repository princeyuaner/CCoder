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
 * 黑名单里**允许被插件显式重新引入**的那一项。
 *
 * 黑名单挡的是"继承来的"那一份：宿主环境里带着它，CLI 就会去剥
 * `~/.claude/settings.json` 里的凭证，而那时插件没给任何凭证 —— 于是
 * authentication_failed（spec §11.1）。
 *
 * 但同一个开关正是"宿主自己管端点与凭证"的官方信号：为真时 CLI 会把
 * settings 来源的 `ANTHROPIC_BASE_URL` / `ANTHROPIC_API_KEY` /
 * `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_CUSTOM_HEADERS` 等一律剥掉
 * （CLI 内部 `Nbe()`，逐字列表见 spec §6.1）。选中了模型配置的会话要的就是
 * 这个：端点与密钥都来自配置，settings.json 不该再插一脚。
 *
 * 所以规则是**分层的**：继承来的照剥，插件显式给的放行。
 *
 * 2026-09-14 实测（同一个 CLI 2.1.268）：settings 里放毒饵
 * （`ANTHROPIC_BASE_URL=http://127.0.0.1:9` + 假密钥），进程环境给真端点真密钥
 * 并带上这个开关 —— 请求正常返回，毒饵一个都没用上；不带开关时则是
 * settings 的端点赢、配置的密钥却留着 → `401 Invalid token`（就是用户那次
 * "问了没回复"）。
 */
export const HOST_ENV_OVERRIDABLE = Object.freeze([
  'CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST',
]);

/**
 * 构建传给 claude 子进程的环境。
 *
 * @param {object} baseEnv   基础环境（通常是 process.env）
 * @param {object} overrides 插件追加的环境变量。黑名单项一律拒绝，
 *   唯 [HOST_ENV_OVERRIDABLE] 里的那几项例外
 * @returns {object} 清洗后的环境对象
 */
export function buildChildEnv(baseEnv, overrides = {}) {
  const blacklist = new Set(HOST_ENV_BLACKLIST);
  const overridable = new Set(HOST_ENV_OVERRIDABLE);
  const out = {};

  for (const [k, v] of Object.entries(baseEnv ?? {})) {
    if (v === undefined) continue;
    if (blacklist.has(k)) continue;
    out[k] = v;
  }

  for (const [k, v] of Object.entries(overrides ?? {})) {
    if (v === undefined) continue;
    if (blacklist.has(k) && !overridable.has(k)) {
      // 黑名单是正确性保证而非偏好：放行会重新引入认证失败。
      // 这里只记 stderr——sidecar 的 stdout 必须保持纯 NDJSON。
      console.error(`[ccoder] 忽略环境变量覆盖 "${k}"：该变量在宿主隔离黑名单中`);
      continue;
    }
    out[k] = v;
  }

  return out;
}
