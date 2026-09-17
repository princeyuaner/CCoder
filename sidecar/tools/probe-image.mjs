#!/usr/bin/env node
/**
 * 探针：**这条链路到底收不收图**。
 *
 * 为什么必须问：SDK 的类型说明收（`sdk.d.ts:5850`：content 可以是 text / image /
 * document 的数组），CLI 自己也有一整套图片政策（`claude.exe` 里的
 * `rk = { maxWidth:2000, maxHeight:2000, maxBase64Size:5242880 }`）—— 但本插件跑的
 * 是**第三方网关**（`~/.claude/settings.json` 的 ANTHROPIC_BASE_URL 指向
 * tokenhub），网关背后那个模型收不收 image block 是另一件事。类型对、CLI 对、
 * 网关不理，是三个独立的"对"。
 *
 * 图是**当场用 zlib 手搓的 PNG**（64×64，左半红右半蓝）—— 不引依赖，也不用
 * 磁盘上任何文件；问的是"左半边什么颜色"，答红才算真的看见了，答"我看不到图片"
 * 或者报错都说明这条路不通。
 *
 * 跑法：
 *   node sidecar/tools/probe-image.mjs                          # 用插件当前那个模型
 *   node sidecar/tools/probe-image.mjs 'deepseek-v4-flash-vision-exp[1m]'
 *
 * 退出码：0 = 跑完了（**不管模型答得对不对**，输出才是结论）；1 = 起不来。
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { deflateSync } from 'node:zlib';
import { buildChildEnv } from '../env.js';
import { resolveClaudePath } from '../claude-path.js';

const MODEL = process.argv[2] ?? 'deepseek-v4-flash[1m]';
/**
 * 左半边的颜色。**必须能换** —— 只问一次"左半边什么颜色"是二选一，猜对也是
 * 50%；把红的换到右边再问一次，两次都对才说明它真在看图（2026-09-15 实测：
 * 第一次答"红色"之后就是这么确认的）。
 */
const LEFT = (process.argv[3] ?? 'red').toLowerCase();
const PROMPT = '这张图的左半边是什么颜色？只回一个词。';
const EXPECTED = LEFT === 'blue' ? /蓝/ : /红/;

// ---- 手搓一张 PNG：左半红、右半蓝 ----------------------------------------
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function makePng(w, h) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    const row = y * (w * 3 + 1);
    raw[row] = 0; // filter: none
    for (let x = 0; x < w; x++) {
      const left = LEFT === 'blue' ? [30, 60, 220] : [220, 30, 30];
      const right = LEFT === 'blue' ? [220, 30, 30] : [30, 60, 220];
      const [r, g, b] = x < w / 2 ? left : right;
      const p = row + 1 + x * 3;
      raw[p] = r;
      raw[p + 1] = g;
      raw[p + 2] = b;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // truecolor
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const png = makePng(64, 64);
const data = png.toString('base64');

// ---- 发出去 ---------------------------------------------------------------
const env = buildChildEnv(process.env);
console.log(`端点   : ${env.ANTHROPIC_BASE_URL ?? '（settings.json 里的）'}`);
console.log(`模型   : ${MODEL}`);
console.log(`图     : 64×64 PNG，左半${LEFT === 'blue' ? '蓝' : '红'}，base64 ${data.length} 字节`);
console.log(`问题   : ${PROMPT}`);
console.log('---');

async function* input() {
  yield {
    type: 'user',
    message: {
      role: 'user',
      content: [
        { type: 'image', source: { type: 'base64', media_type: 'image/png', data } },
        { type: 'text', text: PROMPT },
      ],
    },
    parent_tool_use_id: null,
  };
}

const started = Date.now();
try {
  const q = query({
    prompt: input(),
    options: {
      cwd: process.cwd(),
      model: MODEL,
      maxTurns: 1,
      permissionMode: 'default',
      env,
      pathToClaudeCodeExecutable: resolveClaudePath({}),
    },
  });

  let said = '';
  for await (const msg of q) {
    if (msg.type === 'assistant') {
      for (const block of msg.message?.content ?? []) {
        if (block.type === 'text' && block.text.trim()) said += block.text + '\n';
      }
    } else if (msg.type === 'result') {
      console.log(`result : ${msg.subtype}${msg.is_error ? ' (is_error)' : ''}`);
    } else if (msg.type === 'system' && msg.subtype === 'init') {
      console.log(`init   : tools=${(msg.tools ?? []).length} model=${msg.model ?? '?'}`);
    }
  }

  console.log(`---\n模型说：${said.trim() || '（一个字都没说）'}`);
  console.log(`耗时   : ${((Date.now() - started) / 1000).toFixed(1)}s`);
  const verdict = EXPECTED.test(said) ? '看见图了 ✅' : '没看见图（或被拒）❌';
  console.log(`结论   : ${verdict}`);
} catch (err) {
  console.log(`---\n抛了：${err?.message ?? err}`);
  console.log('结论   : 这条路不通 ❌');
}
