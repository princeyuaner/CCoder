import { useSyncExternalStore } from 'react'
import { CATALOGS } from './strings'

/**
 * 界面语言。
 *
 * 语言标签由 Kotlin 注入（`window.ccoder.locale`），**目录不进桥**：这一侧
 * 自己带一份 TS 目录（strings.ts），因为 `npm run dev`、探针、单测里都没有
 * 宿主，界面照样得是完整的。
 *
 * 只有标签会变，而且变得极少（用户在设置页改一次），所以这里的形状是：
 * 一个模块级的值 + 一张订阅者表。
 */
export type Lang = 'zh' | 'en'

/**
 * 没有任何注入时的语言。
 *
 * 英文是**基底**：注入失败或标签没预料到时，宁可整页给英文，也不要一份
 * 半中半英的界面（那种界面看起来像坏了，而不是像没配好）。
 */
const FALLBACK: Lang = 'en'

/**
 * 当前语言。`null` = **还没读过**（不是"英文"）—— 这个区别是 [getLang] 的全部要害。
 */
let lang: Lang | null = null

const listeners = new Set<() => void>()

/**
 * 标签 → 语言。
 *
 * 契约里只有 `'zh' | 'en'` 两种写法。这里的宽大（认得 `zh-CN`、`zh-Hans`）
 * 是为了**不让一个没预料到的写法静默换掉整个界面**：Kotlin 侧将来给标签带上
 * 地区后缀的话，中国用户该看见的还是中文，而不是一个没有任何报错的英文界面。
 */
function normalize(tag: unknown): Lang {
  return typeof tag === 'string' && tag.toLowerCase().startsWith('zh') ? 'zh' : FALLBACK
}

/**
 * 当前语言 —— **第一次调用时才去读注入的标签**，读过就缓存。
 *
 * 惰性在这里是承重结构，不是省一次属性读取：
 *
 *  - `main.tsx` 渲染得很早，而桥是 CEF 走完 onLoadEnd 才注入的，模块级读一次
 *    会读到 `undefined` 并把整个进程钉死在英文上；
 *  - vitest 把 import 提升到 `beforeEach` **之前**，同理 —— 用例还没摆好
 *    `window.ccoder`，语言就已经定死了（i18n.test.ts 的第一条守的就是这个）。
 */
export function getLang(): Lang {
  if (lang === null) lang = normalize(window.ccoder?.locale)
  return lang
}

/**
 * 换语言（Kotlin 侧走 `ccoderSetLocale` → `ccoderLocaleSink` 落到这里）。
 *
 * **幂等**：同一个 tag 再来一遍不通知订阅者 —— 通知的代价是整棵订阅树重渲染。
 * 但 `documentElement.lang` 每次都对齐：它可能被别处改过，而它说错了会让读屏软件
 * 用错的音库念整页（index.html 里写的是基底语言，正是靠这里纠正）。
 */
export function setLang(tag: unknown): void {
  const next = normalize(tag)
  const changed = lang !== next
  lang = next
  document.documentElement.lang = next
  if (!changed) return
  for (const fn of listeners) fn()
}

/** 订阅语言变化，返回退订函数（[useLang] 的底座）。 */
export function subscribe(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

/**
 * 组件里读语言。语言一变，调过它的组件各自重渲染。
 *
 * **只有带着文案的组件该调它**：正文那条流（Markdown、两个气泡）一个字都没变，
 * 不该因为换语言把 marked 分词和整棵元素树重跑一遍（见 Markdown.tsx 的文件头 ——
 * 那笔账 2026-09-14 已经算过一次）。
 */
export function useLang(): Lang {
  return useSyncExternalStore(subscribe, getLang)
}

/**
 * 取一条文案。
 *
 * **缺键返回键本身**，两个理由：界面上出现 `tool.noOutput` 比出现一句临时编出来的
 * 中文更容易定位；而且**不退回英文** —— 退回意味着中文界面里悄悄夹一句英文，
 * 没有任何东西会红（真发生了的话，strings.test.ts 的中英对齐先红）。
 *
 * 占位符是 `{0}` `{1}` 这种**朴素替换**，不是 MessageFormat：英文里 `don't`
 * 这样的撇号会被 MessageFormat 当语法吃掉（轻则吞字，重则抛异常），而这个界面
 * 里到处都是撇号。参数按 `{n}` 的**下标**给（`params[0]` 填 `{0}`）；
 * 哪个下标没给，那一段就原样留着 —— 漏传参数时看得见，而不是静默少一段话。
 */
export function t(key: string, params?: ReadonlyArray<string | number>): string {
  const template: string | undefined = CATALOGS[getLang()][key]
  if (template === undefined) return key
  if (params === undefined) return template
  return template.replace(/\{(\d+)\}/g, (whole, digits: string) => {
    const value = params[Number(digits)]
    return value === undefined ? whole : String(value)
  })
}
