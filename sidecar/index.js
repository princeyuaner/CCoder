#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  listSessions as sdkListSessions,
  getSessionMessages as sdkGetSessionMessages,
  deleteSession as sdkDeleteSession,
} from '@anthropic-ai/claude-agent-sdk';
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
 * @param {object}   [deps.sessionApi]   { listSessions, getSessionMessages, deleteSession }。
 *   这几个是 SDK 的**独立函数**，不属于任何会话 —— 注入是为了把真实 SDK
 *   挡在单测之外。生产路径用真实实现（默认值就是它）。
 */
export function createDispatcher({
  sessionFactory,
  out,
  sessionApi = {
    listSessions: sdkListSessions,
    getSessionMessages: sdkGetSessionMessages,
    deleteSession: sdkDeleteSession,
  },
}) {
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
        const { requestId, behavior, updatedPermissions, updatedInput, message } = params;
        const result = behavior === 'allow'
          ? { behavior: 'allow' }
          : { behavior: 'deny', message: message ?? '用户拒绝' };
        if (updatedPermissions) result.updatedPermissions = updatedPermissions;
        // AskUserQuestion 的答案就走这条路：允许这个工具调用时改写它的入参。
        // 只在给到时才加字段 —— 塞一个空对象等于"显式改写成了空"
        if (updatedInput) result.updatedInput = updatedInput;
        session.decidePermission(requestId, result);
        return session;
      }

      // 列表与回放**不需要活会话** —— 它们是 SDK 的独立函数。
      // 这是设计的关键点：面板一打开就能列出历史，不必先起一个会话。
      case 'listSessions': {
        // 刻意**不传** includeProgrammatic。SDK 文档说 "IDE session pickers
        // pass false for parity with terminal /resume"，看着正该传 —— 但实测
        // 那会把 19 条会话全滤光：插件自己开的会话也是 SDK 会话
        // （entrypoint sdk-ts），会一并被滤掉，于是列表里永远看不到自己刚
        // 恢复过的那个。传 false 不报错，只是安静地返回空，所以这个坑不
        // 实测根本看不出来（spec §4.2）。
        const { dir, limit = 50, offset = 0 } = params;
        Promise.resolve(sessionApi.listSessions({ dir, limit, offset }))
          .then((sessions) => out({
            type: 'sessions',
            id: msg.id,
            // 只把界面要用的字段送过线。SDK 的 SDKSessionInfo 有十来个字段，
            // 全送过去等于把 SDK 的结构钉进协议里
            sessions: (sessions ?? []).map((s) => ({
              sessionId: s.sessionId,
              summary: s.summary ?? null,
              firstPrompt: s.firstPrompt ?? null,
              lastModified: s.lastModified ?? 0,
            })),
          }))
          .catch((err) => fail('LIST_SESSIONS_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'loadHistory': {
        const { dir, sessionId } = params;
        Promise.resolve(sessionApi.getSessionMessages(sessionId, { dir }))
          .then((items) => out({
            type: 'history',
            id: msg.id,
            sessionId,
            // 条目原样透传：它们与流式事件同构，插件侧直接复用既有渲染管线
            items: items ?? [],
          }))
          .catch((err) => fail('LOAD_HISTORY_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'deleteSession': {
        // 与 listSessions 同一条：不需要活会话。删除是列表上的动作，
        // 要求先起会话等于让用户在删东西之前先建立连接 —— 没道理
        const sessionId = params.sessionId;
        if (typeof sessionId !== 'string' || sessionId === '') {
          fail('DELETE_FAILED', '删除会话缺少 sessionId', false);
          return session;
        }
        Promise.resolve(sessionApi.deleteSession({ sessionId }))
          .then(() => out({ type: 'sessionDeleted', id: msg.id, sessionId }))
          .catch((err) => fail('DELETE_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'interrupt':
        session?.interrupt?.();
        return session;

      case 'setPermissionMode': {
        // 必须 await 并回报结果。原先是不 await 的裸调用，拒绝会变成一条
        // unhandled rejection —— 界面上什么都看不见，用户以为切成功了。
        //
        // 成功与失败都要回话：这是个安全控件，显示一个没生效的模式
        // 比不好用严重得多（见 ClaudePanel 的标签更新逻辑）。
        const mode = params.mode;
        const call = session?.setPermissionMode;
        if (typeof call !== 'function') {
          // 没有会话（或会话没这个方法）时不能默默当成功 ——
          // Promise.resolve(undefined) 会 resolve，那就成了一条假回执
          fail('SET_MODE_FAILED', '当前会话不支持切换权限模式', false);
          return session;
        }
        Promise.resolve(call.call(session, mode))
          .then(() => out({ type: 'permissionModeChanged', mode }))
          .catch((err) => fail('SET_MODE_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'listCommands': {
        // 命令列表是会话的属性，没有会话就没有命令可报。打错是"问早了"
        // 而不是"出错了"——插件在 ready 之后才问，所以不致命
        const cmds = session?.supportedCommands;
        const skills = session?.skills;
        if (typeof cmds !== 'function' || typeof skills !== 'function') {
          fail('NO_SESSION', '会话尚未建立', false);
          return session;
        }
        // 两个方法自己吞掉异常回空数组（见 session.js），所以这里 .catch
        // 只兜真正意外的东西 —— 它是最后一道，不是唯一一道
        Promise.all([cmds.call(session), skills.call(session)])
          .then(([commands, skillList]) => out({
            type: 'commands',
            id: msg.id,
            commands,
            skills: skillList,
          }))
          .catch((err) => fail('LIST_COMMANDS_FAILED', String(err?.message ?? err), false));
        return session;
      }

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
