#!/usr/bin/env node
/**
 * SDK 面 vs 已接入：一张表回答"还有哪些接口没接"。
 *
 * 为什么要这个工具：这个问题被问过两次（2026-09-17、2026-09-18），每次都得人肉
 * 翻 47 万字节的 `sdk.d.ts`；而 SDK 每升一版就往里加东西，靠记忆答必然过期。
 * 有了它，升级后重跑一次就是最新的缺口清单。
 *
 * 判定方式是**词边界正则**，在**生产代码**里找。测试与探针（sidecar 的 test 与
 * tools 两个目录、src/test、web/src 下的 *.test.tsx）都**不算**"接入" ——
 * 探针里调一次不等于产品会调它。**注释行也不算**：KDoc 里提一句"这条对应
 * SDKCompactBoundaryMessage"不是接入，早先没跳过注释时，一条列了三十个消息名的
 * 注释把所有消息都判成了"已接"。
 *
 * 三个面分开算，因为落点不同：
 *   Options 字段     → 只有 `sidecar/*.js` 构造 options（Kotlin 根本不碰 SDK）
 *   Query 方法       → 侧车调用（有的还经 NDJSON 由 Kotlin 发指令触发）
 *   消息 type 字面量 → 侧车透传 / Kotlin 认 / web 渲染，**三处任一处**算接入
 *
 * 噪声**故意留着**：每条都打印命中的那一行原文，扫一眼就知道那是真接入还是
 * 同名词（Options 里的 `model`、消息的 `type: 'system'` 都会蹭到别处）。
 * 要消噪就得上 AST，不值。**看到 `已接` 别直接信，看它右边那句话。**
 *
 * 用法：
 *   cd sidecar && node tools/sdk-surface.mjs            # 表
 *   cd sidecar && node tools/sdk-surface.mjs --hits=5   # 多打几条命中
 */
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const sidecarDir = resolve(here, '..')
const repo = resolve(sidecarDir, '..')
const dts = join(sidecarDir, 'node_modules', '@anthropic-ai', 'claude-agent-sdk', 'sdk.d.ts')

const maxHits = Number((process.argv.find((a) => a.startsWith('--hits=')) || '').split('=')[1]) || 2

/**
 * 读成行数组。**必须按 \r?\n 切** —— `sdk.d.ts` 是 CRLF，按 '\n' 切的话每行尾
 * 都挂一个 \r，`lines[j] !== '}'` 这类判断永远不成立，块会一直吞到文件尾
 * （第一版就这么把 189 个"Options 字段"打了出来，其中一百多个是别的类型的）。
 */
const cache = new Map()
function readLines(f) {
  if (!cache.has(f)) cache.set(f, readFileSync(f, 'utf8').split(/\r?\n/))
  return cache.get(f)
}
const lines = readLines(dts)

// ---- 1. 生产代码都有哪些文件 ----

const sidecarSrc = readdirSync(sidecarDir, { withFileTypes: true })
  .filter((e) => e.isFile() && e.name.endsWith('.js'))
  .map((e) => join(sidecarDir, e.name))
const allSrc = [...sidecarSrc]

function walk(dir, want) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'build' || e.name === '.git') continue
    const p = join(dir, e.name)
    if (e.isDirectory()) walk(p, want)
    else if (want(p)) allSrc.push(p)
  }
}
walk(join(repo, 'src', 'main', 'kotlin'), (p) => p.endsWith('.kt'))
walk(join(repo, 'web', 'src'), (p) => /\.(ts|tsx)$/.test(p) && !/\.test\./.test(p))

/** 注释行（`//`、`*`、`/*` 起头）不算命中 —— 见文件头的说明。 */
const isComment = (t) => /^(\/\/|\*|\/\*)/.test(t)

/** 词边界找 term，返回 `文件:行 原文`。 */
function hits(term, files, n = maxHits) {
  const re = new RegExp(`\\b${term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`)
  const out = []
  for (const f of files) {
    const ls = readLines(f)
    for (let i = 0; i < ls.length; i++) {
      const t = ls[i].trim()
      if (!t || isComment(t) || !re.test(t)) continue
      out.push({ loc: `${relative(repo, f).replace(/\\/g, '/')}:${i + 1}`, text: t.slice(0, 64) })
      if (out.length >= n) return out
    }
  }
  return out
}

// ---- 2. 从 sdk.d.ts 里挖出三个面 ----

/**
 * 从第 i 行起取一个声明块，**按花括号计数**找收尾。
 *
 * 千万别改回"找列 0 的 `}`"：这里的声明收尾是 `};`（带分号），那种找法一次都
 * 匹配不上，块会顺着文件往下吞 —— 症状是**好几条消息认领同一个 subtype**
 * （每条都偷了后面那条第 4 空格缩进的 `type:`）。第一版就是这么错的。
 * 单行声明（`= A | B;`）也认。
 */
function blockFrom(i) {
  const count = (s, c) => s.split(c).length - 1
  let depth = count(lines[i], '{') - count(lines[i], '}')
  if (depth <= 0) return { body: [lines[i]], line: i + 1 }
  let j = i + 1
  for (; j < lines.length && depth > 0; j++) depth += count(lines[j], '{') - count(lines[j], '}')
  return { body: lines.slice(i + 1, j - 1), line: i + 1 }
}

function blockAt(re) {
  const i = lines.findIndex((l) => re.test(l))
  return i < 0 ? null : blockFrom(i)
}

const optionsKeys = blockAt(/^export declare type Options = \{/)
  .body.map((l) => /^ {4}([A-Za-z_][A-Za-z0-9_]*)\??:/.exec(l))
  .filter(Boolean)
  .map((m) => m[1])

const queryMethods = blockAt(/^export declare interface Query extends/)
  .body.map((l) => /^ {4}([a-zA-Z_][A-Za-z0-9_]*)\(/.exec(l))
  .filter(Boolean)
  .map((m) => m[1])

const topFns = lines
  .map((l) => /^export declare function ([A-Za-z_][A-Za-z0-9_]*)\(/.exec(l))
  .filter(Boolean)
  .map((m) => m[1])

/**
 * 每条 SDK 消息：类型名 + 线上判别用的字面量。
 *
 * **优先 subtype** —— 大半消息的 `type` 只是个粗档（`'system'`/`'user'`/`'result'`），
 * 拿它去找命中率 100%：`event.str("type") != "system"` 这行能"命中"十条消息。
 * 真正区分它们的是 subtype（`compact_boundary`、`task_progress`、`status`…）。
 */
const messages = []
for (let i = 0; i < lines.length; i++) {
  const m = /^export declare type (SDK\w*Message\w*) = \{/.exec(lines[i])
  if (!m) continue
  const body = blockFrom(i).body
  const lit = (key) =>
    body
      .map((l) => new RegExp(`^ {4}${key}: '([^']+)'`).exec(l))
      .find(Boolean)
  const sub = lit('subtype')
  const typ = lit('type')
  messages.push({ name: m[1], term: sub?.[1] ?? typ?.[1] ?? null, kind: sub ? 'subtype' : 'type' })
}

// ---- 3. 打表 ----

const pad = (s, n) => s + ' '.repeat(Math.max(0, n - s.length))
function section(title, rows) {
  const used = rows.filter((r) => r.hits.length > 0).length
  console.log(`\n=== ${title} —— 共 ${rows.length}，有命中 ${used}，零命中 ${rows.length - used} ===`)
  for (const r of rows) {
    if (r.hits.length) {
      console.log(` 命中  ${pad(r.name, 42)}${r.hits[0].loc}`)
      console.log(`${' '.repeat(8)}${pad('', 42)}${r.hits[0].text}`)
      for (const h of r.hits.slice(1)) console.log(`${' '.repeat(50)}${h.loc}  ${h.text}`)
    } else {
      console.log(` ····  ${pad(r.name, 42)}${r.note ?? ''}`)
    }
  }
}

console.log(`SDK: ${relative(repo, dts).replace(/\\/g, '/')}`)
console.log(`扫的生产代码：sidecar ${sidecarSrc.length} 个 .js ＋ Kotlin/web ＝ ${allSrc.length} 个文件`)

section(
  'Options 字段（只在 sidecar 顶层 .js 里找）',
  optionsKeys.map((name) => ({ name, hits: hits(name, sidecarSrc) }))
)

section(
  'Query 方法（全仓生产代码）',
  queryMethods.map((name) => ({ name, hits: hits(name, allSrc) }))
)

section(
  '顶层导出函数（全仓生产代码）',
  topFns.map((name) => ({ name, hits: hits(name, allSrc) }))
)

section(
  'SDK 消息类型（按 subtype，没有 subtype 才用 type；另附类型名自身的命中）',
  messages.map((m) => ({
    name: m.name,
    note: m.term ? `线上 ${m.kind}: ${m.term}` : '（无字面量）',
    hits: m.term ? hits(m.term, allSrc) : [],
  }))
)
