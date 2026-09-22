/**
 * 假的 Anthropic 端点：只回**一路慢慢流的思考**，用来量"中断"这类行为。
 *
 * 为什么要它：真端点不可控 —— 上游一旦 503（2026-09-21 实测这台机器上就是这个），
 * 探针连一轮都跑不起来，而"中断时 CLI 到底做了什么"这种问题恰恰要在**流没结束**
 * 的时候按下去才量得到。本地假流是唯一能重放的现场（同 spec §6.1 那次"放毒饵"
 * 的做法：`ANTHROPIC_BASE_URL` 指向本地 + 假密钥）。
 *
 * 它只实现 `/v1/messages` 的流式分支，别的路径一律 404 —— 那正是"CLI 还请求了
 * 什么别的东西"的最好证据（日志里会打出来）。
 *
 * 用法（另开一个终端，或后台跑）：
 *   node sidecar/tools/mock-anthropic.mjs            # 默认 127.0.0.1:8931
 *   MOCK_PORT=9000 MOCK_DELTA_MS=100 node sidecar/tools/mock-anthropic.mjs
 *
 * 关键信号：
 *   - `[mock] 断开` 出现的时刻 = **CLI 真的把这个请求掐了**（中断在传输层生效）
 *   - 断开之后还有没有 `[mock] 下一片` → 没有就说明它不写了
 *
 * ## 2026-09-21 实测：这条假流还没能跑满一轮
 *
 * 端点、请求、SSE 都通了（日志里能看到 `POST /v1/messages?beta=true`、
 * `thinking={"type":"adaptive"}`，SDK 侧也收到了 3 条 stream_event），但 CLI
 * **在第一片 `thinking_delta` 之后就掐了连接**，然后既不重试也不报错，界面看起来
 * 就是"卡住"。要拿它当可控现场还得把这几点补齐（下一步的方向）：
 *   - 思考块的 `signature_delta`（真 API 会发，这里没发）
 *   - `message_delta` 里的 `usage`、`stop_reason` 的完整形状
 *   - 也许还有 `ping` 事件与 `x-request-id` 之类的响应头
 * 在那之前，别拿它当"中断行为"的判据 —— 它现在只能证明**管道是通的**。
 */
import http from 'node:http';

const PORT = Number(process.env.MOCK_PORT ?? 8931);
const DELTA_MS = Number(process.env.MOCK_DELTA_MS ?? 250);
/** 最多流多少片（够长，好让中断落在"正在思考"的中间）。 */
const MAX_CHUNKS = Number(process.env.MOCK_CHUNKS ?? 200);

const t0 = Date.now();
const at = () => `${String(Date.now() - t0).padStart(6)}ms`;
const log = (...a) => console.log(`[${at()}] [mock]`, ...a);

let connNo = 0;

const sse = (res, type, obj) => {
  res.write(`event: ${type}\n`);
  res.write(`data: ${JSON.stringify(obj)}\n\n`);
};

const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => {
    body += c;
  });
  req.on('end', () => {
    log(`${req.method} ${req.url}（${body.length} 字节）`);
    if (!req.url?.includes('/v1/messages')) {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end('{"type":"error","error":{"type":"not_found_error","message":"mock"}}');
      return;
    }

    let parsed = {};
    try {
      parsed = JSON.parse(body);
    } catch {
      /* 不在意：这里只回一路固定的流 */
    }
    log(
      `  model=${parsed.model ?? '?'} stream=${parsed.stream ?? '?'} ` +
        `thinking=${JSON.stringify(parsed.thinking ?? null).slice(0, 60)}`,
    );

    const id = `conn${++connNo}`;
    res.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      connection: 'keep-alive',
    });

    let closed = false;
    let timer = null;
    const stop = (why) => {
      if (closed) return;
      closed = true;
      if (timer) clearTimeout(timer);
      log(`${id} ${why}（已发 ${sent} 片）`);
      try {
        res.end();
      } catch {
        /* 已经断了 */
      }
    };
    // 客户端主动断开 = CLI 掐了这个请求。**这是中断生效的硬证据**
    req.on('close', () => {
      if (!closed) {
        closed = true;
        if (timer) clearTimeout(timer);
        log(`${id} 断开（客户端掐的，已发 ${sent} 片）← 中断在传输层生效了`);
      }
    });
    res.on('close', () => {
      if (!closed) stop('连接关闭');
    });

    sse(res, 'message_start', {
      type: 'message_start',
      message: {
        id: `msg_${id}`,
        type: 'message',
        role: 'assistant',
        model: parsed.model ?? 'mock-model',
        content: [],
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: 12, output_tokens: 0 },
      },
    });
    sse(res, 'content_block_start', {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'thinking', thinking: '' },
    });

    let sent = 0;
    const tick = () => {
      if (closed) return;
      sent += 1;
      sse(res, 'content_block_delta', {
        type: 'content_block_delta',
        index: 0,
        delta: { type: 'thinking_delta', thinking: `第${sent}片思考……` },
      });
      if (sent === 1) log(`${id} 第一片已发（探针马上就该看到它）`);
      if (sent >= MAX_CHUNKS) {
        // 正常收尾：思考块结束 + 正文 + 回合结束
        sse(res, 'content_block_stop', { type: 'content_block_stop', index: 0 });
        sse(res, 'content_block_start', {
          type: 'content_block_start',
          index: 1,
          content_block: { type: 'text', text: '' },
        });
        sse(res, 'content_block_delta', {
          type: 'content_block_delta',
          index: 1,
          delta: { type: 'text_delta', text: '（假流跑完了）' },
        });
        sse(res, 'content_block_stop', { type: 'content_block_stop', index: 1 });
        sse(res, 'message_delta', {
          type: 'message_delta',
          delta: { stop_reason: 'end_turn', stop_sequence: null },
          usage: { output_tokens: 100 },
        });
        sse(res, 'message_stop', { type: 'message_stop' });
        stop('正常收尾');
        return;
      }
      timer = setTimeout(tick, DELTA_MS);
    };
    tick();
  });
});

server.listen(PORT, '127.0.0.1', () => {
  log(`假端点起来了：http://127.0.0.1:${PORT}（每 ${DELTA_MS}ms 一片思考，最多 ${MAX_CHUNKS} 片）`);
});
