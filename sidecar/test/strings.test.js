import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import {
  CATALOGS,
  DEFAULT_LANG,
  UI_LANG_ENV,
  langFromEnv,
  makeT,
  normalizeLang,
  plural,
} from '../strings.js';

/**
 * 词表的两类对账：**表内**（两份语言的形状）与**表外**（键与代码的引用）。
 *
 * 为什么值得写：`makeT` 缺键时返回**键本身**（那是刻意的 —— 静默回退英文是
 * 在撒谎），所以一个打错的键会显示成 `error.unknownMethod`。而词表里一条没人
 * 用的键会一直躺着、被下一个译者当成真在用的东西去翻。
 *
 * 形状照 Kotlin 侧那份 `TextKeysTest`：**扫源码文本，不是运行时** —— 所以只认
 * 字面量调用，键永不动态拼（见 CcoderBundle.properties 顶上的约定）。
 */

const LANGS = Object.keys(CATALOGS).sort();

/** sidecar/ —— 扫描范围就是这个目录顶层的 .js（不含 test/、tools/）。 */
const SIDECAR_DIR = fileURLToPath(new URL('..', import.meta.url));

/**
 * `t('key')` 调用点。
 *
 * 前面那个 lookbehind 是必须的：`this.buffer.split('\n')` 里也有 `t('`，
 * 不加它就会扫出一个叫 `\n` 的键。
 */
const CALL_SITE = /(?<![A-Za-z0-9_$.])t\(\s*'([^']+)'/g;

/**
 * 去掉注释再扫。
 *
 * 文档注释里会**引用**键（"缺键时显示 `error.unknownMethod`"这种），
 * 那不是调用点。规则同 TextKeysTest：只看非注释文本。
 */
function codeOf(file) {
  return readFileSync(join(SIDECAR_DIR, file), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^[ \t]*\/\/.*$/gm, '');
}

function sourceFiles() {
  return readdirSync(SIDECAR_DIR).filter((f) => f.endsWith('.js')).sort();
}

/** key -> 第一个引用它的文件名。 */
function referencedKeys() {
  const found = new Map();
  for (const file of sourceFiles()) {
    for (const match of codeOf(file).matchAll(CALL_SITE)) {
      if (!found.has(match[1])) found.set(match[1], file);
    }
  }
  return found;
}

test('两份词表的键集必须一模一样', () => {
  const [base, ...rest] = LANGS;
  const expected = Object.keys(CATALOGS[base]).sort();
  for (const lang of rest) {
    assert.deepEqual(
      Object.keys(CATALOGS[lang]).sort(),
      expected,
      `${lang} 那份多一条或少一条都不行：少的那条会在界面上显示成键名`,
    );
  }
});

test('值不能是空的，也不能带首尾空白', () => {
  // 首尾空白交给代码拼（"\\n\\n" 这类），别放进词表：看不见的空白会被顺手删掉，
  // 而删掉之后排版就变了 —— 这条同 Kotlin 那份词表顶上的约定
  for (const lang of LANGS) {
    for (const [key, value] of Object.entries(CATALOGS[lang])) {
      assert.equal(typeof value, 'string', `${lang} 的 ${key} 不是字符串`);
      assert.ok(value.trim().length > 0, `${lang} 的 ${key} 是空的`);
      assert.equal(value, value.trim(), `${lang} 的 ${key} 带了首尾空白`);
    }
  }
});

test('占位符下标从 0 连续 —— 跳号等于有一个实参没地方放', () => {
  for (const lang of LANGS) {
    for (const [key, value] of Object.entries(CATALOGS[lang])) {
      const indices = [...new Set([...value.matchAll(/\{(\d+)\}/g)].map((m) => Number(m[1])))]
        .sort((a, b) => a - b);
      assert.deepEqual(
        indices,
        indices.map((_, i) => i),
        `${lang} 的 ${key} 占位符下标不连续：${JSON.stringify(indices)}`,
      );
    }
  }
});

test('英文那列不许出现中文 —— 含全角标点', () => {
  // 全角标点（U+3000-303F、U+FF00-FFEF）也算：`（` 出现在英文句子里同样是漏翻的痕迹
  const CJK = /[\u3000-\u303F\u4E00-\u9FFF\uFF00-\uFFEF]/;
  for (const [key, value] of Object.entries(CATALOGS.en)) {
    assert.ok(!CJK.test(value), `en 的 ${key} 混进了中文：${value}`);
  }
});

test('中文那列必须有中文 —— 漏翻那条在中文界面上显示英文，看不出来', () => {
  // 这条与上一条合起来才闭环：只查英文那列的话，"英文照抄了一份到中文列"
  // （整条没翻）永远查不出来 —— 而那正是用户最看不出来的一种
  const CJK = /[\u4E00-\u9FFF]/;
  for (const [key, value] of Object.entries(CATALOGS.zh)) {
    assert.ok(CJK.test(value), `zh 的 ${key} 里一个汉字都没有，像是漏翻了：${value}`);
  }
});

test('语言标签只认 zh / en，别的一律当没给', () => {
  assert.equal(DEFAULT_LANG, 'en', '缺省语言是英文：插件的词表基础就是英文那份');
  assert.equal(normalizeLang('zh'), 'zh');
  assert.equal(normalizeLang('en'), 'en');
  // 刻意的：契约就是 CcoderText.tag() 那两个字符。放宽成前缀匹配的话，
  // 哪天上游改送 zh-Hant，我们会"猜"成简体 —— 猜错不像缺键那样看得见
  assert.equal(normalizeLang('zh-CN'), null);
  assert.equal(normalizeLang('ZH'), null);
  assert.equal(normalizeLang(undefined), null);
});

test('langFromEnv：没有/认不出都是缺省语言', () => {
  assert.equal(langFromEnv({ [UI_LANG_ENV]: 'zh' }), 'zh');
  assert.equal(langFromEnv({ [UI_LANG_ENV]: 'en' }), 'en');
  assert.equal(langFromEnv({}), DEFAULT_LANG);
  assert.equal(langFromEnv(undefined), DEFAULT_LANG);
  assert.equal(langFromEnv({ [UI_LANG_ENV]: 'fr' }), DEFAULT_LANG);
});

test('makeT：缺键返回键本身，不回退英文', () => {
  // 显示 `error.noSuchKey` 是一眼能报的 bug；静默给英文是在撒谎
  assert.equal(makeT('en')('error.noSuchKey'), 'error.noSuchKey');
  assert.equal(makeT('zh')('error.noSuchKey'), 'error.noSuchKey');
  // 认不出的**语言**才退回缺省那份表（同 ResourceBundle 落回基础词表）
  assert.equal(makeT('fr')('error.unknownMethod', { 0: 'x' }), CATALOGS.en['error.unknownMethod'].replace('{0}', 'x'));
});

test('makeT：占位符朴素替换，缺的实参把 {n} 留着', () => {
  const t = makeT('en');
  assert.equal(t('error.unknownMethod', { 0: 'nope' }), 'Unknown method: nope');
  // 留着比悄悄补空串好：界面上看得见 `{0}` 就知道是调用点漏传了
  assert.equal(t('error.unknownMethod'), 'Unknown method: {0}');
  assert.equal(t('error.unknownMethod', {}), 'Unknown method: {0}');
  // 数组也行 —— 实现按下标取，不关心是对象还是数组
  assert.equal(t('error.unknownMethod', ['nope']), 'Unknown method: nope');
});

test('plural 只分 1 与其余', () => {
  assert.equal(plural(1, 'one', 'other'), 'one');
  assert.equal(plural(0, 'one', 'other'), 'other');
  assert.equal(plural(2, 'one', 'other'), 'other');
});

test('源码里引用的键，两份词表里都得有', () => {
  const used = referencedKeys();
  // 没扫到东西的话下面两条都是空的 —— 先把扫描器本身钉住
  assert.ok(used.size > 10, `只扫到 ${used.size} 个调用点，扫描器像是坏了`);

  const missing = [];
  for (const [key, file] of used) {
    for (const lang of LANGS) {
      if (typeof CATALOGS[lang][key] !== 'string') missing.push(`${key}（${file} 引用，${lang} 词表里没有）`);
    }
  }
  assert.deepEqual(missing, [], '这些键在被引用，但词表里找不到');
});

test('词表里的键都有人引用 —— 没人用的键会烂在词表里', () => {
  const used = referencedKeys();
  const dead = [];
  for (const lang of LANGS) {
    for (const key of Object.keys(CATALOGS[lang])) {
      if (!used.has(key)) dead.push(`${lang}:${key}`);
    }
  }
  assert.deepEqual(dead, [], '这些键没有任何地方引用：要么接上线，要么从两份词表里删掉');
});
