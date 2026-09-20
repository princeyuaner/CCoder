import test from 'node:test';
import assert from 'node:assert/strict';
import { capHistoryImages, HISTORY_IMAGE_BUDGET } from '../history-images.js';
import { makeT } from '../strings.js';

// 这条用例断言的是**中文**文案（"更早的 N 张图已省略"），所以把语言钉成中文 ——
// 生产里它是插件启动 sidecar 进程时设的环境变量（Lever A）；这里在 import 之后
// 设是因为取词器是**调用时**才读环境的（见 strings.js 的 defaultT）。
// 不设的话默认是英文，下面那条断言会红。
process.env.CCODER_UI_LANG = 'zh';

/**
 * 历史里那些图的预算。为什么要有它：CLI 把图**原样全尺寸**存进 JSONL，
 * 而 loadHistory 是整段一次性推回去的 —— 不裁，一个用过两周的会话能推出几十 MB
 * （见 history-images.js 顶部那份实测说明）。
 */

/** 造一条带图的用户消息。size 是 base64 的**字符数**（就是预算的计量单位）。 */
function userWithImages(sizes, text = '看这个') {
  return {
    type: 'user',
    uuid: `u${sizes.join('-')}`,
    session_id: 's1',
    parent_tool_use_id: null,
    parent_agent_id: null,
    message: {
      role: 'user',
      content: [
        ...sizes.map((n, i) => ({
          type: 'image',
          source: { type: 'base64', media_type: 'image/png', data: 'A'.repeat(n) },
        })),
        ...(text ? [{ type: 'text', text }] : []),
      ],
    },
  };
}

function contentOf(item) {
  return item.message.content.map((b) => b.type);
}

test('预算够用时一个块都不动', () => {
  const items = [userWithImages([100, 200])];

  const { items: out, kept, dropped } = capHistoryImages(items, 1000);

  assert.equal(kept, 2);
  assert.equal(dropped, 0);
  assert.deepEqual(contentOf(out[0]), ['image', 'image', 'text']);
  assert.equal(out[0], items[0], '没裁的时候连对象都不该换');
});

test('超预算时**从最新的往回留**', () => {
  // 两条消息各一张 600 的图，预算只够 700 —— 该留下的是**后**发的那张
  const items = [userWithImages([600], '早的'), userWithImages([600], '晚的')];
  items[0].uuid = 'early';
  items[1].uuid = 'late';

  const { items: out, kept, dropped } = capHistoryImages(items, 700);

  assert.equal(kept, 1);
  assert.equal(dropped, 1);
  assert.deepEqual(contentOf(out[1]), ['image', 'text'], '最新的那张必须留下');
  assert.deepEqual(contentOf(out[0]), ['text', 'text'], '更早的那张被丢掉');
});

test('丢掉的不是静默消失 —— 那条消息后面补一句说明', () => {
  const items = [userWithImages([600, 600], '看这两张')];

  const { items: out } = capHistoryImages(items, 700);
  const content = out[0].message.content;

  assert.deepEqual(content.map((b) => b.type), ['image', 'text', 'text']);
  assert.equal(content[0].source.data.length, 600, '留下的该是**后面**那张（数组里靠后的）');
  assert.match(content.at(-1).text, /更早的 1 张图已省略/);
});

test('英文那份按单复数换说法（中文两个键同形）', () => {
  // 这条说明会跟着消息进模型上下文、也画在气泡里，所以它是**要**翻的 ——
  // 与"用户拒绝"那三条协议文案刻意相反（见 shared/deny-message.json）。
  // 单复数只有英文要分，所以由这边用 plural 选键，而不是在词表里拼字符串
  const en = makeT('en');

  const one = capHistoryImages([userWithImages([900])], 500, en);
  assert.equal(one.dropped, 1);
  assert.equal(one.items[0].message.content.at(-1).text, '(1 earlier image in this message omitted)');

  const many = capHistoryImages([userWithImages([900, 900])], 500, en);
  assert.equal(many.dropped, 2);
  assert.equal(many.items[0].message.content.at(-1).text, '(2 earlier images in this message omitted)');
});

test('工具结果里的图不算数 —— 它们不是提问', () => {
  // 那条路上的图属于工具卡片的输出，本来就不画（见 MessageRenderer.renderPrompt）
  const toolResult = {
    type: 'user',
    message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't1', content: 'x' }] },
  };

  const { items: out, kept } = capHistoryImages([toolResult], 0);

  assert.equal(kept, 0, 'tool_result 里就算有图也不占预算');
  assert.equal(out[0], toolResult);
});

test('纯文本的历史原样返回', () => {
  const items = [
    { type: 'user', message: { role: 'user', content: '这是什么项目' } },
    { type: 'assistant', message: { role: 'assistant', content: [{ type: 'text', text: '一个插件' }] } },
  ];

  const { items: out, kept, dropped } = capHistoryImages(items);

  assert.equal(kept + dropped, 0);
  assert.deepEqual(out, items);
});

test('空历史与坏形状都不炸', () => {
  assert.deepEqual(capHistoryImages([]).items, []);
  assert.deepEqual(capHistoryImages(null).items, []);
  const weird = [{ type: 'user' }, { type: 'user', message: { content: 42 } }];
  assert.deepEqual(capHistoryImages(weird).items, weird);
});

test('默认预算是个说得出理由的数', () => {
  // 6M 字符 ≈ 4.5MB 原图 ≈ 四到八张 1568px 截图：够覆盖"刚才那几张"
  assert.equal(HISTORY_IMAGE_BUDGET, 6 * 1024 * 1024);
});
