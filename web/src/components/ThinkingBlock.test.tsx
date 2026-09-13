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
 * 完成态维持原样（收着的「思考过程」），进行中多一个转圈与秒数。
 */
describe('LiveThinkingBlock（进行中）', () => {
  it('默认收着，标题是「思考中」，正文不渲染', () => {
    render(<LiveThinkingBlock text="在想" />)
    expect(screen.getByText('思考中')).toBeInTheDocument()
    expect(screen.queryByText('在想')).not.toBeInTheDocument()
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

  it('点开才看得到实时文字', async () => {
    render(<LiveThinkingBlock text="正在推敲这个方案" />)
    expect(screen.queryByText('正在推敲这个方案')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button'))
    expect(screen.getByText('正在推敲这个方案')).toBeInTheDocument()
  })
})

describe('ThinkingBlock（完成态）', () => {
  it('标题是「思考过程」，正文默认收着', () => {
    render(<ThinkingBlock text="想完了" />)
    expect(screen.getByText('思考过程')).toBeInTheDocument()
    expect(screen.queryByText('想完了')).not.toBeInTheDocument()
  })
})
