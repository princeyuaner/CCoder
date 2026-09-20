import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * i18n 的 store。
 *
 * 每条用例都要一份**全新**的模块：语言是模块级状态（读过一次就缓存），
 * 而这里最要紧的两件事 —— "惰性"与"幂等" —— 只有从零开始才测得到。
 * 用 `vi.resetModules()` 而不是在 i18n.ts 里开测试后门：**被测的不是
 * 那个后门，是真实的加载顺序**。
 */
async function freshI18n() {
  vi.resetModules()
  return await import('./i18n')
}

beforeEach(() => {
  delete window.ccoder
  // 属性跨用例留着没关系（每次都重设），但清掉更接近"刚加载完"的样子
  document.documentElement.lang = 'en'
})

describe('语言从哪来', () => {
  it('没有注入时用基底语言（英文）', async () => {
    const { getLang } = await freshI18n()

    expect(getLang()).toBe('en')
  })

  it('注入里带了标签就读它', async () => {
    window.ccoder = { locale: 'zh' }
    const { getLang } = await freshI18n()

    expect(getLang()).toBe('zh')
  })

  it('**惰性**：注入晚于模块加载也认得 —— 这条就是惰性的存在理由', async () => {
    // 真实时序：main.tsx 一进来就渲染，桥要等 CEF 走完 onLoadEnd 才注入。
    // 模块级读一次的话，这里会是 'en'，整页钉死在英文上（vitest 把 import
    // 提升到 beforeEach 之前，是同一个问题的另一半）
    const { getLang } = await freshI18n()

    window.ccoder = { locale: 'zh' }

    expect(getLang()).toBe('zh')
  })

  it('读一次之后就定住，桥再变也不动 —— 换语言走 setLang', async () => {
    window.ccoder = { locale: 'zh' }
    const { getLang } = await freshI18n()
    expect(getLang()).toBe('zh')

    // 标签被别处改了（Kotlin 侧只在没人听的时候改，这一侧不该跟着漂）
    window.ccoder.locale = 'en'

    expect(getLang()).toBe('zh')
  })

  it('带地区后缀的标签按中文认 —— 界面不该被一个后缀换成英文', async () => {
    const { setLang, getLang } = await freshI18n()

    setLang('zh-CN')

    expect(getLang()).toBe('zh')
  })
})

describe('setLang', () => {
  it('幂等：同一个标签再来一遍不通知订阅者', async () => {
    const { setLang, subscribe } = await freshI18n()
    const listener = vi.fn()
    subscribe(listener)

    setLang('zh')
    setLang('zh')
    setLang('zh')

    // 通知的代价是整棵订阅树重渲染，所以"没变就不叫"是必须的
    expect(listener).toHaveBeenCalledTimes(1)
  })

  it('换成另一种语言会通知（每次都通知）', async () => {
    const { setLang, subscribe } = await freshI18n()
    const listener = vi.fn()
    subscribe(listener)

    setLang('zh')
    setLang('en')
    setLang('zh')

    expect(listener).toHaveBeenCalledTimes(3)
  })

  it('documentElement.lang 跟着走 —— 读屏软件按它挑音库', async () => {
    const { setLang } = await freshI18n()

    setLang('zh')
    expect(document.documentElement.lang).toBe('zh')

    setLang('en')
    expect(document.documentElement.lang).toBe('en')
  })

  it('没变的时候也把属性对齐一次 —— 它可能被别处改坏过', async () => {
    const { setLang } = await freshI18n()
    setLang('zh')

    document.documentElement.lang = 'en' // 别处改坏了

    setLang('zh') // 同一个标签（幂等路径，不通知）

    expect(document.documentElement.lang).toBe('zh')
  })
})

describe('subscribe', () => {
  it('退订之后不再被叫', async () => {
    const { setLang, subscribe, getLang } = await freshI18n()
    const listener = vi.fn()

    const off = subscribe(listener)
    setLang('zh')
    expect(listener).toHaveBeenCalledTimes(1)

    off()
    setLang('en')

    expect(listener).toHaveBeenCalledTimes(1) // 还是那一次
    expect(getLang()).toBe('en') // 退订只是不听，语言照换
  })
})

describe('t', () => {
  it('缺键时返回键本身 —— 界面上看到一个键名比看到一句编出来的话好排查', async () => {
    const { t } = await freshI18n()

    expect(t('no.such.key')).toBe('no.such.key')
  })

  it('按当前语言取，占位符按参数替换', async () => {
    const { t, setLang } = await freshI18n()

    setLang('zh')
    expect(t('tool.showAllLines', [40])).toBe('展开全部 40 行')
    expect(t('transcript.subagent.toolCalls', [3])).toBe('3 次工具调用')

    setLang('en')
    expect(t('tool.showAllLines', [40])).toBe('Show all 40 lines')
    expect(t('transcript.subagent.toolCalls', [3])).toBe('3 tool calls')
  })

  it('没有参数时原样给出模板（`{0}` 留在那里，一眼看得出调用方漏传了）', async () => {
    const { t, setLang } = await freshI18n()
    setLang('zh')

    expect(t('tool.showAllLines')).toBe('展开全部 {0} 行')
    expect(t('tool.showAllLines', [])).toBe('展开全部 {0} 行')
  })

  it('每次调用都看当前语言 —— 模板没有被缓存住', async () => {
    const { t, setLang } = await freshI18n()

    setLang('zh')
    expect(t('result.success')).toBe('成功')

    setLang('en')
    expect(t('result.success')).toBe('success')
  })
})
