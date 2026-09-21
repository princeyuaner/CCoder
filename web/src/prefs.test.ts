import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 偏好快照（prefs.ts）的 store。
 *
 * 与 i18n.test.ts 同一个套路：每条用例要一份**全新**的模块 —— 偏好是模块级状态
 * （读过一次就缓存），而这里最要紧的两件事（"惰性"与"幂等"）只有从零开始才测得到。
 * 用 `vi.resetModules()` 而不是在 prefs.ts 里开测试后门：**被测的不是那个后门，
 * 是真实的加载顺序**。
 */
async function freshPrefs() {
  vi.resetModules()
  return await import('./prefs')
}

beforeEach(() => {
  delete window.ccoderPrefs
})

describe('偏好从哪来', () => {
  it('没有注入时是默认档（不折叠）', async () => {
    const { getPrefs } = await freshPrefs()

    expect(getPrefs().collapseThinking).toBe(false)
  })

  it('注入里带了值就读它', async () => {
    window.ccoderPrefs = { collapseThinking: true }
    const { getPrefs } = await freshPrefs()

    expect(getPrefs().collapseThinking).toBe(true)
  })

  it('**惰性**：注入晚于模块加载也认得 —— 这条就是惰性的存在理由', async () => {
    // 真实时序：main.tsx 一进来就渲染，桥要等 CEF 走完 onLoadEnd 才注入；
    // vitest 把 import 提升到 beforeEach 之前，是同一个问题的另一半
    const { getPrefs } = await freshPrefs()

    window.ccoderPrefs = { collapseThinking: true }

    expect(getPrefs().collapseThinking).toBe(true)
  })

  it('读一次之后就定住，桥再变也不动 —— 换偏好走 applyPrefs', async () => {
    window.ccoderPrefs = { collapseThinking: true }
    const { getPrefs } = await freshPrefs()
    expect(getPrefs().collapseThinking).toBe(true)

    // 快照被别处改了（Kotlin 只在没人听的时候写它，这一侧不该跟着漂）
    window.ccoderPrefs = { collapseThinking: false }

    expect(getPrefs().collapseThinking).toBe(true)
  })

  it('认不出的值当默认档，且不抛 —— 拼错的键不该让整页白屏', async () => {
    const bad: unknown[] = [{ collapseThinking: 'yes' }, { folded: true }, null, 42, 'true']

    for (const raw of bad) {
      const { getPrefs } = await freshPrefs()
      window.ccoderPrefs = raw as { collapseThinking?: boolean }

      expect(getPrefs().collapseThinking).toBe(false)
    }
  })
})

describe('applyPrefs', () => {
  it('幂等：同一个值再来一遍不通知订阅者', async () => {
    const { applyPrefs, subscribePrefs } = await freshPrefs()
    const listener = vi.fn()
    subscribePrefs(listener)

    applyPrefs({ collapseThinking: true })
    applyPrefs({ collapseThinking: true })
    applyPrefs({ collapseThinking: true })

    // 通知的代价是整棵订阅树重渲染，所以"没变就不叫"是必须的
    // （Kotlin 那边每次关设置对话框都会推一次，哪怕用户没碰过这个开关）
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('改了值就通知（每次都通知）', async () => {
    const { applyPrefs, subscribePrefs } = await freshPrefs()
    const listener = vi.fn()
    subscribePrefs(listener)

    applyPrefs({ collapseThinking: true })
    applyPrefs({ collapseThinking: false })
    applyPrefs({ collapseThinking: true })

    expect(listener).toHaveBeenCalledTimes(3)
  })

  it('传 undefined 即复位成默认 —— 用例的收尾就是这么写的', async () => {
    const { applyPrefs, getPrefs } = await freshPrefs()

    applyPrefs({ collapseThinking: true })
    expect(getPrefs().collapseThinking).toBe(true)

    applyPrefs(undefined)
    expect(getPrefs().collapseThinking).toBe(false)
  })

  it('退订之后不再被叫', async () => {
    const { applyPrefs, subscribePrefs, getPrefs } = await freshPrefs()
    const listener = vi.fn()

    const off = subscribePrefs(listener)
    applyPrefs({ collapseThinking: true })
    expect(listener).toHaveBeenCalledTimes(1)

    off()
    applyPrefs({ collapseThinking: false })

    expect(listener).toHaveBeenCalledTimes(1) // 还是那一次
    expect(getPrefs().collapseThinking).toBe(false) // 退订只是不听，值照换
  })
})
