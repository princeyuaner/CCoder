/**
 * 探针：`@路径` 的**行范围后缀**（`@path:24-27`）CLI 认不认。
 *
 * 背景：`@相对路径` 会被 CLI 自己展开成文件内容（2026-09-13 补全设计稿 §2 事实 4
 * 实测过：模型零工具调用就答对了文件里的值）。但"引用一个文件里的**某几行**"
 * 这个形状没验过 —— 而"右键加选区"正要用它。
 *
 * 判据只看一件事：**这一轮有没有 tool_use**。
 *   - 展开了 → 内容已进上下文 → 模型不必读文件 → 0 次工具调用
 *   - 没展开（把 `:24-27` 当成路径的一部分，或退化成普通文字）→ 它只能去读文件，
 *     或者答不出来
 *
 * 两轮对照：Q1 纯路径（对照组，已知会被展开），Q2 带行范围（被测组）。
 *
 * 用法：
 *   cd <repo root>
 *   node sidecar/tools/probe-mention.mjs
 */
import { query } from '@anthropic-ai/claude-agent-sdk';
import { buildChildEnv } from '../env.js';
import { readFileSync } from 'node:fs';

/** 被测的那几行（拿来对照模型答得对不对）。 */
const TARGET = 'src/main/kotlin/com/ccoder/ui/Composer.kt';
const FROM = 24;
const TO = 27;

const questions = [
  { label: 'Q1 对照组（纯路径）', prompt: '@sidecar/package.json 里 version 字段的值是什么？只回答值本身。' },
  { label: `Q2 被测（带行范围 ${FROM}-${TO}）`, prompt: `@${TARGET}:${FROM}-${TO} 这几行注释说了什么？用一句中文概括。` },
];

/** 跑一轮，返回 { tools: string[], answer: string }。 */
async function turn(prompt) {
  const tools = [];
  let answer = '';
  const q = query({
    prompt,
    options: {
      cwd: process.cwd(),
      permissionMode: 'default',
      env: buildChildEnv(process.env),
    },
  });
  for await (const msg of q) {
    if (msg.type === 'assistant') {
      for (const block of msg.message?.content ?? []) {
        if (block.type === 'tool_use') tools.push(block.name);
      }
    }
    if (msg.type === 'result') {
      answer = typeof msg.result === 'string' ? msg.result : JSON.stringify(msg.result ?? '');
    }
  }
  return { tools, answer };
}

const actual = readFileSync(TARGET, 'utf8').split('\n').slice(FROM - 1, TO)
  .map((l, i) => `${FROM + i}| ${l}`).join('\n');

console.log(`\n被测区间 ${TARGET}:${FROM}-${TO} 的实际内容：\n${actual}\n`);

for (const { label, prompt } of questions) {
  const t0 = Date.now();
  const { tools, answer } = await turn(prompt);
  const secs = ((Date.now() - t0) / 1000).toFixed(1);
  console.log(`── ${label}（${secs}s）`);
  console.log(`   问：${prompt}`);
  console.log(`   工具调用：${tools.length ? `${tools.length} 次 [${tools.join(', ')}]` : '0 次'}`);
  console.log(`   回答：${answer.replace(/\s+/g, ' ').slice(0, 160)}`);
  console.log(tools.length === 0
    ? '   → 没有工具调用 = 内容确实已经进了上下文（这一轮就是"展开了"）'
    : '   → 有工具调用 = 引用没被展开，模型只能自己去读\n');
}
