import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Transcript } from './Transcript'
import { setLang } from '../i18n'
import { applyPrefs } from '../prefs'
import type { TranscriptItem } from '../types'

/**
 * 用例里的中文断言一律**先把语言钉住**。
 *
 * 这些断言是**文案的回归网**（文案在这个仓库里是产品的一部分），不是"测试恰好
 * 用了中文"：不钉住的话，默认的基底语言是英文，下面每一条都会变成拿中文去
 * 比对英文界面 —— 红得毫无信息量。
 */
beforeEach(() => {
  setLang('zh')
})

function state(...items: TranscriptItem[]) {
  return { items, live: {} }
}

const ts = 1726050000000

describe('Transcript', () => {
  it('空转写区不报错', () => {
    render(<Transcript state={state()} />)
    expect(screen.getByTestId('transcript')).toBeInTheDocument()
  })

  it('子代理的项收进那张 Task 卡里，主流水不重复出现（A1）', async () => {
    const user = userEvent.setup()
    render(
      <Transcript
        state={state(
          { kind: 'toolUse', id: 'm1', ts, name: 'Task', input: '{}', toolUseId: 'toolu_task' },
          {
            kind: 'toolUse', id: 'm2', ts, name: 'Grep', input: '{}',
            toolUseId: 'toolu_sub', parent: 'toolu_task',
          },
          { kind: 'assistant', id: 'm3', ts, text: '找到了三处', parent: 'toolu_task' },
        )}
      />,
    )

    // 卡片默认收着（2026-09-18 撤销了 A1 那天的自动展开），先点开才看得到里面。
    // 收起时 DOM 里只有 Task 这一张卡 —— 子代理那两张还没被渲染出来
    expect(screen.queryByTestId('subagent-block')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { expanded: false }))

    // 子代理那一块在，且它里面确实装着那两条
    const block = screen.getByTestId('subagent-block')
    expect(block).toBeInTheDocument()
    expect(block).toHaveTextContent('子代理的对话')
    expect(block).toHaveTextContent('1 次工具调用')
    // **只有一份**：子代理的正文没有同时留在主流水里
    expect(screen.getAllByText('找到了三处')).toHaveLength(1)
  })

  it('渲染用户消息', () => {
    render(<Transcript state={state({ kind: 'user', id: 'u', ts, text: '你好' })} />)
    expect(screen.getByText('你好')).toBeInTheDocument()
  })

  it('渲染 Claude 消息', () => {
    render(<Transcript state={state({ kind: 'assistant', id: 'a', ts, text: '在的' })} />)
    expect(screen.getByText('在的')).toBeInTheDocument()
  })

  it('渲染错误消息', () => {
    render(<Transcript state={state({ kind: 'error', id: 'e', ts, text: '认证失败' })} />)
    expect(screen.getByText('认证失败')).toBeInTheDocument()
    expect(screen.getByTestId('error-bubble')).toBeInTheDocument()
  })

  it('渲染系统提示', () => {
    render(<Transcript state={state({ kind: 'systemNote', id: 's', ts, text: '会话已就绪' })} />)
    expect(screen.getByText('会话已就绪')).toBeInTheDocument()
  })

  it('result 显示的是本回合的 token 与耗时', () => {
    render(
      <Transcript
        state={state({
          kind: 'result',
          id: 'r',
          ts,
          subtype: 'success',
          durationMs: 33818,
          inputTokens: 12432,
          cacheReadTokens: 8100,
          outputTokens: 1234,
        })}
      />,
    )
    expect(screen.getByText('成功 · 输入 12.4k · 缓存 8.1k · 输出 1.2k · 33.8s')).toBeInTheDocument()
  })

  it('result 的缺省字段不显示；**花费即使有也不显示**（累计值，会被读成本次）', () => {
    render(<Transcript state={state({ kind: 'result', id: 'r', ts, subtype: 'success', costUsd: 4.546 })} />)
    expect(screen.getByText('成功')).toBeInTheDocument()
    expect(screen.queryByText(/\$/)).not.toBeInTheDocument()
  })

  it('每条消息显示时间戳', () => {
    render(<Transcript state={state({ kind: 'user', id: 'u', ts, text: '你好' })} />)
    // 时间戳按本地时区格式化，只断言格式而非具体值
    expect(screen.getByTestId('timestamp')).toHaveTextContent(/^\d{2}:\d{2}$/)
  })

  it('思考块默认展开，点一下才收起', async () => {
    const user = userEvent.setup()
    render(<Transcript state={state({ kind: 'thinking', id: 't', ts, text: '让我想想' })} />)

    expect(screen.getByText('让我想想')).toBeInTheDocument()
    await user.click(screen.getByText(/思考过程/))
    expect(screen.queryByText('让我想想')).not.toBeInTheDocument()
  })

  it('工具调用默认折叠，展开显示参数', async () => {
    const user = userEvent.setup()
    render(
      <Transcript
        state={state({
          kind: 'toolUse', id: 'x', ts, toolUseId: 'toolu_1',
          name: 'Read', input: '{"file_path":"/a.txt"}',
        })}
      />,
    )

    expect(screen.getByText(/Read/)).toBeInTheDocument()
    expect(screen.queryByText(/file_path/)).not.toBeInTheDocument()
    await user.click(screen.getByText(/Read/))
    expect(screen.getByText(/file_path/)).toBeInTheDocument()
  })

  it('Bash 展开后给的是命令本身，而不是一坨 JSON', async () => {
    // 这一条断言的是本次改动的核心：以前展开看到的是缩进过的参数 JSON，
    // 用户得自己从里面读出"这次跑了什么命令"
    const user = userEvent.setup()
    render(
      <Transcript
        state={state({
          kind: 'toolUse', id: 'x', ts, toolUseId: 'toolu_1',
          name: 'Bash', input: '{"command":"gradlew test"}',
        })}
      />,
    )
    await user.click(screen.getByRole('button', { expanded: false }))
    expect(screen.getByTestId('tool-command')).toHaveTextContent('gradlew test')
    expect(screen.queryByText(/"command":/)).not.toBeInTheDocument()
  })

  it('工具调用的非法 JSON 参数按原文显示', async () => {
    const user = userEvent.setup()
    render(
      <Transcript
        state={state({
          kind: 'toolUse', id: 'x', ts, toolUseId: 'toolu_1', name: 'X', input: 'not json',
        })}
      />,
    )
    await user.click(screen.getByRole('button', { expanded: false }))
    expect(screen.getByText('not json')).toBeInTheDocument()
  })

  it('工具结果按 toolUseId 挂回对应的那张卡片', async () => {
    // 两者在协议里是**两条独立的消息**（结果是后到的那条），
    // 界面上要看不见这条缝：输出必须出现在它自己那张卡片里
    const user = userEvent.setup()
    render(
      <Transcript
        state={state(
          { kind: 'toolUse', id: 'x1', ts, toolUseId: 'toolu_1', name: 'Bash', input: '{"command":"ls"}' },
          { kind: 'toolUse', id: 'x2', ts, toolUseId: 'toolu_2', name: 'Bash', input: '{"command":"pwd"}' },
          { kind: 'toolResult', id: 'r1', ts, toolUseId: 'toolu_2', text: '/home/cy', isError: false },
        )}
      />,
    )

    // 第一条（toolu_1）没有结果：展开后不能把别人的输出挂上来
    await user.click(screen.getAllByRole('button', { expanded: false })[0])
    expect(screen.queryByTestId('tool-output')).not.toBeInTheDocument()

    // 第一条已经展开，剩下的那个收起的就是第二条（toolu_2）—— 它的结果在这张卡上
    await user.click(screen.getAllByRole('button', { expanded: false })[0])
    expect(screen.getByTestId('tool-output')).toHaveTextContent('/home/cy')
  })

  it('进行中的气泡以流式形式渲染', () => {
    render(<Transcript state={{ items: [], live: { assistant: '正在输入' } }} />)
    expect(screen.getByText('正在输入')).toBeInTheDocument()
    expect(screen.getByTestId('streaming-cursor')).toBeInTheDocument()
  })

  it('多条消息按序渲染', () => {
    render(
      <Transcript
        state={state(
          { kind: 'user', id: 'u', ts, text: '第一' },
          { kind: 'assistant', id: 'a', ts: ts + 1, text: '第二' },
        )}
      />,
    )
    const transcript = screen.getByTestId('transcript')
    const text = transcript.textContent ?? ''
    expect(text.indexOf('第一')).toBeLessThan(text.indexOf('第二'))
  })

  it('未填充的 live 字段不渲染气泡', () => {
    render(<Transcript state={{ items: [], live: { thinking: 'x' } }} />)
    expect(screen.queryByTestId('streaming-cursor')).not.toBeInTheDocument()
  })

  it('未知 kind 不导致崩溃', () => {
    // 对应设计文档 §3.3：Kotlin 侧新增类型时旧前端不该白屏
    const bogus = { kind: 'someNewKind', id: 'z', ts } as unknown as TranscriptItem
    expect(() => render(<Transcript state={state(bogus)} />)).not.toThrow()
  })
})

/**
 * jsdom 不做布局：`scrollHeight` / `clientHeight` 恒为 0，`scrollTo` 与
 * `scrollIntoView` 根本不存在（实测）。所以滚动跟随的测试必须自己安装
 * "滚动度量" —— 一个可读回的 `scrollTop` 和可控的 `scrollHeight`。
 *
 * 注意：程序化赋值 `scrollTop` 在 jsdom 里**不会**派发 scroll 事件（真实浏览器
 * 会），所以"用户滚动"一律用 `fireEvent.scroll` 显式模拟。
 */
function installScrollMetrics(
  el: HTMLElement,
  init: { scrollHeight: number; clientHeight: number; scrollTop?: number },
) {
  let top = init.scrollTop ?? 0
  let scrollHeight = init.scrollHeight
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => scrollHeight })
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => init.clientHeight })
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => top,
    set: (v: number) => {
      top = v
    },
  })
  return {
    top: () => top,
    setTop: (v: number) => {
      top = v
    },
    growTo: (h: number) => {
      scrollHeight = h
    },
  }
}

// 思考期间必须有活信号：实测 29% 的思考块跑过 5 秒，而那段时间屏幕是静止的。
// 进行中的思考渲染成折叠块（转圈 + 秒数），整块到达后由 codec 清掉缓冲。
describe('进行中的思考', () => {
  const live = (thinking: string, assistant?: string) => {
    const buffers: Record<string, string> = { thinking }
    if (assistant !== undefined) buffers.assistant = assistant
    return { items: [] as TranscriptItem[], live: buffers }
  }

  it('有思考缓冲时渲染进行中的思考块', () => {
    render(<Transcript state={live('在想')} />)
    expect(screen.getByTestId('live-thinking')).toBeInTheDocument()
    expect(screen.getByText('思考中')).toBeInTheDocument()
  })

  it('没有思考缓冲就不渲染它', () => {
    render(<Transcript state={live('', '正文')} />)
    expect(screen.queryByTestId('live-thinking')).not.toBeInTheDocument()
  })

  it('思考进行中 + 正文已开始：两个都在，思考在上', () => {
    render(<Transcript state={live('在想', '正文')} />)
    const think = screen.getByTestId('live-thinking')
    const text = screen.getByText('正文')
    // compareDocumentPosition 的 FOLLOWING 位 = text 在 think 之后
    expect(think.compareDocumentPosition(text) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('整块思考到达后，进行中的那块就没了', () => {
    const { rerender } = render(<Transcript state={live('在想')} />)
    expect(screen.getByTestId('live-thinking')).toBeInTheDocument()

    rerender(
      <Transcript
        state={{ items: [{ kind: 'thinking', id: 't1', ts, text: '想完了' }], live: {} }}
      />,
    )
    expect(screen.queryByTestId('live-thinking')).not.toBeInTheDocument()
    expect(screen.getByText('思考过程')).toBeInTheDocument()
  })
})

/**
 * 界面偏好（思考折叠）穿到历史条目里 —— 这条守的是 `Item` 那层 memo。
 *
 * 两个思考块**各自**订阅偏好（见 prefs.ts），不从 Transcript 透传：透传要新建节点，
 * `Item` 的 memo 当场失效（那笔 48ms/帧的账 2026-09-14 算过）。这条用例证明那条路
 * 真的通到了**已经画在屏幕上**的块上。
 */
describe('思考折叠穿到历史条目', () => {
  afterEach(() => {
    // 偏好的 store 是模块级的：同文件内会串（见 prefs.test.ts）
    act(() => applyPrefs(undefined))
    delete window.ccoderPrefs
  })

  it('偏好一变，历史里已有的思考块也跟着收 —— memo 不该把它挡住', () => {
    render(<Transcript state={state({ kind: 'thinking', id: 't1', ts, text: '让我想想' })} />)
    expect(screen.getByText('让我想想')).toBeInTheDocument()

    act(() => applyPrefs({ collapseThinking: true }))

    expect(screen.queryByText('让我想想')).not.toBeInTheDocument()
    expect(screen.getByText('思考过程')).toBeInTheDocument()
  })
})

// 设计稿 docs/design/tool-progress.html：卡片的"进行中 / 完成 / 已中断"是前端推出来的 ——
// 结果没到就是还在跑；回合已经结束了还没等到结果，就是被中断（否则它会永远转圈）。
describe('工具卡片的状态收尾', () => {
  const use = (id: string, toolUseId: string): TranscriptItem => ({
    kind: 'toolUse', id, ts, toolUseId, name: 'Bash', input: '{"command":"ls"}',
  })
  const res = (id: string, toolUseId: string): TranscriptItem => ({
    kind: 'toolResult', id, ts, toolUseId, text: 'out', isError: false,
  })
  const turnEnd: TranscriptItem = { kind: 'result', id: 'r', ts, subtype: 'success' }

  it('结果没到、回合还没结束 → 进行中', () => {
    render(<Transcript state={state(use('t1', 'toolu_1'))} />)
    expect(screen.getByTestId('tool-running')).toBeInTheDocument()
  })

  it('回合都结束了还没等到结果 → 标为已中断', () => {
    render(<Transcript state={state(use('t1', 'toolu_1'), turnEnd)} />)
    expect(screen.getByTestId('tool-aborted')).toBeInTheDocument()
    expect(screen.queryByTestId('tool-running')).not.toBeInTheDocument()
  })

  it('配上了结果的卡片不受回合结束影响 → 完成', () => {
    render(<Transcript state={state(use('t1', 'toolu_1'), res('r1', 'toolu_1'), turnEnd)} />)
    expect(screen.getByTestId('tool-done')).toBeInTheDocument()
  })

  it('新回合里正在跑的工具，不会被上一回合的 result 误判成中断', () => {
    render(
      <Transcript
        state={state(use('t1', 'toolu_1'), res('r1', 'toolu_1'), turnEnd, use('t2', 'toolu_2'))}
      />,
    )
    expect(screen.getByTestId('tool-running')).toBeInTheDocument()
    expect(screen.queryByTestId('tool-aborted')).not.toBeInTheDocument()
  })
})

// 设计文档 §4.5：v1 曾有 scrollToBottom，JCEF 重写时丢失 —— 这组用例把它钉住。
describe('Transcript 滚动跟随', () => {
  // 距底 = scrollHeight - scrollTop - clientHeight = 1000 - top - 400
  const METRICS = { scrollHeight: 1000, clientHeight: 400 }

  /** 渲染并把滚动度量装到转写容器上。 */
  function setup(...items: TranscriptItem[]) {
    const view = render(<Transcript state={state(...items)} />)
    const el = screen.getByTestId('transcript')
    return { view, el, m: installScrollMetrics(el, METRICS) }
  }

  const first: TranscriptItem = { kind: 'user', id: 'u', ts, text: '一' }
  const second: TranscriptItem = { kind: 'assistant', id: 'a', ts: ts + 1, text: '二' }

  it('内容增长时把视口带到最底端', () => {
    const { view, m } = setup(first)

    m.growTo(1200)
    view.rerender(<Transcript state={state(first, second)} />)

    expect(m.top()).toBe(1200)
    expect(screen.queryByTestId('jump-to-bottom')).not.toBeInTheDocument()
  })

  it('用户向上滚动后，新内容不再移动视口', () => {
    const { view, el, m } = setup(first)

    m.setTop(200) // 距底 400px，远超阈值 → 进入暂停
    fireEvent.scroll(el)

    m.growTo(1400)
    view.rerender(<Transcript state={state(first, second)} />)

    expect(m.top()).toBe(200)
    // `data-new` 是"暂停已被登记"的可观察证据：它只在 layout effect 的暂停分支里
    // 被置起。**不能拿"按钮在不在"当证据** —— 滚上去之后按钮本来就会在
    // （2026-09-15 改），那时本用例在完全未实现时也会通过："不移动视口"与
    // "什么都不做"分不开。
    expect(screen.getByTestId('jump-to-bottom')).toHaveAttribute('data-new', 'true')
  })

  it('滚上去但一条新内容都没来时，按钮也在（滚上去就是想回来）', () => {
    // 2026-09-15 用户报"回到底部的按钮现在怎么不显示了"——旧条件是
    // `!stick && hasNewWhilePaused`，所以"答完之后往上翻旧内容"时它不出现，
    // 而那正是用户最想点它的时候。
    const { el, m } = setup(first)

    m.setTop(200)
    fireEvent.scroll(el)

    expect(screen.getByTestId('jump-to-bottom')).toBeInTheDocument()
    expect(screen.getByTestId('jump-to-bottom')).toHaveAttribute('data-new', 'false')
  })

  it('用户滚回底部附近时跟随自动恢复', () => {
    const { view, el, m } = setup(first)

    m.setTop(200)
    fireEvent.scroll(el) // 先进入暂停
    m.setTop(600) // 距底 0px → 恢复跟随
    fireEvent.scroll(el)

    m.growTo(1200)
    view.rerender(<Transcript state={state(first, second)} />)

    expect(m.top()).toBe(1200)
  })

  it('暂停期间到达新内容时按钮上加小圆点，点击后恢复跟随并滚到底', async () => {
    const user = userEvent.setup()
    const { view, el, m } = setup(first)

    m.setTop(200)
    fireEvent.scroll(el)

    // 暂停但还没有新内容 —— 按钮就在了（滚上去就该点得到），只是没有小圆点
    expect(screen.getByTestId('jump-to-bottom')).toHaveAttribute('data-new', 'false')

    m.growTo(1400)
    view.rerender(<Transcript state={state(first, second)} />)

    // 有新内容仍未打扰视口，小圆点出现
    expect(m.top()).toBe(200)
    expect(screen.getByTestId('jump-to-bottom')).toHaveAttribute('data-new', 'true')
    await user.click(screen.getByTestId('jump-to-bottom'))

    expect(m.top()).toBe(1400)
    expect(screen.queryByTestId('jump-to-bottom')).not.toBeInTheDocument()
  })

  it('暂停期间自己发一条：视口自动回到底部，暂停与按钮一起收起', () => {
    // 2026-09-15 用户报："发送消息时如果输出区不在最底部，应该自动滚到最底部"。
    // 暂停态下按发送、屏幕上什么都不动，看起来像没发出去
    const { view, el, m } = setup(first)

    m.setTop(200) // 先翻上去读旧内容
    fireEvent.scroll(el)
    expect(screen.getByTestId('jump-to-bottom')).toBeInTheDocument()

    const mine: TranscriptItem = { kind: 'user', id: 'u2', ts: ts + 2, text: '我发一条' }
    m.growTo(1500)
    view.rerender(<Transcript state={state(first, second, mine)} />)

    expect(m.top()).toBe(1500)
    expect(screen.queryByTestId('jump-to-bottom')).not.toBeInTheDocument()
  })

  it('回放历史（切会话 / 恢复）后落在最底端', () => {
    // 回放走的是同一条路（最后一条用户消息也"换了 id"），所以同样落到最底端。
    // 这是上面那条规则的**已知后果**，不是漏网 —— 记在这里免得被当 bug 改掉
    const { view, el, m } = setup(first, second)

    m.setTop(200)
    fireEvent.scroll(el)

    view.rerender(<Transcript state={state()} />) // Reset：转写区先清空

    const replay: TranscriptItem[] = [
      { kind: 'user', id: 'h1', ts, text: '旧的一' },
      { kind: 'assistant', id: 'h2', ts, text: '旧的二' },
      { kind: 'user', id: 'h3', ts, text: '旧的三' },
    ]
    m.growTo(1800)
    view.rerender(<Transcript state={state(...replay)} />)

    expect(m.top()).toBe(1800)
  })

  it('平滑滚动动画期间不会被自己的中间位置误判为用户上滚', async () => {
    const user = userEvent.setup()
    const { view, el, m } = setup(first)
    const third: TranscriptItem = { kind: 'user', id: 'u2', ts: ts + 2, text: '三' }

    m.setTop(200)
    fireEvent.scroll(el)
    m.growTo(1400)
    view.rerender(<Transcript state={state(first, second)} />)

    // jsdom 没有 scrollTo（实测），装上它才能走到平滑分支 ——
    // 否则这条用例只会重复验证兜底路径，守卫依然零覆盖。
    Object.defineProperty(el, 'scrollTo', { configurable: true, value: vi.fn() })
    await user.click(screen.getByTestId('jump-to-bottom'))

    // 动画进行中：位置仍在半途（距底 400px）。若守卫失效，这个事件会把跟随
    // 关掉，于是下一条新内容不再把视口带到最底端。
    m.setTop(200)
    fireEvent.scroll(el)

    m.growTo(1600)
    view.rerender(<Transcript state={state(first, second, third)} />)

    expect(m.top()).toBe(1600)
  })

  // ---- 滚轮意图（2026-09-21）----
  //
  // 用户报"思考输出的时候，转写区往上滚动的时候，会自动弹回来"。病根不是"卡"：
  // 跟随是每帧一次 `scrollTop = scrollHeight` 的**绝对写入**，而它总排在**同一帧的
  // scroll steps 之前**，于是处理器读到的位置永远是"已经被我们自己写回去"的那一个 ——
  // 用户上滚这件事从位置读数里看不出来。判据因此换成滚轮事件自带的**方向**。
  //
  // 真实浏览器里的时序（写入先落地、事件后派发）在 jsdom 里没有，所以这里显式
  // 排出来：**用户那一次位移不发 scroll 事件**（最坏顺序下它与我们那次写入合并成
  // 一条、报的是写入后的位置），随后那条才是"我们自己的回声"。
  // 端到端的时序由 `npm run probe:scroll` 在真实 Chromium 里守着。

  it('滚轮上滚立刻暂停：紧跟其后那条"已到底"的回声不能把跟随打开', () => {
    const { view, el, m } = setup(first)

    // 先来一拍内容（应用写一次 → 回声盾置起）
    m.growTo(1100)
    view.rerender(<Transcript state={state(first, second)} />)

    m.setTop(400) // 用户把视口拉上去（距底 300px）
    fireEvent.wheel(el, { deltaY: -100 })

    // 我们自己那笔写入的回声：位置报的正是底部（距底 0）。**这一笔会把用户那 400
    // 盖掉**（最坏顺序就是如此），所以本用例盯的不是"用户的位置还在"，而是
    // **跟随没有被回声打开** —— 证据是接下来这次内容增长没有把它写到新的底部。
    m.setTop(700)
    fireEvent.scroll(el)

    m.growTo(1400)
    view.rerender(<Transcript state={state(first, second)} />)

    expect(m.top()).toBe(700) // 没被写到新的底部（1400）
    expect(screen.getByTestId('jump-to-bottom')).toBeInTheDocument()
  })

  it('滚轮上滚时人就停在底部也照样暂停（意图优先于位置）', () => {
    const { view, el, m } = setup(first)

    // 让应用自己写一次，把"人在底部"摆出来（度量在 render 之后才装上，
    // 挂载那一次的写入落不进来 —— 与既有用例同一个前提）
    m.growTo(1200)
    view.rerender(<Transcript state={state(first, second)} />)
    expect(m.top()).toBe(1200) // 跟随把它写到的新底部

    fireEvent.wheel(el, { deltaY: -100 })
    expect(screen.getByTestId('jump-to-bottom')).toBeInTheDocument()

    m.growTo(1500)
    view.rerender(<Transcript state={state(first, second)} />)

    expect(m.top()).toBe(1200) // 停在原地：意图说"别动"，位置读数没有发言权
  })

  it('贴着顶再上滚不暂停 —— 转写区根本没得向上滚', () => {
    const { view, el, m } = setup(first)

    m.setTop(0)
    fireEvent.wheel(el, { deltaY: -100 })

    expect(screen.queryByTestId('jump-to-bottom')).not.toBeInTheDocument()

    m.growTo(1400)
    view.rerender(<Transcript state={state(first, second)} />)

    expect(m.top()).toBe(1400) // 跟随没被误关
  })

  it('暂停后滚轮向下、且已在底部：跟随恢复', () => {
    const { view, el, m } = setup(first)

    m.setTop(300)
    fireEvent.wheel(el, { deltaY: -100 })
    expect(screen.getByTestId('jump-to-bottom')).toBeInTheDocument()

    m.setTop(600) // 滚回底部（距底 0）
    fireEvent.wheel(el, { deltaY: 100 })

    m.growTo(1400)
    view.rerender(<Transcript state={state(first, second)} />)

    expect(m.top()).toBe(1400)
    expect(screen.queryByTestId('jump-to-bottom')).not.toBeInTheDocument()
  })

  it('平滑动画被滚轮接管：那条动画标记不再卡住，之后的位置事件照常生效', async () => {
    const user = userEvent.setup()
    const { view, el, m } = setup(first)
    // 用来"再长一点内容"的那条**不能是新用户消息** —— 那会触发"自己发一条 →
    // 强制回到底部"（§4.5 的追加条），把本用例要验的东西盖掉
    const grew: TranscriptItem = { kind: 'assistant', id: 'a2', ts: ts + 2, text: '三' }

    m.setTop(200)
    fireEvent.scroll(el)
    m.growTo(1400)
    view.rerender(<Transcript state={state(first, second)} />)

    Object.defineProperty(el, 'scrollTo', { configurable: true, value: vi.fn() })
    await user.click(screen.getByTestId('jump-to-bottom')) // 平滑动画开始，标记置起

    // 动画途中用户上滚：位置 + 意图。标记必须随之作废 —— 动画被打断后
    // 再也读不到"已到底"，卡着的标记会把之后所有 scroll 事件吞掉
    m.setTop(200)
    fireEvent.wheel(el, { deltaY: -100 })

    m.setTop(300) // 之后一条普通的用户位移
    fireEvent.scroll(el)

    m.growTo(1600)
    view.rerender(<Transcript state={state(first, second, grew)} />)

    expect(m.top()).toBe(300)
  })
})
