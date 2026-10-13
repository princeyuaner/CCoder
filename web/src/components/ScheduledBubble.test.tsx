import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { setLang } from '../i18n'
import { ScheduledBubble } from './ScheduledBubble'
import type { ScheduledEntryWire } from '../types'

/**
 * 幽灵气泡（2026-10-13，定时发送 · 丙案）：
 * 画什么（时间标签/待发/正文/图记号）与撤回收键的**线上格式**
 * （桥发的字节就是 Kotlin 要解析的东西，同 TurnChangesCard.test 的断言法）。
 */

function captureSend(): string[] {
  const sent: string[] = []
  window.ccoder = { send: (message: string) => sent.push(message) }
  return sent
}

// 中文断言先钉语言 —— 默认基底是英文（同 TurnChangesCard.test 那条）
beforeEach(() => {
  setLang('zh')
})

afterEach(() => {
  delete window.ccoder
})

function entryOf(over: Partial<ScheduledEntryWire> = {}): ScheduledEntryWire {
  return {
    id: 's1',
    label: '今天 18:00',
    text: '帮我整理今天的发布记录',
    ...over,
  }
}

describe('定时待发气泡', () => {
  it('时间标签原样渲染（Kotlin 拼好的成品），跟着「待发」一起读', () => {
    render(<ScheduledBubble entry={entryOf()} />)
    expect(screen.getByTestId('scheduled-label').textContent).toBe('今天 18:00')
    expect(screen.getByText('待发')).toBeTruthy()
    expect(screen.getByText('帮我整理今天的发布记录')).toBeTruthy()
  })

  it('没图不画记号；带图挂「图 N」', () => {
    const { unmount } = render(<ScheduledBubble entry={entryOf()} />)
    expect(screen.queryByTestId('scheduled-images')).toBeNull()
    unmount()

    render(<ScheduledBubble entry={entryOf({ imageCount: 2 })} />)
    expect(screen.getByTestId('scheduled-images').textContent).toBe('图 2')
  })

  it('一张图写单数「图 1」', () => {
    render(<ScheduledBubble entry={entryOf({ imageCount: 1 })} />)
    expect(screen.getByTestId('scheduled-images').textContent).toBe('图 1')
  })

  it('点 ✕ 发 cancelScheduled —— 格式就是 Kotlin 的要解析的', () => {
    const sent = captureSend()
    render(<ScheduledBubble entry={entryOf()} />)
    fireEvent.click(screen.getByTestId('scheduled-cancel'))
    expect(sent.map((m) => JSON.parse(m))).toEqual([{ op: 'cancelScheduled', id: 's1' }])
  })

  it('正文里的参考记号与用户气泡同一个画法（span.ref）', () => {
    render(<ScheduledBubble entry={entryOf({ text: '看这段 ⟦src/a.kt 12-18 · 7 行⟧ 再改' })} />)
    const ref = screen.getByText('⟦src/a.kt 12-18 · 7 行⟧')
    expect(ref.className).toContain('ref')
  })
})
