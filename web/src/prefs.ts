import { useSyncExternalStore } from 'react'

/**
 * 界面偏好（今天的全部内容：思考折叠）。
 *
 * ## 家在哪
 *
 * 偏好的家是 **Kotlin 那个服务**（`UiPreferences`，APP 级、落盘）。这一份只是页面里的
 * **内存快照** —— 所以这里**不碰 localStorage**：`CodeBlock.tsx` 里的 `ccoder.codeWrap`
 * 是另一套模型（那是"某一块自己的临时观感"），与"用户要什么"不是一回事。
 *
 * ## 形状照抄 i18n.ts，理由也一样
 *
 * 语言那个 store 存在两条坑，偏好一条不落全踩得到：
 *
 *  - `main.tsx` 渲染得很早，而桥是 CEF 走完 onLoadEnd 才注入的 —— 模块级读一次会读到
 *    `undefined`，整页钉死在默认值上；
 *  - vitest 把 import 提升到 `beforeEach` **之前** —— 用例还没摆好 `window.ccoderPrefs`，
 *    偏好就已经定死了（`i18n.test.ts` 第一条守的就是这件事）。
 *
 * 于是：`getPrefs()` 第一次调用时才读注入值（读过就缓存），之后只认 [applyPrefs]
 * （Kotlin 推过来那条路，落到 `window.ccoderPrefsSink`）。
 *
 * ## 归一化是 Kotlin 侧那位孪生兄弟
 *
 * 认不出的值一律当**默认档**（`false` = 不折叠），永不抛 —— 跨进程来的东西是外部输入，
 * 一个拼错的键不该让整页白屏。两边的默认值与判定口径必须一致，各自的用例互相指名。
 */
export interface Prefs {
  /** 思考块默认收起（进行中的也收起）。默认 `false` —— 2026-09-14 定的"两个阶段都展开"。 */
  collapseThinking: boolean
}

/** 当前快照。`null` = **还没读过**（不是"默认值"）—— 这个区别是 [getPrefs] 的全部要害。 */
let current: Prefs | null = null

const listeners = new Set<() => void>()

/** 归一：只认 `collapseThinking === true`；缺的、拼错的、类型不对的都是默认档。 */
function normalize(raw: unknown): Prefs {
  const r = (raw ?? {}) as Record<string, unknown>
  return { collapseThinking: r.collapseThinking === true }
}

/** 当前偏好 —— **第一次调用时才去读注入的那一份**，读过就缓存。 */
export function getPrefs(): Prefs {
  if (current === null) current = normalize(window.ccoderPrefs)
  return current
}

/**
 * 收一份新偏好（Kotlin 走 `ccoderPrefsSink` 落到这里；`App.tsx` 挂载时也喂一次注入值）。
 *
 * **幂等**：值没变就不通知订阅者 —— 通知的代价是整棵订阅树重渲染。
 * 传 `undefined` 即复位成默认（用例的收尾就是这么写的）。
 */
export function applyPrefs(raw: unknown): void {
  const next = normalize(raw)
  const changed = current === null || next.collapseThinking !== current.collapseThinking
  current = next
  if (!changed) return
  for (const fn of listeners) fn()
}

/** 订阅偏好变化，返回退订函数（[useCollapseThinking] 的底座）。 */
export function subscribePrefs(fn: () => void): () => void {
  listeners.add(fn)
  return () => {
    listeners.delete(fn)
  }
}

/**
 * 组件里读「思考折叠」。偏好一变，调过它的组件各自重渲染。
 *
 * **返回的是原始布尔值，不是对象**：`useSyncExternalStore` 的 `getSnapshot` 一旦返回新引用
 * 就是无限重渲染，而"记得清缓存"是个能忘的不变量。返回 `boolean` 让 `Object.is` 直接比，
 * 问题整个消失。
 *
 * 两个思考块（完成的、进行中的）**各自调它**，不从 Transcript 透传 —— 透传会打破
 * `Transcript.tsx` 里那批 memo（那笔 48ms/帧的账 2026-09-14 已经算过一次）。
 */
export function useCollapseThinking(): boolean {
  return useSyncExternalStore(subscribePrefs, () => getPrefs().collapseThinking)
}
