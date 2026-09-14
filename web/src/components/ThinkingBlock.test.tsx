import { describe, expect, it, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LiveThinkingBlock, ThinkingBlock } from './ThinkingBlock'

/**
 * 思考块。
 *
 * 为什么需要一个"进行中"的形态：实测 207 个思考块里，29% 跑过 5 秒以上
 * （P90 10.5s，最长 31.5s），而在此之前那段时间屏幕上**什么都不动** ——
 * 用户的原话是"看起来感觉像卡死了"。
 *
 * 2026-09-14 改：两个阶段都**默认展开**（用户要"流式输出"看得见）。
 * 转圈与秒数留着 —— 收起之后它们是唯一还在动的信号。
 */
describe('LiveThinkingBlock（进行中）', () => {
  it('默认展开：标题是「思考中」，正文直接看得见', () => {
    render(<LiveThinkingBlock text="在想" />)
    expect(screen.getByText('思考中')).toBeInTheDocument()
    expect(screen.getByText('在想')).toBeInTheDocument()
  })

  it('带一个转圈 —— 这是"它在动"的信号本身', () => {
    render(<LiveThinkingBlock text="在想" />)
    expect(screen.getByTestId('thinking-spin')).toBeInTheDocument()
  })

  it('不到一秒不显示秒数', () => {
    render(<LiveThinkingBlock text="在想" />)
    expect(screen.queryByTestId('thinking-elapsed')).not.toBeInTheDocument()
  })

  it('跑满一秒后标题带秒数', () => {
    vi.useFakeTimers()
    try {
      render(<LiveThinkingBlock text="在想" />)
      act(() => {
        vi.advanceTimersByTime(1000)
      })
      expect(screen.getByTestId('thinking-elapsed')).toHaveTextContent('1s')
      act(() => {
        vi.advanceTimersByTime(4000)
      })
      expect(screen.getByTestId('thinking-elapsed')).toHaveTextContent('5s')
    } finally {
      vi.useRealTimers()
    }
  })

  it('点一下收起 —— 收起来之后只剩标题里那个转圈在动', async () => {
    render(<LiveThinkingBlock text="正在推敲这个方案" />)
    expect(screen.getByText('正在推敲这个方案')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button'))
    expect(screen.queryByText('正在推敲这个方案')).not.toBeInTheDocument()
    expect(screen.getByTestId('thinking-spin')).toBeInTheDocument()
  })
})

describe('ThinkingBlock（完成态）', () => {
  it('标题是「思考过程」，正文默认展开', () => {
    // 与进行中那块一样默认展开：否则思考一结束，屏幕上会**跳一下**
    // （同一块内容从一个形态换成另一个形态，展开态不该变）
    render(<ThinkingBlock text="想完了" />)
    expect(screen.getByText('思考过程')).toBeInTheDocument()
    expect(screen.getByText('想完了')).toBeInTheDocument()
  })
})
