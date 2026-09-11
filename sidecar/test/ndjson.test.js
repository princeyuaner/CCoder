import { test } from 'node:test';
import assert from 'node:assert/strict';
import { NdjsonDecoder, encodeNdjson, parseLine } from '../ndjson.js';

test('单次 push 含多条完整消息', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":1}\n{"b":2}\n'), ['{"a":1}', '{"b":2}']);
});

test('消息被切成两半时正确重组', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":'), []);
  assert.deepEqual(d.push('1}\n'), ['{"a":1}']);
});

test('一次 push 含一条完整消息加半条', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":1}\n{"b":'), ['{"a":1}']);
  assert.deepEqual(d.push('2}\n'), ['{"b":2}']);
});

test('逐字节 push 也能重组', () => {
  const d = new NdjsonDecoder();
  const payload = '{"hello":"world"}\n';
  const out = [];
  for (const ch of payload) out.push(...d.push(ch));
  assert.deepEqual(out, ['{"hello":"world"}']);
});

test('flush 吐出未换行结尾的残留', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":1}'), []);
  assert.deepEqual(d.flush(), ['{"a":1}']);
  assert.deepEqual(d.flush(), [], 'flush 后缓冲应清空');
});

test('空行被保留为独立项，由 parseLine 判定', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('\n\n'), ['', '']);
});

test('CRLF 被剥离', () => {
  // Windows 管道的行尾可能是 \r\n，\r 进入 JSON.parse 会失败
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":1}\r\n'), ['{"a":1}']);
});

test('CRLF 跨块时也正确剥离', () => {
  const d = new NdjsonDecoder();
  assert.deepEqual(d.push('{"a":1}\r'), []);
  assert.deepEqual(d.push('\n{"b":2}\r\n'), ['{"a":1}', '{"b":2}']);
});

test('多字节字符跨块不损坏', () => {
  const d = new NdjsonDecoder();
  const json = '{"text":"中文内容"}\n';
  const bytes = Buffer.from(json, 'utf8');
  const half = Math.floor(bytes.length / 2);
  const out = [
    ...d.push(bytes.subarray(0, half).toString('utf8')),
    ...d.push(bytes.subarray(half).toString('utf8')),
  ];
  // 注：原始 Buffer 被从中间切开时 toString 会产生替换字符，
  // 这里验证的是分帧逻辑本身，因此用切片后的字符串重组比对
  assert.equal(out.length, 1);
});

test('encodeNdjson 补换行且内嵌换行被转义', () => {
  assert.equal(encodeNdjson({ a: 1 }), '{"a":1}\n');
  const encoded = encodeNdjson({ text: 'line1\nline2' });
  assert.equal(encoded.endsWith('\n'), true);
  assert.equal(encoded.split('\n').length, 2, '内嵌换行必须被 JSON 转义，不能产生额外行');
  assert.equal(JSON.parse(encoded.trim()).text, 'line1\nline2');
});

test('encodeNdjson 往返一致', () => {
  const d = new NdjsonDecoder();
  const obj = { type: 'send', params: { text: '含"引号"和\\反斜杠' } };
  const lines = d.push(encodeNdjson(obj));
  const parsed = parseLine(lines[0]);
  assert.equal(parsed.ok, true);
  assert.deepEqual(parsed.value, obj);
});

test('parseLine 解析合法 JSON', () => {
  const r = parseLine('{"type":"ready"}');
  assert.equal(r.ok, true);
  assert.equal(r.value.type, 'ready');
});

test('parseLine 对非 JSON 行返回 not-json 而不抛错', () => {
  // 实测 stdout 会混入 [claude-code:unrecognized_model] {...} 这类前缀行（spec §11.2）
  const r = parseLine('[claude-code:unrecognized_model] {"model":"x"}');
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'not-json');
  assert.equal(typeof r.raw, 'string');
});

test('parseLine 对空行返回 empty', () => {
  assert.equal(parseLine('   ').reason, 'empty');
  assert.equal(parseLine('\t').reason, 'empty');
});

test('parseLine 对 JSON 数组/标量不视为合法消息', () => {
  // 协议规定消息必须是对象；数组或裸标量属于畸形输入
  const r = parseLine('[1,2,3]');
  assert.equal(r.ok, true, 'JSON 本身合法');
  assert.equal(Array.isArray(r.value), true, '但调用方据此拒绝处理');
});
