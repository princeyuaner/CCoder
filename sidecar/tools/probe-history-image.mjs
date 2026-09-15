#!/usr/bin/env node
/**
 * 探针：**恢复会话时，历史里带不带图**。
 *
 * 为什么要问：贴图发出去之后，用户切走再切回来（或者重启 IDE 恢复会话）——
 * 那一轮里的截图还在不在？插件今天只把历史里的**文本**画出来
 * （MessageRenderer），而"历史里到底有没有 image block"是另一件事：
 * CLI 可以把图存下来，也可以存成一个占位文本（`[Image #1]`）。
 *
 * SDK 的类型定义在这一处**不作承诺**：`SessionMessage.message` 是 `unknown`
 * （sdk.d.ts:5666），所以只能实测。
 *
 * 做法：起一个会话 → 发一张图（红蓝各半，答错一眼看得出）→ 拿 init 里的
 * sessionId → 用 `getSessionMessages`（插件的 loadHistory 用的就是它）读回来
 * → 打印每个用户条目的 content **形状**；再直接 grep 一遍磁盘上那份 JSONL，
 * 两条独立的路对得上才算数。
 *
 * 跑法：node sidecar/tools/probe-history-image.mjs ['模型名']
 * 退出码：0 = 走完了（**结论在输出里**）；1 = 起不来。
 */
import { query, getSessionMessages } from '@anthropic-ai/claude-agent-sdk';
import { deflateSync } from 'node:zlib';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { buildChildEnv } from '../env.js';
import { resolveClaudePath } from '../claude-path.js';

const MODEL = process.argv[2] ?? 'deepseek-v4-flash[1m]';
const PROMPT = '这张图是什么颜色？只回一个词。';

// ---- 一张 64×64 的 PNG：左半红、右半蓝（与 probe-image.mjs 同一张图）----
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
    for (let x = 0; x < w; x++) {
      const [r, g, b] = x < w / 2 ? [220, 30, 30] : [30, 60, 220];
      const p = row + 1 + x * 3;
      raw[p] = r;
      raw[p + 1] = g;
      raw[p + 2] = b;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const data = makePng(64, 64).toString('base64');
const cwd = process.cwd();

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

/** 一个 content 字段的**形状**（不打印 base64 本身，那玩意一动就是几 MB）。 */
function shapeOf(content) {
  if (typeof content === 'string') return `字符串(${content.length} 字符)`;
  if (!Array.isArray(content)) return typeof content;
  return content
    .map((b) => {
      if (b?.type === 'image') {
        return `image(${b.source?.type ?? '?'}, ${String(b.source?.data ?? '').length} 字符)`;
      }
      if (b?.type === 'text') return `text("${String(b.text).slice(0, 30)}")`;
      return String(b?.type ?? typeof b);
    })
    .join(' + ');
}

/** 在 ~/.claude/projects 里按文件名找这个会话的 JSONL —— 与 SDK 那条路互相独立。 */
function findTranscript(sessionId) {
  const root = join(homedir(), '.claude', 'projects');
  if (!existsSync(root)) return null;
  for (const proj of readdirSync(root)) {
    const dir = join(root, proj);
    if (!statSync(dir).isDirectory()) continue;
    const file = join(dir, `${sessionId}.jsonl`);
    if (existsSync(file)) return file;
  }
  return null;
}

console.log(`模型   : ${MODEL}`);
console.log(`cwd    : ${cwd}`);
console.log(`图     : 64×64 PNG（左红右蓝），base64 ${data.length} 字符`);
console.log('---');

let sessionId = null;
let said = '';

try {
  const q = query({
    prompt: input(),
    options: {
      cwd,
      model: MODEL,
      maxTurns: 1,
      permissionMode: 'default',
      env: buildChildEnv(process.env),
      pathToClaudeCodeExecutable: resolveClaudePath({}),
    },
  });

  for await (const msg of q) {
    if (msg.type === 'system' && msg.subtype === 'init') {
      sessionId = msg.session_id ?? null;
    } else if (msg.type === 'assistant') {
      for (const b of msg.message?.content ?? []) if (b.type === 'text') said += b.text;
    }
  }
} catch (err) {
  console.log(`发送就抛了：${err?.message ?? err}`);
  process.exit(1);
}

console.log(`模型说 : ${said.trim() || '（一个字都没说）'}`);
console.log(`会话 id: ${sessionId ?? '（没拿到）'}`);
if (!sessionId) process.exit(1);

// ---- 路一：SDK 的 getSessionMessages（插件 loadHistory 用的就是它）----
const items = await getSessionMessages(sessionId, { dir: cwd });
console.log(`\n[路一] getSessionMessages → ${items.length} 条`);
let apiHasImage = false;
for (const it of items.slice(0, 6)) {
  const content = it.message?.content;
  const shape = shapeOf(content);
  if (shape.includes('image(')) apiHasImage = true;
  console.log(`   ${it.type.padEnd(9)} ${shape}`);
}

// ---- 路二：磁盘上那份 JSONL 原样 grep ----
const file = findTranscript(sessionId);
let fileHasImage = false;
if (!file) {
  console.log('\n[路二] 没找到 JSONL（会话可能还没落盘）');
} else {
  const raw = readFileSync(file, 'utf8');
  const userLines = raw.split('\n').filter((l) => l.includes('"role":"user"'));
  fileHasImage = raw.includes('"type":"image"');
  console.log(`\n[路二] ${file}`);
  console.log(`   文件 ${raw.length} 字符，user 行 ${userLines.length} 条，` +
    `含 "type":"image"：${fileHasImage ? '是' : '否'}`);
  // 把第一条 user 行里的 content 类型序列抠出来（不打印 base64）
  const first = userLines[0];
  if (first) {
    const types = [...first.matchAll(/"type":"(image|text|tool_result)"/g)].map((m) => m[1]);
    console.log(`   第一条 user 行里的块：${types.join(', ') || '（没有块类型）'}`);
  }
}

console.log('\n=== 结论 ===');
console.log(
  apiHasImage
    ? '历史里**有** image block —— 恢复会话时那几张图是拿得到的 ✅'
    : '历史里**没有** image block —— 恢复会话时只能看到文字 ❌',
);
console.log(
  `（两条路一致：${apiHasImage === fileHasImage ? '是' : '**不一致，要查**'}）`,
);
