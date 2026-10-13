import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { setLang } from '../i18n'
import type { RevertResultWire } from '../types'
import { TurnChangesCard } from './TurnChangesCard'
import type { TurnSummary } from '../turnChanges'

/**
 * 「本轮改动」卡的三态（2026-10-13，用户选定：卡片乙 / 选择甲 / 确认甲）：
 * 收起 → 展开（复选框批量）→ 卡内确认态。以及两条动作的**线上格式**
 * （桥发的字节就是 Kotlin 要解析的东西，同 bridge.test.ts 的断言法）。
 */

function captureSend(): string[] {
  const sent: string[] = []
  window.ccoder = { send: (message: string) => sent.push(message) }
  return sent
}

// 中文断言先钉语言 —— 默认基底是英文，不钉住这些断言红得毫无信息量
//（同 Transcript.test.tsx 那条）
beforeEach(() => {
  setLang('zh')
})

afterEach(() => {
  delete window.ccoder
})

function summaryOf(over: Partial<TurnSummary> = {}): TurnSummary {
  return {
    files: [
      { path: 'src/main/a.kt', edits: 1, add: 3, del: 1, firstToolUseId: 't1' },
      { path: 'src/test/b.kt', edits: 1, add: 111, del: 0, firstToolUseId: 't2' },
    ],
    add: 114,
    del: 1,
    running: false,
    ...over,
  }
}

describe('收起与展开（卡片乙）', () => {
  it('默认收起：只有一行摘要，没有文件行，也不露回滚入口', () => {
    captureSend()
    const { container } = render(<TurnChangesCard summary={summaryOf()} />)

    expect(container.querySelector('.turn-changes')?.getAttribute('data-state')).toBe('collapsed')
    expect(screen.getByText('2 个文件')).toBeTruthy()
    expect(screen.queryAllByTestId('turn-row')).toHaveLength(0)
    expect(screen.queryByTestId('turn-revert-all')).toBeNull()
  })

  it('展开后列出文件与 ± 行数；再点收起回到一行', async () => {
    captureSend()
    const user = userEvent.setup()
    render(<TurnChangesCard summary={summaryOf()} />)

    await user.click(screen.getByTestId('turn-expand'))
    const rows = screen.getAllByTestId('turn-row')
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('a.kt')
    expect(rows[0].textContent).toContain('+3')
    expect(rows[0].textContent).toContain('−1')

    await user.click(screen.getByTestId('turn-expand'))
    expect(screen.queryAllByTestId('turn-row')).toHaveLength(0)
  })

  it('没有文件时不渲染 —— 不占地方', () => {
    captureSend()
    const { container } = render(
      <TurnChangesCard summary={{ files: [], add: 0, del: 0, running: false }} />,
    )
    expect(container.querySelector('.turn-changes')).toBeNull()
  })
})

describe('两条动作的线上格式', () => {
  it('点行 = 打开对比：发 openTurnDiff 与**原样**路径', async () => {
    const sent = captureSend()
    const user = userEvent.setup()
    render(<TurnChangesCard summary={summaryOf()} />)
    await user.click(screen.getByTestId('turn-expand'))

    await user.click(screen.getAllByText('a.kt')[0])
    expect(JSON.parse(sent[0])).toEqual({ op: 'openTurnDiff', path: 'src/main/a.kt' })
  })

  it('悬停那颗小按钮 = 在编辑器打开文件', async () => {
    const sent = captureSend()
    const user = userEvent.setup()
    render(<TurnChangesCard summary={summaryOf()} />)
    await user.click(screen.getByTestId('turn-expand'))

    await user.click(screen.getAllByTestId('turn-row-open')[1])
    expect(JSON.parse(sent[0])).toEqual({ op: 'openFile', path: 'src/test/b.kt' })
  })

  it('「在 IDE 查看」把这轮**未回滚**的文件都开出来', async () => {
    const sent = captureSend()
    const user = userEvent.setup()
    const revert: Record<string, RevertResultWire> = {
      'src/main/a.kt': { path: 'src/main/a.kt', ok: true, action: 'restored' },
    }
    render(<TurnChangesCard summary={summaryOf()} revert={revert} />)
    await user.click(screen.getByTestId('turn-expand'))
    await user.click(screen.getByTestId('turn-review'))

    expect(sent.map((s) => JSON.parse(s))).toEqual([
      { op: 'openTurnDiff', path: 'src/test/b.kt' },
    ])
  })
})

describe('复选框批量 + 卡内确认（选择甲 / 确认甲）', () => {
  it('勾选后底部升起操作条；点「回滚所选」翻成确认态并发出请求', async () => {
    const sent = captureSend()
    const user = userEvent.setup()
    render(<TurnChangesCard summary={summaryOf()} />)
    await user.click(screen.getByTestId('turn-expand'))

    const boxes = screen.getAllByRole('checkbox')
    await user.click(boxes[0])
    expect(screen.getByText('已选 1 个')).toBeTruthy()

    await user.click(screen.getByTestId('turn-revert-selected'))
    // 确认态：逐行摆出后果（恢复 / 删除 —— 由标记决定）
    expect(screen.getByTestId('turn-changes').getAttribute('data-state')).toBe('confirm')
    expect(screen.getByTestId('turn-confirm-revert')).toBeTruthy()

    await user.click(screen.getByTestId('turn-confirm-revert'))
    expect(sent.map((s) => JSON.parse(s))).toEqual([
      { op: 'revertFiles', paths: ['src/main/a.kt'] },
    ])
  })

  it('确认态里「取消」收回来，一个字节都没发', async () => {
    const sent = captureSend()
    const user = userEvent.setup()
    render(<TurnChangesCard summary={summaryOf()} />)
    await user.click(screen.getByTestId('turn-expand'))
    await user.click(screen.getAllByRole('checkbox')[0])
    await user.click(screen.getByTestId('turn-revert-selected'))

    await user.click(screen.getByText('取消'))
    expect(screen.getByTestId('turn-changes').getAttribute('data-state')).toBe('open')
    expect(sent).toHaveLength(0)
  })

  it('Esc 取消确认态（焦点在哪都算，焦点默认在取消上）', async () => {
    captureSend()
    const user = userEvent.setup()
    render(<TurnChangesCard summary={summaryOf()} />)
    await user.click(screen.getByTestId('turn-expand'))
    await user.click(screen.getAllByRole('checkbox')[0])
    await user.click(screen.getByTestId('turn-revert-selected'))

    fireEvent.keyDown(screen.getByTestId('turn-changes'), { key: 'Escape' })
    expect(screen.getByTestId('turn-changes').getAttribute('data-state')).toBe('open')
  })

  it('标记：新 = 删除、有存档 = 恢复；「全部回滚」只收可回滚的行', async () => {
    captureSend()
    const user = userEvent.setup()
    const marks = { t1: true, t2: false }   // a.kt 有存档、b.kt 是本轮新建
    render(<TurnChangesCard summary={summaryOf()} marks={marks} />)
    await user.click(screen.getByTestId('turn-expand'))
    expect(screen.getByText('新')).toBeTruthy()

    await user.click(screen.getByTestId('turn-revert-all'))
    expect(screen.getByText('回滚 2 个文件？')).toBeTruthy()
    const rows = screen.getAllByTestId('turn-confirm-row')
    expect(rows[0].textContent).toContain('恢复')
    expect(rows[1].textContent).toContain('删除')
  })

  it('「无存档」行：勾不上，也不进「全部回滚」', async () => {
    captureSend()
    const user = userEvent.setup()
    const marks = { t2: null }   // b.kt 无存档；a.kt 没标记（unknown，可勾）
    render(<TurnChangesCard summary={summaryOf()} marks={marks} />)
    await user.click(screen.getByTestId('turn-expand'))

    const boxes = screen.getAllByRole('checkbox')
    expect((boxes[1] as HTMLInputElement).disabled).toBe(true)
    expect((boxes[0] as HTMLInputElement).disabled).toBe(false)

    await user.click(screen.getByTestId('turn-revert-all'))
    expect(screen.getByText('回滚这个文件？')).toBeTruthy()   // 只剩 a.kt 一个
  })
})

describe('忙时与已回滚', () => {
  it('忙时（running）：回滚入口禁用，悬停给出那句解释', async () => {
    captureSend()
    const user = userEvent.setup()
    render(<TurnChangesCard summary={summaryOf({ running: true })} />)
    await user.click(screen.getByTestId('turn-expand'))

    const all = screen.getByTestId('turn-revert-all') as HTMLButtonElement
    expect(all.disabled).toBe(true)
    expect(all.title).toContain('空闲')
    expect((screen.getAllByRole('checkbox')[0] as HTMLInputElement).disabled).toBe(true)
  })

  it('已回滚的行：打标、不可再勾；dirty 的再加一句', async () => {
    captureSend()
    const user = userEvent.setup()
    const revert: Record<string, RevertResultWire> = {
      'src/main/a.kt': { path: 'src/main/a.kt', ok: true, action: 'restored', dirty: true },
    }
    render(<TurnChangesCard summary={summaryOf()} revert={revert} />)
    await user.click(screen.getByTestId('turn-expand'))

    expect(screen.getByText('已回滚')).toBeTruthy()
    expect(screen.getByText('覆盖了之后的新改动')).toBeTruthy()
    expect((screen.getAllByRole('checkbox')[0] as HTMLInputElement).disabled).toBe(true)
  })

  it('失败的行：把原因摆出来（no-snapshot 是码，翻成人话）', async () => {
    captureSend()
    const user = userEvent.setup()
    const revert: Record<string, RevertResultWire> = {
      'src/main/a.kt': { path: 'src/main/a.kt', ok: false, action: 'failed', error: 'no-snapshot' },
    }
    render(<TurnChangesCard summary={summaryOf()} revert={revert} />)
    await user.click(screen.getByTestId('turn-expand'))

    expect(screen.getByText('没有存档')).toBeTruthy()
    // 失败的行还能再勾（重试是幂等的）
    expect((screen.getAllByRole('checkbox')[0] as HTMLInputElement).disabled).toBe(false)
  })
})
