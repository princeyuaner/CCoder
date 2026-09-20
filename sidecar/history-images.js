import { defaultT, plural } from './strings.js';

/**
 * 恢复会话时，历史里的图要有个预算。
 *
 * ## 为什么（2026-09-15 实测）
 *
 * CLI 把用户发过的图**原样存进 JSONL**（`"type":"image"` + 完整 base64，
 * 不是 `[Image #1]` 那种占位 —— 见 `tools/probe-history-image.mjs`）。
 * 而 `loadHistory` 是把整段历史**一条 JSON 推回来**的：
 * 一个用了两周、发过十几张截图的会话，那一行就是几十 MB —— sidecar 要重新
 * 序列化一遍、Kotlin 要解析、要留在内存里。图本身该不该画是另一件事，
 * 这个函数只管**别让传输层被它压垮**。
 *
 * ## 规则
 *
 * 从**最新的一条往回**数，装得下的留下。丢掉的不是静默消失：在那条消息的
 * 文本后面补一句「（这一条里更早的 N 张图已省略）」，用户至少知道那里有过图。
 * 从新往旧数是因为最近的图最可能还要看 —— 而"最近"在会话里就是"往下数"。
 */

/**
 * 预算：base64 字符数（不是字节数 —— 传的就是字符串，按字符串算才准）。
 *
 * 6M 字符 ≈ 4.5MB 原始图 ≈ 四到八张 1568px 的截图。够覆盖"刚才那几张"，
 * 又不至于让那一行 JSON 大到解析都卡。
 */
export const HISTORY_IMAGE_BUDGET = 6 * 1024 * 1024;

/** 一条消息里的块是不是图，以及它占多少（base64 长度）。 */
function imageSize(block) {
  if (!block || block.type !== 'image') return -1;
  const data = block.source?.data;
  return typeof data === 'string' ? data.length : 0;
}

/**
 * @param {Array<object>} items  getSessionMessages 回来的条目（会被浅拷贝，不原地改）
 * @param {number} budget        见 [HISTORY_IMAGE_BUDGET]
 * @param {Function} t           取词器（见 strings.js）。默认现读环境变量
 * @returns {{items: Array<object>, kept: number, dropped: number}}
 */
export function capHistoryImages(items, budget = HISTORY_IMAGE_BUDGET, t = defaultT()) {
  if (!Array.isArray(items) || items.length === 0) {
    return { items: items ?? [], kept: 0, dropped: 0 };
  }

  // 第一遍：从新往旧，看看每条里哪些图能留下
  let left = budget;
  let kept = 0;
  const plan = items.map(() => null); // null = 这条不动；否则是"要丢掉的块下标"
  for (let i = items.length - 1; i >= 0; i--) {
    const content = items[i]?.message?.content;
    if (!Array.isArray(content)) continue;
    const drop = [];
    for (let b = 0; b < content.length; b++) {
      const size = imageSize(content[b]);
      if (size < 0) continue; // 不是图
      if (size <= left) {
        left -= size;
        kept++;
      } else {
        drop.push(b);
      }
    }
    if (drop.length > 0) plan[i] = drop;
  }

  let dropped = 0;
  const out = items.map((item, i) => {
    const drop = plan[i];
    if (!drop) return item;
    dropped += drop.length;
    const content = item.message.content;
    const keptBlocks = content.filter((_, b) => !drop.includes(b));
    // 说明补在**末尾**：正文在前，用户读到的顺序跟原来一样。
    //
    // 这一条**是**要翻的 —— 与拒绝文案（"用户拒绝"/"已中断"/"会话已终止"，
    // 保持中文，见 shared/deny-message.json）刻意相反：那几条是发给 CLI 的
    // 协议载荷，模型看到的内容不该随界面语言变；这一条是**正文**，跟用户自己
    // 打的那段话混在同一个气泡里、模型也照着读 —— 一句中文括号夹在英文对话
    // 中间，比翻错更糟。单复数也只有英文要分，所以让调用方用 plural 选一份
    const omitted = plural(
      drop.length,
      t('history.imagesOmittedOne', { 0: drop.length }),
      t('history.imagesOmittedOther', { 0: drop.length }),
    );
    keptBlocks.push({ type: 'text', text: omitted });
    return { ...item, message: { ...item.message, content: keptBlocks } };
  });

  return { items: out, kept, dropped };
}
