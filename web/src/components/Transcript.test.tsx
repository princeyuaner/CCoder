import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Transcript } from './Transcript'
import type { TranscriptItem } from '../types'

function state(...items: TranscriptItem[]) {
  return { items, live: {} }
}

const ts = 1726050000000

describe('Transcript', () => {
  it('空转写区不报错', () => {
    render(<Transcript state={state()} />)
    expect(screen.getByTestId('transcript')).toBeInTheDocument()
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
})
