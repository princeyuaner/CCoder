/**
 * NDJSON 分帧。
 *
 * 为什么不直接用 readline：Node 的 readline 在遇到 `\r\n` 时会剥离 `\r`，
 * 且无法表达"这一行是垃圾"与"这是合法消息"的区别。这里的解码器只负责切分，
 * 合法性判定交给 parseLine，职责更单一也更好测。
 */
export class NdjsonDecoder {
  constructor() {
    this.buffer = '';
  }

  /**
   * 投入一个数据块，返回其中所有**完整**的行（不含换行符）。
   * 末尾不完整的部分留在缓冲里等待下一个块。
   *
   * 关键：`\r` 必须等到确认它后面是不是 `\n` 才能决定去留，
   * 因此 CRLF 落在块边界上时（chunk 以 `\r` 结尾），该行要留在缓冲里。
   *
   * @param {string} chunk
   * @returns {string[]}
   */
  push(chunk) {
    this.buffer += chunk;
    const lines = this.buffer.split('\n');
    this.buffer = lines.pop() ?? '';
    return lines.map((l) => (l.endsWith('\r') ? l.slice(0, -1) : l));
  }

  /**
   * 吐出缓冲区中的残留（流结束时调用）。
   * @returns {string[]}
   */
  flush() {
    const rest = this.buffer;
    this.buffer = '';
    if (!rest) return [];
    return [rest.endsWith('\r') ? rest.slice(0, -1) : rest];
  }
}

export function encodeNdjson(obj) {
  return JSON.stringify(obj) + '\n';
}

/**
 * 解析单行。**永不抛错** —— 非法行是预期输入，不是异常。
 *
 * 注意返回 `{ok:true}` 只表示"是合法 JSON"，不表示"是合法协议消息"。
 * 消息必须是对象，数组与裸标量由调用方拒绝。
 *
 * @param {string} line
 * @returns {{ok: true, value: unknown} | {ok: false, reason: string, raw?: string}}
 */
export function parseLine(line) {
  const trimmed = (line ?? '').trim();
  if (!trimmed) return { ok: false, reason: 'empty' };
  try {
    return { ok: true, value: JSON.parse(trimmed) };
  } catch {
    // 实测 stdout 会混入 "[claude-code:unrecognized_model] {...}" 这类前缀行（spec §11.2）
    return { ok: false, reason: 'not-json', raw: trimmed };
  }
}
