#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { existsSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  listSessions as sdkListSessions,
  getSessionMessages as sdkGetSessionMessages,
  deleteSession as sdkDeleteSession,
  renameSession as sdkRenameSession,
  tagSession as sdkTagSession,
  getSessionInfo as sdkGetSessionInfo,
  listSubagents as sdkListSubagents,
  getSubagentMessages as sdkGetSubagentMessages,
} from '@anthropic-ai/claude-agent-sdk';
import { NdjsonDecoder, encodeNdjson, parseLine } from './ndjson.js';
import { capHistoryImages } from './history-images.js';
import { createSession } from './session.js';
import { resolveClaudePath, ClaudeNotFoundError } from './claude-path.js';
import { applyProjectKey } from './project-key.js';

/**
 * SDK 的 EffortLevel 联合类型（sdk.d.ts:601）。
 *
 * 与插件侧的 `EffortSetting` 枚举是**同一份知识的两个副本**，看着像冗余，
 * 但这里挡的是另一种错：CLI 对认不出的**字符串**未必报错，可能直接忽略 ——
 * 那时我们会发出一条"切换成功"的回执，而档位根本没变。宁可现在说失败，
 * 也不要界面上出现一个没生效的档位。
 *
 * `'max'` 只在 applyFlagSettings 这条路上被接受，持久化的 Settings.effortLevel
 * 里没有它（sdk.d.ts:2700-2703）—— 我们走的正是这条路，所以它同样合法。
 */
const EFFORT_LEVELS = ['low', 'medium', 'high', 'xhigh', 'max'];

/**
 * 某条会话的子代理目录。
 *
 * 磁盘布局是 SDK 文档写明的：
 * `~/.claude/projects/<项目目录名>/<sessionId>/subagents/agent-<agentId>.jsonl`。
 *
 * **不自己拼那个"项目目录名"**：它是 cwd 转义出来的（`C:\a\b` → `C--a-b`），
 * 照抄一套转义规则等于把 CLI 的内部约定钉进来 —— 换个盘符、UNC 路径、
 * 或哪天转义改了，就静默找不到。扫一遍项目目录找 `<sessionId>` 那层，
 * 慢一点，但不会错。
 */
function subagentDirOf(projectsRoot, sessionId) {
  let projects;
  try {
    projects = readdirSync(projectsRoot);
  } catch {
    return null;
  }
  for (const name of projects) {
    const dir = join(projectsRoot, name, sessionId, 'subagents');
    if (existsSync(dir)) return dir;
  }
  return null;
}

/**
 * 一个子代理的元信息。
 *
 * 里面有 `agentType` / `description` / **`toolUseId`** —— 最后那个是界面能把它
 * 和"运行中的任务"对上号的唯一凭据（任务的 id 就是 tool_use id）。
 *
 * 读不到给空对象：列表里退化成只显示 agentId，总比整条请求失败强。
 */
function subagentMeta(dir, agentId) {
  if (!dir) return {};
  try {
    return JSON.parse(readFileSync(join(dir, `agent-${agentId}.meta.json`), 'utf8'));
  } catch {
    return {};
  }
}

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
    renameSession: sdkRenameSession,
    tagSession: sdkTagSession,
    getSessionInfo: sdkGetSessionInfo,
    listSubagents: sdkListSubagents,
    getSubagentMessages: sdkGetSubagentMessages,
  },
  // 子代理的元信息得自己去磁盘上读（SDK 只给 id 列表），所以根目录做成可注入的
  // —— 不然那段路径逻辑没法测，只能靠真实 home 目录，单测里跑不了
  projectsRoot = join(homedir(), '.claude', 'projects'),
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
        // 补发时**图和文字一起**带过去 —— 排队的那条里可能就贴着截图
        for (const item of preStartQueue.splice(0)) session.send(item.text, item.images);
        return session;
      }

      case 'send': {
        if (!session) {
          // 排队而非丢弃 —— 用户可能抢在 ready 之前就发了消息。
          // 存**对象**而不是字符串：截图也得跟着一起等（2026-09-15 贴图）
          preStartQueue.push({ text: params.text ?? '', images: params.images ?? [] });
          return session;
        }
        session.send(params.text ?? '', params.images ?? []);
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
              // 用户自己改的名字与标签 —— 列表显示时**名字优先于自动摘要**，
              // 不然改完名回到列表看到的还是原来那句摘要，等于没改
              customTitle: s.customTitle ?? null,
              tag: s.tag ?? null,
            })),
          }))
          .catch((err) => fail('LIST_SESSIONS_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'loadHistory': {
        const { dir, sessionId } = params;
        Promise.resolve(sessionApi.getSessionMessages(sessionId, { dir }))
          .then((items) => {
            // 条目照原样透传（它们与流式事件同构，插件侧复用既有渲染管线），
            // 只做一件事：**图按预算裁一裁**。整段历史是一条 JSON，CLI 又把图
            // 原样存着（完整 base64），不裁的话一个用过两周的会话能推出几十 MB
            // —— 见 history-images.js 里那份实测说明
            const capped = capHistoryImages(items ?? []);
            out({
              type: 'history',
              id: msg.id,
              sessionId,
              items: capped.items,
            });
          })
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
        // 位置参数，**不是** `{ sessionId }` —— SDK 的实现第一行就做 UUID 校验，
        // 传对象进去必然抛 "Invalid sessionId: [object Object]"。这个错假实现
        // 测不出来（它来者不拒），所以单测里钉的是**调用形状**而不是结果
        Promise.resolve(sessionApi.deleteSession(sessionId))
          .then(() => out({ type: 'sessionDeleted', id: msg.id, sessionId }))
          .catch((err) => fail('DELETE_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'renameSession':
      case 'tagSession': {
        // 与 deleteSession 同一条：都是**列表上的动作**，不需要活会话
        const sessionId = params.sessionId;
        if (typeof sessionId !== 'string' || sessionId === '') {
          fail('SESSION_UPDATE_FAILED', '改会话缺少 sessionId', false);
          return session;
        }
        // tag **允许是 null**（表示清除标签），所以判的是键在不在，不是值真不真
        const renaming = method === 'renameSession';
        const key = renaming ? 'title' : 'tag';
        if (!(key in params)) {
          fail('SESSION_UPDATE_FAILED', `缺少 ${key} 参数`, false);
          return session;
        }
        const value = params[key];

        const mutate = renaming
          ? sessionApi.renameSession(sessionId, value, { dir: params.dir })
          : sessionApi.tagSession(sessionId, value, { dir: params.dir });

        Promise.resolve(mutate)
          // 改完**回读**一次拿权威值，而不是回显我们刚发的东西：回读才知道写入
          // 真的落下了。读不回来就退回刚设的值 —— 改是改成了，因为读不回来
          // 就报失败是撒谎
          .then(() => sessionApi.getSessionInfo(sessionId, { dir: params.dir }).catch(() => null))
          .then((info) => {
            const read = info ? (renaming ? info.customTitle : info.tag) ?? null : value;
            out({
              type: renaming ? 'sessionRenamed' : 'sessionTagged',
              id: msg.id,
              sessionId,
              // 只带**变了的那一个**字段：改名不影响 tag，反之亦然。
              // 整行回传会让人以为别的字段也可能变了
              value: read,
            });
          })
          .catch((err) => fail('SESSION_UPDATE_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'listSubagents': {
        // 与 listSessions 同一条：读磁盘，不需要活会话
        const sessionId = params.sessionId;
        if (typeof sessionId !== 'string' || sessionId === '') {
          fail('SUBAGENTS_FAILED', '缺少 sessionId', false);
          return session;
        }
        Promise.resolve(sessionApi.listSubagents(sessionId, { dir: params.dir }))
          .then((ids) => {
            const dir = subagentDirOf(projectsRoot, sessionId);
            out({
              type: 'subagents',
              id: msg.id,
              agents: (ids ?? []).map((agentId) => {
                const meta = subagentMeta(dir, agentId);
                return {
                  agentId,
                  agentType: meta.agentType ?? null,
                  description: meta.description ?? null,
                  toolUseId: meta.toolUseId ?? null,
                };
              }),
            });
          })
          .catch((err) => fail('SUBAGENTS_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'subagentMessages': {
        const { sessionId, agentId } = params;
        if (typeof sessionId !== 'string' || typeof agentId !== 'string') {
          fail('SUBAGENT_MESSAGES_FAILED', '缺少 sessionId 或 agentId', false);
          return session;
        }
        // 不传 limit：转写本来就是给人看的，静默截断比慢一点坏得多
        Promise.resolve(
          sessionApi.getSubagentMessages(sessionId, agentId, { dir: params.dir })
        )
          .then((items) => out({
            type: 'subagentMessages',
            id: msg.id,
            agentId,
            items: items ?? [],
          }))
          .catch((err) => fail('SUBAGENT_MESSAGES_FAILED', String(err?.message ?? err), false));
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

      case 'setEffort': {
        // 与 setPermissionMode 同一套：await 并回报结果，成功与失败都要回话 ——
        // 失败而报成功的话，界面标签会显示一个没生效的档位。
        //
        // level **允许是 null**（表示回到「默认」＝把这一项从 flag 层清除），
        // 所以判的是**键在不在**，不是值真不真。写成 `params.level ?? null`
        // 会把"没给"和"明确要求清除"混成一种，而前者是协议出错 ——
        // 那不该默默清掉用户已经选好的档位。
        if (!('level' in params)) {
          fail('SET_EFFORT_FAILED', '缺少 level 参数', false);
          return session;
        }
        const level = params.level;
        if (level !== null && !EFFORT_LEVELS.includes(level)) {
          fail(
            'SET_EFFORT_FAILED',
            `不认识的思考深度：${JSON.stringify(level)}` +
              '（只接受 low/medium/high/xhigh/max 或 null）',
            false
          );
          return session;
        }
        const call = session?.setEffort;
        if (typeof call !== 'function') {
          // 没有会话，或会话没这个方法 —— 两种都不能默默当成功
          fail(
            'SET_EFFORT_FAILED',
            session ? '当前会话不支持调整思考深度' : '会话还没建立，思考深度要等连上会话再改',
            false
          );
          return session;
        }
        Promise.resolve(call.call(session, level))
          .then(() => out({ type: 'effortChanged', level }))
          .catch((err) => fail('SET_EFFORT_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'setModel': {
        // 与 setEffort 同一套：await 并回报结果，成功与失败都要回话 ——
        // 失败而报成功的话，界面标签会显示一个没生效的模型。
        //
        // **刻意不做名字白名单**（与 EFFORT_LEVELS 相反）。档位是个闭集，而且
        // CLI 对认不出的档位可能**静默忽略**，所以那边必须本地挡；模型名是用户
        // 在自己网关上定义的，sidecar 无从知道，认不出的名字会在**下一轮请求时
        // 响亮地失败**（模型不存在），不是静默 —— 本地挡只会把能用的名字限死成
        // 一份猜的清单。
        //
        // 但**空串要挡**：`setModel` 表达不了"不要模型"这个状态
        // （`setModel(undefined)` 不是清除），空名字只会变成一次莫名其妙的请求。
        const model = params.model;
        if (typeof model !== 'string' || model.trim() === '') {
          fail('SET_MODEL_FAILED', '缺少 model 参数（换模型没有"清空"这一档）', false);
          return session;
        }
        const call = session?.setModel;
        if (typeof call !== 'function') {
          // 没有会话，或会话没这个方法 —— 两种都不能默默当成功
          fail(
            'SET_MODEL_FAILED',
            session ? '当前会话不支持换模型' : '会话还没建立，换模型要等连上会话再改',
            false
          );
          return session;
        }
        // 回执**回显请求里那个字符串**，不去读 CLI 的解析结果：界面靠它做
        // "回的是不是我刚发的那个"的等值校验（见 ClaudePanel 的 pendingModelPick）
        Promise.resolve(call.call(session, model))
          .then(() => out({ type: 'modelChanged', model }))
          .catch((err) => fail('SET_MODEL_FAILED', String(err?.message ?? err), false));
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

      case 'contextUsage': {
        // 与 listCommands 同一条：它是会话的属性，没有会话就没得报。
        // 插件在 ready 之后才问，所以"问早了"不致命
        const call = session?.contextUsage;
        if (typeof call !== 'function') {
          fail('NO_SESSION', '会话尚未建立', false);
          return session;
        }
        Promise.resolve(call.call(session))
          .then((cu) => out({
            type: 'contextUsage',
            id: msg.id,
            // 字段名是**驼峰**（2026-09-14 实测），不是 d.ts 里写的 snake_case ——
            // 照 d.ts 读 total_tokens 会拿到 undefined
            usedTokens: cu?.totalTokens ?? 0,
            // 分母取 rawMaxTokens：文档说 usage 是 "measured against" 它，
            // percentage 也是拿它算的（maxTokens 是另一个，实测这里同值）
            windowTokens: cu?.rawMaxTokens ?? cu?.maxTokens ?? 0,
          }))
          .catch((err) => fail('CONTEXT_USAGE_FAILED', String(err?.message ?? err), false));
        return session;
      }

      case 'mcpServerStatus': {
        // 与 contextUsage 同一条：它是会话的属性，没有会话就没得报
        const call = session?.mcpServerStatus;
        if (typeof call !== 'function') {
          fail('NO_SESSION', '会话尚未建立', false);
          return session;
        }
        Promise.resolve(call.call(session))
          .then((list) => out({
            type: 'mcpServers',
            id: msg.id,
            // 字段逐个写默认值，**别把 undefined 漏过线** —— JSON 里它会整个消失，
            // 收端就得为"这个字段可能不在"多写一层防御
            servers: (list ?? []).map((s) => ({
              name: s?.name ?? '',
              // 原样透传，不当枚举认：CLI 将来加一档不该让我们这层崩
              status: s?.status ?? 'pending',
              scope: s?.scope ?? null,
              error: s?.error ?? null,
              tools: (s?.tools ?? []).map((t) => t?.name ?? ''),
            })),
          }))
          .catch((err) => fail('MCP_STATUS_FAILED', String(err?.message ?? err), false));
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
      // **每一条请求都先对一次目录名**（幂等，见 project-key.js）。必须赶在
      // 任何 SDK 调用之前 —— SDK 那边是记忆化的，第一次读过就定了。
      // 侧车自己的 cwd 是插件目录（ProcessBuilder.directory 给的是 sidecar/），
      // 所以只能从请求里拿项目目录：建会话是 cwd、历史那几条是 dir。
      const dir = parsed.value.params?.cwd ?? parsed.value.params?.dir;
      if (typeof dir === 'string' && dir) applyProjectKey(dir);
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
