#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
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
          return session;
        }
        let created;
        try {
          created = sessionFactory({
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
          // 建会话失败时不能留下半成品 session，否则后续 send 会打到空对象上
          session = null;
          if (err instanceof ClaudeNotFoundError || err?.code === 'CLAUDE_NOT_FOUND') {
            fail('CLAUDE_NOT_FOUND', err.message, true);
          } else {
            fail('SDK_INIT_FAILED', String(err?.message ?? err), true);
          }
          return null;
        }
        session = created;
        out({ type: 'ready', sessionId: params.sessionId ?? null, model: params.model ?? null });
        for (const text of preStartQueue.splice(0)) session.send(text);
        return session;
      }

      case 'send': {
        if (!session) {
          // 排队而非丢弃 —— 用户可能抢在 ready 之前就发了消息
          preStartQueue.push(params.text ?? '');
          return session;
        }
        session.send(params.text ?? '');
        return session;
      }

      case 'permissionDecision': {
        if (!session) return session;
        const { requestId, behavior, updatedPermissions, message } = params;
        const result = behavior === 'allow'
          ? { behavior: 'allow' }
          : { behavior: 'deny', message: message ?? '用户拒绝' };
        if (updatedPermissions) result.updatedPermissions = updatedPermissions;
        session.decidePermission(requestId, result);
        return session;
      }

      case 'interrupt':
        session?.interrupt?.();
        return session;

      case 'setPermissionMode':
        session?.setPermissionMode?.(params.mode);
        return session;

      case 'stop':
        // 顺序重要：先清空待决权限，否则工具会挂住
        session?.denyAllPending?.('会话已终止');
        session?.stop?.();
        session = null;
        return session;

      default:
        fail('UNKNOWN_METHOD', `未知方法：${method}`, false);
        return session;
    }
  }

  return { handle, getSession: () => session };
}

/** 判定当前模块是否为程序入口。 */
function isMainModule() {
  const entry = process.argv[1];
  if (!entry) return false;
  try {
    // realpath 比对：Windows 下 import.meta.url 是 file:///C:/... 而
    // process.argv[1] 是 C:\...，直接拼字符串比对不成立
    return realpathSync(entry) === realpathSync(fileURLToPath(import.meta.url));
  } catch {
    return false;
  }
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

  let shuttingDown = false;
  const shutdown = () => {
    if (shuttingDown) return;
    shuttingDown = true;
    dispatcher.handle({ method: 'stop', params: {} });
  };

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
    if (!parsed.value || typeof parsed.value !== 'object' || Array.isArray(parsed.value)) {
      console.error('[ccoder] 忽略非对象输入');
      return;
    }
    try {
      dispatcher.handle(parsed.value);
    } catch (err) {
      write({ type: 'error', code: 'DISPATCH_FAILED', message: String(err?.message ?? err), fatal: false });
    }
  });

  rl.on('close', () => {
    shutdown();
    process.exit(0);
  });

  process.on('SIGTERM', () => { shutdown(); process.exit(0); });
  process.on('SIGINT', () => { shutdown(); process.exit(0); });
}

if (isMainModule()) {
  main();
}
